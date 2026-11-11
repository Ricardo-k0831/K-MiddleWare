# K_middleware

面向企业存量 Java 微服务体系的 AI 中间件平台。

解决的核心问题：**存量 Java 微服务如何低成本、安全可控地给 Agent 调用。**

不是又一个 AI 应用，而是 AI 中间件基础设施 —— Java 做控制面（稳定性、权限、可观测），
Python 做数据面（Agent 编排、推理、RAG），MCP 协议打通两边。

---

## 架构

```
                    ┌─────────────────────────────────────────────┐
  用户请求 ────────▶│  Java 控制面 (Spring Boot 4 / Java 21)      │
                    │                                             │
                    │  km-mcp-server   MCP 工具网关               │
                    │    ← 安全边界, 所有调用都过这里             │
                    │  km-task-service 任务状态事实源             │
                    │    状态机 / 重试 / 心跳 / 取消              │
                    │  km-audit-core   四层审计汇聚               │
                    │  （限流 / 熔断已实现, 权限裁剪未做）        │
                    └──────────────┬──────────────────────────────┘
                                   │ 派发: HTTP /chat
                                   │ 审计: RocketMQ → audit_log
                    ┌──────────────▼──────────────────────────────┐
                    │  Python Agent 运行时 (FastAPI)              │
                    │                                             │
                    │  · LangGraph ReAct Agent                    │
                    │  · MCP Client (协议适配层)                  │
                    │  · 会话身份 + Postgres 断点续跑             │
                    └──────────────┬──────────────────────────────┘
                                   │ MCP over Streamable HTTP
                    ┌──────────────▼──────────────────────────────┐
                    │  存量 Java 微服务 (零改造)                  │
                    └─────────────────────────────────────────────┘
```

---

## 快速开始

### 0. 前置

| 组件 | 版本 | 说明 |
|---|---|---|
| JDK | 21 | Spring Boot 4 最低 17，推荐 21 |
| Maven | 3.9+ | |
| Python | 3.12+ | |
| uv | 最新 | `pip install uv` |
| Docker + Compose | 最新 | |

### 1. 起基础设施

```bash
cp .env.example .env          # 填 LLM_API_KEY
docker compose -f docker/docker-compose.yml up -d
docker compose -f docker/docker-compose.yml ps   # 等全部 healthy
```

拉起：MySQL **3307**（容器内 3306，见下方说明）、Redis 6379（网关限流计数器）、
PostgreSQL(pgvector) 5432、RocketMQ 9876/10911。

> MySQL 特意映射到宿主机 **3307**：本机若装了原生 MySQL（Windows 服务，默认占 3306），
> 容器会因抢不到端口直接退出（`Exited (137)`）。容器内部仍是 3306，docker 网络内的服务
> 照旧写 `mysql:3306`；宿主机上的 Java 服务连 `localhost:3307`。

### 2. 起 Java 控制面

```bash
cd km-control-plane
mvn clean install -DskipTests

# 终端 1 — 模拟存量微服务（8082）
mvn -pl km-mock-order-service spring-boot:run

# 终端 2 — MCP 工具网关（8081）
mvn -pl km-mcp-server spring-boot:run

# 终端 3 — 任务服务（8083）：状态机 / 重试 / 心跳 / 取消 / 审计入口
mvn -pl km-task-service spring-boot:run
```

> 刚跑过服务就别再 `mvn install` —— 运行中的 JVM 加载着旧 jar，覆盖 target 会
> 让下一次类加载报 `NoClassDefFoundError: X$2`。先停服务再构建。

### 3. 起 Python Agent 运行时

```bash
cd km-agent-runtime
uv sync --extra dev

# ★ 先验证 MCP 链路，再写任何业务代码
uv run pytest tests/test_mcp_handshake.py -v -s

# 内存记忆（重启即丢）：
uv run uvicorn app.main:app --port 8000

# 持久化记忆 + 断点续跑（需要 docker 里的 PostgreSQL）：
CHECKPOINT_BACKEND=postgres uv run uvicorn app.main:app --port 8000 \
  --loop asyncio:SelectorEventLoop
```

> **Windows 上必须换事件循环**：psycopg 的异步连接不能跑在
> `ProactorEventLoop`（Windows 默认）上。持久化后端请加
> `--loop asyncio:SelectorEventLoop`，或者用 `--reload`（uvicorn 会自己选）。
> 否则启动直接报 `NotImplementedError`。

### 4. 验证端到端

