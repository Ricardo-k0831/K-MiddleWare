"""/chat 的任务链路接线测试 (落地步骤第 4 步)。

配套设计文档: docs/task-state-machine.md 第五、六节。

## 这里验的是什么

不是"接口返回 200", 而是**接力棒有没有交对**。第 4 步的全部内容就是把三段接起来:

    /chat 入口 ──▶ TaskStateCallbackHandler ──▶ TaskStateReporter ──▶ Java

任何一段接错, 表现都是"HTTP 200, 一切正常", 但 Java 侧的事件表空空如也 ——
或者更糟, 最后一条 COMPLETED 在请求结束时被丢掉, 于是 Java 永远以为任务还在跑。

所以断言全部打在**假 Java 实际收到的报文**上, 而不是接口返回值上。

审计那条旁路 (AuditCallbackHandler -> AuditReporter -> POST /api/audit/events)
接在同一层, 验法完全一样, 也放在本文件里。

## 用的是真的 Reporter

`RecordingReporter` / `RecordingAuditReporter` 继承真 Reporter, 只注入一个
MockTransport 客户端并记下 aclose 是否被调用。这样队列、worker、排空逻辑
都是真的 —— 如果只 mock 掉整个 Reporter, 那"终态有没有发出去"这个最关键的
断言就成了空话。

不用 pytest-asyncio, 每个测试内部 asyncio.run, 与 tests/test_task_state.py 保持一致。
"""

from __future__ import annotations

import asyncio
import json

import httpx
import pytest
from fastapi import HTTPException
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, LLMResult

import app.main as main_module
from app.audit import AuditReporter
from app.mcp_adapter.task_identity import TaskIdentity, current_task_identity
from app.task_state import (
    COMPLETED,
    FAILED,
    THINKING,
    TOOL_CALLING,
    TaskStateReporter,
)

TASK_ID = "0123456789abcdef0123456789abcdef"
TRACE_ID = "cffac16e00000000cffac16e00000000"


class FakeJava:
    """假的 Java 任务服务: 按路径分别记录收到的每一次上报。

    ★ 必须按路径分开记, 不能混在一个列表里 ★

    状态回调 (PATCH /api/tasks/{id}/status) 和审计提交
    (POST /api/audit/events) 打的是同一个 base_url、共用同一个 client,
    报文形状也完全不同 —— 混在一起记的话, 断言"状态上报的顺序"会被审计
    事件插进来打乱, 而失败信息会指向一个根本不存在的 bug。
    """

    def __init__(self) -> None:
        self.status_reports: list[dict] = []
        self.audit_batches: list[list[dict]] = []
        # 建出来的审计 Reporter。挂在 fake 上而不是由 fixture 多返回一个值,
        # 是为了让原来那些 `fake, created = wired` 一行都不用改 ——
        # 一个纯新增的旁路不该逼着所有老测试改签名。
        self.audit_reporters: list[AuditReporter] = []

    def transport(self) -> httpx.MockTransport:
        def handle(request: httpx.Request) -> httpx.Response:
            body = json.loads(request.content)
            if request.url.path == "/api/audit/events":
                self.audit_batches.append(body)
                # 真实接口是 202 + 计数, 见 AuditController.IngestResult
                return httpx.Response(
                    202, json={"accepted": len(body), "received": len(body)}
                )
            self.status_reports.append(body)
            return httpx.Response(200, json={"ok": True})

        return httpx.MockTransport(handle)

    def statuses(self) -> list[str]:
        return [item["toStatus"] for item in self.status_reports]

    def audits(self) -> list[dict]:
        """所有审计事件摊平 —— 批量只是传输方式, 断言关心的是事件本身。"""
        return [event for batch in self.audit_batches for event in batch]

    def audits_of(self, event_type: str) -> list[dict]:
        return [e for e in self.audits() if e["eventType"] == event_type]


def _llm_result() -> LLMResult:
    """一份贴近真实的 LLMResult。

    刻意用真的 langchain 类型而不是一个 dict 假体: 取 token 用量那段代码
    要同时在 usage_metadata (标准位置) 和 llm_output.token_usage (供应商
    位置) 里挖, 假体只能覆盖其中一种, 而覆盖不全恰恰是那段代码唯一值得
    测的地方。
    """
    message = AIMessage(
        content="订单已发货",
        usage_metadata={"input_tokens": 120, "output_tokens": 18, "total_tokens": 138},
    )
    return LLMResult(
        generations=[
            [ChatGeneration(message=message, generation_info={"finish_reason": "stop"})]
        ],
        llm_output={"model_name": "deepseek-chat"},
    )


