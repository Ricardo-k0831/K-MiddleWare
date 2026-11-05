"""LangGraph checkpointer 的装配与生命周期 —— "对话记忆"到底存在哪。

## 这里存的是什么, 不是什么

存的只有**对话消息历史 + 图的执行位置** (LLM 的上下文记忆)。
任务的生命周期状态 (PENDING / THINKING / COMPLETED / FAILED) 一律在 Java
侧, Python 不维护第二份 —— 见 ``app/graph/agent.py`` 头部那条约定。

两者混起来的后果是"两个事实源": Python 进程重启后拿 checkpoint 里的
旧状态去覆盖 Java, 或者反过来。所以这里只做一件事: 让"同一个 thread
下一次进来时看得到上一次说到哪"。

## 两个后端

* ``memory`` (默认) —— ``InMemorySaver``, 进程内。重启即丢。开发/测试用,
  不需要 Postgres 起得来。
* ``postgres`` —— ``AsyncPostgresSaver``, 落在 docker-compose 的 pgvector
  容器里 (库 ``km_memory``)。进程重启、换机器、多实例都能续上。

默认保持 ``memory``, 理由和 ``km.heartbeat`` / ``km.retry`` 一样:
**改变持久化语义是显式决定**, 不该因为代码合进来就生效。要断点续跑就设置::

    CHECKPOINT_BACKEND=postgres

## 为什么是 PostgresSaver 而不是自己写一张表

``LangGraph`` 的 checkpoint 结构 (每个 super-step 的 channel 快照 + 待执行
节点) 是它的内部契约, 自己存一份等于复刻一个会随版本失效的实现。
换成 PostgresSaver 之后 ``graph.aget_state(config)`` / ``ainvoke(None, config)``
这些能力都是现成的 —— 断点续跑就是它们的正常用法。

## 连接池

用 ``psycopg_pool.AsyncConnectionPool`` 而不是 ``from_conn_string``:
后者是给脚本/测试用的单连接上下文, 套在 FastAPI 的 lifespan 里会让
**所有并发请求共用一条连接**。池的 ``open=False`` + 显式 ``open(wait=True)``
是为了让启动失败时立刻抛错, 而不是第一条请求才炸。
"""

from __future__ import annotations

import asyncio
import logging
import sys
from dataclasses import dataclass
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage
from langgraph.checkpoint.memory import InMemorySaver

from app.config import Settings

logger = logging.getLogger(__name__)

BACKEND_MEMORY = "memory"
BACKEND_POSTGRES = "postgres"
_SUPPORTED_BACKENDS = (BACKEND_MEMORY, BACKEND_POSTGRES)

# 一次 /chat 对图的四种处置, 见 plan_invocation。
MODE_FRESH = "fresh"    # 追加新消息, 开新一轮
MODE_RESUME = "resume"  # 空输入, 让图从挂起的节点继续
MODE_REPLAY = "replay"  # 空输入, 图已经跑完 —— 只把结果再交一次
MODE_BUSY = "busy"      # 这个 thread 上有【别的任务】没跑完, 拒绝这次请求

# 线程身份的前缀。前缀不是装饰: 会话 id 和任务 id 都可能由调用方提供,
# 没有任何约束保证两者不会撞上。带上前缀之后, "这是会话线程还是任务线程"
# 从 thread_id 本身就能看出来, 排查时不用再去翻请求日志。
THREAD_SESSION_PREFIX = "session:"
THREAD_TASK_PREFIX = "task:"

# 解析 thread_id
def resolve_thread_id(
    *,
    session_id: str | None = None,
    tenant_id: str | None = None,
    task_id: str | None = None,
    thread_id: str | None = None,
) -> str:
    """把请求里的几种身份解析成 LangGraph 的 thread_id。

    优先级: ``session_id`` > ``task_id`` > 调用方自带的 ``thread_id``。

    * **有 session_id** —— 对话记忆按【会话】累积, 同一会话里的多个任务
      共享上下文。这是默认的、也是推荐的用法。
    * **只有 task_id** —— 兼容"外部系统一次任务一次调用"的场景: 记忆按任务
      隔离, 任务之间互不可见。
    * **两个都没有** —— 完全由调用方指定的 thread_id 决定 (缺省 ``default``)。

    ★ 为什么会话线程要带 tenant_id ★

    ``session_id`` 是调用方自己生成的 (比如 ``s-99b4d0a2``), 只在租户内部
    唯一。如果直接用 ``session:<sid>``, 两个租户恰好用了同一个 session_id
    就会读到彼此的对话历史 —— 这不是理论风险, 而是"客户端随便起个名字"
    这种最常见用法的必然后果。租户是会话的天然隔离边界, 必须进 key。

    任务线程不需要租户前缀: ``task_id`` 是 Java 侧生成的 UUID, 全局唯一。
    """
    session = (session_id or "").strip()
    if session:
        tenant = (tenant_id or "").strip() or "-"
        return f"{THREAD_SESSION_PREFIX}{tenant}:{session}"
    task = (task_id or "").strip()
    if task:
        return f"{THREAD_TASK_PREFIX}{task}"
    return (thread_id or "").strip() or "default"


