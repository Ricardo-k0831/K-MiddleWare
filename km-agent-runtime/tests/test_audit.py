"""AuditReporter / AuditCallbackHandler 的单测。

用 httpx.MockTransport 假装 Java 8083 的 /api/audit/events, 不需要起真服务、
不需要 docker、不需要 LLM。

这里验的是三条容易被改坏、改坏了又【不会立刻暴露】的性质:

  1. 事件 ID 由生产端生成且唯一 —— 它是消费端幂等去重的唯一依据
  2. 上报失败绝不抛异常 —— 审计是旁路, 不能反过来把 LLM 调用搞挂
  3. 挖不到的字段留空而不是填 0 —— 假的 0 会让统计静默偏低, 缺失看得见

接口形状 (字段名、layer/eventType 必填) 由 Java 侧的
AuditControllerTest 钉着, 这里不重复验。

不用 pytest-asyncio, 每个测试内部 asyncio.run —— 与 tests/test_task_state.py
保持一致。
"""

from __future__ import annotations

import asyncio
import json
import time

import httpx
from langchain_core.messages import AIMessage
from langchain_core.outputs import ChatGeneration, LLMResult

from app.audit import (
    ERROR,
    EVENT_LLM_CALL,
    EVENT_MCP_TOOL_CALL,
    LAYER,
    OK,
    TIMEOUT,
    AuditCallbackHandler,
    AuditReporter,
    status_for,
)

BASE_URL = "http://fake-java:8083"


class FakeJava:
    """假的 Java 审计入口: 记下收到的每一批事件。"""

    def __init__(self, status_code: int = 202, accepted_delta: int = 0) -> None:
        self.batches: list[list[dict]] = []
        # 每个请求的头也记下来 —— 认证 token 走的是头, 不记就验不了
        self.headers: list[httpx.Headers] = []
        self.status_code = status_code
        self.accepted_delta = accepted_delta

    def transport(self) -> httpx.MockTransport:
        def handle(request: httpx.Request) -> httpx.Response:
            batch = json.loads(request.content)
            self.batches.append(batch)
            self.headers.append(request.headers)
            received = len(batch)
            return httpx.Response(
                self.status_code,
                json={
                    # accepted_delta 用来模拟"Java 逐条映射时丢了某几条"
                    "accepted": max(received - self.accepted_delta, 0),
                    "received": received,
                },
            )

        return httpx.MockTransport(handle)

    def events(self) -> list[dict]:
        return [event for batch in self.batches for event in batch]


def make_reporter(
    fake: FakeJava, **kwargs: object
) -> tuple[AuditReporter, httpx.AsyncClient]:
    client = httpx.AsyncClient(transport=fake.transport())
    reporter = AuditReporter(
        BASE_URL,
        task_id="T-1",
        client=client,
        **kwargs,  # type: ignore[arg-type]
    )
    return reporter, client


# ----------------------------------------------------------------------
#  1. 事件 ID
# ----------------------------------------------------------------------


def test_每条事件都有自己的eventId() -> None:
    """唯一键去重的依据。撞了的后果是"真实事件被当成重投丢掉"。"""
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake)
        await reporter.start()
        for _ in range(3):
            reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())

    ids = [event["eventId"] for event in fake.events()]
    assert len(ids) == 3
    assert len(set(ids)) == 3, "事件 ID 撞了"
    # 和 Java 侧 AuditEvent.newEventId() 同形, 两端的 ID 会落进同一列
    assert all(len(i) == 32 and i == i.lower() for i in ids)


def test_eventId在入队那一刻就定了() -> None:
    """★ 这条是"重发去重"成立的前提 ★

    如果改成"发送时才生成", 那么任何一次重发都会得到新的 ID ——
    唯一键拦不住任何东西, 库里出现重复行, 而且看起来完全正常。
    """
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake)
        # 还没有 worker: 入队但发不出去
        reporter.emit(EVENT_LLM_CALL)
        # 直接看队列内部 —— 这个测试要断言的就是"入队那一刻 ID 已经定了",
        # 走公开接口反而看不到这一点。
        queued_id = reporter._queue._queue[0]["eventId"]
        await reporter.start()
        await reporter.aclose()
        await client.aclose()
        assert fake.events()[0]["eventId"] == queued_id

    asyncio.run(scenario())


