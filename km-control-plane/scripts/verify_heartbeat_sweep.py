"""心跳兜底 (:8083 + 假的 Python /chat + /alive) 的端到端验收。

用法:

    python km-control-plane/scripts/verify_heartbeat_sweep.py

前置 —— 服务必须带着**短阈值 + 灰度租户 + 自动重试**启动, 否则脚本等不到
探活, 也等不到"关链之后的重试":

    export KM_RETRY_ENABLED=true
    export KM_RETRY_INTERVAL_MS=1000
    export KM_RETRY_BASE_BACKOFF_SECONDS=1
    export KM_RETRY_MAX_BACKOFF_SECONDS=2
    export KM_RETRY_TENANT=heartbeat-e2e
    export KM_HEARTBEAT_ENABLED=true
    export KM_HEARTBEAT_INTERVAL_MS=1000
    export KM_HEARTBEAT_STALE_AFTER_SECONDS=3
    export KM_HEARTBEAT_TENANT=heartbeat-e2e
    export KM_PYTHON_CHAT_URL=http://127.0.0.1:18081
    mvn -pl km-task-service spring-boot:run

(PowerShell 用 `$env:VAR="..."`。)

★ stale 必须是 3 秒这种小值 ★ 默认 120s, 脚本要等两分钟才看得到第一轮探活。
反过来, 这也说明阈值本身只是"发现延迟", 判定依据始终是探活。

脚本自己起一个假的 Python 运行时监听 127.0.0.1:18081, 按 intent 后缀决定
怎么响应 /chat 和 /alive, 所以不需要真的 LLM / API key。

退出码: 0 = 全通过, 1 = 有用例失败, 2 = 前置检查没过。

## 这个脚本要证明什么

心跳兜底是**会自己把任务判死 (进而花钱重试)** 的机制, 所以每一条边界都值
得被钉死:

  1. **心跳正常 -> 根本不该被探活**: 探活是同步 HTTP, 每多问一次都是成本,
     问错一次就是一条任务被误杀。
  2. **探活说活着 -> 不动状态, 并把心跳时钟往后推**: 否则长任务每一轮都
     会被重新探活, 而且永远留在可疑集合里。
  3. **探活说没在跑 -> 关成 FAILED, source=SYSTEM_HEARTBEAT**: 关链必须
     走 reportStatus, 这样事件、闭链、审计和状态是同一个事务。
  4. **关链之后自动重试能把它救回来**: 兜底的价值在于"任务不再永远卡住",
     而不是"任务多了一条失败记录"。
  5. **探活问不出来 -> 什么都不做**: Python 挂了 / 网络抖动 / 版本没对上
     都不能当成"死了"。宁可晚一点关, 不能误杀。
  6. **心跳接口本身的契约**: 204 / 400 / 404 / 409, 以及心跳【不写事件】。
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = os.environ.get("KM_TASK_BASE", "http://localhost:8083")
FAKE_PORT = int(os.environ.get("KM_FAKE_PYTHON_PORT", "18081"))
RUN_ID = uuid.uuid4().hex[:8]
TENANT = "heartbeat-e2e"

PASSED: list[str] = []
FAILED: list[str] = []


# ----------------------------------------------------------------------
#  HTTP 客户端
# ----------------------------------------------------------------------


def call(method: str, path: str, body: dict | None = None,
         timeout: float = 20) -> tuple[int, dict]:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw}


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        PASSED.append(name)
        print(f"  [OK]   {name}")
    else:
        FAILED.append(name)
        print(f"  [FAIL] {name}")
        if detail:
            for line in str(detail).splitlines():
                print(f"         {line}")


def wait_until(fn, timeout: float = 25, interval: float = 0.25):
    deadline = time.time() + timeout
    while time.time() < deadline:
        value = fn()
        if value:
            return value
        time.sleep(interval)
    return None


# ----------------------------------------------------------------------
#  假的 Python 运行时
# ----------------------------------------------------------------------

DISPATCHES: list[dict] = []  # 收到的 /chat 请求体
MODES: dict[str, str] = {}   # trace -> "alive" | "dead" | "error"
PROBES: dict[str, int] = {}  # trace -> 被探活次数
PROBE_TIMES: dict[str, list[float]] = {}  # trace -> 每次被探活的时刻
HEARTBEATS: dict[str, int] = {}  # trace -> 假 Python 主动发出的心跳次数
LOCK = threading.Lock()
FAKE_ERRORS: list[str] = []


def dispatches_for(task_id: str) -> list[dict]:
    with LOCK:
        return [d for d in DISPATCHES if d.get("task_id") == task_id]


def probes_for(trace_id: str) -> int:
    with LOCK:
        return PROBES.get(trace_id, 0)


def probe_times(trace_id: str) -> list[float]:
    with LOCK:
        return list(PROBE_TIMES.get(trace_id, []))


def patch_status(task_id: str, trace_id: str, to_status: str, detail: dict) -> bool:
    code, resp = call("PATCH", f"/api/tasks/{task_id}/status", {
        "toStatus": to_status,
        "traceId": trace_id,
        "detail": detail,
        "source": "PYTHON_RUNTIME",
    })
    if code != 200:
        FAKE_ERRORS.append(f"{task_id} {to_status}: HTTP {code} {resp}")
        return False
    return True


def beat_for(task_id: str, trace_id: str, seconds: float = 6.0,
             interval: float = 0.4) -> None:
    """模拟一个正常执行: 先报 THINKING, 再持续心跳, 最后报 COMPLETED。

    心跳间隔 0.4s 远小于 Java 侧 stale(3s), 所以整段执行期间都不该被探活。
    """
    patch_status(task_id, trace_id, "THINKING", {"phase": "entry"})
    count = 0
    deadline = time.time() + seconds
    while time.time() < deadline:
        code, resp = call("POST", f"/api/tasks/{task_id}/heartbeat",
                          {"traceId": trace_id})
        if code != 204:
            FAKE_ERRORS.append(f"心跳被拒: HTTP {code} {resp}")
            break
        count += 1
        time.sleep(interval)
    with LOCK:
        HEARTBEATS[trace_id] = count
    patch_status(task_id, trace_id, "COMPLETED", {"result": "假的 Python 说完成了"})


class FakePython(BaseHTTPRequestHandler):
    """只认本次运行的任务, 别的 (比如历史数据) 一律不碰。"""

    # HTTP/1.1 + Connection: close: 让 Java 的 JDK HttpClient 每次用新连接,
    # 避免连接池里的 keep-alive 把"端口没人监听"变成"连接被重置"。
    protocol_version = "HTTP/1.1"

    def _read_body(self) -> bytes:
        """读请求体。★ 必须支持 chunked ★

        Spring 的 JdkClientHttpRequestFactory 对未知长度的 body 用
        Transfer-Encoding: chunked。只读 Content-Length 会读成空串, 剩下的
        chunk 字节被当成下一个请求的请求行 —— 表现为莫名其妙的 400。
        """
        if "chunked" in (self.headers.get("Transfer-Encoding") or "").lower():
            data = bytearray()
            while True:
                size_line = self.rfile.readline().strip()
                size = int(size_line.split(b";")[0] or b"0", 16)
                if size == 0:
                    self.rfile.readline()
                    break
                data += self.rfile.read(size)
                self.rfile.read(2)
            return bytes(data)
        return self.rfile.read(int(self.headers.get("Content-Length") or 0))

    def _json(self, code: int, payload: dict) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(raw)
        self.close_connection = True

    # ---------------- /chat ----------------

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/chat":
            self.send_error(404)
            return
        raw = self._read_body()
        try:
            body = json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as e:
            FAKE_ERRORS.append(f"无法解析请求体: {e}")
            self.send_error(400)
            return

        message = body.get("message") or ""
        mine = message.startswith(f"heartbeat-e2e-{RUN_ID}-")
        task_id = body.get("task_id")
        trace_id = body.get("trace_id")
        if mine:
            with LOCK:
                DISPATCHES.append(body)

        # 先回响应再做事: 真实的 /chat 是长请求, 但这里的假实现用后台线程
        # 模拟"执行还在继续", 不能让 Java 的派发线程等着。
        self._json(200, {"reply": "fake", "tool_calls": [],
                         "task_id": None, "trace_id": None})
        if not mine:
            return

        if message.endswith("-beat"):
            with LOCK:
                MODES[trace_id] = "alive"
            threading.Thread(
                target=beat_for, args=(task_id, trace_id), daemon=True
            ).start()

        elif message.endswith("-silent"):
            # "进程活着但推不出心跳" (比如网络单向不通、事件循环卡在别处)。
            # 它不该被关 —— 这正是探活存在的理由。
            with LOCK:
                MODES[trace_id] = "alive"

        elif message.endswith("-dead"):
            # 第一次派发: Python 那边其实没有这条执行 (进程崩了 / 收到就没了)。
            # 第二次派发 (自动重试) 才正常跑完 —— 用来证明"兜底能救回来"。
            attempt_no = len(dispatches_for(task_id))
            if attempt_no == 1:
                with LOCK:
                    MODES[trace_id] = "dead"
            else:
                with LOCK:
                    MODES[trace_id] = "alive"
                patch_status(task_id, trace_id, "THINKING", {"phase": "entry"})
                patch_status(task_id, trace_id, "COMPLETED",
                             {"result": "重试后完成了"})

        elif message.endswith("-probe-error"):
            # 探活接口 500 / 版本没对上 —— 结论必须是"未知", 不是"死了"。
            with LOCK:
                MODES[trace_id] = "error"

    # ---------------- /tasks/{trace}/alive ----------------

    def do_GET(self) -> None:  # noqa: N802
        parts = self.path.strip("/").split("/")
        # /tasks/{trace_id}/alive
        if len(parts) != 3 or parts[0] != "tasks" or parts[2] != "alive":
            self.send_error(404)
            return
        trace_id = parts[1]
        with LOCK:
            PROBES[trace_id] = PROBES.get(trace_id, 0) + 1
            PROBE_TIMES.setdefault(trace_id, []).append(time.time())
            mode = MODES.get(trace_id)

        if mode == "alive":
            self._json(200, {"alive": True, "trace_id": trace_id, "elapsed_seconds": 1.0})
        elif mode == "dead":
            self._json(200, {"alive": False, "trace_id": trace_id, "elapsed_seconds": None})
        elif mode == "error":
            self._json(500, {"error": "fake probe failure"})
        else:
            # ★ 不认识的 trace 回 404, 不回 alive=false ★
            # 404 在 Java 侧是 UNKNOWN (可能是 Python 版本旧了), 什么都不做;
            # 回 false 会让"本脚本没登记过的执行"被批量判死。
            self.send_error(404)

    def log_message(self, fmt, *args):  # 静音
        pass


# ----------------------------------------------------------------------
#  业务操作
# ----------------------------------------------------------------------

TRACES: dict[str, str] = {}


def create_task(intent: str | None, max_retry: int = 2) -> str:
    body = {
        "userId": "U1001",
        "sessionId": f"hb-{RUN_ID}",
        "tenantId": TENANT,
        "maxRetry": max_retry,
    }
    if intent is not None:
        body["intent"] = intent
    status, resp = call("POST", "/api/tasks", body)
    assert status == 201, f"建任务失败: HTTP {status} {resp}"
    TRACES[resp["agentTaskId"]] = resp["traceId"]
    return resp["agentTaskId"]


def report(task_id: str, to_status: str, detail: dict | None = None,
           trace_id: str | None = None) -> tuple[int, dict]:
    """上报状态。

    ★ trace 默认用建任务时的那个, 但自动重试后必须显式传新 trace ★
    重试会滚动指针 (agent_task.trace_id) 并开一条新执行, 拿旧 trace 上报
    会稳定地 409 —— 用例收尾时就踩过, 任务因此一直留在 PENDING。
    """
    body: dict = {"toStatus": to_status, "traceId": trace_id or TRACES[task_id]}
    if detail is not None:
        body["detail"] = detail
    return call("PATCH", f"/api/tasks/{task_id}/status", body)


def task_of(task_id: str) -> dict:
    status, resp = call("GET", f"/api/tasks/{task_id}")
    assert status == 200, f"查任务失败: HTTP {status} {resp}"
    return resp


def events_of(task_id: str) -> list[dict]:
    status, resp = call("GET", f"/api/tasks/{task_id}/events")
    assert status == 200, f"查事件失败: HTTP {status} {resp}"
    return resp


def _task_if(task_id: str, status: str) -> dict | None:
    task = task_of(task_id)
    return task if task.get("status") == status else None


def fail_once(task_id: str) -> None:
    """先把任务打到 FAILED, 让 RetryScheduler 派发一次 /chat。

    心跳兜底只关心"别人派发出去的执行" —— 手工建的 PENDING 任务不在它的
    扫描范围里 (见 HeartbeatSweeper 的类注释), 所以用例都得先走一次派发。
    """
    status, resp = report(task_id, "THINKING")
    assert status == 200, f"上报 THINKING 失败: HTTP {status} {resp}"
    status, resp = report(task_id, "FAILED", {"error": "先失败一次, 触发自动重试派发"})
    assert status == 200, f"上报 FAILED 失败: HTTP {status} {resp}"


def wait_dispatch(task_id: str, timeout: float = 25) -> dict | None:
    """等"最后一条派发" —— 返回请求体本身, 不是列表。"""
    return wait_until(
        lambda: (dispatches_for(task_id) or [None])[-1], timeout=timeout
    )


# ----------------------------------------------------------------------
#  用例
# ----------------------------------------------------------------------


def case_beat_prevents_probe() -> None:
    print("\n用例 1: 正常心跳 -> 全程不该被探活")

    task_id = create_task(f"heartbeat-e2e-{RUN_ID}-beat", max_retry=2)
    fail_once(task_id)

    body = wait_dispatch(task_id)
    check("重试把任务派发到了 /chat", bool(body),
          "25s 内没等到派发。确认服务是用脚本头部那组环境变量启动的 "
          "(KM_RETRY_ENABLED / KM_RETRY_TENANT / KM_PYTHON_CHAT_URL)")
    if not body:
        return
    trace = body["trace_id"]

    done = wait_until(lambda: _task_if(task_id, "COMPLETED"), timeout=20)
    check("心跳期间任务跑到了 COMPLETED", bool(done), str(task_of(task_id)))
    check("正常执行全程没有被探活", probes_for(trace) == 0,
          f"被探活 {probes_for(trace)} 次 —— 心跳没能把证据时钟推后, "
          "检查 KM_HEARTBEAT_STALE_AFTER_SECONDS 是否比脚本里的心跳间隔大得多")
    with LOCK:
        beats = HEARTBEATS.get(trace, 0)
    check("假 Python 确实发过心跳", beats >= 5, f"只发了 {beats} 次")
    check("假 Python 的心跳没有被拒", not any("心跳被拒" in e for e in FAKE_ERRORS),
          str(FAKE_ERRORS))


def case_alive_refreshes_without_closing() -> None:
    print("\n用例 2: 没有心跳但探活说 alive -> 不动状态, 只刷新证据")

    task_id = create_task(f"heartbeat-e2e-{RUN_ID}-silent", max_retry=2)
    fail_once(task_id)

    body = wait_dispatch(task_id)
    check("重试把任务派发到了 /chat", bool(body), "没等到派发")
    if not body:
        return
    trace = body["trace_id"]

    probed = wait_until(lambda: probes_for(trace) or None, timeout=15)
    check("证据过期后 Java 主动探活了", bool(probed), "15s 内没看到 /alive 请求")
    if not probed:
        return

    task = task_of(task_id)
    check("探活 alive 时任务没有被关成 FAILED", task.get("status") != "FAILED",
          str(task))
    check("任务停在 PENDING (awaiting Python 的后续上报)",
          task.get("status") == "PENDING", str(task.get("status")))

    # ★ 用两次探活的间隔判断 ALIVE 有没有刷新心跳 ★
    # 刷新了: 下一次要再等满一个 stale(3s); 没刷新: 每一轮扫描 (1s) 都会
    # 探一次。只看"次数 >= 2"两种实现都能过, 间隔才分得开。
    again = wait_until(lambda: len(probe_times(trace)) >= 2 or None, timeout=12)
    check("探活命中的事实被记下来了 (下一轮还会探活)", bool(again),
          f"探活 {probes_for(trace)} 次")
    if again:
        times = probe_times(trace)
        gap = times[1] - times[0]
        check("两次探活之间隔了一个 stale 周期 (ALIVE 刷新了心跳)", gap >= 2.0,
              f"间隔 {gap:.2f}s, 接近扫描间隔说明探活结果没有被记成新的存活证据")

    # 收尾: 让这条执行正常结束, 免得它作为"卡住的任务"留在库里影响下次运行。
    # 必须用【派发出去的那个 trace】—— 任务已经从 FAILED 被自动重试开过新链了。
    report(task_id, "THINKING", trace_id=trace)
    report(task_id, "COMPLETED", {"result": "用例收尾"}, trace_id=trace)


def case_dead_closes_then_retry_saves() -> None:
    print("\n用例 3: 探活说没在跑 -> 关成 FAILED(SYSTEM_HEARTBEAT) -> 自动重试救回来")

    task_id = create_task(f"heartbeat-e2e-{RUN_ID}-dead", max_retry=2)
    fail_once(task_id)

    first = wait_dispatch(task_id)
    check("重试把任务派发到了 /chat", bool(first), "没等到派发")
    if not first:
        return
    dead_trace = first["trace_id"]

    closed = wait_until(lambda: _task_if(task_id, "FAILED"), timeout=20)
    check("探活 NOT_ALIVE 后任务被关成 FAILED", bool(closed), str(task_of(task_id)))
    if not closed:
        return
    check("关链用的是新链的 trace", closed.get("traceId") == dead_trace,
          f"{closed.get('traceId')} vs {dead_trace}")

    events = events_of(task_id)
    closing = [e for e in events
               if e.get("fromStatus") == "PENDING" and e.get("toStatus") == "FAILED"]
    check("事件表里有一条 PENDING -> FAILED", len(closing) == 1, str(closing))
    if closing:
        e = closing[0]
        check("关链的 source 是 SYSTEM_HEARTBEAT",
              e.get("source") == "SYSTEM_HEARTBEAT", str(e.get("source")))
        detail = e.get("detail") or {}
        check("关链详细里标了 phase=HEARTBEAT",
              detail.get("phase") == "HEARTBEAT", str(detail))
        check("关链详细里带着 trace 便于排查",
              dead_trace in str(detail.get("error")), str(detail))
        check("关链详细里带着证据时间",
              "lastEvidenceAt" in detail and "dispatchedAt" in detail, str(detail))

    # ★ 兜底的价值在这里: 关链不是终点, 而是让任务重新进入重试 ★
    saved = wait_until(lambda: _task_if(task_id, "COMPLETED"), timeout=25)
    check("关链之后自动重试把它救回来了", bool(saved), str(task_of(task_id)))
    if saved:
        check("救回来的是新的一条执行", saved.get("traceId") != dead_trace,
              f"{saved.get('traceId')} vs {dead_trace}")

    heart_events = [e for e in events_of(task_id)
                    if e.get("source") == "SYSTEM_HEARTBEAT"]
    check("SYSTEM_HEARTBEAT 只落在关链那一条上", len(heart_events) == 1,
          str(heart_events))


def case_probe_error_does_nothing() -> None:
    print("\n用例 4: 探活问不出来 (500) -> 什么都不做")

    task_id = create_task(f"heartbeat-e2e-{RUN_ID}-probe-error", max_retry=2)
    fail_once(task_id)

    body = wait_dispatch(task_id)
    check("重试把任务派发到了 /chat", bool(body), "没等到派发")
    if not body:
        return
    trace = body["trace_id"]

    probed = wait_until(lambda: probes_for(trace) or None, timeout=15)
    check("证据过期后 Java 主动探活了", bool(probed), "15s 内没看到 /alive 请求")

    time.sleep(5)
    task = task_of(task_id)
    check("探活 5xx 不会被当成死亡", task.get("status") == "PENDING", str(task))
    check("没有产生 SYSTEM_HEARTBEAT 的关链事件",
          not any(e.get("source") == "SYSTEM_HEARTBEAT" for e in events_of(task_id)))

    # 同用例 2: 收尾成终态, 别给下一次运行留下"卡住的任务"。
    report(task_id, "THINKING", trace_id=trace)
    report(task_id, "COMPLETED", {"result": "用例收尾"}, trace_id=trace)


def case_heartbeat_api_contract() -> None:
    print("\n用例 5: 心跳接口契约 (204 / 400 / 404 / 409), 且不写事件")

    task_id = create_task(None)
    trace = TRACES[task_id]

    code, _ = call("POST", f"/api/tasks/{task_id}/heartbeat", {"traceId": trace})
    check("正确 trace 的心跳返回 204", code == 204, f"HTTP {code}")

    code, resp = call("POST", f"/api/tasks/{task_id}/heartbeat", {"traceId": "0" * 32})
    check("trace 不属于该任务时返回 409", code == 409, f"HTTP {code} {resp}")

    code, resp = call("POST", f"/api/tasks/{task_id}/heartbeat", {})
    check("缺 traceId 时返回 400", code == 400, f"HTTP {code} {resp}")

    code, resp = call("POST", f"/api/tasks/__nothing_{RUN_ID}/heartbeat",
                      {"traceId": trace})
    check("任务不存在时返回 404", code == 404, f"HTTP {code} {resp}")

    before = len(events_of(task_id))
    for _ in range(3):
        call("POST", f"/api/tasks/{task_id}/heartbeat", {"traceId": trace})
    # ★ 心跳不是状态迁移 ★ 每 15s 一条事件会把事件表刷成心跳日志,
    # 真正要看的状态变迁就淹没在里面了。
    check("心跳不写事件行", len(events_of(task_id)) == before,
          f"{before} -> {len(events_of(task_id))}")

    report(task_id, "THINKING")
    report(task_id, "COMPLETED")
    code, resp = call("POST", f"/api/tasks/{task_id}/heartbeat", {"traceId": trace})
    check("执行结束后心跳返回 409", code == 409, f"HTTP {code} {resp}")


# ----------------------------------------------------------------------
#  入口
# ----------------------------------------------------------------------

_fake: ThreadingHTTPServer | None = None


def start_fake() -> None:
    global _fake
    _fake = ThreadingHTTPServer(("127.0.0.1", FAKE_PORT), FakePython)
    threading.Thread(target=_fake.serve_forever, daemon=True).start()


def stop_fake() -> None:
    global _fake
    if _fake is not None:
        _fake.shutdown()
        _fake.server_close()
        _fake = None


def preflight() -> list[str]:
    problems: list[str] = []
    try:
        status, resp = call("GET", f"/api/tasks/__probe__{RUN_ID}")
        if status != 404 or resp.get("error") != "TASK_NOT_FOUND":
            problems.append(f"探活返回 {status} {resp} —— 服务起来了但不是这个任务服务?")
    except Exception as ex:
        problems.append(
            f"km-task-service (:8083) 不可达: {type(ex).__name__}: {ex}\n"
            "       先确认服务已启动, 且 MySQL 可连"
        )
    try:
        start_fake()
    except OSError as ex:
        stop_fake()
        problems.append(
            f"无法监听 127.0.0.1:{FAKE_PORT}: {ex}\n"
            "       端口被占用? 换 KM_FAKE_PYTHON_PORT 并同步改服务的 "
            "KM_PYTHON_CHAT_URL"
        )
    return problems


def main() -> int:
    print(f"目标: {BASE}   假 Python: 127.0.0.1:{FAKE_PORT}   本次运行 ID: {RUN_ID}"
          f"   租户: {TENANT}")

    problems = preflight()
    if problems:
        for p in problems:
            print(f"  [前置失败] {p}")
        return 2

    try:
        case_heartbeat_api_contract()
        case_beat_prevents_probe()
        case_alive_refreshes_without_closing()
        case_dead_closes_then_retry_saves()
        case_probe_error_does_nothing()
    finally:
        stop_fake()
        if FAKE_ERRORS:
            print("\n假 Python 侧记录到的异常:")
            for e in FAKE_ERRORS:
                print(f"  - {e}")

    total = len(PASSED) + len(FAILED)
    print(f"\n结果: {len(PASSED)}/{total} 通过")
    if FAILED:
        print("失败项:")
        for name in FAILED:
            print(f"  - {name}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
