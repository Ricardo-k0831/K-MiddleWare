"""端到端验收 —— 网关了的两道保护: 限流 + 熔断。

用法 (在 km-agent-runtime/ 目录下):

    # 第一阶段: 限流 (后端照常运行)
    uv run python scripts/verify_gateway_guard.py

    # 第二阶段: 熔断 (先【停掉】km-mock-order-service :8082, 再跑)
    uv run python scripts/verify_gateway_guard.py --breaker

前置: km-mcp-server :8081、km-mock-order-service :8082、km-redis :6379。

## 为什么分两阶段

限流要验"打太快会被挡", 熔断要验"后端挂了会快速失败" —— 前者需要后端
健康, 后者需要后端不健康, 两个前置条件互斥, 一个进程里做不到。所以用
参数区分, 由人控制后端状态。

## 为什么直接调工具, 不经过 LLM

这两道保护作用在<b>工具层</b>, 和 Agent 无关。经过 LLM 的话, 模型自己
会调整调用次数, 我们无法精确控制"发 12 次"这件事 —— 而限流的判据恰恰
是精确的次数。所以这里用和 verify_identity_headers.py 一样的
provider 直接调工具。

## 判据为什么是"回复里有没有限流提示", 不是 HTTP 状态码

MCP 工具从不抛异常给 Agent (见 OrderMcpTools 的类注释), 限流和熔断都被
转成了一段自然语言的错误 Map。所以 HTTP 永远 200 —— 判据只能在工具
返回的内容里。

真正落库的证据是审计的 DEGRADED 状态, 脚本末尾会打印 SQL 由人核对。
"""

from __future__ import annotations

import argparse
import asyncio
import sys
import time
import uuid

import httpx

from app.config import get_settings
from app.mcp_adapter import TaskIdentity, build_tool_provider, task_identity

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

RUN_ID = uuid.uuid4().hex[:8]

ORDER_OK = "SO20260901001"

RATE_LIMIT_MARKERS = ["限流", "过于频繁", "没有被执行"]
CIRCUIT_MARKERS = ["熔断", "没有被执行"]


async def gateway_is_up(url: str) -> bool:
    base = url.rsplit("/mcp", 1)[0]
    try:
        async with httpx.AsyncClient(timeout=3.0) as client:
            await client.get(base)
        return True
    except httpx.HTTPError:
        return False


async def load_tool(provider, tool_name: str):
    """取一个工具句柄。

    <p>★ 必须在循环外取一次 ★ `build_langchain_tools()` 每次调用都会重新
    建立 MCP 会话 (带握手), 本机实测约 500ms —— 如果在每次调用前都取一遍,
    测出来的"耗时"全是建连开销, 熔断前后完全看不出差别。
    """
    tools = {tool.name: tool for tool in await provider.build_langchain_tools()}
    return tools[tool_name]


async def call_once(tool, order_id: str) -> tuple[str, float]:
    """调一次工具, 返回 (文字描述, 毫秒耗时)。成功失败都不抛。"""
    started = time.perf_counter()
    try:
        result = await tool.ainvoke({"orderId": order_id})
    except Exception as ex:  # noqa: BLE001 —— 失败也是要验的路径
        elapsed = (time.perf_counter() - started) * 1000
        return f"调用抛异常: {type(ex).__name__}: {ex}", elapsed
    elapsed = (time.perf_counter() - started) * 1000

    if isinstance(result, list):
        text = " ".join(
            part.get("text", "") for part in result if isinstance(part, dict)
        )
    else:
        text = str(result)
    return (text or "(空)"), elapsed


def contains_any(text: str, markers: list[str]) -> bool:
    return any(marker in text for marker in markers)


# ----------------------------------------------------------------------
#  第一阶段: 限流
# ----------------------------------------------------------------------


async def phase_rate_limit(provider) -> int:
    print("阶段 1: 限流 —— 同一个任务并发打 12 次, 必须有一部分被挡")
    print("        (配置: 任务桶 burst=5 补充=2/s; 工具桶 burst=10 补充=5/s)")

    tool = await load_tool(provider, "get_order_by_id")
    task = TaskIdentity(
        task_id=f"guard-rate-{RUN_ID}",
        trace_id=f"guard-rate-trace-{RUN_ID}",
        tenant_id="km",
        actor="verify-guard",
    )
    with task_identity(task):
        results = await asyncio.gather(
            *[call_once(tool, ORDER_OK) for _ in range(12)]
        )

    texts = [t for t, _ in results]
    limited = [t for t in texts if contains_any(t, RATE_LIMIT_MARKERS)]
    succeeded = [t for t in texts if ORDER_OK in t and not contains_any(t, RATE_LIMIT_MARKERS)]

    print(f"  放行并返回真实订单: {len(succeeded)} 次")
    print(f"  被限流:             {len(limited)} 次")
    if limited:
        print(f"  限流提示样例: {limited[0][:110]}")

    ok = True
    if not limited:
        print("  [FAIL] 一次都没被限流 —— 限流没生效? 检查 Redis 连接和 km.gateway.rate-limit")
        ok = False
    else:
        print("  [OK]   有调用被限流")
    if not succeeded:
        print("  [FAIL] 一次都没成功 —— 限流把全部请求都挡了, 配置太紧")
        ok = False
    else:
        print("  [OK]   限流之前有调用正常返回了业务数据")

    # 限流必须能被 Agent 看懂: 明确说"没执行"和"别立刻重试",
    # 否则模型会自作主张重试, 把限流变成更密的流量。
    if limited and all("没有被执行" in t for t in limited):
        print("  [OK]   限流提示都说明了「本次调用没有被执行」")
    elif limited:
        print("  [WARN] 有限流提示没写清楚「没有执行」, Agent 可能误解为订单不存在")

    print(f"""
  人工核对 (审计里这些调用应当是 DEGRADED, 不是 ERROR):

  docker exec km-mysql mysql -ukm -pkm_dev_pwd km_control -e "
  SELECT layer, event_type, status, error_msg
  FROM audit_log
  WHERE agent_task_id = 'guard-rate-{RUN_ID}'
  ORDER BY occurred_at DESC LIMIT 15;"

  期望: 被挡掉的那些是 MCP_GATEWAY + DEGRADED, 而且【没有】对应的
        LEGACY_SERVICE 行 —— 限流的意义就是不让请求到达后端。
""")
    return 0 if ok else 1


