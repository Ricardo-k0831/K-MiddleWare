# 分层采样审计设计

四层审计，统一用 RocketMQ 异步汇聚到同一个数据源，以 `task_id` + `trace_id` 作标识键。

本文记录的是**设计决策和踩过的坑**。逐行的实现理由写在代码注释里（`km-audit-core`
的 `AuditEvent` / `AuditPublisher`，网关的 `McpToolAudit` / `CallStatus` /
`McpGatewayAuditConfig`），本文不重复。

---

## 一、为什么要四层

审计要回答的问题，跨越了四个不同的进程：

| 层 | `AuditLayer` | 进程 | 采集什么 |
|---|---|---|---|
| Agent 运行时 | `AGENT_RUNTIME` | Python :8000 | LLM 调用、token 数、Agent 请求的工具调用 |
| MCP 网关 | `MCP_GATEWAY` | Java :8081 | Agent 调了什么工具、拿到什么 |
| 存量微服务 | `LEGACY_SERVICE` | 由网关代理 | 向后端发了什么请求、它花了多久 |
| 任务服务 | `TASK_SERVICE` | Java :8083 | 任务状态怎么变的 |

四层里最特别的是 `LEGACY_SERVICE`：**存量服务一行代码都不用改**，是网关
"代它记"的。这是"零改造接入也能被完整审计"的证明，也意味着
`AuditLayer` 同时描述两件事——审计**对象**和审计**采集点**。

### 采集拓扑

```
MCP_GATEWAY    ─┐
LEGACY_SERVICE ─┤  (Java, 生产者直连 broker)
TASK_SERVICE   ─┼──▶ topic: KM_AUDIT ──▶ AuditLogConsumer ──▶ audit_log (MySQL)
                │    (按层打 tag)         (km-task-service 里)
AGENT_RUNTIME  ─┘
   └─(Python, 不能直连 broker)─ HTTP POST /api/audit/events ─▶ 由 km-task-service 转投
```

**只有一个 topic、只有一个消费者、只有一个入库口。** 加一层采集不需要碰数据库——
采集方只管往 MQ 发，落库是任务服务的事。将来审计量大到要独立伸缩时，把
`AuditLogConsumer` 和 `AuditLogMapper` 一起搬走即可，对采集方零影响。

这是走 MQ 而不是"各层直连数据库"换来的东西。

**Python 那一层是绕了一下，但绕得不多。** 它本来应该和别的生产者一样直连
broker，实际上不行：Windows 上没有可用的 Python RocketMQ 客户端，而且
`broker.conf` 里 `brokerIP1 = 127.0.0.1` 会让容器化的 client/proxy 根本连不上。
所以 Python 发 HTTP 给 km-task-service，由**已经是生产者的那个 Java 进程**转投。

绕这一下换来的好处比想象中多：**Java 继续持有唯一的生产者身份**，MQ 的认证、
序列化、采样（第七节）、幂等（第八节）全都只有一份实现，不会出现"Python 绕过
采样直接全量灌库"这种事。代价是多一跳 HTTP——在审计这条旁路上一跳不算什么。

---

## 二、信封：跨层跨语言的唯一契约

`com.km.audit.AuditEvent` —— 一个 15 字段的 record。

**字段是照着 `audit_log` 的列设计的，一一对应。** 这不是巧合：一一对应意味着
映射过程不做任何判断，也就没有把信息丢在半路的机会。一旦信封字段比表少，
消费端就必须"猜"剩下那列填什么——猜错一次，以后所有历史数据都是错的。

| 信封字段 | 列 | 说明 |
|---|---|---|
| `eventId` | `event_id` | 消费端幂等去重的**唯一**依据 |
| `taskId` / `traceId` | `agent_task_id` / `trace_id` | 两个标识键 |
| `tenantId` | `tenant_id` | |
| `layer` / `eventType` | `layer` / `event_type` | "谁采集的" / "发生了什么" |
| `actor` | `actor` | 用户 ID 或 Agent ID |
| `status` | `status` | 见第六节 |
| `latencyMs` | `latency_ms` | |
| `tokenIn` / `tokenOut` | `token_in` / `token_out` | 仅 `LLM_CALL` |
| `requestBody` / `responseBody` | 同名 | JSON 文本 |
| `errorMsg` | `error_msg` | 见第六节 |
| `occurredAt` | `occurred_at` | **epoch millis**，不是格式化字符串 |

`occurredAt` 用 epoch millis 是刻意的：格式化字符串要约定时区和格式，
约定错了就是几小时的系统性偏差，而时间戳没有解释空间。落库时才转
`DATETIME(3)`，那是消费端的事。

### 会话 / 任务 / 一次执行 / 调用链：四个边界

这四个词经常被混着用，但它们是四个不同的东西，基数也不一样：

| 概念 | 代码里 | 边界（从哪到哪） | 基数 |
|---|---|---|---|
| 会话 | `agent_task.session_id` | 用户视角的一段连续对话 | 1 会话 : N 任务 |
| 任务 | `agent_task.agent_task_id` | 用户交代的**一件事**，有自己的状态机 | 1 任务 : N 次执行 |
| 一次执行（= 一条调用链） | `trace_id` | 一次 `POST /chat` → 一次 `ainvoke` 跑到终态，跨三个进程 | 1 链 : N 事件 |
| 链内的一步（工具 / LLM 调用） | 无 ID，回调里按 `run_id` 配对 | `on_tool_start→end` / `on_llm_end` | 1 步 : N 条审计行 |

**调用链的边界是"一次执行"，不是工具调用，也不是会话。**

- **不按工具调用切**：ReAct 循环里的「LLM → 工具 → LLM」是同一件事的连续步骤，
  切开就回答不了"这次任务为什么失败"。而且一次 MCP `tools/call` 本来就会产生
  三行审计（运行时 / 网关 / 存量服务）——那是一条链里的**一个步骤的三个观察点**，
  不是三条链。
- **不按会话切**：会话可以持续几小时、跨好几个任务，链太长，失败无法归因。

**怎么判断"产生了一次新的调用"？** 看 `trace_id` 是不是新的。这里有个必须说清
的前提：`/chat` 目前**分不清"第二次调用"和"第一次调用的超时重试"**——两者字段
一模一样。所以 `trace_id` 要同时当**幂等键**用：同一个 `trace_id` 重入视为同一次
执行（丢弃 / 拒绝 / 返回已有结果），新 `trace_id` 才是新的一次调用。否则重试会在
审计里长出一条新链，token 和耗时也会翻倍。要做到这一点，`trace_id` 必须由**发起
一次执行**的人生成，而不是任务创建时生成一次就永久固定。

**`task_id` 和 `trace_id` 的关系是 1:N。** 不是 1:1，也不是"一个会话一条链"：

```
会话 S
├── 任务 A "我的订单到哪了"      trace T1 ── LLM → get_order_by_id → LLM
└── 任务 B "那个订单我要退款"    trace T2 ── LLM → refund_order → LLM

任务 A 因超时被重跑（task_id 不变）：
                               trace T3 ── 第二遍执行的完整链路
```

跨任务的关联一律用 `session_id`，**不要用 trace_id 串**。一个 `task_id` 也可以横向
跨多次重试（每次一个新 trace_id）。只留一个 ID，另一个维度就查不了了。

