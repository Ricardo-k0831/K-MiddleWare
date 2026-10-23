"""网关层审计的端到端验收 —— 一次跑完三条路径, 每条都留下可查的审计痕迹。

## 它在验证什么

    Python 请求头
      -> McpTransportContextExtractor  (McpGatewayAuditConfig)
      -> McpTransportContext
      -> @McpTool 的参数               (OrderMcpTools)
      -> TaskIdentity
      -> AuditEvent.taskId / traceId
      -> KM_AUDIT topic
      -> audit_log 表

这条链任何一环断了, 症状都是<b>审计里 taskId 是空的</b> —— 不报错, 不告警。
所以要专门用一个带任务身份的调用去证明它通了, 并且用一个<b>不带</b>身份的
调用去证明身份确实来自请求头 (而不是别的什么碰巧填上的东西)。

## 三条路径

1. `WITH IDENTITY` —— 带上 X-KM-* 头, 查一个存在的订单。
   期望: audit_log 里出现 taskId = task-gw-001 的两条事件
         (layer=MCP_GATEWAY 和 layer=LEGACY_SERVICE)。

2. `ANONYMOUS` —— 什么都不带, 查同一个订单。
   期望: 调用照样成功 (审计不该拦业务), 但落库的 taskId 为 NULL。
         ★ 这一条是关键 ★ 它证明 taskId 不是凭空来的。

3. `NOT FOUND` —— 带上头, 查一个不存在的订单。
   期望: status = OK (后端明确回了 404, 这不是故障), 且 Agent 收到的
         自然语言是"没有找到订单号..."而不是"后端异常"。

## 跑之前

    mvn -pl km-mock-order-service spring-boot:run   # 8082
    mvn -pl km-task-service     spring-boot:run     # 8083
    mvn -pl km-mcp-server       spring-boot:run     # 8081

## 怎么跑

    uv run --project ../km-agent-runtime python verify_gateway_audit.py

用 uv 借 km-agent-runtime 的环境, 因为需要官方 mcp 客户端 —— 不自己拼
MCP 的握手和 SSE 解析, 免得验收脚本自己成为bug来源。

## 跑完之后看哪里

    docker exec km-mysql mysql -ukm -pkm_dev_pwd km_control -e "
      SELECT layer, agent_task_id, trace_id, status, latency_ms, LEFT(request_body,60)
      FROM audit_log WHERE TRACE_ID_IN  ORDER BY id DESC LIMIT 10;"
"""

from __future__ import annotations

import asyncio
import sys
from typing import Any

from mcp import ClientSession
from mcp.client.streamable_http import streamablehttp_client

GATEWAY_URL = "http://localhost:8081/mcp"

# 订单号来自 km-mock-order-service 的种子数据
EXISTING_ORDER = "SO20260901001"
MISSING_ORDER = "SO20990101999"

# 这三个值等会儿要在数据库里按它们查, 所以写得显眼一点
TASK_ID = "task-gw-verify-001"
TRACE_ID = "trace-gw-verify-001"
ACTOR = "verify-script"

# 网关认的就是这几个头, 名字定义在 km-audit-core 的 AuditHeaders 里
IDENTITY_HEADERS = {
    "X-KM-Task-Id": TASK_ID,
    "X-KM-Trace-Id": TRACE_ID,
    "X-KM-Actor": ACTOR,
}


async def _call_tool(headers: dict[str, str] | None,
                     tool: str,
                     arguments: dict[str, Any]) -> Any:
    """连一次网关, 调一个工具, 断开。

    刻意每次都新建连接, 不复用会话: 身份头是<b>按 HTTP 请求</b>抽的
    (见 McpGatewayAuditConfig 的注释), 复用会话会让"头到底有没有生效"
    变得说不清 —— 而这次验收要证明的恰恰就是这件事。
    """
    async with streamablehttp_client(GATEWAY_URL, headers=headers) as (read, write, _):
        async with ClientSession(read, write) as session:
            await session.initialize()
            return await session.call_tool(tool, arguments)


def _summarize(result: Any) -> str:
    """把 CallToolResult 压成一行, 方便在终端里看。"""
    structured = getattr(result, "structuredContent", None)
    if structured is not None:
        return f"structuredContent={structured}"
    return f"content={getattr(result, 'content', None)}"


async def case_with_identity() -> None:
    print(f"\n=== [1/3] 带身份查询 {EXISTING_ORDER} ===")
    result = await _call_tool(IDENTITY_HEADERS, "get_order_by_id", {"orderId": EXISTING_ORDER})
    print(f"  返回: {_summarize(result)}")
    print(f"  期望落库: taskId={TASK_ID} 的两条事件 (MCP_GATEWAY + LEGACY_SERVICE)")


async def case_anonymous() -> None:
    print(f"\n=== [2/3] 不带任何身份头, 查同一个订单 ===")
    result = await _call_tool(None, "get_order_by_id", {"orderId": EXISTING_ORDER})
    print(f"  返回: {_summarize(result)}")
    print("  期望: 调用照样成功 (审计是旁路, 不该拦业务), 但落库的 agent_task_id 为 NULL")


async def case_not_found() -> None:
    print(f"\n=== [3/3] 带身份查询不存在的订单 {MISSING_ORDER} ===")
    result = await _call_tool(IDENTITY_HEADERS, "get_order_by_id", {"orderId": MISSING_ORDER})
    print(f"  返回: {_summarize(result)}")
    print("  期望: 状态记 OK (404 是后端明确回答, 不是故障),")
    print("        且给 Agent 的话是「没有找到订单号」而不是「后端服务异常」")


async def main() -> int:
    print(f"网关: {GATEWAY_URL}")
    try:
        await case_with_identity()
        await case_anonymous()
        await case_not_found()
    except Exception as ex:  # noqa: BLE001 —— 验收脚本, 报什么都原样打出来
        print(f"\n[FAIL] {type(ex).__name__}: {ex}", file=sys.stderr)
        return 1

    print("\n三条路径都走完了。接下来去库里核对:")
    print("  docker exec km-mysql mysql -ukm -pkm_dev_pwd km_control -e \""
          f"SELECT layer, agent_task_id, trace_id, status, latency_ms, "
          f"LEFT(request_body,70) AS req FROM audit_log "
          f"WHERE trace_id='{TRACE_ID}' OR agent_task_id IS NULL "
          f"ORDER BY id DESC LIMIT 10;\"")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
