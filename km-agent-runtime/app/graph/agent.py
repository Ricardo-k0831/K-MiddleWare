"""LangGraph Agent 编排。

Phase1 的定位: 先用官方预置的 ReAct 循环把链路跑通。
Phase2 再换成自建的多 Agent 子图 (规划 Agent + 执行 Agent + 校验 Agent)。

## 一条必须遵守的约定

Python 侧【不维护】任务的持久化状态。这里的 graph 只持有运行时内存态,
任务的生命周期状态 (准备中 / Agent思考中 / 调用工具 / 完成 / 失败)
一律回调 Java 侧更新, Java 是唯一事实源。

这样做的好处: Python 进程崩了, 任务状态不会丢。Java 侧可以据此重试,
配合 LangGraph 的 checkpoint 还能做到断点续跑。
"""

from __future__ import annotations

import logging
from typing import Any

from langchain_openai import ChatOpenAI
from langgraph.prebuilt import create_react_agent

from app.config import Settings
from app.mcp_adapter.base import ToolProvider

logger = logging.getLogger(__name__)

SYSTEM_PROMPT = """你是一个企业业务助手, 可以调用公司内部系统的工具来回答用户问题。

工作原则:
1. 先判断需要调用哪个工具。用户给了具体订单号, 用 get_order_by_id;
   用户只说"我的订单"没给订单号, 用 list_orders_by_user。
2. 工具返回的错误信息要如实转达给用户, 不要编造业务数据。
3. 如果工具返回的数据不足以回答问题, 直接说明缺什么, 不要猜测。
4. 用中文回答, 简洁直接。
"""


def build_llm(settings: Settings) -> ChatOpenAI:
    """构造 LLM 客户端。

    走 OpenAI 兼容协议, 所以改 base_url + model 就能换供应商
    (DeepSeek / 通义千问 / 本地 vLLM 都行), 不用改这里的代码。
    """
    return ChatOpenAI(
        model=settings.llm_model,
        api_key=settings.llm_api_key,
        base_url=settings.llm_base_url,
        temperature=settings.llm_temperature,
        timeout=60,
        max_retries=2,
    )


async def build_agent(provider: ToolProvider, settings: Settings) -> Any:
    """组装 Agent。

    工具从 ToolProvider 拿 —— 也就是说, Agent 能看见哪些能力, 完全由
    Java 网关的权限裁剪决定。Python 侧不做任何权限判断。
    """
    tools = await provider.build_langchain_tools()
    if not tools:
        logger.warning("Agent 启动时没有拿到任何工具, 请检查 MCP 网关是否可达")

    logger.info("Agent 已装配 %d 个工具", len(tools))
    return create_react_agent(
        build_llm(settings),
        list(tools),
        prompt=SYSTEM_PROMPT,
    )