> 2026-10-28 手动重试落地后，这个 1:N 是真的了：`create()` 生成第一次执行的
> trace，`retry()` 每调用一次生成一条新链并插入一行新的 `agent_task_attempt`。
> 2026-10-29 自动重试接上后，`retry()` 多了一个调用方，但生成 trace 的地方
> 仍然只有这一个 —— Python 从不自己造 trace。
> `agent_task.trace_id` 的语义固定为**最近一次执行**的 trace（滚动指针），
> 权威来源是 `agent_task_attempt`。旧链不会失效于"时间"，而失效于
> **任务表指针已经滚走**——拿旧 trace 进 `/chat` 或 PATCH 上报都会 409。

两者都作为 RocketMQ 的消息 key 写入（空格分隔），所以按任一维度都能在控制台查到消息。

### 为什么用 builder 而不是构造参数

15 个字段的 `new AuditEvent(a, b, c, ...)` 里放错一个位置编译器不会报错——
只要两个字段都是 `String`，`taskId` 和 `traceId` 就能悄悄对调，而症状是
"审计查不到东西"，排查起来离现场非常远。

Builder 上还有一道守卫：`layer` 或 `eventType` 为 null 时直接抛异常。这两个
字段缺失的事件落库后既分不出采集层也分不出类型，等于一条废数据。

> ⚠️ **这道守卫拦不住从 MQ 来的事件。** 消费端走的是 Jackson 的直接构造，
> 不经过 builder。所以 `AuditLogConsumer.validate()` 不是重复劳动，而是
> 唯一的防线——缺 `eventId` 会撞 NULL 约束，缺 `layer` 会让映射抛 NPE，
> 而这两种异常都会被当成"数据库写失败"去重试，一条**永远写不进去**的消息
> 于是永远在重试，顺便把后面的消息全堵住。

---

## 三、★ 网关凭什么知道这次调用属于哪个任务 ★

这是整套设计里唯一需要动 MCP 传输层的地方，也是最初那个问题的落点。

MCP 协议里**没有"任务"这个概念**。Python Agent 连上网关后，网关看到的只是
一个匿名会话和一堆 `tools/call`。任务 ID 只存在于上层业务里。

链路是：

```
Python 在请求头带 X-KM-Task-Id / X-KM-Trace-Id / X-KM-Tenant-Id / X-KM-Actor
      │  (头名定义在 com.km.audit.AuditHeaders)
      ▼
McpTransportContextExtractor<ServerRequest>          ← McpGatewayAuditConfig
      │  每个 HTTP 请求被调一次
      ▼
McpTransportContext
      │  顺着 Reactor context 流进工具方法
      ▼
@McpTool 方法上的 McpTransportContext 参数             ← OrderMcpTools
      │  TaskIdentity.from(...)
      ▼
AuditEvent.taskId / traceId  ──▶  audit_log.agent_task_id / trace_id
```

### 关键点

**（1）协议给了出口，但默认关着。** `WebMvcStreamableServerTransportProvider`
的 builder 上有 `contextExtractor`，它的默认值是 `request -> McpTransportContext.EMPTY`
——也就是"什么都不抽"。不配就是拿不到，而且**不报任何错**。

**（2）必须整个替换 transport provider bean。** SDK 没给"只加一个 extractor"的口子。
Spring AI 的自动配置建了一个默认 provider，上面裹着 `@ConditionalOnMissingBean`，
所以 `McpGatewayAuditConfig` 里注册一个同类型的 bean 就能顶掉它。

代价是必须**原样复刻**那几条 builder 参数（`jsonMapper` / `mcpEndpoint` /
`keepAliveInterval` / `disallowDelete`）。漏掉哪条，哪条的配置项就静默失效——
比如漏了 `mcpEndpoint`，`/mcp` 会变回默认路径，Python 客户端 404，而错误现象
完全指不到这里。

**（3）★ 身份是逐请求的，不能只在握手时带一次。 ★**

这点最容易设计错。MCP 的 streamable 传输是**有传输会话**的：一次 `initialize`
之后，几十上百次 `tools/call` 复用同一个 MCP 会话。如果身份在 `initialize` 时定死，
一个 MCP 会话就只能服务一个任务——而 Agent 的真实用法是在一个**用户会话**
（第二节的 session）里连续处理多个任务，复用同一条 MCP 连接。

> 注意这里有两个"会话"：MCP 传输会话是连接级的（协议概念），用户会话是
> 业务级的（`agent_task.session_id`）。前者几十次工具调用共用，后者包含多个任务，
> 两者没有对应关系。

查过源码，协议实现的做法正是逐请求抽取：`handlePost` 每次都对当前 HTTP 请求
调一次 `contextExtractor`，结果只作用于那一个请求的处理链。方向是对的，
**客户端必须配合**。

**（4）taskId 不能写成 `@McpToolParam` 让 LLM 填。**

那等于让模型自己声明"我是哪个任务"。它完全可能填错或编一个——而审计的全部
价值就在这个字段上。传输层填的才是可信的。

**（5）漏带头不失败，但要显眼。** 客户端漏带头或有人直接 curl 调试时，审计照记，
只是 `taskId` 为空。网关会打一条 WARN——**这是客户端的 bug，不该被静默吞掉**，
否则"审计查不到东西"会被误当成存储问题去排查。

`extractKmIdentity` 本身**不**告警：`initialize`、DELETE、GET 建流这些请求
本来就不该带任务头，在这里告警等于天天喊狼来了。

### 客户端那一半：Python 怎么把身份送出去

网关只负责"读得到"，身份还得有人送。这一半在
`km-agent-runtime/app/mcp_adapter/task_identity.py`。

**走 `httpx.Auth`，不走 `MultiServerMCPClient` 的 `headers`。** 这是整件事里
唯一一个看起来像变通的点，但它是必需的：

- `headers` 是**连接级**的，构造 client 时定死。而 Agent 是长驻进程，会连着跑
  任务 A、任务 B、任务 C。
- 用 `headers` 的后果**不是缺数据，是把数据记到了错的地方**——任务 B 的工具调用
  带着任务 A 的 ID 落库，那条记录看起来完全正常。
- `httpx.Auth.async_auth_flow` 是 httpx 给的**每个出站请求执行一次**的口子，
  正好对应网关那边"每个 POST 抽一次"。

名字叫 `Auth` 但做的不是鉴权，是身份透传。`Authorization` 本来也从这条路径加，
所以语义上不算离题。两者的分工是：`Authorization` 回答"你是哪个 Agent"
（整个进程一个身份），`X-KM-Task-Id` 回答"这次调用属于哪个任务"。

**当前身份用 `ContextVar` 存。** 请求处理是 asyncio 并发的，同一时刻可能有好几个
任务在跑。全局变量会串号，`threading.local` 在 asyncio 下形同虚设（所有协程跑在
同一个线程里）。`ContextVar` 的值跟着当前任务走，自动传播进 await 链和
`create_task` 出来的子任务，彼此隔离。

作用域要**恰好**包住一次 `ainvoke`（`main.py` 里的 `with task_identity(...)`）。
包大了、或者忘了还原，症状同样是"任务 B 被记到任务 A 名下"。