@dataclass
class CheckpointResources:
    """checkpointer 及其背后的资源。

    把 pool 和 saver 放在一起返回, 是为了让 main.py 的 lifespan 收尾时
    **只有一处**需要关闭 —— 漏关连接池的表现是进程退出时挂住, 或者
    Postgres 那边留下一堆 idle 连接, 都很容易在开发期被忽略。
    """

    saver: Any
    pool: Any | None = None
    backend: str = BACKEND_MEMORY
    # 非空表示"本想要 postgres, 但没起来, 降级成了进程内记忆"。
    # 必须能从 /health 看见 —— 否则部署方会以为自己在用持久化,
    # 而实际上每次重启都在丢断点。
    degraded_reason: str | None = None

    async def aclose(self) -> None:
        if self.pool is not None:
            await self.pool.close()
            self.pool = None

# 配置进程内后端
def memory_resources(*, degraded_reason: str | None = None) -> CheckpointResources:
    """进程内后端。降级路径和"本来就配 memory"共用它。"""
    return CheckpointResources(
        saver=InMemorySaver(),
        backend=BACKEND_MEMORY,
        degraded_reason=degraded_reason,
    )

# 将传入值value规整化为小写去空格字符串 并且进行合法性校验
def normalize_backend(value: str | None) -> str:
    """把配置值规整成小写; 空值按默认 (memory) 处理。

    单独抽出来是因为测试要覆盖"写错了会怎样" —— 拼错成 ``postgress``
    时静默退回内存是最坏的结果: 你以为在持久化, 其实重启就丢。
    """
    #将传入值归一化为去空格小写, 空值按默认 (memory) 处理
    backend = (value or "").strip().lower() or BACKEND_MEMORY
    # 是否是 memory 或者 postgres
    if backend not in _SUPPORTED_BACKENDS:
        raise ValueError(
            f"CHECKPOINT_BACKEND={value!r} 不认识, 只支持 {sorted(_SUPPORTED_BACKENDS)}"
        )
    return backend


async def build_checkpointer(settings: Settings) -> CheckpointResources:
    """按配置装配 checkpointer。启动时调用一次, 由 lifespan 负责关。"""
    # 从settings中获取checkpoint_backend
    backend = normalize_backend(settings.checkpoint_backend)

    # 如果setting参数值是memory, 则返回memory内记忆资源
    if backend == BACKEND_MEMORY:
        logger.info(
            "checkpointer=memory: 对话记忆只在进程内, 重启即丢 (断点续跑不可用)"
        )
        # 空参构造, 表示为memory进程内记忆
        return memory_resources()

    # ★ Windows 上必须先确认事件循环类型, 否则要等 10 秒的 PoolTimeout
    #   才看到一个几乎指不出原因的报错。见 _check_windows_loop。
    _check_windows_loop()

    # 延迟导入: 没有用 postgres 后端的部署不需要 psycopg 也应当能启动。
    from langgraph.checkpoint.postgres.aio import AsyncPostgresSaver
    from psycopg.rows import dict_row
    from psycopg_pool import AsyncConnectionPool

    pool = AsyncConnectionPool(
        conninfo=settings.postgres_dsn,
        min_size=1,
        max_size=max(1, settings.postgres_pool_max_size),
        open=False,
        # autocommit: checkpoint 的写入是一条条独立语句, 不该被包进长事务
        # prepare_threshold=0: 走连接池时必须关掉预编译语句缓存, 否则
        #   池里换连接后 prepared statement 会对不上 (psycopg 官方建议)。
        kwargs={
            "autocommit": True,
            "prepare_threshold": 0,
            "row_factory": dict_row,
        },
    )
    try:
        # wait=True: 连不上就当场失败。允许"起得来但每次请求都超时"
        # 是最难排查的一种故障。
        await pool.open(wait=True, timeout=10)
        saver = AsyncPostgresSaver(pool)
        # setup() 是幂等的 (内置 migrations 表), 每次启动跑一遍即可,
        # 省掉一个"要先手工建表"的部署步骤。
        await saver.setup()
    except Exception:
        await pool.close()
        raise

    logger.info(
        "checkpointer=postgres: 对话记忆落在 %s (loop=%s)",
        _safe_target(settings),
        type(asyncio.get_running_loop()).__name__,
    )
    return CheckpointResources(saver=saver, pool=pool, backend=backend)


