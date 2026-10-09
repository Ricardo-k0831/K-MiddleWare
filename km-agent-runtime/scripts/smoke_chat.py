"""端到端验收 —— 一条命令跑完全部用例。

用法 (在 km-agent-runtime/ 目录下):

    uv run python scripts/smoke_chat.py

前置条件 (四个服务都在跑):

    km-mock-order-service  :8082   存量微服务
    km-mcp-server          :8081   MCP 网关
    km-task-service        :8083   任务服务 (只有用例 T 需要)
    agent runtime          :8000   FastAPI + LangGraph

退出码: 0 = 全部通过, 1 = 有用例失败, 2 = 前置检查没过。

## 为什么需要这个脚本

手工回归的成本是"重启 mcp-server(约 50 秒) + 重启 uvicorn(约 16 秒)
+ 手工发请求", 成本高到会让人懒得回归。而这类坑只能靠真跑一次才发现:

  * structuredContent 必须是 JSON object (见 OrderMcpTools 类注释)
    —— 失败点在客户端【解析响应】时, Java 日志里一片正常
  * checkpointer 被误删导致多轮记忆消失
    —— 2026-10-04 真实发生过: 改动只活在进程内存里, 磁盘上没有,
       重启即失效, 但没有任何东西会提醒你

顺带一提, 在 Windows 控制台用 curl 直接传中文 body 会被 GBK 破坏,
报 "There was an error parsing the body"。这个脚本用 httpx 发 UTF-8。

## 为什么断言的是工具轨迹, 不是回复文案

因为文案会说谎。

工具失败时 Agent 会把错误【当成数据】转述给用户, 变成一段通顺的中文,
HTTP 200, JSON 合法。用例 B 第一次跑就是这样:

    查询失败了。系统返回的错误是:
    > structuredContent: Input should be a valid dictionary

看"有没有报错"它 100% 通过, 但它什么都没查到。

所以这里断言三件事, 都是确定性的:

    1. Agent 实际调用了哪个工具       (expect_tools)
    2. 回复里有没有关键业务事实        (expect_contains)
    3. 回复里有没有泄漏内部实现细节    (INTERNAL_LEAK_MARKERS)

LLM 的措辞每次都不同, 所以不拿文案本身做判据。

## 关于 thread_id 的唯一性

每个用例的 thread_id 都带一次运行的随机后缀。这是必须的 ——
Agent 挂了 checkpointer 之后, 同一个 thread_id 会【跨运行累积历史】。
用固定 thread_id 的话, 第二次跑用例 A 时 Agent 会看到上一次的对话,
可能直接回答"我上次已经查过了"而不再调用工具, 断言就会莫名其妙地失败。

换句话说: 固定 thread_id 会让这个脚本自己污染自己。
"""

from __future__ import annotations

import argparse
import sys
import uuid

import httpx

# Windows 控制台默认 GBK, 中文输出会乱码
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

AGENT_BASE = "http://localhost:8000"
ORDER_BASE = "http://localhost:8082"
TASK_BASE = "http://localhost:8083"

# 任务状态里哪些算终态。与 Java 的 TaskStatus.isTerminal() 对齐。
TERMINAL_STATUSES = {"COMPLETED", "FAILED", "CANCELLED"}

# 每次运行一个随机后缀, 保证 thread_id 不会跨运行串味
RUN_ID = uuid.uuid4().hex[:8]

# 这些词出现在给用户的回复里, 无论 HTTP 状态码是什么, 都是链路故障。
# 它们是"工具抛了异常但被 Agent 当数据转述"的指纹。
INTERNAL_LEAK_MARKERS = [
    "structuredContent",
    "ValidationError",
    "Traceback",
    "isError",
    "pydantic",
    "Exception",
    "Input should be",
]


