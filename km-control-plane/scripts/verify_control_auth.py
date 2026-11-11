"""控制面鉴权 (:8083) 的端到端验收。

用法 (任何 Python 3 都行, 不依赖虚拟环境):

    python km-control-plane/scripts/verify_control_auth.py

前置: km-task-service 已启动, 且 km.control-auth 用的是默认开发密钥
      (dev-user-key-change-me / dev-service-key-change-me)。
      用环境变量覆盖过密钥的话, 这里也要给同样的值:
      KM_CONTROL_USER_KEY / KM_CONTROL_SERVICE_KEY。

退出码: 0 = 全通过, 1 = 有用例失败, 2 = 前置检查没过。

## 这个脚本要证明什么

鉴权不是"多了一个 401", 它同时改变了四件事:

  1. **没有密钥就进不了业务** —— 401, 而且不是"先执行再拒绝"。
  2. **角色是硬边界** —— USER 密钥能读/取消/重试自己的任务, 但不能上报
     状态。否则拿到用户密钥的脚本就能伪造 COMPLETED, 状态机的事实源
     就不成立了。
  3. **租户是隔离边界** —— 跨租户读任务一律 404 (不是 403): 403 等于
     告诉对方"这个 ID 存在", 那就是一个可枚举的存在性探针。
     唯一例外是建任务时显式声明了别人的租户 —— 那是参数伪造, 回 403。
  4. **身份要进审计** —— 重试/取消的 actor 是真正点按钮的人, 不是任务
     归属人。这一条 HTTP 接口看不见, 脚本末尾会打印 SQL 由人核对。

## 为什么不用 curl

同 verify_task_service.py: Windows 控制台 GBK 会破坏中文 JSON, 而且
错误信息指不到编码问题。这里用 urllib + UTF-8。

## 关于租户名

脚本假设默认配置里 USER 密钥的租户是 `demo`、SERVICE 密钥通配。
这两个值写在 application.yml 的 km.control-auth 里, 改配置要同步改这里。
"""

from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
import uuid

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

BASE = os.environ.get("KM_TASK_BASE", "http://localhost:8083")
RUN_ID = uuid.uuid4().hex[:8]

USER_KEY = os.environ.get("KM_CONTROL_USER_KEY", "dev-user-key-change-me")
SERVICE_KEY = os.environ.get("KM_CONTROL_SERVICE_KEY", "dev-service-key-change-me")

# 默认配置里 USER 密钥的租户和操作者名字。见 application.yml 的 km.control-auth。
USER_TENANT = "demo"
USER_ACTOR = "demo-user"
# 另一个租户, 用来制造"跨租户"。和 USER_TENANT 不同即可。
OTHER_TENANT = "km"

PASSED: list[str] = []
FAILED: list[str] = []


# ----------------------------------------------------------------------
#  HTTP
# ----------------------------------------------------------------------


def call(method: str, path: str, body: dict | None = None,
         key: str | None = None) -> tuple[int, dict]:
    """发一个请求。key=None 表示故意不带密钥 (测 401)。"""
    data = json.dumps(body, ensure_ascii=False).encode("utf-8") if body is not None else None
    headers = {"Content-Type": "application/json; charset=utf-8"}
    if key is not None:
        headers["X-KM-Api-Key"] = key
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, {"_raw": raw}


def check(name: str, ok: bool, detail: str = "") -> None:
    if ok:
        PASSED.append(name)
        print(f"  [OK]   {name}")
    else:
        FAILED.append(name)
        print(f"  [FAIL] {name}")
        if detail:
            for line in detail.splitlines():
                print(f"         {line}")


TRACES: dict[str, str] = {}


def create_task(key: str, **overrides) -> tuple[int, dict]:
    body = {
        "userId": "U-auth",
        "sessionId": f"auth-{RUN_ID}",
        "intent": "鉴权验收: 查订单 SO20260901001",
    }
    body.update(overrides)
    return call("POST", "/api/tasks", body, key)


