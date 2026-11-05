"""app.checkpoint 的测试 —— 记忆后端与"这一轮该喂什么输入"。

## 为什么跑真 LangGraph 而不是 mock

断点语义 (`next` 非空 = 有节点没跑完, `ainvoke(None)` 沿着它继续) 是
LangGraph 的内部契约。手搓一个"next=('agent',)"的假快照只能证明我们的
if 写对了, 证明不了真实版本在节点抛错后确实留下挂起状态 —— 而那正是
断点续跑成立的唯一前提。所以这里用一张最小状态图 + InMemorySaver
真的跑一遍崩溃/恢复。

判据本身 (三种处置的取舍) 再在 plan_invocation 上单独覆盖, 两种手段互补:
真图验"假设成立", 假快照验"边界选对"。
"""

from __future__ import annotations

import asyncio
import socket
import sys
from typing import Annotated, TypedDict

import pytest
from fastapi.testclient import TestClient
from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver
from langgraph.graph import END, START, StateGraph
from langgraph.graph.message import add_messages

import app.main as main_module
from app.checkpoint import (
    BACKEND_MEMORY,
    BACKEND_POSTGRES,
    CANCEL_MESSAGE,
    MODE_BUSY,
    MODE_FRESH,
    MODE_REPLAY,
    MODE_RESUME,
    CheckpointResources,
    build_checkpointer,
    memory_resources,
    normalize_backend,
    plan_invocation,
    release_pending,
    resolve_thread_id,
)
from app.config import Settings

# ----------------------------------------------------------------------
#  后端名与装配
# ----------------------------------------------------------------------


def test_后端名规整():
    assert normalize_backend("memory") == BACKEND_MEMORY
    assert normalize_backend("POSTGRES") == BACKEND_POSTGRES
    assert normalize_backend("  Postgres  ") == BACKEND_POSTGRES
    # 空值按默认处理: 没配置 = 用最保守的内存后端
    assert normalize_backend("") == BACKEND_MEMORY
    assert normalize_backend(None) == BACKEND_MEMORY


def test_拼错的后端名直接报错():
    """★ 静默退回内存是最坏的结果 ★

    部署方以为在持久化, 其实每次重启都在丢断点 —— 而且只有"重试时没续上"
    这种间接现象能暴露它。宁可启动失败。
    """
    with pytest.raises(ValueError):
        normalize_backend("postgress")


def test_内存后端不需要连接池():
    async def _go() -> CheckpointResources:
        resources = await build_checkpointer(Settings(checkpoint_backend="memory"))
        assert resources.backend == BACKEND_MEMORY
        assert resources.pool is None
        assert isinstance(resources.saver, InMemorySaver)
        # 没池也要能安全收尾 (lifespan 无条件调 aclose)
        await resources.aclose()
        return resources

    asyncio.run(_go())


def test_关闭时释放连接池():
    class FakePool:
        def __init__(self) -> None:
            self.closed = False

        async def close(self) -> None:
            self.closed = True

    async def _go() -> None:
        pool = FakePool()
        resources = CheckpointResources(saver=object(), pool=pool, backend=BACKEND_POSTGRES)
        await resources.aclose()
        assert pool.closed, "连接池没关 —— 进程退出会挂住 / Postgres 留下一堆 idle 连接"
        assert resources.pool is None
        # 重复关闭必须是安全的 (lifespan 的降级路径可能调两次)
        await resources.aclose()

    asyncio.run(_go())


def test_降级资源带原因():
    resources = memory_resources(degraded_reason="OperationalError: 连不上")
    assert resources.backend == BACKEND_MEMORY
    assert resources.degraded_reason and "连不上" in resources.degraded_reason


def test_Windows的Proactor循环给出可执行的报错():
    """★ 先于连接池失败 ★

    psycopg 在 Proactor 上连不上时, 报错会被连接池重试 + PoolTimeout 淹掉,
    看起来像"Postgres 没起来"。宁可在这里换成一个能照着做的提示。
    """
    if sys.platform != "win32":
        pytest.skip("ProactorEventLoop 只存在于 Windows")

    async def _go() -> None:
        with pytest.raises(RuntimeError, match="SelectorEventLoop"):
            await build_checkpointer(Settings(checkpoint_backend="postgres"))

    asyncio.run(_go(), loop_factory=asyncio.ProactorEventLoop)