# ----------------------------------------------------------------------
#  第二阶段: 熔断
# ----------------------------------------------------------------------


async def phase_breaker(provider) -> int:
    print("阶段 2: 熔断 —— 后端已停, 连续失败后必须快速失败")
    print("        (配置: 30s 窗口内失败率 >=50% 且调用 >=5 次 -> 打开)")

    tool = await load_tool(provider, "get_order_by_id")
    task = TaskIdentity(
        task_id=f"guard-breaker-{RUN_ID}",
        trace_id=f"guard-breaker-trace-{RUN_ID}",
        tenant_id="km",
        actor="verify-guard",
    )

    texts: list[str] = []
    latencies: list[float] = []
    with task_identity(task):
        for i in range(10):
            text, elapsed = await call_once(tool, ORDER_OK)
            texts.append(text)
            latencies.append(elapsed)
            print(f"  第 {i + 1:2d} 次: {elapsed:6.1f}ms  {text[:80]}")
            # 间隔 600ms: 让任务桶 (2/s) 有令牌, 否则测到的是限流不是熔断
            await asyncio.sleep(0.6)

    opened = [t for t in texts if "熔断" in t]
    ok = True
    if not opened:
        print("  [FAIL] 没有出现熔断拒绝 —— 后端真的停了吗? 检查 8082 是否还在监听")
        ok = False
    else:
        print(f"  [OK]   熔断打开: {len(opened)} 次调用被快速拒绝")

    # 熔断前后的耗时对比: 打开前要等 TCP 连接失败, 打开后一个请求
    # 都不发。本机 refused 很快, 所以只做提示不做硬断言。
    if opened:
        first_open = next(i for i, t in enumerate(texts) if "熔断" in t)
        if first_open > 0:
            print(f"  提示: 熔断在第 {first_open + 1} 次调用后打开 "
                  f"(打开前平均 {sum(latencies[:first_open]) / first_open:.1f}ms, "
                  f"打开后 {sum(latencies[first_open:]) / max(1, len(latencies) - first_open):.1f}ms)")

    print(f"""
  人工核对 (熔断前后的审计差异):

  docker exec km-mysql mysql -ukm -pkm_dev_pwd km_control -e "
  SELECT layer, event_type, status, latency_ms, LEFT(COALESCE(error_msg,''), 40) AS err
  FROM audit_log
  WHERE agent_task_id = 'guard-breaker-{RUN_ID}'
  ORDER BY occurred_at, id;"

  期望:
    * 熔断打开【之前】每次调用有两行 (MCP_GATEWAY + LEGACY_SERVICE), 都是 ERROR
    * 熔断打开【之后】只有 MCP_GATEWAY + DEGRADED 一行 —— LEGACY_SERVICE
      那行不存在, 因为请求根本没发出去
""")

    print("  ------------------------------------------------------------------")
    print("  下一步: 重新启动 km-mock-order-service (8082), 等约 10 秒")
    print("  (熔断打开后 wait-duration-in-open-state-seconds=10),")
    print("  再随便调一次工具应当恢复正常 —— 半开试探成功会自动关闭熔断,")
    print("  不需要重启网关。网关日志里会有一行「试探成功, 后端已恢复」。")
    print("  ------------------------------------------------------------------")
    return 0 if ok else 1


# ----------------------------------------------------------------------


async def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--breaker", action="store_true",
                        help="跑熔断阶段 (需要先停掉 8082)")
    args = parser.parse_args()

    settings = get_settings()
    gateway = settings.mcp_order_server_url

    print(f"目标: {gateway}   本次运行 ID: {RUN_ID}")
    if not await gateway_is_up(gateway):
        print("  [前置失败] 网关不可达。先起 km-mcp-server (8081)。")
        return 2

    provider = build_tool_provider(settings)
    try:
        if args.breaker:
            return await phase_breaker(provider)
        return await phase_rate_limit(provider)
    finally:
        await provider.aclose()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(main()))
