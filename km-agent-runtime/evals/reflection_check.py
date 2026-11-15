"""反思闭环的**分量**评估: 缺陷注入 (mutation)。

## 为什么不做"弱提示词 vs 反思"的端到端对照

一开始是打算用 ``AGENT_PROMPT_VARIANT=weak`` 造一个会犯错的基线, 然后看
开反思后通过率涨多少。实跑结果 (见 docs/agent-evals.md) 是: 把系统提示词
削弱到"用户说的就是事实、不必调工具"的程度, deepseek-chat 依然 16/16。
提示词扰动的力度根本不够, 拿它当基线只能得到"反思没触发" —— 这不是反思
没用, 是**实验没有制造出可修复的缺陷**。

所以这里换个测法: 直接测组件本身。

* critic 能不能发现**已知有问题的**答复? (召回率 / 误报率)
* 发现之后走完整的"反馈 → 重答", 能不能把答案改回来? (修复率)

做法是 defect injection —— 软件测试里的 mutation testing:

1. 让 Agent 正常跑一遍, 拿到真实轨迹 (含工具返回) 和正确答复;
2. 在答复末尾注入一条**工具返回里根本没有**的业务事实 (编造), 或把答复
   截断 (不完整);
3. 把"真实轨迹 + 被污染的答复"交给 critic, 看它判不判不合格;
4. 判了不合格就调 ``reflect_loop``, 用同一个 Agent 带着反馈重答, 再用
   确定性断言 + judge 检查重答是否恢复正确。

## 这一步的量级

每条用例 1 次 Agent 执行 + 3 次 critic + 最多 2 次重答。串行跑, 十几条
用例几分钟。它不是 CI 的一部分 (要真模型), 由人手动跑, 出报告。

## 为什么不在 HTTP 层做

``/chat`` 没有"给我一条脏草稿"的入口 —— 也不该有, 生产 API 不该为评测开洞。
这里直接调 ``build_agent``: 和 ``app/main.py`` 用的是同一个装配函数、同一套
MCP 适配、同一份提示词, 区别只是不落 Java 任务与审计。评测的是 critic 和
重答路径, 不是审计链路 (那条链路由 runner.py 端到端覆盖)。
"""

from __future__ import annotations

import argparse
import asyncio
import json
import sys
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from app.config import get_settings
from app.graph.agent import build_agent, build_llm
from app.mcp_adapter import TaskIdentity, build_tool_provider, task_identity
from app.reflection import critique_answer, reflect_loop
from evals.runner import load_jsonl
from evals.scorers import (
    deterministic_checks,
    judge_case,
    runtime_tools,
    trajectory_stats,
)

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

DEFAULT_DATASET = Path(__file__).with_name("dataset.jsonl")
DEFAULT_OUT = Path(__file__).parent / "results" / "reflection_check.json"

# 注入的"事实"必须满足两个条件: 业务上很合理 (模型/质检员不会一眼觉得荒谬),
# 但后端从来没返回过。后端只有订单表, 没有任何物流时间与运单号。
FAKE_FACT = "另外, 物流信息显示预计明天下午 3 点前送达, 快递单号 SF1234567890。"
FAKE_MARKERS = ("SF1234567890", "明天下午")

# 答复截断: 模拟"模型输出被 max_tokens / 超时截断"这一类很常见的生产故障。
TRUNCATE_AT = 8


# ----------------------------------------------------------------------
#  从消息历史还原"轨迹"
# ----------------------------------------------------------------------

# 从消息内容中提取文本 text/content 字段内容
def _content_text(content: Any) -> str:
    if isinstance(content, str):
        return content
    if isinstance(content, list):
        parts = []
        for block in content:
            if isinstance(block, dict):
                parts.append(str(block.get("text") or block.get("content") or block))
            else:
                parts.append(str(block))
        return "\n".join(parts)
    return str(content)