def create_ok(key: str, **overrides) -> dict:
    status, resp = create_task(key, **overrides)
    assert status == 201, f"建任务失败: HTTP {status} {resp}"
    TRACES[resp["agentTaskId"]] = resp["traceId"]
    return resp


def report(task_id: str, to_status: str, detail: dict | None = None,
           key: str = SERVICE_KEY) -> tuple[int, dict]:
    body: dict = {"toStatus": to_status, "traceId": TRACES[task_id]}
    if detail is not None:
        body["detail"] = detail
    return call("PATCH", f"/api/tasks/{task_id}/status", body, key)


# ----------------------------------------------------------------------
#  用例
# ----------------------------------------------------------------------


def case_unauthenticated() -> None:
    print("\n用例 1: 没有密钥 / 密钥错误 —— 一律 401")

    task = create_ok(SERVICE_KEY)

    status, resp = call("GET", f"/api/tasks/{task['agentTaskId']}", key=None)
    check("不带密钥读任务返回 401", status == 401, f"HTTP {status} {resp}")
    check("错误码是 UNAUTHENTICATED", resp.get("error") == "UNAUTHENTICATED", str(resp))

    # 不存在的任务在这里也必须是 401 而不是 404 —— 认证先于业务。
    status, resp2 = call("GET", f"/api/tasks/__probe__{RUN_ID}", key=None)
    check("不存在的 ID 不带密钥也是 401 (认证先于业务)",
          status == 401 and resp2.get("error") == "UNAUTHENTICATED",
          f"HTTP {status} {resp2}")

    status, resp3 = call("GET", f"/api/tasks/{task['agentTaskId']}", key="wrong-key")
    check("错误密钥返回 401", status == 401, f"HTTP {status} {resp3}")
    check("错误密钥和缺密钥的响应不可区分",
          resp2.get("error") == resp3.get("error")
          and resp2.get("message") == resp3.get("message"),
          f"缺={resp2} 错={resp3}")

    status, resp4 = call("POST", "/api/tasks",
                         {"userId": "U-auth", "sessionId": "s", "intent": "x"}, key=None)
    check("不带密钥建任务返回 401", status == 401, f"HTTP {status} {resp4}")


def case_create_tenant() -> None:
    print("\n用例 2: 建任务时的租户归属")

    # USER 不写 tenantId -> 用密钥自己的租户, 而不是请求体 (请求体根本没写)
    mine = create_ok(USER_KEY)
    check(f"USER 不写租户时落在自己的租户 {USER_TENANT}",
          mine.get("tenantId") == USER_TENANT, f"实际 {mine.get('tenantId')!r}")

    # USER 写别人的租户 -> 403。这里不存在信息泄露: 租户名是调用方自己写的。
    status, resp = create_task(USER_KEY, tenantId=OTHER_TENANT)
    check("USER 以别人的租户建任务返回 403 FORBIDDEN",
          status == 403 and resp.get("error") == "FORBIDDEN", f"HTTP {status} {resp}")

    # SERVICE 通配, 可以替任意租户建 (Python 运行时替所有租户执行)
    other = create_ok(SERVICE_KEY, tenantId=OTHER_TENANT)
    check(f"SERVICE 可以替租户 {OTHER_TENANT} 建任务",
          other.get("tenantId") == OTHER_TENANT, f"实际 {other.get('tenantId')!r}")

    # 同一密钥写自己的租户名 -> 允许
    same = create_ok(USER_KEY, tenantId=USER_TENANT)
    check("USER 写自己租户的名字仍然成功", same.get("tenantId") == USER_TENANT)


