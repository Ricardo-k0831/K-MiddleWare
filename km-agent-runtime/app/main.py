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

import logging
import sys
from contextlib import asynccontextmanager
from typing import Any

from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from app.config import get_settings
from app.graph.agent import build_agent
from app.mcp_adapter import ToolProvider, build_tool_provider
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


class ChatResponse(BaseModel):
    reply: str
    thread_id: str
    # 本轮实际调用过的工具名, 按调用顺序。
    # 存在的意义是【可验证】: 只看 reply 判断不了链路是否健康 ——
    # 工具失败时 Agent 会把错误当数据转述成一段通顺的中文, HTTP 200,
    # 肉眼看像是成功了。验收脚本靠这个字段判断 Agent 有没有"选对工具"。
    tool_calls: list[str] = Field(default_factory=list)
    # 走任务链路时回显, 方便调用方和自己建的任务对上号。
    # 不走任务链路时为 null —— 这个区别本身就是"哪条路径"的证据。
    task_id: str | None = None


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


@app.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest) -> ChatResponse:
    """跑一轮 Agent。

    Phase1 先用非流式把链路跑通。流式输出 (SSE 推送 token) 是 Phase2 的事,
    到时候这里换成 StreamingResponse 即可, Agent 本身不用改。

    两条路径并存:

    * **不带 task_id** —— 与 Phase1 完全一致, 不回调任何外部服务
    * **带 task_id** —— 任务链路。task_id 即 thread_id, 运行期间把状态回调给
      Java, 终态也在这里上报
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

    reporter: TaskStateReporter | None = None
    callbacks: list[Any] = []
    if task_id is not None:
        reporter = TaskStateReporter(
            settings.km_task_service_url,
            task_id,
            timeout=settings.km_task_service_timeout_seconds,
        )
        await reporter.start()
        # 入口就报 THINKING: 这样即使 LLM 一次都没调起来 (直接抛错),
        # 事件表里也留下了"任务开始跑过"的痕迹, 而不是一条都没有。
        # 之后 on_llm_start 再报一次 THINKING 是幂等的, Java 不写重复事件。
        reporter.report(THINKING, {"phase": "entry"})
        callbacks.append(TaskStateCallbackHandler(reporter))

    # 任务链路下 task_id 同时就是 LangGraph 的 thread_id —— 两者本来就是
    # 同一个东西 ("这单业务"和"这张图跑到哪了"), 共用一个 ID 让断点续跑
    # 不需要任何映射表。见 AgentTask 的类注释。
    thread_id = task_id or req.thread_id
    config: dict[str, Any] = {"configurable": {"thread_id": thread_id}}
    if callbacks:
        config["callbacks"] = callbacks

    try:
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
        await _drain_reporter(reporter)

    return ChatResponse(
        reply=reply,
        thread_id=thread_id,
        tool_calls=tool_calls,
        task_id=task_id,
    )
