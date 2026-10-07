-- ============================================================================
-- V2 — 从旧库（Hibernate ddl-auto=update 生成的历史结构）升级到生产结构
--
-- 【为什么长这样，别改回去】
-- 1) MySQL **不支持** `ALTER TABLE ... ADD COLUMN IF NOT EXISTS`，也不支持
--    `CREATE INDEX IF NOT EXISTS`（8.0.46 实测报 1064，那是 MariaDB 扩展）。
--    因此这里用 information_schema 先判断再执行，判断与执行都在存储过程里完成。
-- 2) 为什么用存储过程而不是散装 `SET @sql; PREPARE; EXECUTE`：
--    存储过程体对 Flyway 的分号解析器来说是一个整体，不会因为 COMMENT 里的标点被切错。
--    DDL 在存储过程里可以正常执行，MySQL 会在过程结束时隐式提交，不影响迁移语义。
-- 3) 为什么要"重排"：
--    旧库的列顺序是 Hibernate 按实体字段顺序陆续追加出来的，与 V1 建出的顺序不同。
--    更要命的是 **带 AUTO_INCREMENT 的表无法把 `id` 放到末位**（MySQL 强制自增列必须是索引首列），
--    所以中间列的顺序必须显式用 `MODIFY ... AFTER` 调整才能与 V1 对齐。
--    重排做成"位置已正确就跳过"，因此重复执行是廉价且幂等的。
--
-- 【在全新库上会怎样】
-- V1 已建出最终结构：补列阶段全部跳过；重排阶段每个字段位置都已正确，也全部跳过。整体是空操作。
--
-- 【安全承诺】
-- 不 DROP 任何表/列，不 TRUNCATE，不删索引；唯一的写数据动作是把 incr_value 继承到 cursor_value
-- 以及让 enabled 与历史 status 对齐，两者都是幂等的。
-- ============================================================================

DELIMITER $$

-- ---------------------------------------------------------------------------
-- 第一段：补齐 V1 里有、旧库缺失的列
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS datasync_v2_align_columns$$

