"""执行心跳与存活登记 —— Java 侧"卡住的执行"兜底的另一半。

配套设计: docs/task-state-machine.md 的「心跳兜底」一节。

## 两个信号, 方向相反

**1. 心跳 (push): Python -> Java**

执行期间每隔 ``heartbeat_interval_seconds`` POST 一次
``/api/tasks/{id}/heartbeat``, 带上本次执行的 traceId。Java 把它记在
``agent_task_attempt.last_heartbeat_at`` 上 —— 这是"这次执行还活着"的
时间证据。

它和状态上报是两条独立的旁路:

* 心跳不改变状态, Java 也不会为它写事件/审计 (见 ``TaskService.heartbeat``)。
* 心跳失败**绝不**影响主流程, 理由和 ``task_state.py`` 里那段一样:
  旁路坏了不能把可用性搞坏。所以这里吞掉所有网络异常, 只记日志。

**2. 存活登记 (pull): Java -> Python**

``GET /tasks/{trace_id}/alive`` 返回本进程是否正在处理该 trace。
Java 的心跳兜底扫描发现证据过期时, 会先问这一句 —— 只有 Python
**亲口说没有**, 它才会关链。

## ★ 为什么必须"亲口说", 而不是等心跳过期 ★

心跳停了有三种解释: Python 死了、Python 活着但连不上 Java、Java 自己
收不到。后两种情况下任务其实还在跑, 直接判死会造成双写。探活把判断
变成双向的: 只有能正常响应 ``/alive`` 的进程说"没有这条 trace",
才说明它真的没在跑。

## ★ 单实例前提 ★

``LivenessRegistry`` 是进程内内存。当前部署是单个 uvicorn 进程, 所以
"本进程没有它" = "没人在跑它"。多实例部署时负载均衡可能把探活打到
另一台, 它会诚实地回答 false —— 那是误判。那时必须把这份清单放到
共享存储 (Redis key + TTL), 或者让探活带实例亲和性。
"""

from __future__ import annotations

import asyncio
import logging
import time

import httpx

logger = logging.getLogger(__name__)


class LivenessRegistry:
    """本进程正在处理哪些 trace。

    FastAPI / uvicorn 单事件循环里, dict 的读写在协程之间是原子的
    (中间没有 await), 所以不需要锁。多线程 worker 不适用 —— 那本来就
    需要共享存储, 见模块注释。

    ★ 按引用计数, 不是简单的 set ★

    同一个 trace 的 /chat 理论上只该来一次 (Java 每次派发都换新执行),
    但"客户端超时后重发 /chat"是真实存在的。两次并发执行同一个 trace 时,
    如果第一个结束就把登记删掉, Java 探活会得到 false 并关链 —— 而另一个
    其实还在跑。判活的规则是"宁可多答几毫秒 alive, 不能早答 false",
    所以这里等最后一个持有者退出才真正删除。
    """

    def __init__(self) -> None:
        self._started: dict[str, float] = {}
        self._refs: dict[str, int] = {}

    def register(self, trace_id: str) -> None:
        """登记一次执行的开始 (用单调时钟, 不受系统时间调整影响)。"""
        self._refs[trace_id] = self._refs.get(trace_id, 0) + 1
        # 第一个持有者决定"开始时刻"; 重复登记不重置它。
        self._started.setdefault(trace_id, time.monotonic())

    def unregister(self, trace_id: str) -> None:
        remaining = self._refs.get(trace_id, 0) - 1
        if remaining > 0:
            self._refs[trace_id] = remaining
            return
        # 最后一个持有者退出, 才真正删除登记
        self._refs.pop(trace_id, None)
        self._started.pop(trace_id, None)

    def is_alive(self, trace_id: str) -> bool:
        return trace_id in self._started

    def elapsed_seconds(self, trace_id: str) -> float | None:
        # 查询 trace 最早的运行秒数
        started = self._started.get(trace_id)
        # 返回 trace 已运行秒数
        return None if started is None else time.monotonic() - started

    def snapshot(self) -> dict[str, float]:
        """当前在跑的 trace -> 已运行秒数。给 /alive 和排查用。"""
        now = time.monotonic()
        return {trace: now - started for trace, started in self._started.items()}


class HeartbeatBeater:
    """执行期间定期给 Java 发心跳。
    专门为一次执行设计, 不会跨执行。
    生命周期: ``start()`` -> 任意多次心跳 -> ``aclose()``。

    和 ``TaskStateReporter`` 不同, 这里**没有队列**: 心跳的价值在于"最新"。
    如果真的发不出去, 补发一条陈旧的心跳没有任何意义 (Java 看的是时间戳),
    而且会让后台任务无限制堆积。所以单循环: 发完 -> 等一个间隔 -> 再发。
    """

    def __init__(
        self,
        base_url: str,
        task_id: str,
        *,
        trace_id: str,
        interval: float = 15.0,
        timeout: float = 2.0,
        client: httpx.AsyncClient | None = None,
    ) -> None:
        if not trace_id:
            raise ValueError("trace_id 不能为空 —— 心跳必须标明是哪一次执行")
        self._base_url = base_url.rstrip("/")
        self._task_id = task_id
        self._trace_id = trace_id
        # 间隔下限保护: 配置成 0 会把 Java 打成每秒几十次请求。
        self._interval = max(1.0, float(interval))
        self._timeout = timeout
        self._client = client
        self._worker: asyncio.Task[None] | None = None
        self._stopped = False

    @property
    def task_id(self) -> str:
        return self._task_id

    @property
    def trace_id(self) -> str:
        return self._trace_id

    async def start(self) -> None:
        """启动后台心跳。重复调用是安全的。"""
        if self._worker is None:
            self._worker = asyncio.create_task(
                self._run(), name=f"task-heartbeat-{self._task_id}"
            )

    async def aclose(self) -> None:
        """停止心跳。不排空 —— 见类注释: 心跳只有最新一条有意义。"""
        if self._worker is None:
            return
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
            # 心跳循环
            while not self._stopped:
                # 先跳一次再等: 让 Java 尽早拿到 last_heartbeat_at。
                # 如果先等一个间隔, 派发到首次心跳之间的窗口会白白拉长。
                if await self._beat(client):
                    # 心跳被拒, 停止心跳
                    break
                # 发出心跳后等下一个间隔再循环发一次
                await asyncio.sleep(self._interval)
        finally:
            if owns_client:
                await client.aclose()

    async def _beat(self, client: httpx.AsyncClient) -> bool:
        """发一次心跳。返回 True 表示"以后不用再发了"。

        4xx 的含义是"别跳了": trace 对不上 (409) 或执行已经结束 (404/409)。
        继续发只会每 15 秒往 Java 日志里丢一条 WARN。
        网络错误 / 5xx 则相反 —— 是暂时性的, 继续跳, 等它恢复。
        """
        try:
            response = await client.post(
                f"{self._base_url}/api/tasks/{self._task_id}/heartbeat",
                json={"traceId": self._trace_id},
            )
        except Exception as ex:  # noqa: BLE001 — 旁路, 盲捕是刻意的
            logger.debug(
                "心跳发送失败 task=%s trace=%s: %s", self._task_id, self._trace_id, ex
            )
            return False

        if response.status_code >= 500:
            logger.debug(
                "心跳暂时失败 task=%s HTTP %d",
                self._task_id,
                response.status_code,
            )
            return False

        if response.status_code >= 400:
            logger.info(
                "心跳被拒, 停止心跳 task=%s trace=%s HTTP %d: %s",
                self._task_id,
                self._trace_id,
                response.status_code,
                response.text[:200],
            )
            self._stopped = True
            return True

        return False
