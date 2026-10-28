"""审计上报 —— AGENT_RUNTIME 那一层。

配套设计文档: docs/audit.md

## 和 task_state.py 什么关系

两者都是"Python 主动回调 Java 8083", 但**不是同一件事**, 别合并:

|              | task_state.py            | 这里                        |
|--------------|--------------------------|-----------------------------|
| 记的是什么   | 任务【当前状态】          | 发生过的【事件】            |
| 一条代替一条? | 是 (状态是覆盖语义)      | 否 (事件是追加语义)         |
| 顺序要紧吗   | 要紧 —— 状态机靠顺序      | 不要紧 —— 各有各的时间戳     |
| 量级         | 一单几条                 | 一单几十到几百条 (每次都报)  |
| 丢了怎么办   | 严重: Java 会以为还在跑   | 可接受: 审计【可以丢】       |

最后一行是设计上的分水岭。状态上报丢了要重试、要等排空; 审计上报丢了就丢了,
重试一次不值得 —— 而**"可以丢"正是它允许异步、允许批量、允许失败静默的
全部理由**。反过来说, 谁要是把审计改成"必须送达", 就同时把旁路变成了主路。

## 为什么事件 ID 由 Python 生成

`audit_log` 上 `event_id` 是唯一键, 消费端靠它做幂等去重 (RocketMQ 保证的是
"至少一次", 重投是常态)。ID 只有**在观察到这件事的那一刻**定下来才有意义 ——
生成得越早, 重投能拦住的窗口就越长。

所以 Python 生成 ID, Java 侧原样透传, 一路到数据库。假如改成"Java 收到时
再生成", 那么 HTTP 重发一次就会多一条重复审计, 而且**看起来完全正常**,
没有任何东西会报警。见 `AuditEvent.Builder#eventId` 的注释。

ID 的形态 (`uuid4().hex`, 32 位小写十六进制) 和 Java 侧的
`AuditEvent.newEventId()` 逐字一致 —— 两端的 ID 最终躺进同一张表的同一列,
形态不一致查起来会很难受。

## 为什么批量

一轮 ReAct 对话里 LLM 调用少则两次多则十几次, 每次都单发一个 POST 的话,
审计的 HTTP 开销会赶上它要记录的业务本身。所以: 队列 + 后台 worker,
worker 每次醒来把队列里现成的取走, 一趟发完。

## 分层采样不在这里做

采样在 Java 侧 (`AuditProperties#sampleRate`, 按层配)。Python 只管报,
不管该不该报 —— 采样策略只有一份实现, 改一次两边都生效, 不会出现
"Java 以为只采 10%, Python 还在全量"这种对不上的局面。

## 认证: X-KM-Audit-Token

`/api/audit/events` 是服务间接口, Java 侧会校验一个共享 token。它是
**能不能写**的问题, 和信封里的 task_id / trace_id (写的是**谁的事**) 分开:

* token 管入口 —— 没有它, 任何能访问 8083 的进程都能伪造一条"任务已完成"
* task_id / trace_id 管归属 —— Java 拿 trace_id 去 `agent_task_attempt`
  核对, 矛盾的事件一律拒收

两者缺一不可: 只有 token 时, 一个配错的 trace_id 仍然会安静地把整条链路
挂到别的任务下; 只有身份核对时, 伪造者可以编一个自洽的 (task, trace) 对。

token 对不上时服务端回 **401** (而不是 400) —— 那是"配置不一致"的信号,
和"报文写错了"要分开看。这里单独判一下, 是为了让日志直接说清该改哪边。
"""

from __future__ import annotations

import asyncio
import json
import logging
import time
import uuid
from typing import Any

import httpx
from langchain_core.callbacks import AsyncCallbackHandler

logger = logging.getLogger(__name__)

# ----------------------------------------------------------------------
#  跨语言常量 —— 必须和 Java 侧逐字一致
# ----------------------------------------------------------------------

LAYER = "AGENT_RUNTIME"

EVENT_LLM_CALL = "LLM_CALL"
EVENT_MCP_TOOL_CALL = "MCP_TOOL_CALL"

