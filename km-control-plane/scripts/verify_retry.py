"""任务重试 (:8083) 的端到端验收。

用法 (任何 Python 3 都行, 不依赖虚拟环境):

    python km-control-plane/scripts/verify_retry.py

前置: 服务已启动 (`mvn -pl km-task-service spring-boot:run`), MySQL 可连。

退出码: 0 = 全通过, 1 = 有用例失败, 2 = 前置检查没过。

## 这个脚本要证明什么

重试不是一个"多了一个接口"的功能, 它同时改变了三条不变量, 每一条都
必须能在这里被看见:

  1. **状态机**: FAILED -> PENDING 只能由控制面发起。通过 PATCH 上报
     走同一条边必须仍然是 409 —— 否则等于给 Python 开了后门。
  2. **链的边界**: 重试必须换一条新 trace。旧 trace 立刻失效 (409),
     新 trace 才能继续上报; 任务表上的指针滚到新链。
  3. **次数**: retry_count 与 max_retry 的边界是 `>=`, 而且并发重试
     只能有一个成功 —— 输的一方不能留下第二条 attempt。

## 为什么不用 curl

和 verify_task_service.py 同一个理由: Windows 控制台默认 GBK, 中文
JSON body 会被破坏, 而错误信息完全指不到编码问题。这里用 urllib + UTF-8。
"""

from __future__ import annotations

import concurrent.futures
import json
import os
import sys
import urllib.error
import urllib.request
import uuid

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = os.environ.get("KM_TASK_BASE", "http://localhost:8083")
RUN_ID = uuid.uuid4().hex[:8]

PASSED: list[str] = []
FAILED: list[str] = []


# ----------------------------------------------------------------------
#  HTTP
# ----------------------------------------------------------------------


