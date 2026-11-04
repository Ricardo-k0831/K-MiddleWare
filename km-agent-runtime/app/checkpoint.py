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

# 一次 /chat 对图的三种处置, 见 plan_invocation。
MODE_FRESH = "fresh"    # 追加新消息, 开新一轮
MODE_RESUME = "resume"  # 空输入, 让图从挂起的节点继续
MODE_REPLAY = "replay"  # 空输入, 图已经跑完 —— 只把结果再交一次


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

    # 调用模式, 可选值: FRESH, RESUME,REPLAY,
    # FRESH:  新输入, 从头开始
    # RESUME: 继续上一遍的未完成节点
    # REPLAY: 重复上一遍的结果
    mode: str

    # 调用新的ainvoke之前, 历史 messages 的条数
    baseline_messages: int = 0

    #挂起节点的名称列表
    #只在 RESUME模式下有值
    pending_nodes: tuple[str, ...] = ()

    @property
    def resumes(self) -> bool:
        return self.payload is None


async def plan_invocation(
    agent: Any,
    config: dict[str, Any],
    message: str,
    *,
    task_scoped: bool,
) -> InvocationPlan:
    """读一次 checkpoint, 决定"续跑 / 复述结果 / 开新一轮"。

    ★ 为什么必须区分这三种, 不能一律追加消息 ★

    重试派发的是**同一件事** (Java 侧 message 始终是 task.intent)。
    如果每次都追加一条 user 消息:

    * 图停在半路 (进程被杀 / 节点抛错) 时, 那条消息已经在 checkpoint 里,
      再追加就是同一个问题问两遍 —— LLM 看到两段一模一样的上下文;
    * 图其实已经跑完、只是终态上报丢了时, 会白白再花一次 LLM 的钱,
      而且答案可能不一样 —— 同一个任务两次执行给出两个答案。

    LangGraph 对这两种情况都有现成能力: ``ainvoke(None)`` 对挂起的图会
    沿着未执行的 super-step 继续; 对已完成的线程则原样返回最终状态
    (一个节点都不跑)。判断依据只有 ``snapshot.next``:

    * 非空 → 有节点没跑完 (RESUME)。这也覆盖"进程崩溃" —— checkpoint
      在节点开始前写, 所以崩溃点一定表现为待执行节点。
    * 空 + 最后一条是 AI 消息 → 上一遍已经给出答复 (REPLAY)。
      只对任务链路这么做: 自由对话里用户重发同一句话是一次新提问。
    * 其余 → 正常追加 (FRESH)。

    ★ 自由对话的额外一票否决 ★

    自由对话没有"同一件事"的概念。一个 thread 停在半路、用户又发了
    **新问题**时, 空输入续跑会把新消息彻底吞掉。所以只有"重发的正是
    挂起前最后那条用户消息"时才 RESUME, 否则照常追加 —— 实测这种
    "带新输入 + 有待执行节点"的组合, LangGraph 会先写入新消息再重跑节点。
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

    # 如果有挂起节点
    if pending:
        # 任务链路 或者 断点后, 历史消息列表里最新的一条用户消息和当前输入相同,
        if task_scoped or _last_human_content(messages) == message:
            return InvocationPlan(
                payload=None,
                mode=MODE_RESUME,
                baseline_messages=baseline,
                pending_nodes=pending,
            )
        # 用户发起了新的问题. 和挂起前最新的用户消息有所不同
        return _fresh(message, baseline)

    # 任务已经跑完, 属于任务链路且最后一条是 AI 消息, 则重复上一遍的结果
    if task_scoped and _ends_with_ai(messages):
        # 同一个任务重试, 而图上一次已经跑出了答复 —— 别再问一遍 LLM。
        # 直接 ainvoke(None) 取回最终状态, 由 /chat 重新上报 COMPLETED。
        return InvocationPlan(
            payload=None,
            mode=MODE_REPLAY,
            baseline_messages=baseline,
        )

    return _fresh(message, baseline)

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
    except Exception as ex:  # 降级处理
        return None, f"{type(ex).__name__}: {ex}"

# 追加新消息, 组装新一轮对话的InvocationPlan对象
def _fresh(message: str, baseline_messages: int = 0) -> InvocationPlan:
    return InvocationPlan(
        payload={"messages": [{"role": "user", "content": message}]},
        mode=MODE_FRESH,
        baseline_messages=baseline_messages,
    )

# 从快照中提取消息列表
#StateSnapshot(
    values={"messages": [...]},    # 当前对话走到这一步的所有历史消息
    next=("tools",),               # 下一步需要执行的节点
    config={...},                  # 内部用，你没用到
    metadata={"step": 3},          # 内部用，你没用到
    created_at="2026-11-04T...",   # 内部用，你没用到
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
