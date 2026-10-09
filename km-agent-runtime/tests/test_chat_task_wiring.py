"""/chat 的任务链路接线测试 (落地步骤第 4 步)。

配套设计文档: docs/task-state-machine.md 第五、六节。

## 这里验的是什么

不是"接口返回 200", 而是**接力棒有没有交对**。第 4 步的全部内容就是把三段接起来:

    /chat 入口 ──▶ TaskStateCallbackHandler ──▶ TaskStateReporter ──▶ Java

任何一段接错, 表现都是"HTTP 200, 一切正常", 但 Java 侧的事件表空空如也 ——
或者更糟, 最后一条 COMPLETED 在请求结束时被丢掉, 于是 Java 永远以为任务还在跑。

所以断言全部打在**假 Java 实际收到的报文**上, 而不是接口返回值上。

## 用的是真的 Reporter

`RecordingReporter` 继承真 `TaskStateReporter`, 只注入一个 MockTransport 客户端
并记下 aclose 是否被调用。这样队列、worker、排空逻辑都是真的 ——
如果只 mock 掉整个 Reporter, 那"终态有没有发出去"这个最关键的断言就成了空话。

不用 pytest-asyncio, 每个测试内部 asyncio.run, 与 tests/test_task_state.py 保持一致。
"""

from __future__ import annotations

import asyncio
import json

import httpx
import pytest
from langchain_core.messages import AIMessage

import app.main as main_module
from app.task_state import (
    COMPLETED,
    FAILED,
    THINKING,
    TOOL_CALLING,
    TaskStateReporter,
)

TASK_ID = "0123456789abcdef0123456789abcdef"


class FakeJava:
    """假的 Java 任务服务: 记录收到的每一次上报。"""

    def __init__(self) -> None:
        self.received: list[dict] = []

    def transport(self) -> httpx.MockTransport:
        def handle(request: httpx.Request) -> httpx.Response:
            self.received.append(json.loads(request.content))
            return httpx.Response(200, json={"ok": True})

        return httpx.MockTransport(handle)

    def statuses(self) -> list[str]:
        return [item["toStatus"] for item in self.received]


class FakeAgent:
    """假的 ReAct 图。

    会真的去 fire 传进来的 callbacks —— 否则 TaskStateCallbackHandler 到
    Reporter 这一段就没被走到, 而这正是第 4 步最容易接错的地方。
    """

    def __init__(self, reply: str = "订单 SO20260901001 已发货", raise_exc: Exception | None = None):
        self.reply = reply
        self.raise_exc = raise_exc
        self.configs: list[dict] = []

    async def ainvoke(self, payload: dict, config: dict | None = None) -> dict:
        self.configs.append(config or {})
        for handler in (config or {}).get("callbacks") or []:
            await handler.on_llm_start({}, ["hi"])
            await handler.on_tool_start({"name": "get_order_by_id"}, "{}")
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
    """把 /chat 里的 Reporter 换成"真逻辑 + 假网络"的版本。"""
    fake = FakeJava()
    created: list[TaskStateReporter] = []

    class RecordingReporter(TaskStateReporter):
        def __init__(self, base_url: str, task_id: str, timeout: float = 2.0) -> None:
            super().__init__(
                base_url, task_id, timeout,
                client=httpx.AsyncClient(transport=fake.transport()),
            )
            self.aclosed = False
            created.append(self)

        async def aclose(self, drain_timeout: float = 5.0) -> None:
            await super().aclose(drain_timeout)
            self.aclosed = True

    monkeypatch.setattr(main_module, "TaskStateReporter", RecordingReporter)

    yield fake, created

    # Reporter 用的是外部注入的 client (owns_client=False), 它自己不会关;
    # 测试里收个尾, 免得留一堆没关的连接。
    for reporter in created:
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
    assert fake.received == []


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
    assert fake.received == []


# ----------------------------------------------------------------------
#  带 task_id —— 任务链路
# ----------------------------------------------------------------------


def test_带task_id时上报进行中状态与终态(wired):
    fake, _ = wired
    resp = post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID})

    assert resp.status_code == 200
    # 两个 THINKING 分别是 /chat 入口和 on_llm_start 报的。
    # 这不是重复 bug —— Java 侧对同态上报是幂等的, 不会写重复事件行。
    # 入口那一条的意义是: 即使 LLM 一次都没跑起来, 事件表里也有"开始过"的痕迹。
    assert fake.statuses() == [THINKING, THINKING, TOOL_CALLING, COMPLETED]


