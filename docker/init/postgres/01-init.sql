-- ============================================================
--  PostgreSQL 初始化: 语义记忆(向量) + LangGraph checkpoint
-- ============================================================

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ------------------------------------------------------------
-- 语义记忆: 长期知识库 / 用户历史问答摘要, 向量检索走这里
--
-- ⚠ embedding 维度必须和你实际用的 Embedding 模型对齐, 否则插入直接报错。
--   1024 是 bge-large-zh / bge-m3 这类中文常用模型的维度。
--   换成 text-embedding-3-small 要改 1536, 换 ada-002 也是 1536。
--   改这里的同时, 记得改 app/config.py 里的 EMBEDDING_DIM。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS semantic_memory (
    id           BIGSERIAL PRIMARY KEY,
    -- 多租户 / 记忆隔离: 不同用户、不同 Agent 的记忆互不可见
    tenant_id    VARCHAR(64)  NOT NULL DEFAULT 'default',
    user_id      VARCHAR(64),
    -- 记忆类型: knowledge(知识库文档) / qa_summary(历史问答摘要)
    mem_type     VARCHAR(32)  NOT NULL DEFAULT 'knowledge',
    content      TEXT         NOT NULL,
    metadata     JSONB        NOT NULL DEFAULT '{}'::jsonb,
    embedding    vector(1024),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- HNSW 索引: 比 IVFFlat 建索引快、召回稳, 且不需要预先训练。
-- 向量数量 < 10 万时这样配就够了。
CREATE INDEX IF NOT EXISTS idx_semantic_memory_embedding
    ON semantic_memory USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_semantic_memory_tenant
    ON semantic_memory (tenant_id, user_id, mem_type);

-- ------------------------------------------------------------
-- 程序记忆(案例库): Agent 历史任务轨迹, 用于 Few-shot 动态选取
--
-- 和上面的区别: 语义记忆存的是"知识", 这里存的是"做过什么、成没成"。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS episode_memory (
    id            BIGSERIAL PRIMARY KEY,
    agent_task_id VARCHAR(64)  NOT NULL,          -- 与 Java 侧 agent_task_id 对齐
    tenant_id     VARCHAR(64)  NOT NULL DEFAULT 'default',
    task_intent   TEXT         NOT NULL,          -- 用户原始意图
    trajectory    JSONB        NOT NULL DEFAULT '[]'::jsonb,  -- 工具调用轨迹
    outcome       VARCHAR(16)  NOT NULL,          -- SUCCESS / FAILED
    embedding     vector(1024),                   -- 意图向量, 用于相似案例检索
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_episode_memory_embedding
    ON episode_memory USING hnsw (embedding vector_cosine_ops);

CREATE INDEX IF NOT EXISTS idx_episode_memory_lookup
    ON episode_memory (tenant_id, outcome);

-- ------------------------------------------------------------
-- LangGraph checkpoint 表
--
-- 不用手写, 也不用在这里建。Python 服务启动时 app/checkpoint.py 会在
-- CHECKPOINT_BACKEND=postgres 下调用 AsyncPostgresSaver.setup(), 自动建
-- checkpoint_migrations / checkpoints / checkpoint_blobs / checkpoint_writes
-- (setup 是幂等的, 每次启动跑一遍即可)。前提: 连库的 km 用户有 DDL 权限
-- (compose 里的 km 是库的 owner, 满足)。
-- 关键点: checkpoint 只用于"Agent 思考流程"的断点恢复,
--         任务的生命周期状态仍然由 Java 侧 MySQL 说了算。
-- ------------------------------------------------------------

-- ------------------------------------------------------------
-- 跨会话长期记忆: 用户事实 (订单号 / 用户ID / 答复偏好)
--
-- 和上面两张表的区别: 这里存的是"关于这个用户、跨会话仍然成立的事实",
-- 由 app/memory.py 在每轮回答后抽取, 按 (租户, 操作者) 隔离。
-- 新数据卷会在这里建好; 已经跑起来的库由服务启动时的
-- CREATE TABLE IF NOT EXISTS 兜底, 或手工执行
-- docker/migrations/2026-11-15-agent-memory.sql。
-- DDL 以 app/memory.py 的 ensure_schema 为准, 三处保持一致。
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS agent_memory (
    id             BIGSERIAL PRIMARY KEY,
    tenant_id      VARCHAR(64)  NOT NULL,
    actor_id       VARCHAR(128) NOT NULL,
    kind           VARCHAR(32)  NOT NULL,
    content        TEXT         NOT NULL,
    order_id       VARCHAR(64),
    keywords       TEXT         NOT NULL DEFAULT '',
    content_hash   CHAR(64)     NOT NULL,
    source_task_id VARCHAR(64),
    source_session VARCHAR(128),
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_seen_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    use_count      INTEGER      NOT NULL DEFAULT 0
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_agent_memory_scope_hash
    ON agent_memory (tenant_id, actor_id, content_hash);

CREATE INDEX IF NOT EXISTS idx_agent_memory_scope_recent
    ON agent_memory (tenant_id, actor_id, last_seen_at DESC);