**身份是"携带"的，不是"生成"的。** `traceId` 只在 Java 侧生成，而且只在
"开始一次执行"时生成：`TaskService.create()`（第一次执行）和
`TaskService.retry()`（第 N 次执行）。`POST /api/tasks` 与
`POST /api/tasks/{id}/retry` 的响应里都有 `traceId` / `tenantId` / `userId`，
调用方原样转交给 `/chat` 即可。Python 侧拿到的永远是"这次该用哪条链"，
不参与决定。

Python 侧**不兜底生成**一个 `traceId`。生成的话会得到两个：任务表里是 Java 的，
审计表里是 Python 的，同一个任务被劈成两条对不上的链路。**缺了就拒绝**——任务
链路缺 `trace_id` 直接 400，和任务对不上 409，都在入口就停（`/chat` 先
`GET /api/tasks/{id}` 核对）。宁可跑不成，也不要跑完留下一条归不了位的链。

**`ContextVar` 穿得过 LangGraph 的工具调度**（2026-10-14 实测，langgraph 1.2.11）：
异步工具走同一条 asyncio 任务；同步工具虽然被丢进线程，但 langgraph 用的是
`asyncio.to_thread`——它按设计会复制当前 context，所以身份照样跟得过去。
`tests/test_task_identity.py` 直接跑真的 `ToolNode` 钉住这个假设：哪天 langgraph
换了调度方式，测试会红，而不是等到线上发现审计里一片 NULL。

---

## 四、一次工具调用为什么产生三条事件

```
Agent ──▶ AuditCallbackHandler  ──▶ AGENT_RUNTIME  (工具名 + Agent 请求的入参)
   │
   └──tools/call──▶ 网关
                     │
                     ├─ McpToolAudit         ──▶ MCP_GATEWAY    (工具名 + 参数 + Agent 收到的结果 + 总耗时)
                     │
                     └─ OrderServiceClient   ──▶ LEGACY_SERVICE (真实 HTTP 路径 + 后端耗时)
```

看起来重复，其实三条回答的是三个不同的问题：

- `AGENT_RUNTIME`：**Agent 决定要调什么**
- `MCP_GATEWAY`：**Agent 调了什么、拿到了什么**
- `LEGACY_SERVICE`：**向后端发了什么请求、它花了多久**

前两条的差别最值得说：**靠网关回答不了"Agent 到底调了没有"**。只查网关会得到
"这个任务没调过工具"的结论，而它可能是错的——真相是 Agent 调了，请求根本没到
网关（网关没起来、SSE 会话断了、身份头被拒了）。反过来也一样：网关有一行，
Agent 那边没有，说明有别的东西在发 tools/call。

后两条只有当"工具 ↔ 后端调用"是一对一的时候才显得像重复。等出现"一个工具扇出
到三个后端服务"时，区别立刻就出来了——而那时再加 `LEGACY_SERVICE` 采集点，就得
回头补历史数据的口径。

两条耗时相减还是有用的：**总耗时 − 后端耗时 = 网关自己的开销**。

**各记一半，别记两遍。** 同一次调用里：

- 响应体只有 `MCP_GATEWAY` 记（Agent 收到的就是它），`AGENT_RUNTIME` 那条**刻意
  留空**——同一份数据记两遍，一万次里只要有一次不一致，查审计的人就得先花时间
  判断哪个是对的。
- `LEGACY_SERVICE` 同理不记响应体。
- 入参两层都记，但记的**不是同一个东西**：运行时记的是 Agent 送出的，网关记的是
  它收到的。两者有可能不同（中间有序列化、有封装），这个对比才有意义。

代价是查一次调用要按 `trace_id` 把两层的行拼起来——为了不出错，值。

### 为什么不用 AOP 切 `@McpTool`

这是 `McpToolAudit` 存在的主要理由，也是这套设计里最容易踩空的一步。

`OrderMcpTools` 会把所有异常吞掉，转成一段自然语言的错误 Map 返回给 Agent
（这是刻意的——Agent 拿到技术堆栈没法自愈）。于是从切面视角看，工具方法
**永远正常返回**，审计就永远记成 OK。结果是最该被看见的那些失败，一条都没留下。

这不是切面写错了，是切面**放错了位置**：它切在"方法有没有抛异常"上，而这里
真正要观测的边界在方法内部——后端调用那一下。

所以审计点落在 `McpToolAudit.invoke(identity, tool, params, body, onFailure)` 里，
包住真正会失败的那一步，并且能直接拿到异常对象。

这还有个额外好处：**"什么算失败"由异常对象决定，不是由工具返回的 Map 长什么样决定。**
于是它是一条能写下来、能测的规则（`CallStatus`），而不是"返回的 map 里有没有
`error` 键"这种靠约定维系的隐式耦合。

---

## 五、投递：绝不抛异常

`AuditPublisher.publish()` 是唯一的出口，它**永远不抛异常，没有返回值，也不保证送达**。

审计是旁路。MQ 挂了、broker 抽风、序列化炸了——业务该怎么走还怎么走。如果
`publish` 能把异常抛回调用方，那就等于"审计系统一抖，业务就挂"，这是完全颠倒的
优先级：我们宁愿丢一条审计，也不愿让用户的任务失败。

同一条原则在 Python 侧已经存在（`task_state.py` 里"上报失败绝不能影响主流程"）。

**降级而不是挡在启动上**：`producer.start()` 要连 namesrv，如果 RocketMQ 没起，
这里不让 Spring 启动失败——只记一条 error 然后把生产者置空，之后所有 publish
静默丢弃。本地只想调接口、不想开全套中间件时，这个行为决定了你还能不能干活。

**事务边界**：`TaskService.create` 是 `@Transactional`，如果插完库就直接发 MQ，
事务后面完全可能回滚，结果是"库里没有这个任务，但审计里有一条任务已创建"。
所以 `publish` 检测到有活动事务时，注册 `afterCommit` 回调，推到提交之后再发。

注意 `occurredAt` 仍然是**事件发生**的时刻，不是提交的时刻——落库的时间差本来
就是审计要记录的信息之一。

---

## 六、`status` 和 `error_msg` 各回答一个问题

两个字段**不是同义词**，这点值得单独说，因为它反直觉：

- `status` —— 这次调用**算不算系统故障**
- `error_msg` —— 这次调用**到底抛了什么**

于是会出现 `status='OK' AND error_msg IS NOT NULL` 的行。这不是数据错位。

### 4xx 记 OK

网关调后端，后端返回 404"订单不存在"。从 HTTP 看这是个错误响应；从**系统**看，
这次调用完全成功——后端活着，它明确地回答"没有这个东西"。真正值得报警的是
另一种：连不上、5xx、超时——那时你才知道"订单查不到"是因为**系统坏了**。

如果 404 也记 ERROR，后果是错误率被用户输错订单号的行为污染。而"Agent 老在查
不存在的订单"本身是**提示词质量问题**，它的证据在 `responseBody` 里（工具返回的
`error: true` 那段话），不在 `status` 里。一个指标想表达两件事，最后两件都表达不清。

唯一的例外是 **429**：限流是容量问题，必须算故障，否则永远没人去扩。

