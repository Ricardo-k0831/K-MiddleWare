"""任务身份的透传测试 —— Python 怎么把"这是哪个任务"送到 Java 网关。

配套: docs/audit.md 第三节 (网关凭什么知道这次调用属于哪个任务),
实现在 app/mcp_adapter/task_identity.py。

## 这里验的是什么

不是"头发出去了", 而是 **每次请求用的是【当前那个】任务的身份**。

这两者差别极大, 也是这个模块存在的全部理由:

    headers 方案: 一个 client 一套头。Agent 先跑任务 A 再跑任务 B,
                  任务 B 的工具调用被记到任务 A 名下 —— 审计里【看不出来】。
    auth 方案:    每个请求读一次 ContextVar。同一连接依次服务不同任务。

所以第一个测试就是"同一个 client 连着发两次, 两次的身份不一样"。
把实现退回 headers 方案的话, 它会立刻变红 —— 这是本文件的主要价值。

## 不用 pytest-asyncio

每个测试内部 asyncio.run, 与 tests/test_chat_task_wiring.py 保持一致。
"""

from __future__ import annotations

import asyncio

import httpx
import pytest

from app.config import Settings
from app.mcp_adapter.langchain_provider import LangChainMcpToolProvider
from app.mcp_adapter.task_identity import (
    ACTOR_HEADER,
    TASK_ID_HEADER,
    TENANT_ID_HEADER,
    TRACE_ID_HEADER,
    TaskIdentity,
    TaskIdentityAuth,
    current_task_identity,
    task_identity,
)

TASK_A = TaskIdentity(
    task_id="task-a", trace_id="trace-a", tenant_id="km", actor="u-1"
)


class Wire:
    """一个假的网关: 记下每个 POST 实际收到的头。

    存 httpx.Headers 而不是 dict: 它的 get() 是大小写不敏感的, 和 Java 侧
    (HttpHeaders.getFirst) 读头的方式一致 —— HTTP 头名本来就不区分大小写。
    要验"拼写是否逐字一致"时用 `raw`, 那里保留着发送时的原始大小写。
    """

    def __init__(self) -> None:
        self.seen: list[httpx.Headers] = []

    def transport(self) -> httpx.MockTransport:
        def handle(request: httpx.Request) -> httpx.Response:
            self.seen.append(request.headers)
            return httpx.Response(200, json={"ok": True})

        return httpx.MockTransport(handle)

    def km_names(self, index: int) -> list[str]:
        """第 index 次请求带的所有 KM 头名, 按原始大小写。"""
        return [
            name.decode()
            for name, _ in self.seen[index].raw
            if name.lower().startswith(b"x-km-")
        ]


def make_client(wire: Wire) -> httpx.AsyncClient:
    """模拟 MCP 客户端建连接的方式 —— 和 langchain-mcp-adapters
    `_create_streamable_http_session` 里那句
    `client_factory(headers=..., timeout=..., auth=auth)` 同形。
    """
    return httpx.AsyncClient(transport=wire.transport(), auth=TaskIdentityAuth())


def post_with(client: httpx.AsyncClient, identity: TaskIdentity | None) -> None:
    """在一个身份作用域里发一次请求。"""
    if client.is_closed:
        raise AssertionError("client 已经被关掉了, 复用同一个实例才测得到逐请求语义")

    async def _run() -> None:
        async with client:
            with task_identity(identity):
                await client.post("http://gw/mcp", json={})

    asyncio.run(_run())


# ----------------------------------------------------------------------
#  逐请求 —— 本文件的核心
# ----------------------------------------------------------------------


def test_同一个client连着发两次_两次用的是各自的身份():
    """★ 最重要的一条 ★

    退回"往 AsyncClient 的 headers 上加"这个实现, 这里就会红:
    两次都会是 task-a。
    """
    wire = Wire()
    client = make_client(wire)

    async def _run() -> None:
        async with client:
            with task_identity(TASK_A):
                await client.post("http://gw/mcp", json={})
            with task_identity(TaskIdentity(task_id="task-b", actor="u-2")):
                await client.post("http://gw/mcp", json={})

    asyncio.run(_run())

    assert wire.seen[0].get(TASK_ID_HEADER) == "task-a"
    assert wire.seen[1].get(TASK_ID_HEADER) == "task-b", (
        "第二次请求带的是上一个任务的身份 —— 工具调用会被记到错的任务名下, "
        "而且从审计里看不出来"
    )


