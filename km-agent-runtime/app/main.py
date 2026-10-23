"""FastAPI 入口。

对外提供:
    GET  /health        存活检查
    GET  /agent/tools   ★ 列出 Agent 实际拿到的工具 —— 这是验证 MCP 握手的手段
    POST /chat          跑一轮 Agent

启动顺序上有个关键点: 工具拉取放在 lifespan 里, 而不是每个请求里。
MCP 握手和 tools/list 是有成本的, 每轮对话重来一次会让响应变慢,
也容易把 Java 网关的连接数打满。
"""

from __future__ import annotations

import asyncio
import logging
import sys
from contextlib import asynccontextmanager
from typing import Any

import httpx
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.audit import AuditCallbackHandler, AuditReporter
from app.config import get_settings
from app.graph.agent import build_agent
from app.mcp_adapter import TaskIdentity, ToolProvider, build_tool_provider, task_identity
from app.task_state import (
    COMPLETED,
    FAILED,
    THINKING,
    TaskStateCallbackHandler,
    TaskStateReporter,
)

# Windows 控制台默认 GBK, 中文日志会变成乱码。强制 UTF-8 输出。
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
)
logger = logging.getLogger(__name__)


def describe_exception(exc: BaseException) -> str:
    """把异常描述成一句人类能看懂的话。

    MCP 客户端内部用 anyio 的 TaskGroup 并发建连接, 连接失败时抛的是
    `ExceptionGroup: unhandled errors in a TaskGroup (1 sub-exception)` ——
    这句话对排查没有任何帮助, 真正的原因 (httpx.ConnectError) 被埋在
    .exceptions 里。这里递归剥到最底层。

    这是调试体验问题, 不是功能问题 —— 但跨语言系统里"网关没起来"
    是最常见的故障, 报错说不清楚会浪费大量时间。
    """
    while isinstance(exc, BaseExceptionGroup) and exc.exceptions:
        exc = exc.exceptions[0]
    return f"{type(exc).__name__}: {exc}"


class AppState:
    """进程级单例。启动时装配一次, 全程复用。"""

    provider: ToolProvider | None = None
    agent: Any = None
    startup_error: str | None = None


state = AppState()


@asynccontextmanager
async def lifespan(app: FastAPI):
    settings = get_settings()
    logger.info("正在启动 Agent 运行时, MCP 网关: %s", settings.mcp_order_server_url)
    # 创建 LangchainProvider 适配器实例
    provider = build_tool_provider(settings)
    state.provider = provider

    try:
        state.agent = await build_agent(provider, settings)
        logger.info("Agent 运行时启动完成")
    except Exception as ex:
        # 启动失败不直接退出进程 —— 让 /health 和 /agent/tools 还能回答问题,
        # 否则排查"到底是网关没起来还是 Python 写错了"会非常痛苦。
        state.startup_error = describe_exception(ex)
        logger.exception("Agent 装配失败, 服务以降级模式启动")

    yield

    if state.provider is not None:
        await state.provider.aclose()
    logger.info("Agent 运行时已关闭")


app = FastAPI(
    title="K_middleware Agent Runtime",
    version="0.1.0",
    description="Python Agent 运行时 — LangGraph 编排 + MCP Client",
    lifespan=lifespan,
)