判定规则集中在 `CallStatus` 里，而不是散落在各个 catch 块——分散了的话，同一件事
在不同地方会被记成不同的 status，按 status 做的统计就全部不可信。

### 顺带修掉的一个谎话

改之前，`get_order_by_id` 无论后端是"明确回了 404"还是"根本连不上"，给 Agent 的
都是同一句话：*"没有找到订单号 X 对应的订单"*。

对第二种情况，这句话是**谎话**——后端挂了的时候，网关根本不知道这个订单存不存在。
而 Agent 会忠实地把它转述给用户，用户于是以为自己的订单丢了。

现在按同一个 `CallStatus` 分支：404 说"没有找到"，其它说"后端服务异常，这不能
说明该订单不存在"。**判断依据是同一个方法，所以"审计里记的"和"Agent 看到的"
永远一致**——不会出现审计说是故障、用户被告知"订单不存在"这种对不上的情况。

---

## 七、分层采样

`km.audit.sample-rate.<LAYER>`，没配的层回落到 `default-sample-rate`。默认全采。

**为什么按层而不是全局**：四层的量级差得非常远。任务生命周期事件一单就几条，
而 `LLM_CALL` 一次任务可能上百条。全局一个采样率要么把生命周期事件采没了
（查不到任务怎么走的），要么被 LLM 调用淹掉（MQ 和库都撑不住）。

**失败事件永远上报，采样只作用于成功事件。** 采样是为了在高量下省成本，
不是为了把问题藏起来——一条 ERROR 恰恰是最该留下的那条。如果采样把失败也随机
丢掉，你会得到"线上偶发失败、日志里查不到"这种最难查的局面。

---

## 八、幂等：至少一次投递

RocketMQ 保证的是**至少一次**，所以重投是常态而不是故障。

去重靠 `audit_log` 上的唯一键 `uk_audit_event (event_id)`。消费端用**普通 insert**，
撞键就抛 `DuplicateKeyException`，由消费端把它认成"重投"。

### ★ 踩过的坑：别用影响行数判断"是不是重投" ★

曾经写成 `INSERT ... ON DUPLICATE KEY UPDATE id = id`，靠"影响行数为 0 就是重复"
来判断。在 mysql 命令行里验过，`ROW_COUNT()` 确实返回 **0**，看起来没问题。

但走 JDBC 时 MyBatis 打出来的是 `<== Updates: 1`。原因是 **Connector/J 默认设置
了 `CLIENT_FOUND_ROWS` 标志**，服务端于是回报"**匹配到的**行数"而不是"实际改动的
行数"——重复插入匹配到了一行，于是报 1。同一句 SQL，命令行和驱动给出不同的数字。

后果**不是数据错**（唯一键仍然挡住了重复行），而是**统计和日志全错**：重投被当成
新入库计数，那条"已去重"的日志永远不会出现，运维看不到正在发生重投。

这类错误比崩掉更难发现，因为它删不掉任何东西，只是让你对系统状态的认知是错的。

**修法**：接住唯一键冲突这个事实本身，不要去数行数。也没有用"在 JDBC URL 里加
`useAffectedRows`"来修——那会把一个对正确性至关重要的决策藏进连接字符串。

### 消费要从最早开始

新消费组的默认值是 `CONSUME_FROM_LAST_OFFSET`，也就是"从我出生这一刻之后才开始收"。
对业务消息这是对的（重放历史订单是灾难），但审计恰恰相反——它的全部意义就是
"发生过的事都留着"。

用默认值的话，第一次部署时 topic 里已有的历史事件会被**静默跳过**：不报错、
不告警，只是那批数据永远查不到了。所以 `AuditLogConsumer` 显式设置
`CONSUME_FROM_FIRST_OFFSET`。

---

## 九、实测结论（2026-10-14）

环境：`docker compose` 全套 + 三个 Java 服务 + Python 运行时。

### ★ 四层贯通：同一个 task_id 横跨三个进程 ★

`km-agent-runtime/scripts/verify_identity_headers.py` 证明身份从 Python 走到库，
但那条路径绕过了 `/chat`。真正的验收是真跑一轮 Agent：

```
POST :8083/api/tasks        → taskId=ce6756a0... traceId=cffac16e... userId=u-verify
POST :8000/chat             → 带 taskId + traceId + tenantId + actor
                              （Agent 自己调了 get_order_by_id，回复了订单状态）
```

```sql
SELECT id, layer, agent_task_id, trace_id, actor, status,
       JSON_UNQUOTE(JSON_EXTRACT(request_body,'$.tool')) AS tool
FROM audit_log
WHERE agent_task_id='ce6756a017994dbda59f771bfaf224ec' ORDER BY id;
```

```
35  TASK_SERVICE     ce6756a0...  cffac16e...  u-verify  OK   NULL
36  LEGACY_SERVICE   ce6756a0...  cffac16e...  u-verify  OK   NULL
37  MCP_GATEWAY      ce6756a0...  cffac16e...  u-verify  OK   get_order_by_id
```

（那一轮 `AGENT_RUNTIME` 还没接上，所以只有三层——**四层贯通的实测在第十节**。）

**三层挂在同一个 task_id 和同一个 trace_id 上**，而它们分别产自三个不同的进程、
两种语言。`trace_id` 是 Java 在创建任务时生成、经 `/chat` 原样转交的——
Python 一次都没有自己造过 ID。

单独验一次 `verify_identity_headers.py` 也是必要的：它覆盖 `/chat` 覆盖不到的
两件事——**同一个 client 连着发两次请求时身份会不会换过来**（这条失败了说明
身份退化成了连接级的），以及**不带身份时是否如实记成匿名**。实测：

```
27  LEGACY_SERVICE  task-identity-verify-A  trace-identity-verify-A  verify-script
28  MCP_GATEWAY     task-identity-verify-A  trace-identity-verify-A  verify-script
29  LEGACY_SERVICE  task-identity-verify-B  NULL                     verify-script
30  MCP_GATEWAY     task-identity-verify-B  NULL                     verify-script
31  MCP_GATEWAY     NULL                   NULL                     NULL
32  LEGACY_SERVICE  NULL                   NULL                     NULL
```

B 那两行 `trace_id` 为 NULL 是**对的**——脚本只给了 taskId，Python 就只带了
`X-KM-Task-Id` 一个头，没有编造一个 trace 出来。

### 网关身份链路

`km-control-plane/scripts/verify_gateway_audit.py`（借 km-agent-runtime 的环境，
用官方 mcp 客户端，不自己拼握手和 SSE 解析）。三条路径：带身份 / 不带身份 /
带身份查不存在的订单。

```sql
SELECT id, layer, agent_task_id, trace_id, status, latency_ms, actor,
       JSON_UNQUOTE(JSON_EXTRACT(request_body,'$.endpoint')) AS endpoint,
       JSON_UNQUOTE(JSON_EXTRACT(request_body,'$.tool'))     AS tool,
       error_msg IS NOT NULL AS has_err
FROM audit_log WHERE id > 18 ORDER BY id;
```