# ----------------------------------------------------------------------
#  线程身份: 会话是记忆单位, 任务是执行身份
# ----------------------------------------------------------------------


def test_会话优先于任务并且带租户前缀():
    """同一个会话里的多个任务必须落在同一个 thread 上 —— 这是会话记忆的全部。"""
    assert resolve_thread_id(
        session_id="s-1", tenant_id="km", task_id="task-1", thread_id="x"
    ) == "session:km:s-1"


def test_没有会话时任务自己成线程():
    assert resolve_thread_id(task_id="task-1", thread_id="x") == "task:task-1"


def test_两个身份都没有时用调用方线程():
    assert resolve_thread_id(thread_id="chat-1") == "chat-1"
    assert resolve_thread_id() == "default"
    # 空串按"没给"处理, 不能变成空 thread_id
    assert resolve_thread_id(session_id="  ", task_id=" ", thread_id="") == "default"


def test_不同租户的同名会话不会共享记忆():
    """session_id 是调用方自己起的名字, 只在租户内唯一 —— key 必须带租户。"""
    a = resolve_thread_id(session_id="same", tenant_id="tenant-a")
    b = resolve_thread_id(session_id="same", tenant_id="tenant-b")
    assert a != b


# ----------------------------------------------------------------------
#  真图的崩溃 / 恢复语义
# ----------------------------------------------------------------------


class _State(TypedDict):
    messages: Annotated[list, add_messages]


def _build_graph(*, fail_first: bool = False):
    """一张最小图: 一个节点, 产出固定答复。

    和一个 stateful 的计数器配合, 用来观察"节点到底跑了几次" ——
    复述 (replay) 的核心断言就是"节点没有第二次执行"。
    """
    calls = {"n": 0}

    def answer(state: _State) -> dict:
        calls["n"] += 1
        if fail_first and calls["n"] == 1:
            raise RuntimeError("模拟节点崩溃")
        return {"messages": [AIMessage(content="答案")]}

    graph = StateGraph(_State)
    graph.add_node("answer", answer)
    graph.add_edge(START, "answer")
    graph.add_edge("answer", END)
    return graph.compile(checkpointer=InMemorySaver()), calls


def _config(thread_id: str, task_id: str | None = None) -> dict:
    """构造 config; task_id 会作为 metadata 落进 checkpoint —— 任务归属靠它。"""
    config: dict = {"configurable": {"thread_id": thread_id}}
    if task_id is not None:
        config["metadata"] = {"task_id": task_id}
    return config


def test_节点崩溃后留下挂起状态并可续跑():
    """★ 断点续跑的基石 ★

    崩溃点必须表现为 next 非空 + 已写入的用户消息。如果 LangGraph 的行为
    变了 (比如崩溃回滚了输入), 这里的断言会先失败, 而不是等线上重试时
    才发现"续跑"其实在重跑。
    """

    async def _go() -> None:
        graph, calls = _build_graph(fail_first=True)
        config = _config("crash-1", task_id="task-1")
        with pytest.raises(RuntimeError):
            await graph.ainvoke(
                {"messages": [{"role": "user", "content": "查订单"}]}, config
            )

        plan = await plan_invocation(graph, config, "查订单", task_id="task-1")
        assert plan.mode == MODE_RESUME
        assert plan.payload is None, "续跑必须用空输入, 否则同一个问题会问两遍"
        assert plan.pending_nodes, "崩溃后应当有未执行的节点"
        assert plan.baseline_messages == 1, "调用前已有 1 条用户消息"

        result = await graph.ainvoke(plan.payload, config)
        contents = [m.content for m in result["messages"]]
        assert contents == ["查订单", "答案"], f"消息重复或丢失: {contents}"
        assert calls["n"] == 2

    asyncio.run(_go())