# 与 com.km.audit.AuditEvent 里的四个常量一致。
#
# ★ 这四个值在四层里是同一套语义: status 回答"算不算【系统】故障" ★
#
# 最容易搞错的一处: 任务最终失败 (Java 侧 to=FAILED) 的那些事件,
# 它们的 status 仍然是 OK —— 因为"订单不存在"和"系统坏了"是两件事,
# 而 status 只回答后一个。任务失没失败去看 TASK_LIFECYCLE 事件的
# request_body.to。四层一致, "按 status 算错误率" 这件事才有意义。
OK = "OK"
ERROR = "ERROR"
TIMEOUT = "TIMEOUT"

# input_str 不是 JSON 时最多存多少个字符。截断是为了给"这个值到底长什么样"
# 留个线索, 而不是为了完整保存 —— 完整保存会变成另一条不受控的数据通路。
_RAW_INPUT_LIMIT = 500


def status_for(exc: BaseException | None) -> str:
    """把异常翻译成 audit_log.status。

    ## 为什么这里和网关的 CallStatus 规则【不一样】

    网关那边 4xx 算 OK (后端明确回答"没有这个订单", 是成功的一次调用)。
    这里不能照搬: 运行时的前端只有我们自己的 MCP 网关, 任何非 2xx 都意味着
    **管道坏了** (token 过期、URL 配错、网关没起来) —— 没有"业务上正常的
    4xx"这种东西。

    照抄一条规则而不理解它的理由, 就会得到这种"看起来对齐、实际反了"的代码。

    ## 超时单独判

    超时不算"错", 算"慢到没法用" —— 两者的排查方向完全不同 (一个看报错,
    一个看容量)。merge 成一个 ERROR 就再也拆不开了。
    """
    if exc is None:
        return OK

    # 沿 __cause__ / __context__ 走一遍: langchain 和 MCP 客户端都会包装异常,
    # 直接被抛出的那个往往是 "ToolException: ..." 之类, 真正的类型埋在下面。
    current: BaseException | None = exc
    for _ in range(32):
        if current is None:
            break
        if isinstance(current, (TimeoutError, httpx.TimeoutException)):
            return TIMEOUT
        nxt = current.__cause__ or current.__context__
        current = nxt if nxt is not current else None
    return ERROR


