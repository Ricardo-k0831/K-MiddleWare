"""端到端验收 —— 任务身份从 Python 一路走到 audit_log。

用法 (在 km-agent-runtime/ 目录下):

    uv run python scripts/verify_identity_headers.py

前置: km-mock-order-service :8082、km-mcp-server :8081 在跑,
      以及 docker compose 里的 rocketmq + mysql。

## 为什么单独一个脚本

这一条链路横跨三种语言边界和五个进程, 而【每一段都可能静默失效】:

    Python ContextVar -> httpx.Auth -> HTTP 头 -> Java contextExtractor
      -> McpTransportContext -> @McpTool 参数 -> AuditEvent -> RocketMQ
      -> 消费端 -> audit_log

中间任何一环断了, 工具调用本身【照常返回正确的业务数据】—— 用户看不出
任何异常, 只有审计里 agent_task_id 是 NULL。靠肉眼回归发现不了, 只能靠
"发一次带身份的请求, 再去库里看那一行"。

## 为什么走真的 provider, 不是直接发 HTTP

直接发 HTTP 只能证明"网关读头是对的", 证明不了 langchain-mcp-adapters
会把 TaskIdentityAuth 挂上去。而后者才是这次改动的全部内容 ——
它依赖一个非显然的事实: `_create_streamable_http_session` 会把连接配置里的
`auth` 透传给 httpx_client_factory。库升级后这个透传消失的话, 只有真跑
一次才看得出来。

## 脚本自己不断言数据库

断言放在 SQL 里打印出来, 由人执行 —— 因为读 MySQL 要走 docker exec
(宿主机的 3306 被另一个 MySQL 占着, 见 km-local-dev-env-stack),
在脚本里塞一层 docker subprocess 只会让它在别的机器上跑不起来。
"""

from __future__ import annotations

import asyncio
import sys

import httpx

from app.config import get_settings
from app.mcp_adapter import TaskIdentity, build_tool_provider, task_identity

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

TASK_ID_A = "task-identity-verify-A"
TRACE_ID_A = "trace-identity-verify-A"
TASK_ID_B = "task-identity-verify-B"

ORDER_OK = "SO20260901001"


async def gateway_is_up(url: str) -> bool:
    base = url.rsplit("/mcp", 1)[0]
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            await client.get(base)
        return True
    except httpx.HTTPError:
        return False


async def call_order_tool(provider, tool_name: str, order_id: str) -> str:
    """调一次工具, 返回一句话描述结果 (成功或失败都不抛)。"""
    tools = {tool.name: tool for tool in await provider.build_langchain_tools()}
    tool = tools.get(tool_name)
    if tool is None:
        return f"!! 工具 {tool_name} 不在列表里: {sorted(tools)}"

    try:
        result = await tool.ainvoke({"orderId": order_id})
    except Exception as ex:  # noqa: BLE001 —— 失败也是要验的一条路径
        return f"调用抛异常: {type(ex).__name__}: {ex}"

    # MCP 工具返回的是 content 块列表, 挑出文字部分够用了
    if isinstance(result, list):
        text = " ".join(
            part.get("text", "") for part in result if isinstance(part, dict)
        )
    else:
        text = str(result)
    return (text or "(空)")[:120].replace("\n", " ")


async def main() -> int:
    settings = get_settings()
    gateway = settings.mcp_order_server_url

    print(f"1) 检查 MCP 网关 {gateway} ...")
    if not await gateway_is_up(gateway):
        print("   !! 网关不可达。先起 km-mcp-server (8081)。")
        return 2
    print("   ok")

    provider = build_tool_provider(settings)
    try:
        print("2) 用任务 A 的身份查一个存在的订单 ...")
        with task_identity(
            TaskIdentity(
                task_id=TASK_ID_A,
                trace_id=TRACE_ID_A,
                tenant_id="km",
                actor="verify-script",
            )
        ):
            print("   ->", await call_order_tool(provider, "get_order_by_id", ORDER_OK))

        print("3) 用任务 B 的身份查同一个订单 (同一个 provider, 身份必须换过来) ...")
        with task_identity(TaskIdentity(task_id=TASK_ID_B, actor="verify-script")):
            print("   ->", await call_order_tool(provider, "get_order_by_id", ORDER_OK))

        print("4) 不带身份查同一个订单 (应当记成匿名, 但照样成功) ...")
        print("   ->", await call_order_tool(provider, "get_order_by_id", ORDER_OK))
    finally:
        await provider.aclose()

    print(f"""
发出去的身份:

    A: task={TASK_ID_A} trace={TRACE_ID_A}
    B: task={TASK_ID_B}            (只有 taskId, 不该出现 trace 列)
    匿名: 不带任何 X-KM-* 头, 审计里这三列应当全为 NULL

5) 去库里对账 (密码用 docker-compose.yml 里的 dev 值):

docker exec km-mysql mysql -uroot -p<密码> km_control -e "
  SELECT id, layer, agent_task_id, trace_id, actor,
         JSON_UNQUOTE(JSON_EXTRACT(request_body,'$.tool')) AS tool
  FROM audit_log
  WHERE agent_task_id LIKE 'task-identity-verify-%'
     OR (agent_task_id IS NULL AND id > (SELECT MAX(id) - 20 FROM audit_log))
  ORDER BY id;"

判据:
  * A 那两行 agent_task_id = {TASK_ID_A}, trace_id = {TRACE_ID_A},
    actor = verify-script
  * B 那两行 agent_task_id = {TASK_ID_B}, trace_id 为 NULL
  * 匿名那两行三个字段全 NULL —— 但工具【返回了真实订单数据】
  * 每次工具调用产生两行 (MCP_GATEWAY + LEGACY_SERVICE), 谁先入库不定 ——
    MQ 是异步的, 顺序不作为判据
  * 如果 B 的行上出现 A 的 task_id, 说明身份是连接级的而不是请求级的,
    也就是本模块存在的全部理由被推翻了
""")
    return 0


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