def test_已完成的任务链路只复述结果不再执行():
    """图跑完但终态没送到 Java 时, 重试应当便宜且幂等地补上那一条。"""

    async def _go() -> None:
        graph, calls = _build_graph()
        config = _config("done-1", task_id="task-1")
        first = await graph.ainvoke(
            {"messages": [{"role": "user", "content": "查订单"}]}, config
        )

        plan = await plan_invocation(graph, config, "查订单", task_id="task-1")
        assert plan.mode == MODE_REPLAY
        assert plan.payload is None
        assert plan.baseline_messages == len(first["messages"])

        again = await graph.ainvoke(plan.payload, config)
        assert calls["n"] == 1, "复述又把节点跑了一遍 —— 等于白花一次 LLM 的钱"
        contents = [m.content for m in again["messages"]]
        assert contents == ["查订单", "答案"], "复述不能往 thread 里追加消息"

    asyncio.run(_go())


def test_自由对话的新问题不会被挂起状态吞掉():
    """挂起的是旧问题, 用户已经问了新的 —— 新的必须进上下文。"""

    async def _go() -> None:
        graph, _ = _build_graph(fail_first=True)
        config = _config("free-1")
        with pytest.raises(RuntimeError):
            await graph.ainvoke(
                {"messages": [{"role": "user", "content": "旧问题"}]}, config
            )

        plan = await plan_invocation(graph, config, "新问题")
        assert plan.mode == MODE_FRESH
        assert plan.payload == {"messages": [{"role": "user", "content": "新问题"}]}

        result = await graph.ainvoke(plan.payload, config)
        contents = [m.content for m in result["messages"]]
        assert "新问题" in contents, "空输入续跑会把这条消息彻底吞掉"
        assert contents[-1] == "答案"

    asyncio.run(_go())


def test_自由对话重发同一句话才续跑():
    async def _go() -> None:
        graph, _ = _build_graph(fail_first=True)
        config = _config("free-2")
        with pytest.raises(RuntimeError):
            await graph.ainvoke(
                {"messages": [{"role": "user", "content": "同一个问题"}]}, config
            )

        plan = await plan_invocation(graph, config, "同一个问题")
        assert plan.mode == MODE_RESUME
        assert plan.payload is None

    asyncio.run(_go())


def test_自由对话已完成的历史不触发复述():
    """同一个 thread 的第二句话是新提问, 即使上一轮有 AI 答复也不能复述。"""

    async def _go() -> None:
        graph, _ = _build_graph()
        config = _config("free-3")
        await graph.ainvoke({"messages": [{"role": "user", "content": "你好"}]}, config)

        plan = await plan_invocation(graph, config, "查订单")
        assert plan.mode == MODE_FRESH
        assert plan.baseline_messages == 2

    asyncio.run(_go())


def test_同一会话的两个任务共享历史但不共享答案():
    """会话记忆的实际效果, 用真图跑一遍:

    任务 B 必须看到任务 A 的问答 (上下文连续), 但如果 A 已经答完, B 不能
    拿 A 的答案当自己的结果 —— 那是复述, 不是执行。
    """

    async def _go() -> None:
        graph, _ = _build_graph()
        config = _config("session-1", task_id="task-a")
        await graph.ainvoke(
            {"messages": [{"role": "user", "content": "任务A"}]}, config
        )

        plan = await plan_invocation(graph, config, "任务B", task_id="task-b")
        assert plan.mode == MODE_FRESH
        assert plan.baseline_messages == 2, "B 应当看到 A 的两条消息"

        result = await graph.ainvoke(plan.payload, config)
        contents = [m.content for m in result["messages"]]
        assert contents == ["任务A", "答案", "任务B", "答案"]

    asyncio.run(_go())


def test_会话里别的任务崩了会拒绝而不是接管():
    """真图 + 真 metadata: BUSY 的判据在真实快照上同样成立。"""

    async def _go() -> None:
        graph, _ = _build_graph(fail_first=True)
        config = _config("session-2", task_id="task-a")
        with pytest.raises(RuntimeError):
            await graph.ainvoke(
                {"messages": [{"role": "user", "content": "任务A"}]}, config
            )

        plan = await plan_invocation(graph, config, "任务B", task_id="task-b")
        assert plan.mode == MODE_BUSY
        assert plan.busy_task_id == "task-a"
        assert plan.pending_nodes, "拒绝的理由应当能报出来"

    asyncio.run(_go())


# ----------------------------------------------------------------------
#  判据的边界 —— 用假快照逐条钉住
# ----------------------------------------------------------------------