class FakeAgent:
    """假的 ReAct 图。

    会真的去 fire 传进来的 callbacks —— 否则 TaskStateCallbackHandler /
    AuditCallbackHandler 到 Reporter 这一段就没被走到, 而这正是第 4 步
    最容易接错的地方。

    start / end 成对 fire 并且带 run_id: 耗时和"哪个工具"全靠 run_id 配对,
    而审计是在 end 里上报的 —— 只 fire start 的话审计那边一条事件都不会
    产生, 表现是"审计是空的", 看起来像代码坏了, 其实只是假 Agent 不够真。
    """

    def __init__(self, reply: str = "订单 SO20260901001 已发货", raise_exc: Exception | None = None):
        self.reply = reply
        self.raise_exc = raise_exc
        self.configs: list[dict] = []

    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        self.configs.append(config or {})
        for handler in (config or {}).get("callbacks") or []:
            await handler.on_llm_start({}, ["hi"], run_id="llm-1")
            await handler.on_llm_end(_llm_result(), run_id="llm-1")
            await handler.on_tool_start(
                {"name": "get_order_by_id"},
                '{"orderId": "SO20260901001"}',
                run_id="tool-1",
            )
            await handler.on_tool_end("已发货", run_id="tool-1")
        if self.raise_exc is not None:
            raise self.raise_exc
        return {
            "messages": [
                AIMessage(
                    content="",
                    tool_calls=[{"name": "get_order_by_id", "args": {}, "id": "c1"}],
                ),
                AIMessage(content=self.reply),
            ]
        }


@pytest.fixture
def wired(monkeypatch):
    """把 /chat 里的两个 Reporter 都换成"真逻辑 + 假网络"的版本。"""
    fake = FakeJava()
    created: list[TaskStateReporter] = []

    class RecordingReporter(TaskStateReporter):
        def __init__(
            self, base_url: str, task_id: str, *, trace_id: str, timeout: float = 2.0
        ) -> None:
            super().__init__(
                base_url, task_id, trace_id=trace_id, timeout=timeout,
                client=httpx.AsyncClient(transport=fake.transport()),
            )
            self.aclosed = False
            created.append(self)

        async def aclose(self, drain_timeout: float = 5.0) -> None:
            await super().aclose(drain_timeout)
            self.aclosed = True

    class RecordingAuditReporter(AuditReporter):
        def __init__(
            self,
            base_url: str,
            *,
            task_id: str,
            trace_id: str | None = None,
            tenant_id: str | None = None,
            actor: str | None = None,
            token: str | None = None,
            timeout: float = 2.0,
        ) -> None:
            super().__init__(
                base_url,
                task_id=task_id,
                trace_id=trace_id,
                tenant_id=tenant_id,
                actor=actor,
                token=token,
                timeout=timeout,
                client=httpx.AsyncClient(transport=fake.transport()),
            )
            self.aclosed = False
            fake.audit_reporters.append(self)

        async def aclose(self, drain_timeout: float = 2.0) -> None:
            await super().aclose(drain_timeout)
            self.aclosed = True

    monkeypatch.setattr(main_module, "TaskStateReporter", RecordingReporter)
    monkeypatch.setattr(main_module, "AuditReporter", RecordingAuditReporter)

    # 入口的执行身份校验 (GET /api/tasks/{id}) 在 test_task_entry_validation.py
    # 里单独验, 那里用 MockTransport 造了任务服务的响应。这里换成放行版 ——
    # 但仍然要求 traceId 和已知常量一致, 否则"拿错 trace 也会被接进来"这件事
    # 就没人守着了。
    async def verify(settings, task_id, trace_id):
        if trace_id != TRACE_ID:
            raise HTTPException(status_code=409, detail="执行身份不匹配")

    monkeypatch.setattr(main_module, "_verify_task_identity", verify)

    yield fake, created

    # Reporter 用的是外部注入的 client (owns_client=False), 它自己不会关;
    # 测试里收个尾, 免得留一堆没关的连接。
    for reporter in created:
        asyncio.run(reporter._client.aclose())
    for reporter in fake.audit_reporters:
        asyncio.run(reporter._client.aclose())