# ============================================================
#  模型
# ============================================================
# 聊天的请求模型, 内部有 message,thread_id, task_id, trace_id, tenant_id, actor 这些字段
class ChatRequest(BaseModel):
    message: str = Field(..., description="用户输入")
    thread_id: str = Field(
        default="default",
        description="会话ID。同一个 thread_id 的多轮对话共享上下文",
    )
    task_id: str | None = Field(
        default=None,
        description=(
            "Java 侧建好的任务ID。给了就启用任务链路: 它同时作为 thread_id, "
            "运行期间状态实时回调 Java, 终态也由这里上报。"
            "不给则行为与 Phase1 完全一致。"
        ),
    )
    # ---- 下面三个是【任务身份】, 只在带了 task_id 时有意义 ----
    #
    # ★ 它们由调用方从 POST /api/tasks 的响应里原样转交, Python 不生成 ★
    #
    # traceId 的唯一生成点是 Java 的 TaskService.create() —— 准确说是"任务
    # 第一次执行"的生成点 (重试会另生成一个)。Python 这边自己造一个的话,
    # 会得到两个 traceId: 任务表里是 Java 的, 审计表里是 Python 的,
    # 同一遍执行被劈成两条对不上的链路。
    #
    # 那"缺省时兜底生成一个"呢? 也不行 —— 缺一个值是诚实的缺失 (查询时
    # 一眼看得出来), 值不一致是谎言 (只有交叉比对两张表才会暴露)。
    trace_id: str | None = Field(
        default=None,
        description=(
            "链路ID, 来自任务创建响应。审计按它串起【这一次执行】的所有事件 "
            "(跨运行时/网关/存量服务); 它不跨任务 —— 同一会话里的多个任务 "
            "各有自己的 trace_id, 会话维度请用 session_id。"
            "★ 带 task_id 时必填 ★ 缺了直接 400: 没有执行身份, 这次运行的"
            "每条上报都无法归位。"
        ),
    )
    tenant_id: str | None = Field(
        default=None,
        description="租户ID, 来自任务创建响应。不给则由 Java 侧填默认租户",
    )
    actor: str | None = Field(
        default=None,
        description=(
            "操作者。在 Java 侧就是 agent_task.user_id, 在审计里那一列叫 actor "
            "—— 这里跟着审计的叫法走, 免得同一份数据在两个系统里两个名字"
        ),
    )


class ChatResponse(BaseModel):
    reply: str
    thread_id: str
    # 本轮实际调用过的工具名, 按调用顺序。
    # 存在的意义是【可验证】: 只看 reply 判断不了链路是否健康 ——
    # 工具失败时 Agent 会把错误当数据转述成一段通顺的中文(降级处理), HTTP 200,
    # 肉眼看像是成功了。验收脚本靠这个字段判断 Agent 有没有"选对工具"。
    tool_calls: list[str] = Field(default_factory=list)
    # 走任务链路时回显, 方便调用方和自己建的任务对上号。
    # 不走任务链路时为 null —— 这个区别本身就是"哪条路径"的证据。
    task_id: str | None = None
    # 同上的理由: 回显它, 验收脚本才能拿它去 audit_log 里对账
    # ("我发出去的身份"和"数据库收到的身份"是不是同一个)。
    trace_id: str | None = None


# ============================================================
#  接口
# ============================================================

@app.get("/health")
async def health() -> dict[str, Any]:
    """存活 + 装配状态。

    startup_error 不为空说明 Agent 没装配成功 —— 这是排查链路问题的第一站。
    """
    return {
        "status": "ok" if state.startup_error is None else "degraded",
        "agent_ready": state.agent is not None,
        "startup_error": state.startup_error,
    }

# 该接口返回的工具是ToolSpec列表, 不是 LangChain的 BaseTool 列表
@app.get("/agent/tools")
async def list_agent_tools() -> dict[str, Any]:
    """列出 Agent 实际能调用的工具。

    ★ 这个接口是 MCP 链路的验收点: 如果这里能列出 get_order_by_id
    和 list_orders_by_user, 说明 Java MCP 网关 -> Python MCP Client
    这条链是通的, 整个项目的架构假设成立。
    """
    if state.provider is None:
        raise HTTPException(status_code=503, detail="ToolProvider 尚未初始化")

    try:
        tools = await state.provider.list_tools()
    except Exception as ex:
        raise HTTPException(
            status_code=502,
            detail=(
                f"从 MCP 网关拉取工具失败: {describe_exception(ex)}。"
                "请确认 km-mcp-server 已在 8081 端口启动。"
            ),
        ) from ex

    return {
        "count": len(tools),
        "tools": [
            {"name": t.name, "description": t.description} for t in tools
        ],
    }