```
19  MCP_GATEWAY     task-gw-verify-001  trace-gw-verify-001  OK   68  verify-script  NULL                            get_order_by_id  0
20  LEGACY_SERVICE  task-gw-verify-001  trace-gw-verify-001  OK   61  verify-script  GET /api/orders/SO20260901001   NULL             0
21  LEGACY_SERVICE  NULL                NULL                 OK    4  NULL           GET /api/orders/SO20260901001   NULL             0
22  MCP_GATEWAY     NULL                NULL                 OK    5  NULL           NULL                            get_order_by_id  0
23  LEGACY_SERVICE  task-gw-verify-001  trace-gw-verify-001  OK    9  verify-script  GET /api/orders/SO20990101999   NULL             1
24  MCP_GATEWAY     task-gw-verify-001  trace-gw-verify-001  OK   16  verify-script  NULL                            get_order_by_id  1
```

逐条说明它能证明什么：

- **19/20**：身份从请求头一路走到数据库。`latency 68 − 61 = 7ms` 是网关自己的开销。
- **21/22**：同样的调用**不带任何头**——工具照常返回数据，但 `task_id` / `trace_id` /
  `actor` 全是 NULL。**这一条是关键**：它证明 taskId 确实来自请求头，不是别的什么
  碰巧填上的。
- **23/24**：查一个不存在的订单。两条都是 `status='OK'`（后端明确回答 404，
  不是故障），但 `error_msg` 都非空（`HttpClientErrorException$NotFound`），
  且 Agent 收到的是"没有找到订单号…"而不是"后端服务异常"。

### 中文字节完整性

GBK 控制台下 `mysql -e` 里中文会显示成 `?`，**永远不要据此判断数据损坏**。
让数据库用 ASCII 回答：

```sql
SET @found = UNHEX('E689BEE588B0');   -- 找到
SELECT id, JSON_UNQUOTE(JSON_EXTRACT(response_body,'$.message'))
             LIKE CONCAT('%', @found, '%') AS has_zhaodao
FROM audit_log WHERE agent_task_id='task-gw-verify-001' AND layer='MCP_GATEWAY';
```

得到 `14 → NULL`（那条是成功订单，没有 `message` 键）、`18 → 1`。

> ⚠️ **`mysql -e` 里的 SQL 字面量同样会被 GBK 破坏**，而且产生的是**假阴性**：
> 一句 `LIKE '%物流%'` 会返回 0 行，看起来像数据坏了，其实字节完全正确。
> 保持 SQL 纯 ASCII，中文写成 `UNHEX(...)`。另外**不要用中文列别名**——
> `SELECT ROW_COUNT() AS 第一次插入` 是语法错误 1064，不是显示问题。

### 单元测试

当时的规模（`AuditPublisherTest` 12 / 网关 32 / 任务服务 8，Python 51）已经被
后续工作超过，**完整清单见第十节第 6 条**——这里不再抄一份，免得两处数字
各自漂移。

网关那四个测试把事件**发出去再读回来**（走一遍完整的 JSON 序列化往返），
而不是直接抓 `AuditEvent` 对象——因为真正要验的东西有一半在序列化里：
record + Map 字段能不能往返、中文会不会坏、枚举会不会被写成下标而不是名字。
消费端做的是完全相同的一次往返，所以这里看到的坏法就是线上会有的坏法。

Python 侧最重要的一条是 `test_同一个client连着发两次_两次用的是各自的身份`。
把实现退回"往 `AsyncClient` 的 `headers` 上加"它就会红——那正是这个模块存在的
全部理由。

---

## 十、四层审计全部落地（2026-10-14 续）

上一节里"尚未做"的第 1、2 条已经做完，**四层现在都有生产者**。

### 1. `POST /api/audit/events` —— Java 侧的提交入口

入口放在 km-task-service 上，不新起服务：审计的落库目标 `audit_log` 就在它的
数据源里，**中继挨着目标库放**比挨着生产者放合理——生产者本来就要发 HTTP，
多一跳少一跳没区别，而少一个连数据库的进程是实打实的好处。

```
POST :8083/api/audit/events      body:   [AuditEventRequest, ...]
                                 header: X-KM-Audit-Token: <共享密钥>
→ 202 {"accepted": n, "received": m, "rejected": r, "unverified": u, "late": l}
→ 401 认证失败（token 缺失或不匹配）
```

**批量，不是单条。** 一次 LLM 调用只产生一两条事件，但一个任务几十条，每条
一个 HTTP 请求意味着几十次往返——而这些往返对调用方是**纯开销**。数组进、
计数出，`accepted != received` 就是丢数据的证据：审计最怕的不是丢，是**丢了
没人知道**。

**202 而不是 200。** 这个接口的语义是"收下并转投 MQ"，不是"已落库"。返 200
会让人以为现在查得到。

**校验是全有全无的。** 50 条里 1 条 `layer` 为 null，整批 400，那 49 条好的
也跟着没。这是有意的：报文是我们自己的 Python 拼的，出现一条畸形几乎必然
意味着拼装逻辑坏了，剩下 49 条同样不可信。反过来"悄悄丢掉 1 条、照常收下
49 条"才是真正危险的——丢的那条没人会知道。

**容错分两道，管的是两类问题。** 边界那道（`@NotNull` / 枚举反序列化）挡的是
**畸形报文**；`AuditIngestService` 那道逐条 try/catch 挡的是**映射代码自己的
bug**。两道都不重复，去掉任何一道，另一个类别的错都会静默变成"库里少了几行"。

> 实测踩到的：Spring Framework 7 下 `List<Dto>` 参数上的 `@Valid`，元素级约束
> **会**被级联到，不需要写成 `List<@Valid Dto>`；抛的是
> `HandlerMethodValidationException`，**不是** `MethodArgumentNotValidException`。
> 这两条光看代码看不出来，所以在 `AuditControllerTest` 里用一个
> "显式传 null 的 layer" 的用例钉住了——字段缺失可能死在反序列化那一步，只有
> 显式 null 才能证明元素级校验真的生效。

**认证和身份核对是后来补的**（见下面第 7 条）。这里只强调一点：这两件事管的
不是同一个问题——token 管"你能不能写"，attempt 核对管"你写的这条是否自洽"。

### 2. Python 侧的生产者（`app/audit.py`）

**eventId 由生产端生成。** 它是消费端幂等去重的唯一依据，没有它，"至少一次
投递"就等于"重复行"（第八节那个用影响行数判断重投的坑是同一件事的另一面）。

**入队前先 `json.dumps(body, allow_nan=False)` 序列化一次。** 这是踩出来的：
一个不可序列化的入参会让 httpx 在组装请求时炸——**而那时它已经和别的事件躺在
同一批里了**，结果是整批一起丢，一条畸形带走 49 条好数据。`allow_nan=False`
是顺手捡到的第二个好处：Python 默认把 NaN 写成裸的 `NaN`，那不是合法 JSON，
Jackson 会 400 掉整批。

**只对任务链路开。** 不带 `task_id` 的 `/chat` 一条审计都不发（有测试钉着）。
每条审计记录都要能归属到一个任务，没有 `task_id` 的调用记出来是一堆查不到
一起的匿名行——那种数据不是"少"，是"没法用"，还会拉低整张表的信噪比。

