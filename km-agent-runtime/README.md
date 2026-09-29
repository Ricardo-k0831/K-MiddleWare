# km-agent-runtime

K_middleware 的 Python Agent 运行时。

职责：LangGraph 多 Agent 编排、MCP Client 调用 Java 网关暴露的业务工具、分层记忆、上下文裁剪。

**注意：这里不是任务状态的权威来源。** 任务生命周期状态由 Java 控制面（MySQL）说了算，
本运行时只持有运行时内存态，状态变更一律回调 Java 更新。详见仓库根目录 README 的「关键设计决策」。

## 快速开始

```bash
# 1. 起基础设施（在仓库根目录）
docker compose -f docker/docker-compose.yml up -d

# 2. 起 Java 侧（另开两个终端）
cd km-control-plane && mvn -pl km-mock-order-service spring-boot:run
cd km-control-plane && mvn -pl km-mcp-server spring-boot:run

# 3. 装 Python 依赖
cd km-agent-runtime
uv sync --extra dev

# 4. ★ 先跑验收测试，确认 MCP 链路通了
uv run pytest tests/test_mcp_handshake.py -v -s

# 5. 起服务
uv run uvicorn app.main:app --reload --port 8000
```

## 验证

```bash
# 健康检查 + 装配状态
curl http://localhost:8000/health

# ★ 列出 Agent 实际拿到的工具 —— MCP 链路的验收点
curl http://localhost:8000/agent/tools

# 跑一轮 Agent
curl -X POST http://localhost:8000/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我查一下订单 SO20260901001 的状态","thread_id":"t1"}'
```

## 目录

```
app/
├── config.py                 环境变量配置
├── mcp_adapter/              ★ MCP 协议适配层（协议无关抽象）
│   ├── base.py               ToolProvider / ToolSpec 接口定义
│   └── langchain_provider.py 基于 mcp 1.x 的实现
├── graph/
│   └── agent.py              LangGraph ReAct Agent 装配
└── main.py                   FastAPI 入口
tests/
└── test_mcp_handshake.py     ★ 第 0 号验收测试
```