class _Snapshot:
    def __init__(
        self,
        messages: list | None = None,
        next_nodes: tuple = (),
        task_id: str | None = None,
    ) -> None:
        self.values = {"messages": list(messages or [])}
        self.next = next_nodes
        # 任务归属: 真实 StateSnapshot.metadata 里由 config["metadata"] 带进来
        self.metadata = {"task_id": task_id} if task_id is not None else {}


class _Agent:
    def __init__(self, snapshot: _Snapshot | None = None, error: Exception | None = None) -> None:
        self.snapshot = snapshot or _Snapshot()
        self.error = error

    async def aget_state(self, config: dict) -> _Snapshot:
        if self.error is not None:
            raise self.error
        return self.snapshot


def _plan(agent, message: str, *, task_id: str | None = None):
    return asyncio.run(
        plan_invocation(agent, {"configurable": {"thread_id": "t"}}, message,
                        task_id=task_id)
    )


def test_空thread按新对话处理():
    plan = _plan(_Agent(), "查订单", task_id="task-1")
    assert plan.mode == MODE_FRESH
    assert plan.baseline_messages == 0


def test_图没有aget_state时按新对话处理():
    """没有 checkpointer 的图不该被断点逻辑判死。"""

    class Bare:
        async def ainvoke(self, payload, config=None):
            return {}

    plan = asyncio.run(
        plan_invocation(Bare(), {"configurable": {"thread_id": "t"}}, "hi",
                        task_id="task-1")
    )
    assert plan.mode == MODE_FRESH


def test_读快照失败时按新对话处理():
    plan = _plan(_Agent(error=RuntimeError("postgres 抖了")), "查订单", task_id="task-1")
    assert plan.mode == MODE_FRESH


def test_任务链路最后一条是用户消息时不复述():
    """理论上不该出现 (ReAct 结束一定以 AI 消息收尾), 但真出现时不能把
    用户的问题当成答案返回 —— 那比重新执行一次糟得多。"""
    snapshot = _Snapshot(messages=[HumanMessage(content="查订单")], task_id="task-1")
    plan = _plan(_Agent(snapshot), "查订单", task_id="task-1")
    assert plan.mode == MODE_FRESH


def test_即使消息内容相同任务链路也不重复追加():
    """重试的 message 永远是 task.intent; 只要同一个任务挂起就必须续上。"""
    snapshot = _Snapshot(
        messages=[HumanMessage(content="查订单")], next_nodes=("agent",), task_id="task-1"
    )
    plan = _plan(_Agent(snapshot), "查订单", task_id="task-1")
    assert plan.mode == MODE_RESUME
    assert plan.payload is None


# ----------------------------------------------------------------------
#  会话线程里的任务归属 —— 这套设计的核心不变量
# ----------------------------------------------------------------------


def test_别的任务的挂起不续跑():
    """★ 会话记忆的前提是"能共享", 但共享不等于"能接管别人的执行" ★"""
    snapshot = _Snapshot(
        messages=[HumanMessage(content="任务A的问题")],
        next_nodes=("agent",),
        task_id="task-a",
    )
    plan = _plan(_Agent(snapshot), "任务B的问题", task_id="task-b")

    assert plan.mode == MODE_BUSY
    assert plan.payload is None, "BUSY 绝不能带着输入去碰别人的挂起状态"
    assert plan.busy_task_id == "task-a"


def test_没有归属的挂起对任务也是占用():
    """自由对话崩在半路时, 任务不能把它的挂起节点当成自己的断点。"""
    snapshot = _Snapshot(
        messages=[HumanMessage(content="随便聊聊")],
        next_nodes=("agent",),
    )
    plan = _plan(_Agent(snapshot), "查订单", task_id="task-1")
    assert plan.mode == MODE_BUSY
    assert plan.busy_task_id is None


def test_别的任务留下的完成状态不能复述():
    """★ 会话线程下最容易犯的错 ★

    会话里最后一条是 AI 消息, 但那可能是上一个任务的答案。把它复述给新任务
    等于答非所问, 还省掉了一次本该发生的执行。
    """
    snapshot = _Snapshot(
        messages=[HumanMessage(content="任务A"), AIMessage(content="任务A的答案")],
        task_id="task-a",
    )
    plan = _plan(_Agent(snapshot), "任务B", task_id="task-b")

    assert plan.mode == MODE_FRESH
    assert plan.baseline_messages == 2, "新任务要在完整会话历史上追加"