# 从消息历史中提取工具调用信息
def trajectory_from_messages(messages: list[Any]) -> list[dict]:
    """把 LangGraph 的消息历史还原成和审计行同构的轨迹。

    一次工具调用产生两行: 运行时层的"我请求了什么"、网关层的"后端返回了
    什么"。这样 ``runtime_tools`` / ``evidence_text`` / scorer 全都能直接复用,
    不必为离线评测再写一套解析。
    """
    rows: list[dict] = []
    for message in messages:
        if isinstance(message, AIMessage):
            # 提取AIMessage中的工具调用信息
            for call in getattr(message, "tool_calls", None) or []:
                name = call.get("name") if isinstance(call, dict) else getattr(call, "name", "?")
                args = call.get("args") if isinstance(call, dict) else getattr(call, "args", {})
                # 构造一条审计记录
                rows.append({
                    "layer": "AGENT_RUNTIME",
                    "eventType": "MCP_TOOL_CALL",
                    "status": "OK",
                    "requestBody": json.dumps({"tool": name, "params": args}, ensure_ascii=False),
                })
        # 提取ToolMessage中的工具调用信息
        elif isinstance(message, ToolMessage):
            rows.append({
                "layer": "MCP_GATEWAY",
                "eventType": "MCP_TOOL_CALL",
                "status": "OK",
                "requestBody": json.dumps({"tool": message.name, "params": {}}, ensure_ascii=False),
                "responseBody": _content_text(message.content),
            })
    return rows


# ----------------------------------------------------------------------
#  缺陷注入
# ----------------------------------------------------------------------


def inject_fake_fact(answer: str) -> str:
    return f"{answer.rstrip()}\n{FAKE_FACT}"


def inject_truncation(answer: str) -> str:
    return answer.strip()[:TRUNCATE_AT]