def _check_windows_loop() -> None:
    """★ Windows 陷阱: psycopg 的异步连接不支持 ProactorEventLoop ★

    Windows 上 asyncio 默认是 Proactor; uvicorn 只有在 --reload / 多 worker
    时 (走子进程) 才换成 Selector。psycopg 会在建立连接时直接拒绝,
    而连接池会重试到超时才抛 PoolTimeout —— 那一行
    "Psycopg cannot use the 'ProactorEventLoop'" 很容易被池的噪音淹掉。

    在这里提前判断, 换成一个能照着做的报错。非 Windows 不受影响。
    """
    if sys.platform != "win32":
        return
    try:
        loop = asyncio.get_running_loop()
    except RuntimeError:
        # 没有运行中的事件循环 (同步调用) —— 不归这里管
        return
    if "Proactor" in type(loop).__name__:
        raise RuntimeError(
            "psycopg 的异步连接不支持 Windows 默认的 ProactorEventLoop。"
            "请用 `uvicorn app.main:app --loop asyncio:SelectorEventLoop` 启动; "
            "开发时加 --reload 也可以 (uvicorn 在 reload 子进程里用的是 Selector)。"
        )


# ----------------------------------------------------------------------
#  断点续跑: 一次 /chat 该以什么输入调用图
# ----------------------------------------------------------------------


@dataclass(frozen=True)
class InvocationPlan:
    """这次 invocation 的输入。

    ``payload`` 直接喂给 ``ainvoke``: ``None`` 在 LangGraph 里表示
    "没有新输入, 沿着已存的 checkpoint 继续"。``baseline_messages``
    是调用前这个 thread 已有的消息条数 —— 调用方靠它把"本轮新增的消息"
    从历史里切出来 (否则新接上持久化后, tool_calls 会把上一轮的工具调用
    也算进来)。
    """
    # 喂给 ainvoke 的输入, 如果有值,则表示有新输入, 让图按照 FRESH
    # ainvoke(None) 不是"什么都不做"，而是"别给新输入，沿着已存的 checkpoint 往下走"
    payload: dict[str, Any] | None

    # 调用模式, 可选值: FRESH, RESUME, REPLAY, BUSY
    # FRESH:  新输入, 从头开始
    # RESUME: 继续上一遍的未完成节点
    # REPLAY: 重复上一遍的结果
    # BUSY:   这个 thread 上挂着别的任务, 本次请求必须被拒绝
    mode: str

    # 调用新的ainvoke之前, 历史 messages 的条数
    baseline_messages: int = 0

    #挂起节点的名称列表
    #只在 RESUME模式下有值
    pending_nodes: tuple[str, ...] = ()

    # BUSY 时, 占着这个 thread 的任务 id (没有归属时是 None)。
    # 回给调用方是为了让 409 的报错能说清"被谁挡住了"。
    busy_task_id: str | None = None

    @property
    def resumes(self) -> bool:
        return self.payload is None