CREATE PROCEDURE datasync_v2_align_columns()
BEGIN
    DECLARE v INT DEFAULT 0;

    -- ---------------- sync_task ----------------
    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'cursor_value';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD COLUMN `cursor_value` VARCHAR(255) DEFAULT NULL
            COMMENT '增量游标：上一次成功同步的最后一个增量字段值';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'order_column';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD COLUMN `order_column` VARCHAR(128) DEFAULT NULL
            COMMENT '键集分页排序列，空则自动推断';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'safety_lag_seconds';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD COLUMN `safety_lag_seconds` BIGINT NOT NULL DEFAULT 0
            COMMENT '增量读上界安全滞后秒数（规避未提交事务）';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'lookback_seconds';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD COLUMN `lookback_seconds` BIGINT NOT NULL DEFAULT 0
            COMMENT '增量读下界回看秒数（补迟到的历史写入）';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'full_sync_strategy';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD COLUMN `full_sync_strategy` VARCHAR(16) NOT NULL DEFAULT 'TRUNCATE'
            COMMENT '全量策略：TRUNCATE / DELETE / SWAP';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'error_policy_json';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD COLUMN `error_policy_json` TEXT COMMENT '错误处理策略（JSON）';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'enabled';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD COLUMN `enabled` TINYINT(1) NOT NULL DEFAULT 1
            COMMENT '是否启用调度：1 启用 0 停用';
    END IF;

    -- ---------------- sync_record ----------------
    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'run_key';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD COLUMN `run_key` VARCHAR(64) DEFAULT NULL
            COMMENT '本次执行的幂等键（唯一，防重复落库）';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'skipped_rows';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD COLUMN `skipped_rows` BIGINT DEFAULT 0 COMMENT '被跳过的坏行数';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'start_cursor';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD COLUMN `start_cursor` VARCHAR(255) DEFAULT NULL
            COMMENT '本次生效的增量读下界';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'end_cursor';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD COLUMN `end_cursor` VARCHAR(255) DEFAULT NULL
            COMMENT '本次成功提交的最后一行增量值（仅成功时非空）';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'read_millis';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD COLUMN `read_millis` BIGINT NOT NULL DEFAULT 0
            COMMENT '读取阶段耗时（毫秒）';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'write_millis';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD COLUMN `write_millis` BIGINT NOT NULL DEFAULT 0
            COMMENT '写入阶段耗时（毫秒）';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'total_millis';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD COLUMN `total_millis` BIGINT NOT NULL DEFAULT 0
            COMMENT '总耗时（毫秒）';
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'preflight_json';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD COLUMN `preflight_json` TEXT COMMENT '预检结果（JSON 数组）';
    END IF;

    -- ---------------- sync_error：旧库完全没有这张表 ----------------
    CREATE TABLE IF NOT EXISTS `sync_error` (
        `id`         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '错误行ID',
        `record_id`  BIGINT        NOT NULL COMMENT '关联的执行记录ID',
        `task_id`    BIGINT        NOT NULL COMMENT '关联的任务ID',
        `phase`      VARCHAR(16)   NOT NULL COMMENT '失败阶段：PREFLIGHT / READ / MAP / WRITE',
        `row_key`    VARCHAR(255)  DEFAULT NULL COMMENT '失败行的主键值（尽力而为）',
        `message`    VARCHAR(1000) DEFAULT NULL COMMENT '错误原因',
        `row_data`   TEXT          COMMENT '源行数据（JSON，超长会截断）',
        `retryable`  TINYINT(1)    NOT NULL DEFAULT 0 COMMENT '是否可重试：1 可重试 0 致命',
        `created_at` DATETIME(6)   NOT NULL COMMENT '记录时间',
        PRIMARY KEY (`id`),
        KEY `idx_sync_error_record` (`record_id`),
        KEY `idx_sync_error_task` (`task_id`)
    ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '同步失败行明细表';

    -- ---------------- 索引 ----------------
    SELECT COUNT(*) INTO v FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND INDEX_NAME = 'uk_sync_record_run_key';
    IF v = 0 THEN
        -- run_key 可空，MySQL 唯一索引允许多个 NULL，历史记录不受影响
        ALTER TABLE `sync_record` ADD UNIQUE KEY `uk_sync_record_run_key` (`run_key`);
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND INDEX_NAME = 'idx_sync_record_task_start';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD KEY `idx_sync_record_task_start` (`task_id`, `start_time`);
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND INDEX_NAME = 'idx_sync_record_status';
    IF v = 0 THEN
        ALTER TABLE `sync_record` ADD KEY `idx_sync_record_status` (`status`);
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND INDEX_NAME = 'idx_sync_task_status';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD KEY `idx_sync_task_status` (`status`);
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND INDEX_NAME = 'idx_sync_task_source_ds';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD KEY `idx_sync_task_source_ds` (`source_ds_id`);
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND INDEX_NAME = 'idx_sync_task_target_ds';
    IF v = 0 THEN
        ALTER TABLE `sync_task` ADD KEY `idx_sync_task_target_ds` (`target_ds_id`);
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.STATISTICS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND INDEX_NAME = 'idx_datasource_name';
    IF v = 0 THEN
        ALTER TABLE `datasource` ADD KEY `idx_datasource_name` (`name`);
    END IF;
END$$

-- ---------------------------------------------------------------------------
-- 第二段：列定义与列顺序对齐
--   每步都是「位置已正确就跳过」的幂等操作；主键 id 因 AUTO_INCREMENT 天然在首位。
--   按目标顺序从左到右执行，因此每步的 AFTER 都引用上一步刚定位好的列。
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS datasync_v2_reorder$$

CREATE PROCEDURE datasync_v2_reorder()
BEGIN
    DECLARE v INT DEFAULT 0;

    -- ============ datasource：id, name, created_at, database_name, db_type, host, port, password, updated_at, username ============
    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND COLUMN_NAME = 'name' AND ORDINAL_POSITION = 2 AND COLUMN_COMMENT = '数据源名称';
    IF v = 0 THEN
        ALTER TABLE `datasource` MODIFY COLUMN `name` VARCHAR(100) NOT NULL
            COMMENT '数据源名称' AFTER `id`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND COLUMN_NAME = 'created_at' AND ORDINAL_POSITION = 3 AND COLUMN_COMMENT = '创建时间';
    IF v = 0 THEN
        ALTER TABLE `datasource` MODIFY COLUMN `created_at` DATETIME(6) DEFAULT NULL
            COMMENT '创建时间' AFTER `name`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND COLUMN_NAME = 'database_name' AND ORDINAL_POSITION = 4 AND COLUMN_COMMENT = '数据库名';
    IF v = 0 THEN
        ALTER TABLE `datasource` MODIFY COLUMN `database_name` VARCHAR(100) NOT NULL
            COMMENT '数据库名' AFTER `created_at`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND COLUMN_NAME = 'db_type' AND ORDINAL_POSITION = 5 AND COLUMN_COMMENT = '数据库类型：MYSQL / DM8';
    IF v = 0 THEN
        ALTER TABLE `datasource` MODIFY COLUMN `db_type` VARCHAR(20) NOT NULL
            COMMENT '数据库类型：MYSQL / DM8' AFTER `database_name`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND COLUMN_NAME = 'host' AND ORDINAL_POSITION = 6 AND COLUMN_COMMENT = '主机地址';
    IF v = 0 THEN
        ALTER TABLE `datasource` MODIFY COLUMN `host` VARCHAR(255) NOT NULL
            COMMENT '主机地址' AFTER `db_type`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND COLUMN_NAME = 'port' AND ORDINAL_POSITION = 7 AND COLUMN_COMMENT = '端口号';
    IF v = 0 THEN
        ALTER TABLE `datasource` MODIFY COLUMN `port` INT NOT NULL COMMENT '端口号' AFTER `host`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND COLUMN_NAME = 'password' AND ORDINAL_POSITION = 8 AND COLUMN_COMMENT = '登录密码（AES-GCM 加密，ENC( 前缀标识密文）';
    IF v = 0 THEN
        ALTER TABLE `datasource` MODIFY COLUMN `password` VARCHAR(255) NOT NULL
            COMMENT '登录密码（AES-GCM 加密，ENC( 前缀标识密文）' AFTER `port`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND COLUMN_NAME = 'updated_at' AND ORDINAL_POSITION = 9 AND COLUMN_COMMENT = '更新时间';
    IF v = 0 THEN
        ALTER TABLE `datasource` MODIFY COLUMN `updated_at` DATETIME(6) DEFAULT NULL
            COMMENT '更新时间' AFTER `password`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'datasource' AND COLUMN_NAME = 'username' AND ORDINAL_POSITION = 10 AND COLUMN_COMMENT = '登录用户名';
    IF v = 0 THEN
        ALTER TABLE `datasource` MODIFY COLUMN `username` VARCHAR(100) NOT NULL
            COMMENT '登录用户名' AFTER `updated_at`;
    END IF;

    -- ============ sync_task：25 列，目标顺序见 V1 ============
    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'name' AND ORDINAL_POSITION = 2 AND COLUMN_COMMENT = '任务名称';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `name` VARCHAR(200) NOT NULL COMMENT '任务名称' AFTER `id`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'source_ds_id' AND ORDINAL_POSITION = 3 AND COLUMN_COMMENT = '源数据源ID';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `source_ds_id` BIGINT NOT NULL COMMENT '源数据源ID' AFTER `name`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'target_ds_id' AND ORDINAL_POSITION = 4 AND COLUMN_COMMENT = '目标数据源ID';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `target_ds_id` BIGINT NOT NULL COMMENT '目标数据源ID' AFTER `source_ds_id`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'source_table' AND ORDINAL_POSITION = 5 AND COLUMN_COMMENT = '源表名';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `source_table` VARCHAR(200) NOT NULL COMMENT '源表名' AFTER `target_ds_id`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'target_table' AND ORDINAL_POSITION = 6 AND COLUMN_COMMENT = '目标表名';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `target_table` VARCHAR(200) NOT NULL COMMENT '目标表名' AFTER `source_table`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'sync_mode' AND ORDINAL_POSITION = 7 AND COLUMN_COMMENT = '同步模式：FULL / INCR / FULL_INCR';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `sync_mode` VARCHAR(20) NOT NULL
            COMMENT '同步模式：FULL / INCR / FULL_INCR' AFTER `target_table`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'incr_column' AND ORDINAL_POSITION = 8 AND COLUMN_COMMENT = '增量字段（单调递增列或时间戳列）';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `incr_column` VARCHAR(100) DEFAULT NULL
            COMMENT '增量字段（单调递增列或时间戳列）' AFTER `sync_mode`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'incr_value' AND ORDINAL_POSITION = 9 AND COLUMN_COMMENT = '增量起始值（历史字段，保留兼容）';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `incr_value` VARCHAR(255) DEFAULT NULL
            COMMENT '增量起始值（历史字段，保留兼容）' AFTER `incr_column`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'cursor_value' AND ORDINAL_POSITION = 10 AND COLUMN_COMMENT = '增量游标：上一次成功同步的最后一个增量字段值';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `cursor_value` VARCHAR(255) DEFAULT NULL
            COMMENT '增量游标：上一次成功同步的最后一个增量字段值' AFTER `incr_value`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'order_column' AND ORDINAL_POSITION = 11 AND COLUMN_COMMENT = '键集分页排序列，空则自动推断';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `order_column` VARCHAR(128) DEFAULT NULL
            COMMENT '键集分页排序列，空则自动推断' AFTER `cursor_value`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'cron_expression' AND ORDINAL_POSITION = 12 AND COLUMN_COMMENT = 'Quartz Cron 调度表达式';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `cron_expression` VARCHAR(100) DEFAULT NULL
            COMMENT 'Quartz Cron 调度表达式' AFTER `order_column`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'page_size' AND ORDINAL_POSITION = 13 AND COLUMN_COMMENT = '单次抓取行数（内存 chunk 大小）';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `page_size` INT DEFAULT 1000
            COMMENT '单次抓取行数（内存 chunk 大小）' AFTER `cron_expression`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'batch_size' AND ORDINAL_POSITION = 14 AND COLUMN_COMMENT = 'JDBC 批量提交行数';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `batch_size` INT DEFAULT 500
            COMMENT 'JDBC 批量提交行数' AFTER `page_size`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'mapping_json' AND ORDINAL_POSITION = 15 AND COLUMN_COMMENT = '字段映射配置（JSON 数组）';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `mapping_json` TEXT
            COMMENT '字段映射配置（JSON 数组）' AFTER `batch_size`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'source_mode' AND ORDINAL_POSITION = 16 AND COLUMN_COMMENT = '数据源模式：TABLE / CUSTOM_SQL';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `source_mode` VARCHAR(20) DEFAULT 'TABLE'
            COMMENT '数据源模式：TABLE / CUSTOM_SQL' AFTER `mapping_json`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'source_sql' AND ORDINAL_POSITION = 17 AND COLUMN_COMMENT = '自定义查询 SQL（source_mode=CUSTOM_SQL 时使用）';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `source_sql` TEXT
            COMMENT '自定义查询 SQL（source_mode=CUSTOM_SQL 时使用）' AFTER `source_mode`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'status' AND ORDINAL_POSITION = 18 AND COLUMN_COMMENT = '任务状态：ENABLED / DISABLED';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `status` VARCHAR(20) DEFAULT 'DISABLED'
            COMMENT '任务状态：ENABLED / DISABLED' AFTER `source_sql`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'safety_lag_seconds' AND ORDINAL_POSITION = 19 AND COLUMN_COMMENT = '增量读上界安全滞后秒数（规避未提交事务）';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `safety_lag_seconds` BIGINT NOT NULL DEFAULT 0
            COMMENT '增量读上界安全滞后秒数（规避未提交事务）' AFTER `status`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'lookback_seconds' AND ORDINAL_POSITION = 20 AND COLUMN_COMMENT = '增量读下界回看秒数（补迟到的历史写入）';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `lookback_seconds` BIGINT NOT NULL DEFAULT 0
            COMMENT '增量读下界回看秒数（补迟到的历史写入）' AFTER `safety_lag_seconds`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'full_sync_strategy' AND ORDINAL_POSITION = 21 AND COLUMN_COMMENT = '全量策略：TRUNCATE / DELETE / SWAP';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `full_sync_strategy` VARCHAR(16) NOT NULL DEFAULT 'TRUNCATE'
            COMMENT '全量策略：TRUNCATE / DELETE / SWAP' AFTER `lookback_seconds`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'error_policy_json' AND ORDINAL_POSITION = 22 AND COLUMN_COMMENT = '错误处理策略（JSON：重试次数/是否跳过坏行/坏行上限）';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `error_policy_json` TEXT
            COMMENT '错误处理策略（JSON：重试次数/是否跳过坏行/坏行上限）' AFTER `full_sync_strategy`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'enabled' AND ORDINAL_POSITION = 23 AND COLUMN_COMMENT = '是否启用调度：1 启用 0 停用';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `enabled` TINYINT(1) NOT NULL DEFAULT 1
            COMMENT '是否启用调度：1 启用 0 停用' AFTER `error_policy_json`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'created_at' AND ORDINAL_POSITION = 24 AND COLUMN_COMMENT = '创建时间';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `created_at` DATETIME(6) DEFAULT NULL
            COMMENT '创建时间' AFTER `enabled`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_task' AND COLUMN_NAME = 'updated_at' AND ORDINAL_POSITION = 25 AND COLUMN_COMMENT = '更新时间';
    IF v = 0 THEN
        ALTER TABLE `sync_task` MODIFY COLUMN `updated_at` DATETIME(6) DEFAULT NULL
            COMMENT '更新时间' AFTER `created_at`;
    END IF;

    -- ============ sync_record：19 列，目标顺序见 V1 ============
    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'task_id' AND ORDINAL_POSITION = 2 AND COLUMN_COMMENT = '关联的任务ID';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `task_id` BIGINT NOT NULL COMMENT '关联的任务ID' AFTER `id`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'run_key' AND ORDINAL_POSITION = 3 AND COLUMN_COMMENT = '本次执行的幂等键（唯一，防重复落库）';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `run_key` VARCHAR(64) DEFAULT NULL
            COMMENT '本次执行的幂等键（唯一，防重复落库）' AFTER `task_id`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'start_time' AND ORDINAL_POSITION = 4 AND COLUMN_COMMENT = '开始执行时间';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `start_time` DATETIME(6) NOT NULL
            COMMENT '开始执行时间' AFTER `run_key`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'end_time' AND ORDINAL_POSITION = 5 AND COLUMN_COMMENT = '结束时间';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `end_time` DATETIME(6) DEFAULT NULL
            COMMENT '结束时间' AFTER `start_time`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'status' AND ORDINAL_POSITION = 6 AND COLUMN_COMMENT = '执行状态：RUNNING / COMPLETED / FAILED / CANCELLED';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `status` VARCHAR(20) DEFAULT NULL
            COMMENT '执行状态：RUNNING / COMPLETED / FAILED / CANCELLED' AFTER `end_time`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'total_rows' AND ORDINAL_POSITION = 7 AND COLUMN_COMMENT = '源表/结果集总行数（预检时统计，可空）';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `total_rows` BIGINT DEFAULT 0
            COMMENT '源表/结果集总行数（预检时统计，可空）' AFTER `status`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'read_rows' AND ORDINAL_POSITION = 8 AND COLUMN_COMMENT = '累计读取行数（含重试重复读取）';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `read_rows` BIGINT DEFAULT 0
            COMMENT '累计读取行数（含重试重复读取）' AFTER `total_rows`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'write_rows' AND ORDINAL_POSITION = 9 AND COLUMN_COMMENT = '累计成功写入行数';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `write_rows` BIGINT DEFAULT 0
            COMMENT '累计成功写入行数' AFTER `read_rows`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'skipped_rows' AND ORDINAL_POSITION = 10 AND COLUMN_COMMENT = '被跳过的坏行数';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `skipped_rows` BIGINT DEFAULT 0
            COMMENT '被跳过的坏行数' AFTER `write_rows`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'error_rows' AND ORDINAL_POSITION = 11 AND COLUMN_COMMENT = '失败行数';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `error_rows` BIGINT DEFAULT 0
            COMMENT '失败行数' AFTER `skipped_rows`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'start_cursor' AND ORDINAL_POSITION = 12 AND COLUMN_COMMENT = '本次生效的增量读下界';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `start_cursor` VARCHAR(255) DEFAULT NULL
            COMMENT '本次生效的增量读下界' AFTER `error_rows`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'end_cursor' AND ORDINAL_POSITION = 13 AND COLUMN_COMMENT = '本次成功提交的最后一行增量值（仅成功时非空）';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `end_cursor` VARCHAR(255) DEFAULT NULL
            COMMENT '本次成功提交的最后一行增量值（仅成功时非空）' AFTER `start_cursor`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'read_millis' AND ORDINAL_POSITION = 14 AND COLUMN_COMMENT = '读取阶段耗时（毫秒）';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `read_millis` BIGINT NOT NULL DEFAULT 0
            COMMENT '读取阶段耗时（毫秒）' AFTER `end_cursor`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'write_millis' AND ORDINAL_POSITION = 15 AND COLUMN_COMMENT = '写入阶段耗时（毫秒）';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `write_millis` BIGINT NOT NULL DEFAULT 0
            COMMENT '写入阶段耗时（毫秒）' AFTER `read_millis`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'total_millis' AND ORDINAL_POSITION = 16 AND COLUMN_COMMENT = '总耗时（毫秒）';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `total_millis` BIGINT NOT NULL DEFAULT 0
            COMMENT '总耗时（毫秒）' AFTER `write_millis`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'error_message' AND ORDINAL_POSITION = 17 AND COLUMN_COMMENT = '错误信息（已清洗，不含明文口令）';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `error_message` TEXT
            COMMENT '错误信息（已清洗，不含明文口令）' AFTER `total_millis`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'preflight_json' AND ORDINAL_POSITION = 18 AND COLUMN_COMMENT = '预检结果（JSON 数组，含 code/message/hint）';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `preflight_json` TEXT
            COMMENT '预检结果（JSON 数组，含 code/message/hint）' AFTER `error_message`;
    END IF;

    SELECT COUNT(*) INTO v FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'sync_record' AND COLUMN_NAME = 'trigger_type' AND ORDINAL_POSITION = 19 AND COLUMN_COMMENT = '触发方式：SCHEDULED / MANUAL';
    IF v = 0 THEN
        ALTER TABLE `sync_record` MODIFY COLUMN `trigger_type` VARCHAR(20) DEFAULT NULL
            COMMENT '触发方式：SCHEDULED / MANUAL' AFTER `preflight_json`;
    END IF;
END$$

-- ---------------------------------------------------------------------------
-- 第三段：存量数据修补
-- ---------------------------------------------------------------------------
DROP PROCEDURE IF EXISTS datasync_v2_data_fix$$

CREATE PROCEDURE datasync_v2_data_fix()
BEGIN
    -- cursor_value 为空但 incr_value 有值：继承历史水位，避免升级后从头全量重跑
    UPDATE `sync_task`
       SET `cursor_value` = `incr_value`
     WHERE (`cursor_value` IS NULL OR `cursor_value` = '')
       AND `incr_value` IS NOT NULL
       AND `incr_value` <> '';

    -- enabled 与历史 status 字段对齐（两条都是幂等的）
    UPDATE `sync_task` SET `enabled` = 1 WHERE `status` = 'ENABLED';
    UPDATE `sync_task` SET `enabled` = 0 WHERE `status` = 'DISABLED';
END$$

DELIMITER ;

CALL datasync_v2_align_columns();
CALL datasync_v2_reorder();
CALL datasync_v2_data_fix();

DROP PROCEDURE IF EXISTS datasync_v2_align_columns;
DROP PROCEDURE IF EXISTS datasync_v2_reorder;
DROP PROCEDURE IF EXISTS datasync_v2_data_fix;