# ----------------------------------------------------------------------
#  2. 绝不抛异常 —— 审计是旁路
# ----------------------------------------------------------------------


def test_java拒收时只记下来不抛() -> None:
    fake = FakeJava(status_code=500)

    async def scenario() -> int:
        reporter, client = make_reporter(fake)
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL)
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()  # ← 不该抛
        dropped = reporter.dropped
        await client.aclose()
        return dropped

    assert asyncio.run(scenario()) == 2


def test_提交时带上内部认证token() -> None:
    """X-KM-Audit-Token 是服务间认证, 不是信封字段。

    它不随事件走, 而是随 HTTP 请求走 —— 所以这里断言的是请求头,
    不是 batch 里的某个键。头名必须和 Java 的 AuditHeaders.INGEST_TOKEN
    逐字一致, 拼错一个字符的结果是每条审计都 401, 而 Python 侧只是
    "丢了", 看不出原因。
    """
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake, token="t-1")
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    assert fake.headers, "没有发出任何请求"
    assert fake.headers[0]["X-KM-Audit-Token"] == "t-1"


def test_没配token时不发空的认证头() -> None:
    # 发一个空串和"没发"在服务端是两回事: 后者能走"认证已关闭"的分支,
    # 前者会被当成"带了一个错 token"而 401。
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake)
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    assert "X-KM-Audit-Token" not in fake.headers[0]


def test_token不一致时只丢事件不抛异常() -> None:
    # 401 是配置问题, 不是报文问题。它必须和别的失败一样被吞掉 ——
    # 否则一个 token 配错会把 /chat 的正常响应也带崩。
    fake = FakeJava(status_code=401)

    async def scenario() -> int:
        reporter, client = make_reporter(fake, token="wrong")
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL)
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()
        dropped = reporter.dropped
        await client.aclose()
        return dropped

    assert asyncio.run(scenario()) == 2


def test_java不可达时worker不会被第一条打死() -> None:
    attempts: list[int] = []

    def refuse(request: httpx.Request) -> httpx.Response:
        attempts.append(len(json.loads(request.content)))
        raise httpx.ConnectError("connection refused")

    async def scenario() -> None:
        client = httpx.AsyncClient(transport=httpx.MockTransport(refuse))
        reporter = AuditReporter(BASE_URL, task_id="T-1", client=client)
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL)
        await asyncio.sleep(0)  # 让第一条真的失败一次
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    assert len(attempts) >= 2, "第一条的网络错误把 worker 打死了, 后面的再也没试过"


def test_不能序列化的那一条只丢它自己() -> None:
    """★ 一条坏数据不能带走一整批 ★

    如果等到发请求时才发现序列化不了, 那条坏事件已经和别的躺在同一批里了 ——
    结果是整批一起丢: 一条畸形入参带走 49 条好数据, 而且丢的是"那一批",
    不是"那一条"。所以在入队时就先序列化一次, 让坏的死在门口。
    """
    fake = FakeJava()

    async def scenario() -> int:
        reporter, client = make_reporter(fake)
        await reporter.start()
        # 一个 json 序列化不了的对象 (真实触发路径: 某个 provider 对象被混进 body)
        reporter.emit(EVENT_LLM_CALL, request_body={"weird": object()})
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()  # ← 不该抛
        dropped = reporter.dropped
        await client.aclose()
        return dropped

    assert asyncio.run(scenario()) == 1
    assert len(fake.events()) == 1, "坏的那条连累了好的一条"


def test_NaN不会毁掉整批() -> None:
    """Python 的 json 默认把 NaN 输出成非法 JSON, 而 Java 侧的 Jackson 会
    以 400 拒掉整个请求 —— 一个 NaN 就能带走一批审计。"""
    fake = FakeJava()

    async def scenario() -> tuple[int, int]:
        reporter, client = make_reporter(fake)
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL, latency_ms=None, token_in=float("nan"))
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()
        dropped = reporter.dropped
        await client.aclose()
        return dropped, len(fake.events())

    assert asyncio.run(scenario()) == (1, 1)


