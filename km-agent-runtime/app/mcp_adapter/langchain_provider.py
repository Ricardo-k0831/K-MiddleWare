"""ToolProvider 的 LangChain 实现 —— 当前唯一的生产实现。

底层走 langchain-mcp-adapters, 它把 MCP 工具转成 LangChain 的 BaseTool,
这样 LangGraph 的 Agent 就能像用普通工具一样用 Java 网关暴露的业务能力。

## 版本相关的坑 (2026-09 核实)

* langchain-mcp-adapters >= 0.2.0 起, session 由 get_tools() 内部按次管理,
  【不再需要】手动 `async with client.session(...)`。
  网上大量教程还是 0.1.x 的写法, 照抄会报错。
* 客户端要在应用启动时创建一次并复用。每个请求 new 一个会拖慢响应,
  也容易把服务端的连接数打满。
"""

from __future__ import annotations

import logging
from collections.abc import Sequence
from typing import Any

from langchain_mcp_adapters.client import MultiServerMCPClient

from app.config import Settings
from app.mcp_adapter.base import ToolSpec
from app.mcp_adapter.task_identity import TaskIdentityAuth

logger = logging.getLogger(__name__)

# MCP 的传输方式名。langchain-mcp-adapters 用的字面量是 "streamable_http"。
# 如果用 "http" 连不上, 先来这里确认这个常量。
TRANSPORT_STREAMABLE_HTTP = "streamable_http"


class LangChainMcpToolProvider:
    """通过 langchain-mcp-adapters 连接 Java 侧 MCP 网关。

    同时管理多个 MCP Server: Java 侧是"MCP Server 集群"(订单服务一个,
    将来库存 / 用户各一个), 每个 server 是一个 key, 工具名会自动加前缀区分。
    """

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        # 创建 client端, 指明连接的 MCP Server 集群, 之间的通信数据是MCP SDK的Tool类 Json序列
        self._client = MultiServerMCPClient(self._server_config(settings))
        self._tools_cache: list[Any] | None = None

    # 根据 Setting 环境信息返回 JavaMCPServer 集群信息
    @staticmethod
    def _server_config(settings: Settings) -> dict[str, dict[str, Any]]:
        """构造多 Server 连接配置。
        headers 是 Java 网关鉴权的入口 —— Phase2 这里会换成真实的
        Agent 身份 token, 网关据此决定这个 Agent 能看见哪些工具。

        ★ auth 和 headers 是两种不同的东西, 别把任务身份挪到 headers 里 ★

        headers 是【连接级】的: 构造时定死, 一个 client 一套, 之后改不了。
        用它带 task_id 的后果是长驻的 Agent 进程会把任务 B 的工具调用记到
        任务 A 名下 —— 而且审计里看不出来, 它不是缺数据, 是把数据记到了错
        的地方。详见 task_identity 模块开头那节。

        auth 是【请求级】的: TaskIdentityAuth 每个出站请求读一次当前的
        ContextVar。同一个 MCP 会话因此能依次服务不同任务, 和 Java 侧
        McpGatewayAuditConfig 逐 POST 抽取的语义对上。

        两者的分工: Authorization 回答"你是哪个 Agent"(整个进程一个身份),
        X-KM-Task-Id 回答"这次调用属于哪个任务"(每次调用都可能不同)。
        """
        return {
            # 订单服务 对应的 McpServer
            "km-order": {
                "transport": TRANSPORT_STREAMABLE_HTTP,
                #订单服务所属 MCP 网关的 URL（Java 侧 km-mcp-server, 端口 8081）
                "url": settings.mcp_order_server_url,
                "headers": {
                    "Authorization": f"Bearer {settings.mcp_auth_token}",
                },
                # 逐请求透传任务身份。无状态, 可以安全地在多个 server 之间共用
                # 同一个实例 —— 它读的是 ContextVar, 不持有任何自己的状态。
                "auth": TaskIdentityAuth(),
                "timeout": settings.mcp_tool_timeout_seconds,
            },

        }

    #  LangChain 的BaseTool格式工具列表 -> 用户自定义修饰的ToolSpec格式工具列表, 业务代码可以灵活调用
    async def list_tools(self) -> Sequence[ToolSpec]:
        """列出 Java 网关暴露的工具, 转成协议无关的 ToolSpec。"""
        raw_tools = await self._get_raw_tools()
        specs: list[ToolSpec] = []
        for tool in raw_tools:
            specs.append(
                ToolSpec(
                    name=tool.name,
                    description=tool.description or "",
                    input_schema=getattr(tool, "args_schema", {}) or {},
                )
            )
        return specs

    # 直接返回LangChain的 BaseTool 格式工具列表, agent 调用只认LangChain的 BaseTool 实例
    async def build_langchain_tools(self) -> Sequence[Any]:
        """返回原生 LangChain 工具对象, 直接喂给 LangGraph Agent。"""
        return await self._get_raw_tools()

    # JavaMCP Server返回的Json串-> MCP SDK .BaseTool-> LangChain 的 BaseTool  并放入缓存
    # "拿到需要修饰的原材料" : LangChain 的 BaseTool 实例
    async def _get_raw_tools(self) -> list[Any]:
        """带缓存的工具拉取。

        工具列表在运行期基本不变 (改工具要走 Java 侧注册中心),
        所以缓存起来, 避免每轮对话都打一次 MCP 的 tools/list。
        Phase2 接入工具变更通知 (notifications/tools/list_changed) 后再做失效。
        """
        if self._tools_cache is None:
            logger.info("正在从 MCP 网关拉取工具列表...")
            # client通过session会话向 server 发起tool/list请求
            # get_tools() 返回的是 LangChain 的 BaseTool 实例
            # get_tools() 内部把 MCP SDK的Tool类, 转换为 LangChain 的 BaseTool 实例
            self._tools_cache = list(await self._client.get_tools())
            logger.info(
                "已加载 %d 个 MCP 工具: %s",
                len(self._tools_cache),
                [t.name for t in self._tools_cache],
            )
        return self._tools_cache

    async def aclose(self) -> None:
        """应用关闭时调用。

        MultiServerMCPClient 在 0.2.0+ 不暴露显式的 close 接口
        (session 是按次管理的), 这里主要是清缓存并留一个扩展点。
        """
        self._tools_cache = None
