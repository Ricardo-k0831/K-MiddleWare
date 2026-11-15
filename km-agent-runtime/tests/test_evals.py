"""评测打分器的单测。

配套代码: evals/scorers.py。

评测工具本身也必须有测试 —— 否则一个写错的关键词断言会让 Agent 永远"不通过",
而排查方向会全部指向模型。这里用假的轨迹行, 不碰网络、不碰数据库。
"""

from __future__ import annotations

import asyncio
import json

from langchain_core.messages import AIMessage, ToolMessage

from evals.reflection_check import (
    inject_fake_fact,
    inject_truncation,
    trajectory_from_messages,
)
from evals.scorers import (
    CaseResult,
    Check,
    deterministic_checks,
    evidence_text,
    judge_case,
    runtime_tools,
    summarize,
    trajectory_stats,
)


def _row(layer: str, event_type: str, *, tool: str | None = None, status: str = "OK",
         request: dict | None = None, response=None, error: str | None = None,
         token_in: int = 0, token_out: int = 0) -> dict:
    body = request if request is not None else ({"tool": tool, "params": {}} if tool else None)
    return {
        "layer": layer,
        "eventType": event_type,
        "agentTaskId": "task-1",
        "status": status,
        "requestBody": json.dumps(body, ensure_ascii=False) if body else None,
        "responseBody": json.dumps(response, ensure_ascii=False) if isinstance(response, (dict, list)) else response,
        "errorMsg": error,
        "tokenIn": token_in,
        "tokenOut": token_out,
    }


TRAJ = [
    _row("AGENT_RUNTIME", "LLM_CALL", token_in=100, token_out=20),
    _row("AGENT_RUNTIME", "MCP_TOOL_CALL", tool="get_order_by_id",
         request={"tool": "get_order_by_id", "params": {"orderId": "SO1"}}),
    _row("MCP_GATEWAY", "MCP_TOOL_CALL", tool="get_order_by_id",
         request={"tool": "get_order_by_id", "params": {"orderId": "SO1"}},
         response={"orderId": "SO1", "status": "PAID"}),
    _row("LEGACY_SERVICE", "MCP_TOOL_CALL", status="OK"),
    _row("TASK_SERVICE", "TASK_LIFECYCLE"),
]


# ----------------------------------------------------------------------
#  轨迹解析
# ----------------------------------------------------------------------


def test_工具序列取运行时的选择():
    assert runtime_tools(TRAJ) == ["get_order_by_id"]


def test_统计里四层分开计数():
    stats = trajectory_stats(TRAJ)

    assert stats["llmCalls"] == 1
    assert stats["runtimeToolCalls"] == 1
    assert stats["gatewayToolCalls"] == 1
    assert stats["legacyCalls"] == 1
    assert stats["tokenIn"] == 100


def test_降级调用单独计数():
    rows = TRAJ + [_row("MCP_GATEWAY", "MCP_TOOL_CALL", tool="get_order_by_id", status="DEGRADED")]
    assert trajectory_stats(rows)["degraded"] == 1
    # 被挡掉的调用不该到达后端 —— 这是限流/熔断的核心语义, 也在这里被钉住
    assert trajectory_stats(rows)["legacyCalls"] == 1


def test_给judge的证据里有两层和参数():
    evidence = evidence_text(TRAJ)

    assert "SO1" in evidence
    assert "PAID" in evidence, "后端返回没进证据, judge 判断不了编造"
    assert "MCP_GATEWAY" in evidence
    assert "LEGACY_SERVICE" in evidence


def test_非法JSON的轨迹行不会让评分崩溃():
    assert runtime_tools([{"layer": "AGENT_RUNTIME", "eventType": "MCP_TOOL_CALL",
                           "requestBody": "not json"}]) == []


# ----------------------------------------------------------------------
#  确定性断言
# ----------------------------------------------------------------------


CASE = {
    "id": "demo",
    "expect": {
        "answer_contains": [["机械键盘"], ["已支付", "PAID"]],
        "answer_not_contains": ["不存在"],
        "tools_required": ["get_order_by_id"],
        "tools_forbidden": ["list_orders_by_user"],
        "max_tool_calls": 2,
    },
}


def test_全部满足时通过():
    checks = deterministic_checks(CASE, "订单是机械键盘, 状态已支付", TRAJ)
    assert all(c.ok for c in checks), [c.as_dict() for c in checks]


def test_同义词组命中任意一个即可():
    # "已支付" 和 "PAID" 是同一组的两种说法, 命中任一个就算过 ——
    # 中文答复的措辞不该被一个写死的字符串卡死。
    checks = deterministic_checks(CASE, "机械键盘, 状态 PAID", TRAJ)
    assert all(c.ok for c in checks), [c.as_dict() for c in checks]


def test_缺少关键词会被指出来():
    checks = deterministic_checks(CASE, "已经支付了", TRAJ)
    missing = [c for c in checks if not c.ok]
    assert missing, "漏了商品名却没报出来"
    assert any("机械键盘" in c.name for c in missing)


def test_工具选错会被指出来():
    bad = [_row("AGENT_RUNTIME", "MCP_TOOL_CALL", tool="list_orders_by_user")]
    checks = deterministic_checks(CASE, "机械键盘 已支付", bad)
    assert any(not c.ok and "get_order_by_id" in c.name for c in checks)
    assert any(not c.ok and "list_orders_by_user" in c.name for c in checks)


