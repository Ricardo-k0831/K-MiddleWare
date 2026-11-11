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

### 连接点：thread = 会话，任务用 checkpoint metadata 标记归属（2026-11-04 改版）

**旧方案**是 `agent_task_id` 直接当 `thread_id`：断点续跑不需要映射表，但代价是
对话记忆按任务隔离 —— 同一会话里连问两件事，第二个任务看不见第一个的上下文。

**现方案**把两个身份拆开：

| 身份 | 用途 | 落在哪 |
|---|---|---|
| `session_id` | 对话记忆的单位 | `thread_id = session:<tenant>:<session_id>` |
| `task_id` | 执行身份（状态、审计、重试） | checkpoint 的 `metadata.task_id` |

优先级（`app/checkpoint.py::resolve_thread_id`）：

- 有 `session_id` → 同一会话的多个任务共享一条消息历史（会话记忆）；
- 只有 `task_id` → `task:<task_id>`，记忆按任务隔离（兼容"一次任务一次调用"）；
- 都没有 → 用调用方给的 `thread_id`（缺省 `default`）。

**为什么带租户**：`session_id` 是调用方自己起的名字，只在租户内唯一；不带
`tenant_id` 的话，两个租户用了同一个 `session_id` 就会读到彼此的对话历史。

**重试怎么仍然不需要映射表**：重试只换 `traceId`，`task_id` 和 `session_id`
都不变，算出的 thread 一样；归属信息随 checkpoint 走，因此第 N 次执行能被
识别成"同一任务的续跑"，也不存在把执行位置存进 `agent_task.checkpoint_ref`。

### 持久化与断点续跑（2026-11-02）

checkpointer 之前固定是 `InMemorySaver`：进程一重启图状态就没了，"用同一个
`thread_id` 重新 invoke" 只在同一个进程里成立，断点续跑其实无从谈起。现在由
`km-agent-runtime/app/checkpoint.py` 装配，后端由 `CHECKPOINT_BACKEND` 显式选择：

| 值 | 实现 | 适用 |
|---|---|---|
| `memory`（默认） | `InMemorySaver` | 开发/测试；重启即丢 |
| `postgres` | `AsyncPostgresSaver` + `psycopg_pool.AsyncConnectionPool` | 断点续跑；落在 `km_memory`，启动时自动 `setup()` 建表 |

默认仍是 memory：改变持久化语义必须是显式决定，和 `km.retry` / `km.heartbeat`
默认关闭同一个理由。

**`/chat` 调用图之前先 `aget_state` 读快照，按 `snapshot.next` + 任务归属
分四种处置**（实现见 `plan_invocation`）：

| 快照 | 归属（metadata.task_id） | 处置 | 传给 `ainvoke` 的输入 | 为什么 |
|---|---|---|---|---|
| `next` 非空 | 就是本次 task | resume | `None` | 重试续跑自己的半截执行；再追加消息会把同一个问题问两遍 |
| `next` 非空 | 别的 task / 无归属 | **busy（409）** | 不调用 | 不能接管别人的推理过程 |
| `next` 空 + 最后一条是 AI 消息 | 就是本次 task | replay | `None` | 图已经跑完，只是终态上报丢了；复述结果，不再花一次 LLM |
| 其余 | — | fresh | `{"messages": [...]}` | 首次执行、新任务、或 checkpoint 已丢（如 memory 后端重启） |

自由对话（不带 `task_id`）只在"没有任务归属 + 重发的正是挂起前最后那条用户
消息"时才 resume；挂起状态属于某个任务时一律 409，不去碰正在跑的执行。否则
一律 fresh —— 用户在一个挂起的 thread 上问新问题时，空输入续跑会把新消息吞掉
（实测"带新输入 + 有待执行节点"的组合，LangGraph 会先写入新消息再重跑节点）。

> **为什么不用 `checkpoint_ns` 区分任务**（最初的设想）：`checkpoint_ns` 是
> 子图的命名空间。对根图传一个自造的 ns，`aget_state` 直接
> `ValueError("Subgraph ... not found")`；而且不同 ns 的 messages 互相隔离，
> 而会话记忆要的恰恰是共享。两者不可兼得（2026-11-04 实测）。
>
> **为什么归属放 metadata 而不是图 state**：它是"谁写下这个快照"的元信息，
> 不是对话内容；放 state 里会混进模型上下文，也会被消息裁剪逻辑碰到。
> LangGraph 会把 `config["metadata"]` 持久化到每个 checkpoint，`aget_state`
> 原样返回。

