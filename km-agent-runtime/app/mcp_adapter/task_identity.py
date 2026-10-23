"""任务身份 —— 让 Java 网关知道"这次工具调用属于哪个任务"。

## 要解决的问题

MCP 协议里【没有"任务"这个概念】。Python 连上网关之后, 网关看到的只是一个
匿名会话和一堆 tools/call, 而"哪个任务在调这个工具"恰恰是审计的核心问题
(见 docs/audit.md 第三节)。

答案是把身份放进 HTTP 请求头。Java 侧已经在读这四个头
(com.km.audit.AuditHeaders 定义了头名, McpGatewayAuditConfig 负责抽取),
这里负责把它们【按请求】放上去。

## 为什么走 auth, 不走 langchain-mcp-adapters 的 headers

`LangChainMcpToolProvider._server_config` 里的 `headers` 是构造
MultiServerMCPClient 时定死的 —— 一个 client 一套头, 之后改不了。

而 Agent 是长驻进程, 会连着跑任务 A、任务 B、任务 C。用静态头的话, 任务 B
的工具调用会被记到任务 A 名下, 而且【从审计里看不出来】: 它不是缺数据,
它是把数据记到了错的地方 —— 比缺数据危险得多。

真正需要的是"每个请求都带上【当前】任务的身份", 这正好是 httpx.Auth 的
语义: `async_auth_flow` 在每一个出站请求上被调一次。所以身份走 auth 走。

这不是变通。Java 侧也是逐请求抽取的 —— `handlePost` 每个 POST 调一次
contextExtractor, 而不是在 initialize 时定死。两边语义对齐。

## 为什么用 ContextVar

请求处理是 asyncio 并发的: 同一时刻可能有好几个任务在跑, 每个都得带自己的
身份。ContextVar 是 asyncio 下做这件事的原语 —— 值跟着【当前任务】走,
自动传播进 await 链和 create_task 出来的子任务, 且彼此隔离。

全局变量在这里会串号; threading.local 在 asyncio 下形同虚设 (所有协程跑在
同一个线程里, 那才是"全局变量"的另一种写法)。
"""

from __future__ import annotations

import contextvars
import logging
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass

import httpx

logger = logging.getLogger(__name__)

# 头名必须和 Java 侧 com.km.audit.AuditHeaders 逐字一致。
# 写错了不会报错 —— 网关只是抽不到, 审计里的 task_id 一列全空。见文件头那节。
TASK_ID_HEADER = "X-KM-Task-Id"
TRACE_ID_HEADER = "X-KM-Trace-Id"
TENANT_ID_HEADER = "X-KM-Tenant-Id"
ACTOR_HEADER = "X-KM-Actor"


@dataclass(frozen=True, slots=True)
class TaskIdentity:
    """一次工具调用归属的任务身份。

    只有 `task_id` 是必需的 —— 它是"哪个任务"的答案, 也是审计里唯一
    真正不可缺的一列。其余三个缺失只是让排查时少几条线索, 不影响归属,
    所以给不给都能跑。

    frozen: 身份在一次调用期间不该被改。真要换身份就换一个对象 ——
    可变对象在并发下出事是那种"偶尔串号"的 bug, 最难查。
    """

    task_id: str
    trace_id: str | None = None
    tenant_id: str | None = None
    actor: str | None = None

    # 将TaskIdentity类对象转换为dict格式的 HTTP Header
    def to_headers(self) -> dict[str, str]:
        """转成 HTTP 头。

        可选字段【没有就不放】, 不放空串。空串到了 Java 侧也会被
        `TaskIdentity.from` 当成 null (它 trim 之后判空), 但发送端
        多此一举地塞一个空头, 只会让人以为是调用方传了个空值。
        """

        headers = {TASK_ID_HEADER: self.task_id}
        if self.trace_id:
            headers[TRACE_ID_HEADER] = self.trace_id
        if self.tenant_id:
            headers[TENANT_ID_HEADER] = self.tenant_id
        if self.actor:
            headers[ACTOR_HEADER] = self.actor
        return headers

# 有记忆的上下文变量, 用于存储当前任务的身份
_current: contextvars.ContextVar[TaskIdentity | None] = contextvars.ContextVar(
    "km_task_identity", default=None
)

# 从上下文变量中获取当前任务身份
def current_task_identity() -> TaskIdentity | None:
    """当前请求的任务身份, 不在任务上下文里时为 None。"""
    return _current.get()

# 将当前任务身份加入上下文变量, 并且规定用法
@contextmanager
def task_identity(identity: TaskIdentity | None) -> Iterator[TaskIdentity | None]:
    """把身份绑定到当前任务上, 出块后还原。

    ★ 用 token + reset 而不是在 finally 里 `set(None)` ★

    两者在嵌套时结果完全不同。假设外层是任务 A、内层临时切成任务 B:

        with task_identity(A):
            with task_identity(B):   # reset 回 A ✔ / set(None) 就丢了 A ✘
                ...
            ...                      # 这里还应该属于 A

    因为 agent 可能在这条路径上被复用于别的调用, 而"身份丢了"的症状是
    审计里一片 NULL —— 又会被人当成客户端没带头去查。
    """
    token = _current.set(identity)
    try:
        yield identity
    finally:
        _current.reset(token)


class TaskIdentityAuth(httpx.Auth):
    """把当前任务身份塞进每个出站请求的头上。

    名字叫 Auth, 做的却不是鉴权而是身份透传 —— 用它的唯一理由是 httpx 只
    给了这一个"逐请求改请求"的口子。Authorization 头本身也是从这条路径加
    上去的, 所以语义上不算离题。

    ★ 为什么不能改成往 AsyncClient 的 headers 上加 ★

    client 级的 headers 是"这个连接的身份", auth flow 是"这个请求的身份"。
    MCP 的 streamable 传输会复用连接, 所以前者会让同一连接上的多个任务
    共用一个身份 —— 正是本模块开头说的那个错法。
    """

    # 改头不需要请求体/响应体。留默认 (False) 就是不加这两层开销 ——
    # 请求体是真会读的, streamable HTTP 传的可能是图片或大 JSON。
    requires_request_body = False
    requires_response_body = False

    async def async_auth_flow(self, request: httpx.Request):
        self._apply(request)
        yield request

    def sync_auth_flow(self, request: httpx.Request):
        """AsyncClient 不会走这里, 实现它只是为了让这个对象在同步 client 上
        也不会直接抛 NotImplementedError —— 它是公开可复用的部件, 没有理由
        只在一半场景下能用。
        """
        self._apply(request)
        yield request

    # 获取当前任务身份, 并添加到请求对象的请求头
    @staticmethod
    def _apply(request: httpx.Request) -> None:
        """没绑身份就原样放行。

        刻意【不告警、不抛错】: 不带 task_id 的调用是合法的 —— `/agent/tools`
        拉工具列表、手工调试、将来非任务型的对话, 都会走到这里。在这里喊
        "缺身份"等于天天狼来了。

        真正该提醒的那个位置在 Java 网关: 它的告警条件是"一次【工具调用】
        没带身份", 那才说明是客户端漏了。职责分清楚, 两边都不吵。
        """
        identity = _current.get()
        if identity is None:
            return
        for name, value in identity.to_headers().items():
            request.headers[name] = value