def test_自由对话不碰任务留下的挂起():
    """同一个会话里, 用户的手动提问不能把正在跑的任务接走。"""
    snapshot = _Snapshot(
        messages=[HumanMessage(content="任务A")],
        next_nodes=("agent",),
        task_id="task-a",
    )
    plan = _plan(_Agent(snapshot), "顺便问一句", task_id=None)
    assert plan.mode == MODE_BUSY
    assert plan.busy_task_id == "task-a"


def test_自由对话在任务已完成的会话里照常追加():
    snapshot = _Snapshot(
        messages=[HumanMessage(content="任务A"), AIMessage(content="任务A的答案")],
        task_id="task-a",
    )
    plan = _plan(_Agent(snapshot), "顺便问一句", task_id=None)
    assert plan.mode == MODE_FRESH
    assert plan.baseline_messages == 2


# ----------------------------------------------------------------------
#  lifespan 接线 —— 真的装配一次 Postgres, 真的关掉它
# ----------------------------------------------------------------------


def _postgres_reachable(host: str, port: int, timeout: float = 0.5) -> bool:
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        # 和服务没起时的处理方式一致: 先 skip 并提示怎么起。
        return False


def test_lifespan装配postgres并在退出时关池(monkeypatch):
    """验三件容易被"看起来启动了"掩盖的事:

    1. 配了 postgres, 图拿到的就是 PostgresSaver (而不是悄悄退回内存);
    2. /health 能看出后端 (排查"重启后为什么没续上"的第一站);
    3. lifespan 退出时连接池真的关了 —— 漏关不会报错, 只会让进程退不干净。
    """
    settings = Settings(checkpoint_backend="postgres")
    if not _postgres_reachable(settings.postgres_host, settings.postgres_port):
        pytest.skip(
            f"Postgres {settings.postgres_host}:{settings.postgres_port} 不可达 —— "
            "请先 docker compose -f docker/docker-compose.yml up -d postgres"
        )

    # TestClient 在后台线程里 new_event_loop(), 同样会落到 Windows 默认的
    # Proactor —— 和 psycopg 不兼容。这里临时换 policy, 用完还原。
    policy = None
    if sys.platform == "win32":
        policy = asyncio.get_event_loop_policy()
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())

    class DummyProvider:
        async def aclose(self) -> None:
            pass

    built: dict = {}

    async def fake_build_agent(provider, cfg, checkpointer):
        # 不拉 MCP 工具、不连 LLM —— 这里只验 checkpointer 的装配与生命周期。
        built["checkpointer"] = checkpointer
        return object()

    monkeypatch.setattr(main_module, "get_settings", lambda: settings)
    monkeypatch.setattr(main_module, "build_tool_provider", lambda cfg: DummyProvider())
    monkeypatch.setattr(main_module, "build_agent", fake_build_agent)

    old = (
        main_module.state.provider,
        main_module.state.agent,
        main_module.state.checkpoints,
        main_module.state.startup_error,
    )
    main_module.state.startup_error = None
    try:
        with TestClient(main_module.app) as client:
            health = client.get("/health").json()
            resources = main_module.state.checkpoints
            assert health["checkpoint_backend"] == BACKEND_POSTGRES
            assert health["checkpoint_degraded"] is None
            assert built["checkpointer"] is resources.saver
        assert resources.pool is None, "lifespan 退出没关连接池"
    finally:
        (
            main_module.state.provider,
            main_module.state.agent,
            main_module.state.checkpoints,
            main_module.state.startup_error,
        ) = old
        if policy is not None:
            asyncio.set_event_loop_policy(policy)


def test_health把记忆降级标成degraded():
    """服务能跑, 但持久化没生效 —— 这两件事必须同时从 /health 上看得出来。"""
    import httpx

    async def _go() -> None:
        old = (
            main_module.state.checkpoints,
            main_module.state.startup_error,
            main_module.state.agent,
        )
        main_module.state.checkpoints = memory_resources(
            degraded_reason="OperationalError: 连不上"
        )
        main_module.state.startup_error = None
        try:
            transport = httpx.ASGITransport(app=main_module.app)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
                body = (await client.get("/health")).json()
        finally:
            (
                main_module.state.checkpoints,
                main_module.state.startup_error,
                main_module.state.agent,
            ) = old

        assert body["status"] == "degraded"
        assert body["checkpoint_backend"] == BACKEND_MEMORY
        assert "连不上" in body["checkpoint_degraded"]

    asyncio.run(_go())