# 每个用例是一串【轮次】。expect_tools / expect_contains 按轮次对应,
# 第 N 项管第 N 轮。expect_tools 写 None 表示这一轮不检查工具轨迹
# (用于 LLM 可能合理地跳过工具的情况)。
CASES = [
    {
        "tag": "A",
        "desc": "给了明确订单号 -> 必须选中 get_order_by_id",
        "messages": ["帮我查一下订单 SO20260901001 的状态"],
        "expect_tools": [["get_order_by_id"]],
        "expect_contains": [["SO20260901001", "机械键盘"]],
    },
    {
        "tag": "B",
        "desc": "只给用户ID -> 必须选中 list_orders_by_user",
        "messages": ["用户 U1001 最近买了什么"],
        "expect_tools": [["list_orders_by_user"]],
        # 这个用例是 structuredContent 那个坑的回归防线:
        # listOrdersByUser 返回 List 时, Python 客户端会在解析响应时抛
        # ValidationError, 这里会因 INTERNAL_LEAK_MARKERS 命中而失败。
        "expect_contains": [["SO20260901001", "SO20260901002"]],
    },
    {
        "tag": "C",
        "desc": "订单号不存在 -> 结构化错误被如实转述, 而不是崩溃",
        "messages": ["帮我查一下订单 SO99999999999"],
        # 仍然调用 get_order_by_id, 但后端返回 404 -> 工具吞掉异常,
        # 返回结构化的自然语言错误 -> Agent 据此引导用户
        "expect_tools": [["get_order_by_id"]],
        "expect_contains": [["SO99999999999"]],
    },
    {
        "tag": "D",
        "desc": "多轮记忆 -> 第二轮省略订单号, 仍能靠上文答对",
        # 第二轮刻意只说"这笔订单", 不给订单号。
        # 只有 checkpointer 真的在工作, Agent 才可能答出 U1001。
        # ★ 这是 checkpointer 的回归防线 —— 没有它, 记忆被删掉也没人知道。
        "messages": [
            "帮我查一下订单 SO20260901001",
            "这笔订单是谁买的？",
        ],
        # 第二轮不检查工具轨迹: LLM 可能合理地直接从上文作答, 也可能再查一次,
        # 两种都对。真正要验的是它【知不知道"这笔"指哪个订单】。
        "expect_tools": [["get_order_by_id"], None],
        "expect_contains": [["SO20260901001"], ["U1001"]],
    },
]


# 任务链路的用例。结构上比 A-D 多三步: 先在 Java 建任务, 再带 task_id 调 /chat,
# 最后回头查事件表 —— 因为这条路径的全部意义就是"Java 侧留下了正确的流转记录",
# 不看事件表等于什么都没验。
TASK_CASES = [
    {
        "tag": "T",
        "desc": "任务链路: Java 建任务 -> /chat 带 task_id -> 事件表留下完整流转",
        "message": "帮我查一下订单 SO20260901001 的状态",
        "expect_tools": ["get_order_by_id"],
        "expect_contains": ["SO20260901001", "机械键盘"],
        "expect_final_status": "COMPLETED",
    },
]


def find_internal_leaks(reply: str) -> list[str]:
    """回复里泄漏内部实现细节的指纹词。

    抽成独立函数是为了能被单独验证 —— 这条断言比"工具调用轨迹"更微妙:
    轨迹不符至少还看得见, 而错误泄漏长得像一句正常的回答。
    """
    return [marker for marker in INTERNAL_LEAK_MARKERS if marker in reply]


def preflight(client: httpx.Client) -> list[str]:
    """先确认三个服务都在, 再跑用例。

    如果不做这一步, 服务没起来时所有用例会以同样的方式失败,
    输出里看不出真正的问题是什么。
    """
    problems: list[str] = []

    try:
        r = client.get(f"{ORDER_BASE}/api/orders/SO20260901001", timeout=5.0)
        if r.status_code != 200:
            problems.append(
                f"km-mock-order-service (:8082) 返回 HTTP {r.status_code}, 期望 200"
            )
    except Exception as ex:
        problems.append(f"km-mock-order-service (:8082) 不可达: {type(ex).__name__}")

    try:
        health = client.get(f"{AGENT_BASE}/health", timeout=10.0).json()
        if not health.get("agent_ready"):
            problems.append(
                f"Agent 未装配完成 (startup_error={health.get('startup_error')!r})"
                " —— 通常是 km-mcp-server (:8081) 没起来, 或工具定义有编译错误"
            )
    except Exception as ex:
        problems.append(f"agent runtime (:8000) 不可达: {type(ex).__name__}")

    return problems


