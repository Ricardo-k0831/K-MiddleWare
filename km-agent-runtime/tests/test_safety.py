"""提示注入防护的单测。

配套代码: app/safety.py。

这里只测"模型看到的消息视图"这一层: 边界有没有加上、会不会重复加、
原消息有没有被悄悄改掉。真的注入能不能扛住, 由 evals/injection.jsonl 端到端量。
"""

from __future__ import annotations

import logging

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from app.safety import (
    DEFENSE_OFF,
    DEFENSE_PROMPT,
    DEFENSE_SPOTLIGHT,
    FENCE_CLOSE,
    FENCE_OPEN,
    fence_tool_output,
    resolve_defense,
    sanitize_messages,
    spotlight_hook,
    system_prompt_suffix,
)


def _tool(content="{\"status\":\"PAID\"}") -> ToolMessage:
    return ToolMessage(content=content, name="get_order_by_id", tool_call_id="c1")


def test_工具返回被框起来并带安全提示():
    fenced = fence_tool_output("{\"status\":\"PAID\"}")

    assert fenced.startswith("[安全提示]")
    assert FENCE_OPEN in fenced
    assert FENCE_CLOSE in fenced
    assert "PAID" in fenced


def test_重复套边界不会叠加():
    once = fence_tool_output("订单已支付")
    assert fence_tool_output(once) == once


def test_content_block列表被拼成文本():
    fenced = fence_tool_output([{"type": "text", "text": "第一段"}, {"type": "text", "text": "第二段"}])

    assert "第一段" in fenced
    assert "第二段" in fenced


def test_只改工具消息且不改原对象():
    tool = _tool()
    human = HumanMessage(content="查一下订单")
    original_content = tool.content

    view = sanitize_messages([human, tool])

    assert view[0] is human, "用户消息不该被重建"
    assert view[1] is not tool
    assert FENCE_OPEN in view[1].content
    assert tool.content == original_content, "原消息被就地改了 —— 审计和 checkpoint 会跟着变"


def test_未知防护级别往严的方向回落(caplog):
    with caplog.at_level(logging.WARNING):
        assert resolve_defense("spotlgiht") == DEFENSE_SPOTLIGHT
    assert "injection_defense" in caplog.text


def test_防护级别对应提示词后缀():
    assert system_prompt_suffix(DEFENSE_OFF) == ""
    assert "外部数据" in system_prompt_suffix(DEFENSE_PROMPT)
    assert "外部数据" in system_prompt_suffix(DEFENSE_SPOTLIGHT)


def test_pre_model_hook只产出模型视图():
    state = {"messages": [HumanMessage(content="q"), AIMessage(content=""), _tool()]}

    update = spotlight_hook(state)

    assert set(update) == {"llm_input_messages"}
    assert len(update["llm_input_messages"]) == 3
    assert FENCE_OPEN in update["llm_input_messages"][2].content
    assert FENCE_OPEN not in state["messages"][2].content, "state 里的原文不该被改"