class AuditReporter:
    """把审计事件异步、批量地上报给 Java (POST /api/audit/events)。

    生命周期: start() -> 任意多次 emit() -> aclose()

    和 TaskStateReporter 一样是**每轮对话一个实例**, 因为它绑着一份任务身份。
    常驻的那部分开销 (httpx 客户端、worker) 都在实例里, 一单一次, 可以忽略。
    """

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
        client: httpx.AsyncClient | None = None,
        max_batch: int = 50,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        # 服务间认证头。为空时不发 —— 服务端若配了 token 会回 401, 这比在这里
        # 抛异常好: 审计是旁路, 构造 reporter 失败会反过来把 /chat 搞挂。
        self._headers = {"X-KM-Audit-Token": token} if token else {}
        # 这四个是【任务身份】, 由调用方从 POST /api/tasks 的响应里转交。
        # Python 不生成、不推断、不补默认值 —— 理由见 app/main.py 里
        # ChatRequest.trace_id 那段注释。
        self._task_id = task_id
        self._trace_id = trace_id
        self._tenant_id = tenant_id
        self._actor = actor
        self._timeout = timeout
        # 允许注入 client, 测试时塞 MockTransport 进来, 不用起真服务
        self._client = client
        # 一次 POST 最多带多少条。攒得太多会让单次请求又大又慢, 失败时丢的
        # 也多 —— 50 条是个"一次 ReAct 循环也就这么多"的量级。
        self._max_batch = max_batch
        self._queue: asyncio.Queue[dict[str, Any]] = asyncio.Queue()
        self._worker: asyncio.Task[None] | None = None
        self._dropped = 0

    # ------------------------------------------------------------------
    #  对外
    # ------------------------------------------------------------------

    def emit(
        self,
        event_type: str,
        *,
        status: str = OK,
        latency_ms: int | None = None,
        token_in: int | None = None,
        token_out: int | None = None,
        request_body: dict[str, Any] | None = None,
        response_body: dict[str, Any] | None = None,
        error_msg: str | None = None,
        occurred_at: float | None = None,
    ) -> None:
        """入队一条事件。

        **同步、非阻塞、不抛异常** —— 它会被 on_llm_end / on_tool_end 这类
        跑在关键路径上的钩子调用, 在那里 await 一个网络请求是不可接受的。

        `occurred_at` 是**事件发生的时刻**, 不是发送的时刻, 也**不是结束的
        时刻** —— 一次调用要记的是它【什么时候开始的】。这里的三个"不是"
        都有具体的踩坑来源:

        * 不是发送时刻: 批量意味着最多会晚 max_batch 条才发出去, 用它的话
          时间线整体后移, 落库顺序和真实顺序对不上。
        * 不是结束时刻: 一次 500ms 的工具调用会因此比网关上同一件调用的那行
          晚半秒, 排出来的时间线变成"网关先收到、Agent 后发起"。
          这个错单独看每一层都正常, 只有并排看两层才露馅。

        所以回调通过 `_close(run_id)` 把开始时刻一起带回来 (语义和网关那条
        MCP_TOOL_CALL 的 startedAt 对齐)。默认取现在只是兜底 —— 拿不到开始
        时刻时, 写现在至少不会写一个错的值。
        """
        body = self._event_body(
            event_type,
            status=status,
            latency_ms=latency_ms,
            token_in=token_in,
            token_out=token_out,
            request_body=request_body,
            response_body=response_body,
            error_msg=error_msg,
            occurred_at=occurred_at,
        )

        # ★ 在这里先序列化一次, 就是为了【让坏的那一条死在门口】★
        #
        # 不这么做的话, 坏事件会在 httpx 组装请求时炸 —— 而那时它已经和别的
        # 事件躺在同一批里了, 结果是【整批一起丢】: 一条畸形的入参能带走 49 条
        # 好数据, 而且丢的是"那一批", 不是"那一条"。
        #
        # 代价是每条事件多一次 json.dumps。这些 body 只有几个键, 微秒级,
        # 而每次 LLM 调用是几百毫秒 —— 值得。
        #
        # allow_nan=False 是顺手捡到的: Python 的 json 默认把 NaN/Infinity
        # 输出成 NaN 这种【非法 JSON】, 而 Java 侧的 Jackson 会以 400 拒掉
        # 整个请求。这里拦住, 就等于不让一个 NaN 把一批审计带走。
        try:
            json.dumps(body, allow_nan=False)
        except (TypeError, ValueError):
            self._drop(1, f"报文无法序列化 (eventType={event_type})")
            return

        # 队列的 put_nowait 自己不抛; 上面那段是本模块的代码, 可能会 ——
        # 无论如何都不能让它冒到 on_llm_end 上, 那样一个审计 bug 会直接
        # 把 LLM 调用搞挂。
        try:
            self._queue.put_nowait(body)
        except Exception:  # noqa: BLE001 — 见上
            self._drop(1, f"入队失败 (eventType={event_type})")

    async def start(self) -> None:
        """启动后台 worker。重复调用是安全的。"""
        if self._worker is None:
            self._worker = asyncio.create_task(
                self._run(), name=f"audit-reporter-{self._task_id}"
            )

    async def aclose(self, drain_timeout: float = 2.0) -> None:
        """排空队列后停掉 worker。

        排空时间比状态上报短 (那边 5 秒): 审计丢得起, 而 /chat 的响应
        不该为了几条可丢的记录多等三秒。
        """
        if self._worker is None:
            return

        try:
            await asyncio.wait_for(self._queue.join(), timeout=drain_timeout)
        except TimeoutError:
            logger.warning(
                "审计上报队列未排空 (剩 %d 条), 直接停止 task=%s",
                self._queue.qsize(),
                self._task_id,
            )

        self._worker.cancel()
        try:
            await self._worker
        except asyncio.CancelledError:
            pass
        self._worker = None

    @property
    def dropped(self) -> int:
        """被丢弃的事件数。一直涨说明和 8083 之间出了问题。"""
        return self._dropped

    # ------------------------------------------------------------------
    #  内部
    # ------------------------------------------------------------------

    def _event_body(self, event_type: str, **fields: Any) -> dict[str, Any]:
        """按 Java DTO 的字段名组包。

        ★ 字段名要和 `AuditEventRequest` 一一对应 ★
        少一个字段的后果不是报错, 而是那一列永远是 NULL —— 而这一层是
        HTTP, 没有编译期检查兜着, 所以键名就是契约本身。

        值为 None 的字段直接不发: Java 侧对"缺失"和"null"一视同仁, 省下的
        体积乘以"每次 LLM 调用都发一次"还值一点。唯一不能缺的是 layer /
        eventType, 缺了会被 400 挡掉 (已实测, 见 AuditControllerTest)。

        任务身份那四个值为 None 时也会被丢掉 —— 那是**刻意的**:
        没给的字段不编造, 缺一个值是诚实的缺失, 值不一致才是谎言。
        """
        occurred = fields.pop("occurred_at", None)
        body: dict[str, Any] = {
            "eventId": uuid.uuid4().hex,
            "layer": LAYER,
            "eventType": event_type,
            "taskId": self._task_id,
            "traceId": self._trace_id,
            "tenantId": self._tenant_id,
            "actor": self._actor,
            "occurredAt": _epoch_millis(occurred if occurred is not None else time.time()),
        }
        for key, value in fields.items():
            if value is not None:
                body[_to_camel(key)] = value
        return {key: value for key, value in body.items() if value is not None}

    # 审计上报后台 worker 要执行的任务
    async def _run(self) -> None:
        owns_client = self._client is None
        client = self._client or httpx.AsyncClient(timeout=self._timeout)
        try:
            while True:
                # 1. 挂起等第一条 —— 没有事件时不空转
                batch = [await self._queue.get()]
                # 2. 把此刻【已经在队列里】的都捎上。不 await: 只拿现成的,
                #    凑不满也照发 —— 等下去就变成"攒批", 而攒批意味着
                #    长对话里最后几条要等很久才落库。
                while len(batch) < self._max_batch:
                    try:
                        batch.append(self._queue.get_nowait())
                    except asyncio.QueueEmpty:
                        break

                try:
                    await self._post(client, batch)
                except Exception as ex:  # noqa: BLE001 — 盲捕是刻意的, 见下
                    # ★ 吞掉。审计是旁路, 它失败不该让 Agent 崩。★
                    # 和 task_state.py 里那条是同一个原则。
                    self._drop(len(batch), f"上报失败: {ex}")
                finally:
                    for _ in batch:
                        self._queue.task_done()
        finally:
            if owns_client:
                await client.aclose()

    async def _post(self, client: httpx.AsyncClient, batch: list[dict[str, Any]]) -> None:
        response = await client.post(
            f"{self._base_url}/api/audit/events",
            json=batch,
            # 空 dict 时 httpx 不会额外做任何事, 所以不需要条件传参
            headers=self._headers,
        )
        if response.status_code == 401:
            # ★ 单独判 401 ★ 它不是"报文写错了", 而是"两边 token 不一致"。
            # 混在通用的 400 分支里, 排查的人会先去翻报文格式, 方向就错了。
            self._drop(
                len(batch),
                "提交被拒 HTTP 401 —— X-KM-Audit-Token 与 Java 侧 "
                "km.audit.ingest.token 不一致",
            )
            return
        if response.status_code >= 400:
            # 400 = 报文被校验挡了, 那是本模块的 bug (字段名写错 / 枚举值写错),
            # 值得让日志难看一下。202 之外的一切都当失败。
            self._drop(
                len(batch),
                f"提交被拒 HTTP {response.status_code}: {response.text[:200]}",
            )
            return

        # 202 不代表全部收下: Java 侧是逐条映射逐条投的, 映射失败的单条会被
        # 丢掉, 返回的 accepted 会小于 received。不查这个字段的话, "部分丢失"
        # 就永远只存在于服务端的日志里。
        try:
            result = response.json()
        except Exception:  # noqa: BLE001 — 响应体不是 JSON 不值得把整轮搞挂
            logger.warning("审计提交的响应不是 JSON: %s", response.text[:200])
            return

        accepted = result.get("accepted", len(batch))
        if accepted < len(batch):
            # rejected 是"身份核对没过"或"映射失败", 和"网络失败"不是一个
            # 排查方向 —— 把它一起打出来, 日志才直接指向原因。
            self._drop(
                len(batch) - accepted,
                f"服务端收下 {accepted} / 收到 {result.get('received')} 条 "
                f"(rejected={result.get('rejected')})",
            )
        # 收下了但身份没核实: 多半是 traceId 和任务服务那边对不上。
        # 不算丢事件, 所以不调用 _drop; 但必须留痕 —— 否则这个指标
        # 只会活在 Java 的计数里, 而 Python 这边"看起来一切正常"。
        if result.get("unverified"):
            logger.warning(
                "审计身份未核实 %s 条 task=%s —— 检查 traceId 是否来自任务创建响应",
                result.get("unverified"),
                self._task_id,
            )

    def _drop(self, count: int, reason: str) -> None:
        self._dropped += count
        logger.warning(
            "审计事件被丢弃 %d 条 task=%s: %s", count, self._task_id, reason
        )


