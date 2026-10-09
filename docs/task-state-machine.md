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
```

关键点：`TOOL_CALLING → THINKING` 是**合法且高频**的流转 ——
Agent 调完工具要回到思考。一次 `/chat` 可能走好几轮。

### 合法性规则

**校验必须在 Java 侧做**，这是"控制面"的实质含义：
Python 只能**上报**，不能**决定**状态。

| 规则 | 说明 |
|---|---|
| 终态不可离开 | `COMPLETED` / `FAILED` / `CANCELLED` 是终点，任何回转都是 bug |
| 不能跳级 | `PENDING` 不能直接到 `TOOL_CALLING`（必须先 THINKING） |
| 同态上报幂等 | 重复上报当前状态视为成功、不写事件（见下方"幂等"） |
| 非法流转记为异常 | 拒绝更新，但**记 WARN 日志** —— 它说明 Python 侧逻辑有问题 |

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
POST   /api/tasks                      创建任务          -> { agentTaskId }
GET    /api/tasks/{id}                 查询任务（Redis 优先, miss 回落 MySQL）
GET    /api/tasks/{id}/events          状态流转历史
PATCH  /api/tasks/{id}/status          状态上报（Python 调这个）
```

`PATCH /api/tasks/{id}/status` 请求体：

```json
{
  "toStatus": "TOOL_CALLING",
  "source": "PYTHON_RUNTIME",
  "detail": { "toolName": "get_order_by_id", "arguments": {"orderId": "SO20260901001"} }
}
```

`source` 取值（与 `agent_task_event.source` 字段对应）：
`JAVA_GATEWAY` / `PYTHON_RUNTIME` / `SYSTEM_RETRY`

### 一次状态上报，Java 做四件事

1. **校验流转合法性** —— 不合法则拒绝（返回 409），并记 WARN
2. **写 MySQL** —— 更新 `agent_task.status`，同时向 `agent_task_event` 插一条（append-only）
3. **更新 Redis** —— 缓存当前状态，供高频查询
4. **终态时补 `finished_at`**，并（将来）触发 MQ 通知

> `agent_task_event` 是 append-only 的：`agent_task.status` 记"现在是什么"，
> 事件表记"怎么变成的"。审计和排查全靠它。

### 幂等性

回调走网络，**重复投递是常态**，不是异常。Java 必须能安全处理重复上报：

- 目标状态 == 当前状态 → 直接返回 200，**不写事件表**（否则事件流会被噪音淹没）
- 加 `requestId` 去重（可选，Phase 2 后期再做）

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
| 2 | 新建 `km-task-service` :8083，任务 CRUD + 状态机校验 ✅ | `scripts/verify_task_service.py`（33 项断言，见下） | 1 |
| 3 | Python 加 `TaskStateCallbackHandler` ✅ | 单测：mock Java 端，断言上报序列 | — |
| 4 | `/chat` 支持 `task_id`，挂上 callback + 上报终态 ✅ | `tests/test_chat_task_wiring.py`（13 项）+ 真链路跑通 | 2, 3 |
| 5 | smoke test 加一条任务链路的用例 ✅ | `smoke_chat.py` 用例 T | 4 |

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
