"""验证 verify_task_service.py 本身是不是个可信的闸门。

用法 (不需要 MySQL, 不需要 Docker):

    python km-control-plane/scripts/selftest_verify_task_service.py

## 为什么需要这个

verify_task_service.py 是"km-task-service 到底行不行"的判据。判据本身
如果写错了, 后果比没有验收更糟 —— 它会给出一个【看着可信】的错误答案:

  - 断言写松了  -> 假阴性: 服务有 bug 却报"全通过"
  - 脚本自己崩了 -> 假阳性: 报一堆 FAIL, 让人去查根本不存在的问题

而这两件事在真实环境里都【很难和真 bug 区分开】。所以这里用一个内存版假服务
把真脚本跑一遍, 再逐个注入变异, 确认每条断言真的会红。

## 假服务不是"实现的一部分"

它只实现契约 (状态机规则 / 幂等 / CAS / 错误码), 不碰数据库。
它的唯一用途是给断言提供"可控的正确答案"和"可控的错误答案"。

## 变异测试

每个变异对应真服务里一种【真实可能发生】的退化:

  cas=False              UPDATE 少了 status 条件 -> 并发覆盖
  write_event_on_noop    幂等分支顺手写了事件 -> 事件表被重放淹没
  terminal_unlocked      终态可离开 -> 已完成的任务被改坏
  allow_illegal          不做流转校验 -> 状态机退化成字符串字段

每个变异都必须让【对应的那一条】断言红, 而且只让它红。
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

HERE = os.path.dirname(os.path.abspath(__file__))
VERIFIER = os.path.join(HERE, "verify_task_service.py")

ALL_STATUSES = {"PENDING", "THINKING", "TOOL_CALLING", "COMPLETED", "FAILED", "CANCELLED"}
TERMINAL = {"COMPLETED", "FAILED", "CANCELLED"}

# 和 TaskStatusMachine 一致
ALLOWED: dict[str, set[str]] = {
    "PENDING": {"THINKING", "FAILED", "CANCELLED"},
    "THINKING": {"TOOL_CALLING", "COMPLETED", "FAILED", "CANCELLED"},
    "TOOL_CALLING": {"THINKING", "COMPLETED", "FAILED", "CANCELLED"},
}


class FakeService:
    """km-task-service 的内存版。flags 控制要注入哪种退化。"""

    def __init__(self, **flags):
        self.cas = flags.get("cas", True)
        self.write_event_on_noop = flags.get("write_event_on_noop", False)
        self.terminal_unlocked = flags.get("terminal_unlocked", False)
        self.allow_illegal = flags.get("allow_illegal", False)

        self.tasks: dict[str, dict] = {}
        self.events: dict[str, list[dict]] = {}
        self.lock = threading.Lock()

    # -- 状态机 ---------------------------------------------------------

    def _allowed(self, frm: str, to: str) -> bool:
        if self.allow_illegal:
            return True
        if frm in TERMINAL:
            # 正常: 终态在流转表里没有 key, 所以一律 False。
            # 变异: 假装表里给终态留了出边 —— 注意不能只"跳过守卫",
            # 那样 ALLOWED.get() 仍然返回 false, 变异就变成了空操作,
            # 自检会给出"没抓到"的假象 (这个坑真踩过一次)。
            return self.terminal_unlocked
        return to in ALLOWED.get(frm, set())

    def _append(self, task_id: str, frm: str | None, to: str, source: str, detail: dict) -> None:
        self.events[task_id].append({
            "id": len(self.events[task_id]) + 1,
            "agentTaskId": task_id,
            "fromStatus": frm,
            "toStatus": to,
            "source": source,
            "detail": detail,
            "createdAt": "2026-10-09T00:00:00",
        })

    # -- 业务 -----------------------------------------------------------

    def create(self, body: dict) -> tuple[int, dict]:
        if not (body.get("userId") or "").strip():
            return 400, {"error": "INVALID_REQUEST", "message": "userId: 不能为空"}
        task_id = uuid.uuid4().hex
        self.tasks[task_id] = {
            "agentTaskId": task_id,
            # 生成点只有这里 —— 和真服务一样, 建任务时确定本次执行的身份。
            # 上报时不带它 / 带了别的任务的, 都必须被拒 (见 report)。
            "traceId": uuid.uuid4().hex,
            "tenantId": body.get("tenantId") or "default",
            "userId": body["userId"],
            "sessionId": body.get("sessionId"),
            "intent": body.get("intent"),
            "status": "PENDING",
            "result": None,
            "errorMsg": None,
            "retryCount": 0,
            "maxRetry": body.get("maxRetry") if body.get("maxRetry") is not None else 3,
            "createdAt": "2026-10-09T00:00:00",
            "updatedAt": "2026-10-09T00:00:00",
            "finishedAt": None,
        }
        self.events[task_id] = []
        self._append(task_id, None, "PENDING", "JAVA_GATEWAY", {})
        return 201, self.tasks[task_id]

    def report(self, task_id: str, body: dict) -> tuple[int, dict]:
        to = body.get("toStatus")
        trace_id = (body.get("traceId") or "").strip()
        if not trace_id:
            # 真服务里这是 Bean Validation 拦下的, 返回 INVALID_REQUEST
            return 400, {"error": "INVALID_REQUEST", "message": "traceId: 不能为空"}
        if to not in ALL_STATUSES:
            return 400, {"error": "MALFORMED_BODY", "message": f"未知的状态值: {to!r}"}
        detail = body.get("detail") or {}

        if task_id not in self.tasks:
            return 404, {"error": "TASK_NOT_FOUND", "message": task_id}

        # ★ 执行身份 ★ 必须是这个任务当前那次执行的 trace。
        # 拿别的任务的 trace 上报, 事件会被记到本任务头上 —— 必须 409。
        if self.tasks[task_id].get("traceId") != trace_id:
            return 409, {"error": "EXECUTION_MISMATCH",
                         "message": f"执行身份不匹配: task={task_id} trace={trace_id}"}

        # ★ 变异点: 关掉 CAS 时, "读"和"写"之间刻意留一个窗口,
        #   让并发请求真的能互相覆盖。真服务里这个窗口是天然存在的。
        task = self.tasks[task_id]
        frm = task["status"]
        if not self.cas:
            time.sleep(0.02)

        with self.lock:
            if self.cas:
                # CAS: 落库前重新确认状态没被别人改过
                if self.tasks[task_id]["status"] != frm:
                    return 409, {"error": "ILLEGAL_TRANSITION",
                                 "message": "并发冲突: 状态在读取之后已被其他请求修改"}

            if frm == to:
                if self.write_event_on_noop:  # ★ 变异点
                    self._append(task_id, frm, to, "PYTHON_RUNTIME", detail)
                return 200, self.tasks[task_id]

            if not self._allowed(frm, to):  # ★ 变异点在 _allowed
                return 409, {"error": "ILLEGAL_TRANSITION",
                             "message": f"{frm} -> {to} 不是合法流转"}

            t = self.tasks[task_id]
            t["status"] = to
            if to in TERMINAL:
                t["finishedAt"] = "2026-10-09T00:00:01"
            if to == "FAILED" and detail.get("error") is not None:
                t["errorMsg"] = str(detail["error"])
            if to == "COMPLETED" and detail.get("result") is not None:
                t["result"] = str(detail["result"])
            self._append(task_id, frm, to, body.get("source") or "PYTHON_RUNTIME", detail)
            return 200, t


class Handler(BaseHTTPRequestHandler):
    service: FakeService

    def log_message(self, *args):  # 静音
        pass

    def _send(self, code: int, payload) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _body(self) -> dict:
        n = int(self.headers.get("Content-Length") or 0)
        if not n:
            return {}
        return json.loads(self.rfile.read(n).decode("utf-8"))

    def _route(self, method: str) -> None:
        path = self.path.split("?")[0]
        parts = [p for p in path.split("/") if p]
        svc = self.service

        if method == "POST" and parts == ["api", "tasks"]:
            code, resp = svc.create(self._body())
        elif parts[:2] == ["api", "tasks"] and len(parts) == 3 and method == "GET":
            tid = parts[2]
            code, resp = (200, svc.tasks[tid]) if tid in svc.tasks \
                else (404, {"error": "TASK_NOT_FOUND", "message": tid})
        elif parts[:2] == ["api", "tasks"] and len(parts) == 4 and parts[3] == "events":
            tid = parts[2]
            code, resp = (200, svc.events[tid]) if tid in svc.tasks \
                else (404, {"error": "TASK_NOT_FOUND", "message": tid})
        elif parts[:2] == ["api", "tasks"] and len(parts) == 4 and parts[3] == "status" \
                and method == "PATCH":
            code, resp = svc.report(parts[2], self._body())
        else:
            code, resp = 404, {"error": "NO_ROUTE", "message": path}
        self._send(code, resp)

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_PATCH(self):
        self._route("PATCH")


class FakeServer:
    """把 FakeService 挂到一个随机空闲端口上。"""

    def __init__(self, **flags):
        service = FakeService(**flags)
        handler = type("H", (Handler,), {"service": service})
        self.httpd = ThreadingHTTPServer(("127.0.0.1", 0), handler)
        self.port = self.httpd.server_address[1]
        self.thread = threading.Thread(target=self.httpd.serve_forever, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *exc):
        self.httpd.shutdown()
        self.httpd.server_close()

    @property
    def base(self) -> str:
        return f"http://127.0.0.1:{self.port}"


def run_verifier(**flags) -> tuple[int, str]:
    """对指定变体跑一遍真脚本, 返回 (退出码, 输出)。"""
    with FakeServer(**flags) as srv:
        env = {**os.environ, "KM_TASK_BASE": srv.base, "PYTHONIOENCODING": "utf-8"}
        proc = subprocess.run(
            [sys.executable, VERIFIER],
            env=env, capture_output=True, text=True, encoding="utf-8", errors="replace",
            timeout=180,
        )
        return proc.returncode, (proc.stdout or "") + (proc.stderr or "")


PASSED: list[str] = []
FAILED: list[str] = []


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        PASSED.append(name)
        print(f"  [OK]   {name}")
    else:
        FAILED.append(name)
        print(f"  [FAIL] {name}")
        for line in detail.splitlines():
            print(f"         {line}")


def main() -> int:
    print("verify_task_service.py 自检 (不需要 MySQL / Docker)")

    # ---- 1. 对照组: 契约正确的假服务, 真脚本必须全绿 ----
    print("\n对照组: 假服务契约正确 -> 真脚本应全通过")
    rc, out = run_verifier()
    check("退出码为 0", rc == 0, f"实际 {rc}\n{out}")
    check("输出里没有 FAIL", "[FAIL]" not in out,
          "\n".join(l for l in out.splitlines() if "[FAIL]" in l or "抛出异常" in l))

    # ---- 2. 变异组: 每个变异必须让对应的断言红 ----
    #
    # 每个元组: (变异参数, 期望变红的断言名, 这个变异模拟的真服务退化)
    mutations = [
        ({"cas": False},
         "并发下没有两条事件来自同一个状态",
         "UPDATE 少了 status 条件 —— 并发上报互相覆盖"),
        ({"write_event_on_noop": True},
         "重复上报没有增加事件行数",
         "幂等分支顺手写了事件 —— 事件表被网络重放淹没"),
        ({"terminal_unlocked": True},
         "FAILED 之后不能再回到 THINKING",
         "终态可离开 —— 一个已完成的任务能被改坏"),
        ({"allow_illegal": True},
         "跳级上报返回 409",
         "不做流转校验 —— 状态机退化成普通字符串字段"),
    ]

    for flags, expected_assertion, description in mutations:
        print(f"\n变异: {description}")
        rc, out = run_verifier(**flags)
        check(f"退出码非 0 ({rc})", rc != 0, out)
        # ★ 必须匹配 "[FAIL] <断言名>" 整行, 不能只找断言名 ★
        # 断言名在 "[OK] <断言名>" 里也出现, 只找名字的话这一项【永远为真】,
        # 等于没检查 —— 而这个自检本身的存在意义就是"别让空断言混过去"。
        red_line = f"[FAIL] {expected_assertion}"
        check(f"断言「{expected_assertion}」变红", red_line in out,
              f"脚本没抓到该变异 (在输出里找不到 {red_line!r}) —— 这条断言是空的\n" + out)

    print(f"\n{'=' * 68}")
    print(f"通过 {len(PASSED)} 项, 失败 {len(FAILED)} 项")
    if FAILED:
        print("\n失败项:")
        for name in FAILED:
            print(f"  - {name}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
