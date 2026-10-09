"""任务状态上报 —— 把 Agent 的运行阶段同步给 Java 侧。

配套设计文档: docs/task-state-machine.md

## 边界

Java 是任务状态的【唯一事实源】。这里做的只是【上报】——
Java 收到后会校验流转合法性, 非法流转会被拒(HTTP 409)。
Python 不判断状态该不该变, 只负责说"我现在进到哪一步了"。

## 两条硬性约束

**1. 上报失败绝不能影响主流程。**

状态回调是旁路, 不是主链路的一部分。Java 挂了、网络抖了,
Agent 该跑完还是要跑完。否则一个"可观测性"功能会把"可用性"搞坏 ——
Java 一抖动, 所有任务全挂。

代价是状态可能失真(Python 认为在跑, Java 记的是旧状态)。
兜底手段是 Java 侧的超时检测, 那正是 agent_task.retry_count 的用武之地。

**2. 上报不能阻塞 LLM 调用。**

on_llm_start 这类钩子跑在关键路径上, 同步发 HTTP 会拖慢每一次调用。

做法: report() 只把事件塞进本地队列就立刻返回, 由后台 worker 串行发出。
**串行是有意的** —— 并发上报会让 Java 侧事件表的顺序乱掉,
而状态机的价值恰恰在于顺序。
"""

from __future__ import annotations

import asyncio
import logging
from typing import Any

import httpx
from langchain_core.callbacks import AsyncCallbackHandler

logger = logging.getLogger(__name__)

# 与 Java 侧枚举 / MySQL agent_task.status 字段一一对应。
# 刻意不做成 Enum: 这些值在 HTTP 报文里以字符串裸奔, 两端按字符串对齐,
# 中间加一层 Enum 只会在序列化边界上多一次转换, 不换来任何安全。
PENDING = "PENDING"
THINKING = "THINKING"
TOOL_CALLING = "TOOL_CALLING"
COMPLETED = "COMPLETED"
FAILED = "FAILED"
CANCELLED = "CANCELLED"


