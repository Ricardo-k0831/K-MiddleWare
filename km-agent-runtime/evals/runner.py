"""跑一遍金标准用例, 出 JSON 报告。

用法 (在 km-agent-runtime/ 下, 服务全部起着):

    # 基线: 不开反思
    uv run python -m evals.runner --out evals/results/baseline.json

    # 开反思, 和基线对比
    uv run python -m evals.runner --reflect --baseline evals/results/baseline.json \
        --out evals/results/with_reflection.json

## 为什么要真的建任务、真的等审计落库

评测的数据源不是 Python 的返回值, 是 ``GET /api/tasks/{id}/trajectory`` ——
四层审计拼出来的轨迹。这个选择让评测同时验证了两件事:

* Agent 答得对不对;
* **中间件有没有把这次调用记下来**。审计链路断了的话, 评测会以
  "infrastructureErrors" 的形式失败, 而不是悄悄用一份 Python 自述去打分。

## 为什么串行跑

用例数很少 (十几条), 而并发会让: 限流的令牌桶被跨用例消耗、日志交错、
以及"第 5 条为什么慢"这种问题变得没法排查。评测要的是可复现, 不是吞吐。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

from app.config import get_settings
from app.graph.agent import build_llm
from evals.scorers import (
    CaseResult,
    compare,
    deterministic_checks,
    judge_case,
    runtime_tools,
    summarize,
    trajectory_stats,
)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

TENANT = "eval"
ACTOR = "eval-runner"
DEFAULT_DATASET = Path(__file__).with_name("dataset.jsonl")
DEFAULT_OUT = Path(__file__).parent / "results" / "latest.json"


# ----------------------------------------------------------------------
#  和 Java / Python 两个服务说话
# ----------------------------------------------------------------------


class Services:
    """两个 HTTP 客户端的薄封装。失败一律抛, 由调用方决定算不算基础设施错误。"""

    def __init__(self, settings: Any, agent_url: str | None = None) -> None:
        self.settings = settings
        self.control_headers = {"X-KM-Api-Key": settings.km_task_service_api_key}
        # 0.0.0.0 是监听地址, 不能用它去连自己。
        host = "127.0.0.1" if settings.app_host == "0.0.0.0" else settings.app_host
        self.agent_url = agent_url or f"http://{host}:{settings.app_port}"
        self.client = httpx.AsyncClient(timeout=120.0)

    async def aclose(self) -> None:
        await self.client.aclose()
    # 向java侧发起请求创建任务 返回AgentTask的json字符串
    async def create_task(self, *, question: str, session_id: str) -> dict:
        # response 为返回AgentTask的json字符串
        response = await self.client.post(
            f"{self.settings.km_task_service_url.rstrip('/')}/api/tasks",
            # 传入/api/tasks 相关路径的认证鉴权过滤器 header
            headers=self.control_headers,
            json={
                "userId": ACTOR,
                "sessionId": session_id,
                "intent": question,
                "tenantId": TENANT,
                "maxRetry": 0,
            },
        )
        # 检验请求是否成功
        response.raise_for_status()
        return response.json()
    # 向python侧发起请求聊天 返回聊天结果的json字符串(包含会话id、任务id、轨迹id、调用工具的集合、reflect)
    async def chat(self, *, question: str, task: dict, reflect: bool, max_reflections: int) -> dict:
        # response 为返回聊天结果的json字符串
        response = await self.client.post(
            f"{self.agent_url}/chat",
            json={
                "message": question,
                "task_id": task["agentTaskId"],
                "trace_id": task["traceId"],
                "session_id": task.get("sessionId"),
                "tenant_id": task.get("tenantId") or TENANT,
                "actor": ACTOR,
                "reflect": reflect,
                "max_reflections": max_reflections,
            },
        )
        response.raise_for_status()
        return response.json()

    # 向java侧轨迹评估接口发起请求 返回任务轨迹的json字符串
    async def trajectory(self, task_id: str) -> list[dict]:
        # response 为返回任务轨迹的json字符串
        response = await self.client.get(
            f"{self.settings.km_task_service_url.rstrip('/')}"
            f"/api/tasks/{task_id}/trajectory",
            headers=self.control_headers,
        )
        response.raise_for_status()
        return response.json()

    # 确认一个任务最终的审计记录列表 一定是已经定型的
    async def wait_for_trajectory(
        self,
        task_id: str,
        *,
        timeout_seconds: float = 10.0,
        settle_polls: int = 2,
        interval: float = 0.25,
    ) -> list[dict]:
        """等审计落库。

        RocketMQ 是异步汇聚, /chat 一返回并不代表四层都记完了。判据是
        "连续两次轮询行数不变" —— 只看"非空"会在只落了第一层时就开始打分,
        评测结果就会随机地好一点/差一点。
        """
        deadline = time.monotonic() + timeout_seconds
        rows: list[dict] = []
        last_len = -1
        stable = 0
        # 当前时间未逾期
        while time.monotonic() < deadline:
            # 调用Trajectory.java轨迹评估接口  记录该任务执行整个过程中产生的审计记录条数
            rows = await self.trajectory(task_id)
            # 如果审计记录条数未改变
            if len(rows) == last_len:
                stable += 1
                # 查询两次后都没变 认为任务已经执行完毕, 不会再产生新的审计
                if stable >= settle_polls and rows:
                    # 返回审计记录列表
                    return rows
            else:
                # 如果审计记录条数改变
                # 重置查询计数器
                stable = 0
                # 更新审计记录条数
                last_len = len(rows)
            # 查询一次后, 休息interval秒
            await asyncio.sleep(interval)

        # 轮询结束, 返回已经定型的审计记录列表
        return rows


# ----------------------------------------------------------------------
#  跑一个用例
# ----------------------------------------------------------------------


async def run_case(
    case: dict[str, Any],
    services: Services,
    judge_llm: Any,
    *,
    reflect: bool,
    max_reflections: int,
) -> CaseResult:
    # 获取用例id, 用户问题列表, 组装sessionId
    case_id = case["id"]
    turns: list[str] = case.get("turns") or [case["question"]]
    session_id = f"eval-{uuid.uuid4().hex[:8]}-{case_id}"

    started = time.perf_counter()
    last: dict[str, Any] = {}
    task: dict[str, Any] = {}
    try:
        # 遍历用户问题列表 调用create_task创建任务, 调用chat返回聊天结果
        for question in turns:
            task = await services.create_task(question=question, session_id=session_id)
            last = await services.chat(
                question=question,
                task=task,
                reflect=reflect,
                max_reflections=max_reflections,
            )
    # 出了异常 手动组装CaseResult封装异常信息
    except httpx.HTTPError as ex:
        body = getattr(getattr(ex, "response", None), "text", "")
        return CaseResult(
            case_id=case_id,
            question=turns[-1],
            answer="",
            error=f"{type(ex).__name__}: {ex} {body[:300]}",
        )
    # 计算耗时 单位: 毫秒
    latency_ms = int((time.perf_counter() - started) * 1000)
    # 记录最后一条答复内容
    answer = str(last.get("reply") or "")

    try:
        # 获取该任务最终的审计记录列表
        trajectory = await services.wait_for_trajectory(task["agentTaskId"])
    except httpx.HTTPError as ex:
        trajectory = []
        error = f"轨迹查询失败: {type(ex).__name__}: {ex}"
    else:
        error = "" if trajectory else "审计轨迹为空 —— MQ 消费端 (km-task-service) 是否在跑?"
    # 根据chat回复和审计记录表 生成检查言列表
    checks = deterministic_checks(case, answer, trajectory)

    # 质检员生成评估结果JudgeResult
    judge = None
    if judge_llm is not None:
        judge = await judge_case(
            judge_llm,
            case=case,
            question=turns[-1],
            answer=answer,
            trajectory=trajectory,
        )

    return CaseResult(
        case_id=case_id,
        question=turns[-1],
        answer=answer,
        checks=checks,
        judge=judge,
        stats=trajectory_stats(trajectory),
        tools=runtime_tools(trajectory),
        reflections=list(last.get("reflections") or []),
        latency_ms=latency_ms,
        error=error,
    )


# ----------------------------------------------------------------------
#  报告
# ----------------------------------------------------------------------


def print_case(result: CaseResult) -> None:
    det = "OK " if result.deterministic_ok else "FAIL"
    judge = "  - " if result.judge is None or result.judge.score is None else f"{result.judge.score:3d}"
    flag = "PASS" if result.passed else "FAIL"
    tools = ",".join(result.tools) or "-"
    print(
        f"  [{flag}] {result.case_id:<22} 断言={det} judge={judge} "
        f"工具={tools} ({len(result.tools)}次) {result.latency_ms or 0}ms"
    )
    if result.error:
        print(f"          ! {result.error}")
    for check in result.checks:
        if not check.ok:
            print(f"          - {check.name}: {check.detail}")
    if result.judge and result.judge.passed is False:
        print(f"          - judge: {result.judge.reason}")
    if result.reflections:
        rounds = " → ".join(
            f"{r.get('score')}{'✓' if r.get('passed') else '✗'}" for r in result.reflections
        )
        print(f"          ~ 反思: {rounds}")


def write_report(path: Path, payload: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    cases: list[dict[str, Any]] = []
    for line_no, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        try:
            cases.append(json.loads(line))
        except json.JSONDecodeError as ex:
            raise SystemExit(f"{path}:{line_no} 不是合法 JSON: {ex}") from ex
    return cases


async def main_async(args: argparse.Namespace) -> int:
    settings = get_settings()
    cases = load_jsonl(Path(args.dataset))
    if args.only:
        wanted = {item.strip() for item in args.only.split(",") if item.strip()}
        cases = [c for c in cases if c["id"] in wanted]
    if args.limit:
        cases = cases[: args.limit]
    if not cases:
        print("没有用例可跑")
        return 2

    judge_llm = None
    if not args.no_judge:
        if not settings.llm_api_key:
            print("没有 LLM_API_KEY, judge 无法工作; 用 --no-judge 跳过或配置 .env")
            return 2
        judge_llm = build_llm(settings, temperature=0.0)

    mode = "反思开启" if args.reflect else "基线 (不反思)"
    print(f"评测 {len(cases)} 条用例 · {mode} · 模型 {settings.llm_model}")
    print("=" * 78)

    services = Services(settings, args.agent_url)
    results: list[CaseResult] = []
    try:
        for case in cases:
            result = await run_case(
                case,
                services,
                judge_llm,
                reflect=args.reflect,
                max_reflections=args.max_reflections,
            )
            results.append(result)
            print_case(result)
    finally:
        await services.aclose()

    summary = summarize(results)
    baseline = None
    if args.baseline:
        baseline_path = Path(args.baseline)
        if baseline_path.exists():
            baseline = json.loads(baseline_path.read_text(encoding="utf-8")).get("summary")
        else:
            print(f"基线文件不存在, 跳过对比: {baseline_path}")
    delta = compare(baseline, summary)

    print("=" * 78)
    print(
        f"通过 {summary['passed']}/{summary['cases']} "
        f"({summary['passRate'] * 100:.0f}%) · judge 均分 {summary['judgeAvgScore']} · "
        f"平均工具调用 {summary['avgToolCalls']} 次"
    )
    print(
        f"降级 {summary['degradedCalls']} 次 · 错误 {summary['errors']} 次 · "
        f"token {summary['tokenIn']}→{summary['tokenOut']} · "
        f"反思轮次 {summary['reflectionRounds']} (其中不合格 {summary['reflectionRetries']})"
    )
    if summary["infrastructureErrors"]:
        print(f"★ 基础设施错误 {summary['infrastructureErrors']} 条 —— 这类失败不是 Agent 的问题")
    if delta:
        print(
            f"对比基线: 通过率 {delta['basePassRate'] * 100:.0f}% → "
            f"{summary['passRate'] * 100:.0f}% (Δ{delta['passRateDelta'] * 100:+.0f}pp), "
            f"judge 均分 Δ{delta['judgeScoreDelta']:+.1f}"
        )

    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "config": {
            "dataset": str(Path(args.dataset).name),
            "model": settings.llm_model,
            "reflect": args.reflect,
            "maxReflections": args.max_reflections,
            "judge": judge_llm is not None,
        },
        "summary": summary,
        "compare": delta,
        "cases": [r.as_dict() for r in results],
    }
    out = Path(args.out)
    write_report(out, payload)
    print(f"报告已写入 {out}")
    return 0 if summary["passed"] == summary["cases"] else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="Agent 轨迹评测")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--baseline", default=None, help="上一次的报告, 用于对比")
    parser.add_argument("--only", default=None, help="只跑这些 id, 逗号分隔")
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--reflect", action="store_true", help="打开反思闭环")
    parser.add_argument("--max-reflections", type=int, default=1)
    parser.add_argument("--no-judge", action="store_true", help="只跑确定性断言")
    parser.add_argument(
        "--agent-url",
        default=None,
        help="Python 运行时地址 (默认按配置拼)。对照实验时指向 8001 上的 weak 实例",
    )
    args = parser.parse_args()
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    raise SystemExit(main())