def preflight_task(client: httpx.Client) -> str | None:
    """用例 T 专用的【软】检查: 只提示, 不阻断 A-D 的 Phase1 回归。

    8083 没起来时, 你仍然应该能跑完 A-D —— 那四个用例和任务链路无关。
    """
    try:
        r = client.get(f"{TASK_BASE}/api/tasks/__probe__{RUN_ID}", timeout=5.0)
        if r.status_code != 404:
            return f"km-task-service (:8083) 返回 HTTP {r.status_code}, 期望 404"
    except Exception as ex:
        return (
            f"km-task-service (:8083) 不可达 ({type(ex).__name__}) —— 用例 T 会失败。\n"
            "       启动: mvn -pl km-task-service spring-boot:run"
        )
    return None


def chat(client: httpx.Client, message: str, thread_id: str) -> tuple[dict | None, str]:
    """发一轮对话。返回 (响应字典, 错误说明)。"""
    try:
        r = client.post(
            f"{AGENT_BASE}/chat",
            json={"message": message, "thread_id": thread_id},
            timeout=180.0,  # 每轮最多两次 LLM 调用, 给足余量
        )
    except Exception as ex:
        return None, f"请求失败: {type(ex).__name__}: {ex}"

    if r.status_code != 200:
        return None, f"HTTP {r.status_code}: {r.text[:300]}"

    data = r.json()
    if "tool_calls" not in data:
        return None, (
            "响应里没有 tool_calls 字段。\n"
            "       agent runtime 是不是改完代码没重启? (这个字段是新加的)"
        )
    return data, ""


def run_case(client: httpx.Client, case: dict, show_reply: bool) -> bool:
    thread_id = f"smoke-{RUN_ID}-{case['tag'].lower()}"
    failures: list[str] = []

    print(f"\n{'=' * 72}")
    print(f"用例 {case['tag']}: {case['desc']}")
    print(f"thread_id: {thread_id}")
    print("-" * 72)

    for index, message in enumerate(case["messages"]):
        round_no = index + 1
        total = len(case["messages"])
        prefix = f"[第 {round_no}/{total} 轮] " if total > 1 else ""

        print(f"{prefix}问: {message}")

        data, error = chat(client, message, thread_id)
        if data is None:
            print(f"{prefix}[FAIL] {error}")
            return False

        actual_tools = data["tool_calls"]
        reply = data.get("reply", "")

        print(f"{prefix}工具: {actual_tools}")
        print(f"{prefix}答: {reply if show_reply else reply[:120] + ('...' if len(reply) > 120 else '')}")

        expected_tools = case["expect_tools"][index]
        if expected_tools is not None and actual_tools != expected_tools:
            failures.append(
                f"第 {round_no} 轮工具调用轨迹不符: "
                f"期望 {expected_tools}, 实际 {actual_tools}"
            )

        for keyword in case["expect_contains"][index]:
            if keyword not in reply:
                failures.append(
                    f"第 {round_no} 轮回复里缺少期望的关键业务事实: {keyword!r}"
                )

        leaked = find_internal_leaks(reply)
        if leaked:
            failures.append(
                f"第 {round_no} 轮回复里泄漏了内部实现细节 {leaked}\n"
                "       —— 工具很可能报错了, 但 Agent 把错误当数据转述给了用户。\n"
                "       这正是 HTTP 200 却实际失败的情形, 光看状态码发现不了。"
            )

    if failures:
        print()
        for f in failures:
            print(f"[FAIL] {f}")
        return False

    print("[OK]")
    return True


# ----------------------------------------------------------------------
#  任务链路 (用例 T)
# ----------------------------------------------------------------------


def create_task(client: httpx.Client, intent: str) -> tuple[dict | None, str]:
    try:
        r = client.post(
            f"{TASK_BASE}/api/tasks",
            json={
                "userId": "U1001",
                "sessionId": f"smoke-{RUN_ID}-{uuid.uuid4().hex[:6]}",
                "intent": intent,
            },
            timeout=20.0,
        )
    except Exception as ex:
        return None, (
            f"km-task-service (:8083) 不可达: {type(ex).__name__}: {ex}\n"
            "       启动: mvn -pl km-task-service spring-boot:run"
        )
    if r.status_code != 201:
        return None, f"建任务 HTTP {r.status_code}: {r.text[:300]}"
    return r.json(), ""