def test_调用次数超限会被指出来():
    many = [_row("AGENT_RUNTIME", "MCP_TOOL_CALL", tool="get_order_by_id") for _ in range(3)]
    checks = deterministic_checks(CASE, "机械键盘 已支付", many)
    assert any(not c.ok and "不超过 2 次" in c.name for c in checks)


def test_没声明的维度不产生断言():
    checks = deterministic_checks({"id": "x", "expect": {}}, "随便", TRAJ)
    assert checks == []


def test_工具入参断言能区分用哪个订单号查询():
    """长期记忆评测的关键: 调了工具不算赢, 用记忆里的订单号调才算。"""
    traj = [
        _row(
            "AGENT_RUNTIME",
            "MCP_TOOL_CALL",
            tool="get_order_by_id",
            request={"tool": "get_order_by_id", "params": {"orderId": "SO20260901002"}},
        )
    ]
    case = {
        "id": "mem",
        "expect": {
            "tool_args_contain": ["SO20260901002"],
            "tool_args_not_contain": ["SO20260901001"],
        },
    }

    checks = deterministic_checks(case, "", traj)

    assert len(checks) == 2
    assert all(check.ok for check in checks)


def test_用错订单号时入参断言失败并给出实际值():
    traj = [
        _row(
            "AGENT_RUNTIME",
            "MCP_TOOL_CALL",
            tool="get_order_by_id",
            request={"tool": "get_order_by_id", "params": {"orderId": "SO20260901001"}},
        )
    ]
    case = {"id": "mem", "expect": {"tool_args_not_contain": ["SO20260901001"]}}

    checks = deterministic_checks(case, "", traj)

    assert [check.ok for check in checks] == [False]
    assert "SO20260901001" in checks[0].detail


# ----------------------------------------------------------------------
#  judge
# ----------------------------------------------------------------------


class FakeJudge:
    def __init__(self, reply: str) -> None:
        self.reply = reply
        self.prompts: list = []

    async def ainvoke(self, messages):
        self.prompts.append(messages)
        return AIMessage(content=self.reply)


def test_judge能解析通过结论():
    llm = FakeJudge('{"passed": true, "score": 88, "reason": "事实都有出处"}')
    result = asyncio.run(judge_case(llm, case=CASE, question="q", answer="机械键盘 已支付", trajectory=TRAJ))

    assert result.passed is True
    assert result.score == 88
    assert result.reason == "事实都有出处"


def test_judge输出坏了不算不合格():
    llm = FakeJudge("我觉得还行")
    result = asyncio.run(judge_case(llm, case=CASE, question="q", answer="a", trajectory=TRAJ))

    assert result.passed is None, "judge 自己坏了不能判用户不合格"
    assert result.error


def test_judge能看到后端证据():
    llm = FakeJudge('{"passed": true, "score": 90, "reason": "ok"}')
    asyncio.run(judge_case(llm, case=CASE, question="q", answer="a", trajectory=TRAJ))

    prompt = " ".join(str(m.content) for m in llm.prompts[0])
    assert "PAID" in prompt


# ----------------------------------------------------------------------
#  缺陷注入评估 (evals/reflection_check.py)
# ----------------------------------------------------------------------


def test_离线轨迹能还原运行时与网关两层():
    messages = [
        AIMessage(
            content="",
            tool_calls=[{"name": "get_order_by_id", "args": {"orderId": "SO1"}, "id": "c1"}],
        ),
        ToolMessage(content='{"orderId":"SO1","status":"PAID"}', name="get_order_by_id",
                    tool_call_id="c1"),
        AIMessage(content="订单已支付"),
    ]
    trajectory = trajectory_from_messages(messages)

    assert runtime_tools(trajectory) == ["get_order_by_id"]
    # 证据必须来自工具返回, 而不是助手自己的话 —— 否则 judge 判不出编造。
    assert "PAID" in evidence_text(trajectory)


def test_编造注入保留原答复并追加不存在的物流信息():
    injected = inject_fake_fact("订单 SO1 已支付。")

    assert "订单 SO1 已支付。" in injected
    assert "SF1234567890" in injected


def test_截断注入只保留前缀():
    answer = "订单 SO20260901001 的状态是已支付"
    assert inject_truncation(answer) == answer[:8]


# ----------------------------------------------------------------------
#  汇总
# ----------------------------------------------------------------------


def _result(case_id: str, *, passed: bool, score: int | None, error: str = "") -> CaseResult:
    judge = None
    if score is not None:
        from evals.scorers import JudgeResult
        judge = JudgeResult(passed=passed, score=score, reason="")
    return CaseResult(
        case_id=case_id,
        question="q",
        answer="a",
        checks=[Check(name="c", ok=passed)],
        judge=judge,
        stats={"degraded": 0, "errors": 0, "tokenIn": 10, "tokenOut": 2},
        tools=["t"],
        error=error,
    )


def test_基础设施错误一律不通过():
    result = _result("x", passed=True, score=100, error="审计轨迹为空")
    assert result.passed is False


def test_judge没评出来时以断言为准():
    result = _result("x", passed=True, score=None)
    assert result.passed is True


def test_汇总统计通过率与反思轮次():
    results = [
        _result("a", passed=True, score=90),
        _result("b", passed=False, score=40),
    ]
    results[0].reflections = [{"passed": False}, {"passed": True}]

    summary = summarize(results)

    assert summary["cases"] == 2
    assert summary["passed"] == 1
    assert summary["passRate"] == 0.5
    assert summary["judgeAvgScore"] == 65.0
    assert summary["reflectionRounds"] == 2
    assert summary["reflectionRetries"] == 1