def _epoch_millis(seconds: float) -> int:
    """秒 (float) -> epoch 毫秒 (int)。

    跨语言只用 epoch 毫秒, 不用格式化字符串 —— 字符串要约定时区和格式,
    约定错了就是几小时的系统性偏差, 而数字没有解释空间。
    """
    return int(seconds * 1000)


def _to_camel(snake: str) -> str:
    """token_in -> tokenIn。

    只在键名对不上时才用得上, 但写死一张映射表更容易漏 (漏一个的后果是
    那一列永远为空)。这里靠命名规则推, 规则只有一条, 一眼能看完。
    """
    head, *rest = snake.split("_")
    return head + "".join(part.title() for part in rest)


# ======================================================================
#  LangChain 回调 -> 审计事件
# ======================================================================


class AuditCallbackHandler(AsyncCallbackHandler):
    """把 LangChain 的运行时事件映射成审计事件。

    记两件事, 都是【Agent 运行时这一层的观察点】:

    * `LLM_CALL` —— 每次 LLM 调用: 耗时、模型、token 用量、成败
    * `MCP_TOOL_CALL` —— 每次工具调用【被 Agent 发起】: 工具名、入参、耗时

    ## 关于第二个: 网关不是已经记过了吗

    记过了, 但**这是两个不同的观察点, 不是同一件事记两遍**:

    * 网关那条记的是"网关收到并执行了什么"
    * 这里记的是"Agent 想调什么" —— 包含根本没到达网关的那些 (网关没起来、
      超时、连接被拒)

    两者一比才能回答一个网关自己永远答不了的问题: **Agent 到底调了没有**。
    只查网关会得到"这个任务没调过工具"这种结论, 而它可能是错的 —— 真相是
    Agent 调了, 请求根本没到。

    ## response_body 刻意为空

    `MCP_TOOL_CALL` 的响应体不填, 留给网关那条 —— 同一份数据记两遍,
    一万次里只要有一次不一致, 查审计的人就得先花时间判断哪个是对的。
    各记一半、按 trace_id 拼起来, 是这里唯一说得通的写法。

    同理, request_body 里放的是 **Agent 请求的入参**, 不是网关收到的入参 ——
    两者有可能不同, 这个对比才有意义。
    """

    def __init__(self, reporter: AuditReporter, *, default_model: str | None = None) -> None:
        self._reporter = reporter
        self._default_model = default_model
        # run_id -> 开始时刻。一个 ReAct 循环里 LLM 调用和工具调用是交错的,
        # 不能用"上一次的 start" —— 必须按 run_id 配对。
        self._starts: dict[str, float] = {}
        # run_id -> 这次工具调用的入参 `{"tool":..., "params":...}`。
        # 只在 on_tool_start 拿得到, 结束时那个回调里没有, 所以趁早存下来。
        self._tool_requests: dict[str, dict[str, Any]] = {}

    # ------------------------------------------------------------------
    #  LLM
    # ------------------------------------------------------------------

    async def on_llm_start(
        self, serialized: dict[str, Any], prompts: list[str], **kwargs: Any
    ) -> None:
        self._starts[str(kwargs.get("run_id"))] = time.time()

    async def on_chat_model_start(
        self, serialized: dict[str, Any], messages: list[list[Any]], **kwargs: Any
    ) -> None:
        """聊天模型走的是这个方法, 不是 on_llm_start。

        ★ 两个都要实现, 理由值得记下来 ★

        langchain 在 handler 的 on_chat_model_start 抛 NotImplementedError 时,
        会**回落到 on_llm_start** (见 langchain_core.callbacks.manager 里的
        `_achat_model_start_fallback`, 它顺手把 messages 转成格式化字符串)。
        所以"只实现 on_llm_start"其实也能收到聊天模型的调用 —— 只是绕了
        一大圈, 而且那个格式化把每条消息都渲染成文本, 对几万 token 的上下文
        来说纯属浪费。

        显式实现它, 是为了不依赖那个回落: 旁路的开销应该显式可见,
        而不是藏在框架的一个 except 分支里。
        """
        self._starts[str(kwargs.get("run_id"))] = time.time()

    async def on_llm_end(self, response: Any, **kwargs: Any) -> None:
        token_in, token_out = _usage_of(response)
        self._reporter.emit(
            EVENT_LLM_CALL,
            **self._close(kwargs.get("run_id")),
            token_in=token_in,
            token_out=token_out,
            request_body={"model": _model_of(response) or self._default_model},
            response_body={"finishReason": _finish_reason_of(response)},
        )

    async def on_llm_error(self, error: BaseException, **kwargs: Any) -> None:
        self._reporter.emit(
            EVENT_LLM_CALL,
            status=status_for(error),
            **self._close(kwargs.get("run_id")),
            request_body={"model": self._default_model},
            error_msg=_describe(error),
        )

    # ------------------------------------------------------------------
    #  工具
    # ------------------------------------------------------------------

    async def on_tool_start(
        self, serialized: dict[str, Any], input_str: str, **kwargs: Any
    ) -> None:
        run_id = str(kwargs.get("run_id"))
        self._starts[run_id] = time.time()
        self._tool_requests[run_id] = {
            "tool": (serialized or {}).get("name") or "unknown",
            "params": _parse_params(input_str),
        }

    async def on_tool_end(self, output: Any, **kwargs: Any) -> None:
        run_id = kwargs.get("run_id")
        self._reporter.emit(
            EVENT_MCP_TOOL_CALL,
            **self._close(run_id),
            request_body=self._tool_request(run_id),
        )

    async def on_tool_error(self, error: BaseException, **kwargs: Any) -> None:
        # ★ 这里记 ERROR **不等于**任务失败 ★
        #
        # task_state.py 里那段 ★ 注释说得很清楚: ReAct 循环存在的意义就是从
        # 工具错误里恢复, 工具抛错时图会继续跑, 任务完全可能成功。所以工具级
        # 的错误【不能】映射成任务终态 (那会把一个成功的任务永久记成失败)。
        #
        # 但审计里记一条 status=ERROR 是对的, 而且必须记: 这条记录的主张是
        # "这一次工具调用失败了", 它没有说"任务失败了" —— 后者去看
        # TASK_LIFECYCLE 那条。一行一件事, 别让它替别的事情说话。
        run_id = kwargs.get("run_id")
        self._reporter.emit(
            EVENT_MCP_TOOL_CALL,
            status=status_for(error),
            **self._close(run_id),
            request_body=self._tool_request(run_id),
            error_msg=_describe(error),
        )

    # ------------------------------------------------------------------
    #  内部
    # ------------------------------------------------------------------

    def _close(self, run_id: Any) -> dict[str, Any]:
        """结束一个 run: 取出它的耗时【和开始时刻】。

        ★ 为什么要连开始时刻一起交回去 ★

        因为 occurred_at 的定义是「这件事什么时候发生的」, 不是「这条记录
        什么时候被写下来的」。一次工具调用耗时 500ms, 拿结束时刻当 occurred_at
        的话, 这一行会比网关上同一件调用的那行晚半个秒 —— 而按 occurred_at
        排出来的时间线会因此显示「网关先收到, Agent 后发起」。

        这个错很难发现: 单独看每一层都正常, 只有把两层并排看才露馅。
        第一版就是这么写的, 端到端跑出来才看见 (AGENT_RUNTIME 那条比
        MCP_GATEWAY 那条晚 25ms)。所以这里固定用【开始时刻】,
        和网关的 AuditEvent.startedAt 语义对齐 —— 两层对同一件事的
        occurred_at 应当【几乎相等】, 这才是能用的信号。

        没有 start 就没有耗时可言。填 0 是谎报 (看起来像"瞬间完成"),
        不如留 NULL —— 缺失看得见, 假的 0 看不见。occurred_at 则交给
        emit() 取当前时刻兜底。
        """
        started = self._starts.pop(str(run_id), None)
        if started is None:
            return {"latency_ms": None}
        return {
            "latency_ms": int((time.time() - started) * 1000),
            "occurred_at": started,
        }

    def _tool_request(self, run_id: Any) -> dict[str, Any]:
        """工具调用的入参。

        ★ 键名和网关保持一致: `{"tool": ..., "params": ...}` ★
        这样 `JSON_EXTRACT(request_body, '$.tool')` 在两层的事件上写法完全
        一样, 按 trace_id 把两层的行拉出来对比时不需要先做一次字段名映射。
        """
        return self._tool_requests.pop(str(run_id), {"tool": "unknown"})


