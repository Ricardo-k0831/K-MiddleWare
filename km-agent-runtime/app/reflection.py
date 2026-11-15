"""答案自检 (critique) + 重规划 —— Agent 的"反思闭环"。

## 它解决什么问题

ReAct 只能保证"模型把工具结果读完再回答", 保证不了"回答对不对"。常见的三种
失败, 循环本身一条都发现不了:

* **编造**: 工具只返回了状态, 模型自己补了一个没出现过的发货时间;
* **答非所问**: 用户问"什么时候到", 模型答了"已支付";
* **白跑一趟**: 工具报错/被限流, 模型把错误当结论 ("订单不存在"), 而正确做法
  是换一个工具或换参数再试一次。

这三种错误在 HTTP 层都是 200, 在状态机上都是 COMPLETED, 肉眼看也很通顺 ——
只有拿"用户的问题 + 这条轨迹"再问一次模型, 才能判出来。

## 为什么放在应用层, 不做成图里的节点

LangGraph 的 ReAct 图路由是 "最后一条 AI 消息没有 tool_calls → 结束"。取消
收尾 (`app/checkpoint.py` 的 `release_pending`) 正是借这条路由把挂起状态落地的:
以 agent 身份写一条无 tool_calls 的消息, 图就停在终态。如果在 agent 后面硬加
一个 critic 节点, 那条消息会走到 critic, `next` 非空, 会话线程被永久占住 ——
取消功能会以一个很难排查的方式坏掉。

所以反思放在应用层: 每一轮都是一次**完整的 ainvoke**, 状态上报、审计、
取消、断点续跑全部原样复用, 不重造第二套执行语义。

## 代价, 说清楚

* 每轮多一次 LLM 调用 (critic), 失败还要多一次完整回答。这是真的贵, 所以
  默认关闭, 由请求里的 ``reflect`` 显式打开, 评测脚本才开。
* critic 的调用同样带 callbacks, 因此它会作为一条 LLM_CALL 落到四层审计里 ——
  "打分的开销"本身是可观测的, 这一点是刻意的。
* 自检反馈以 HumanMessage 的形式写进会话记忆。它不会消失, 下一轮模型还能
  看到"上次被指出过什么"。这是有意的取舍: 与其把反馈藏在模型看不见的地方,
  不如让后续轮次也知道这次修正; 消息带 ``km_reflection`` 标记, 审计和调试
  时可以和用户真正说的话区分开。
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    SystemMessage,
    ToolMessage,
)

logger = logging.getLogger(__name__)

# 轨迹文本的总预算。critic 不需要看完整的长回答, 但必须看到每一次工具调用
# 和它的返回 —— 编造类错误只有把"工具说了什么"和"回答说了什么"并排放才看得出来。
MAX_TRANSCRIPT_CHARS = 6000
# 单个工具结果保留的长度。超长结果截断, 但要留够判断依据 (订单号/状态都在开头)。
MAX_TOOL_RESULT_CHARS = 1200

CRITIC_SYSTEM_PROMPT = """你是一个严格的企业助手质检员。你会看到【用户的问题】
和【助手这次执行的全部轨迹】(工具调用与返回、最终答复)。请判断最终答复是否合格。

判定标准 (按重要性排序):

1. **忠实**: 答复里的每一条业务事实 (订单号、状态、金额、商品、时间)
   都必须能在工具返回里找到出处。工具没给的信息不能补 —— 说"不知道"是合格的,
   编一个看起来合理的时间/状态是不合格的。
2. **切题**: 直接回答了用户问的那件事。问"什么时候到"却只答"已支付", 不合格。
3. **会用错误信息**: 工具返回"不存在/被限流/被熔断"时, 允许如实转达;
   但如果还有别的合理手段没试 (例如给了用户 ID 却没调列表工具), 应指出。
4. **不重复劳动**: 参数完全相同的工具调用重复两次以上, 且第二次没有带来新信息,
   记一个问题。

只输出一个 JSON 对象, 不要输出其它文字:

{
  "passed": true 或 false,          // 忠实且切题才为 true
  "score": 0-100 的整数,            // 100 = 完全合格
  "issues": ["具体问题, 一条一句"],  // passed=true 时可以是空数组
  "fix_hint": "如果重来一次, 应该怎么改 (一句话)"  // passed=true 时给空串
}
"""


@dataclass(frozen=True)
class Critique:
    """一次质检的结论。``passed`` 之外的全部字段都只用于日志/反馈。"""

    passed: bool
    score: int
    issues: tuple[str, ...] = ()
    fix_hint: str = ""
    # 构造质检报告
    def as_report(self, round_no: int) -> dict[str, Any]:
        return {
            "round": round_no,
            "passed": self.passed,
            "score": self.score,
            "issues": list(self.issues),
            "fixHint": self.fix_hint,
        }


class CritiqueParseError(ValueError):
    """critic 的输出不是合法 JSON / 缺字段。

    单独一个类型是为了让调用方能"宽恕"它: 质检员自己胡说八道不应该
    让用户的请求失败。
    """

# 截断文本
def _clip(text: str, limit: int) -> str:
    text = (text or "").strip()
    if len(text) <= limit:
        return text
    return text[:limit] + f"…(已截断, 原长 {len(text)} 字)"

# 从工具调用call中提取工具名称
def _tool_name(call: Any) -> str:
    if isinstance(call, dict):
        return str(call.get("name") or "?")
    return str(getattr(call, "name", "?"))

# 从工具调用call中提取工具参数
def _tool_args(call: Any) -> str:
    args = call.get("args") if isinstance(call, dict) else getattr(call, "args", {})
    try:
        return json.dumps(args, ensure_ascii=False)
    except (TypeError, ValueError):
        return str(args)

# 压缩消息列表
def render_transcript(messages: list[BaseMessage]) -> str:
    """把消息历史压成 critic 能读的文本。
    只保留三类: 用户说的话、助手的答复/工具调用、工具返回。SystemMessage
    (系统提示词) 不进来 —— 质检员判的是"这一次执行", 不是"提示词写得好不好"。
    """
    lines: list[str] = []
    for message in messages:
        # 处理HumanMessage
        if isinstance(message, HumanMessage):
            # 截断用户说的话
            content = _clip(str(message.content), MAX_TOOL_RESULT_CHARS)
            # 如果从additional_kwargs中获取到 km_reflection=True, 则认定为自检反馈
            marker = "[自检反馈]" if (message.additional_kwargs or {}).get("km_reflection") else "用户"
            lines.append(f"{marker}: {content}")
        # 处理AIMessage
        elif isinstance(message, AIMessage):
            # 截断助手的答复
            content = _clip(str(message.content or ""), MAX_TOOL_RESULT_CHARS)
            if content:
                lines.append(f"助手: {content}")
            #如果 AIMessage 有工具调用, 则记录工具调用信息
            for call in getattr(message, "tool_calls", None) or []:
                lines.append(f"助手 → 调用工具 {_tool_name(call)}({_tool_args(call)})")
        # 处理ToolMessage
        elif isinstance(message, ToolMessage):
            name = getattr(message, "name", None) or "工具"
            lines.append(f"工具 {name} 返回: {_clip(str(message.content), MAX_TOOL_RESULT_CHARS)}")
    transcript = "\n".join(lines)
    # 连 lines 都要截断
    return _clip(transcript, MAX_TRANSCRIPT_CHARS)


def _extract_json_object(text: str) -> str:
    """从可能带 ```json 围栏/前后废话的输出里抠出第一个 JSON 对象。
    模型偶尔会写"好的, 这是我的判断: {...}" —— 直接 json.loads 会失败,
    而失败一次就丢掉一次质检机会, 所以这里做得宽一点。
    """
    fenced = re.search(r"```(?:json)?\s*(\{.*?\})\s*```", text, re.S)
    if fenced:
        return fenced.group(1)
    start = text.find("{")
    end = text.rfind("}")
    if start == -1 or end <= start:
        raise CritiqueParseError(f"输出里没有 JSON 对象: {text[:200]!r}")
    return text[start : end + 1]

"""把 critic 的原始输出解析成结论。字段缺失/类型不对一律抛 CritiqueParseError。"""
def parse_critique(text: str) -> Critique:

    payload = json.loads(_extract_json_object(text))
    if not isinstance(payload, dict):
        raise CritiqueParseError("JSON 顶层不是对象")
    passed = payload.get("passed")
    if not isinstance(passed, bool):
        raise CritiqueParseError("passed 不是布尔值")
    score = payload.get("score")
    if not isinstance(score, (int, float)):
        raise CritiqueParseError("score 不是数字")
    # 处理 issues
    issues_raw = payload.get("issues") or []
    if not isinstance(issues_raw, list):
        raise CritiqueParseError("issues 不是数组")
    # 过滤掉issues中的空字符串
    issues = tuple(str(item).strip() for item in issues_raw if str(item).strip())

    # 过滤掉fix_hint(自我修正指令)中的空字符串
    fix_hint = str(payload.get("fix_hint") or "").strip()
    return Critique(
        passed=passed,
        score=max(0, min(100, int(score))),
        issues=issues,
        fix_hint=fix_hint,
    )

# 传入用户问题, 工作模型产生的历史消息列表  质检模型对此次工作进行质检,返回质检结果对象
async def critique_answer(
    llm: Any,
    question: str,
    messages: list[BaseMessage],
    *,
    config: dict[str, Any] | None = None,
) -> Critique:
    """让 critic 读一遍轨迹并给出结论。

    ``config`` 要传进来: 这样这次打分本身也会触发 callbacks, 落成一条
    LLM_CALL 审计 —— 质检的成本和它看到的模型是可查的。
    """
    prompt = (
        f"{CRITIC_SYSTEM_PROMPT}\n\n"
        "===== 用户的问题 =====\n"
        f"{question}\n\n"
        "===== 本次执行轨迹 =====\n"
        f"{render_transcript(messages)}\n"
    )
    response = await llm.ainvoke(
        [
            SystemMessage(content="你只输出 JSON, 不做其它解释。"),
            HumanMessage(content=prompt),
        ],
        config=config,
    )
    # 生成质检结果并解析成Critique对象进行返回
    return parse_critique(str(response.content))

# 传入质检结果对象, 返回修正指令  (后续作为HumanMessage.content添加到历史消息列表中, 让工作模型再一次调用)
def build_feedback(critique: Critique) -> str:
    """把质检结论翻译成给 Agent 的修正指令。

    措辞上有三个刻意:
    * 明确这是【系统内部】消息, 别在最终答复里提及"自检" —— 用户不关心;
    * 把"不要编造"单独列一条, 因为编造是最难自查的错误;
    * 提醒不要重复相同参数的调用 —— 否则模型会为了"补齐信息"再打一遍工具,
      限流下这是最坏的行为。
    """

    # 获取质检结果中 工作模型存在的问题 和 自我修正指令
    issues = "\n".join(f"- {item}" for item in critique.issues) or "- (无具体条目)"
    hint = critique.fix_hint or "基于已有工具结果, 给出更准确的答复"
    return (
        "[系统内部自检反馈, 用户看不到这条消息]\n"
        f"你上一轮的回答没有通过质量检查 (评分 {critique.score}/100)。\n"
        f"发现的问题:\n{issues}\n"
        f"修改建议: {hint}\n\n"
        "请重新给出最终答复:\n"
        "1. 只使用工具实际返回过的信息; 没有的信息直接说没有, 不要推测;\n"
        "2. 如果确实缺关键信息, 先调用合适的工具补齐再回答;\n"
        "3. 不要重复调用参数完全相同的工具;\n"
        "4. 直接回答用户的问题, 不要提及自检、评分或这条消息。"
    )


@dataclass
class ReflectionOutcome:
    messages: list[BaseMessage]
    critiques: list[Critique] = field(default_factory=list)

    @property
    def reports(self) -> list[dict[str, Any]]:
        return [c.as_report(i + 1) for i, c in enumerate(self.critiques)]


async def reflect_loop(
    *,
    question: str,
    messages: list[BaseMessage],
    llm: Any,
    invoke: Callable[[Any], Awaitable[Any]],
    max_revisions: int,
    config: dict[str, Any] | None = None,
    on_round: Callable[[int, Critique], None] | None = None,
) -> ReflectionOutcome:
    """质检 → (不合格则) 带着反馈重跑, 最多 ``max_revisions`` 次重跑。

    ``invoke`` 由调用方提供 (main.py 的 ``_run_agent``), 这样取消登记、
    任务身份、callbacks 全部沿用原执行路径, 反思轮不是一条"野路子"。

    质检本身失败 (网络/JSON 不合法) 时**直接结束反思, 保留现有答复**:
    质检是增益, 不是依赖。让它把一次本来成功的对话变成失败, 是把工具当成了
    主人。
    """
    critiques: list[Critique] = []
    current = messages

    # 循环质检, 直到通过或超过最大重跑次数
    for revision in range(max_revisions + 1):
        try:
            # 生成质检结果对象
            critique = await critique_answer(llm, question, current, config=config)
        except Exception:  # noqa: BLE001 —— 质检失败绝不影响主流程
            logger.warning("自检失败, 跳过反思 (第 %d 轮)", revision + 1, exc_info=True)
            break

        critiques.append(critique)
        # 这里的ON_ROUND 在main函数里 是进行一次THINKING状态的上报，detail是质检结果
        if on_round is not None:
            on_round(revision + 1, critique)

        # 如果质检通过 或 超过最大重跑次数, 则跳出循环
        if critique.passed or revision == max_revisions:
            break

        # 如果质检未通过, 则带着反馈重跑
        feedback = HumanMessage(
            # 根据质检结果构造修正指令消息
            content=build_feedback(critique),
            # 标记: 审计/调试时能把它和用户真正说的话分开。
            # 是render_transcript 里显示成 [自检反馈] 的依据。
            additional_kwargs={"km_reflection": revision + 1},
        )
        logger.info(
            "自检未通过 (score=%d, issues=%d), 带着反馈重跑第 %d 次",
            critique.score, len(critique.issues), revision + 1,
        )
        # 将修正指令消息添加到历史消息列表   重新调用传入的 invoke 函数  不是ainvoke()!
        result = await invoke({"messages": [feedback]})
        # 从 invoke 函数返回的对话中提取最新消息列表
        current = list(result.get("messages", current))

    return ReflectionOutcome(messages=current, critiques=critiques)