def test_部分丢失会被看见() -> None:
    """202 不代表全部收下。

    Java 是逐条映射逐条投的, 映射失败的单条会被丢掉并返回更小的 accepted。
    不看这个字段的话, "部分丢失"就永远只存在于服务端的日志里 ——
    而调用方会一直以为都记上了。
    """
    fake = FakeJava(accepted_delta=1)

    async def scenario() -> int:
        reporter, client = make_reporter(fake)
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL)
        reporter.emit(EVENT_LLM_CALL)
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()
        dropped = reporter.dropped
        await client.aclose()
        return dropped

    assert asyncio.run(scenario()) == 1


# ----------------------------------------------------------------------
#  3. 批量、排空
# ----------------------------------------------------------------------


def test_攒下的事件一次发完() -> None:
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake)
        # 入队时还没有 worker —— 模拟"一次 ReAct 循环里连着产生好几条"
        for _ in range(5):
            reporter.emit(EVENT_LLM_CALL)
        await reporter.start()
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    assert len(fake.batches) == 1, "5 条事件发了多个请求 —— 批量没生效"
    assert len(fake.batches[0]) == 5


def test_单批不超过上限() -> None:
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake, max_batch=4)
        for _ in range(10):
            reporter.emit(EVENT_LLM_CALL)
        await reporter.start()
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    assert all(len(batch) <= 4 for batch in fake.batches)
    assert sum(len(b) for b in fake.batches) == 10, "拆分过程中丢事件了"


def test_收尾会排空队列() -> None:
    """最后几条往往最有价值 (任务收尾那一刻的失败), 不能留在队列里。"""
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake)
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    assert len(fake.events()) == 1


def test_没启动过worker时收尾不报错() -> None:
    """aclose 在没有 start 的情况下必须是无害的空操作。

    /chat 里两条旁路的收尾是并发做的, 其中一条可能压根没建 (非任务链路),
    那时候收尾代码照样会被调用。
    """

    async def scenario() -> None:
        reporter, client = make_reporter(FakeJava())
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())


# ----------------------------------------------------------------------
#  4. 报文形状
# ----------------------------------------------------------------------


def test_信封字段名和Java侧逐字一致() -> None:
    """这一层是 HTTP, 没有编译期检查兜着 —— 键名就是契约本身。

    写错一个键名的后果不是报错, 而是那一列永远是 NULL。
    """
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(
            fake, trace_id="tr-1", tenant_id="km", actor="u-1"
        )
        await reporter.start()
        reporter.emit(
            EVENT_LLM_CALL,
            latency_ms=1840,
            token_in=812,
            token_out=96,
            request_body={"model": "deepseek-chat"},
            response_body={"finishReason": "stop"},
        )
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    event = fake.events()[0]
    assert set(event) == {
        "eventId", "layer", "eventType", "status", "occurredAt",
        "taskId", "traceId", "tenantId", "actor",
        "latencyMs", "tokenIn", "tokenOut", "requestBody", "responseBody",
    }
    assert event["layer"] == LAYER
    assert event["eventType"] == EVENT_LLM_CALL
    assert event["status"] == OK
    assert (event["tokenIn"], event["tokenOut"]) == (812, 96)


def test_没给的字段不发出去_而不是发一个null() -> None:
    """Java 侧对"缺失"和"null"一视同仁, 但发 None 出去容易在下一步被
    某个"把 None 变成字符串 'None'"的代码悄悄毁掉。少发几个字节更省心。"""
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake)
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL)
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    event = fake.events()[0]
    assert "traceId" not in event
    assert "tenantId" not in event
    assert "actor" not in event
    assert "latencyMs" not in event
    assert "tokenIn" not in event
    # 这两个【必须】有: 缺了会被 Java 侧 400 挡掉
    assert event["layer"] and event["eventType"]


def test_发生时刻用epoch毫秒() -> None:
    """跨语言只用数字时间, 不用格式化字符串 —— 字符串要约定时区和格式,
    约定错了就是几小时的系统性偏差, 而数字没有解释空间。"""
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake)
        await reporter.start()
        reporter.emit(EVENT_LLM_CALL, occurred_at=1_750_000_000.5)
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    assert fake.events()[0]["occurredAt"] == 1_750_000_000_500


# ----------------------------------------------------------------------
#  status 的翻译规则
# ----------------------------------------------------------------------


def test_没异常就是OK() -> None:
    assert status_for(None) == OK