**同一会话必须串行**：两个任务同时写一个 thread 会互踩分支，最终状态只留下
一条，另一个任务的对话甚至它的 COMPLETED 上报都像没发生过。运行时用进程内
`_thread_lock(thread_id)` 把"读快照 → 决策 → 调用"整段串起来。这**只在单实例
部署下成立**；多实例要换 Postgres advisory lock 或按 `session_id` 分片。被占用的
会话返回 409，调用方等该任务结束（或心跳兜底关链）后重发。

> 旧 thread（`thread_id = task_id`）不会自动迁移：升级后历史对话记忆从空开始。

> **副作用边界**：resume 重跑的是"未完成的节点"。如果崩溃点在工具调用之后、
> 结果写回之前，那个工具会被再调一次。有副作用的工具（下单、支付）必须自带
> 幂等键 —— 这是 checkpoint 语义本身的边界，重试层兜不住。

**两个配套修正**：

- `tool_calls` 只统计本次 invocation 新增的消息（`plan.baseline_messages` 是调用前
  的条数）。持久化后 `result["messages"]` 是整个 thread 的历史，扫全部会把上一轮
  的工具名算进这一轮，验收脚本会误判 replay 为"重新执行过"。
- Postgres 连不上时降级为进程内记忆，并在 `/health` 的 `checkpoint_degraded` 里
  给出原因；配置名写错（如 `postgress`）则直接启动失败 —— 拼错不能静默变成
  "以为在持久化，其实重启就丢"。

**单实例提醒**：`thread_id="default"` 在 postgres 后端下是一个所有匿名调用共享、
且跨重启保留的全局线程。生产环境不带 `session_id` / `task_id` 的调用应当显式
传 `thread_id`；`session_id` 则是会话记忆的正常入口。

**Windows 启动要求**：psycopg 的异步连接不支持 Windows 默认的 ProactorEventLoop。
`--reload` 时 uvicorn 在子进程里用 Selector，所以开发命令照旧；不带 `--reload`
时必须加 `--loop asyncio:SelectorEventLoop`。漏了的话 `build_checkpointer` 会
当场给出这条提示（而不是等连接池 10 秒超时）。

会话级记忆已按上面的方案落地（`session_id` → thread，`metadata.task_id` →
归属）。尚未做的两件事：会话历史的长度控制（现在只增不减，需要时用
`trim_messages` + `pre_model_hook` 截断送给模型的消息，而不是删 checkpoint），
以及多实例下的跨进程会话锁。

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
POST   /api/tasks/{id}/heartbeat       执行心跳（Python 调这个）-> 204
POST   /api/tasks/{id}/retry           失败任务重试      -> 任务（含新 traceId）
POST   /api/tasks/{id}/cancel          主动取消任务      -> 任务 + 运行时结果
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
`JAVA_GATEWAY` / `PYTHON_RUNTIME` / `SYSTEM_RETRY` / `SYSTEM_HEARTBEAT`

`POST /api/tasks/{id}/heartbeat` 的请求体只有一个字段：

```json
{ "traceId": "cffac16e00000000cffac16e00000000" }
```

