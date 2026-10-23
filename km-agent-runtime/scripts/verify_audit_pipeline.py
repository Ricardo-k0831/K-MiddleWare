"""端到端验收 —— 四层审计是不是真的落进了同一张表。

用法 (在 km-agent-runtime/ 目录下):

    uv run python scripts/verify_audit_pipeline.py

前置: km-mock-order-service :8082、km-mcp-server :8081、km-task-service :8083
      在跑, 以及 docker compose 里的 rocketmq + mysql。

## 这条链路和 verify_identity_headers.py 的分工

那个脚本验的是【身份送出去】—— 从 Python 的 ContextVar 一路到网关读头。
这个脚本验的是【事件收回来】—— 从三层各自的采集点经过 MQ 落到 audit_log。

两段的失败方式完全不同, 所以必须是两个脚本:
身份断了的表现是"审计里 agent_task_id 全是 NULL";
采集点断了的表现是"某一层一条记录都没有", 而另外两层看着完全正常。

## 为什么必须发真任务

审计的每一条记录都要能归属到一个 task_id, 而 task_id 只能由
Java 的 POST /api/tasks 生成 —— 所以这个脚本必须先建任务, 再拿着它去 /chat。
用一个自己编的 task_id 也能跑通 HTTP, 但那样看到的东西完全不真实
(比如 Java 侧状态流转会一路 404)。

## 脚本自己不断言数据库

断言写成 SQL 打印出来, 由人执行 —— 读 MySQL 要走 docker exec
(宿主机的 3306 被另一个 MySQL 占着, 见 km-local-dev-env-traps),
在脚本里塞一层 docker subprocess 只会让它在别的机器上跑不起来。
"""

from __future__ import annotations

import sys

import httpx

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

TASK_SERVICE = "http://127.0.0.1:8083"
AGENT_RUNTIME = "http://127.0.0.1:8000"

INTENT = "查一下订单 SO20260901001 的物流"
USER_ID = "u-audit-verify"
TENANT_ID = "km"


def main() -> int:
    with httpx.Client(timeout=120.0) as client:
        # ---- 第 1 步: 建任务。trace_id 由 Java 生成, 见 TaskService.create ----
        created = client.post(
            f"{TASK_SERVICE}/api/tasks",
            json={
                "userId": USER_ID,
                "sessionId": "s-audit-verify",
                "intent": INTENT,
                "tenantId": TENANT_ID,
            },
        )
        if created.status_code != 201:
            print(f"建任务失败 HTTP {created.status_code}: {created.text}")
            return 1

        task = created.json()
        task_id = task["agentTaskId"]
        trace_id = task["traceId"]
        print(f"任务已建: task_id={task_id}")
        print(f"          trace_id={trace_id}   (由 Java 生成, Python 只转交)")

        # ---- 第 2 步: 跑一轮 Agent, 身份原样转交 ----
        print("\n正在跑 /chat (会真的调 LLM 和 MCP 工具, 约十几秒)...")
        chatted = client.post(
            f"{AGENT_RUNTIME}/chat",
            json={
                "message": INTENT,
                "task_id": task_id,
                "trace_id": trace_id,
                "tenant_id": TENANT_ID,
                "actor": USER_ID,
            },
        )
        if chatted.status_code != 200:
            print(f"/chat 失败 HTTP {chatted.status_code}: {chatted.text}")
            return 1

        body = chatted.json()
        print(f"\n回复: {body['reply']}")
        print(f"工具调用: {body['tool_calls']}")
        print(f"回显 trace_id: {body['trace_id']}  (应当和上面那个一样)")

        if body["trace_id"] != trace_id:
            print("★ trace_id 对不上 —— 审计会被劈成两条链路, 先查这个")
            return 1

    # --------------------------------------------------------------
    #  上面跑完, 事件还在 MQ 里往消费端走。下面这段 SQL 由人来执行。
    # --------------------------------------------------------------
    print(
        f"""
=====================================================================
请执行下面这段 SQL (宿主机 3306 被别的 MySQL 占着, 所以走 docker exec):

docker exec km-mysql mysql -uroot -proot_pwd km_control -e "
SELECT id, layer, event_type, status,
       latency_ms, token_in, token_out,
       LEFT(COALESCE(error_msg,''), 40) AS err,
       JSON_EXTRACT(request_body, '$.tool') AS tool,
       occurred_at
FROM audit_log
WHERE agent_task_id = '{task_id}'
ORDER BY occurred_at, id;"

期望看到 (同一个 agent_task_id 横跨三个进程/两种语言):

  TASK_SERVICE  TASK_LIFECYCLE  任务创建 + 每次状态流转 (PENDING/THINKING/
                                TOOL_CALLING/COMPLETED 各一条)
  AGENT_RUNTIME LLM_CALL        至少一条, token_in/token_out 有值
  AGENT_RUNTIME MCP_TOOL_CALL   request_body.tool = get_order_by_id
  MCP_GATEWAY   MCP_TOOL_CALL   同一件工具调用的另一半 (有 response_body)
  LEGACY_SERVICE MCP_TOOL_CALL  网关代存量服务记的那一次

再看一眼时间线是不是顺的 —— 跨层比对时间是最容易露馅的地方:

  ORDER BY occurred_at 之后, 每层的 occurred_at 应该单调不倒退。
  倒退了说明某一层用了"发送时刻"而不是"发生时刻" (见 app/audit.py 里
  emit() 的 occurred_at 参数说明)。

顺便确认 task_id 和 trace_id 两个维度都能查 (这正是"唯一标识"的落地):

docker exec km-mysql mysql -uroot -proot_pwd km_control -e "
SELECT layer, event_type, COUNT(*) AS n
FROM audit_log WHERE trace_id = '{trace_id}'
GROUP BY layer, event_type;"
=====================================================================
"""
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