# ----------------------------------------------------------------------
#  取消: 把挂起状态收尾, 让会话线程重新可用
# ----------------------------------------------------------------------


def _build_release_graph(*, fail_first: bool = True):
    """节点名必须是 ``agent`` —— 和 create_react_agent 的图一致。

    ``release_pending`` 用 ``as_node="agent"`` 写入终止消息; 换名字就
    写不进去。这里刻意用真图而不是假快照: "写完之后 next 真的空了"
    是 LangGraph 用条件边重新计算的结果, 假对象证明不了。
    """
    calls = {"n": 0}

    def agent_node(state: _State) -> dict:
        calls["n"] += 1
        if fail_first and calls["n"] == 1:
            raise RuntimeError("模拟节点崩溃")
        return {"messages": [AIMessage(content="答案")]}

    graph = StateGraph(_State)
    graph.add_node("agent", agent_node)
    graph.add_edge(START, "agent")
    graph.add_edge("agent", END)
    return graph.compile(checkpointer=InMemorySaver()), calls


def test_取消后挂起被收尾并且会话可以被下一个任务使用():
    """★ 取消不只是"不再跑", 还要把占着线程的挂起清掉 ★

    不做这一步, ``plan_invocation`` 会一直看到 pending, 同会话的下一个
    任务永远 409 —— 而那个挂起的任务已经 CANCELLED, 永远不会有人来续跑。
    """

    async def _go() -> None:
        graph, calls = _build_release_graph()
        config = _config("cancel-1", task_id="task-a")
        with pytest.raises(RuntimeError):
            await graph.ainvoke(
                {"messages": [{"role": "user", "content": "查订单"}]}, config
            )

        before = await graph.aget_state(config)
        assert before.next, "崩溃后应当有挂起节点"

        released = await release_pending(graph, config, task_id="task-a")
        assert released is True

        after = await graph.aget_state(config)
        assert tuple(after.next) == (), f"取消后仍有挂起: {after.next}"
        contents = [m.content for m in after.values["messages"]]
        assert contents[0] == "查订单"
        assert contents[-1] == CANCEL_MESSAGE, "收尾消息应当写进会话历史"
        assert calls["n"] == 1, "收尾不能重新执行节点"

        # 会话解锁: 另一个任务进来应当开新一轮, 而不是 BUSY。
        plan = await plan_invocation(graph, config, "另一个问题", task_id="task-b")
        assert plan.mode == MODE_FRESH, f"会话仍被占用: {plan.mode}"

    asyncio.run(_go())


def test_没有挂起时取消是幂等的():
    """跑到一半之前就收到取消 (任务还在 PENDING) 不该报错或改历史。"""

    async def _go() -> None:
        graph, _ = _build_release_graph()
        config = _config("cancel-2", task_id="task-a")

        assert await release_pending(graph, config, task_id="task-a") is False

        # 再取消一次也一样: 没有挂起就是没有事要做。
        assert await release_pending(graph, config, task_id="task-a") is False

    asyncio.run(_go())


def test_取消不碰别的任务留下的挂起():
    """★ 归属校验: 只有自己任务的挂起才能收尾 ★

    会话线程里可能挂着另一个任务 (它还在跑)。取消 A 时把 B 的挂起写死,
    等于替 B 结束了一次执行 —— 而 B 的 Java 状态还是 THINKING。
    """

    async def _go() -> None:
        graph, _ = _build_release_graph()
        config = _config("cancel-3", task_id="task-a")
        with pytest.raises(RuntimeError):
            await graph.ainvoke(
                {"messages": [{"role": "user", "content": "A 的问题"}]}, config
            )

        assert await release_pending(graph, config, task_id="task-b") is False

        after = await graph.aget_state(config)
        assert after.next, "别的任务的挂起必须原样保留"
        contents = [m.content for m in after.values["messages"]]
        assert CANCEL_MESSAGE not in contents

    asyncio.run(_go())
