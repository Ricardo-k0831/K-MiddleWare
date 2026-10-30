"""自动重试 (:8083 + 假的 Python /chat) 的端到端验收。

用法:

    python km-control-plane/scripts/verify_auto_retry.py

前置 —— 服务必须带着**短退避 + 灰度租户**启动, 否则脚本等不到重试:

    KM_RETRY_ENABLED=true \
    KM_RETRY_INTERVAL_MS=1000 \
    KM_RETRY_BASE_BACKOFF_SECONDS=2 \
    KM_RETRY_MAX_BACKOFF_SECONDS=4 \
    KM_RETRY_TENANT=retry-e2e \
    KM_PYTHON_CHAT_URL=http://127.0.0.1:18080 \
    mvn -pl km-task-service spring-boot:run

(Git Bash 里用 `export VAR=...`; PowerShell 用 `$env:VAR="..."`。)

脚本自己起一个假的 Python 运行时监听 127.0.0.1:18080, 按 intent 决定
上报什么终态, 所以不需要真的 LLM / API key, 也不需要 Python Agent 起来。

退出码: 0 = 全通过, 1 = 有用例失败, 2 = 前置检查没过。

## 这个脚本要证明什么

自动重试是**没人点按钮也会花钱**的行为, 所以比手动接口更需要被钉死:

  1. **退避真的生效**: 失败后不是立刻重试, 而是等 min(base*2^n, max)。
  2. **开新链**: 自动重试也换 trace, 事件 source 是 SYSTEM_RETRY,
     previousTraceId 接得上旧链 —— 和手动重试同一套原子动作。
  3. **次数是硬上限**: 到了 max_retry 就彻底停下, 不会无限烧钱。
  4. **派发失败可恢复**: Python 连不上 (请求确定没送达) 时, 这次执行
     被标记 FAILED, 任务回到 FAILED, 而不是永远卡在 PENDING。
  5. **没有 intent 的任务不重试**: 重建不出请求体, 自动重试无从谈起。
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
FAKE_PORT = int(os.environ.get("KM_FAKE_PYTHON_PORT", "18080"))
RUN_ID = uuid.uuid4().hex[:8]
TENANT = "retry-e2e"

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

DISPATCHES: list[dict] = []
DISPATCH_LOCK = threading.Lock()
FAKE_ERRORS: list[str] = []


def dispatches_for(task_id: str) -> list[dict]:
    with DISPATCH_LOCK:
        return [d for d in DISPATCHES if d.get("task_id") == task_id]


def patch_terminal(task_id: str, trace_id: str, status: str, detail: dict) -> None:
    """模拟 Python 运行时: PENDING -> THINKING -> 终态。"""
    for step, body in (("THINKING", {}), (status, detail)):
        code, resp = call("PATCH", f"/api/tasks/{task_id}/status", {
            "toStatus": step,
            "traceId": trace_id,
            "detail": body,
        })
        if code != 200:
            FAKE_ERRORS.append(f"{task_id} {step}: HTTP {code} {resp}")
            return


class FakePython(BaseHTTPRequestHandler):
    """只认本次运行的任务, 别的 (比如历史数据) 一律不碰。"""

    # HTTP/1.1 + Connection: close: 让 Java 的 JDK HttpClient 每次都用新连接。
    # 用例 3 要验证"端口没人监听", 连接池里残留的 keep-alive 会让错误类型变成
    # 连接被重置而不是拒绝连接, 那和真实场景不符。
    protocol_version = "HTTP/1.1"

    def _read_body(self) -> bytes:
        """读请求体。★ 必须支持 chunked ★

        JDK HttpClient (Spring 的 JdkClientHttpRequestFactory) 对未知长度的
        body 用 Transfer-Encoding: chunked 发送。只读 Content-Length 的话会
        读成空串, 然后剩下的 chunk 字节被 http.server 当成下一个请求的
        请求行 —— 表现为莫名其妙的 HTTP 400, 而不是"没收到请求"。
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
                self.rfile.read(2)  # 每个 chunk 结尾的 CRLF
            return bytes(data)
        length = int(self.headers.get("Content-Length") or 0)
        return self.rfile.read(length)

    def do_POST(self) -> None:  # noqa: N802 (http.server 的命名约定)
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
        # ★ 只处理本脚本创建的任务 ★ 调度器扫到历史 FAILED 任务时,
        # 那些请求也会打进来; 给它们回 200 但不改状态, 避免把旧数据搅乱。
        mine = message.startswith(f"auto-retry-{RUN_ID}-")
        if mine:
            with DISPATCH_LOCK:
                DISPATCHES.append(body)

        payload = b'{"reply":"fake","tool_calls":[],"task_id":null,"trace_id":null}'
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Connection", "close")
        self.end_headers()
        self.wfile.write(payload)
        self.close_connection = True

        if not mine:
            return
        if message.endswith("-ok"):
            patch_terminal(body["task_id"], body["trace_id"], "COMPLETED",
                           {"result": "假的 Python 说完成了"})
        elif message.endswith("-fail"):
            patch_terminal(body["task_id"], body["trace_id"], "FAILED",
                           {"error": "假的 Python 说又失败了"})
        # -unreachable 用例里服务已经关了, 走不到这里

    def log_message(self, fmt, *args):  # 静音
        pass