def _parse_params(input_str: str) -> Any:
    """把工具入参的字符串还原成对象。

    工具入参在回调里以字符串形式给出 (MCP 工具那边就是一段 JSON)。能解析
    就解析成对象 —— 只有对象才能用 JSON 路径查询, 而"按 orderId 查这次调用"
    是审计最常见的用法之一。解析不出来就原样留一小段: 那说明 Agent 传了
    一个畸形的东西, 这本身就是值得记下来的事实, 不该被丢掉。
    """
    if not input_str:
        return {}
    try:
        return json.loads(input_str)
    except (TypeError, ValueError):
        return {"raw": input_str[:_RAW_INPUT_LIMIT]}


def _model_of(response: Any) -> str | None:
    """从 LLMResult 里挖模型名。

    没有标准位置 —— OpenAI 兼容协议放 llm_output["model_name"], 有些供应商
    放在 response_metadata 里, 还有些什么都不给。挖不到就返回 None, 由调用方
    回落到配置里的 llm_model；**不要**在这里编一个默认值。
    """
    output = getattr(response, "llm_output", None) or {}
    name = output.get("model_name") or output.get("model")
    if name:
        return name
    for generation in _generations_of(response):
        meta = getattr(getattr(generation, "message", None), "response_metadata", None) or {}
        name = meta.get("model_name") or meta.get("model")
        if name:
            return name
    return None