def test_超时单独判成TIMEOUT() -> None:
    """超时不算"错", 算"慢到没法用" —— 排查方向完全不同, 合并就再也拆不开。"""
    assert status_for(TimeoutError("timed out")) == TIMEOUT
    assert status_for(httpx.ReadTimeout("read timeout")) == TIMEOUT


def test_包装在里面的超时也能认出来() -> None:
    """langchain 和 MCP 客户端都会包装异常, 直接被抛出的那个往往什么也说明不了。"""
    wrapped = RuntimeError("tool failed")
    wrapped.__cause__ = httpx.ConnectTimeout("connect timed out")
    assert status_for(wrapped) == TIMEOUT


def test_其他异常一律ERROR() -> None:
    # 注意: 这和网关的 CallStatus 不同 —— 那边 4xx 算 OK。理由见 audit.status_for
    assert status_for(RuntimeError("boom")) == ERROR
    assert status_for(httpx.ConnectError("refused")) == ERROR


# ----------------------------------------------------------------------
#  AuditCallbackHandler: LangChain 事件 -> 审计事件
# ----------------------------------------------------------------------


class RecordingReporter(AuditReporter):
    """只记 emit 的参数, 不走网络。

    回调处理器要验的是"它上报了什么", 而不是"它怎么发出去" ——
    后者上面已经验过了, 在同一个测试里叠两层真逻辑只会让失败信息变模糊。
    """

    def __init__(self) -> None:
        super().__init__(BASE_URL, task_id="T-1")
        self.emitted: list[tuple[str, dict]] = []

    def emit(self, event_type: str, *, status: str = OK, **fields: object) -> None:
        # 记下来的 status 是【生效后】的值, 不是"传进来有没有这个参数" ——
        # 断言关心的是最终会写进库的那一行, 而不是调用点省了几个字。
        self.emitted.append((event_type, {"status": status, **fields}))

    def of(self, event_type: str) -> list[dict]:
        return [f for t, f in self.emitted if t == event_type]


def _llm_result(
    *,
    usage_metadata: dict | None = None,
    token_usage: dict | None = None,
    model: str | None = None,
    finish_reason: str | None = None,
) -> LLMResult:
    message = AIMessage(content="hi", usage_metadata=usage_metadata)
    generation = ChatGeneration(
        message=message,
        generation_info={"finish_reason": finish_reason} if finish_reason else {},
    )
    llm_output: dict = {}
    if token_usage:
        llm_output["token_usage"] = token_usage
    if model:
        llm_output["model_name"] = model
    return LLMResult(generations=[[generation]], llm_output=llm_output)


def test_LLM调用记下耗时和token用量() -> None:
    """token 用量只有这一层拿得到 —— 网关和任务服务都看不见它。"""
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter, default_model="deepseek-chat")

    async def scenario() -> None:
        await handler.on_chat_model_start({}, [[]], run_id="run-1")
        await asyncio.sleep(0.01)
        await handler.on_llm_end(
            _llm_result(
                usage_metadata={
                    "input_tokens": 120,
                    "output_tokens": 18,
                    "total_tokens": 138,
                },
                model="deepseek-chat",
                finish_reason="stop",
            ),
            run_id="run-1",
        )

    asyncio.run(scenario())
    (event,) = reporter.of(EVENT_LLM_CALL)
    assert event["token_in"] == 120
    assert event["token_out"] == 18
    assert event["latency_ms"] >= 10
    assert event["request_body"]["model"] == "deepseek-chat"
    assert event["response_body"]["finishReason"] == "stop"


def test_没有标准位置时从供应商字段里挖token() -> None:
    """DeepSeek 走 OpenAI 兼容协议, 用量在 llm_output.token_usage 里。"""
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)

    async def scenario() -> None:
        await handler.on_llm_start({}, ["hi"], run_id="run-1")
        await handler.on_llm_end(
            _llm_result(token_usage={"prompt_tokens": 7, "completion_tokens": 3}),
            run_id="run-1",
        )

    asyncio.run(scenario())
    (event,) = reporter.of(EVENT_LLM_CALL)
    assert (event["token_in"], event["token_out"]) == (7, 3)


def test_挖不到token时留空而不是填0() -> None:
    """填 0 会让"这个月用了多少 token"这种统计【静默偏低】——
    偏低比缺失危险得多, 因为缺失看得见。"""
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)

    async def scenario() -> None:
        await handler.on_llm_start({}, ["hi"], run_id="run-1")
        await handler.on_llm_end(_llm_result(), run_id="run-1")

    asyncio.run(scenario())
    (event,) = reporter.of(EVENT_LLM_CALL)
    assert event["token_in"] is None
    assert event["token_out"] is None