def case_cross_tenant_read() -> None:
    print("\n用例 3: 跨租户读 —— 404 而不是 403")

    other = create_ok(SERVICE_KEY, tenantId=OTHER_TENANT)
    task_id = other["agentTaskId"]

    status, resp = call("GET", f"/api/tasks/{task_id}", key=USER_KEY)
    check("USER 读别的租户的任务返回 404", status == 404, f"HTTP {status} {resp}")
    check("错误码是 TASK_NOT_FOUND (和真的不存在不可区分)",
          resp.get("error") == "TASK_NOT_FOUND", str(resp))

    status, resp = call("GET", f"/api/tasks/{task_id}/events", key=USER_KEY)
    check("别的租户的事件历史同样 404", status == 404, f"HTTP {status} {resp}")

    status, resp = call("POST", f"/api/tasks/{task_id}/cancel", {"reason": "越权"}, USER_KEY)
    check("USER 取消别的租户的任务返回 404 而不是 409",
          status == 404 and resp.get("error") == "TASK_NOT_FOUND",
          f"HTTP {status} {resp}")

    # SERVICE 通配, 读得到
    status, _ = call("GET", f"/api/tasks/{task_id}", key=SERVICE_KEY)
    check("SERVICE 读得到别的租户的任务 (通配)", status == 200, f"HTTP {status}")


def case_role_boundary() -> None:
    print("\n用例 4: 角色边界 —— USER 不能上报状态/心跳")

    user_task = create_ok(USER_KEY)
    task_id = user_task["agentTaskId"]

    status, resp = report(task_id, "THINKING", key=USER_KEY)
    check("USER 密钥上报状态返回 403 FORBIDDEN",
          status == 403 and resp.get("error") == "FORBIDDEN", f"HTTP {status} {resp}")

    status, resp = call("POST", f"/api/tasks/{task_id}/heartbeat",
                        {"traceId": TRACES[task_id]}, USER_KEY)
    check("USER 密钥发心跳返回 403", status == 403, f"HTTP {status} {resp}")

    status, task = call("GET", f"/api/tasks/{task_id}", key=SERVICE_KEY)
    check("被拒的上报没有改动任务状态", task.get("status") == "PENDING",
          f"实际 {task.get('status')}")

    # SERVICE 同一个动作是允许的
    status, _ = report(task_id, "THINKING")
    check("SERVICE 密钥可以上报状态", status == 200, f"HTTP {status}")
    status, _ = call("POST", f"/api/tasks/{task_id}/heartbeat",
                     {"traceId": TRACES[task_id]}, SERVICE_KEY)
    check("SERVICE 密钥可以发心跳", status == 204, f"HTTP {status}")


def case_actor_in_audit() -> None:
    print("\n用例 5: 取消/重试的 actor 是调用方, 不是任务归属用户")

    task = create_ok(USER_KEY, userId="U-owner")
    task_id = task["agentTaskId"]

    status, resp = call("POST", f"/api/tasks/{task_id}/cancel",
                        {"reason": "验收取消"}, USER_KEY)
    check("USER 可以取消自己租户的任务", status == 200, f"HTTP {status} {resp}")
    # 取消的响应是 {task, runtime}: 任务本体嵌在 task 里, 不是平铺的。
    check("取消后状态是 CANCELLED", (resp.get("task") or {}).get("status") == "CANCELLED",
          f"实际 {(resp.get('task') or {}).get('status')!r}")

    # 重试: 先由 SERVICE 把它弄成 FAILED, 再由 USER 重试。
    retry_task = create_ok(USER_KEY, userId="U-owner")
    rid = retry_task["agentTaskId"]
    report(rid, "THINKING")
    report(rid, "FAILED", {"error": "验收失败"})
    status, resp = call("POST", f"/api/tasks/{rid}/retry", {"reason": "验收重试"}, USER_KEY)
    check("USER 可以重试自己租户的失败任务", status == 200, f"HTTP {status} {resp}")

    # 自动重试的 actor 是 "system" —— 那一层在 RetrySchedulerTest 里钉住,
    # 这里给出人工核对 SQL: actor 两列应当都是 demo-user, 不是 U-owner。
    print(f"""
  ------------------------------------------------------------------
  人工核对 (HTTP 看不见 actor, 读 MySQL):

  docker exec km-mysql mysql -ukm -pkm_dev_pwd km_control -e "
  SELECT agent_task_id, event_type, actor, status, occurred_at
  FROM audit_log
  WHERE agent_task_id IN ('{task_id}', '{rid}')
  ORDER BY occurred_at, id;"

  期望: 取消那条和重试那条的 actor = '{USER_ACTOR}' (密钥对应的
  操作者), 而 task.user_id 是 'U-owner'。如果 actor 还是 U-owner,
  说明 Controller 没把 caller.userId() 传进 Service。
  ------------------------------------------------------------------
""")


