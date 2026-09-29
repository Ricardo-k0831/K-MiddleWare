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


class ChatResponse(BaseModel):
    reply: str
    thread_id: str


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


@app.post("/chat", response_model=ChatResponse)
async def chat(req: ChatRequest) -> ChatResponse:
    """跑一轮 Agent。

    Phase1 先用非流式把链路跑通。流式输出 (SSE 推送 token) 是 Phase2 的事,
    到时候这里换成 StreamingResponse 即可, Agent 本身不用改。
    """
    if state.agent is None:
        raise HTTPException(
            status_code=503,
            detail=f"Agent 未就绪: {state.startup_error}",
        )

    config = {"configurable": {"thread_id": req.thread_id}}
    try:
        result = await state.agent.ainvoke(
            {"messages": [{"role": "user", "content": req.message}]},
            config=config,
        )
    except Exception as ex:
        logger.exception("Agent 执行失败")
        raise HTTPException(
            status_code=500,
            detail=f"Agent 执行失败: {describe_exception(ex)}",
        ) from ex

    # ReAct 循环产出的最后一条消息就是最终答复
    messages = result.get("messages", [])
    reply = messages[-1].content if messages else ""

    return ChatResponse(reply=reply, thread_id=req.thread_id)
