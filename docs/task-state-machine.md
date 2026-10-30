# Agent 任务状态机设计

Phase 2 第一件大件。目标是把 README「关键设计决策 1」和 `项目介绍.txt` 第 25~27 行
那条约定从**声明**变成**运行中的代码**。

> Java 侧是 Agent 任务状态的唯一事实源；Python 只持有运行时临时状态，
> 每次状态变更回调 Java。

---

## 一、为什么需要它

现在（Phase 1）的一次 `/chat` 调用是纯内存的：Agent 跑完，结果返回，
**中间发生过什么没有任何记录**。

这带来三个具体问题：

1. **进程崩了就全没了。** Python 挂掉时，用户不知道任务死在哪一步，
   也没法重试 —— 因为压根没有"任务"这个实体。
2. **不可审计。** 谁在什么时候调了哪个工具、拿到了什么，无处可查。
   企业场景下这是刚需。
3. **无法断点续跑。** LangGraph 的 checkpoint 能恢复图的内部状态，
   但"这个任务该不该恢复、归谁所有、重试过几次"是业务问题，图回答不了。

---

## 二、状态机

### 状态定义

沿用 `docker/init/mysql/01-schema.sql` 里已经定好的六个状态，
**Java 侧的枚举名与数据库 `status` 字段严格一一对应**：

| 枚举 | 数据库值 | 含义 |
|---|---|---|
| `PENDING` | PENDING | 已创建，尚未开始 |
| `THINKING` | THINKING | Agent 正在调 LLM（思考 / 生成） |
| `TOOL_CALLING` | TOOL_CALLING | Agent 正在执行一次 MCP 工具调用 |
| `COMPLETED` | COMPLETED | 正常结束 |
| `FAILED` | FAILED | 异常结束 |
| `CANCELLED` | CANCELLED | 人工取消 |

### 流转图

ReAct 的本质是一个**循环**，状态机必须体现这一点：

```
                              ┌──────────────────┐
                              │                  │
                              ▼                  │
   ┌─────────┐          ┌──────────┐      ┌──────────────┐
   │ PENDING │─────────▶│ THINKING │─────▶│ TOOL_CALLING │
   └─────────┘          └──────────┘      └──────────────┘
        │                    │                    │
        │                    │                    │
        │                    ▼                    ▼
        │              ┌───────────────────────────────┐
        └─────────────▶│  COMPLETED / FAILED           │
                       └───────────────────────────────┘

   任意非终态 ──▶ CANCELLED

   FAILED ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─ ─▶ PENDING
          （仅 POST /api/tasks/{id}/retry，控制面专有）
```

关键点：`TOOL_CALLING → THINKING` 是**合法且高频**的流转 ——
Agent 调完工具要回到思考。一次 `/chat` 可能走好几轮。

虚线是**重试边**：它是唯一能离开终态的边，但**不在上报用的流转表里**。
PATCH `/status` 永远不能从 FAILED 出发，只有 `TaskService.retry()` 会走到它。
`COMPLETED` / `CANCELLED` 没有这条边（理由见"重试"一节）。

### 合法性规则

**校验必须在 Java 侧做**，这是"控制面"的实质含义：
Python 只能**上报**，不能**决定**状态。

| 规则 | 说明 |
|---|---|
| 终态不可离开（上报路径） | `COMPLETED` / `FAILED` / `CANCELLED` 是终点；**唯一例外**是控制面的 `FAILED → PENDING` 重试边 |
| 不能跳级 | `PENDING` 不能直接到 `TOOL_CALLING`（必须先 THINKING） |
| 同态上报幂等 | 重复上报当前状态视为成功、不写事件（见下方"幂等"） |
| 非法流转记为异常 | 拒绝更新，但**记 WARN 日志** —— 它说明 Python 侧逻辑有问题 |

重试边由 `TaskStatusMachine.isRetryAllowed()` **单独判定**，不加进
`isAllowed()` 的流转表。两条规则分开之后，
「上报不能离开终态」仍然由 6×6 组合的穷举测试锁死；如果谁为了省事把
`FAILED → PENDING` 加进流转表，`TaskStatusMachineTest` 会立刻红。

---

## 三、边界划分：checkpointer 和 Java 各管什么

这是最容易搞混的地方，必须一次划清。