def fetch_task(client: httpx.Client, task_id: str) -> dict | None:
    try:
        r = client.get(f"{TASK_BASE}/api/tasks/{task_id}", timeout=10.0)
        return r.json() if r.status_code == 200 else None
    except Exception:
        return None


def fetch_events(client: httpx.Client, task_id: str) -> tuple[list | None, str]:
    try:
        r = client.get(f"{TASK_BASE}/api/tasks/{task_id}/events", timeout=10.0)
    except Exception as ex:
        return None, f"查事件失败: {type(ex).__name__}: {ex}"
    if r.status_code != 200:
        return None, f"查事件 HTTP {r.status_code}: {r.text[:200]}"
    return r.json(), ""


def check_event_chain(events: list[dict]) -> list[str]:
    """事件表的结构不变量。

    ★ 这里【没有】断言"fromStatus 不重复" —— 直觉上像, 但它是错的 ★

    ReAct 循环会回到 THINKING, 所以 THINKING 作为 fromStatus 天然出现两次以上:

        PENDING->THINKING, THINKING->TOOL_CALLING, TOOL_CALLING->THINKING, THINKING->COMPLETED
                          ^^^^^^^^                                      ^^^^^^^^

    (km-control-plane/scripts/verify_task_service.py 里那个"不重复"检查是对的,
     因为那里是多个请求抢同一个状态; 这里是一条时间线, 两者不是一回事。
     把 Java 那条断言照搬过来会得到一个必然失败的用例。)

    真正的结构不变量是【首尾相接】。它同样能抓住并发覆盖造成的断链 ——
    两条事件的 fromStatus 都是 THINKING 时, 第二条就接不上了 —— 又不会误伤循环。
    """
    problems: list[str] = []
    if not events:
        return ["事件表是空的 —— 一次流转都没上报。task_id 是不是没传进 /chat?"]

    seq = [(e["fromStatus"], e["toStatus"]) for e in events]

    if seq[0] != (None, "PENDING"):
        problems.append(f"第一条事件应是 (None -> PENDING), 实际 {seq[0]}")
    if events[0]["source"] != "JAVA_GATEWAY":
        problems.append(
            f"建任务那条事件的 source 应是 JAVA_GATEWAY, 实际 {events[0]['source']!r}"
        )

    for i in range(1, len(seq)):
        if seq[i][0] != seq[i - 1][1]:
            problems.append(
                f"事件链断在第 {i + 1} 条: 它的 from={seq[i][0]!r}, "
                f"但上一条的 to={seq[i - 1][1]!r}"
            )

    if "TOOL_CALLING" not in [e["toStatus"] for e in events]:
        problems.append(
            "事件表里没有 TOOL_CALLING —— 但工具明明被调用了。"
            "回调链路 (CallbackHandler -> Reporter -> Java) 有一段断了"
        )

    return problems


