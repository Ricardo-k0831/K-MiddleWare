"""反思闭环 (critic + 重答) 的单测。

配套代码: app/reflection.py。

## 这里验的是"闭环真的闭上了"没有

反思最容易被写成一段看着很对、实际什么都不做的代码:

* 质检永远返回 passed → 重答分支从没被执行过;
* 重答了, 但反馈没带进模型 (payload 是空的) → 只是把同一个问题又问了一遍;
* 上限写反了 → 无限重答, 或者一次都不重答。

所以断言全部打在**假模型实际收到的消息**和**调用次数**上, 不看日志、不看分数。
分数是给报告看的, 不是给控制流看的。
"""

from __future__ import annotations

import json

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from app.reflection import (
    Critique,
    CritiqueParseError,
    build_feedback,
    critique_answer,
    parse_critique,
    reflect_loop,
    render_transcript,
)


class FakeCritic:
    """假的质检模型: 按顺序吐出预设的 JSON。"""

    def __init__(self, *replies: str) -> None:
        self.replies = list(replies)
        self.calls: list[list] = []
        self.configs: list[dict | None] = []

    async def ainvoke(self, messages, config=None):
        self.calls.append(list(messages))
        self.configs.append(config)
        text = self.replies[0] if len(self.replies) == 1 else self.replies.pop(0)
        return AIMessage(content=text)


def _pass(score: int = 95) -> str:
    return json.dumps({"passed": True, "score": score, "issues": [], "fix_hint": ""})


def _fail(*issues: str, score: int = 40, hint: str = "只使用工具返回过的事实") -> str:
    return json.dumps(
        {"passed": False, "score": score, "issues": list(issues), "fix_hint": hint}
    )


class FakeInvoke:
    """假的"重跑一次图"。记录每次收到的 payload, 返回一份新的消息列表。"""

    def __init__(self, *rounds: list) -> None:
        self.rounds = list(rounds)
        self.payloads: list = []

    async def __call__(self, payload):
        self.payloads.append(payload)
        messages = self.rounds.pop(0) if len(self.rounds) > 1 else self.rounds[0]
        return {"messages": list(messages)}


BASE = [
    HumanMessage(content="SO20260901001 什么时候到?"),
    AIMessage(
        content="",
        tool_calls=[{"name": "get_order_by_id", "args": {"orderId": "SO20260901001"}, "id": "c1"}],
    ),
    ToolMessage(content='{"orderId":"SO20260901001","status":"PAID"}', tool_call_id="c1", name="get_order_by_id"),
    AIMessage(content="预计明天送达"),
]
REVISED = BASE + [
    HumanMessage(content="[系统内部自检反馈]", additional_kwargs={"km_reflection": 1}),
    AIMessage(content="订单已支付, 系统里没有预计送达时间"),
]


# ----------------------------------------------------------------------
#  解析: 模型的输出永远不能全信
# ----------------------------------------------------------------------


def test_能解析带json围栏和前后废话的输出():
    text = '好的, 我的判断如下:\n```json\n{"passed": false, "score": 30, "issues": ["编造了时间"], "fix_hint": "删掉时间"}\n```\n希望有帮助。'
    critique = parse_critique(text)

    assert critique.passed is False
    assert critique.score == 30
    assert critique.issues == ("编造了时间",)
    assert critique.fix_hint == "删掉时间"


def test_分数越界会被夹到0到100():
    assert parse_critique('{"passed": true, "score": 250}').score == 100
    assert parse_critique('{"passed": true, "score": -5}').score == 0


@pytest.mark.parametrize(
    "text",
    [
        "没有 JSON",
        '{"score": 80}',                       # 缺 passed
        '{"passed": "true", "score": 80}',     # 类型不对 (字符串不是布尔)
        '{"passed": true, "score": "高"}',      # 分数不是数字
        '{"passed": true, "score": 80, "issues": "编造"}',  # issues 不是数组
    ],
)
def test_不合法的输出会抛解析错误(text: str):
    with pytest.raises(CritiqueParseError):
        parse_critique(text)


# ----------------------------------------------------------------------
#  轨迹渲染: 编造类错误只有在"工具说了什么"和"回答说了什么"并排时才看得出来
# ----------------------------------------------------------------------


