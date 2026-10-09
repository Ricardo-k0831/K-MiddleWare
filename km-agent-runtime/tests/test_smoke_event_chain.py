"""smoke_chat.check_event_chain 的单测 —— 事件表结构不变量。

## 为什么要单独测这个纯函数

用例 T 的其他断言 (task_id/thread_id 一致、工具轨迹、终态) 在
tests/test_chat_task_wiring.py 里已经覆盖了, 而 check_event_chain 是
只在端到端跑得起来时才会执行的一段逻辑 —— 也就是说, 它错不错平时看不见,
只有跑一次完整链路才知道。而跑一次要四个服务 + 若干次 LLM 调用。

它是纯函数, 所以这里直接喂构造好的事件序列, 毫秒级验完。

## 这个文件最想钉死的一件事

**"fromStatus 不能重复" 是错的断言。**

我写用例 T 时第一反应就是加它, 因为 km-control-plane 那边的
verify_task_service.py 里有一条一模一样的。但两者不是一回事:

  * Java 那边: 多个请求【并发抢同一个状态】, 重复 = CAS 失效
  * 这里: 一条【时间线】, ReAct 循环本来就会回到 THINKING,
          THINKING 作为 fromStatus 天然出现两次以上

把 Java 那条照搬过来, 用例 T 会永远失败 —— 而且失败得很有说服力,
让人以为是状态机坏了。test_合法的ReAct循环里THINKING重复出现_不应报错
就是为了防止有人再把它加回去。
"""

from __future__ import annotations

import importlib.util
from pathlib import Path

import pytest

SCRIPT = Path(__file__).resolve().parents[1] / "scripts" / "smoke_chat.py"

spec = importlib.util.spec_from_file_location("smoke_chat", SCRIPT)
assert spec and spec.loader
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)

check_event_chain = smoke.check_event_chain


def ev(frm: str | None, to: str, source: str = "PYTHON_RUNTIME") -> dict:
    return {"fromStatus": frm, "toStatus": to, "source": source}


# 真实跑出来的一条完整流转 (2026-10-09, 用例 T)
REAL_HAPPY_PATH = [
    ev(None, "PENDING", "JAVA_GATEWAY"),
    ev("PENDING", "THINKING"),
    ev("THINKING", "TOOL_CALLING"),
    ev("TOOL_CALLING", "THINKING"),
    ev("THINKING", "COMPLETED"),
]

# 真实跑出来的一条失败流转 (杀掉 MCP 网关逼出来的)
REAL_FAILED_PATH = [
    ev(None, "PENDING", "JAVA_GATEWAY"),
    ev("PENDING", "THINKING"),
    ev("THINKING", "TOOL_CALLING"),
    ev("TOOL_CALLING", "FAILED"),
]


def test_真实的成功流转没有疑问():
    assert check_event_chain(REAL_HAPPY_PATH) == []


def test_真实的失败流转没有疑问():
    assert check_event_chain(REAL_FAILED_PATH) == []


def test_合法的ReAct循环里THINKING重复出现_不应报错():
    """★ 这是本文件的核心用例, 见模块 docstring ★

    THINKING 作为 fromStatus 出现两次是 ReAct 的正常形状, 不是异常。
    """
    events = [
        ev(None, "PENDING", "JAVA_GATEWAY"),
        ev("PENDING", "THINKING"),
        ev("THINKING", "TOOL_CALLING"),
        ev("TOOL_CALLING", "THINKING"),
        ev("THINKING", "TOOL_CALLING"),
        ev("TOOL_CALLING", "THINKING"),
        ev("THINKING", "COMPLETED"),
    ]
    froms = [e["fromStatus"] for e in events]
    assert froms.count("THINKING") == 3, "前提: 这条序列里 THINKING 确实重复了"

    assert check_event_chain(events) == [], "重复的 THINKING 被误判成错误了"


def test_空事件表要报错():
    problems = check_event_chain([])
    assert len(problems) == 1
    assert "task_id" in problems[0], "空表最常见的原因是 task_id 没传进 /chat, 提示要说到点上"


def test_第一条不是建任务事件要报错():
    events = [ev("PENDING", "THINKING"), ev("THINKING", "COMPLETED")]
    problems = check_event_chain(events)
    assert any("第一条事件" in p for p in problems)


def test_建任务事件的source必须是JAVA_GATEWAY():
    events = [ev(None, "PENDING", "PYTHON_RUNTIME"), ev("PENDING", "THINKING")]
    problems = check_event_chain(events)
    assert any("JAVA_GATEWAY" in p for p in problems)


def test_断链要报错():
    """两条事件的 fromStatus 都是 THINKING —— 这就是并发覆盖的指纹。"""
    events = [
        ev(None, "PENDING", "JAVA_GATEWAY"),
        ev("PENDING", "THINKING"),
        ev("THINKING", "TOOL_CALLING"),
        ev("THINKING", "COMPLETED"),  # ← 接不上: 上一条的 to 是 TOOL_CALLING
    ]
    problems = check_event_chain(events)
    assert any("断在第 4 条" in p for p in problems), problems


def test_没有TOOL_CALLING要报错():
    """工具确实被调用了, 事件表里却没有 —— 说明回调链路有一段断了。"""
    events = [
        ev(None, "PENDING", "JAVA_GATEWAY"),
        ev("PENDING", "THINKING"),
        ev("THINKING", "COMPLETED"),
    ]
    problems = check_event_chain(events)
    assert any("TOOL_CALLING" in p for p in problems)


@pytest.mark.parametrize("events", [REAL_HAPPY_PATH, REAL_FAILED_PATH])
def test_正常序列不会触发断链告警(events):
    assert [p for p in check_event_chain(events) if "断在第" in p] == []