def run_task_case(client: httpx.Client, case: dict, show_reply: bool) -> bool:
    failures: list[str] = []

    print(f"\n{'=' * 72}")
    print(f"用例 {case['tag']}: {case['desc']}")
    print("-" * 72)

    task, error = create_task(client, case["message"])
    if task is None:
        print(f"[FAIL] {error}")
        return False

    task_id = task["agentTaskId"]
    print(f"task_id: {task_id}")
    print(f"建任务后状态: {task['status']}")
    if task["status"] != "PENDING":
        failures.append(f"新建任务的状态应是 PENDING, 实际 {task['status']!r}")

    # 带 task_id 调 /chat —— 刻意【不传 thread_id】: task_id 自己就是 thread_id。
    print(f"问: {case['message']}")
    try:
        r = client.post(
            f"{AGENT_BASE}/chat",
            json={"message": case["message"], "task_id": task_id},
            timeout=180.0,
        )
    except Exception as ex:
        print(f"[FAIL] /chat 请求失败: {type(ex).__name__}: {ex}")
        return False

    if r.status_code != 200:
        print(f"[FAIL] /chat HTTP {r.status_code}: {r.text[:300]}")
        return False

    data = r.json()
    reply = data.get("reply", "")
    actual_tools = data.get("tool_calls", [])

    print(f"工具: {actual_tools}")
    print(f"答: {reply if show_reply else reply[:120] + ('...' if len(reply) > 120 else '')}")

    if data.get("task_id") != task_id:
        failures.append(
            f"响应里的 task_id 回显不对: 期望 {task_id!r}, 实际 {data.get('task_id')!r}"
        )
    if data.get("thread_id") != task_id:
        failures.append(
            f"任务链路下 thread_id 应当就是 task_id, 实际 {data.get('thread_id')!r}\n"
            "       —— 两者不一致的话, 断点续跑会找不到上次的 checkpoint"
        )
    if actual_tools != case["expect_tools"]:
        failures.append(f"工具调用轨迹不符: 期望 {case['expect_tools']}, 实际 {actual_tools}")
    for keyword in case["expect_contains"]:
        if keyword not in reply:
            failures.append(f"回复里缺少期望的关键业务事实: {keyword!r}")
    leaked = find_internal_leaks(reply)
    if leaked:
        failures.append(f"回复里泄漏了内部实现细节 {leaked}")

    # ---- 回头查 Java 侧的任务与事件 ----
    #
    # ★ 刻意不做重试轮询 ★
    # Reporter.aclose() 会等队列排空, 而 Java 是先提交事务再回 HTTP 响应。
    # 所以 /chat 返回的那一刻, 终态必然已经落库。
    # 真要等一会儿才读得到, 那本身就是 bug (终态被丢), 不该被重试掩盖掉。
    final = fetch_task(client, task_id)
    if final is None:
        failures.append("查不回任务 —— km-task-service 挂了?")
    else:
        print(f"最终状态: {final['status']}   errorMsg: {final.get('errorMsg')!r}")
        if final["status"] != case["expect_final_status"]:
            failures.append(
                f"最终状态期望 {case['expect_final_status']}, 实际 {final['status']!r}\n"
                f"       errorMsg={final.get('errorMsg')!r}"
            )
        if final.get("finishedAt") is None:
            failures.append("终态没有写 finishedAt")

    events, error = fetch_events(client, task_id)
    if events is None:
        failures.append(error)
    else:
        print("事件序列:")
        for e in events:
            print(f"  {e['fromStatus']} -> {e['toStatus']}  [{e['source']}]")
        failures.extend(check_event_chain(events))

    if failures:
        print()
        for f in failures:
            print(f"[FAIL] {f}")
        return False

    print("[OK]")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(
        description="K_middleware Phase 1 端到端验收",
    )
    parser.add_argument(
        "--quiet", action="store_true", help="只打印结论, 不打印完整回复"
    )
    args = parser.parse_args()

    print("K_middleware Phase 1 端到端验收")
    print("需要三个服务都在跑: :8082 存量订单 / :8081 MCP 网关 / :8000 Agent 运行时")
    print(f"本次运行 ID: {RUN_ID} (用于隔离 thread_id, 避免跨运行串味)")

    with httpx.Client() as client:
        problems = preflight(client)
        if problems:
            print("\n前置检查未通过:")
            for p in problems:
                print(f"  - {p}")
            print(
                "\n先修好这些再跑, 否则所有用例会以同样的方式失败, "
                "输出里看不出真正的问题。"
            )
            return 2

        print("\n基础服务检查通过。")
        warning = preflight_task(client)
        print(f"[警告] {warning}" if warning else "任务服务 (:8083) 可用, 用例 T 会一起跑。")
        print()

        results = [
            (case["tag"], run_case(client, case, not args.quiet)) for case in CASES
        ]
        results += [
            (case["tag"], run_task_case(client, case, not args.quiet))
            for case in TASK_CASES
        ]

    print(f"\n{'=' * 72}")
    for tag, ok in results:
        print(f"  用例 {tag}: {'通过' if ok else '失败'}")

    passed = sum(1 for _, ok in results if ok)
    print(f"\n{passed}/{len(results)} 通过")
    return 0 if passed == len(results) else 1


if __name__ == "__main__":
    sys.exit(main())