async def plan_invocation(
    agent: Any,
    config: dict[str, Any],
    message: str,
    *,
    task_id: str | None = None,
) -> InvocationPlan:
    """读一次 checkpoint, 决定"续跑 / 复述结果 / 开新一轮 / 拒绝"。

    ★ 会话线程下, 判断依据多了一个: 这个快照是【哪个任务】留下的 ★

    对话记忆现在按会话累积 (thread_id = ``session:<tenant>:<sid>``), 同一个
    thread 里会先后出现多个任务。只看 ``snapshot.next`` 已经不够了:

    * 任务 A 崩在半路, 任务 B 进入同一会话 —— B 不能"续跑" A 的节点,
      那会把 B 的消息接进 A 的推理过程;
    * 任务 A 跑完了, 任务 B 进来 —— 最后一条确实是 AI 消息, 但那是 A 的
      答案, 复述给 B 等于答非所问, 还省掉了 B 本该花的那次调用。

    归属信息放在 checkpoint 的 ``metadata`` 里 (``config["metadata"]["task_id"]``),
    不放进图的 state: 它是"谁写下了这个快照"的元信息, 不是对话内容 ——
    放 state 里会混进模型上下文, 还会被消息裁剪逻辑碰到。LangGraph 会把
    config.metadata 持久化到每个 checkpoint 并由 ``aget_state`` 原样返回
    (2026-11-04 实测)。

    四种结论:

    * 有挂起节点 + 归属就是本次任务 → RESUME (重试续跑)
    * 有挂起节点 + 归属是别的任务 / 无归属 → BUSY (由 /chat 回 409)
    * 无挂起 + 归属就是本次任务 + 最后一条是 AI → REPLAY (补终态上报)
    * 其余 → FRESH (追加消息, 会话记忆自然累积)

    ★ 为什么不用 checkpoint_ns 区分任务 (之前的设想) ★

    ``checkpoint_ns`` 是给【子图】用的命名空间。对根图传一个自造的 ns,
    ``aget_state`` 直接报 ``Subgraph xxx not found`` (已实测); 就算能写,
    不同 ns 的 messages 也是互相隔离的 —— 而会话记忆恰恰要求共享。
    所以"session 线程 + 任务 metadata"是这两个诉求唯一能同时成立的组合。

    ★ 自由对话的额外一票否决 ★

    自由对话没有"同一件事"的概念。一个 thread 停在半路、用户又发了
    **新问题**时, 空输入续跑会把新消息彻底吞掉。所以只有"重发的正是
    挂起前最后那条用户消息"时才 RESUME; 而挂起状态如果属于某个任务,
    自由对话一律 BUSY, 不去碰别人正在跑的执行。

    注意 FRESH 在"有挂起节点"时也成立: LangGraph 会先写入新消息, 再重跑
    挂起节点 (实测)。这正是自由对话"旧问题崩了, 用户换了个新问题"要的行为。
    """
    #获取指定thread_id 对应的最新的snapshot快照对象
    snapshot, error = await _read_snapshot(agent, config)
    if error is not None:
        # 读不到 checkpoint (Postgres 抖动 / 图没挂 checkpointer) 时按新对话走。
        # 理由: 记忆是旁路, 不能因为它把一次本来能跑的请求变成 500。
        # 代价是这一轮可能看不到上下文 —— 日志里留 WARNING, 不静默。
        logger.warning("读取 checkpoint 失败, 本轮按新对话处理: %s", error)
        return _fresh(message)
    # 从快照中获取挂起节点的名称列表, 默认为空
    pending = tuple(getattr(snapshot, "next", None) or ())
    # 从最新快照中获取历史消息列表
    messages = _messages(snapshot)
    # 判断未进行下一步之前的历史消息条数
    baseline = len(messages)
    # 这个快照是哪个任务写下的? None = 自由对话 (没有任务身份)
    owner = _owner_task(snapshot)
    wanted = (task_id or "").strip() or None

    # 如果有挂起节点
    if pending:
        if wanted is not None:
            # 任务链路: 只有"同一个任务"的挂起才能续。别的任务的半截执行
            # 属于另一个推理过程, 接上去就是串味。
            if owner == wanted:
                return InvocationPlan(
                    payload=None,
                    mode=MODE_RESUME,
                    baseline_messages=baseline,
                    pending_nodes=pending,
                )
            return _busy(owner, baseline, pending)
        # 自由对话: 挂起的是某个任务时绝不碰它 —— 那是还在跑/没跑完的执行。
        if owner is not None:
            return _busy(owner, baseline, pending)
        # 没有归属的挂起 = 上一次自由对话崩了。重发同一句话才续跑。
        if _last_human_content(messages) == message:
            return InvocationPlan(
                payload=None,
                mode=MODE_RESUME,
                baseline_messages=baseline,
                pending_nodes=pending,
            )
        # 用户发起了新的问题. 和挂起前最新的用户消息有所不同
        return _fresh(message, baseline)

    # 同一个任务重试, 而图上一次已经跑出了答复 —— 别再问一遍 LLM。
    # ★ 必须核对归属 ★ 会话线程里"最后一条是 AI"可能是上一个任务的答案,
    # 复述给新任务就是答非所问。
    if wanted is not None and owner == wanted and _ends_with_ai(messages):
        # 直接 ainvoke(None) 取回最终状态, 由 /chat 重新上报 COMPLETED。
        return InvocationPlan(
            payload=None,
            mode=MODE_REPLAY,
            baseline_messages=baseline,
        )

    return _fresh(message, baseline)


