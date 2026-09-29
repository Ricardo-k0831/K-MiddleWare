"""MCP 协议适配层。

对外只暴露协议无关的接口和工厂函数, 具体实现藏在子模块里。
业务代码请只 import 本文件导出的东西, 不要直接 import langchain_provider。
"""

from app.config import Settings
from app.mcp_adapter.base import ToolCallResult, ToolProvider, ToolSpec
from app.mcp_adapter.langchain_provider import LangChainMcpToolProvider

__all__ = [
    "LangChainMcpToolProvider",
    "ToolCallResult",
    "ToolProvider",
    "ToolSpec",
    "build_tool_provider",
]


def build_tool_provider(settings: Settings) -> ToolProvider:
    """工厂函数 —— 全项目唯一决定"用哪个协议实现"的地方。

    将来 mcp 2.x 客户端可用时, 只需要在这里加一个分支:

        if settings.mcp_protocol_revision == "2026-07-28":
            return Mcp2ToolProvider(settings)
        return LangChainMcpToolProvider(settings)

    Agent 运行时那边一行都不用改。
    """
    return LangChainMcpToolProvider(settings)
