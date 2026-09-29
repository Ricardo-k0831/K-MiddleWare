"""MCP 协议适配层的抽象定义。

这个文件是整个 Python 运行时里【唯一】不依赖具体 MCP SDK 的地方, 也是这个项目
应对协议迭代风险的核心手段。

## 为什么需要这层

MCP 协议目前处于分线状态:

    mcp 1.x  -> 带 initialize 握手的协议 (对应 MCP 2025-11-25 一线, 仍在维护)
    mcp 2.x  -> MCP 2026-07-28 修订版, 协议变为【无状态】:
                握手被删除、Mcp-Session-Id 被删除、GET SSE 流被删除

Python 侧的 langchain-mcp-adapters 目前要求 mcp<2.0.0, 只能用 1.x 线;
Java 侧 Spring AI 2.0.1 底层是 mcp 2.0.0, 走的是 2.x 线。
两边靠"服务端向后兼容旧版握手"来对齐 —— 这个假设必须被测试证明,
见 tests/test_mcp_handshake.py。

## 这层怎么用

    Agent 运行时  ->  只 import ToolProvider / ToolSpec
    具体实现      ->  langchain_provider.py (当前, 基于 mcp 1.x)
                      mcp2_provider.py     (将来, 协议升级时新增)

将来 mcp 2.x 客户端可用时, 只需要新写一个实现类并在工厂函数里换一行,
graph/ 和 main.py 里的业务代码一行都不用改。
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field
from typing import Any, Protocol, runtime_checkable

# Tool工具对象
@dataclass(frozen=True, slots=True)
class ToolSpec:
    """一个 MCP 工具的协议无关描述。

    刻意不直接复用 MCP SDK 的 Tool 对象 —— 那个对象的结构随协议版本变化
    (比如 2026-07-28 把 JSON Schema 从 Draft-07 换成了 2020-12),
    一旦泄漏到业务代码里, 协议升级就会变成全仓库改动。
    """

    name: str
    description: str
    input_schema: Mapping[str, Any] = field(default_factory=dict)

    def __str__(self) -> str:
        return f"{self.name}: {self.description[:60]}"

# 工具调用Result对象
@dataclass(frozen=True, slots=True)
class ToolCallResult:
    """一次工具调用的结果。

    is_error 由适配层根据底层协议的错误语义填充, 业务代码只判断这个布尔值,
    不需要知道是 MCP 的 isError 字段还是别的什么机制。
    """

    tool_name: str
    content: str
    is_error: bool = False


@runtime_checkable
class ToolProvider(Protocol):
    """工具提供者的接口。

    所有实现都必须满足这个协议, 业务代码只依赖它。
    """

    async def list_tools(self) -> Sequence[ToolSpec]:
        """列出当前身份可见的工具。

        注意"当前身份"四个字: Java 网关会按 Agent 的权限裁剪工具列表,
        所以这个方法的返回值是权限过滤【之后】的结果, 不是全量工具。
        Python 侧不做权限校验 —— 权限边界在 Java 网关, 这是刻意设计。
        """
        ...

    async def build_langchain_tools(self) -> Sequence[Any]:
        """返回可直接喂给 LangGraph / LangChain Agent 的工具对象列表。

        返回类型标 Any 而不是 BaseTool, 是为了不让这个抽象文件
        硬依赖 langchain_core —— 保持它作为"协议边界"的纯粹性。
        """
        ...
