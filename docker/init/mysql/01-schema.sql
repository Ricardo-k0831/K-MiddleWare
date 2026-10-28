-- ============================================================
--  MySQL: Java 控制面的持久化层
--
--  设计前提(整个项目最重要的一条约定):
--    Java 侧是 Agent 任务状态的【唯一事实源】。
--    Python Agent 运行时只持有运行时内存态, 每次状态变更回调 Java。
--    这样 Python 进程崩了, 任务状态不会丢, 可以重试 / 断点续跑。
-- ============================================================

USE km_control;

-- ------------------------------------------------------------
-- Agent 任务主表
--
-- ★ agent_task_id 和 trace_id 是两个维度, 不要合并 ★
--   agent_task_id = 业务身份: "哪件事" (一次会话里可以有好几件)
--   trace_id      = 执行身份: "这件事第几遍跑" (重试/重跑就是新一条链)
-- 一个任务可以有多条调用链 (每次执行一条), 一条链不会跨任务。
-- trace_id 用 32 位小写十六进制, 和 W3C traceparent 同形, 将来可直接接 OTel。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_task (
    agent_task_id  VARCHAR(64)  NOT NULL COMMENT '任务ID = 业务身份, 一次"要办的事"',
    tenant_id      VARCHAR(64)  NOT NULL DEFAULT 'default',
    user_id        VARCHAR(64)  NOT NULL,
    session_id     VARCHAR(64)           COMMENT '会话ID, 一个会话含多件事(多个任务); 跨任务的关联用它, 不要用 traceId',
    trace_id       VARCHAR(64)           COMMENT '【最近一次执行】的 traceId, 跨 Java/Python 透传; 重试会换一个新的',

    intent         TEXT                  COMMENT '用户原始输入(脱敏后)',

    -- 状态机: 准备中 / Agent思考中 / 调用工具 / 完成 / 失败 / 已取消
    -- 与 Python 侧 LangGraph 的运行时状态解耦, 这里只记对外可见的粗粒度状态
    status         VARCHAR(32)  NOT NULL DEFAULT 'PENDING',
    result         MEDIUMTEXT            COMMENT '最终结果',
    error_msg      TEXT                  COMMENT '失败原因',

    -- 断点续跑用: 指向 Python 侧 LangGraph checkpoint 的定位信息
    checkpoint_ref VARCHAR(128)          COMMENT 'LangGraph checkpoint 定位',

    retry_count    INT          NOT NULL DEFAULT 0,
    max_retry      INT          NOT NULL DEFAULT 3,

    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    finished_at    DATETIME(3),

    PRIMARY KEY (agent_task_id),
    KEY idx_task_tenant_status (tenant_id, status, created_at),
    KEY idx_task_user (user_id, created_at),
    KEY idx_task_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='Agent 任务元信息, 状态事实源';

-- ------------------------------------------------------------
-- 任务执行尝试 (一次执行 = 一条调用链)
--
-- ★ 为什么要单独一张表 ★
-- agent_task.trace_id 是"最近一次执行"的滚动值, 会变;
-- 审计事件需要的 trace_id 是"这条事件属于哪一次执行", 必须不可变。
-- 从可变字段推断溯源信息, 只在一任务一执行的巧合下才正确 —— 一旦重试,
-- 在途的上报会被盖上新执行的 trace。所以把"执行"做成有主键的行:
--   task 1 : N attempt, 每次执行一个 trace_id, 闭链后不再改。
--
-- 首次执行由建任务时写入; 之后每次人工重试 (POST /api/tasks/{id}/retry)
-- 新增一行 attempt_no+1 的 RUNNING 记录。同一任务最多一条 ended_at IS NULL。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_task_attempt (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    agent_task_id VARCHAR(64)  NOT NULL,
    -- 一次执行的链路 ID。全局唯一: 同一 trace 不可能属于两个任务/两次执行
    trace_id      VARCHAR(64)  NOT NULL,
    attempt_no    INT          NOT NULL COMMENT '第几次执行, 从 1 开始',
    -- RUNNING / COMPLETED / FAILED / CANCELLED; 闭链时取任务终态
    status        VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',
    started_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    ended_at      DATETIME(3)  COMMENT 'NULL = 这次执行还没结束',
    PRIMARY KEY (id),
    UNIQUE KEY uk_attempt_trace (trace_id),
    UNIQUE KEY uk_attempt_no (agent_task_id, attempt_no),
    KEY idx_attempt_task (agent_task_id, attempt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='任务执行尝试: 一次执行 = 一条调用链, trace_id 的权威来源';

-- ------------------------------------------------------------
-- 任务状态流转事件 (append-only)
-- 和上面的 status 字段是一对: status 记"现在是什么", 这里记"怎么变成的"
-- 审计 + 排查 + 面试讲状态机设计都靠它
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_task_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    agent_task_id VARCHAR(64)  NOT NULL,
    from_status   VARCHAR(32),
    to_status     VARCHAR(32)  NOT NULL,
    -- 谁触发的状态变更: JAVA_GATEWAY / PYTHON_RUNTIME / SYSTEM_RETRY
    source        VARCHAR(32)  NOT NULL,
    detail        JSON                   COMMENT '变更上下文, 如工具名/耗时',
    created_at    DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_event_task (agent_task_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='任务状态流转事件';

-- ------------------------------------------------------------
-- MCP 工具注册中心
-- 存量 Java 微服务的工具在这里登记, Agent 启动时按身份拉取授权子集
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mcp_tool_registry (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    tool_name      VARCHAR(128) NOT NULL COMMENT '对 Agent 暴露的工具名',
    -- 后端真实服务: Phase1 指向 km-mock-order-service, 将来指向存量微服务
    backend_uri    VARCHAR(512) NOT NULL COMMENT '工具背后的真实 HTTP 端点模板',
    http_method    VARCHAR(8)   NOT NULL DEFAULT 'GET',

    -- ★ 给 LLM 看的自然语言描述。工具描述质量直接决定 Agent 选工具的准确率,
    --   这块要当 prompt 工程对待, 不是随便填的注释。
    description    TEXT         NOT NULL,
    -- JSON Schema, 描述入参结构。Phase1 直接喂给 LLM, Phase2 用 JsonSchemaValidator 做参数校验
    input_schema   JSON         NOT NULL,

    -- 权限标签: Agent 身份需持有对应标签才能调用 (Phase2 接真实鉴权)
    required_scope VARCHAR(128)          COMMENT '如 order:read',
    -- 参数白名单, 防越权 (Phase2 启用)
    param_whitelist JSON,

    qps_limit      INT          NOT NULL DEFAULT 100,
    timeout_ms     INT          NOT NULL DEFAULT 3000,

    enabled        TINYINT(1)   NOT NULL DEFAULT 1,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    PRIMARY KEY (id),
    UNIQUE KEY uk_tool_name (tool_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='MCP 工具注册中心';

-- ------------------------------------------------------------
-- 审计日志: 所有 Prompt 输入输出 + MCP 工具调用入参出参
-- 企业级中间件的刚需, 也是普通学生项目普遍缺失的部分
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS audit_log (
    id             BIGINT       NOT NULL AUTO_INCREMENT,

    -- 事件自己的唯一 ID, 由采集方生成 (见 AuditEvent#newEventId)。
    -- ★ 唯一索引是消费端幂等的全部依据 ★
    -- RocketMQ 保证的是"至少一次", broker 重启 / 消费者 rebalance 都会重投。
    -- 消费端拿 event_id 做 INSERT ... ON DUPLICATE KEY UPDATE, 重投就变成无操作。
    -- 没有这一列, 审计表里同一次调用会被记好几遍, "审计"本身就不成立了。
    event_id       VARCHAR(64)  NOT NULL COMMENT '事件全局唯一ID, 消费端幂等去重',

    agent_task_id  VARCHAR(64),
    trace_id       VARCHAR(64),
    tenant_id      VARCHAR(64)  NOT NULL DEFAULT 'default',

    -- 采集层: AGENT_RUNTIME / MCP_GATEWAY / TASK_SERVICE / LEGACY_SERVICE
    -- ★ 和 event_type 是两个维度 ★ layer 是"谁采集的", event_type 是"发生了什么"。
    -- 同层会产生多种事件, 同类事件也来自多层 —— 合成一个字段就再也拆不开了。
    layer          VARCHAR(32)  NOT NULL COMMENT '采集层, 见 com.km.task.audit.AuditLayer',

    -- 事件类型: LLM_CALL / MCP_TOOL_CALL / PROMPT_GUARD / SANDBOX_EXEC
    event_type     VARCHAR(32)  NOT NULL,
    actor          VARCHAR(64)           COMMENT '谁触发的, 用户ID或AgentID',

    request_body   MEDIUMTEXT            COMMENT '入参(已脱敏)',
    response_body  MEDIUMTEXT            COMMENT '出参(已脱敏)',
    status         VARCHAR(16)  NOT NULL DEFAULT 'OK',
    error_msg      TEXT,

    latency_ms     INT,
    token_in       INT                   COMMENT '仅 LLM_CALL 有值',
    token_out      INT,

    -- 事件发生时刻。★ 不等价于 created_at ★
    -- MQ 积压时消费时刻会晚很多; 排查时序问题时, 要的是发生时刻。
    occurred_at    DATETIME(3)  NOT NULL COMMENT '事件发生时刻(采集方填)',

    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    UNIQUE KEY uk_audit_event (event_id),
    KEY idx_audit_task (agent_task_id, created_at),
    KEY idx_audit_trace (trace_id),
    KEY idx_audit_type_time (event_type, created_at),
    -- "MCP 网关这一层都发生了什么" 是最常查的问法之一
    KEY idx_audit_layer_time (layer, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='统一审计日志';

-- ------------------------------------------------------------
-- 工具调用记录 (程序记忆的 MySQL 侧)
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS tool_invocation (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    agent_task_id  VARCHAR(64)  NOT NULL,
    -- 和 audit_log 用同一个 trace_id —— "这次工具调用属于哪条调用链"。
    -- 只有 task_id 的话, 任务重试后就分不清是哪一次重试调的这个工具了。
    trace_id       VARCHAR(64),
    tool_name      VARCHAR(128) NOT NULL,
    arguments      JSON,
    result         MEDIUMTEXT,
    status         VARCHAR(16)  NOT NULL,   -- OK / ERROR / TIMEOUT / DEGRADED
    latency_ms     INT,
    created_at     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (id),
    KEY idx_invocation_task (agent_task_id, created_at),
    KEY idx_invocation_trace (trace_id),
    KEY idx_invocation_tool (tool_name, status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
  COMMENT='MCP 工具调用记录';