| | LangGraph checkpointer (PG) | Java (MySQL) |
|---|---|---|
| **存什么** | 图状态：消息历史、当前节点位置 | 任务生命周期：状态、结果、重试、归属 |
| **谁读** | 只有 Python | Java / Python / 运维 / Grafana |
| **用途** | 断点续跑（用同一 thread_id 重新 invoke） | 任务管理、审计、重试调度、取消 |
| **生命周期** | 技术性，可清理 | 业务性，必须永久保留 |
| **语义** | "图跑到哪了" | "这单业务办成没有" |

### 连接点：`agent_task_id` 直接用作 LangGraph 的 `thread_id`

这是整个设计里最关键的一个决定。好处：

- 断点续跑 = **用同一个 `agent_task_id` 重新 invoke**，不需要任何映射表
- 排查问题时，一个 ID 同时能在 MySQL 和 PG checkpoint 里查
- `agent_task.checkpoint_ref` 字段因此**暂时不需要**，
  留给将来"一个任务多个 checkpoint 分支"的场景

> 反面做法：另生成一个 `thread_id` 并存进 `checkpoint_ref`。
> 多一层映射，多一处可能不一致的地方，收益为零。

---

## 四、Java 侧：新增 `km-task-service` 模块（:8083）

### 为什么单独开模块，而不是塞进 `km-mcp-server`

两者职责不同，混在一起会让 MCP 网关从"工具调用边界"膨胀成"什么都有"：

- `km-mcp-server` ── **工具调用的安全边界**（Agent 能调什么）
- `km-task-service` ── **任务生命周期管理**（任务处于什么状态）

> 取舍：合并进 `km-mcp-server` 可以少起一个进程，开发更快。
> 但拆开后每个服务的职责在答辩时能讲清楚，且将来任务服务要独立扩容
> （它会被 MQ 消费者、定时重试、Grafana 同时打）时不用返工。
> **建议拆开。**

### 接口

```
POST   /api/tasks                      创建任务          -> 任务（含 traceId）
GET    /api/tasks/{id}                 查询任务（Redis 优先, miss 回落 MySQL）
GET    /api/tasks/{id}/events          状态流转历史
PATCH  /api/tasks/{id}/status          状态上报（Python 调这个）
POST   /api/tasks/{id}/retry           失败任务重试      -> 任务（含新 traceId）
```

`PATCH /api/tasks/{id}/status` 请求体：

```json
{
  "traceId": "cffac16e00000000cffac16e00000000",
  "toStatus": "TOOL_CALLING",
  "source": "PYTHON_RUNTIME",
  "detail": { "toolName": "get_order_by_id", "arguments": {"orderId": "SO20260901001"} }
}
```

`traceId` **必填**，是本次执行的执行身份（建任务响应里的那个值）。Java 用它
去 `agent_task_attempt` 找到对应的执行，再用那次执行上不可变的 `trace_id`
给 `TASK_LIFECYCLE` 审计事件盖章。不带 → 400；对不上 → 409
`EXECUTION_MISMATCH`。

`source` 取值（与 `agent_task_event.source` 字段对应）：
`JAVA_GATEWAY` / `PYTHON_RUNTIME` / `SYSTEM_RETRY`

### 一次状态上报，Java 做五件事

1. **校验执行身份** —— `traceId` 必须对应该任务的一次未闭链执行，否则 409
2. **校验流转合法性** —— 不合法则拒绝（返回 409），并记 WARN
3. **写 MySQL** —— 更新 `agent_task.status`，同时向 `agent_task_event` 插一条（append-only）
4. **更新 Redis** —— 缓存当前状态，供高频查询
5. **终态时补 `finished_at`、关闭 `agent_task_attempt`**，并（将来）触发 MQ 通知

> `agent_task_event` 是 append-only 的：`agent_task.status` 记"现在是什么"，
> 事件表记"怎么变成的"。审计和排查全靠它。

### 幂等性

回调走网络，**重复投递是常态**，不是异常。Java 必须能安全处理重复上报：

- 目标状态 == 当前状态 → 直接返回 200，**不写事件表**（否则事件流会被噪音淹没）
- 加 `requestId` 去重（可选，Phase 2 后期再做）

### 重试（2026-10-28，手动 API 先行）