# ----------------------------------------------------------------------
#  业务操作
# ----------------------------------------------------------------------

TRACES: dict[str, str] = {}


def create_task(intent: str | None, max_retry: int = 2) -> str:
    body = {
        "userId": "U1001",
        "sessionId": f"auto-{RUN_ID}",
        "tenantId": TENANT,
        "maxRetry": max_retry,
    }
    if intent is not None:
        body["intent"] = intent
    status, resp = call("POST", "/api/tasks", body)
    assert status == 201, f"建任务失败: HTTP {status} {resp}"
    TRACES[resp["agentTaskId"]] = resp["traceId"]
    return resp["agentTaskId"]


def report(task_id: str, to_status: str, trace_id: str | None = None,
           detail: dict | None = None) -> tuple[int, dict]:
    body: dict = {"toStatus": to_status, "traceId": trace_id or TRACES[task_id]}
    if detail is not None:
        body["detail"] = detail
    return call("PATCH", f"/api/tasks/{task_id}/status", body)


def fail_once(task_id: str) -> None:
    trace = TRACES[task_id]
    status, resp = report(task_id, "THINKING", trace)
    assert status == 200, f"上报 THINKING 失败: HTTP {status} {resp}"
    status, resp = report(task_id, "FAILED", trace, {"error": "MCP 网关 500"})
    assert status == 200, f"上报 FAILED 失败: HTTP {status} {resp}"


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


# ----------------------------------------------------------------------
#  用例
# ----------------------------------------------------------------------


def case_auto_retry_after_backoff() -> None:
    print("\n用例 1: 退避到期 -> 自动开新链 -> 派发 -> New Python 上报完成")

    task_id = create_task(f"auto-retry-{RUN_ID}-ok", max_retry=2)
    old_trace = TRACES[task_id]
    fail_once(task_id)

    # 退避 2s: 半秒内绝不该有请求。没有这条, "退避生效"只是代码里的一句话。
    time.sleep(0.5)
    check("失败后 0.5s 内没有立刻派发 (退避生效)",
          dispatches_for(task_id) == [], str(dispatches_for(task_id)))

    sent = wait_until(lambda: dispatches_for(task_id) or None, timeout=25)
    if not sent:
        check("退避到期后自动派发到 /chat", False,
              "25s 内没等到派发。检查服务是否用 KM_RETRY_ENABLED=true 和 "
              "KM_RETRY_TENANT=retry-e2e 启动, 以及 KM_PYTHON_CHAT_URL 是否指向 "
              f"http://127.0.0.1:{FAKE_PORT}")
        return
    check("退避到期后自动派发到 /chat", True)

    body = sent[0]
    new_trace = body.get("trace_id")
    check("派发用的是新 trace", bool(new_trace) and new_trace != old_trace,
          f"old={old_trace} new={new_trace}")
    check("派发带着原始 intent", body.get("message") == f"auto-retry-{RUN_ID}-ok", str(body))
    check("派发的 task_id 对得上", body.get("task_id") == task_id, str(body))
    check("派发的 tenant_id 对得上", body.get("tenant_id") == TENANT, str(body))

    # 假 Python 已经上报 COMPLETED, 等 Java 收下
    done = wait_until(lambda: _task_if(task_id, "COMPLETED"), timeout=10)
    check("自动重试后任务跑到 COMPLETED", bool(done), str(task_of(task_id)))
    if done:
        check("滚动指针停在新链", done.get("traceId") == new_trace,
              f"{done.get('traceId')} vs {new_trace}")

    events = events_of(task_id)
    auto = [e for e in events
            if e.get("fromStatus") == "FAILED" and e.get("toStatus") == "PENDING"]
    check("事件表里有一条 FAILED -> PENDING", len(auto) == 1, str(auto))
    if auto:
        e = auto[0]
        check("自动重试的 source 是 SYSTEM_RETRY", e.get("source") == "SYSTEM_RETRY",
              str(e.get("source")))
        detail = e.get("detail") or {}
        check("自动重试记了 reason=自动重试", detail.get("reason") == "自动重试", str(detail))
        check("自动重试记了 previousTraceId", detail.get("previousTraceId") == old_trace,
              str(detail))
        check("自动重试开的是第 2 次执行", detail.get("attemptNo") == 2, str(detail))

    check("假 Python 的上报没有被拒", FAKE_ERRORS == [], str(FAKE_ERRORS))