class TaskStateReporter:
    """把任务状态变更异步上报给 Java 网关。

    生命周期: start() -> 任意多次 report() -> aclose()
    """

    def __init__(
        self,
        base_url: str,
        task_id: str,
        timeout: float = 2.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        self._base_url = base_url.rstrip("/")
        self._task_id = task_id
        self._timeout = timeout
        # 允许注入 client, 测试时可以塞 MockTransport 进来, 不用起真服务
        self._client = client
        self._queue: asyncio.Queue[tuple[str, dict[str, Any]]] = asyncio.Queue()
        self._worker: asyncio.Task[None] | None = None

    @property
    def task_id(self) -> str:
        return self._task_id

    # ------------------------------------------------------------------
    #  对外
    # ------------------------------------------------------------------

    def report(self, to_status: str, detail: dict[str, Any] | None = None) -> None:
        """入队一次状态上报。

        刻意做成【同步、非阻塞、不抛异常】的方法 —— 它会被
        on_llm_start / on_tool_start 这类跑在关键路径上的钩子调用,
        在那里 await 一个网络请求是不可接受的。
        """
        self._queue.put_nowait((to_status, detail or {}))

    async def start(self) -> None:
        """启动后台 worker。重复调用是安全的。"""
        if self._worker is None:
            self._worker = asyncio.create_task(
                self._run(), name=f"task-state-reporter-{self._task_id}"
            )

    async def aclose(self, drain_timeout: float = 5.0) -> None:
        """排空队列后停掉 worker。

        给排空留时间, 是因为最后一条 COMPLETED / FAILED 往往是整个任务里
        最有价值的一条 —— 丢了它, Java 侧会一直以为任务还在跑。
        """
        if self._worker is None:
            return

        try:
            await asyncio.wait_for(self._queue.join(), timeout=drain_timeout)
        except TimeoutError:
            logger.warning(
                "状态上报队列未排空 (剩 %d 条), 直接停止 task=%s",
                self._queue.qsize(),
                self._task_id,
            )

        self._worker.cancel()
        try:
            await self._worker
        except asyncio.CancelledError:
            pass
        self._worker = None

    # ------------------------------------------------------------------
    #  内部
    # ------------------------------------------------------------------

    async def _run(self) -> None:
        owns_client = self._client is None
        client = self._client or httpx.AsyncClient(timeout=self._timeout)
        try:
            while True:
                to_status, detail = await self._queue.get()
                try:
                    await self._patch(client, to_status, detail)
                except Exception as ex:  # noqa: BLE001 — 见下面 ★, 盲捕是刻意的
                    # ★ 吞掉。上报是旁路, 它失败不该让 Agent 崩。
                    logger.warning(
                        "状态上报失败 task=%s %s: %s", self._task_id, to_status, ex
                    )
                finally:
                    self._queue.task_done()
        finally:
            if owns_client:
                await client.aclose()

    async def _patch(
        self, client: httpx.AsyncClient, to_status: str, detail: dict[str, Any]
    ) -> None:
        response = await client.patch(
            f"{self._base_url}/api/tasks/{self._task_id}/status",
            json={
                "toStatus": to_status,
                "source": "PYTHON_RUNTIME",
                "detail": detail,
            },
        )
        if response.status_code >= 400:
            # 409 通常是 Java 拒绝了非法流转 —— 那说明 Python 这边有 bug,
            # 值得让日志难看一下, 但同样不能影响主流程。
            logger.warning(
                "状态上报被拒 task=%s %s -> HTTP %d: %s",
                self._task_id,
                to_status,
                response.status_code,
                response.text[:200],
            )


class TaskStateCallbackHandler(AsyncCallbackHandler):
    """把 LangChain 的运行时事件映射成任务状态上报。

    **只上报「进行中」的状态 (THINKING / TOOL_CALLING)。**
    终态 (COMPLETED / FAILED) 由调用方 /chat 决定 —— 理由见下面那段注释。

    为什么用 CallbackHandler 而不是自定义图节点:

    * create_react_agent 建出来的图【一行都不用动】, 挂到
      ainvoke 的 config={"callbacks": [...]} 上即可
    * on_llm_start / on_tool_start 天然对应 THINKING / TOOL_CALLING
    * Phase 2 后期换成多 Agent 子图时, 这里不用重写;
      而如果把状态上报混进图节点, 那时候就全得推倒

    注意这些 async 方法内部【没有 await】—— report() 是同步入队。
    LangChain 会 await 这些方法, 它们会立刻返回, 不阻塞 LLM 调用。
    """

    def __init__(self, reporter: TaskStateReporter) -> None:
        self._reporter = reporter

    async def on_llm_start(
        self, serialized: dict[str, Any], prompts: list[str], **kwargs: Any
    ) -> None:
        self._reporter.report(THINKING, {"phase": "llm"})

    async def on_tool_start(
        self, serialized: dict[str, Any], input_str: str, **kwargs: Any
    ) -> None:
        tool_name = (serialized or {}).get("name") or "unknown"
        self._reporter.report(TOOL_CALLING, {"toolName": tool_name})

    # ------------------------------------------------------------------
    #  ★ 刻意【不实现】 on_llm_error / on_tool_error
    #
    # 直觉上这里该把异常映射成 FAILED。那是错的, 已实测确认 (2026-10-04):
    #
    #   工具抛【普通异常】(工具体里的 ValueError, 或 Java 后端 500):
    #       异常穿透出图, ainvoke 整体抛错 —— 任务确实失败了
    #   工具抛【pydantic ValidationError】(如 structuredContent 不是 object):
    #       ToolNode 默认的 _default_handle_tool_errors 只认这一种,
    #       把它吞成 ToolMessage(status="error"), 【图继续跑】,
    #       Agent 随后可能给出正确答案, HTTP 200
    #
    # 两种情况下 on_tool_error 【都会触发】。在这里报 FAILED 的话,
    # 第二种情况下任务会被打成终态 FAILED 而图还在跑 —— 后续的
    # THINKING / COMPLETED 会被 Java 以 409 全部拒绝,
    # 一个【实际成功】的任务被永久记成失败。
    #
    # 根本原因: ReAct 循环存在的意义就是【从工具错误中恢复】,
    # 所以工具级的错误不是任务级的失败。只有 /chat 知道这轮 invocation
    # 到底成没成, 终态由它报。
    #
    # 工具调用的审计 (调了哪个工具、成功与否、耗时) 走 tool_invocation 表,
    # 不应该污染任务状态机。
    # ------------------------------------------------------------------