def _usage_of(response: Any) -> tuple[int | None, int | None]:
    """从 LLMResult 里挖 (输入 token, 输出 token)。

    两个可能的位置, 按可靠性排:

    1. generation 上 `message.usage_metadata` —— langchain 的**标准**位置
       (`input_tokens` / `output_tokens`)
    2. `llm_output.token_usage` —— OpenAI 兼容协议的原始字段
       (`prompt_tokens` / `completion_tokens`)。DeepSeek 走这条路

    都挖不到就返回 (None, None): 那一列留空是诚实的, 而填 0 会让
    "这个月用了多少 token"这种统计**静默偏低** —— 偏低比缺失危险得多,
    因为缺失看得见。
    """
    for generation in _generations_of(response):
        meta = getattr(getattr(generation, "message", None), "usage_metadata", None)
        if meta:
            return meta.get("input_tokens"), meta.get("output_tokens")

    usage = (getattr(response, "llm_output", None) or {}).get("token_usage") or {}
    if usage:
        return (
            usage.get("prompt_tokens", usage.get("input_tokens")),
            usage.get("completion_tokens", usage.get("output_tokens")),
        )
    return None, None


def _finish_reason_of(response: Any) -> str | None:
    for generation in _generations_of(response):
        reason = (getattr(generation, "generation_info", None) or {}).get("finish_reason")
        if reason:
            return reason
    return None


def _generations_of(response: Any) -> list[Any]:
    """LLMResult -> 扁平的 generation 列表。

    `generations` 是 list[list[Generation]] (外层是"每个 prompt 一组",
    内层是候选), 挖字段时只想要平的那一层。所有调用点都从这里走, 免得
    每个辅助函数各写一遍同样的两层循环、各错一遍。
    """
    flat: list[Any] = []
    for group in getattr(response, "generations", None) or []:
        flat.extend(group or [])
    return flat


def _describe(error: BaseException) -> str:
    """异常描述。只取最里面那一层的【类型 + 消息】。

    和 app/main.py 的 describe_exception 同一个思路, 但这里不展开
    ExceptionGroup —— 那是为了让启动失败好排查。审计里存的是"这次调用
    为什么失败", 一句话够用, 堆栈该去日志里看。
    """
    current: BaseException = error
    for _ in range(32):
        cause = current.__cause__ or current.__context__
        if cause is None or cause is current:
            break
        current = cause
    return f"{type(current).__name__}: {current}"