**上报什么**：`LLM_CALL`（耗时 / token 数 / 模型 / finish_reason——**token 数
只有这一层拿得到**，网关和任务服务都看不见）、`MCP_TOOL_CALL`（工具名 +
入参）。

> **`MCP_TOOL_CALL` 在网关和运行时两层都记，这不是重复**——理由见第四节。
> 两边用**同一个键名** `{"tool":..., "params":...}`，就是为了
> `JSON_EXTRACT(request_body,'$.tool')` 在两层写法完全一样。

### 3. `TaskService.reportStatus` 发状态流转事件

现在每一条状态流转都落一行 `TASK_SERVICE TASK_LIFECYCLE`，`request_body` 是
`{"from":..., "to":..., "source":"PYTHON_RUNTIME", "detail":{...}}`。**查审计
就能看到任务怎么走的**，不用再去翻 `agent_task_event`。

**这一行的 `trace_id` 来自 `agent_task_attempt`，不是 `agent_task`。** 上报
报文里的 `traceId` 必须对应该任务一次未闭链的执行，Java 找到 attempt 之后
用它的 `trace_id` 盖章。原因是溯源信息必须不可变：`agent_task.trace_id` 是
"最近一次执行"的滚动值，重试一开始就会变，从那里取 trace 会把第一遍执行
的在途事件盖成第二遍的——单看每一行都正常，只有按 trace 拼链路才发现拼不上。

**重试也发一条 TASK_LIFECYCLE，盖的是新链的 trace。** `request_body` 沿用
同一形状 `{"from":"FAILED","to":"PENDING","source":"JAVA_GATEWAY","detail":{...}}`，
`detail` 里额外有 `attemptNo` / `traceId` / `previousTraceId` / `reason`。
于是审计里能看到两条完整的链，且能回答"第二遍是从第一遍的哪条链接过来的"：

```
trace 364a280c: 创建 -> THINKING -> FAILED
trace 488db1d0: FAILED -> PENDING (attemptNo=2, previousTraceId=364a280c)
                -> THINKING -> COMPLETED
```

`previousTraceId` 是**冗余**的（`agent_task_attempt` 里本来就能按 attempt_no
推出前后关系），冗余在这里是值得的：审计是旁路，查一条链的来历不该再要求
联表。注意它只用于**链与链之间**的父子关系，跨任务仍然只用 `task_id`。

### ★ 4. `occurred_at` 的定义——这条是端到端跑出来才发现的 ★

第一版把 `occurred_at` 写成了**回调结束的时刻**，端到端一比就露馅了：

```
        layer           latency  occurred_at
修复前  AGENT_RUNTIME   503ms    20:43:46.124   ← Agent 那一层
        MCP_GATEWAY       7ms    20:43:46.099   ← 网关那一层(更早)
修复后  AGENT_RUNTIME   467ms    20:45:44.402   ← Agent 那一层(更早)
        MCP_GATEWAY       4ms    20:45:44.848   ← 网关那一层
```

修复前那条时间线在说"**网关先收到、Agent 后发起**"——物理上不可能。这个错
讨厌的地方在于：单独看每一层都完全正常，只有把两层并排看才看得出来。

定义固定成 **`occurred_at` = 调用【发起】的时刻**，和网关那条 `MCP_TOOL_CALL`
的 `startedAt` 对齐。修复后两层对同一件事的 `occurred_at` 差值是 MCP 传输
开销（~450ms，localhost 上也不算小），而不是"谁先谁后"的颠倒。

### ★ 各层 `occurred_at` 的口径不一样，这必须知道 ★

| 层 | `occurred_at` 是什么时刻 | 时钟来源 |
|---|---|---|
| `TASK_SERVICE` | Java **应用**这次流转的时刻 | Java |
| `AGENT_RUNTIME` | 这次 LLM / 工具调用**发起**的时刻 | Python |
| `MCP_GATEWAY` | 网关**发起**对存量服务调用的时刻 | Java |
| `LEGACY_SERVICE` | 同上（网关代存量服务记的） | Java |

`TASK_SERVICE` 用"Java 应用它的时刻"是有道理的——**Java 是任务状态的唯一
事实源，"生效"就是那一刻**。但它的代价是：`TASK_LIFECYCLE` 的时间戳会**滞后
于**触发它的那次动作（实测 446ms:运行时工具在 44.402 开始，`TOOL_CALLING`
那行在 44.851 发出——中间隔着状态上报的入队 + HTTP 提交）。

所以：`ORDER BY occurred_at` 得到的因果顺序是可信的，但**跨层的毫秒差值不要
当网络延迟去优化**——不同进程、不同时钟、不同"发生"的定义。要秒级以上的
结论，可以；要毫秒级的，自己去埋点。

### 5. 实测结果

`km-agent-runtime/scripts/verify_audit_pipeline.py`（真建任务 + 真跑 Agent 真调
工具真调 LLM，不 mock 任何一层），`ORDER BY occurred_at, id`：

```sql
SELECT id, layer, event_type, status, latency_ms, token_in, token_out,
       JSON_UNQUOTE(JSON_EXTRACT(request_body,'$.tool')) AS tool, occurred_at
FROM audit_log WHERE agent_task_id='f519040216df4c0ca8f34311916b955c'
ORDER BY occurred_at, id;
```

```
56  TASK_SERVICE   TASK_LIFECYCLE  OK  NULL   NULL  NULL  NULL              20:45:42.206
58  AGENT_RUNTIME  LLM_CALL        OK  1250   647   44    NULL              20:45:43.150
57  TASK_SERVICE   TASK_LIFECYCLE  OK  NULL   NULL  NULL  NULL              20:45:43.174
62  AGENT_RUNTIME  MCP_TOOL_CALL   OK  467    NULL  NULL  get_order_by_id   20:45:44.402
60  MCP_GATEWAY    MCP_TOOL_CALL   OK  4      NULL  NULL  get_order_by_id   20:45:44.848
59  LEGACY_SERVICE MCP_TOOL_CALL   OK  3      NULL  NULL  NULL              20:45:44.849
61  TASK_SERVICE   TASK_LIFECYCLE  OK  NULL   NULL  NULL  NULL              20:45:44.851
64  AGENT_RUNTIME  LLM_CALL        OK  1462   760   103   NULL              20:45:44.877
63  TASK_SERVICE   TASK_LIFECYCLE  OK  NULL   NULL  NULL  NULL              20:45:44.895
65  TASK_SERVICE   TASK_LIFECYCLE  OK  NULL   NULL  NULL  NULL              20:45:46.356
```

十条记录，**四个层，一个 `agent_task_id`**，一次 LLM 调用（`1250ms / 647→44`
token）加一次工具调用（经网关，后端只花了 3ms）。`id` 的顺序和 `occurred_at`
的顺序**不一致**（57 比 58 先入库，却比它晚 24ms）——这是对的，`id` 反映的是
**发布顺序**，`occurred_at` 反映的是**发生顺序**，排时间线要用后者。

`TASK_LIFECYCLE` 那五行逐条查出来是：

```
CREATED                             phase=CREATED  sessionId / intent
PENDING      → THINKING             source=PYTHON_RUNTIME  detail.phase=entry
THINKING     → TOOL_CALLING         source=PYTHON_RUNTIME  detail.toolName=get_order_by_id
TOOL_CALLING → THINKING             source=PYTHON_RUNTIME  detail.phase=llm
THINKING     → COMPLETED            source=PYTHON_RUNTIME  detail.result=...
```