async def _drain_reporter(reporter: TaskStateReporter | None) -> None:
    """排空上报队列, 且无论如何都不让收尾动作影响响应。

    最后一条 COMPLETED / FAILED 是整个任务里最有价值的一条 —— 丢了它,
    Java 侧会一直以为任务还在跑。所以要等它发完 (aclose 内部有排空等待)。

    但等不到也不能报错: 上报是旁路, 旁路的收尾失败绝不该把一个成功的请求
    变成 500。这正是设计文档里"上报失败绝不能影响主流程"那条约束的最后一段。
    """
    if reporter is None:
        return
    try:
        await reporter.aclose()
    except Exception:
        logger.warning("状态上报收尾失败, 忽略", exc_info=True)


async def _drain_audit(audit: AuditReporter | None) -> None:
    """排空审计队列。失败一律吞掉, 理由同上。

    和状态上报唯一的区别是【期望值】: 状态那条"丢了很严重", 审计这条
    "丢几条可以接受"。所以审计的排空超时更短 —— 让调用方为可丢的记录
    多等几秒是不能接受的, 而让最有价值那条状态多等几秒可以。
    """
    if audit is None:
        return
    try:
        await audit.aclose()
    except Exception:
        logger.warning("审计上报收尾失败, 忽略", exc_info=True)


async def _verify_task_identity(
    settings: Any,
    task_id: str,
    trace_id: str,
    client: httpx.AsyncClient | None = None,
) -> None:
    """入口核对: 这个 trace 是不是这个任务【当前这次执行】的身份。

    ★ 为什么要在入口做, 而不是等 Java 在 PATCH 上报时再拒 ★

    两种做法的成本完全不同:

    * 只在上报时拒 —— Agent 会先花几十秒把 LLM 和工具全跑完, 然后每一条
      状态上报、每一条审计都被拒。用户等到的是一份正常答案, 而库里这个
      任务停在 PENDING, 审计里一条 TASK_LIFECYCLE 都没有。
    * 在入口拒 —— 一个 GET, 几十毫秒, 调用方当场看到 409。

    代价是 Python 任务链路从此【硬依赖】Java。这是有意的: 任务链路的
    唯一事实源就是 Java, Java 不在的时候本来也上报不了任何东西; 与其
    "跑完但什么都没记下", 不如一开始就说"现在跑不了"。

    核对的是 {@code agent_task.traceId}(最近一次执行的 trace)。重试落地后
    它应当换成"该 trace 对应的 attempt 是否还开着", 那需要任务服务再开一个
    查询接口 —— 现在 1:1, 用任务上的字段足够, 不做多余的接口。
    """
    url = f"{settings.km_task_service_url.rstrip('/')}/api/tasks/{task_id}"
    # 允许注入 client: 测试塞 MockTransport 进来, 和 TaskStateReporter 同一个写法。
    owns_client = client is None
    client = client or httpx.AsyncClient(
        timeout=settings.km_task_service_timeout_seconds
    )
    try:
        response = await client.get(url)
    except httpx.HTTPError as ex:
        raise HTTPException(
            status_code=502,
            detail=f"任务服务不可达, 无法校验执行身份: {describe_exception(ex)}",
        ) from ex
    finally:
        if owns_client:
            await client.aclose()

    if response.status_code == 404:
        raise HTTPException(status_code=404, detail=f"任务不存在: task_id={task_id}")
    if response.status_code >= 400:
        raise HTTPException(
            status_code=502,
            detail=f"任务服务返回 HTTP {response.status_code}: {response.text[:200]}",
        )

    try:
        actual_trace_id = (response.json() or {}).get("traceId")
    except ValueError as ex:
        raise HTTPException(
            status_code=502,
            detail=f"任务服务返回的不是合法 JSON: {response.text[:200]}",
        ) from ex

    if actual_trace_id != trace_id:
        # 409 而不是 400: 报文本身合法, 是"执行身份"与任务当前状态冲突 ——
        # 和 Java 侧 EXECUTION_MISMATCH 用同一个码。
        raise HTTPException(
            status_code=409,
            detail=(
                f"执行身份不匹配: task_id={task_id} 当前执行是 {actual_trace_id}, "
                f"请求带的是 {trace_id}。请用任务创建响应里的 traceId。"
            ),
        )


