"""/chat 入口的执行身份校验。

配套代码: app/main.py 的 `_verify_task_identity`。

## 为什么这条校验值得单独测

它不是"顺手加的一道限制", 而是任务链路<strong>能不能自洽</strong>的前提:

    POST /chat(task_id, trace_id)
        └─ GET /api/tasks/{id} -> 比对 traceId
             ├─ 一致   : 这次运行的每条审计都能归到同一条链上
             ├─ 不一致 : 409, 一条都不跑
             └─ Java 不可达: 502, 也不跑

失败即止是刻意的代价: 任务链路从此硬依赖 Java。反过来 (先跑, 到时候
再说) 的失效方式更糟 —— Agent 正常回答, 用户看不出任何问题, 而库里
这个任务停在 PENDING、审计里一条 TASK_LIFECYCLE 都没有。

不用起真服务: 用 httpx.MockTransport 造响应, client 由测试注入。
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import httpx
import pytest
from fastapi import HTTPException

from app.main import _verify_task_identity

TASK_ID = "0123456789abcdef0123456789abcdef"
TRACE_ID = "cffac16e00000000cffac16e00000000"

SETTINGS = SimpleNamespace(
    km_task_service_url="http://java:8083",
    km_task_service_timeout_seconds=2.0,
)


def _client(handler) -> httpx.AsyncClient:
    return httpx.AsyncClient(transport=httpx.MockTransport(handler))


def _run(handler, trace_id: str = TRACE_ID) -> None:
    async def scenario() -> None:
        async with _client(handler) as client:
            await _verify_task_identity(SETTINGS, TASK_ID, trace_id, client=client)

    asyncio.run(scenario())


def _task_response(trace_id: str) -> httpx.Response:
    return httpx.Response(200, json={"agentTaskId": TASK_ID, "traceId": trace_id})


# ----------------------------------------------------------------------
#  通过
# ----------------------------------------------------------------------


def test_trace一致时放行() -> None:
    seen: dict = {}

    def handle(request: httpx.Request) -> httpx.Response:
        seen["url"] = str(request.url)
        seen["method"] = request.method
        return _task_response(TRACE_ID)

    _run(handle)

    assert seen["method"] == "GET"
    # 打的是任务查询接口 —— 换成别的路径就等于这道校验悄悄失效了
    assert seen["url"] == f"http://java:8083/api/tasks/{TASK_ID}"


# ----------------------------------------------------------------------
#  拒绝 —— 每一种都必须【抛出去】, 而不是记条日志继续跑
# ----------------------------------------------------------------------


def test_trace不一致返回409() -> None:
    with pytest.raises(HTTPException) as caught:
        _run(lambda request: _task_response("另一个trace"))

    assert caught.value.status_code == 409
    assert "执行身份不匹配" in caught.value.detail


def test_任务不存在返回404() -> None:
    with pytest.raises(HTTPException) as caught:
        _run(lambda request: httpx.Response(404, json={"error": "TASK_NOT_FOUND"}))

    assert caught.value.status_code == 404


def test_Java不可达返回502() -> None:
    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused")

    with pytest.raises(HTTPException) as caught:
        _run(refuse)

    # 502 而不是 500: 是上游不可用, 不是本服务自己的 bug —— 调用方据此
    # 决定是"重试"还是"报警", 两种处理完全不同。
    assert caught.value.status_code == 502
    assert "不可达" in caught.value.detail


def test_Java返回5xx返回502() -> None:
    with pytest.raises(HTTPException) as caught:
        _run(lambda request: httpx.Response(500, text="boom"))

    assert caught.value.status_code == 502


def test_响应不是合法JSON返回502() -> None:
    with pytest.raises(HTTPException) as caught:
        _run(lambda request: httpx.Response(200, text="<html>代理页</html>"))

    assert caught.value.status_code == 502


def test_响应里没有traceId按不匹配处理() -> None:
    """宁可疑, 不可放: 拿不到权威 trace 就等于无法确认身份。"""
    with pytest.raises(HTTPException) as caught:
        _run(lambda request: httpx.Response(200, json={"agentTaskId": TASK_ID}))

    assert caught.value.status_code == 409