### 6. 单元测试（今天的量）

```bash
cd km-control-plane && mvn install
# km-audit-core        AuditPublisherTest                 12
# km-mcp-server        TaskIdentityTest                     6
#                      CallStatusTest                       8
#                      McpToolAuditTest                    10
#                      OrderServiceClientAuditTest          8
# km-task-service      TaskStatusMachineTest               11   (含重试边)
#                      AuditIngestServiceTest              24   (映射 + 身份核对矩阵)
#                      CachedAttemptLookupTest              6   (缓存的三种结果)
#                      GuardedAttemptLookupTest             5   (500ms 截止 + 熔断)
#                      AuditIngestAuthFilterTest            5   (认证边界)
#                      AuditControllerTest                 10   (本项目第一个 web 测试)
#                      TaskServiceTest                     15   (执行身份 + 溯源 + 重试两入口)
#                      RetrySchedulerTest                   8   (退避 / 批次 / 租户灰度)
#                      RetryDispatcherTest                  5   (payload + 失败分类)
#                      ReportStatusRequestTest              3   (traceId 必填契约)

cd km-agent-runtime && uv run pytest
# 103 passed, 3 skipped   (跳过的是 3 条 MCP 握手用例: 8081 没起)
```

`AuditControllerTest` 是**本项目第一个 web 测试**。它验的不是业务，是
**跨语言契约的形状**：字段名就是信封上的名字，多一个少一个都不行。因为
Python 那边拼错一个键名，表现不是报错，而是**那一列永远是 null**——而且
看起来完全正常。`AuditIngestServiceTest` 是纯单测（不起 Spring 上下文），
它证明的是"一条坏事件不会带走整批"。

Python 侧最值得留着的一条是
`test_事件的occurredAt是开始时刻而不是结束时刻`——它守的是上面第 4 条那个
只靠并排比对才能发现的错。

### 7. 审计摄入的信任边界（2026-10-26）

第十一节的第 1 条和第 4 条（入口不校验身份）在这里一起收口。分两层，因为
它们回答的是两个不同的问题：

| 问题 | 机制 | 失败时 |
|------|------|--------|
| 你有没有权写审计 | `X-KM-Audit-Token` 共享密钥 | 401，请求进不了控制器 |
| 你写的这条是否自洽 | 拿 `traceId` 查 `agent_task_attempt` | 矛盾拒收 / 未知按模式处理 |

**为什么 token 不做成按调用方区分的凭据。** 现在只有 Python 运行时一个
提交方，按调用方发 token 是在给一个还不存在的问题写代码。布局不用变：将来
MCP 网关也要直报时，多配一个 token 即可。

**为什么比较用 `MessageDigest.isEqual`。** `String.equals` 遇到第一个不同的
字节就返回，比较耗时因此与"猜对了几个字符"相关。`isEqual` 对等长数组做定长
比较。长度不同仍会提前返回，但不引入依赖、比 `equals` 强，对这个威胁模型够了。

**空 token = 明确关闭，不是静默关闭。** 配空时过滤器放行，但启动日志里打一条
WARN。默认值 `dev-audit-token-change-me` 写在 YAML 里（所以它不是秘密），
用在默认值上时再单独 WARN 一次——生产必须用 `KM_AUDIT_INGEST_TOKEN` 覆盖。

**身份核对复用 `agent_task_attempt`，不新建表。** 这张表已经是"一次执行"的
权威记录：(task_id, attempt_no) 唯一、trace_id 全局唯一、`ended_at IS NULL`
表示还在跑。审计要核对的正是这些，再造一张表只会多一份需要同步的真相。

规则矩阵：

| 情况 | WARN（默认） | STRICT |
|------|--------------|--------|
| 没有 traceId | 不查表，放行 | 不查表，放行 |
| trace 查到，taskId 一致 | 放行，verified | 放行，verified |
| trace 查到，taskId 缺失 | 用 attempt 上的 taskId 补齐 | 同左 |
| trace 查到，taskId 矛盾 | **拒收** | **拒收** |
| trace 查无 | 放行，计 `unverified` | 拒收 |
| 查询失败 / 超时（库故障） | 放行，计 `unverified` | **放行**，计 `unverified` |

最后一行是刻意的：**数据库故障不等于"这个 trace 不存在"**。审计入口不该额外
引入一次"必须查库成功才能记录"的同步依赖——故障期间恰是最需要审计的时候。
所以 `AttemptLookup` 的契约把"返回 null（查无）"和"抛异常（查不动）"分开，
缓存层也保证**异常不进缓存**。

### ★ 实测出来的 30 秒：降级必须带硬截止时间 ★

第一版直接在请求线程里查库，规则全对，但把 MySQL 停掉之后实测：

```
POST /api/audit/events  ->  202 {"accepted":1,"unverified":1}   time=30.024930s
```

卡满的是 Hikari 的 `connectionTimeout`（默认 30 秒）。这 30 秒让降级失去意义：

- Python 的审计上报超时是 **2 秒**，客户端早就丢掉整批了，服务端再"收下"没用；
- 每个事件占着一个 Tomcat 线程，数据库一挂，审计入口会反过来拖垮任务服务；
- STRICT 模式更糟：本该"查不动就放行"，实际是"先卡 30 秒再说"。

所以加了 `GuardedAttemptLookup` 这一层装饰器：

| 机制 | 参数 | 作用 |
|------|------|------|
| 硬截止 | 500ms | 查库扔进小线程池，当前线程最多等 500ms |
| 熔断 | 失败后 5s | 冷却期内直接抛，不再提交——一批 50 条不会变成 50 × 500ms |
| 有界池 | 2 线程 / 32 队列 | 满了直接拒绝并熔断，宁可少核对也不让积压吃内存 |

超时/失败抛 `AttemptLookupUnavailableException`，`AuditIngestService` 收到后
按"未核实"放行。冷却结束后的第一次查询就是探测，没有额外的"半开"状态。

同一套停机场景复测：

```
MySQL 停机后第一条:  202 {"accepted":1,"unverified":1}   time=0.513s
熔断中的第二条:      202 {"accepted":1,"unverified":1}   time=0.005s
MySQL 拉起后新任务:  202 {"accepted":1,"unverified":0}   verified
```

> 500ms 的来历：Python 超时 2 秒的四分之一，留足回包余量；正常查库是毫秒级
> （还有缓存兜着），只有真出问题时才会用满。后台那次 JDBC 调用仍会走完 30 秒，
> 但已经没人等它了——线程是 daemon，不会拖住 JVM 关闭。

### 端到端实测（2026-10-26，docker 起来后）

```
无 token / 错 token                -> 401，投递 0 条
已验证 (task1 + trace1)            -> 202 accepted=1 unverified=0
缺 taskId                          -> 202 accepted=1，库里 task_id 已补齐
taskId 与 trace 矛盾               -> 202 accepted=0 rejected=1，库里无此行
未知 trace (WARN)                  -> 202 accepted=1 unverified=1
未知 trace (STRICT)                -> 202 accepted=0 rejected=1
闭链后迟到事件                     -> 202 accepted=1 late=1，仍未丢
```