@app.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest) -> ChatResponse:
    """跑一轮 Agent。

    Phase1 先用非流式把链路跑通。流式输出 (SSE 推送 token) 是 Phase2 的事,
    到时候这里换成 StreamingResponse 即可, Agent 本身不用改。

    两条路径并存:

    * **不带 task_id** —— 与 Phase1 完全一致, 不回调任何外部服务
    * **带 task_id** —— 任务链路。task_id 即 thread_id, 运行期间把状态回调给
      Java, 终态也在这里上报; 同时按事件上报审计 (LLM 调用 / 工具调用)
    """
    if state.agent is None:
        raise HTTPException(
            status_code=503,
            detail=f"Agent 未就绪: {state.startup_error}",
        )

    settings = get_settings()

    # 空串按"没给"处理。调用方从 JSON 里传个 "" 进来很常见, 让它走任务链路
    # 会得到一个必定 404 的 task_id, 只会在日志里刷一片无用的警告。
    task_id = (req.task_id or "").strip() or None
    trace_id = (req.trace_id or "").strip() or None

    if task_id is not None:
        # ★ 任务链路必须带 trace_id ★
        # 不带的话, Java 只能拿"任务表上最近一次执行"去猜。猜错不会报错,
        # 只会让这次运行的每一条审计挂到别的执行/别的任务名下。宁可 400。
        if trace_id is None:
            raise HTTPException(
                status_code=400,
                detail="带 task_id 时必须同时带 trace_id (来自任务创建响应)",
            )
        # ★ 入口校验, 失败即止 ★ —— 在拉起任何上报器、跑任何 LLM 之前。
        await _verify_task_identity(settings, task_id, trace_id)

    # 从传入的请求中获取任务身份
    # 任务身份的作用: 让 Java 网关知道接下来的工具调用属于哪个任务。
    # 只在任务链路下绑 —— 不带 task_id 的调用没有身份可绑, 走匿名。
    identity = (
        TaskIdentity(
            task_id=task_id,
            trace_id=trace_id,
            tenant_id=(req.tenant_id or "").strip() or None,
            actor=(req.actor or "").strip() or None,
        )
        if task_id is not None
        else None
    )

    reporter: TaskStateReporter | None = None
    audit: AuditReporter | None = None

    # 回调函数列表, 用于在任务链路下的状态上报和审计上报
    callbacks: list[Any] = []

    if task_id is not None:
        reporter = TaskStateReporter(
            settings.km_task_service_url,
            task_id,
            # trace_id 是执行身份: Java 用它找 agent_task_attempt,
            # 缺了它整条上报会被拒 —— 上面入口已经确保了它非空。
            trace_id=trace_id,
            timeout=settings.km_task_service_timeout_seconds,
        )
        await reporter.start()
        # 入口就报 THINKING: 这样即使 LLM 一次都没调起来 (直接抛错),
        # 事件表里也留下了"任务开始跑过"的痕迹, 而不是一条都没有。
        # 之后 on_llm_start 再报一次 THINKING 是幂等的, Java 不写重复事件。
        reporter.report(THINKING, {"phase": "entry"})
        callbacks.append(TaskStateCallbackHandler(reporter))

        # 审计是第二条旁路, 和状态上报并列 —— 两者都在任务链路下才建,
        # 不带 task_id 的调用一条外部请求都不发 (有测试钉着这一点)。
        #
        # ★ 为什么审计只在任务链路下开 ★
        #
        # 因为【审计的每条记录都要能归属到一个任务】。没有 task_id 的调用
        # 记出来是一堆查不到一起的匿名行 —— 那种数据不是"少", 是"没法用",
        # 还会把审计表的信噪比拉低。宁可没有, 也不要一堆解释不了的行。
        audit = AuditReporter(
            settings.km_task_service_url,
            task_id=task_id,
            # 身份原样透传, 一个都不补 —— 理由见 ChatRequest.trace_id 那段
            trace_id=identity.trace_id if identity is not None else None,
            tenant_id=identity.tenant_id if identity is not None else None,
            actor=identity.actor if identity is not None else None,
            timeout=settings.km_task_service_timeout_seconds,
        )
        await audit.start()
        # 审计上报回调函数, 用于在任务链路下的审计上报
        callbacks.append(AuditCallbackHandler(audit, default_model=settings.llm_model))

    # 任务链路下 task_id 同时就是 LangGraph 的 thread_id —— 两者本来就是
    # 同一个东西 ("这单业务"和"这张图跑到哪了"), 共用一个 ID 让断点续跑
    # 不需要任何映射表。见 AgentTask 的类注释。
    #
    # ★ 代价: 对话记忆是按【任务】隔离的, 不是按【会话】★
    # 同一个 session 里连问两件事 = 两个 task = 两个 thread, 第二个任务
    # 看不见第一个任务的上下文。现在的"会话"只有 agent_task.session_id
    # 这个数据库标签, 运行时并不认识它。
    # 要做到会话级记忆, thread_id 应当是 session_id, 用 LangGraph 的
    # checkpoint_ns 区分任务 —— 那是 Phase 2 的事。这里写清楚, 是为了
    # 避免误以为"同一会话的多个任务共享记忆"而按这个前提去设计功能。
    thread_id = task_id or req.thread_id
    config: dict[str, Any] = {"configurable": {"thread_id": thread_id}}
    if callbacks:
        config["callbacks"] = callbacks

    try:
        # ★ 身份的作用域要【恰好】包住这次 invocation ★
        #
        # 往小了说: 不包的话, 这一轮里所有工具调用都会匿名 —— 网关收不到
        # X-KM-Task-Id, 审计里的 agent_task_id 全是 NULL。
        #
        # 往大了说: 包住整个请求处理 (或者干脆不还原) 的话, 并发请求之间
        # 会串号 —— 而且串得毫无规律, 只在压测时才看得出来。
        # contextvars 保证了这里按任务隔离, `with` 保证出了块就还原。
        #
        # Agent 内部对工具调用的调度 (LangGraph 的 ReAct 循环、它起的
        # 子任务) 都在这个块里, ContextVar 会自动传播进去, 不需要把身份
        # 当参数一层层往下传。
        with task_identity(identity):
            result = await state.agent.ainvoke(
                {"messages": [{"role": "user", "content": req.message}]},
                config=config,
            )

        # ReAct 循环产出的最后一条消息就是最终答复
        messages = result.get("messages", [])
        reply = messages[-1].content if messages else ""

        # 把整轮里所有 AIMessage 的 tool_calls 按顺序收集起来。
        # 注意是从【整个 messages 列表】扫, 不是只看最后一条 ——
        # ReAct 可能有多轮工具调用, 最后一条通常已经没有 tool_calls 了。
        tool_calls: list[str] = []
        for msg in messages:
            for call in getattr(msg, "tool_calls", None) or []:
                name = call.get("name") if isinstance(call, dict) else getattr(call, "name", None)
                if name:
                    tool_calls.append(name)

        # ★ 终态由这里报, 不由 CallbackHandler 报 ★
        # 只有这里知道这轮 invocation 到底成没成。工具报错【不】代表任务失败 ——
        # ReAct 循环存在的意义就是从工具错误里恢复。详见 app/task_state.py
        # 里那段 ★ 注释, 那里记着把它映射成 FAILED 会怎样永久毁掉一个成功的任务。
        if reporter is not None:
            reporter.report(COMPLETED, {"result": reply})

    except Exception as ex:
        logger.exception("Agent 执行失败")
        if reporter is not None:
            reporter.report(FAILED, {"error": describe_exception(ex)})
        raise HTTPException(
            status_code=500,
            detail=f"Agent 执行失败: {describe_exception(ex)}",
        ) from ex
    finally:
        # 无论成功还是失败都要排空 —— 尤其不能丢掉刚入队的那条终态。
        # 两条旁路并发收尾, 等待时间不叠加 (它们之间没有任何依赖)。
        await asyncio.gather(
            _drain_reporter(reporter),
            _drain_audit(audit),
        )

    return ChatResponse(
        reply=reply,
        thread_id=thread_id,
        tool_calls=tool_calls,
        task_id=task_id,
        trace_id=identity.trace_id if identity is not None else None,
    )