def test_模型名挖不到时回落到配置() -> None:
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter, default_model="deepseek-chat")

    async def scenario() -> None:
        await handler.on_llm_start({}, ["hi"], run_id="run-1")
        await handler.on_llm_end(_llm_result(), run_id="run-1")

    asyncio.run(scenario())
    assert reporter.of(EVENT_LLM_CALL)[0]["request_body"]["model"] == "deepseek-chat"


def test_模型名挖不到也没有配置时就是None() -> None:
    """不许在这里编一个默认值 —— 编出来的值没有任何东西能证伪。"""
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)

    async def scenario() -> None:
        await handler.on_llm_start({}, ["hi"], run_id="run-1")
        await handler.on_llm_end(_llm_result(), run_id="run-1")

    asyncio.run(scenario())
    assert reporter.of(EVENT_LLM_CALL)[0]["request_body"]["model"] is None


def test_LLM失败记ERROR并留下原因() -> None:
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)

    async def scenario() -> None:
        await handler.on_llm_start({}, ["hi"], run_id="run-1")
        await handler.on_llm_error(RuntimeError("额度超限"), run_id="run-1")

    asyncio.run(scenario())
    (event,) = reporter.of(EVENT_LLM_CALL)
    assert event["status"] == ERROR
    assert "额度超限" in event["error_msg"]


def test_工具调用记下工具名和入参() -> None:
    """键名和网关那条保持一致 (tool / params), 两层按 trace_id 拼的时候
    不需要先做一次字段名映射。"""
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)

    async def scenario() -> None:
        await handler.on_tool_start(
            {"name": "get_order_by_id"}, '{"orderId": "SO1"}', run_id="tool-1"
        )
        await handler.on_tool_end("已发货", run_id="tool-1")

    asyncio.run(scenario())
    (event,) = reporter.of(EVENT_MCP_TOOL_CALL)
    assert event["request_body"] == {"tool": "get_order_by_id", "params": {"orderId": "SO1"}}
    assert event["status"] == OK
    # 响应体留给网关那条 —— 各记一半, 拼起来才是完整的一次调用
    assert "response_body" not in event


def test_工具入参不是JSON时原样留一小段() -> None:
    """那说明 Agent 传了个畸形的东西 —— 这本身就是值得记下来的事实, 不该被丢掉。"""
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)

    async def scenario() -> None:
        await handler.on_tool_start({"name": "t"}, "不是 json", run_id="tool-1")
        await handler.on_tool_end(None, run_id="tool-1")

    asyncio.run(scenario())
    params = reporter.of(EVENT_MCP_TOOL_CALL)[0]["request_body"]["params"]
    assert params == {"raw": "不是 json"}


def test_工具失败记ERROR但不代表任务失败() -> None:
    """★ 这条守的是一个语义, 不是一个数字 ★

    工具抛错时 ReAct 循环会继续跑, 任务完全可能成功 (见 app/task_state.py
    里那段 ★ 注释)。所以这条记录的主张只是"这一次工具调用失败了" ——
    任务失没失败去看 TASK_LIFECYCLE 那条。
    """
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)

    async def scenario() -> None:
        await handler.on_tool_start({"name": "get_order_by_id"}, "{}", run_id="tool-1")
        await handler.on_tool_error(RuntimeError("后端 500"), run_id="tool-1")

    asyncio.run(scenario())
    (event,) = reporter.of(EVENT_MCP_TOOL_CALL)
    assert event["status"] == ERROR
    assert event["request_body"]["tool"] == "get_order_by_id"
    assert "后端 500" in event["error_msg"]


