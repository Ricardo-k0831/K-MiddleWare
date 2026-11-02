-- ============================================================
--  2026-10-31  心跳兜底: agent_task_attempt 增加两列 + 一个索引
--
--  ★ 为什么要有 migrations 目录 ★
--  docker/init/mysql/01-schema.sql 只在【数据卷第一次创建】时执行。
--  已经跑起来的库 (mysql-data 卷) 不会因为你改了 init 脚本就长出新列,
--  于是 Java 侧会在第一次读 attempt 时报 "Unknown column".
--
--  这个脚本是【幂等】的: 列/索引已存在就跳过, 可以重复执行。
--  用法 (宿主机):
--    docker exec -i km-mysql mysql -ukm -pkm_dev_pwd km_control \
--      < docker/migrations/2026-10-31-heartbeat.sql
-- ============================================================

USE km_control;

-- dispatched_at: Java 派发 /chat 的时点。NULL = 还没派发。
SET @ddl := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE agent_task_attempt ADD COLUMN dispatched_at DATETIME(3) COMMENT ''Java 派发 /chat 的时点; NULL = 未派发''',
        'SELECT ''dispatched_at 已存在, 跳过''')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_task_attempt'
      AND column_name = 'dispatched_at'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- last_heartbeat_at: Python 执行期间的心跳; Java 探活命中 alive=true 时也刷新。
SET @ddl := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE agent_task_attempt ADD COLUMN last_heartbeat_at DATETIME(3) COMMENT ''最近一次心跳; NULL = 从没跑起来''',
        'SELECT ''last_heartbeat_at 已存在, 跳过''')
    FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_task_attempt'
      AND column_name = 'last_heartbeat_at'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 扫描器只捞"开着的、且有过派发/心跳证据"的执行。
SET @ddl := (
    SELECT IF(COUNT(*) = 0,
        'ALTER TABLE agent_task_attempt ADD KEY idx_attempt_open (ended_at, dispatched_at, last_heartbeat_at)',
        'SELECT ''idx_attempt_open 已存在, 跳过''')
    FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'agent_task_attempt'
      AND index_name = 'idx_attempt_open'
);
PREPARE stmt FROM @ddl;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