`audit_log` 里核对过：`ev-verified` / `ev-fill` / `ev-unknown` / `ev-late`
都在，`ev-mismatch` 不在；`ev-fill` 那一行的 `agent_task_id` 就是 attempt 上的
任务 ID。`agent_task_attempt` 中任务 1 是 `RUNNING`、任务 2 闭链为 `COMPLETED`。

**缓存很小，而且是有意的。** 一次执行的几十条事件共用一个 traceId，缓存命中率
接近 100%；不缓存等于让审计旁路按"每条事件一次查询"的速率压库。正缓存 60 秒、
负缓存 5 秒（让"审计先到、attempt 后建"能自己恢复）、上限 1 万条（满了先清过期
再淘汰任意一条，淘汰谁都不影响正确性）。TTL 用 `System.nanoTime()` 计算，不受
系统时钟回拨影响。

**迟到事件不拒收。** 事件的发生时刻晚于 attempt 的 `ended_at` 时，说明执行已经
闭链、还有事件在排队。它可能只是慢，不是假——丢掉等于篡改历史；所以只计入
`late` 并打 INFO，不改事件内容（`audit_log` 没有对应的列，改了记录就不再忠于
原始观察）。

**计数可以互相验算**：`accepted + rejected == received`，且 `unverified` /
`late` 都是 `accepted` 的子集。Python 侧把 `rejected` 一起打进丢事件的日志，
把 `unverified` 单独 WARN 一次——否则"身份没对上"只存在于 Java 的计数里，
Python 这边看起来一切正常。

配置两端各一份，必须一致：

```yaml
# km-task-service/application.yml
km.audit.ingest.token: ${KM_AUDIT_INGEST_TOKEN:dev-audit-token-change-me}
km.audit.ingest.identity-check: ${KM_AUDIT_IDENTITY_CHECK:warn}
```

```dotenv
# 仓库根目录 .env（Python 读取）
AUDIT_INGEST_TOKEN=dev-audit-token-change-me
```

---

## 十一、还没做的部分

1. ~~**审计接口没有认证。**~~ 2026-10-26 已补：共享 token + 按 attempt 核对
   身份（第十节第 7 条）。但它是"够用"而不是"完备"，剩下的三件事要记着：

   - **token 是共享的。** 一份泄露 = 所有提交方都能写。要做到"按调用方追责"，
     得给每个提交方发独立凭据，或者上 mTLS——现在只有 Python 一个提交方，
     还为时过早。
   - **控制面的其它接口仍然没有鉴权。** `/api/tasks` 谁都能建任务、改状态；
     审计入口只是先收口了这一处。
   - **`unverified` / `late` 没有接指标系统。** 它们现在只出现在 202 响应和
     日志里，没有趋势、没有告警。要回答"这周身份核对失败率是不是涨了"，
     还得先把 Micrometer 接上。

2. **`tool_invocation` 表已经加了 `trace_id` 列，但没有任何地方写它。**
   它和 `audit_log` 是两张表：前者是"工具调用"这张业务视图，后者是统一审计
   流。两张表都在，是因为它们要回答的问题不同——但 `trace_id` 空着就串不起来。

3. **信封还没有 `version` 字段。** schema 现在有两个实现者（Java / Python，
   不同仓库），没有版本号就没法在只改一边的时候知道对面会不会挂。加它是
   几分钟的事，难的是**约定什么时候 +1**。

4. **`/chat` 入口校验是有的，但它让任务链路硬依赖 Java。** 带 `task_id`
   时必须带 `trace_id`（否则 400），并先 `GET /api/tasks/{id}` 核对身份：
   不存在 404、对不上 409、Java 不可达 502——任何一种都**一条审计都不发**。
   代价是 Java 不在时 Python 跑不了任务链路；换来的是不会出现"Agent 正常
   回答、审计里却一条归属不明的记录都没有"这种更难发现的状态。

5. **采样的机制有了，策略没定。** `AuditPublisher.shouldSample`（第七节）已经
   实现——按层配置 + 失败事件永远上报，也有测试。但三个 `application.yml` 里
   全是 `default-sample-rate: 1.0`，**现在是全采**。机制空转不算问题，问题是
   没有依据去定那个数：得先有真实量级的分布，才谈得上"哪一层的哪些事件可以
   丢"。`LLM_CALL` 大概是最不能丢的——它贵，而且 token 数只有这一层有。

6. **重试已从手动 API 走到自动调度，「任务 : 调用链 = 1:N」两条入口都在用了。**
   `POST /api/tasks/{id}/retry` 在一条事务里完成：CAS 更新任务（FAILED ->
   PENDING、换新 trace、`retry_count+1`、清掉上一轮的 `finished_at` /
   `error_msg` / `result`）、插入 `attempt_no+1` 的 RUNNING 记录、写事件、
   发审计。失败时 `retry_count >= max_retry` 或任务不是 FAILED 都返回 409。

   2026-10-29 起，`RetryScheduler` 会定时扫描到期的 FAILED 任务，走**同一个**
   `TaskService.retry(...)` 开新链，然后由 `RetryDispatcher` 调 Python `/chat`。
   审计上的区别只有 `source`：手动 `JAVA_GATEWAY`，自动 `SYSTEM_RETRY`
   （`reason="自动重试"`）。所以按 source 分组就能回答"多少任务是系统兜底
   救回来的"，不用翻应用日志。

   派发失败不是一律关链：**只有"确定没到达 Python"**（拒绝连接 / DNS / 连接
   超时 / 线程池满）才回写 `PENDING -> FAILED`，detail 带 `phase=DISPATCH`；
   读超时和 HTTP 5xx 时请求可能已经在跑，**只记日志**，否则会出现"任务失败
   但 Python 继续上报"的双写。默认关闭、可按租户灰度（`km.retry.scheduler`）。

   `/chat` 入口现在核对的仍是 `agent_task.traceId`（当前执行的指针），这已经
   够了：重试和换指针在同一事务里，指针指向的 attempt 一定是开着的那条。另外
   入口加了一条**终态拒绝**——trace 对得上但任务已经 COMPLETED / FAILED /
   CANCELLED 时直接 409，并提示去调 `/retry`。没有这条的话，Agent 会白跑一整轮，
   而 Java 会拒掉它产生的每一条上报和审计。

7. **「会话」只有数据库维度，没有运行时维度。** `/chat` 里 `thread_id = task_id`，
   所以 LangGraph 的记忆按**任务**隔离：同一会话里的第二个任务看不见第一个任务的
   上下文。要做会话级记忆，得把 `thread_id` 换成 `session_id`，用 LangGraph 的
   `checkpoint_ns` 区分任务。当前 `session_id` 只是一个能被查询的标签。

8. **审计摄入的可用性参数还是常量。** 500ms 截止、5 秒熔断、2 线程 / 32 队列
   都写在 `GuardedAttemptLookup` 里。现在够用，但这几个值取决于机器和流量，
   压测之后应当外置到 `km.audit.ingest`。另外正缓存 60 秒意味着**迟到识别最
   多会晚一分钟**——迟到事件本身不会丢，只是 `late` 计数晚一点才准。