# 取消任务时写进图里的那条消息。它是"收尾", 不是"回答" —— 措辞要让
# 下一轮看到这段历史的模型知道: 上一个任务是被主动停掉的, 不是它自己结束的。
CANCEL_MESSAGE = "上一个任务已被主动取消, 本次执行到此为止。"

# 收尾要借哪个节点的手写进图。``create_react_agent`` 的节点名固定是
# ``agent`` (另一个是 ``tools``)。这不是"约定俗成", 而是 ReAct 图的路由:
# 以 agent 身份写入一条【没有 tool_calls 的 AI 消息】, 条件边就会走向 END,
# 挂起状态随之清空。换自建图时这里要跟着改 —— 所以单独提成常量。
RELEASE_NODE = "agent"


async def release_pending(
    agent: Any,
    config: dict[str, Any],
    *,
    task_id: str | None = None,
) -> bool:
    """把一个任务留下的挂起状态收尾, 让会话线程重新可用。

    ★ 为什么取消必须做这一步 ★

    取消的语义是"这次执行不再继续", 但图可能正挂在 ``agent`` 节点上
    (崩溃/被停都是这个形态)。只要 ``snapshot.next`` 非空, 同会话的
    下一个任务就会在 ``plan_invocation`` 里撞到 BUSY —— 线程被一个
    **永远不会再被续跑**的任务永久占住。所以取消不能只把 Java 侧改成
    CANCELLED, 还要让 checkpoint 自己"落地"。

    ★ 为什么是"以 agent 身份写一条无 tool_calls 的 AI 消息" ★

    LangGraph 不提供"丢弃挂起"的接口: ``aupdate_state`` 只能以某个节点的
    身份写状态, 写完之后 ``next`` 由该节点的出边重新计算。以 ``agent``
    写入空 tool_calls 的消息, 条件边判 END, ``next`` 变成空 —— 图就停在
    一个正常的终态上, 而不是"半截推理"。

    另一个看起来更简单的做法是直接把 thread 的 checkpoint 删掉, 但那是
    绕过 checkpointer 的内部表结构 (LangGraph 没有公开的删除 API), 且会
    连会话历史一起抹掉 —— 取消一个任务不该让整段会话失忆。

    ★ 归属校验 ★

    只收尾"属于 task_id"的挂起。没有归属的挂起 (自由对话) 或别的任务的
    挂起一律不碰 —— 那不是这次取消该管的事, 误清会让另一个任务的续跑
    直接丢上下文。

    @return True = 确实收尾了一个挂起; False = 没有挂起 / 归属不符 / 收尾失败
    """
    snapshot, error = await _read_snapshot(agent, config)
    if error is not None:
        # 读不到就什么也做不了。返回 False 让调用方如实上报"没释放",
        # 不要假装成功 —— 会话可能仍然被占着。
        logger.warning("取消时读取 checkpoint 失败: %s", error)
        return False
    if snapshot is None:
        return False

    pending = tuple(getattr(snapshot, "next", None) or ())
    if not pending:
        # 没有挂起 = 不需要收尾 (执行已经跑完/还没开始写)。幂等。
        return False

    owner = _owner_task(snapshot)
    wanted = (task_id or "").strip() or None
    if wanted and owner and owner != wanted:
        logger.warning(
            "取消时不碰别人的挂起: thread=%s owner=%s task=%s pending=%s",
            config.get("configurable", {}).get("thread_id"),
            owner,
            wanted,
            pending,
        )
        return False

    updater = getattr(agent, "aupdate_state", None)
    if updater is None:
        logger.warning("Agent 不支持 aupdate_state, 无法释放挂起状态")
        return False

    try:
        await updater(
            config,
            {"messages": [AIMessage(content=CANCEL_MESSAGE)]},
            as_node=RELEASE_NODE,
        )
    except Exception as ex:  # noqa: BLE001 - 取消是旁路, 失败只影响"能否立即释放会话"
        logger.warning("写入取消收尾失败: %s: %s", type(ex).__name__, ex)
        return False

    # 写完再确认一次 next 真的空了。不确认的话, 如果以后的图结构调整了
    # (比如 agent 之后还有别的边), 我们会"以为释放了", 而调用方得到的
    # 409 会变成一句解释不了的"取消成功但会话还被占着"。
    check, error = await _read_snapshot(agent, config)
    if error is not None:
        logger.warning("取消收尾后复查失败: %s", error)
        return True
    still_pending = tuple(getattr(check, "next", None) or ())
    if still_pending:
        logger.warning("取消收尾后仍有挂起: %s", still_pending)
        return False
    return True


