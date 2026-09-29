# K_middleware

面向企业存量 Java 微服务体系的 AI 中间件平台。

解决的核心问题：**存量 Java 微服务如何低成本、安全可控地给 Agent 调用。**

不是又一个 AI 应用，而是 AI 中间件基础设施 —— Java 做控制面（稳定性、权限、可观测），
Python 做数据面（Agent 编排、推理、RAG），MCP 协议打通两边。

---

## 架构

```
                    ┌─────────────────────────────────────┐
   用户请求 ────────▶│  Java 控制面 (Spring Boot 4 / Java 21) │
                    │                                     │
                    │  · MCP 工具网关  ← 安全边界, 所有    │
                    │    Agent 对业务的调用都从这里过      │
                    │  · 任务状态事实源 (MySQL)            │
                    │  · 权限 / 限流 / 熔断 / 审计         │
                    └──────────────┬──────────────────────┘
                                   │
                    RocketMQ 异步任务投递 / 回调
                                   │
                    ┌──────────────▼──────────────────────┐
                    │  Python Agent 运行时 (FastAPI)       │
                    │                                     │
                    │  · LangGraph 多 Agent 编排           │
                    │  · MCP Client (协议适配层)           │
                    │  · 分层记忆 (Redis / PGVector / MySQL)│
                    └──────────────┬──────────────────────┘
                                   │ MCP over Streamable HTTP
                    ┌──────────────▼──────────────────────┐
                    │  存量 Java 微服务 (零改造)            │
                    └─────────────────────────────────────┘
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

拉起：MySQL 3306、Redis 6379、PostgreSQL(pgvector) 5432、RocketMQ 9876/10911。

### 2. 起 Java 控制面

```bash
cd km-control-plane
mvn clean install -DskipTests

# 终端 1 — 模拟存量微服务（8082）
mvn -pl km-mock-order-service spring-boot:run

# 终端 2 — MCP 工具网关（8081）
mvn -pl km-mcp-server spring-boot:run
```

### 3. 起 Python Agent 运行时

```bash
cd km-agent-runtime
uv sync --extra dev

# ★ 先验证 MCP 链路，再写任何业务代码
uv run pytest tests/test_mcp_handshake.py -v -s

uv run uvicorn app.main:app --reload --port 8000
```

### 4. 验证端到端

```bash
curl http://localhost:8000/agent/tools
curl -X POST http://localhost:8000/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我查一下订单 SO20260901001 的状态","thread_id":"t1"}'
```

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
│   └── km-mcp-server/            MCP 工具网关（8081）
└── km-agent-runtime/             Python Agent 运行时（8000）
    ├── app/mcp_adapter/          ★ MCP 协议适配层
    ├── app/graph/                LangGraph 编排
    └── tests/                    ★ 含 MCP 握手验收测试
```

---

## 开发路线

### Phase 1 — MVP（当前）

目标：**端到端跑通**，架构故事完整。

- [x] 基础设施 docker-compose
- [x] 模拟存量订单服务
- [x] MCP 工具网关（Streamable HTTP）
- [x] Python MCP Client + 协议适配层
- [x] LangGraph ReAct Agent
- [x] **验证 MCP 握手** ← ✅ 2026-09-17 实测通过，见上方「MCP 协议版本」
- [ ] 接 LLM 跑通完整 Agent 对话（需要先配 `.env` 里的 LLM_API_KEY）
- [ ] RocketMQ 异步任务投递
- [ ] 任务状态机 + 回调 Java
- [ ] 基础 RAG 语义记忆

### Phase 2 — 企业特性

分层记忆完善、全链路 OTel 追踪、Token 指标、Prompt 注入防护、任务断点恢复、SSE 流式输出。

### Phase 3 — 高阶亮点（可选）

CADF 上下文智能裁剪、Embedding LoRA 微调、Docker 代码沙箱、Grafana 监控面板。

---

## 参考

- [MCP 规范](https://modelcontextprotocol.io/specification)
- [Spring AI MCP](https://docs.spring.io/spring-ai/reference/api/mcp/mcp-server-boot-starter-docs.html)
- [LangGraph](https://langchain-ai.github.io/langgraph/)
- [langchain-mcp-adapters](https://github.com/langchain-ai/langchain-mcp-adapters)
