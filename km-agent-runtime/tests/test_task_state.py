"""TaskStateReporter 的单测。

用 httpx.MockTransport 假装 Java 任务服务, 不需要起真服务、不需要 docker。

重点验的是 docs/task-state-machine.md 第五节那两条硬性约束:
  1. 上报失败绝不能影响主流程
  2. 上报不能阻塞 LLM 调用

不用 pytest-asyncio, 每个测试内部 asyncio.run —— 少一个依赖, 也让
"这个测试自己管自己的事件循环"这件事显式一点。
"""

from __future__ import annotations

import asyncio
import json
import time

import httpx
from langchain_core.messages import AIMessage
from langchain_core.tools import tool
from langgraph.graph import END, MessagesState, StateGraph
from langgraph.prebuilt import ToolNode
from pydantic import ValidationError

from app.task_state import (
    COMPLETED,
    FAILED,
    THINKING,
    TOOL_CALLING,
    TaskStateCallbackHandler,
    TaskStateReporter,
)


class FakeJava:
    """假的 Java 任务服务: 记录收到的每一次状态上报。"""

    def __init__(self, fail_statuses: set[str] | None = None) -> None:
        self.received: list[dict] = []
        self.fail_for = fail_statuses or set()

    def transport(self) -> httpx.MockTransport:
        def handle(request: httpx.Request) -> httpx.Response:
            payload = json.loads(request.content)
            self.received.append(payload)
            if payload["toStatus"] in self.fail_for:
                return httpx.Response(500, text="java 内部错误")
            return httpx.Response(200, json={"ok": True})

        return httpx.MockTransport(handle)

    def statuses(self) -> list[str]:
        return [item["toStatus"] for item in self.received]


def make_reporter(fake: FakeJava) -> tuple[TaskStateReporter, httpx.AsyncClient]:
    client = httpx.AsyncClient(transport=fake.transport())
    reporter = TaskStateReporter(
        base_url="http://fake-java:8083",
        task_id="T-1",
        client=client,
    )
    return reporter, client


# ----------------------------------------------------------------------
#  约束 1: 上报失败绝不能影响主流程
# ----------------------------------------------------------------------


def test_java_rejecting_a_report_does_not_stop_later_reports() -> None:
    """Java 用错误状态码拒掉一条上报, 后面的照常发 —— 尤其是最后的 COMPLETED。

    这条守的不是"网络异常"而是"HTTP 错误码": 409 (非法流转) 是最典型的场景 ——
    Python 报了个不该报的状态, Java 拒绝它, 但【绝不能】因此中断上报。
    """
    fake = FakeJava(fail_statuses={TOOL_CALLING})

    async def scenario() -> list[str]:
        reporter, client = make_reporter(fake)
        await reporter.start()
        reporter.report(THINKING, {})
        reporter.report(TOOL_CALLING, {})  # 这条会拿到 409/500
        reporter.report(COMPLETED, {})  # 这条必须照常发出
        await reporter.aclose()
        await client.aclose()
        return fake.statuses()

    assert asyncio.run(scenario()) == [THINKING, TOOL_CALLING, COMPLETED]


def test_java_unreachable_does_not_stop_later_reports() -> None:
    """Java 完全不可达: 不抛异常, 且 worker 不会被第一条失败打死。

    这条才是真正守 try/except 的 —— 换成 HTTP 错误码是打不中它的,
    因为 _patch 对 4xx/5xx 只是记警告、根本不抛。
    这里的 ConnectError 才会穿透到 _run 的 except 上。
    """
    attempts: list[str] = []

    def refuse(request: httpx.Request) -> httpx.Response:
        attempts.append(json.loads(request.content)["toStatus"])
        raise httpx.ConnectError("connection refused")

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(refuse))
        reporter = TaskStateReporter(
            base_url="http://nowhere:9999", task_id="T-1", client=client
        )
        await reporter.start()
        reporter.report(THINKING, {})
        reporter.report(COMPLETED, {})
        await reporter.aclose()  # ← 不该抛
        await client.aclose()

    asyncio.run(scenario())
    # 两条都被【尝试发送】了 —— 第一条的网络错误没有打死 worker
    assert attempts == [THINKING, COMPLETED]


# ----------------------------------------------------------------------
#  约束 2: 上报不能阻塞 LLM 调用
# ----------------------------------------------------------------------


def test_report_returns_immediately_even_without_worker() -> None:
    """report() 必须是同步立即返回的。

    这里刻意【不调用 start()】, 也就是让队列永远没人消费 —— 这是最坏情况。
    如果哪天有人把 report() 改成同步发 HTTP, 这个测试会立刻失败。

    它守的是: on_llm_start 跑在 Agent 的关键路径上, 那里不能等网络。
    """

    async def scenario() -> float:
        reporter, client = make_reporter(FakeJava())
        started = time.perf_counter()
        for _ in range(100):
            reporter.report(THINKING, {})
        elapsed = time.perf_counter() - started
        await client.aclose()
        return elapsed

    assert asyncio.run(scenario()) < 0.05, "report() 阻塞了, 它在关键路径上"


# ----------------------------------------------------------------------
#  顺序与生命周期
# ----------------------------------------------------------------------


def test_reports_are_serialized_in_order() -> None:
    """串行上报, 顺序与调用顺序一致 —— 状态机的价值就在顺序。"""

    async def scenario() -> list[str]:
        fake = FakeJava()
        reporter, client = make_reporter(fake)
        await reporter.start()
        # 模拟一次 ReAct 的两轮循环
        reporter.report(THINKING, {"phase": "llm"})
        reporter.report(TOOL_CALLING, {"toolName": "get_order_by_id"})
        reporter.report(THINKING, {"phase": "llm"})
        reporter.report(COMPLETED, {})
        await reporter.aclose()
        await client.aclose()
        return fake.statuses()

    assert asyncio.run(scenario()) == [
        THINKING,
        TOOL_CALLING,
        THINKING,
        COMPLETED,
    ]