# ----------------------------------------------------------------------
#  跑一条用例
# ----------------------------------------------------------------------
async def run_case(agent: Any, critic: Any, case: dict[str, Any], *, max_revisions: int) -> dict:
    case_id = case["id"]
    question = (case.get("turns") or [case["question"]])[-1]
    identity = TaskIdentity(task_id=f"eval-reflect-{uuid.uuid4().hex[:12]}", tenant_id="eval", actor="eval-runner")

    # 1) 正常跑一遍, 拿真实轨迹和正确答复。
    with task_identity(identity):
        result = await agent.ainvoke(
            {"messages": [HumanMessage(content=question)]},
            config={"configurable": {"thread_id": f"eval-{uuid.uuid4().hex[:8]}"}},
        )
    messages = list(result.get("messages") or [])
    # 取出最后一条消息
    clean_answer = str(messages[-1].content) if messages else ""
    # 从消息历史汇聚出工具调用审计记录
    trajectory = trajectory_from_messages(messages)
    # 从工具调用审计记录汇聚出工具列表
    tools = runtime_tools(trajectory)
    # 检查言
    clean_checks = deterministic_checks(case, clean_answer, trajectory)
    # 分数评估
    clean_judge = await judge_case(
        critic, case=case, question=question, answer=clean_answer, trajectory=trajectory
    )

    record: dict[str, Any] = {
        "caseId": case_id,
        "question": question,
        "cleanAnswer": clean_answer,
        "cleanDeterministicOk": bool(clean_checks) and all(c.ok for c in clean_checks),
        "cleanJudge": clean_judge.as_dict(),
        "tools": tools,
        "trajectory": trajectory_stats(trajectory),
    }

    # 2) 干净答复先给 critic 看一次 —— 这是误报率的分母。
    # 质检员进行质检
    clean_critique = await critique_answer(critic, question, messages)
    # 构造质检报告
    record["cleanCritique"] = clean_critique.as_report(0)

    # 3) 注入编造事实: critic 该判不合格。判了就修。
    fake_answer = inject_fake_fact(clean_answer)
    # 构造一条AIMessage, 含有编造事实的答复
    fake_messages = messages[:-1] + [AIMessage(content=fake_answer)]
    # 质检员进行质检
    fake_critique = await critique_answer(critic, question, fake_messages)
    # 是否检测出来
    fake_detected = not fake_critique.passed
    # 生成打假记录
    fake_record: dict[str, Any] = {
        "answer": fake_answer,
        "detected": fake_detected,
        "critique": fake_critique.as_report(0),
        "repaired": None,
    }
    # 打假成功
    if fake_detected:
        # 4) 带反馈payload重答。invoke 的形态和 main.py 里完全一致:
        #    payload 只有新消息, 历史由调用方拼上。
        async def _invoke(payload: dict[str, Any]) -> dict[str, Any]:
            with task_identity(identity):
                return await agent.ainvoke(
                    {"messages": [*fake_messages, *payload["messages"]]},
                    config={"configurable": {"thread_id": f"eval-{uuid.uuid4().hex[:8]}"}},
                )

        # 轮询质检, 直到答复满意
        outcome = await reflect_loop(
            question=question,
            messages=fake_messages,
            llm=critic,
            # 这里的 _invoke 能够拼接反馈消息, 不是ainvoke()!
            invoke=_invoke,
            max_revisions=max_revisions,
        )
        # 修正之后的消息列表
        repaired_messages = outcome.messages
        # 最后一条消息
        repaired_answer = str(repaired_messages[-1].content) if repaired_messages else ""
        # 从消息历史汇聚出工具调用审计记录
        repaired_traj = trajectory_from_messages(repaired_messages)
        # 检查言
        repaired_checks = deterministic_checks(case, repaired_answer, repaired_traj)
        # 质检员进行分数评估
        repaired_judge = await judge_case(
            critic, case=case, question=question, answer=repaired_answer, trajectory=repaired_traj
        )
        # 生成修复记录
        fake_record["repaired"] = {
            "answer": repaired_answer,
            "stillHasFakeFact": any(marker in repaired_answer for marker in FAKE_MARKERS),
            "deterministicOk": bool(repaired_checks) and all(c.ok for c in repaired_checks),
            "judge": repaired_judge.as_dict(),
            "critiques": [c.as_report(i + 1) for i, c in enumerate(outcome.critiques)],
        }
    # 生成打假记录
    record["injectedFakeFact"] = fake_record

    # 5) 截断: 只测检出, 不测修复 (截断属于输出通道故障, 不是内容缺陷)。
    truncated_messages = messages[:-1] + [AIMessage(content=inject_truncation(clean_answer))]
    trunc_critique = await critique_answer(critic, question, truncated_messages)
    record["injectedTruncation"] = {
        "answer": inject_truncation(clean_answer),
        "detected": not trunc_critique.passed,
        "critique": trunc_critique.as_report(0),
    }

    return record


# ----------------------------------------------------------------------
#  汇总 / 输出
# ----------------------------------------------------------------------
def summarize(records: list[dict[str, Any]]) -> dict[str, Any]:
    total = len(records)
    clean_pass = sum(1 for r in records if r["cleanCritique"]["passed"])
    fake_detected = sum(1 for r in records if r["injectedFakeFact"]["detected"])
    trunc_detected = sum(1 for r in records if r["injectedTruncation"]["detected"])
    repaired_ok = 0
    for r in records:
        repaired = r["injectedFakeFact"].get("repaired")
        if not repaired:
            continue
        if (
            not repaired["stillHasFakeFact"]
            and repaired["deterministicOk"]
            and (repaired["judge"].get("passed") is not False)
        ):
            repaired_ok += 1
    return {
        "cases": total,
        "cleanCriticPass": clean_pass,
        "cleanCriticFalsePositive": total - clean_pass,
        "fakeDetected": fake_detected,
        "fakeRecall": round(fake_detected / total, 4) if total else 0.0,
        "truncationDetected": trunc_detected,
        "truncationRecall": round(trunc_detected / total, 4) if total else 0.0,
        "repairedOk": repaired_ok,
        "repairRate": round(repaired_ok / fake_detected, 4) if fake_detected else None,
    }


