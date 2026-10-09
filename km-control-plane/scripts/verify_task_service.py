"""km-task-service (:8083) 的端到端验收。

用法 (任何 Python 3 都行, 不依赖虚拟环境):

    python km-control-plane/scripts/verify_task_service.py

前置: 服务已启动 (`mvn -pl km-task-service spring-boot:run`), MySQL 可连。

退出码: 0 = 全通过, 1 = 有用例失败, 2 = 前置检查没过。

## 为什么不用 curl

Windows 控制台默认 GBK, 用 curl 直接传带中文的 JSON body 会被破坏,
服务端报 "There was an error parsing the body", 而错误信息完全指不到编码问题。
这个脚本用 urllib 发 UTF-8 —— 而且不用 httpx, 是为了让它在没有 venv
的裸 Python 下也能跑 (Java 模块的验证脚本不该依赖 Python 项目的环境)。

## 这个脚本重点打什么

不是"接口能不能返回 200", 而是**状态机有没有真的在管事**:

  1. 非法流转必须被拒 (409), 而不是被静默接受
  2. 终态必须锁死 —— 一个 COMPLETED 的任务不能再被改成别的
  3. 幂等上报返回 200 且【不写事件表】—— 否则事件流会被重放淹没
  4. detail 的 JSON 往返不失真 (含中文和嵌套结构)
  5. 并发 CAS —— 两个请求同时改, 只能有一个成功

第 2、3 条是最容易做错、也最难在手工测试里发现的。
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

# 默认打本地服务; selftest_verify_task_service.py 用这个环境变量指向假服务。
BASE = os.environ.get("KM_TASK_BASE", "http://localhost:8083")

# 每次运行一个随机后缀, 避免和上次残留的数据混在一起
RUN_ID = uuid.uuid4().hex[:8]

PASSED: list[str] = []
FAILED: list[str] = []


# ----------------------------------------------------------------------
#  HTTP
# ----------------------------------------------------------------------


def call(method: str, path: str, body: dict | None = None) -> tuple[int, dict]:
    """发一个请求, 返回 (状态码, 响应体)。响应体解析失败时返回 {}。"""
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


def create_task(**overrides) -> str:
    body = {
        "userId": "U1001",
        "sessionId": f"verify-{RUN_ID}",
        "intent": "帮我查一下订单 SO20260901001 的状态",
    }
    body.update(overrides)
    status, resp = call("POST", "/api/tasks", body)
    assert status == 201, f"建任务失败: HTTP {status} {resp}"
    return resp["agentTaskId"]


def events_of(task_id: str) -> list[dict]:
    status, resp = call("GET", f"/api/tasks/{task_id}/events")
    assert status == 200, f"查事件失败: HTTP {status} {resp}"
    return resp


# ----------------------------------------------------------------------
#  前置检查
# ----------------------------------------------------------------------


def preflight() -> list[str]:
    problems: list[str] = []
    try:
        # 用一个必然不存在的 ID 探活: 服务活着的话会返回 404 + 结构化错误
        status, resp = call("GET", f"/api/tasks/__probe__{RUN_ID}")
        if status != 404:
            problems.append(
                f"探活请求期望 404, 实际 {status} —— 服务起来了但不是这个任务服务?"
            )
        elif resp.get("error") != "TASK_NOT_FOUND":
            problems.append(
                f"404 的错误码期望 TASK_NOT_FOUND, 实际 {resp.get('error')!r} "
                "—— 统一错误处理没生效?"
            )
    except Exception as ex:
        problems.append(
            f"km-task-service (:8083) 不可达: {type(ex).__name__}: {ex}\n"
            "       先确认服务已启动, 且 MySQL 可连"
        )
    return problems


# ----------------------------------------------------------------------
#  用例
# ----------------------------------------------------------------------


def case_happy_path() -> None:
    print("\n用例 1: 正常流转 PENDING -> THINKING -> TOOL_CALLING -> THINKING -> COMPLETED")

    task_id = create_task()
    status, task = call("GET", f"/api/tasks/{task_id}")
    check("新建任务状态为 PENDING", task.get("status") == "PENDING", f"实际 {task.get('status')}")

    for to_status, detail in [
        ("THINKING", {"phase": "llm"}),
        ("TOOL_CALLING", {"toolName": "get_order_by_id"}),
        ("THINKING", {"phase": "llm"}),
        ("COMPLETED", {"result": "订单 SO20260901001 已发货"}),
    ]:
        code, resp = call("PATCH", f"/api/tasks/{task_id}/status",
                          {"toStatus": to_status, "detail": detail})
        check(f"上报 {to_status} 返回 200", code == 200, f"HTTP {code}: {resp}")

    code, task = call("GET", f"/api/tasks/{task_id}")
    check("最终状态为 COMPLETED", task.get("status") == "COMPLETED", f"实际 {task.get('status')}")
    check("终态写入了 finishedAt", task.get("finishedAt") is not None)
    check("COMPLETED 的 detail.result 落到了 result 字段",
          task.get("result") == "订单 SO20260901001 已发货",
          f"实际 {task.get('result')!r}")

    evs = events_of(task_id)
    seq = [(e["fromStatus"], e["toStatus"]) for e in evs]
    expected = [
        (None, "PENDING"),
        ("PENDING", "THINKING"),
        ("THINKING", "TOOL_CALLING"),
        ("TOOL_CALLING", "THINKING"),
        ("THINKING", "COMPLETED"),
    ]
    check("事件表完整记录了每一步流转", seq == expected,
          f"期望 {expected}\n实际 {seq}")
    check("第一条事件来自建任务 (source=JAVA_GATEWAY)",
          evs and evs[0]["source"] == "JAVA_GATEWAY")


def case_illegal_transition() -> None:
    print("\n用例 2: 非法流转必须被拒 (PENDING 不能直接到 TOOL_CALLING)")

    task_id = create_task()
    code, resp = call("PATCH", f"/api/tasks/{task_id}/status",
                      {"toStatus": "TOOL_CALLING", "detail": {}})
    check("跳级上报返回 409", code == 409, f"HTTP {code}: {resp}")
    check("错误码为 ILLEGAL_TRANSITION", resp.get("error") == "ILLEGAL_TRANSITION",
          f"实际 {resp.get('error')!r}")

    _, task = call("GET", f"/api/tasks/{task_id}")
    check("被拒后任务状态没有被改动", task.get("status") == "PENDING",
          f"实际 {task.get('status')}")
    check("被拒的流转没有写进事件表", len(events_of(task_id)) == 1,
          f"实际事件数 {len(events_of(task_id))}, 期望 1 (只有建任务那条)")


def case_terminal_locked() -> None:
    print("\n用例 3: 终态必须锁死")

    task_id = create_task()
    call("PATCH", f"/api/tasks/{task_id}/status", {"toStatus": "THINKING"})
    call("PATCH", f"/api/tasks/{task_id}/status", {"toStatus": "FAILED",
                                                   "detail": {"error": "工具超时"}})

    code, resp = call("PATCH", f"/api/tasks/{task_id}/status", {"toStatus": "THINKING"})
    check("FAILED 之后不能再回到 THINKING (409)", code == 409, f"HTTP {code}: {resp}")

    _, task = call("GET", f"/api/tasks/{task_id}")
    check("状态仍是 FAILED", task.get("status") == "FAILED", f"实际 {task.get('status')}")
    check("FAILED 的 detail.error 落到了 errorMsg 字段",
          task.get("errorMsg") == "工具超时", f"实际 {task.get('errorMsg')!r}")


def case_idempotent() -> None:
    print("\n用例 4: 幂等上报 —— 返回 200 但【不写事件表】")

    task_id = create_task()
    call("PATCH", f"/api/tasks/{task_id}/status", {"toStatus": "THINKING"})
    before = len(events_of(task_id))

    for _ in range(3):
        code, _resp = call("PATCH", f"/api/tasks/{task_id}/status",
                           {"toStatus": "THINKING", "detail": {"phase": "llm"}})
        check("重复上报同一状态返回 200", code == 200, f"HTTP {code}")

    after = len(events_of(task_id))
    check("重复上报没有增加事件行数", after == before,
          f"上报 3 次后事件数从 {before} 变成 {after} —— 事件表被重放污染了")

    # 终态的同态上报也应该是幂等, 不是 409
    call("PATCH", f"/api/tasks/{task_id}/status", {"toStatus": "COMPLETED"})
    code, _ = call("PATCH", f"/api/tasks/{task_id}/status", {"toStatus": "COMPLETED"})
    check("终态重复上报返回 200 (幂等), 不是 409", code == 200, f"HTTP {code}")


def case_detail_roundtrip() -> None:
    print("\n用例 5: detail 的 JSON 往返 (含中文与嵌套结构)")

    task_id = create_task()
    payload = {
        "toolName": "查询订单",
        "arguments": {"orderId": "SO20260901001", "withItems": True, "limit": 1},
        "备注": "中文键名也不能坏",
        "extra": None,
    }
    call("PATCH", f"/api/tasks/{task_id}/status",
         {"toStatus": "THINKING", "detail": payload})

    evs = events_of(task_id)
    stored = evs[-1].get("detail")
    check("detail 往返后内容一致", stored == payload,
          f"写进去 {json.dumps(payload, ensure_ascii=False)}\n"
          f"读出来 {json.dumps(stored, ensure_ascii=False)}")


def case_concurrent_cas() -> None:
    print("\n用例 6: 并发上报 —— CAS 必须挡住「读到写」之间的竞争")

    # 这一条是本脚本里唯一【无法靠手工点点点发现】的用例。
    #
    # TaskService.reportStatus 是"读当前状态 -> 判断 -> 写新状态", 三步之间有窗口。
    # 没有 CAS 的话, 两个并发请求都会读到 THINKING, 都认为合法, 都写进去 ——
    # 于是事件表里出现【两条 fromStatus 都是 THINKING】的记录,
    # 状态机被绕过, 而每一单个请求看上去都是成功的。
    #
    # 所以这里断言的不是"谁赢", 而是事件流的结构不变量:
    #   (a) 事件必须首尾相接: 每条事件的 fromStatus == 上一条的 toStatus
    #   (b) 不能有两条事件有相同的 fromStatus
    # (b) 就是 CAS 失效的指纹, 和调度顺序无关, 所以这个用例不会 flaky。
    task_id = create_task()
    call("PATCH", f"/api/tasks/{task_id}/status", {"toStatus": "THINKING"})
    before = len(events_of(task_id))

    # TOOL_CALLING 和 COMPLETED 在 THINKING 状态下都合法, 所以冲突必须由 CAS 解决,
    # 而不是被流转规则顺手挡掉 —— 否则这个用例测不到 CAS。
    targets = ["TOOL_CALLING", "COMPLETED"] * 3
    with concurrent.futures.ThreadPoolExecutor(max_workers=len(targets)) as pool:
        futures = [
            pool.submit(call, "PATCH", f"/api/tasks/{task_id}/status", {"toStatus": t})
            for t in targets
        ]
        codes = [f.result()[0] for f in futures]

    check("并发上报没有出现 500",
          500 not in codes, f"状态码分布 {sorted(codes)} —— 冲突路径抛了未捕获异常?")

    evs = events_of(task_id)
    new_evs = evs[before:]

    froms = [e["fromStatus"] for e in new_evs]
    check("并发下没有两条事件来自同一个状态 (CAS 生效)",
          len(froms) == len(set(froms)),
          f"出现了重复的 fromStatus: {froms}\n"
          "       —— 说明 UPDATE 缺少 status 条件, 并发请求互相覆盖了")

    chain_ok = all(evs[i]["fromStatus"] == evs[i - 1]["toStatus"] for i in range(1, len(evs)))
    check("事件链首尾相接, 没有分叉",
          chain_ok,
          "事件序列: " + " -> ".join(f"{e['fromStatus']}={e['toStatus']}" for e in evs))

    _, task = call("GET", f"/api/tasks/{task_id}")
    check("最终状态是其中一个合法目标",
          task.get("status") in ("TOOL_CALLING", "COMPLETED"),
          f"实际 {task.get('status')}")


def case_not_found() -> None:
    print("\n用例 7: 不存在的任务")

    ghost = f"nope-{RUN_ID}"
    code, resp = call("GET", f"/api/tasks/{ghost}")
    check("查不存在的任务返回 404", code == 404, f"HTTP {code}")
    check("错误码为 TASK_NOT_FOUND", resp.get("error") == "TASK_NOT_FOUND")

    code, _ = call("GET", f"/api/tasks/{ghost}/events")
    check("查不存在任务的事件返回 404 (而不是空数组)", code == 404, f"HTTP {code}")


def case_validation() -> None:
    print("\n用例 8: 参数校验")

    code, resp = call("POST", "/api/tasks", {"userId": ""})
    check("userId 为空返回 400", code == 400, f"HTTP {code}: {resp}")
    check("错误码为 INVALID_REQUEST", resp.get("error") == "INVALID_REQUEST",
          f"实际 {resp.get('error')!r}")

    task_id = create_task()
    code, resp = call("PATCH", f"/api/tasks/{task_id}/status", {"toStatus": "不存在的状态"})
    check("非法枚举值返回 400", code == 400, f"HTTP {code}: {resp}")


def main() -> int:
    print("km-task-service 端到端验收")
    print(f"目标: {BASE}   本次运行 ID: {RUN_ID}")

    problems = preflight()
    if problems:
        print("\n前置检查未通过:")
        for p in problems:
            print(f"  - {p}")
        return 2
    print("\n前置检查通过。")

    for case in (
        case_happy_path,
        case_illegal_transition,
        case_terminal_locked,
        case_idempotent,
        case_detail_roundtrip,
        case_concurrent_cas,
        case_not_found,
        case_validation,
    ):
        try:
            case()
        except Exception as ex:
            FAILED.append(case.__name__)
            print(f"  [FAIL] {case.__name__} 抛出异常: {type(ex).__name__}: {ex}")

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