def _owner_task(snapshot: Any) -> str | None:
    """快照是由哪个任务写下的; 自由对话/读不到时返回 None。"""
    metadata = getattr(snapshot, "metadata", None)
    if not isinstance(metadata, dict):
        return None
    value = metadata.get("task_id")
    return value if isinstance(value, str) and value else None


def _busy(owner: str | None, baseline: int, pending: tuple[str, ...]) -> InvocationPlan:
    """这个 thread 被别的执行占着 —— 既不能续, 也不能追加。"""
    return InvocationPlan(
        payload=None,
        mode=MODE_BUSY,
        baseline_messages=baseline,
        pending_nodes=pending,
        busy_task_id=owner,
    )

# 获取 config(thread_id) 对应的最新的snapshot快照对象
async def _read_snapshot(agent: Any, config: dict[str, Any]) -> tuple[Any, str | None]:
    """读 checkpoint, 返回 ``(snapshot, error)``。

    单独一层是因为 ``aget_state`` 是图/checkpointer 提供的能力, 不一定总有:
    没有 checkpointer 的图、测试里的假 Agent 都可能没有它。没有就等价于
    "这个 thread 没历史", 不需要报错。
    """
    # getattr方法: 从对象agent中获取方法aget_state, 赋值给getter, getter就是一个可直接调用的函数对象
    # 我不关心这个对象具体是什么类型，我只关心它有没有我需要的东西 -- getattr 方法
    getter = getattr(agent, "aget_state", None)
    # 读取的快照为空, 按照空历史处理
    if getter is None:
        return None, None
    try:
        # 去Postgres / 内存 里根据传入的config读取快照, 返回一个StateSnapshot对象
        return await getter(config), None
    # 这里必须捕获所有异常 (含 psycopg 的连接/序列化错误): 读不到历史只该
    # 退化成"新对话", 不能让整个 /chat 挂掉。降级是有意的, 不是漏了具体类型。
    except Exception as ex:  # noqa: BLE001 - 降级处理
        return None, f"{type(ex).__name__}: {ex}"

# 追加新消息, 组装新一轮对话的InvocationPlan对象
def _fresh(message: str, baseline_messages: int = 0) -> InvocationPlan:
    return InvocationPlan(
        payload={"messages": [{"role": "user", "content": message}]},
        mode=MODE_FRESH,
        baseline_messages=baseline_messages,
    )

# 从快照中提取消息列表
# 一个 StateSnapshot 对象大致长这样 (示意, 不是可执行代码 —— 每一行都要注释掉,
# 否则会被当成模块级赋值, 污染 values / next / config 这些名字):
#StateSnapshot(
#     values={"messages": [...]},    # 当前对话走到这一步的所有历史消息
#     next=("tools",),               # 下一步需要执行的节点
#     config={...},                  # 内部用，你没用到
#     metadata={"step": 3},          # 内部用，你没用到
#     created_at="2026-11-04T...",   # 内部用，你没用到
#)
def _messages(snapshot: Any) -> list[Any]:
    # 快照里面有一个value字段, values是一个dict, 里面有{"messages": [...]}
    values = getattr(snapshot, "values", None)

    if not isinstance(values, dict):
        return []
    messages = values.get("messages") or []
    return list(messages)

# 判断消息列表是否以 AI 消息结尾
def _ends_with_ai(messages: list[Any]) -> bool:
    return bool(messages) and isinstance(messages[-1], AIMessage)

# 从消息列表中提取最后一条用户消息 humanMessage 的内容
def _last_human_content(messages: list[Any]) -> str | None:
    for msg in reversed(messages):
        if isinstance(msg, HumanMessage):
            # 多模态消息的 content 是 list, 和 str 比较永远不等 —— 按"不匹配"
            # 处理, 走 FRESH 更安全 (顶多重复一次提问, 不会吞掉新输入)。
            return msg.content if isinstance(msg.content, str) else None
    return None


def _safe_target(settings: Settings) -> str:
    """日志里只打 host/db, 不打密码。"""
    return f"{settings.postgres_host}:{settings.postgres_port}/{settings.postgres_db}"