def test_终态detail里带着最终答复(wired):
    fake, _ = wired
    post_chat(FakeAgent(reply="订单已发货"), {"message": "查订单", "task_id": TASK_ID})

    last = fake.received[-1]
    assert last["toStatus"] == COMPLETED
    assert last["detail"]["result"] == "订单已发货"
    assert last["source"] == "PYTHON_RUNTIME"


def test_调用失败时上报FAILED而不是COMPLETED(wired):
    fake, _ = wired
    resp = post_chat(
        FakeAgent(raise_exc=RuntimeError("工具网关 500")),
        {"message": "查订单", "task_id": TASK_ID},
    )

    assert resp.status_code == 500
    statuses = fake.statuses()
    assert statuses[-1] == FAILED
    assert COMPLETED not in statuses, "失败的任务被上报成 COMPLETED 了"
    assert "工具网关 500" in fake.received[-1]["detail"]["error"]


def test_失败路径同样会排空队列(wired):
    """终态往往是最有价值的一条, 失败路径丢它 = Java 永远以为任务还在跑。"""
    fake, created = wired
    post_chat(
        FakeAgent(raise_exc=RuntimeError("boom")),
        {"message": "查订单", "task_id": TASK_ID},
    )

    assert len(created) == 1
    assert created[0].aclosed, "失败路径没排空上报队列, 终态可能被丢"
    assert fake.received, "一条都没发出去"


def test_成功路径也会排空队列(wired):
    fake, created = wired
    post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID})

    assert created[0].aclosed
    assert fake.statuses()[-1] == COMPLETED, "COMPLETED 没能在请求结束前发出去"


# ----------------------------------------------------------------------
#  task_id 与 thread_id 的关系
# ----------------------------------------------------------------------


def test_task_id同时就是thread_id(wired):
    """两者本就是同一个东西 —— 共用 ID 是断点续跑不需要映射表的前提。"""
    agent = FakeAgent()
    post_chat(agent, {"message": "查订单", "task_id": TASK_ID, "thread_id": "另一个值"})

    assert agent.configs[0]["configurable"]["thread_id"] == TASK_ID


def test_任务链路会挂上callback_handler(wired):
    agent = FakeAgent()
    post_chat(agent, {"message": "查订单", "task_id": TASK_ID})

    callbacks = agent.configs[0].get("callbacks") or []
    assert len(callbacks) == 1, "没挂 callback, 那 THINKING/TOOL_CALLING 根本没人上报"
    assert type(callbacks[0]).__name__ == "TaskStateCallbackHandler"


def test_不带task_id时不挂callback(wired):
    agent = FakeAgent()
    post_chat(agent, {"message": "你好"})

    assert (agent.configs[0].get("callbacks") or []) == []


def test_响应回显task_id(wired):
    resp = post_chat(FakeAgent(), {"message": "查订单", "task_id": TASK_ID})
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
        fake.received.append(json.loads(request.content))
        return httpx.Response(409, json={"error": "ILLEGAL_TRANSITION", "message": "已被拒绝"})

    # ★ 必须继承【真】TaskStateReporter (模块顶部 import 的那个),
    #   不能继承 fixture 换进去的 RecordingReporter —— 那样 super().__init__()
    #   会打到 RecordingReporter 的签名上, 报 TypeError。这个坑踩过一次。
    class Rejecting(TaskStateReporter):
        def __init__(self, base_url: str, task_id: str, timeout: float = 2.0) -> None:
            super().__init__(
                base_url, task_id, timeout,
                client=httpx.AsyncClient(transport=httpx.MockTransport(always_409)),
            )

    monkeypatch.setattr(main_module, "TaskStateReporter", Rejecting)

    resp = post_chat(FakeAgent(reply="订单已发货"), {"message": "查订单", "task_id": TASK_ID})

    assert resp.status_code == 200
    assert resp.json()["reply"] == "订单已发货"
    assert fake.received, "上报确实发出去了 —— 只是被 Java 拒了, 这正是要覆盖的情况"