def post_chat(agent: FakeAgent, body: dict) -> httpx.Response:
    """打一次 /chat。直接设 state.agent, 不走 lifespan —— ASGITransport 不跑 lifespan。"""

    async def _go() -> httpx.Response:
        original = main_module.state.agent
        main_module.state.agent = agent
        try:
            transport = httpx.ASGITransport(app=main_module.app)
            async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
                return await client.post("/chat", json=body)
        finally:
            main_module.state.agent = original

    return asyncio.run(_go())


# ----------------------------------------------------------------------
#  不带 task_id —— Phase1 老路径必须一点没变
# ----------------------------------------------------------------------


def test_不带task_id时完全不回调(wired):
    fake, created = wired
    resp = post_chat(FakeAgent(), {"message": "你好", "thread_id": "t1"})

    assert resp.status_code == 200
    assert created == [], "没给 task_id 却建了 Reporter —— Phase1 路径被污染了"
    assert fake.status_reports == []
    # 审计同样一条都不发。理由和状态上报略有不同: 状态上报没有 task_id 就
    # 不知道该 PATCH 哪个任务; 审计其实发得出去 (不带任务身份), 但那样记出来
    # 的是一堆归属不到任何任务的匿名行 —— 没有比有它更省事。
    assert fake.audit_batches == []


def test_不带task_id时响应里task_id为null(wired):
    resp = post_chat(FakeAgent(), {"message": "你好"})
    assert resp.json()["task_id"] is None
    assert resp.json()["thread_id"] == "default"


def test_空字符串的task_id按未提供处理(wired):
    """调用方从 JSON 里传个 "" 进来很常见, 不该被当成一个真任务去回调。"""
    fake, created = wired
    resp = post_chat(FakeAgent(), {"message": "你好", "task_id": "   "})

    assert resp.status_code == 200
    assert created == []
    assert fake.status_reports == []


# ----------------------------------------------------------------------
#  带 task_id —— 任务链路
# ----------------------------------------------------------------------


def test_带task_id时上报进行中状态与终态(wired):
    fake, _ = wired
    resp = post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    assert resp.status_code == 200
    # 两个 THINKING 分别是 /chat 入口和 on_llm_start 报的。
    # 这不是重复 bug —— Java 侧对同态上报是幂等的, 不会写重复事件行。
    # 入口那一条的意义是: 即使 LLM 一次都没跑起来, 事件表里也有"开始过"的痕迹。
    assert fake.statuses() == [THINKING, THINKING, TOOL_CALLING, COMPLETED]