def print_record(record: dict[str, Any]) -> None:
    clean_mark = "通过" if record["cleanCritique"]["passed"] else "误报"
    fake = record["injectedFakeFact"]
    det = "检出" if fake["detected"] else "漏检"
    repaired = fake.get("repaired")
    if repaired is None:
        fix = "未触发重答"
    elif repaired["stillHasFakeFact"]:
        fix = "仍编造"
    elif repaired["deterministicOk"] and repaired["judge"].get("passed") is not False:
        fix = "已修复"
    else:
        fix = "有修改但未完全通过"
    trunc = "检出" if record["injectedTruncation"]["detected"] else "漏检"
    print(f"  {record['caseId']:<22} 干净={clean_mark:<3} 编造={det:<3}/{fix:<12} 截断={trunc}")


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

    provider = build_tool_provider(settings)
    agent = await build_agent(provider, settings)  # memory checkpointer: 离线不落库
    critic = build_llm(settings, temperature=0.0)

    print(f"反思闭环分量评估 · {len(cases)} 条用例 · 模型 {settings.llm_model}")
    print("=" * 78)

    records: list[dict[str, Any]] = []
    for case in cases:
        try:
            record = await run_case(agent, critic, case, max_revisions=args.max_reflections)
        except Exception as ex:  # noqa: BLE001 —— 单条用例失败不该终止整轮
            record = {
                "caseId": case["id"],
                "error": f"{type(ex).__name__}: {ex}",
                "cleanCritique": {"passed": False},
                "injectedFakeFact": {"detected": False},
                "injectedTruncation": {"detected": False},
            }
        records.append(record)
        if record.get("error"):
            print(f"  {record['caseId']:<22} ! {record['error']}")
        else:
            print_record(record)

    summary = summarize(records)
    print("=" * 78)
    print(
        f"干净答复: critic 通过 {summary['cleanCriticPass']}/{summary['cases']} "
        f"(误报 {summary['cleanCriticFalsePositive']})"
    )
    print(
        f"编造事实: 检出 {summary['fakeDetected']}/{summary['cases']} "
        f"({summary['fakeRecall'] * 100:.0f}%) · 修复 {summary['repairedOk']}"
        f"{'/' + str(summary['fakeDetected']) if summary['fakeDetected'] else ''}"
        f"{'' if summary['repairRate'] is None else f' ({summary['repairRate'] * 100:.0f}%)'}"
    )
    print(
        f"答复截断: 检出 {summary['truncationDetected']}/{summary['cases']} "
        f"({summary['truncationRecall'] * 100:.0f}%)"
    )

    payload = {
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "config": {"dataset": Path(args.dataset).name, "model": settings.llm_model,
                   "maxReflections": args.max_reflections},
        "summary": summary,
        "cases": records,
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"报告已写入 {out}")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="反思闭环缺陷注入评估")
    parser.add_argument("--dataset", default=str(DEFAULT_DATASET))
    parser.add_argument("--out", default=str(DEFAULT_OUT))
    parser.add_argument("--only", default=None)
    parser.add_argument("--limit", type=int, default=None)
    parser.add_argument("--max-reflections", type=int, default=1)
    args = parser.parse_args()
    # 和 app/checkpoint.py 的选择一致: psycopg 的异步连接不能跑在
    # ProactorEventLoop 上。这里虽然用内存后端, 但 Agent 的工具调用走的是
    # 同一套 asyncio + httpx 栈, 统一用 Selector 省得两套环境行为不一。
    if sys.platform == "win32":
        asyncio.set_event_loop_policy(asyncio.WindowsSelectorEventLoopPolicy())
    return asyncio.run(main_async(args))


if __name__ == "__main__":
    raise SystemExit(main())