def case_max_retry_stops() -> None:
    print("\n用例 2: maxRetry=1 —— 自动重试一次后彻底停下")

    task_id = create_task(f"auto-retry-{RUN_ID}-fail", max_retry=1)
    fail_once(task_id)

    sent = wait_until(lambda: dispatches_for(task_id) or None, timeout=25)
    check("第一次自动重试发生了", bool(sent), "没等到派发")
    if not sent:
        return

    failed_again = wait_until(lambda: _task_if(task_id, "FAILED"), timeout=10)
    check("假 Python 上报 FAILED 后任务回到 FAILED", bool(failed_again),
          str(task_of(task_id)))

    # 退避上限是 4s; 等两个完整周期, 确认不会再发起
    time.sleep(6)
    check("次数用尽后没有再派发 (不会无限烧钱)",
          len(dispatches_for(task_id)) == 1, f"实际 {len(dispatches_for(task_id))} 次")
    after = task_of(task_id)
    check("retryCount 停在 1", after.get("retryCount") == 1, str(after.get("retryCount")))
    check("状态仍是 FAILED", after.get("status") == "FAILED", str(after.get("status")))
    check("finishedAt 重新有值", bool(after.get("finishedAt")))


def case_python_unreachable() -> None:
    print("\n用例 3: Python 不可达 —— 自动重试把这次执行标记 FAILED, 而不是卡在 PENDING")

    # ★ 关掉假 Python ★ 端口没人监听 = 连接被拒, 属于"确定没送达"。
    stop_fake()
    time.sleep(0.3)

    task_id = create_task(f"auto-retry-{RUN_ID}-unreachable", max_retry=1)
    fail_once(task_id)

    def dispatch_closed() -> dict | None:
        task = task_of(task_id)
        # 自动重试已把任务拉回 PENDING (retryCount=1), 然后派发失败又写回 FAILED
        if task.get("retryCount") == 1 and task.get("status") == "FAILED":
            return task
        return None

    back_to_failed = wait_until(dispatch_closed, timeout=25)
    check("派发失败后任务回到 FAILED", bool(back_to_failed), str(task_of(task_id)))
    if not back_to_failed:
        return

    events = events_of(task_id)
    opened = [e for e in events
              if e.get("fromStatus") == "FAILED" and e.get("toStatus") == "PENDING"]
    closed = [e for e in events
              if e.get("fromStatus") == "PENDING" and e.get("toStatus") == "FAILED"]
    check("先有一次 FAILED -> PENDING (SYSTEM_RETRY)",
          len(opened) == 1 and opened[0].get("source") == "SYSTEM_RETRY", str(opened))
    check("紧接着有一次 PENDING -> FAILED (SYSTEM_RETRY)",
          len(closed) == 1 and closed[0].get("source") == "SYSTEM_RETRY", str(closed))
    if closed:
        detail = closed[0].get("detail") or {}
        check("失败原因标记 phase=DISPATCH", detail.get("phase") == "DISPATCH", str(detail))
        check("失败原因里带连接错误",
              "Connection" in str(detail.get("error")) or "连接" in str(detail.get("error")),
              str(detail))

    time.sleep(3)
    after = task_of(task_id)
    check("次数已用尽, 不会继续重试", after.get("retryCount") == 1
          and after.get("status") == "FAILED", str(after))


def case_blank_intent_not_retried() -> None:
    print("\n用例 4: 没有 intent 的任务不会被自动重试")

    task_id = create_task(None, max_retry=2)
    fail_once(task_id)
    time.sleep(3.5)

    after = task_of(task_id)
    check("没有 intent 的任务停在 FAILED", after.get("status") == "FAILED",
          str(after.get("status")))
    check("retryCount 没有被自动增加", after.get("retryCount") == 0,
          str(after.get("retryCount")))
    check("没有产生重试事件",
          not any(e.get("fromStatus") == "FAILED" and e.get("toStatus") == "PENDING"
                  for e in events_of(task_id)))


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
    print(f"目标: {BASE}   假 Python: 127.0.0.1:{FAKE_PORT}   本次运行 ID: {RUN_ID}")

    problems = preflight()
    if problems:
        for p in problems:
            print(f"  [前置失败] {p}")
        return 2

    try:
        case_auto_retry_after_backoff()
        case_max_retry_stops()
        case_blank_intent_not_retried()
        # 这个用例会关掉假 Python, 放最后
        case_python_unreachable()
    finally:
        stop_fake()

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
