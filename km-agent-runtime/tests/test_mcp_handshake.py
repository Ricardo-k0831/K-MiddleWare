"""★ 第 0 号验收测试 —— 整个项目最重要的一次验证。

## 它在验证什么

Java 侧 Spring AI 2.0.1 底层是 io.modelcontextprotocol.sdk:mcp:2.0.0,
实现的是 MCP 2026-07-28 的【无状态】协议;
Python 侧 langchain-mcp-adapters 0.3.2 却要求 mcp<2.0.0, 走的是
【带 initialize 握手的旧版】协议。

这两者能不能对上, 是整个"Java 控制面 + Python Agent 运行时"架构的地基。
如果对不上, 后面所有模块都是白写。

## 跑之前需要

1. docker compose -f docker/docker-compose.yml up -d
2. 起 km-mock-order-service (8082)
3. 起 km-mcp-server (8081)
4. uv run pytest tests/test_mcp_handshake.py -v -s

## 如果失败了怎么看

* 连不上 8081        -> MCP 网关没起来, 或端口不对
* 握手阶段报协议错误 -> 大概率是协议版本对不上。先把 km-mcp-server 的
                        application.yml 里 protocol 确认为 streamable,
                        再去 Spring AI 的 issue 区搜 "protocol version"
* 工具列表为空       -> 握手通了但工具没注册上。检查 OrderMcpTools 是否有
                        @Component, 以及 @McpTool 的扫描是否开启
                        (spring.ai.mcp.server.annotation-scanner.enabled 默认 true)
"""

from __future__ import annotations

import httpx
import pytest

from app.config import get_settings
from app.mcp_adapter import build_tool_provider

pytestmark = pytest.mark.asyncio


async def _gateway_is_up(url: str) -> bool:
    """先把"服务没起"和"协议对不上"两种情况区分开。

    否则测试失败时报一堆连接异常, 很难一眼看出到底是哪类问题。
    """
    base = url.rsplit("/mcp", 1)[0]
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            await client.get(base)
            return True
    except httpx.HTTPError:
        # 连接被拒 / 超时 / DNS 失败都归为"服务没起"。
        # 这里刻意不区分具体异常 —— 对调用方来说处理方式都是同一种:
        # 先 skip 并提示去启动服务。
        return False


async def test_mcp_gateway_reachable() -> None:
    """前置检查: Java MCP 网关是否可达。"""
    settings = get_settings()
    ok = await _gateway_is_up(settings.mcp_order_server_url)
    if not ok:
        pytest.skip(
            f"MCP 网关 {settings.mcp_order_server_url} 不可达 —— "
            "请先启动 km-mcp-server (端口 8081)"
        )


async def test_mcp_handshake_and_tool_discovery() -> None:
    """核心验收: 握手成功, 且能列出 Java 侧注册的工具。"""
    settings = get_settings()

    if not await _gateway_is_up(settings.mcp_order_server_url):
        pytest.skip("MCP 网关不可达, 请先启动 km-mcp-server")

    provider = build_tool_provider(settings)

    # 这一步内部会完成: 连接 -> 协议握手 -> tools/list
    tools = await provider.list_tools()

    assert tools, "握手成功但工具列表为空 —— 检查 OrderMcpTools 是否被扫描到"

    names = {t.name for t in tools}
    print(f"\n[OK] MCP 握手成功, 拿到 {len(tools)} 个工具: {sorted(names)}")

    assert "get_order_by_id" in names, f"缺少订单查询工具, 实际拿到: {sorted(names)}"
    assert "list_orders_by_user" in names, f"缺少用户订单列表工具, 实际拿到: {sorted(names)}"


async def test_tool_call_end_to_end() -> None:
    """端到端: 真的调用一次 Java 侧工具, 拿到业务数据。

    只握手成功还不够 —— 要证明参数能传过去、结果能传回来。
    """
    settings = get_settings()

    if not await _gateway_is_up(settings.mcp_order_server_url):
        pytest.skip("MCP 网关不可达, 请先启动 km-mcp-server")

    provider = build_tool_provider(settings)
    tools = await provider.build_langchain_tools()

    order_tool = next((t for t in tools if t.name == "get_order_by_id"), None)
    assert order_tool is not None, "没有找到 get_order_by_id 工具"

    # 这个订单号来自 km-mock-order-service 的种子数据
    result = await order_tool.ainvoke({"orderId": "SO20260901001"})
    print(f"\n[OK] 工具调用返回: {result}")

    assert result, "工具调用返回空结果"
    assert "SO20260901001" in str(result), f"返回内容里没有订单号: {result}"