def call(method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    req = urllib.request.Request(
        BASE + path,
        data=data,
        method=method,
        headers={"Content-Type": "application/json; charset=utf-8"},
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
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
            for line in detail.splitlines():
                print(f"         {line}")


# ----------------------------------------------------------------------
#  业务操作
# ----------------------------------------------------------------------

TRACES: dict[str, str] = {}


def create_task(**overrides) -> str:
    body = {
        "userId": "U1001",
        "sessionId": f"retry-{RUN_ID}",
        "intent": "重试验收: 查订单 SO20260901001",
    }
    body.update(overrides)
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


def retry(task_id: str, reason: str | None = None) -> tuple[int, dict]:
    body = {"reason": reason} if reason else None
    return call("POST", f"/api/tasks/{task_id}/retry", body)


def task_of(task_id: str) -> dict:
    status, resp = call("GET", f"/api/tasks/{task_id}")
    assert status == 200, f"查任务失败: HTTP {status} {resp}"
    return resp


def events_of(task_id: str) -> list[dict]:
    status, resp = call("GET", f"/api/tasks/{task_id}/events")
    assert status == 200, f"查事件失败: HTTP {status} {resp}"
    return resp


def fail_once(task_id: str, trace_id: str | None = None) -> None:
    """PENDING -> THINKING -> FAILED, 把一个任务弄成可重试的状态。"""
    trace = trace_id or TRACES[task_id]
    status, resp = report(task_id, "THINKING", trace)
    assert status == 200, f"上报 THINKING 失败: HTTP {status} {resp}"
    status, resp = report(task_id, "FAILED", trace, {"error": "MCP 网关 500"})
    assert status == 200, f"上报 FAILED 失败: HTTP {status} {resp}"


# ----------------------------------------------------------------------
#  用例
# ----------------------------------------------------------------------


def case_full_retry_cycle() -> None:
    print("\n用例 1: 失败 -> 重试 -> 新链跑完 -> 终态不可再重试")

    task_id = create_task(maxRetry=2)
    old_trace = TRACES[task_id]
    fail_once(task_id)

    before = task_of(task_id)
    check("失败后状态是 FAILED", before.get("status") == "FAILED", str(before))
    check("失败后 retryCount 仍为 0", before.get("retryCount") == 0, str(before.get("retryCount")))
    check("失败后 finishedAt 有值", bool(before.get("finishedAt")), str(before.get("finishedAt")))

    status, resp = retry(task_id, "MCP 网关 500, 人工确认后重试")
    check("重试返回 200", status == 200, f"HTTP {status} {resp}")

    new_trace = resp.get("traceId")
    check("重试换了新的 traceId", bool(new_trace) and new_trace != old_trace,
          f"old={old_trace} new={new_trace}")
    check("重试后状态回到 PENDING", resp.get("status") == "PENDING", str(resp.get("status")))
    check("retryCount 变成 1", resp.get("retryCount") == 1, str(resp.get("retryCount")))
    check("finishedAt 被清空", resp.get("finishedAt") is None, str(resp.get("finishedAt")))
    check("errorMsg 被清空", resp.get("errorMsg") is None, str(resp.get("errorMsg")))
    check("滚动指针指向新链", resp.get("traceId") == new_trace)

    events = events_of(task_id)
    last = events[-1]
    check("最后一条事件是 FAILED -> PENDING",
          last.get("fromStatus") == "FAILED" and last.get("toStatus") == "PENDING",
          str(last))
    check("重试事件的 source 是 JAVA_GATEWAY (不是 SYSTEM_RETRY)",
          last.get("source") == "JAVA_GATEWAY", str(last.get("source")))
    detail = last.get("detail") or {}
    check("事件记下 attemptNo=2", detail.get("attemptNo") == 2, str(detail))
    check("事件记下新 trace", detail.get("traceId") == new_trace, str(detail))
    check("事件记下 previousTraceId, 两条链能接上",
          detail.get("previousTraceId") == old_trace, str(detail))
    check("事件保留了重试原因", detail.get("reason") == "MCP 网关 500, 人工确认后重试", str(detail))

    # ★ 旧链必须当场失效 ★
    status, resp = report(task_id, "THINKING", old_trace)
    check("旧 trace 再上报被拒 (409 EXECUTION_MISMATCH)",
          status == 409 and resp.get("error") == "EXECUTION_MISMATCH",
          f"HTTP {status} {resp}")

    # 新链正常跑完
    status, resp = report(task_id, "THINKING", new_trace)
    check("新 trace 上报 THINKING 成功", status == 200, f"HTTP {status} {resp}")
    status, resp = report(task_id, "COMPLETED", new_trace, {"result": "订单已发货"})
    check("新 trace 上报 COMPLETED 成功", status == 200, f"HTTP {status} {resp}")

    done = task_of(task_id)
    check("任务最终是 COMPLETED", done.get("status") == "COMPLETED", str(done.get("status")))
    check("最终 trace 仍是新链", done.get("traceId") == new_trace)
    check("COMPLETED 后 finishedAt 又有值", bool(done.get("finishedAt")))

    status, resp = retry(task_id, "不该成功")
    check("已完成的任务不能重试 (409 RETRY_NOT_ALLOWED)",
          status == 409 and resp.get("error") == "RETRY_NOT_ALLOWED",
          f"HTTP {status} {resp}")

    # 上报路径也不能离开终态 —— 重试边没有污染常规流转表。
    # 这里拿到的是 EXECUTION_MISMATCH 而不是 ILLEGAL_TRANSITION, 因为
    # "执行已闭链"的检查在状态机之前: 终态上报会闭链, 之后同一个 trace
    # 的任何上报都先被身份检查挡住。两个码都是 409, 也都说明状态没动。
    status, resp = report(task_id, "THINKING", new_trace)
    check("上报无法把 COMPLETED 拉回 THINKING (409, 执行已闭链)",
          status == 409 and resp.get("error") in {"ILLEGAL_TRANSITION", "EXECUTION_MISMATCH"},
          f"HTTP {status} {resp}")
    check("任务仍然是 COMPLETED", task_of(task_id).get("status") == "COMPLETED")


def case_max_retry_zero() -> None:
    print("\n用例 2: maxRetry=0 —— 一次都不许重试")

    task_id = create_task(maxRetry=0)
    fail_once(task_id)

    status, resp = retry(task_id)
    check("次数用尽返回 409 MAX_RETRY_EXCEEDED",
          status == 409 and resp.get("error") == "MAX_RETRY_EXCEEDED",
          f"HTTP {status} {resp}")

    after = task_of(task_id)
    check("被拒绝后状态仍是 FAILED", after.get("status") == "FAILED", str(after.get("status")))
    check("被拒绝后 retryCount 没被偷偷 +1", after.get("retryCount") == 0,
          str(after.get("retryCount")))
    check("被拒绝后 trace 没变", after.get("traceId") == TRACES[task_id],
          str(after.get("traceId")))


def case_second_retry_exhausted() -> None:
    print("\n用例 3: maxRetry=1 —— 重试一次后, 第二次失败不能再试")

    task_id = create_task(maxRetry=1)
    old_trace = TRACES[task_id]
    fail_once(task_id)

    status, resp = retry(task_id)
    check("第一次重试成功", status == 200, f"HTTP {status} {resp}")
    new_trace = resp.get("traceId")
    TRACES[task_id] = new_trace

    status, resp = report(task_id, "THINKING", old_trace)
    check("第一代 trace 已失效", status == 409, f"HTTP {status} {resp}")

    # 第二代也失败
    status, resp = report(task_id, "THINKING", new_trace)
    assert status == 200, f"新链 THINKING 失败: HTTP {status} {resp}"
    status, resp = report(task_id, "FAILED", new_trace, {"error": "又失败了"})
    assert status == 200, f"新链 FAILED 失败: HTTP {status} {resp}"

    status, resp = retry(task_id)
    check("第二次重试被次数上限拦住 (409 MAX_RETRY_EXCEEDED)",
          status == 409 and resp.get("error") == "MAX_RETRY_EXCEEDED",
          f"HTTP {status} {resp}")

    retries = [e for e in events_of(task_id)
               if e.get("fromStatus") == "FAILED" and e.get("toStatus") == "PENDING"]
    check("事件表里只有一条重试事件", len(retries) == 1, f"实际 {len(retries)} 条")
    check("那条重试事件是 attemptNo=2", retries and retries[0]["detail"].get("attemptNo") == 2)


def case_retry_pending_task() -> None:
    print("\n用例 4: 从未失败的任务不能重试")

    task_id = create_task()

    status, resp = retry(task_id)
    check("PENDING 任务重试返回 409 RETRY_NOT_ALLOWED",
          status == 409 and resp.get("error") == "RETRY_NOT_ALLOWED",
          f"HTTP {status} {resp}")

    events = events_of(task_id)
    check("没有产生任何重试事件",
          not any(e.get("fromStatus") == "FAILED" for e in events), str(events))


def case_concurrent_retry() -> None:
    print("\n用例 5: 并发重试 —— 只能有一个成功, 不能开出第二条 attempt")

    task_id = create_task(maxRetry=3)
    fail_once(task_id)
    old_trace = TRACES[task_id]

    def fire() -> tuple[int, dict]:
        return retry(task_id, "并发重试")

    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        results = list(pool.map(lambda _: fire(), range(3)))

    winners = [r for r in results if r[0] == 200]
    losers = [r for r in results if r[0] == 409]
    check("3 个并发重试里恰好 1 个成功", len(winners) == 1,
          f"HTTP 结果: {[s for s, _ in results]}")
    check("其余都是 409 RETRY_NOT_ALLOWED",
          len(losers) == 2 and all(r.get("error") == "RETRY_NOT_ALLOWED" for _, r in losers),
          str([r for _, r in losers]))

    winner_trace = winners[0][1]["traceId"] if winners else None
    after = task_of(task_id)
    check("retryCount 只 +1", after.get("retryCount") == 1, str(after.get("retryCount")))
    check("trace 是赢家那条", after.get("traceId") == winner_trace,
          f"db={after.get('traceId')} winner={winner_trace}")

    retries = [e for e in events_of(task_id)
               if e.get("fromStatus") == "FAILED" and e.get("toStatus") == "PENDING"]
    check("事件表里只有一条 FAILED -> PENDING (输的一方什么都没写)",
          len(retries) == 1, f"实际 {len(retries)} 条")
    check("赢家的新链和旧链不同", winner_trace != old_trace, f"{old_trace} vs {winner_trace}")


def case_not_found() -> None:
    print("\n用例 6: 不存在的任务重试")

    status, resp = retry(f"missing-{RUN_ID}")
    check("返回 404 TASK_NOT_FOUND",
          status == 404 and resp.get("error") == "TASK_NOT_FOUND",
          f"HTTP {status} {resp}")


# ----------------------------------------------------------------------
#  入口
# ----------------------------------------------------------------------


def preflight() -> list[str]:
    try:
        status, resp = call("GET", f"/api/tasks/__probe__{RUN_ID}")
        if status != 404 or resp.get("error") != "TASK_NOT_FOUND":
            return [f"探活返回 {status} {resp} —— 服务起来了但不是这个任务服务?"]
    except Exception as ex:
        return [
            f"km-task-service (:8083) 不可达: {type(ex).__name__}: {ex}\n"
            "       先确认服务已启动, 且 MySQL 可连"
        ]
    return []


def main() -> int:
    print(f"目标: {BASE}   本次运行 ID: {RUN_ID}")

    problems = preflight()
    if problems:
        for p in problems:
            print(f"  [前置失败] {p}")
        return 2

    case_full_retry_cycle()
    case_max_retry_zero()
    case_second_retry_exhausted()
    case_retry_pending_task()
    case_concurrent_retry()
    case_not_found()

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
