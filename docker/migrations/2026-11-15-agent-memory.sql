-- ============================================================
--  2026-11-15  跨会话长期记忆: agent_memory 表 (PostgreSQL)
--
--  ★ 这张表平时不需要手工建 ★
--  Python 运行时启动时 app/memory.py 的 PostgresMemoryStore.ensure_schema()
--  会执行同样的 CREATE TABLE IF NOT EXISTS。这个脚本是给两类场景用的:
--    1. 希望在建服务之前就把表/索引准备好 (DBA 流程);
--    2. 排查"为什么长期记忆没启用"时, 手工确认库里的实际结构。
--  两处 DDL 必须保持一致 —— 改一边就改另一边。
--
--  用法 (宿主机):
--    docker exec -i km-postgres psql -U km -d km_memory \
--      < docker/migrations/2026-11-15-agent-memory.sql
--
--  设计要点 (详见 km-agent-runtime/app/memory.py 与 docs/agent-memory.md):
--  * 按 (tenant_id, actor_id) 分区 —— 记忆隔离的最小边界;
--  * 只存用户自己陈述过的持久事实 (订单号/用户ID/偏好), 不存状态、金额、
--    时间, 也不存工具返回的自由文本 —— 后者是间接提示注入的入口;
--  * content_hash 去重: 同一条事实重复出现只刷新 last_seen_at;
--  * last_seen_at 是"最近一次提到", "上次那个订单"按它排序。
-- ============================================================

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