它**不改变状态、不写事件、不发审计** —— 只更新这次执行上的
`last_heartbeat_at`。204 成功；400 缺 traceId；404 任务不存在；
409 表示"别再跳了"（trace 对不上，或这次执行已经结束）。详见
[心跳兜底](#心跳兜底2026-10-31)。

### 控制面鉴权（2026-11-09）

在此之前 `/api/tasks/**` 是完全开放的：任何人能建任务、改状态、取消别人的执行。
审计里记的 `actor` 也只能取任务归属人——它回答"这是谁的任务"，回答不了
"刚才是谁动的"。

没有统一认证中心的前提下，这一版用**共享密钥 + 密钥表**：

```yaml
km:
  control-auth:
    enabled: true            # 默认开, 关了会 WARN
    keys:
      - key: dev-user-key-change-me
        tenant: demo
        user: demo-user
        role: USER
      - key: dev-service-key-change-me
        tenant: "*"
        user: python-runtime
        role: SERVICE
```

请求头固定为 `X-KM-Api-Key`（Python 侧同名常量在 `app/control_auth.py`）。

**两个角色，边界比租户更硬：**

| 角色 | 能做什么 | 不能做什么 |
| --- | --- | --- |
| `USER` | 建任务 / 查自己的任务 / 取消 / 重试 | 上报状态、发心跳 |
| `SERVICE` | 上报状态、发心跳；建任意租户的任务 | —— |

不允许 `USER` 上报状态，是因为**状态必须由真正执行的那一方报**。否则一个拿到
用户密钥的脚本就能伪造 `COMPLETED`，状态机的事实源就不再是事实源。

**错误码的语义分工：**

- 没有密钥 / 密钥不匹配 → **401 `UNAUTHENTICATED`**
- 有密钥但角色不够（USER 调 `/status` 或 `/heartbeat`）→ **403 `FORBIDDEN`**
- 跨租户读/改**已存在的任务** → **404 `TASK_NOT_FOUND`**，不是 403
- 建任务时**请求体显式声明了别的租户** → **403 `FORBIDDEN`**

第三种是刻意的：403 等于告诉对方"这个 ID 存在，只是你无权看"——对任务 ID
来说那就是一个可枚举的存在性探针。让跨租户和"真的不存在"不可区分，泄露面
才是零。第四种反过来：租户名是调用方自己写进请求体的，不存在信息泄露，
回 403 能让调用方知道参数写错了。

**actor 进审计：** 通过认证的取消/重试会把 `caller.userId()` 传进
`TaskService`，审计里的 `actor` 从此是"真正点按钮的人"，而不是任务归属用户。
内部调用（`RetryScheduler` 的自动重试）显式传 `"system"`——为空会退回任务
归属人，审计里看起来像是用户自己点的重试，误导排查。

**默认值的选择：** 默认开启 + 开发密钥。默认开才会在本地就跑起来，而不是
"生产记得开"；开发密钥写在被 git 跟踪的配置里，不是秘密，启动日志会专门
WARN 一次，生产用 `KM_CONTROL_USER_KEY` / `KM_CONTROL_SERVICE_KEY` 覆盖。
`enabled=false` 是明确关闭（本地临时调试用），同样会 WARN——安全开关不能
静默失效。

**为什么用 request attribute 而不是 ThreadLocal：** 请求一进线程池，
ThreadLocal 的清理就依赖 `finally`，漏一次就是"上一个请求的身份跟着下一个
请求跑"。过滤器写 `km.control.caller`，Controller 用 `@RequestAttribute` 读，
生命周期和请求本身绑定。

验收脚本：`km-control-plane/scripts/verify_control_auth.py`（25 项）。

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

- ~~**重试原因的操作者身份**：现在没有鉴权，审计的 `actor` 记的是任务归属
  用户，发起重试的人不记。~~ 2026-11-09 已补：`ControlAuthFilter` 把
  `X-KM-Api-Key` 解成 `CallerIdentity` 并塞进 request attribute，Controller
  拿它当 `actor` 传给 Service——审计里的 `actor` 现在是**真正点重试的人**，
  不再是任务归属用户。验收 `scripts/verify_control_auth.py` 用例 5。
- `/chat` 入口会拒绝终态任务（409，提示调用 `/retry`）；这是重试落地时
  一起补的，避免"Agent 白跑一整轮、所有上报都被拒"。

### 取消（2026-11-05）

状态机里 `CANCELLED` 一直存在，但在此之前**没有任何入口** —— 任务卡住只能等
心跳兜底把它关成 FAILED。取消补上的正是"运维/用户主动停下"这条边。

```
POST /api/tasks/{id}/cancel       请求体可省略 {"reason": "..."}
```

#### 允许从哪些状态取消

任何**非终态**（PENDING / THINKING / TOOL_CALLING）。PENDING 是"派发前反悔"，
另外两个是"跑到一半停掉"。

| 当前状态 | 结果 |
|---|---|
| PENDING / THINKING / TOOL_CALLING | 200，任务变 CANCELLED |
| CANCELLED | 200，**幂等**（不写第二条事件、不再发审计） |
| COMPLETED / FAILED | 409 `CANCEL_NOT_ALLOWED` |

对已经 CANCELLED 的任务幂等，是因为取消是故障时最可能被连点的按钮；让它
409 只会制造第二层混乱。

#### 顺序固定：先落库，再通知运行时

1. **Java（一个事务）**：CAS `status -> CANCELLED` + 写 `* -> CANCELLED` 事件
   （source `JAVA_GATEWAY`）+ 闭掉当前未闭链的 attempt + 发 `TASK_LIFECYCLE`
   审计（trace 用**被取消那次执行**的，不是任务表上的滚动值）。
2. **通知 Python** `POST /tasks/{id}/cancel`：停掉正在跑的 `ainvoke`，并把
   checkpoint 上的挂起状态收尾。

反过来写（先停 Python 再改状态）会有一个窗口：Python 已经停了，任务还是
THINKING，心跳兜底会把它当"卡住的执行"关掉，随后自动重试又把它拉起来 ——
用户以为自己已经取消了。

通知失败**不会**让接口失败（仍返回 200，`runtime.reached=false`）：取消的
业务决定在事务提交时已经生效，Python 的后续上报会因为执行已闭链被 409 拒掉。
代价只是那次 LLM 调用可能继续跑到自然结束。

#### 为什么 Python 必须收尾 checkpoint

图崩溃/被停时通常挂在 `agent` 节点上（`snapshot.next=('agent',)`）。只要
`next` 非空，`plan_invocation` 就会对同会话的下一个任务返回 BUSY —— 而这个
任务已经是 CANCELLED，永远不会有人来续跑它。会话就被一个死任务**永久占住**。

收尾方式（`app/checkpoint.py: release_pending`）：

- 以 `agent` 节点身份写入一条**没有 tool_calls 的 AI 消息**
  （"上一个任务已被主动取消…"）。ReAct 图的条件边据此判 END，`next` 变空。
- 不直接删 checkpoint：LangGraph 没有公开的删除 API，而且删掉会连会话历史
  一起抹掉 —— 取消一个任务不该让整段会话失忆。
- 只收尾 `metadata.task_id` 等于自己的挂起；别人的挂起不碰。
- 写完再读一次确认 `next` 真的空了，避免"以为释放了、实际还占着"。

#### 已验过

- 单元：`TaskServiceTest`（状态/事件/闭链/审计 trace、幂等、终态拒绝）、
  `PythonCancelNotifierTest`（请求体、失败不抛）、`TaskCancellationServiceTest`
  （先后顺序）、`test_checkpoint.py`（真图收尾 + 归属校验）、
  `test_chat_task_wiring.py`（真取消一个卡住的 ainvoke）。
- 真栈（2026-11-05，假模型挂住 30s 制造"正在跑"的窗口）：
  1. 任务 A（`session:cancel-e2e:s-55bf14a1`）进入 THINKING、`ainvoke` 卡在
     LLM 调用上 → `POST /api/tasks/{A}/cancel` 返回 200，任务 CANCELLED，
     `runtime = {reached:true, stopped:true, released:true}`；
     原 `/chat` 请求连接被直接掐断（客户端拿到空响应体）。
  2. 事件链 `PENDING -> THINKING (PYTHON_RUNTIME) -> CANCELLED (JAVA_GATEWAY)`；
     `audit_log` 上同一 trace 的 `CREATED / THINKING / CANCELLED` 三条
     `TASK_LIFECYCLE` 全部落库，没有断裂。
  3. 同会话再发任务 B → 200 并 **COMPLETED**（走完一次真实工具调用），
     证明 `release_pending` 真的把 BUSY 占位清掉了，而不是"取消完就永久 409"。
  4. 对 A 再取消一次 → 200、状态仍 CANCELLED、`runtime.stopped=false`（幂等路径）。

#### 还没做的

- **多实例**：`_RUNNING` 是进程内登记表，取消请求必须落到持有那个运行的实例上；
  否则只能收尾 checkpoint，停不掉正在跑的 LLM 调用。
- ~~**取消原因的操作者身份**：和重试一样，没有鉴权，`actor` 记的是任务归属用户。~~
  2026-11-09 已补，同上一节：`actor` 是调用方身份，不是任务归属人。

### 心跳兜底（2026-10-31）

自动重试补上了"失败之后怎么办"，但还有一类任务它永远看不到：**根本没有变成
FAILED**。典型场景是派发读超时（请求可能已经在跑），或者 Python 接到请求后
进程崩了 / 被 kill / 事件循环卡死。任务停在 PENDING（或 THINKING /
TOOL_CALLING），`agent_task_attempt.ended_at` 一直为 NULL —— 自动重试只扫
FAILED，于是这条任务永远不再被推进；未闭链的 attempt 还会让将来的重试被判
"数据不一致"而拒绝。

#### ★ 判据是"探活"，不是"心跳旧了" ★

心跳停了有三种解释：

1. Python 真的死了
2. Python 活着，但连不上 Java（网络分区、Java 侧收不到）
3. Java 自己收不到

后两种情况下任务其实还在跑。**只凭"心跳过期"就关链 = 制造双写**：Java 把
任务写成 FAILED 并触发自动重试，Python 那边还在跑同一份工作，两边的终态上报
互相 409。

所以协议做成**双向**：

| 方向 | 接口 | 谁问谁 | 含义 |
|---|---|---|---|
| push | `POST /api/tasks/{id}/heartbeat` | Python → Java | "这次执行还活着"的时钟 |
| pull | `GET /tasks/{trace}/alive` | Java → Python | "你那儿还有这条执行吗" |

扫描只负责**发现可疑**（证据过期），能不能关链完全取决于探活的回答：

| 探活结果 | 含义 | 处理 |
|---|---|---|
| `alive: true` | Python 正在跑 | 刷新 `last_heartbeat_at`，不动状态 |
| `alive: false` | Python 明确说没有 | 关链 → `FAILED`，source=`SYSTEM_HEARTBEAT` |
| 404 / 5xx / 连不上 / 字段缺失 | 问不出来 | **什么都不做**，下一轮再问 |

第三行是安全边界：Python 还是旧版本（没有这个接口）、网络抖动、响应格式改了，
都不能被当成"死了"。一次接口不同步造成的批量误杀，比晚关几条任务严重得多。

#### 扫描范围：必须有"存活证据"

```sql
WHERE ended_at IS NULL
  AND COALESCE(last_heartbeat_at, dispatched_at) IS NOT NULL
  AND COALESCE(last_heartbeat_at, dispatched_at) <= :cutoff   -- now - stale_after
ORDER BY started_at ASC LIMIT 20
```

`dispatched_at` 由 `RetryDispatcher` 在 POST `/chat` **之前**写入（
`TaskService.markDispatched`）—— 先留证据再发请求，否则"请求发出去了但进程
在写标记前崩溃"这条执行就没人能发现。

★ 为什么不按"PENDING 超过 N 分钟"扫 ★ 时间本身不构成证据。一个刚创建的任务
可能合法地停在 PENDING 等调用方发起（首次执行由外部触发，不是 Java 派的）。
`dispatched_at` / `last_heartbeat_at` 两者都为空，恰好就是"没人能为这条执行
作证"，直接排除。

#### 为什么关成 FAILED，而不是 CANCELLED

这次执行没完成，也不知道做到哪一步 —— 语义上就是失败。更重要的是：
**FAILED 是唯一的重试入口**。关成 CANCELLED 会让任务永久停在"人工取消"，
而用户并没有取消过。关成 FAILED 之后，`RetryScheduler` 按自己的退避和次数
上限决定要不要再试 —— "怎么处理失败"仍然只有一套策略。

关链走 `TaskService.reportStatus`，不是自己写 SQL：状态、事件、闭链、审计
必须在同一个事务里，否则系统里就有了第二套状态机。

#### Python 侧的顺序契约（★ 错了会打架 ★）

```
register(trace)          -- 在入口身份校验【之前】
  ... ainvoke ...
  drain terminal report  -- 先保证终态发出去
unregister(trace)        -- 最后才撤销
```

- 登记早了没关系，登记晚了不行：校验窗口内轮到探活就会得到 `false`，误关。
- 撤销早了不行：探活答 `false` → Java 立刻关链 → 那条 COMPLETED 还在队列里，
  到达时 execution 已经闭链，只能 409。**"还在跑"宁可多答几毫秒，不能少答。**

同一个 trace 并发跑了两份（客户端超时重发 `/chat`）时，登记按**引用计数**：
先结束的那份不删登记，等最后一份退出才答 `false`。少了这一层，第一份一结束
Java 就会把还在跑的第二份判死。

#### 开关、默认值与灰度

默认**关闭**（`km.heartbeat.enabled=false`）—— 它会自己把任务判死，进而触发
自动重试（花钱），打开必须是显式决定。默认值：

| 配置 | 默认 | 说明 |
|---|---|---|
| `km.heartbeat.interval-ms` | 15000 | 多久扫一次，只影响发现延迟 |
| `km.heartbeat.stale-after-seconds` | 120 | 证据多久算过期，必须 ≫ Python 心跳间隔（15s） |
| `km.heartbeat.batch-size` | 20 | 一轮最多处理多少条 |
| `km.heartbeat.tenant` | 空 | 灰度；空 = 全部 |
| `km.heartbeat.probe-url` | `KM_PYTHON_CHAT_URL` | 探活和派发是同一个 Python 运行时 |

```bash
KM_HEARTBEAT_ENABLED=true \
KM_HEARTBEAT_TENANT=heartbeat-e2e \
KM_HEARTBEAT_STALE_AFTER_SECONDS=3 \
KM_PYTHON_CHAT_URL=http://127.0.0.1:18081 \
mvn -pl km-task-service spring-boot:run

python km-control-plane/scripts/verify_heartbeat_sweep.py
```

验证脚本覆盖 5 个用例：正常心跳不被探活、探活 alive 只刷新、探活 dead 关链后
被自动重试救回、探活 5xx 不动、心跳接口 204/400/404/409 且不写事件。

#### ★ 单实例前提 ★

`GET /tasks/{trace}/alive` 查的是 Python 进程内的内存登记。当前部署是单个
uvicorn 进程，所以"本进程没有它" = "没人在跑它"。**多实例部署时这个等式不
成立**：负载均衡可能把探活打到另一台，它会诚实地回答 `false` —— 那就是误判。
那时必须把登记放到共享存储（Redis key + TTL），或者让探活带实例亲和性。
代码注释里也写了这一条，别在扩容时忘了。

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
兜底手段是 Java 侧的**心跳兜底**（2026-10-31，见
[心跳兜底](#心跳兜底2026-10-31)）：证据过期后 Java 反向探活 Python，
**只有 Python 亲口回答"没有这条执行"才关链**。单看超时时间就判死是不行的
—— 那会把"网络不通"当成"进程死了"，制造双写。

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

1. `/chat` 需要新增 `task_id` 参数（执行身份）和 `session_id` 参数（对话记忆
   的单位；没有时退回 `thread_id = task:<task_id>`）
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
| 8 | 心跳兜底（Python push 心跳 + Java 反向探活，确认死亡才关链）✅ | `scripts/verify_heartbeat_sweep.py`（5 个用例）+ `tests/test_heartbeat.py` + `HeartbeatSweeperTest` | 2, 7 |

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

### 第 8 步的实测结论（2026-10-31）

`scripts/verify_heartbeat_sweep.py` 起一个假的 Python 运行时（:18081），打真服务
（`stale=3s`、扫描 1s、心跳 0.4s、退避 1s/2s、租户 `heartbeat-e2e`），
**32/32 通过**：

| 要验的事 | 结果 |
|---|---|
| 心跳接口：204 / 400（缺 traceId）/ 404（任务不存在）/ 409（trace 不符、执行已结束） | ✅ |
| 心跳不写事件行（连续 3 次心跳，事件数不变） | ✅ |
| 正常心跳（0.4s 一次）全程没有被探活 | ✅ |
| 没有心跳但探活 `alive=true`：不关链、留在 PENDING，两次探活间隔 ≈ stale 周期 | ✅ |
| 探活 `alive=false`：关成 FAILED，source=`SYSTEM_HEARTBEAT`，detail 带 `phase=HEARTBEAT` / trace / 证据时间 | ✅ |
| 关链后自动重试开了新链并跑到 COMPLETED（真正救回来） | ✅ |
| 探活 500（UNKNOWN）：什么都不做，任务留在 PENDING | ✅ |

实施顺序上有一点值得记：老 volume 不会重跑 `docker/init/mysql/01-schema.sql`，
所以新列必须走 `docker/migrations/2026-10-31-heartbeat.sql`（幂等，
`information_schema` 判断后再 `PREPARE/EXECUTE`）：

```bash
docker exec -i km-mysql mysql -ukm -pkm_dev_pwd km_control \
  < docker/migrations/2026-10-31-heartbeat.sql
```

脚本本身也踩了一个坑，和产品无关但很像真的：用例 2/4 收尾时用**建任务时的
trace** 上报 COMPLETED，稳定 409 `EXECUTION_MISMATCH` —— 任务已经被自动重试
滚到新链了。正确做法是拿 `/chat 派发时收到的 trace_id` 上报。这就是"换指针
和开新链在同一事务里"的另一面：**任何拿旧 trace 上报的地方都会失败**，
包括验收脚本。

回归防线：见 [审计 §11.9](audit.md#十一还没做的部分) —— 那一节末尾列了
`HeartbeatProbeTest` / `HeartbeatSweeperTest` / `TaskServiceTest` /
`tests/test_heartbeat.py` / `tests/test_chat_task_wiring.py` 的覆盖点。

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