def test_终态detail里带着最终答复(wired):
    fake, _ = wired
    post_chat(FakeAgent(reply="订单已发货"), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    last = fake.status_reports[-1]
    assert last["toStatus"] == COMPLETED
    assert last["detail"]["result"] == "订单已发货"
    assert last["source"] == "PYTHON_RUNTIME"


def test_调用失败时上报FAILED而不是COMPLETED(wired):
    fake, _ = wired
    resp = post_chat(
        FakeAgent(raise_exc=RuntimeError("工具网关 500")),
        {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID},
    )

    assert resp.status_code == 500
    statuses = fake.statuses()
    assert statuses[-1] == FAILED
    assert COMPLETED not in statuses, "失败的任务被上报成 COMPLETED 了"
    assert "工具网关 500" in fake.status_reports[-1]["detail"]["error"]


def test_失败路径同样会排空队列(wired):
    """终态往往是最有价值的一条, 失败路径丢它 = Java 永远以为任务还在跑。"""
    fake, created = wired
    post_chat(
        FakeAgent(raise_exc=RuntimeError("boom")),
        {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID},
    )

    assert len(created) == 1
    assert created[0].aclosed, "失败路径没排空上报队列, 终态可能被丢"
    assert fake.status_reports, "一条都没发出去"


def test_成功路径也会排空队列(wired):
    fake, created = wired
    post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    assert created[0].aclosed
    assert fake.statuses()[-1] == COMPLETED, "COMPLETED 没能在请求结束前发出去"


# ----------------------------------------------------------------------
#  task_id 与 thread_id 的关系
# ----------------------------------------------------------------------


def test_task_id同时就是thread_id(wired):
    """两者本就是同一个东西 —— 共用 ID 是断点续跑不需要映射表的前提。"""
    agent = FakeAgent()
    post_chat(agent, {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID, "thread_id": "另一个值"})

    assert agent.configs[0]["configurable"]["thread_id"] == TASK_ID


def test_任务链路会挂上状态和审计两个callback(wired):
    agent = FakeAgent()
    post_chat(agent, {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    callbacks = agent.configs[0].get("callbacks") or []
    # 两个缺一不可, 而且挂错一个不会报错 —— 只会让某一层的审计永远为空。
    names = sorted(type(c).__name__ for c in callbacks)
    assert names == ["AuditCallbackHandler", "TaskStateCallbackHandler"]


def test_不带task_id时不挂callback(wired):
    agent = FakeAgent()
    post_chat(agent, {"message": "你好"})

    assert (agent.configs[0].get("callbacks") or []) == []


def test_响应回显task_id(wired):
    resp = post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})
    body = resp.json()

    assert body["task_id"] == TASK_ID
    assert body["thread_id"] == TASK_ID, "响应里的 thread_id 应该是生效的那个"
    assert body["tool_calls"] == ["get_order_by_id"]


# ----------------------------------------------------------------------
#  旁路不能反过来影响主流程
# ----------------------------------------------------------------------


def test_Java拒绝上报时请求仍然成功(wired, monkeypatch):
    """409/500 都可能发生 (并发、状态过期)。上报失败绝不能把成功的对话变成 500。"""
    fake, _ = wired

    def always_409(request: httpx.Request) -> httpx.Response:
        fake.status_reports.append(json.loads(request.content))
        return httpx.Response(409, json={"error": "ILLEGAL_TRANSITION", "message": "已被拒绝"})

    # ★ 必须继承【真】TaskStateReporter (模块顶部 import 的那个),
    #   不能继承 fixture 换进去的 RecordingReporter —— 那样 super().__init__()
    #   会打到 RecordingReporter 的签名上, 报 TypeError。这个坑踩过一次。
    class Rejecting(TaskStateReporter):
        def __init__(
            self, base_url: str, task_id: str, *, trace_id: str, timeout: float = 2.0
        ) -> None:
            super().__init__(
                base_url, task_id, trace_id=trace_id, timeout=timeout,
                client=httpx.AsyncClient(transport=httpx.MockTransport(always_409)),
            )

    monkeypatch.setattr(main_module, "TaskStateReporter", Rejecting)

    resp = post_chat(FakeAgent(reply="订单已发货"), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    assert resp.status_code == 200
    assert resp.json()["reply"] == "订单已发货"
    assert fake.status_reports, "上报确实发出去了 —— 只是被 Java 拒了, 这正是要覆盖的情况"


# ----------------------------------------------------------------------
#  审计链路 —— 第二条旁路
# ----------------------------------------------------------------------
#
#  第二段接力: /chat ──▶ AuditCallbackHandler ──▶ AuditReporter ──▶ Java 8083
#
#  和状态那条一样的验法, 但重点不同: 状态那条容易错在"终态丢了",
#  审计这条容易错在"一条都没记" —— 而"一条都没有"在 /chat 的返回值上
#  完全看不出来, 只有打在报文上才能发现。


def test_带task_id时LLM调用被记成审计事件(wired):
    fake, _ = wired
    post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    llm_calls = fake.audits_of("LLM_CALL")
    assert len(llm_calls) == 1, "一趟对话至少一次 LLM 调用, 一条审计都没有说明没接上"

    event = llm_calls[0]
    assert event["layer"] == "AGENT_RUNTIME", "层写错的话, 按层统计会把运行时算到别人头上"
    assert event["status"] == "OK"
    # token 用量是 LLM_CALL 这类事件存在的理由: 别的层都拿不到这个数
    assert (event["tokenIn"], event["tokenOut"]) == (120, 18)
    assert event["latencyMs"] >= 0
    assert event["requestBody"]["model"] == "deepseek-chat"


def test_工具调用在运行时这一层也留一条(wired):
    """★ 和网关那条不是重复 ★

    网关记的是"网关收到了什么", 这里记的是"Agent 想调什么"。请求根本没到达
    网关时 (网关没起来、超时), 只查网关会得出"这个任务没调过工具"的结论,
    而那是错的。
    """
    fake, _ = wired
    post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    tool_calls = fake.audits_of("MCP_TOOL_CALL")
    assert len(tool_calls) == 1
    assert tool_calls[0]["requestBody"]["tool"] == "get_order_by_id"
    # 入参解析成对象而不是原样字符串: 只有对象才能用 JSON 路径查
    # ("按 orderId 查这次调用"是审计最常见的用法)。
    assert tool_calls[0]["requestBody"]["params"] == {"orderId": "SO20260901001"}
    # 响应体刻意留给网关那条 —— 同一份数据记两遍, 不一致时没人知道该信谁
    assert "responseBody" not in tool_calls[0]


def test_审计事件带着任务身份(wired):
    """身份是审计能按任务串起来的前提。

    这条数据链跨了三个进程: Python 提交 -> Java 转投 MQ -> 消费端落库。
    中间任何一段把身份丢了, 落库的 agent_task_id 就是 NULL —— 而审计表里
    NULL 行不是"少一点数据", 是"这些记录再也归属不到任何任务"。
    """
    fake, _ = wired
    post_chat(
        FakeAgent(),
        {
            "message": "查订单",
            "task_id": TASK_ID,
            "trace_id": TRACE_ID,
            "tenant_id": "km",
            "actor": "u-1",
        },
    )

    assert fake.audits(), "一条审计都没发"
    for event in fake.audits():
        assert event["taskId"] == TASK_ID
        assert event["traceId"] == TRACE_ID
        assert event["tenantId"] == "km"
        assert event["actor"] == "u-1"


def test_没给的租户和操作者不会被补成一个假值(wired):
    """缺一个值是诚实的缺失, 值不一致才是谎言 —— 见 ChatRequest.trace_id。

    traceId 现在不在"可以不补"之列: 任务链路缺了它直接 400 (见下一个用例)。
    """
    fake, _ = wired
    post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    event = fake.audits()[0]
    assert event["taskId"] == TASK_ID
    assert event["traceId"] == TRACE_ID
    assert "tenantId" not in event
    assert "actor" not in event


def test_每条审计事件都有唯一的eventId(wired):
    """消费端靠它做幂等去重 (RocketMQ 保证"至少一次", 重投是常态)。

    没有它, HTTP 重发或 MQ 重投都会在库里变成重复行 —— 而且看起来
    完全正常, 没有任何东西会报警。
    """
    fake, _ = wired
    post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    ids = [event["eventId"] for event in fake.audits()]
    assert len(ids) >= 2, "这个用例需要不止一条事件才说明得了问题"
    assert len(set(ids)) == len(ids), "事件 ID 撞了 —— 唯一键会把真实的第二条当重投丢掉"
    # 和 Java 侧 AuditEvent.newEventId() 同形: 32 位无横线 UUID
    assert all(len(i) == 32 for i in ids)


def test_审计是批量发的而不是一条一个请求(wired):
    """一趟对话里 LLM 和工具各有事件, 逐个 POST 的话 HTTP 开销会赶上业务本身。"""
    fake, _ = wired
    post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    assert len(fake.audit_batches) == 1, f"实际发了 {len(fake.audit_batches)} 个请求"
    assert len(fake.audit_batches[0]) == 2


def test_审计收尾也会排空队列(wired):
    fake, _ = wired
    post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    assert len(fake.audit_reporters) == 1
    assert fake.audit_reporters[0].aclosed, "审计队列没排空, 最后几条可能还在队列里"
    assert fake.audit_reporters[0].dropped == 0


def test_审计上报失败不影响对话(wired, monkeypatch):
    """审计是旁路: 8083 拒收审计, 对话该成功还是成功。"""
    # 这个用例只关心响应, 不关心收到了什么 —— 假 Java 用不上
    _fake, _ = wired

    def always_500(request: httpx.Request) -> httpx.Response:
        if request.url.path == "/api/audit/events":
            return httpx.Response(500, text="boom")
        return httpx.Response(200, json={"ok": True})

    class FailingAudit(AuditReporter):
        def __init__(self, base_url: str, **kwargs) -> None:
            super().__init__(
                base_url,
                client=httpx.AsyncClient(transport=httpx.MockTransport(always_500)),
                **kwargs,
            )

    monkeypatch.setattr(main_module, "AuditReporter", FailingAudit)

    resp = post_chat(FakeAgent(reply="订单已发货"), {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    assert resp.status_code == 200
    assert resp.json()["reply"] == "订单已发货"


# ----------------------------------------------------------------------
#  任务身份 —— 交给 MCP 客户端的那根接力棒
# ----------------------------------------------------------------------
#
#  第四段接力: /chat ──▶ ContextVar ──▶ TaskIdentityAuth ──▶ Java 网关
#
#  这里验前半段 (ContextVar 有没有绑上、绑的范围对不对),
#  后半段 (头有没有真的发出去) 在 tests/test_task_identity.py。
#
#  单独验前半段是有必要的: 在真实链路上, 读取身份的是 langchain 的 BaseTool,
#  它离 /chat 有 LangGraph 好几层 —— 中间少绑一次, 表现是审计里
#  agent_task_id 全空, 而工具调用一切正常, 端到端看像是"网关的锅"。


class IdentityAwareAgent(FakeAgent):
    """在 ainvoke 里读当前身份的假 Agent。

    读的位置很关键: 必须在 ainvoke 【内部】读, 而不是从 config 里拿 ——
    ContextVar 的意义就在于身份不需要被当参数一层层传下去, 这里模拟的正是
    真实工具链 (LangGraph -> BaseTool -> httpx) 读取它的方式。
    """

    def __init__(self) -> None:
        super().__init__()
        self.seen: list[TaskIdentity | None] = []

    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        self.seen.append(current_task_identity())
        return await super().ainvoke(payload, config)


def test_带task_id时身份在ainvoke期间可见(wired):
    agent = IdentityAwareAgent()
    post_chat(agent, {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})

    assert agent.seen == [TaskIdentity(task_id=TASK_ID, trace_id=TRACE_ID)], (
        "工具调用拿不到任务身份 —— 审计里的 agent_task_id 会全是 NULL"
    )


def test_三个可选字段会一起带到身份上(wired):
    agent = IdentityAwareAgent()
    post_chat(
        agent,
        {
            "message": "查订单",
            "task_id": TASK_ID,
            "trace_id": TRACE_ID,
            "tenant_id": "km",
            "actor": "u-1",
        },
    )

    assert agent.seen[0] == TaskIdentity(
        task_id=TASK_ID, trace_id=TRACE_ID, tenant_id="km", actor="u-1"
    )


def test_带task_id但不给trace_id直接400(wired):
    """★ Python 既不生成 traceId, 也不替调用方猜 ★

    以前缺 trace 时会带着 None 跑完全程, 只有 Java 那边的审计是空的 ——
    从返回值上完全看不出来。现在在入口就拒: 没有执行身份, 这条任务链路
    跑的每一步都无法归位, 不如一开始就跑不成。

    断言同时打在"没建 Reporter"和"没动 Agent"上: 光看 400 不知道它在拒绝
    之前已经走了多远。
    """
    fake, created = wired
    agent = IdentityAwareAgent()
    resp = post_chat(agent, {"message": "查订单", "task_id": TASK_ID})

    assert resp.status_code == 400
    assert "trace_id" in resp.json()["detail"]
    assert created == [], "400 之前就把 Reporter 建起来了"
    assert agent.configs == [], "校验没过却已经把 Agent 跑起来了"
    assert fake.status_reports == []
    assert fake.audit_batches == []


def test_不带task_id时没有身份(wired):
    """Phase1 老路径: 不绑身份, 网关那边会记成匿名 —— 这是对的, 不该硬塞。"""
    agent = IdentityAwareAgent()
    post_chat(agent, {"message": "你好"})

    assert agent.seen == [None]


def test_响应回显trace_id(wired):
    resp = post_chat(
        IdentityAwareAgent(),
        {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID},
    )

    assert resp.json()["trace_id"] == TRACE_ID
    assert resp.json()["task_id"] == TASK_ID


def test_上一次的身份不会泄漏到下一次请求(wired):
    """身份的作用域要【恰好】包住一次 invocation。

    包大了 (比如在 lifespan 里绑一次, 或者干脆不还原) 的后果不是"多带了一
    个头", 而是任务 B 的工具调用被记到任务 A 名下 —— 审计里看不出来,
    因为那看起来是一条完全正常的记录。
    """
    agent = IdentityAwareAgent()

    post_chat(agent, {"message": "查订单", "task_id": TASK_ID, "trace_id": TRACE_ID})
    resp = post_chat(agent, {"message": "你好"})

    assert resp.status_code == 200
    assert agent.seen[0] == TaskIdentity(task_id=TASK_ID, trace_id=TRACE_ID)
    assert agent.seen[-1] is None, "上一个任务的身份泄漏到了下一个请求"