**重试不是 Python 上报的一个状态，是控制面的一次动作。** Python 运行时只能
说"我失败了"；要不要再试、什么时候试，是 Java 的事。所以接口是
`POST /api/tasks/{id}/retry`。

这个接口**只负责开新链，不负责调用 Python**：它返回新的 `traceId`，调用方
（人工脚本 / 自动调度器）拿它再去 `POST /chat`。手动 API 先落地，是因为
自动派发需要它这个原子动作；调度器已在 2026-10-29 接上，见下面的
[自动重试](#自动重试2026-10-29)。

请求体可选：

```json
{ "reason": "MCP 网关 500, 人工确认后重试" }
```

#### 一次重试，一个事务里做七件事

1. 状态机校验：**只有 FAILED** 能重试
2. 次数校验：`retry_count < max_retry`
3. 不变量校验：该任务不能有未闭链的 attempt
4. CAS 更新任务行：
   `WHERE status='FAILED' AND retry_count=旧值`
   → `status=PENDING`、`trace_id=新 trace`、`retry_count+1`、
   清空 `finished_at` / `error_msg` / `result`
5. 插入 `attempt_no = retry_count + 2` 的 RUNNING 记录
6. 写 `FAILED → PENDING` 事件
7. 发 TASK_LIFECYCLE 审计（事务提交后才真正投 MQ）

CAS 同时带上 `retry_count` 是为了并发：多个重试请求读到同一个
`FAILED + retry_count=0` 时，只有一个能更新成功，输的一方 409。
输的一方**不会**留下 attempt / 事件 / 审计 —— 那些都在 CAS 成功之后才写。

#### 不引入 `RETRYING` 状态

新 attempt、回 PENDING、事件、审计全在同一个事务里，外部看不到任何中间态。
多一个状态就要多定义"它能去哪 / 超时怎么办 / 谁推进它"，而它存在的几毫秒
没有任何人观察得到。**"换一条链重新排队"本身就是这个动作的全部语义。**

#### 为什么是新 trace

一条 trace 的边界是**一次执行**。复用旧 trace 会：

- 把两遍执行混成一条链，分不清工具调用属于哪一遍
- 上一遍已经闭链的 attempt 会让新上报被当成"迟到的旧执行"拒掉

任务和链是 1:N，靠 `task_id` 关联；`agent_task.trace_id` 只是"当前执行"的
滚动指针。重试后旧 trace 立刻失效：拿它进 `/chat` 或 PATCH 上报都是 409。

#### 为什么 COMPLETED / CANCELLED 不能重试

- **COMPLETED**：已经成功，"再试一次"没有业务含义。要重跑是**新建任务**
  （新的 intent 上下文、新的 task_id），不是重试。
- **CANCELLED**：取消是显式意图，不能被重试悄悄推翻。将来要做"恢复"，
  应当是一条独立的操作。

#### 错误码

| HTTP | `error` | 含义 |
|---|---|---|
| 409 | `RETRY_NOT_ALLOWED` | 不是 FAILED / 还有执行未闭链 / 并发冲突 |
| 409 | `MAX_RETRY_EXCEEDED` | `retry_count >= max_retry`（`maxRetry=0` 表示一次都不许） |
| 404 | `TASK_NOT_FOUND` | 任务不存在 |

#### 审计上的样子

重试事件盖**新链**的 trace，`request_body.detail` 里带
`attemptNo` / `traceId` / `previousTraceId` / `reason`。于是两条链都能完整
查到，而且能看出第二遍接在第一遍的哪里：

```
trace 364a280c: 创建 -> THINKING -> FAILED
trace 488db1d0: FAILED -> PENDING (attemptNo=2, previousTraceId=364a280c)
                -> THINKING -> COMPLETED
```

### 自动重试（2026-10-29）

手动重试只差最后一步：**谁去调 `/chat`**。自动重试就是那个调用方。

#### 为什么是定时扫描，不是失败时排一个定时器

失败那一刻往内存里排 `ScheduledFuture`，进程一重启就全丢；多实例部署时每个
实例各排一份，会重复派发。状态已经在库里（`status` / `finished_at` /
`retry_count`），由数据库当唯一事实来源，扫描器只回答"谁到期了"：

```sql
WHERE status = 'FAILED'
  AND finished_at IS NOT NULL
  AND retry_count < max_retry
  AND intent IS NOT NULL AND TRIM(intent) <> ''
  [AND tenant_id = ?]            -- 灰度
ORDER BY finished_at ASC LIMIT 20
```

捞出来之后**还要逐条再判一次退避**（粗筛只按 base 过滤），然后调用
`TaskService.retry(id, "自动重试", SYSTEM_RETRY)` —— 状态机、次数、CAS 全部
复用，扫描器里没有第二套规则。

#### 退避：`min(base * 2^retry_count, max)`

`retry_count` 是"已经重试过几次"，`finished_at` 是"这一轮失败落库的时间"。
第一次失败等 `base`，之后 2×、4×、8×…封顶 `max`。**不存 `next_retry_at`**：
多一列就多一个可能写错、可能和事实不一致的地方，而这两个字段已经够了。

默认 `base=30s`、`max=300s`。验证时用 2s/4s，跑一轮只要十几秒。

#### 派发失败的分类（★ 这里最容易做错 ★）

| 情况 | 判定 | 处理 |
|---|---|---|
| 拒绝连接 / DNS 失败 / 连接超时 | **确定没到 Python** | 标记这次 attempt FAILED（`detail.phase=DISPATCH`），任务回 FAILED，下轮退避再试 |
| 线程池满 | 请求还没发出去 | 同上 |
| 读超时 / HTTP 5xx | 请求**可能**已经在跑 | **只记日志，不关链**，等 Python 自己的终态上报 |

第三行是重点：如果读超时也回写 FAILED，就会出现"任务显示失败、Python 还在
跑并继续上报"的双写，后到的上报全被终态拒绝，审计里留下一串 409。结果未知
时不要猜。真的一直不回来，属于"卡住的 PENDING"，那是另一条兜底（见下）。

#### 审计与来源

自动重试走和手动完全相同的七步事务，唯一区别是事件 / 审计的 `source`：

- 手动：`JAVA_GATEWAY`（人/调用方显式发起）
- 自动：`SYSTEM_RETRY`（系统按退避兜底），`reason="自动重试"`

于是可以按 source 统计"有多少任务是系统救回来的"，而不用翻日志。派发失败
回写的 `PENDING → FAILED` 也记 `SYSTEM_RETRY`，detail 带 `phase=DISPATCH` 和
错误原因 —— 能一眼分清"Python 里任务失败"和"根本没进 Python 的门"。

#### 开关与灰度

默认**关闭**（`km.retry.scheduler.enabled=false`）—— 它会自己花钱，不该因为
代码合进来就生效。打开还要能按租户灰度（`km.retry.scheduler.tenant`，空 = 全部），
验证脚本也靠它把测试任务和历史数据隔开。

```bash
KM_RETRY_ENABLED=true \
KM_RETRY_TENANT=retry-e2e \
KM_PYTHON_CHAT_URL=http://localhost:8000 \
mvn -pl km-task-service spring-boot:run
```

#### 还没做的

- **卡住的 PENDING 兜底**：派发读超时/Python 进程中途死掉时，任务会停在
  PENDING、attempt 一直不闭链。需要一个"超过 N 分钟没进展"的扫描，但前提是
  能确定 Python 那边真的没在跑 —— 贸然关链会制造双写，所以先不做。
- **重试原因的操作者身份**：现在没有鉴权，审计的 `actor` 记的是任务归属
  用户，发起重试的人不记。等接入认证再补。
- `/chat` 入口会拒绝终态任务（409，提示调用 `/retry`）；这是重试落地时
  一起补的，避免"Agent 白跑一整轮、所有上报都被拒"。

---

## 五、Python 侧：用 LangChain CallbackHandler 上报

### 为什么用 CallbackHandler，而不是改图结构

LangChain 的 `BaseCallbackHandler` 是为"旁路观察"设计的，正好匹配这个需求：

- **不改图**：`create_react_agent` 出来的图一行不用动
- **不侵入业务**：挂到 `ainvoke(config={"callbacks": [...]})` 上即可
- **自动对齐节点**：`on_llm_start` / `on_tool_start` 天然对应 THINKING / TOOL_CALLING

如果自定义节点来上报，会把状态逻辑混进 Agent 编排，Phase 2 后期换多 Agent 子图时全得重写。

### 谁来报什么

**规则：回调只报「进行中」，终态一律由调用方报。**

| 上报方 | 触发点 | 状态 | detail |
|---|---|---|---|
| `/chat` 入口 | 请求进入 | `PENDING` → `THINKING` | — |
| CallbackHandler | `on_llm_start` | `THINKING` | `{"phase": "llm"}` |
| CallbackHandler | `on_tool_start` | `TOOL_CALLING` | `{"toolName": ...}` |
| `/chat` 入口 | invocation 正常返回 | `COMPLETED` | `{"result": ...}` |
| `/chat` 入口 | invocation 抛异常 | `FAILED` | `{"error": ...}` |

**`TaskStateCallbackHandler` 刻意不实现 `on_llm_error` / `on_tool_error`。**
理由见下一节 —— 这不是遗漏，是踩过坑之后的决定。

### 为什么工具报错【不能】映射成 `FAILED`

直觉上 `on_tool_error` → `FAILED` 天经地义。实测（2026-10-04）证明它是错的。

`langgraph/prebuilt/tool_node.py` 的默认错误处理器是这样的：

```python
def _default_handle_tool_errors(e: Exception) -> str:
    if isinstance(e, ToolInvocationError):
        return e.message
    raise e          # ← 不是校验错误就原样抛出
```

于是两种工具错误的行为**完全不同**：

| 工具抛出 | ToolNode 的行为 | 图 |
|---|---|---|
| 普通异常（`ValueError`、Java 后端 500） | 重新抛出 | 异常穿透出图，`ainvoke` 整体失败 |
| pydantic `ValidationError` | 吞成 `ToolMessage(status="error")` | **继续跑**，Agent 可能随后给出正确答案，HTTP 200 |

而 `on_tool_error` 在**两种情况下都会触发**。

- 第一种：报 `FAILED` 是对的（任务确实死了）。
- 第二种：报 `FAILED` 是**灾难**。`FAILED` 是终态，Java 会锁死它；
  可图还在跑，后续的 `THINKING` / `COMPLETED` 会被 409 全部拒绝 ——
  **一个实际成功的任务被永久记成失败。**

而第二种恰恰是**最常见的**：用例 B（`structuredContent` 必须是 JSON object）
就是它。工具调用失败、Agent 把错误转述给用户、HTTP 200 —— 这条路径我们见过。

根本原因：**ReAct 循环存在的意义就是从工具错误中恢复**，
所以工具级的错误不是任务级的失败。只有 `/chat` 知道这轮 invocation 到底成没成。

> 工具调用的审计（调了哪个工具、成功与否、耗时）走 `tool_invocation` 表 ——
> schema 里本来就有这张表，不该拿任务状态机去兼职做它。

回归防线：`tests/test_task_state.py::test_tool_validation_error_does_not_report_a_terminal_state`

### 两条硬性约束

**1. 上报失败绝不能影响主流程。**

状态回调是**旁路**，不是主链路的一部分。Java 挂了、网络抖了，Agent 该跑完还是要跑完：

```python
async def _report(self, to_status: str, detail: dict) -> None:
    try:
        await self._client.patch(...)
    except Exception:
        logger.warning("状态上报失败 task=%s -> %s", self._task_id, to_status)
        # 吞掉, 不上抛
```

**为什么**：如果上报失败就中断 Agent，那 Java 侧一抖动就会让所有任务挂掉 ——
一个"可观测性"功能把"可用性"搞坏了，完全得不偿失。

**代价**：状态可能失真（Python 认为在跑，Java 记的是旧状态）。
兜底手段是 Java 侧的**超时检测**（Phase 2 后期）：任务停在非终态超过 N 分钟 → 标记异常。
这个兜底也正好是 `agent_task.retry_count` / `max_retry` 的用武之地。

**2. 上报要异步，不能阻塞 LLM 调用。**

`on_llm_start` 这类钩子跑在 Agent 的关键路径上，同步发 HTTP 会拖慢每一次 LLM 调用。
用 `asyncio.create_task` 扔进后台，或者用带缓冲的队列批量上报。

---

## 六、入口的变化（需要你拍板）

Phase 1 是用户**直接**调 Python：

```
用户 ──▶ POST :8000/chat ──▶ Agent
```

引入任务后应该变成：

```
用户 ──▶ POST :8083/api/tasks ──▶ Java 建任务(PENDING) ──▶ 调 Python :8000/chat
                 │                                              │
                 │◀────────── PATCH /status 回调 ────────────────┘
                 ▼
              返回 agentTaskId
```

**这是一个架构变化，不是纯增量。** 三个连带影响：

1. `/chat` 需要新增 `task_id` 参数（作为 thread_id 用）
2. Python 从"对外服务"变成"内部执行器"，接口可以不再暴露给用户
3. Phase 2 后期长任务改走 RocketMQ 时，Java 那一步从"同步调 Python"换成"投递 MQ"

**建议**：先保留 `/chat` 能独立调用（同时兼容带 `task_id` 和不带），
这样 smoke test 和手工调试不受影响，任务链路作为一条新路径并行验证。

---

## 七、落地步骤

按依赖顺序，每步都可独立验证：

| # | 做什么 | 验证方式 | 依赖 |
|---|---|---|---|
| 1 | **起 docker**，MySQL 建表 ✅ | `docker compose ps` 全 healthy；`SHOW TABLES` 看到五张表 | 只有你能做 |
| 2 | 新建 `km-task-service` :8083，任务 CRUD + 状态机校验 ✅ | `scripts/verify_task_service.py`（40+ 项断言，见下） | 1 |
| 3 | Python 加 `TaskStateCallbackHandler` ✅ | 单测：mock Java 端，断言上报序列 | — |
| 4 | `/chat` 支持 `task_id`，挂上 callback + 上报终态 ✅ | `tests/test_chat_task_wiring.py`（27 项）+ 真链路跑通 | 2, 3 |
| 5 | smoke test 加一条任务链路的用例 ✅ | `smoke_chat.py` 用例 T | 4 |
| 6 | 手动重试 API（FAILED→PENDING，新 trace / 新 attempt）✅ | `scripts/verify_retry.py`（43 项断言）+ MySQL / audit_log 双信道核对 | 2 |
| 7 | 自动重试（定时扫描 + 退避 + Java→Python 派发）✅ | `scripts/verify_auto_retry.py`（29 项断言，含假 Python 运行时） | 2, 6 |

**第 1 步是硬前置** —— 第 2 步之后全部依赖 MySQL。这也是为什么
docker-compose 悬了两周是个真问题：它不是"顺便验一下"，而是卡着整条路。

### 第 2 步的验证方式：为什么不是 `curl`

原计划写的是"`curl` 建任务、PATCH 状态"。实际不能用——Windows 控制台默认 GBK，
`curl` 直接传带中文的 JSON body 会被破坏，服务端报 `There was an error parsing the body`，
**错误信息完全指不到编码问题**。所以改成了 Python 脚本：

```
python km-control-plane/scripts/verify_task_service.py          # 打真服务
python km-control-plane/scripts/selftest_verify_task_service.py # 验脚本本身, 不需要 MySQL
```

后者是前者的**元验证**：用一个内存版假服务把真脚本跑一遍（对照组必须 33/33 全绿），
再注入 4 种变异（关掉 CAS / 幂等却写事件 / 终态可离开 / 不做流转校验），
逐个确认**对应的那条断言真的会红**。

这不是洁癖。写这个自检的时候，其中一项断言曾**恒为真**——它 grep 的是断言名，
而断言名在 `[OK]` 行里也出现。也就是说它是个纯装饰：变异明明注入了，
脚本却报"全过"。如果没有元验证，拿这份脚本验收会得到一个**看着可信的错误答案**。

### 第 2 步的实测结论（2026-10-09）

四项风险全部实测消掉，没有一项是靠"应该没问题"：

| 风险项 | 结论 | 证据 |
|---|---|---|
| MyBatis-Plus 在 Boot 4 上的自动装配 | ✅ | 服务启动，Mapper 全部工作 |
| `Jackson3TypeHandler` + `autoResultMap` 读写 JSON 列 | ✅ | 中文键名/值、嵌套对象、`null` 往返一致 |
| CAS 并发控制 | ✅ | 竞争真实发生且被挡住，见下 |
| 实体字段 / 列映射 | ✅ | 与 `01-schema.sql` 逐列对账 + 实际读写 |

**CAS 那条特别值得记**：6 个并发请求（3× `TOOL_CALLING`、3× `COMPLETED`，两者在
`THINKING` 下都合法）打同一个任务，**最终只产生了 1 条事件**。关键在于另外 5 个
请求一条都没写——如果 CAS 没生效，读到 `THINKING` 的失败者会各自写一条记录，
而 `TOOL_CALLING → COMPLETED` 在规则上是合法的，根本不会被 `isAllowed` 拦下。
能让它一条都不写的路径只有 CAS 的 `affected == 0` 分支。

这个判据是直接查 MySQL 得出的，**没有信 API 自己的回显**——同一份数据走两个
独立信道核对，才能排除"接口和数据库一起错"这种最坏情况。

### 第 4、5 步的实测结论（2026-10-09）

真链路跑通，四条服务全在（:8082 存量 / :8081 MCP 网关 / :8083 任务服务 / :8000 Agent）。
两条路径都拿到了真实的流转记录：

**成功**（`smoke_chat.py` 用例 T）:

```
None      -> PENDING       [JAVA_GATEWAY]
PENDING   -> THINKING      [PYTHON_RUNTIME]  {"phase": "entry"}
THINKING  -> TOOL_CALLING  [PYTHON_RUNTIME]  {"toolName": "get_order_by_id"}
TOOL_CALLING -> THINKING   [PYTHON_RUNTIME]  {"phase": "llm"}
THINKING  -> COMPLETED     [PYTHON_RUNTIME]  {"result": "..."}
```

**失败**（杀掉 MCP 网关逼出来的）:

```
None -> PENDING -> THINKING -> TOOL_CALLING -> FAILED
```

`/chat` 返 500，`errorMsg` = `ConnectError: All connection attempts failed`。

这两条序列验证了三件之前只是"设计上应该如此"的事：

1. **幂等在真链路上生效。** `/chat` 入口和 `on_llm_start` 各报了一次 `THINKING`，
   但事件表里只有**一条** `PENDING -> THINKING`。Java 的 `isNoop` 分支把它合并了。

2. **`on_tool_error` 没有抢着上报终态。** 失败那条里工具确实调用了、也确实报错了，
   但 `FAILED` 只出现一次，且是 `/chat` 在最后报的。如果按直觉把
   `on_tool_error` 映射成 `FAILED`，这里就会出现"图还在跑而任务已是终态"的经典
   死局 —— 这正是第五节点名要避免的那个 bug。

3. **`TOOL_CALLING -> THINKING` 这条 ReAct 回归边在真链路上被走到了。**
   在此之前它只有单测覆盖（`TaskStatusMachineTest`），是纯函数的正确性，
   不是端到端的事实。

### 第 6 步的实测结论（2026-10-28）

`scripts/verify_retry.py` 打真服务（MySQL + RocketMQ 都在），**43/43 通过**。
覆盖的不只是"接口返回 200"，而是七条容易悄悄退化的规则：

| 要验的事 | 结果 |
|---|---|
| 重试换新 trace，任务表指针滚过去 | ✅ |
| 旧 trace 再上报 / 再进 `/chat` 都 409 | ✅ |
| 上一次执行的 attempt 已闭链，新 attempt 是 RUNNING | ✅ |
| `retry_count` / `max_retry` 的 `>=` 边界（含 `maxRetry=0`） | ✅ |
| COMPLETED / PENDING 任务不能重试 | ✅ |
| 3 个并发重试只有 1 个成功，只产生 1 条 FAILED→PENDING 事件 | ✅ |
| 不存在的任务 404，且错误码是 `TASK_NOT_FOUND` | ✅ |

**没有只看 API 的回显**，直接查了库（两个独立信道）：

```
attempt_no=1  trace 364a280c  FAILED   ended_at 非空
attempt_no=2  trace 488db1d0  COMPLETED ended_at 非空
retry_count=1  max_retry=2  agent_task.trace_id=488db1d0

audit_log（按 id 排序）:
  364a280c  创建 -> THINKING -> FAILED
  488db1d0  FAILED -> PENDING  attemptNo=2  previousTraceId=364a280c
  488db1d0  PENDING -> THINKING -> COMPLETED
```

并发那条用例尤其能说明 CAS 生效：3 个请求同时打同一个 FAILED 任务，
**只多出 1 行 attempt、1 条事件、1 条审计**；如果 CAS 没带上
`retry_count`，就会有两个赢家，第二条 attempt 会撞唯一索引变成 500。

另外在真 Java 服务上跑了一遍 Python 的入口校验（`_verify_task_identity`）：

- COMPLETED + 当前 trace → 409，提示先调 `/retry`
- COMPLETED + 旧 trace → 409 `执行身份不匹配`
- PENDING + 当前 trace → 放行

### 第 7 步的实测结论（2026-10-29）

`scripts/verify_auto_retry.py` 起一个假的 Python 运行时（:18080），打真服务
（短退避 2s/4s、扫描 1s、租户 `retry-e2e`），**29/29 通过**：

| 要验的事 | 结果 |
|---|---|
| 失败后 0.5s 内不派发，退避到期后才派发 | ✅ |
| 自动重试换新 trace，事件 source=`SYSTEM_RETRY`，`previousTraceId` 接得上 | ✅ |
| 派发的 payload 带原始 intent / task_id / 新 trace / tenant | ✅ |
| `maxRetry=1`：自动重试一次后再失败就彻底停下，等两个退避周期也不再派发 | ✅ |
| Python 不可达：任务回 FAILED，事件 `phase=DISPATCH`，次数用尽后不再试 | ✅ |
| 没有 intent 的任务不重试，`retry_count` 不动 | ✅ |

过程中踩到一个真实问题，值得记下来：**假的 Python 第一版只读
`Content-Length`，而 JDK HttpClient 对未知长度的 body 用 chunked 发送**，
于是它读成空 body、回了 200，剩下的 chunk 字节被 `http.server` 当成下一个
请求的请求行 —— Java 收到的是莫名的 **HTTP 400**，第一轮因此 2 项失败。
服务端日志（`重试派发收到 HTTP 400`）才把方向指对。修的是假服务端的读
body 逻辑，不是产品代码：真的 FastAPI 一直支持 chunked。

这也解释了为什么派发失败要分三类：这次是 HTTP 4xx（"Python 回了错"），
如果当时把 4xx 也当"没送达"去关链，就会在 Python 可能已经开跑的情况下
制造双写。

回归防线：`RetrySchedulerTest`（8 项）+ `RetryDispatcherTest`（5 项）+
`TaskServiceTest` 里"带来源重试"一条。

### 一个从 Java 侧照搬过来会出事的断言

写 `smoke_chat.py` 用例 T 的时候，第一反应是加一条"事件的 `fromStatus` 不能重复"，
因为 `km-control-plane/scripts/verify_task_service.py` 里就有一条一模一样的。

**那是错的，两者不是一回事：**

| | 场景 | "重复"意味着 |
|---|---|---|
| Java 的验收脚本 | 多个请求**并发抢同一个状态** | CAS 失效，真 bug |
| smoke 用例 T | 一条**时间线** | ReAct 循环回到 THINKING，正常 |

`THINKING` 作为 `fromStatus` 在一条正常的 ReAct 时间线里**必然出现两次以上**。
照搬过来会让用例 T 永远失败，而且失败得很有说服力——看起来就像状态机坏了。

真正的结构不变量是**首尾相接**（每条事件的 `fromStatus` == 上一条的 `toStatus`）。
它同样能抓住并发覆盖造成的断链（两条事件的 `fromStatus` 都是 `THINKING` 时，
第二条就接不上了），但不会误伤循环。

回归防线：`tests/test_smoke_event_chain.py::test_合法的ReAct循环里THINKING重复出现_不应报错`

---

## 八、已定决策（2026-10-04）

| 决策 | 结论 | 连带影响 |
|---|---|---|
| 任务服务模块划分 | **单独开 `km-task-service` :8083** | 与 km-mcp-server 职责分离，各自可独立扩容 |
| `/chat` 入口 | **两条路径并存** | Python 侧同时支持带 / 不带 `task_id`；不带时行为与 Phase 1 完全一致 |
| 状态上报方式 | 逐条异步上报，走本地队列串行 | 见第五节；初期不做批量 |
| 终态归谁报 | **`/chat` 入口**，回调只报进行中 | 见第五节；`on_tool_error` → FAILED 是错的，已实测 |

前两条由 Ricardo 拍板。第三条是设计推导（串行上报保证事件表顺序，
而状态机的价值恰恰在于顺序）。