def test_report_before_start_is_not_lost() -> None:
    """先入队、后启动 worker, 数据不能丢。"""

    async def scenario() -> list[str]:
        fake = FakeJava()
        reporter, client = make_reporter(fake)
        reporter.report(THINKING, {})  # worker 还不存在
        await reporter.start()
        await reporter.aclose()
        await client.aclose()
        return fake.statuses()

    assert asyncio.run(scenario()) == [THINKING]


def test_request_shape() -> None:
    """报文的 URL / 方法 / 字段名要和 Java 侧约定一致。"""

    async def scenario() -> dict:
        seen: dict = {}

        def handle(request: httpx.Request) -> httpx.Response:
            seen["method"] = request.method
            seen["url"] = str(request.url)
            seen["body"] = json.loads(request.content)
            return httpx.Response(200, json={})

        client = httpx.AsyncClient(transport=httpx.MockTransport(handle))
        # base_url 故意带尾斜杠, 验证会被规整掉
        reporter = TaskStateReporter(
            base_url="http://java:8083/", task_id="T-42", client=client
        )
        await reporter.start()
        reporter.report(TOOL_CALLING, {"toolName": "x"})
        await reporter.aclose()
        await client.aclose()
        return seen

    seen = asyncio.run(scenario())
    assert seen["method"] == "PATCH"
    assert seen["url"] == "http://java:8083/api/tasks/T-42/status"
    assert seen["body"] == {
        "toStatus": TOOL_CALLING,
        "source": "PYTHON_RUNTIME",
        "detail": {"toolName": "x"},
    }


# ----------------------------------------------------------------------
#  LangChain 事件 -> 状态 的映射
# ----------------------------------------------------------------------


def test_callback_reports_progress_states_only() -> None:
    """on_llm_start -> THINKING, on_tool_start -> TOOL_CALLING。

    回调【不报终态】—— 见下一个测试, 那不是随便定的。
    """

    async def scenario() -> list[dict]:
        fake = FakeJava()
        reporter, client = make_reporter(fake)
        handler = TaskStateCallbackHandler(reporter)
        await reporter.start()

        await handler.on_llm_start({"name": "ChatOpenAI"}, ["你好"])
        await handler.on_tool_start({"name": "get_order_by_id"}, "{}")
        await handler.on_llm_start({"name": "ChatOpenAI"}, ["查到了"])

        await reporter.aclose()
        await client.aclose()
        return fake.received

    received = asyncio.run(scenario())

    assert [item["toStatus"] for item in received] == [
        THINKING,
        TOOL_CALLING,
        THINKING,
    ]
    assert received[1]["detail"]["toolName"] == "get_order_by_id"
    # source 恒定, Java 侧靠它区分是谁改的状态
    assert all(item["source"] == "PYTHON_RUNTIME" for item in received)
    # 终态一律不从这里出去
    assert not {COMPLETED, FAILED} & {item["toStatus"] for item in received}


def test_tool_validation_error_does_not_report_a_terminal_state() -> None:
    """回归防线: ToolNode 吞掉 ValidationError 后图会继续跑, 此时绝不能报终态。

    2026-10-04 实测发现的坑, 两种工具错误的行为【完全不同】:

      * 普通异常 (ValueError, 或 Java 后端 500)
            -> 穿透出图, ainvoke 整体抛错, 任务确实失败了
      * pydantic ValidationError
            -> ToolNode 的 _default_handle_tool_errors 只认这一种,
               吞成 ToolMessage(status="error"), 图【继续跑】,
               Agent 随后可能给出正确答案, HTTP 200

    而 on_tool_error 在【两种情况下都会触发】。早期版本把它映射成 FAILED,
    于是第二种情况下任务被钉成终态, 图却还在跑 ——
    后续的 THINKING / COMPLETED 会被 Java 以 409 全部拒掉。

    用例 B (structuredContent 必须是 JSON object) 就是第二种情况,
    所以这条不是假想的边界, 是已经踩过的坑。
    """

    @tool
    def boom_validation_error(x: str) -> str:
        """模拟用例 B: MCP 响应解析失败。"""
        raise ValidationError.from_exception_data(
            "Response",
            [{"type": "int_parsing", "loc": ("structuredContent",), "input": x}],
        )

    async def scenario() -> list[str]:
        fake = FakeJava()
        reporter, client = make_reporter(fake)
        handler = TaskStateCallbackHandler(reporter)

        graph = StateGraph(MessagesState)
        graph.add_node("tools", ToolNode([boom_validation_error]))
        graph.set_entry_point("tools")
        graph.add_edge("tools", END)
        app = graph.compile()

        message = AIMessage(
            content="",
            tool_calls=[
                {"name": "boom_validation_error", "args": {"x": "1"}, "id": "c1"}
            ],
        )

        await reporter.start()
        result = await app.ainvoke(
            {"messages": [message]}, config={"callbacks": [handler]}
        )
        await reporter.aclose()
        await client.aclose()

        # 前置校验: 确认这确实是"图继续跑"的那一类错误。
        # 如果哪天 langgraph 改了默认行为让异常穿透, 这个断言会先炸,
        # 而不是让下面那条静悄悄地空转通过。
        assert getattr(result["messages"][-1], "status", None) == "error", (
            "ToolNode 不再吞 ValidationError 了 —— "
            "这条测试的前提变了, 需要重新评估 on_tool_error 的映射"
        )
        return fake.statuses()

    assert asyncio.run(scenario()) == [TOOL_CALLING]