def test_交错的调用各配各的run_id() -> None:
    """一个 ReAct 循环里 LLM 调用和工具调用是交错的。

    如果按"上一次的 start"计时, 得到的耗时会张冠李戴 —— 而且数值看起来
    完全合理, 没有任何东西会报警。
    """
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)

    async def scenario() -> None:
        await handler.on_llm_start({}, ["hi"], run_id="llm")
        await handler.on_tool_start({"name": "t"}, "{}", run_id="tool")
        await asyncio.sleep(0.01)
        await handler.on_tool_end("out", run_id="tool")
        await asyncio.sleep(0.01)
        await handler.on_llm_end(_llm_result(), run_id="llm")

    asyncio.run(scenario())
    tool_ms = reporter.of(EVENT_MCP_TOOL_CALL)[0]["latency_ms"]
    llm_ms = reporter.of(EVENT_LLM_CALL)[0]["latency_ms"]
    assert llm_ms > tool_ms, "耗时配错了 run_id"


def test_没有start时耗时留空而不是0() -> None:
    """填 0 是谎报 (看起来像"瞬间完成")。缺失看得见, 假的 0 看不见。"""
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)

    async def scenario() -> None:
        # 直接 end, 没有对应的 start (例如 handler 是半路挂上去的)
        await handler.on_llm_end(_llm_result(), run_id="orphan")

    asyncio.run(scenario())
    event = reporter.of(EVENT_LLM_CALL)[0]
    assert event["latency_ms"] is None
    # 没有开始时刻就不传 occurred_at, 由 emit() 取当前时刻兜底 ——
    # 拿不到的时侯写"现在"至少不是个错的值, 编一个才是。
    assert "occurred_at" not in event


def test_事件的occurredAt是开始时刻而不是结束时刻() -> None:
    """★ 这条守的是事件时间的【定义】, 不是某个具体数值 ★

    occurred_at 说的是"这件事什么时候发生的", 不是"这条记录什么时候被写下
    来的"。一次 500ms 的工具调用, 按结束时刻记的话, 这一行会比网关上同一件
    调用的那行晚半秒 —— 排出来的时间线成了"网关先收到、Agent 后发起"。

    这个错的样子很讨厌: 单独看每一层都正常, 只有把两层并排看才露馅。
    第一版就是按结束时刻写的, 端到端跑出来才看见 (AGENT_RUNTIME 那条比
    MCP_GATEWAY 那条晚 25ms)。所以按开始时刻钉住 —— 和网关那条
    MCP_TOOL_CALL 的 startedAt 语义对齐, 两层对同一件事的 occurred_at
    应当几乎相等, 那才是个能用的信号。
    """
    reporter = RecordingReporter()
    handler = AuditCallbackHandler(reporter)
    marks: dict[str, float] = {}

    async def scenario() -> None:
        await handler.on_tool_start({"name": "t"}, "{}", run_id="tool-1")
        marks["after_start"] = time.time()
        await asyncio.sleep(0.05)
        await handler.on_tool_end("out", run_id="tool-1")

    asyncio.run(scenario())
    (event,) = reporter.of(EVENT_MCP_TOOL_CALL)
    # 开始时刻: 不晚于 start 之后立刻取的那一刻 (结束时刻必然晚于它)
    assert event["occurred_at"] <= marks["after_start"]
    # 顺带确认这确实是同一次调用, 不是串了 run_id
    assert event["latency_ms"] >= 50


def test_从队列里跑完一整轮也不会漏事件() -> None:
    """端到端一点: 真 reporter + 真 handler + 假 Java。

    上面那些用的是 RecordingReporter (不走网络), 这条补上"事件真的落到
    HTTP 报文里"这一环 —— 中间任何一段断掉, 表现都是"接口 200, 审计为空"。
    """
    fake = FakeJava()

    async def scenario() -> None:
        reporter, client = make_reporter(fake, trace_id="tr-1")
        handler = AuditCallbackHandler(reporter, default_model="deepseek-chat")
        await reporter.start()
        await handler.on_chat_model_start({}, [[]], run_id="llm-1")
        await handler.on_llm_end(
            _llm_result(usage_metadata={"input_tokens": 5, "output_tokens": 2, "total_tokens": 7}),
            run_id="llm-1",
        )
        await handler.on_tool_start({"name": "get_order_by_id"}, "{}", run_id="tool-1")
        await handler.on_tool_end("out", run_id="tool-1")
        await reporter.aclose()
        await client.aclose()

    asyncio.run(scenario())
    events = fake.events()
    assert [e["eventType"] for e in events] == [EVENT_LLM_CALL, EVENT_MCP_TOOL_CALL]
    assert all(e["traceId"] == "tr-1" for e in events)
    assert all(e["layer"] == LAYER for e in events)
