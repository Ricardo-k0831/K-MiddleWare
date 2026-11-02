"""心跳 (push) 与探活 (pull) 这两个方向各自的单元测试。

配套设计: docs/task-state-machine.md「心跳兜底」一节; 实现: app/heartbeat.py。

## 为什么这里单独成文件

`tests/test_chat_task_wiring.py` 验的是"/chat 有没有把心跳接上", 那里只关心
"建了吗、停了吗、身份对不对"。**判死开关本身** —— 4xx 该停、5xx 该继续、
探活只在明确 false 时才算死 —— 是另一类问题: 写反一个 `>=` 就会把全量
执行在第一次网络抖动时停掉心跳, 而 /chat 的返回值一个字节都不会变。

## 不用 pytest-asyncio

和其余测试保持一致: 每个用例内部 asyncio.run。
"""

from __future__ import annotations

import asyncio
import json

import httpx
import pytest

import app.main as main_module
from app.heartbeat import HeartbeatBeater, LivenessRegistry

TASK_ID = "0123456789abcdef0123456789abcdef"
TRACE_ID = "cffac16e00000000cffac16e00000000"


class Recorder:
    """记录每一次心跳请求, 并按用例返回指定状态码。"""

    def __init__(self, status_code: int = 204) -> None:
        self.status_code = status_code
        self.requests: list[httpx.Request] = []

    def transport(self) -> httpx.MockTransport:
        def handle(request: httpx.Request) -> httpx.Response:
            self.requests.append(request)
            return httpx.Response(self.status_code)

        return httpx.MockTransport(handle)


# ----------------------------------------------------------------------
#  LivenessRegistry —— Java 探活问的就是它
# ----------------------------------------------------------------------


def test_登记之后才算活着_撤销之后立刻不算():
    registry = LivenessRegistry()

    assert not registry.is_alive(TRACE_ID)
    registry.register(TRACE_ID)
    assert registry.is_alive(TRACE_ID)
    registry.unregister(TRACE_ID)
    assert not registry.is_alive(TRACE_ID)


def test_撤销不存在的trace是安全的():
    """重复请求、异常路径都会走到这里 —— 不该抛。"""
    registry = LivenessRegistry()
    registry.unregister("从没登记过")


def test_同一个trace并发两次时_先结束的不算结束():
    """★ 判活宁可多答 alive, 不能早答 false ★

    客户端超时重发 /chat 会出现同一个 trace 跑两份。第一份结束时如果直接
    删登记, Java 探活立刻得到 false 并关链, 而另一份还在跑 —— 结果是一条
    还在干活的任务被判死, 并触发一次多余的重试。
    """
    registry = LivenessRegistry()
    registry.register(TRACE_ID)
    registry.register(TRACE_ID)

    registry.unregister(TRACE_ID)
    assert registry.is_alive(TRACE_ID), "第一份结束了就把整条 trace 判死了"

    registry.unregister(TRACE_ID)
    assert not registry.is_alive(TRACE_ID), "最后一份结束了却还答 alive"


def test_已运行秒数从登记时刻算起():
    registry = LivenessRegistry()
    registry.register(TRACE_ID)

    elapsed = registry.elapsed_seconds(TRACE_ID)
    assert elapsed is not None and elapsed >= 0
    assert registry.elapsed_seconds("别的trace") is None


# ----------------------------------------------------------------------
#  HeartbeatBeater —— push 方向
# ----------------------------------------------------------------------


def _beater(
    recorder: Recorder,
    *,
    interval: float = 15.0,
    client: httpx.AsyncClient | None = None,
) -> HeartbeatBeater:
    return HeartbeatBeater(
        "http://java.test/",
        TASK_ID,
        trace_id=TRACE_ID,
        interval=interval,
        client=client or httpx.AsyncClient(transport=recorder.transport()),
    )


def test_心跳打到正确的地址且带着本次执行的trace():
    recorder = Recorder()

    async def scenario() -> None:
        beater = _beater(recorder)
        await beater.start()
        # 让后台协程跑起来: 它先打一次心跳再进入 sleep。
        await asyncio.sleep(0)
        await asyncio.sleep(0)
        await beater.aclose()
        await beater._client.aclose()

    asyncio.run(scenario())

    assert recorder.requests, "一次心跳都没发"
    request = recorder.requests[0]
    # base_url 结尾的斜杠被吃掉了 —— 双斜杠在某些网关上是 404。
    assert request.url.path == f"/api/tasks/{TASK_ID}/heartbeat"
    assert json.loads(request.content) == {"traceId": TRACE_ID}


