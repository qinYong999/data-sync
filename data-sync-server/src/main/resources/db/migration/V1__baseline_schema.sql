-- ============================================================================
-- V1 — 元数据库基线结构（最终形态）
--
-- 与 V2 的职责划分（重要，不要合并）：
--   * 全新库：没有 flyway_schema_history → 执行 V1，一次性建出最终结构；
--     V2 里的条件判断会发现列已存在而全部跳过，等于空操作。
--   * 旧库（历史上由 Hibernate ddl-auto=update 建表）：Flyway 将其基线化为版本 1，
--     V1 被跳过，由 V2 把旧结构补齐到与本文件完全一致的最终形态。
--   两条路径收敛到同一结果。
--
-- 因此本文件用**普通 CREATE TABLE**（不加 IF NOT EXISTS）：全新库上若出现半成品结构，
-- 应当立刻失败而不是被静默跳过。也绝不用 DROP：迁移脚本不允许销毁既有配置与执行历史。
--
-- 字符集统一 utf8mb4：任务名、表名、错误信息都可能包含中文甚至 emoji。
-- ============================================================================

CREATE TABLE `datasource` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '数据源ID',
    `name`          VARCHAR(100) NOT NULL COMMENT '数据源名称',
    `db_type`       VARCHAR(20)  NOT NULL COMMENT '数据库类型：MYSQL / DM8',
    `host`          VARCHAR(255) NOT NULL COMMENT '主机地址',
    `port`          INT          NOT NULL COMMENT '端口号',
    `database_name` VARCHAR(100) NOT NULL COMMENT '数据库名',
    `username`      VARCHAR(100) NOT NULL COMMENT '登录用户名',
    `password`      VARCHAR(255) NOT NULL COMMENT '登录密码（AES-GCM 加密，ENC( 前缀标识密文）',
    `created_at`    DATETIME(6)  DEFAULT NULL COMMENT '创建时间',
    `updated_at`    DATETIME(6)  DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_datasource_name` (`name`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '数据源配置表';

CREATE TABLE `sync_task` (
    `id`                  BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务ID',
    `name`                VARCHAR(200) NOT NULL COMMENT '任务名称',
    `source_ds_id`        BIGINT       NOT NULL COMMENT '源数据源ID',
    `target_ds_id`        BIGINT       NOT NULL COMMENT '目标数据源ID',
    `source_table`        VARCHAR(200) NOT NULL COMMENT '源表名',
    `target_table`        VARCHAR(200) NOT NULL COMMENT '目标表名',
    `sync_mode`           VARCHAR(20)  NOT NULL COMMENT '同步模式：FULL / INCR / FULL_INCR',
    `incr_column`         VARCHAR(100) DEFAULT NULL COMMENT '增量字段（单调递增列或时间戳列）',
    `incr_value`          VARCHAR(255) DEFAULT NULL COMMENT '增量起始值（历史字段，保留兼容）',
    `cursor_value`        VARCHAR(255) DEFAULT NULL COMMENT '增量游标：上一次成功同步的最后一个增量字段值',
    `order_column`        VARCHAR(128) DEFAULT NULL COMMENT '键集分页排序列，空则自动推断',
    `cron_expression`     VARCHAR(100) DEFAULT NULL COMMENT 'Quartz Cron 调度表达式',
    `page_size`           INT          DEFAULT 1000 COMMENT '单次抓取行数（内存 chunk 大小）',
    `batch_size`          INT          DEFAULT 500 COMMENT 'JDBC 批量提交行数',
    `mapping_json`        TEXT         COMMENT '字段映射配置（JSON 数组）',
    `source_mode`         VARCHAR(20)  DEFAULT 'TABLE' COMMENT '数据源模式：TABLE / CUSTOM_SQL',
    `source_sql`          TEXT         COMMENT '自定义查询 SQL（source_mode=CUSTOM_SQL 时使用）',
    `status`              VARCHAR(20)  DEFAULT 'DISABLED' COMMENT '任务状态：ENABLED / DISABLED',
    `safety_lag_seconds`  BIGINT       NOT NULL DEFAULT 0 COMMENT '增量读上界安全滞后秒数（规避未提交事务）',
    `lookback_seconds`    BIGINT       NOT NULL DEFAULT 0 COMMENT '增量读下界回看秒数（补迟到的历史写入）',
    `full_sync_strategy`  VARCHAR(16)  NOT NULL DEFAULT 'TRUNCATE' COMMENT '全量策略：TRUNCATE / DELETE / SWAP',
    `error_policy_json`   TEXT         COMMENT '错误处理策略（JSON：重试次数/是否跳过坏行/坏行上限）',
    `enabled`             TINYINT(1)   NOT NULL DEFAULT 1 COMMENT '是否启用调度：1 启用 0 停用',
    `created_at`          DATETIME(6)  DEFAULT NULL COMMENT '创建时间',
    `updated_at`          DATETIME(6)  DEFAULT NULL COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_sync_task_source_ds` (`source_ds_id`),
    KEY `idx_sync_task_target_ds` (`target_ds_id`),
    KEY `idx_sync_task_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '同步任务配置表';

CREATE TABLE `sync_record` (
    `id`              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '记录ID',
    `task_id`         BIGINT      NOT NULL COMMENT '关联的任务ID',
    `run_key`         VARCHAR(64) DEFAULT NULL COMMENT '本次执行的幂等键（唯一，防重复落库）',
    `start_time`      DATETIME(6) NOT NULL COMMENT '开始执行时间',
    `end_time`        DATETIME(6) DEFAULT NULL COMMENT '结束时间',
    `status`          VARCHAR(20) DEFAULT NULL COMMENT '执行状态：RUNNING / COMPLETED / FAILED / CANCELLED',
    `total_rows`      BIGINT      DEFAULT 0 COMMENT '源表/结果集总行数（预检时统计，可空）',
    `read_rows`       BIGINT      DEFAULT 0 COMMENT '累计读取行数（含重试重复读取）',
    `write_rows`      BIGINT      DEFAULT 0 COMMENT '累计成功写入行数',
    `skipped_rows`    BIGINT      DEFAULT 0 COMMENT '被跳过的坏行数',
    `error_rows`      BIGINT      DEFAULT 0 COMMENT '失败行数',
    `start_cursor`    VARCHAR(255) DEFAULT NULL COMMENT '本次生效的增量读下界',
    `end_cursor`      VARCHAR(255) DEFAULT NULL COMMENT '本次成功提交的最后一行增量值（仅成功时非空）',
    `read_millis`     BIGINT      NOT NULL DEFAULT 0 COMMENT '读取阶段耗时（毫秒）',
    `write_millis`    BIGINT      NOT NULL DEFAULT 0 COMMENT '写入阶段耗时（毫秒）',
    `total_millis`    BIGINT      NOT NULL DEFAULT 0 COMMENT '总耗时（毫秒）',
    `error_message`   TEXT        COMMENT '错误信息（已清洗，不含明文口令）',
    `preflight_json`  TEXT        COMMENT '预检结果（JSON 数组，含 code/message/hint）',
    `trigger_type`    VARCHAR(20) DEFAULT NULL COMMENT '触发方式：SCHEDULED / MANUAL',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sync_record_run_key` (`run_key`),
    KEY `idx_sync_record_task_start` (`task_id`, `start_time`),
    KEY `idx_sync_record_status` (`status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '同步执行记录表';

CREATE TABLE `sync_error` (
    `id`         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '错误行ID',
    `record_id`  BIGINT       NOT NULL COMMENT '关联的执行记录ID',
    `task_id`    BIGINT       NOT NULL COMMENT '关联的任务ID',
    `phase`      VARCHAR(16)  NOT NULL COMMENT '失败阶段：PREFLIGHT / READ / MAP / WRITE',
    `row_key`    VARCHAR(255) DEFAULT NULL COMMENT '失败行的主键值（尽力而为）',
    `message`    VARCHAR(1000) DEFAULT NULL COMMENT '错误原因',
    `row_data`   TEXT         COMMENT '源行数据（JSON，超长会截断）',
    `retryable`  TINYINT(1)   NOT NULL DEFAULT 0 COMMENT '是否可重试：1 可重试 0 致命',
    `created_at` DATETIME(6)  NOT NULL COMMENT '记录时间',
    PRIMARY KEY (`id`),
    KEY `idx_sync_error_record` (`record_id`),
    KEY `idx_sync_error_task` (`task_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci COMMENT = '同步失败行明细表';