def case_idempotent_and_unaffected() -> None:
    print("\n用例 6: 鉴权不改变业务语义")

    task = create_ok(SERVICE_KEY, tenantId=OTHER_TENANT)

    # 同一个 SERVICE 密钥连点取消: 第二次仍 200 (幂等), 不是 401/403
    status, first = call("POST", f"/api/tasks/{task['agentTaskId']}/cancel", None, SERVICE_KEY)
    status2, second = call("POST", f"/api/tasks/{task['agentTaskId']}/cancel", None, SERVICE_KEY)
    check("重复取消仍返回 200 (鉴权没有把幂等改成 409)",
          status == 200 and status2 == 200, f"HTTP {status} / {status2}")

    # 探活路径: 带密钥时还是 404 (业务 404), 证明过滤器没有把 404 吞掉
    status, resp = call("GET", f"/api/tasks/__probe__{RUN_ID}", key=SERVICE_KEY)
    check("带密钥的探活仍是 404 TASK_NOT_FOUND",
          status == 404 and resp.get("error") == "TASK_NOT_FOUND",
          f"HTTP {status} {resp}")


# ----------------------------------------------------------------------
#  入口
# ----------------------------------------------------------------------


def preflight() -> list[str]:
    # 带服务密钥探活: 服务活着 + 密钥生效。401 说明密钥配错了,
    # 不是"服务没起来" —— 两种情况要分开报, 否则会往错的方向查。
    status, resp = call("GET", f"/api/tasks/__probe__{RUN_ID}", key=SERVICE_KEY)
    if status == 401:
        return [f"SERVICE 密钥 {SERVICE_KEY!r} 没被接受 —— "
                "和 Java 的 KM_CONTROL_SERVICE_KEY 不一致?"]
    if status == 403:
        return [f"探活被 403 —— SERVICE 密钥的角色配错? {resp}"]
    if status != 404 or resp.get("error") != "TASK_NOT_FOUND":
        return [f"探活返回 {status} {resp} —— 服务起来了但不是这个任务服务?"]
    return []


def main() -> int:
    print("km-task-service 控制面鉴权验收")
    print(f"目标: {BASE}   本次运行 ID: {RUN_ID}")

    try:
        problems = preflight()
    except Exception as ex:
        problems = [f"km-task-service (:8083) 不可达: {type(ex).__name__}: {ex}"]
    if problems:
        print("\n前置检查未通过:")
        for p in problems:
            print(f"  - {p}")
        return 2
    print("\n前置检查通过。")

    for case in (
        case_unauthenticated,
        case_create_tenant,
        case_cross_tenant_read,
        case_role_boundary,
        case_actor_in_audit,
        case_idempotent_and_unaffected,
    ):
        try:
            case()
        except Exception as ex:
            FAILED.append(case.__name__)
            print(f"  [FAIL] {case.__name__} 抛出异常: {type(ex).__name__}: {ex}")

    total = len(PASSED) + len(FAILED)
    print(f"\n结果: {len(PASSED)}/{total} 通过")
    if FAILED:
        print("失败项:")
        for name in FAILED:
            print(f"  - {name}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