def test_轨迹里同时有工具入参和工具返回():
    transcript = render_transcript(BASE)

    assert "SO20260901001" in transcript
    assert "get_order_by_id" in transcript
    assert "PAID" in transcript, "工具返回没进轨迹 —— 质检员没法判断答复是否编造"
    assert "预计明天送达" in transcript


def test_轨迹会标注自检反馈而不是伪装成用户提问():
    transcript = render_transcript(REVISED)
    assert "[自检反馈]" in transcript


def test_超长工具结果会被截断():
    huge = [ToolMessage(content="x" * 5000, tool_call_id="c1", name="get_order_by_id")]
    transcript = render_transcript(huge)
    assert len(transcript) < 2000
    assert "已截断" in transcript


# ----------------------------------------------------------------------
#  闭环: 通过就不重答, 不通过才重答, 且反馈真的带进去了
# ----------------------------------------------------------------------


def test_质检通过时不重答():
    import asyncio

    critic = FakeCritic(_pass())
    invoke = FakeInvoke(REVISED)

    async def _go():
        return await reflect_loop(
            question="什么时候到", messages=BASE, llm=critic,
            invoke=invoke, max_revisions=2,
        )

    outcome = asyncio.run(_go())

    assert invoke.payloads == [], "质检通过了还重答, 等于每次都多花一倍钱"
    assert len(outcome.critiques) == 1
    assert outcome.critiques[0].passed
    assert outcome.messages == BASE


def test_不合格时带着具体问题重答():
    import asyncio

    critic = FakeCritic(_fail("编造了送达时间"), _pass())
    invoke = FakeInvoke(REVISED)

    async def _go():
        return await reflect_loop(
            question="什么时候到", messages=BASE, llm=critic,
            invoke=invoke, max_revisions=1,
        )

    outcome = asyncio.run(_go())

    assert len(invoke.payloads) == 1, "不合格却没重答"
    feedback = invoke.payloads[0]["messages"][0]
    assert isinstance(feedback, HumanMessage)
    assert "编造了送达时间" in feedback.content, "问题没带进反馈 —— 模型只会再猜一遍"
    assert "只使用工具实际返回过的信息" in feedback.content
    assert feedback.additional_kwargs["km_reflection"] == 1

    assert [c.passed for c in outcome.critiques] == [False, True]
    assert outcome.messages == REVISED


def test_重答次数有上限():
    import asyncio

    critic = FakeCritic(_fail("还是不对"))
    invoke = FakeInvoke(REVISED)

    async def _go():
        return await reflect_loop(
            question="什么时候到", messages=BASE, llm=critic,
            invoke=invoke, max_revisions=2,
        )

    outcome = asyncio.run(_go())

    # 2 次重答 → 3 次质检 (最后一次是终检), 不会无限循环
    assert len(invoke.payloads) == 2
    assert len(outcome.critiques) == 3
    assert not outcome.critiques[-1].passed


def test_质检失败不影响原答复():
    import asyncio

    class BrokenCritic:
        async def ainvoke(self, messages, config=None):
            raise RuntimeError("judge 挂了")

    invoke = FakeInvoke(REVISED)

    async def _go():
        return await reflect_loop(
            question="什么时候到", messages=BASE, llm=BrokenCritic(),
            invoke=invoke, max_revisions=3,
        )

    outcome = asyncio.run(_go())

    # 质检是增益而不是依赖: 它自己坏了, 用户仍然拿到原来的答复。
    assert outcome.critiques == []
    assert outcome.messages == BASE
    assert invoke.payloads == []


def test_反馈被标记为内部消息且要求不要提及自检():
    feedback = build_feedback(Critique(passed=False, score=20, issues=("a", "b"), fix_hint="h"))

    assert "系统内部自检反馈" in feedback
    assert "不要提及自检" in feedback
    assert "1. " in feedback and "不要推测" in feedback


def test_质检调用会带上callbacks_config():
    """质检也要进审计 —— 不传 config 的话, 打分的 token 花在账外。"""
    import asyncio

    critic = FakeCritic(_pass())
    config = {"callbacks": ["sentinel"]}

    async def _go():
        return await critique_answer(critic, "q", BASE, config=config)

    asyncio.run(_go())
    assert critic.configs == [config]