def test_并发任务之间身份不串():
    """asyncio 下同一时刻可能有多个任务在跑。

    这也是为什么不能用全局变量: 全局变量在这里会让两个任务都拿到最后
    写入的那个身份, 而且串得毫无规律 —— 只在压测时才看得出来。
    """
    wire = Wire()
    client = make_client(wire)

    async def worker(name: str) -> None:
        with task_identity(TaskIdentity(task_id=name)):
            # 让出控制权, 确保两个协程真的交错执行
            await asyncio.sleep(0)
            await client.post("http://gw/mcp", json={})

    async def _run() -> None:
        async with client:
            await asyncio.gather(worker("task-1"), worker("task-2"), worker("task-3"))

    asyncio.run(_run())

    assert sorted(h.get(TASK_ID_HEADER, "") for h in wire.seen) == [
        "task-1",
        "task-2",
        "task-3",
    ]


def test_身份能传播进create_task起的子任务():
    """真实链路上, LangGraph 内部会起子任务来调度工具调用。

    ContextVar 会自动跟着任务走, 所以身份不需要被当参数一层层传下去 ——
    这正是选它而不是"把 identity 传进每个函数"的理由。
    """
    wire = Wire()
    client = make_client(wire)

    async def _run() -> None:
        async with client:
            with task_identity(TASK_A):
                task = asyncio.create_task(client.post("http://gw/mcp", json={}))
                await task

    asyncio.run(_run())

    assert wire.seen[0].get(TASK_ID_HEADER) == "task-a"


# ----------------------------------------------------------------------
#  带头的方式
# ----------------------------------------------------------------------


def test_四个头一起带_名字和Java侧逐字一致():
    """用 .raw 而不是 .items(): items() 给出的是小写, 那样就验不出"头名的
    拼写和 Java 侧的常量是不是真的对得上"了 —— 而这恰恰是这里唯一会错的
    地方 (写错名字不报错, 只是网关抽不到, 审计里 task_id 一列全空)。
    """
    wire = Wire()
    client = make_client(wire)
    post_with(client, TASK_A)

    assert wire.km_names(0) == [
        TASK_ID_HEADER,
        TRACE_ID_HEADER,
        TENANT_ID_HEADER,
        ACTOR_HEADER,
    ]
    assert wire.seen[0].get(ACTOR_HEADER) == "u-1"


def test_没给的字段就不带_而不是带空值():
    """空串到了 Java 侧也会被当成 null, 但发送端塞一个空头只会让人以为
    是调用方传了个空值 —— 排查时会往错的方向找。
    """
    wire = Wire()
    client = make_client(wire)
    post_with(client, TaskIdentity(task_id="task-a"))

    assert wire.km_names(0) == [TASK_ID_HEADER]


def test_没有身份时不带任何KM头():
    """不带 task_id 的调用是合法的 (拉工具列表、手工调试、非任务型对话)。

    这里刻意不告警 —— 该提醒的位置在 Java 网关, 它的条件是"一次工具调用
    没带身份", 那才说明是客户端漏了。两边都喊就成了狼来了。
    """
    wire = Wire()
    client = make_client(wire)
    post_with(client, None)

    assert wire.km_names(0) == []


# ----------------------------------------------------------------------
#  作用域的还原
# ----------------------------------------------------------------------


def test_出块之后身份被还原():
    assert current_task_identity() is None

    with task_identity(TASK_A):
        assert current_task_identity() is TASK_A

    assert current_task_identity() is None


def test_嵌套时退出内层回到外层而不是清空():
    """★ 这条钉住的是实现里的 token + reset ★

    写成在 finally 里 `set(None)` 的话, 出内层之后外层身份就【丢了】,
    而症状是审计里一片 NULL —— 又会被人当成客户端没带头去查。
    """
    outer = TaskIdentity(task_id="outer")
    inner = TaskIdentity(task_id="inner")

    with task_identity(outer):
        with task_identity(inner):
            assert current_task_identity() is inner
        assert current_task_identity() is outer, "出内层把外层的身份也清掉了"

    assert current_task_identity() is None