```bash
# 工具发现（走 MCP 网关）
curl http://localhost:8000/agent/tools

# 自由对话（不建任务，不回调 Java）
curl -X POST http://localhost:8000/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我查一下订单 SO20260901001 的状态","thread_id":"t1"}'

# 任务链路：先建任务，再带着任务身份调 /chat
# ★ 8083 需要控制面密钥：USER 密钥只能在自己租户（demo）下建任务 ★
curl -X POST http://localhost:8083/api/tasks \
  -H 'Content-Type: application/json' \
  -H 'X-KM-Api-Key: dev-user-key-change-me' \
  -d '{"userId":"U1001","sessionId":"s-demo","intent":"查订单","tenantId":"demo","maxRetry":1}'
# 把响应里的 agentTaskId / traceId / sessionId 原样转交：
curl -X POST http://localhost:8000/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我查一下订单 SO20260901001 的状态",
       "task_id":"<agentTaskId>","trace_id":"<traceId>",
       "tenant_id":"demo","actor":"U1001","session_id":"s-demo"}'

# 查最终状态与事件链（同样要带密钥）
curl -H 'X-KM-Api-Key: dev-user-key-change-me' \
  http://localhost:8083/api/tasks/<agentTaskId>
curl -H 'X-KM-Api-Key: dev-user-key-change-me' \
  http://localhost:8083/api/tasks/<agentTaskId>/events
```

`trace_id` 只能来自任务创建（或重试）的响应 —— Python 不生成它。缺了会 400，
补一个假的则会让同一遍执行在审计里劈成两条对不上的链路。

`X-KM-Api-Key` 有两个开发默认值（生产用 `KM_CONTROL_USER_KEY` /
`KM_CONTROL_SERVICE_KEY` 覆盖，启动日志会对默认密钥 WARN 一次）：

| 密钥 | 角色 | 能做什么 |
| --- | --- | --- |
| `dev-user-key-change-me` | USER（租户 `demo`） | 建任务 / 查 / 取消 / 重试 |
| `dev-service-key-change-me` | SERVICE（租户通配） | Python 运行时用；上报状态 / 心跳 |