def test_没有trace_id时拒绝构造():
    """心跳必须标明是哪一次执行。猜 trace 等于给别人的执行续命。"""
    with pytest.raises(ValueError):
        HeartbeatBeater("http://java.test", TASK_ID, trace_id="")


def test_4xx表示别再跳了():
    """409/404 的含义是"这次执行已经不该有心跳了"。

    继续发只会每 15 秒往 Java 日志里丢一条 WARN, 而且说明双方的
    执行身份已经对不上了 —— 越跳越误导。
    """
    recorder = Recorder(status_code=409)
    client = httpx.AsyncClient(transport=recorder.transport())

    async def scenario() -> bool:
        try:
            return await _beater(recorder, client=client)._beat(client)
        finally:
            await client.aclose()

    assert asyncio.run(scenario()) is True


def test_5xx只是暂时失败_继续跳():
    """Java 重启、数据库抖动都会 5xx。这时候停掉心跳, 等于自己放弃了
    唯一能让 Java 知道"我还活着"的通道。"""
    recorder = Recorder(status_code=503)
    client = httpx.AsyncClient(transport=recorder.transport())

    async def scenario() -> bool:
        try:
            return await _beater(recorder, client=client)._beat(client)
        finally:
            await client.aclose()

    assert asyncio.run(scenario()) is False


def test_连不上Java时吞掉异常并继续():
    """旁路坏了不能把主流程搞坏 —— 和状态上报/审计是同一条规矩。"""

    def refuse(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("Connection refused", request=request)

    client = httpx.AsyncClient(transport=httpx.MockTransport(refuse))
    beater = HeartbeatBeater(
        "http://java.test", TASK_ID, trace_id=TRACE_ID,
        interval=15.0, client=client,
    )

    async def scenario() -> bool:
        try:
            return await beater._beat(client)
        finally:
            await client.aclose()

    assert asyncio.run(scenario()) is False


def test_收到4xx后后台循环自己结束():
    """不是"下一次再判断", 而是直接退出 —— 否则要等一整轮才会消停。"""
    recorder = Recorder(status_code=404)

    async def scenario() -> bool:
        beater = _beater(recorder)
        await beater.start()
        try:
            # 循环退出后 worker 正常完成; 1 秒等不到就是没退 (interval 是 15s,
            # 若判断写错, 这里会稳稳地超时)。
            await asyncio.wait_for(beater._worker, timeout=1.0)
            return True
        except TimeoutError:
            return False
        finally:
            await beater.aclose()
            await beater._client.aclose()

    assert asyncio.run(scenario()) is True


def test_重复start不会开出第二个协程():
    """重试/重入都可能调到 start(); 两个协程 = 双倍心跳。"""
    recorder = Recorder()

    async def scenario() -> None:
        beater = _beater(recorder)
        await beater.start()
        worker = beater._worker
        await beater.start()
        assert beater._worker is worker
        await beater.aclose()
        await beater._client.aclose()

    asyncio.run(scenario())


def test_aclose之后再aclose是安全的():
    recorder = Recorder()

    async def scenario() -> None:
        beater = _beater(recorder)
        await beater.start()
        await beater.aclose()
        await beater.aclose()
        await beater._client.aclose()

    asyncio.run(scenario())


def test_间隔有下限保护():
    """配置成 0 会把 Java 打成每秒几十次请求, 而且看起来像被攻击。"""
    beater = HeartbeatBeater(
        "http://java.test", TASK_ID, trace_id=TRACE_ID, interval=0,
        client=httpx.AsyncClient(transport=Recorder().transport()),
    )

    assert beater._interval >= 1.0
    asyncio.run(beater._client.aclose())


# ----------------------------------------------------------------------
#  GET /tasks/{trace}/alive —— pull 方向
# ----------------------------------------------------------------------


def _get_alive(trace_id: str) -> dict:
    async def _go() -> dict:
        transport = httpx.ASGITransport(app=main_module.app)
        async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
            response = await client.get(f"/tasks/{trace_id}/alive")
            assert response.status_code == 200
            return response.json()

    return asyncio.run(_go())


def test_没登记过的trace回答没在跑():
    body = _get_alive("trace-从没登记过")

    assert body["alive"] is False
    # None 而不是 0: 0 秒会被读成"刚开始跑", 那正好是相反的结论。
    assert body["elapsed_seconds"] is None


def test_登记中的trace回答在跑并带上已运行时间():
    trace_id = "trace-liveness-endpoint"
    main_module.LIVENESS.register(trace_id)
    try:
        body = _get_alive(trace_id)
    finally:
        main_module.LIVENESS.unregister(trace_id)

    assert body["alive"] is True
    assert body["elapsed_seconds"] is not None and body["elapsed_seconds"] >= 0