# ----------------------------------------------------------------------
#  身份穿得过 LangGraph 的工具调度吗
# ----------------------------------------------------------------------
#
#  ★ 这是整套设计里最要命的一个假设 ★
#
#  ContextVar 是【按任务 (asyncio task)】隔离的。而 /chat 里绑身份的地方
#  和真正读身份的地方 (MCP 工具内部的 httpx), 隔着 LangGraph 一整套调度:
#  图节点 → ToolNode → 工具协程。中间只要有一环把工具丢进别的线程/任务去跑,
#  身份就丢了 —— 表现是审计里 agent_task_id 全空, 而工具调用一切正常,
#  看起来像是 Java 网关的锅。
#
#  实测结论 (2026-10-14, langgraph 1.2.11): 两种工具都保得住。
#  异步工具走的是同一条 asyncio 任务; 同步工具虽然被丢进线程, 但 langgraph
#  用的是 asyncio.to_thread —— 它【按设计会复制当前的 context】,
#  所以身份照样跟得过去。
#
#  下面这个测试直接钉住这个假设。langgraph 哪天换了调度方式, 它会红,
#  而不是等到线上发现审计里一片 NULL。


def _runtime_config() -> dict:
    """ToolNode 单独调用时需要手动塞一个 runtime。

    走完整张图时这是 pgraph 自己接上的, 这里绕过了图, 所以要自己接一
    langgraph 的内部常量。用内部 API 是刻意的: 这个测试的价值就在于跑
    【真的 ToolNode】而不是一个自己写的假调度器 —— 假调度器保不住身份,
    恰恰说明不了任何问题。
    """
    from langgraph._internal._constants import CONF, CONFIG_KEY_RUNTIME
    from langgraph.runtime import Runtime

    return {CONF: {CONFIG_KEY_RUNTIME: Runtime()}}


@pytest.mark.parametrize("async_tool", [True, False], ids=["异步工具", "同步工具"])
def test_身份穿得过LangGraph的工具调度(async_tool):
    from langchain_core.messages import AIMessage
    from langchain_core.tools import StructuredTool
    from langgraph.prebuilt import ToolNode

    seen: list[TaskIdentity | None] = []

    async def a_coro(orderId: str) -> str:
        seen.append(current_task_identity())
        return "ok"

    def s_coro(orderId: str) -> str:
        seen.append(current_task_identity())
        return "ok"

    tool = (
        StructuredTool.from_function(coroutine=a_coro, name="t", description="d")
        if async_tool
        else StructuredTool.from_function(func=s_coro, name="t", description="d")
    )
    node = ToolNode([tool])
    call = {
        "messages": [
            AIMessage(
                content="",
                tool_calls=[
                    {"name": "t", "args": {"orderId": "X"}, "id": "c1", "type": "tool_call"}
                ],
            )
        ]
    }

    async def _run() -> None:
        with task_identity(TASK_A):
            await node.ainvoke(call, config=_runtime_config())

    asyncio.run(_run())

    assert seen == [TASK_A], (
        "身份没能穿过 LangGraph 的工具调度 —— 工具内部读到的不是任务 A。"
        "审计里的 agent_task_id 会全空, 而工具调用一切正常"
    )


# ----------------------------------------------------------------------
#  接线: provider 真的把 auth 挂上去了吗
# ----------------------------------------------------------------------


def test_provider的连接配置里挂了身份_auth():
    """防止有人"顺手清理"掉 _server_config 里的 auth 那一行。

    删了它不会报任何错 —— 网关只是抽不到身份, 审计里 agent_task_id 全空,
    而工具调用一切正常。这种"静默失效"必须有测试挡着。
    """
    config = LangChainMcpToolProvider._server_config(Settings())
    order = config["km-order"]

    assert isinstance(order.get("auth"), TaskIdentityAuth)
    # 鉴权头仍然在 headers 上 —— 身份和鉴权是两件事, 别互相替代
    assert "Authorization" in order["headers"]