状态上报和心跳**只接受 SERVICE 密钥** —— 否则拿到用户密钥的脚本就能伪造
`COMPLETED`。详见 [`docs/task-state-machine.md`](docs/task-state-machine.md#控制面鉴权2026-11-09)。

### 5. 详细文档

- [`docs/task-state-machine.md`](docs/task-state-machine.md) — 状态机、重试、心跳兜底、
  取消、会话/线程身份与断点续跑的完整设计
- [`docs/audit.md`](docs/audit.md) — 四层审计的数据模型、投递链路、身份核对与已知边界

---

## 关键设计决策

### 1. Java 是任务状态的唯一事实源

Python 侧**不维护**任务的持久化状态。每次状态变更回调 Java。

**为什么**：双语言系统最大的坑是双端状态不一致。Python 进程崩溃时，任务状态由 Java 兜底，
可以重试、可以配合 LangGraph checkpoint 做断点续跑。反过来如果 Python 也存状态，
崩了以后两边对不上，恢复逻辑会变成噩梦。

任务状态机：`准备中 → Agent思考中 → 调用工具 → 完成 / 失败 / 已取消`

### 2. MCP 协议版本 —— 本项目最容易踩的坑

MCP 协议目前处于**分线状态**，两边必须对齐：

| | 实现 | 协议线 |
|---|---|---|
| Java 侧 | Spring AI 2.0.1 → `io.modelcontextprotocol.sdk:mcp:2.0.0` | **2.x**（无状态） |
| Python 侧 | `langchain-mcp-adapters` 0.3.2 → `mcp<2.0.0` | **1.x**（带握手） |

`mcp 2.x` 对应 **MCP 2026-07-28 修订版**：`initialize` 握手被删除、`Mcp-Session-Id` 被删除、
GET SSE 流被删除，协议变为完全无状态。而 Python 客户端生态目前还锁在 1.x 线上。

**本项目采取的对策**：

- Java 侧 `spring.ai.mcp.server.protocol` 用 **`streamable`**（带会话的流式 HTTP），
  **不要用 `stateless`** —— 那是 2026-07-28 线，Python 客户端连不上。
- Python 侧在 `pyproject.toml` 里显式钉 `mcp>=1.30.0,<2.0.0`，
  防止依赖解析器悄悄升级导致协议对不上。
- 两侧之间通过 `app/mcp_adapter/` 这一层协议无关抽象隔离，
  将来 `mcp 2.x` 客户端可用时，只新增一个 Provider 实现即可，业务代码不动。

这个约束**必须靠 `tests/test_mcp_handshake.py` 验证**，不能靠假设。

> **✅ 已于 2026-09-17 实测验证通过。**
> Spring AI 2.0.1 的 MCP 服务端**确实向后兼容** Python 的 mcp 1.30.0 客户端 ——
> 握手成功，工具发现正常，工具调用能拿回真实业务数据。
> 本项目最大的技术风险已解除。
>
> 但**这个兼容性不是协议保证的**，是服务端实现的宽容。升级 Spring AI 或
> `langchain-mcp-adapters` 版本后必须重跑这个测试。

### 3. 权限边界在 Java 网关，不在 Python

Python 侧不做任何权限校验。Agent 启动时从 Java 网关拉取的是**权限裁剪后**的工具列表 ——
Agent 拿到的能力本身就是被限制过的。

**为什么**：安全边界必须收敛在单一位置。如果 Python 也做一层权限判断，
两套规则迟早会不一致，而不一致的那一侧就是漏洞。

> **现状（2026-11-09）**：边界的位置已经定死，**裁剪本身还没实现** ——
> 网关目前全量暴露工具。这是 Phase 2 剩下的第一项，不是已完成能力。
> 网关上的限流/熔断保护的是**容量**（谁都能调，但调不快），不是**权限**
> （谁能调什么），两者不要混为一谈。

### 4. 存量微服务零改造

`km-mock-order-service` 里**故意**没有任何 MCP / AI 相关代码。
适配 MCP 协议的成本全部由 `km-mcp-server` 网关承担。

这是整个项目的核心卖点：企业不需要动那套跑了好几年的业务系统。

---

## 目录结构

```
K_middleware/
├── docker/                       基础设施
│   ├── docker-compose.yml
│   └── init/                     MySQL 建表 / pgvector 扩展 / RocketMQ 配置
├── km-control-plane/             Java 控制面
│   ├── km-mock-order-service/    模拟存量微服务（8082，零改造）
│   ├── km-mcp-server/            MCP 工具网关（8081）
│   ├── km-task-service/          任务服务（8083）：状态机 / 重试 / 心跳 / 取消
│   ├── km-audit-core/            审计公共库：事件模型 + RocketMQ 投递 + 采样
│   └── scripts/                  验收脚本（状态机 / 重试 / 心跳 / 审计链路）
├── km-agent-runtime/             Python Agent 运行时（8000）
│   ├── app/mcp_adapter/          ★ MCP 协议适配层
│   ├── app/graph/                LangGraph 编排
│   ├── app/checkpoint.py         会话记忆后端（memory / postgres）
│   └── tests/                    ★ 含 MCP 握手、任务链路、审计、断点续跑测试
├── docs/                         设计文档（状态机 / 审计）
└── docker/migrations/            增量迁移（老 volume 不会重跑 init 里的建表）
```

---

## 开发路线

### Phase 1 — MVP（已跑通，剩两项）

目标：**端到端跑通**，架构故事完整。

- [x] 基础设施 docker-compose
- [x] 模拟存量订单服务
- [x] MCP 工具网关（Streamable HTTP）
- [x] Python MCP Client + 协议适配层
- [x] LangGraph ReAct Agent
- [x] **验证 MCP 握手** ← ✅ 2026-09-17 实测通过，见上方「MCP 协议版本」
- [x] **接真模型跑通完整对话**（DeepSeek + 真实工具调用）← ✅ 2026-11-09
- [x] 任务状态机 + 回调 Java（PENDING / THINKING / TOOL_CALLING / COMPLETED / FAILED / CANCELLED）
- [x] 失败重试（手动 + 定时自动）与心跳兜底
- [x] 取消：Java 先落库，Python 协作终止并释放 checkpoint
- [x] 四层审计（AGENT_RUNTIME / MCP_GATEWAY / LEGACY_SERVICE / TASK_SERVICE）→ RocketMQ → `audit_log`
- [x] 会话记忆（`session:<tenant>:<session_id>`）+ Postgres 断点续跑
- [ ] RocketMQ **异步任务派发** —— 架构图上的那一块，现在派发仍是 HTTP，MQ 只用于审计汇聚
- [ ] 基础 RAG 语义记忆 —— pgvector 容器已就绪，尚未接入

### Phase 2 — 企业特性

- [ ] 网关权限裁剪（按租户/角色裁剪工具列表）—— README 前半部分描述的是**目标态**
- [x] 网关限流 + 熔断 ← ✅ 2026-11-09
  - Redis 令牌桶（Lua 原子操作），任务 / 工具两个维度，Redis 故障时 fail-open
  - Resilience4j core 熔断，4xx 不计入失败率；被挡掉的调用记审计 `DEGRADED`
  - 验收：`km-agent-runtime/scripts/verify_gateway_guard.py`
- [x] 控制面鉴权 ← ✅ 2026-11-09
  - `X-KM-Api-Key` → (租户, 操作者, 角色)，USER / SERVICE 两种角色
  - 跨租户读一律 404（不做存在性泄露）；审计 `actor` 变成真正的调用方
  - 验收：`km-control-plane/scripts/verify_control_auth.py`
- [ ] 全链路 OTel 追踪、Token 指标、Micrometer 指标与审计采样率策略
- [ ] Prompt 注入防护
- [ ] SSE 流式输出

### Phase 3 — 高阶亮点（可选）

CADF 上下文智能裁剪、Embedding LoRA 微调、Docker 代码沙箱、Grafana 监控面板。

---

## 参考

- [MCP 规范](https://modelcontextprotocol.io/specification)
- [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [LangGraph](https://langchain-ai.github.io/langgraph/)
- [langchain-mcp-adapters](https://github.com/langchain-ai/langchain-mcp-adapters)
