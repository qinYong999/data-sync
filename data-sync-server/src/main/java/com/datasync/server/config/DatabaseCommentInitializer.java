package com.datasync.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 给元数据表与列补中文注释（运维直接读库时的可读性）。
 *
 * <p>改造点：删除了 Spring Batch 时代遗留的 {@code BATCH_*} 表条目（Batch 已移除，D1），
 * 补齐了契约 §4.2 新增的列与 {@code sync_error} 表。</p>
 *
 * <p><b>D3 / D7 修正（QA 实测）</b>：</p>
 * <ol>
 *   <li>只在注释<b>为空</b>时才补 —— 注释的唯一事实源是 Flyway，非空注释一律跳过，
 *       不再出现"每次启动刷 3 条 WARN"的噪音，也不会偷偷改写迁移脚本给的文案；</li>
 *   <li>{@code DEFAULT} 子句按类型正确转义（字符串加引号并转义内部引号、数值与
 *       {@code CURRENT_TIMESTAMP} 等表达式不加引号）—— 旧实现直接拼接会生成
 *       {@code DEFAULT TABLE} → ERROR 1064；</li>
 *   <li>重建 DDL 保留 {@code CHARACTER SET} / {@code COLLATE}，避免 MODIFY 意外改变列属性；</li>
 *   <li>失败可见且不误导：单条 WARN 带表/列/SQL 上下文，末尾汇总区分"补齐 / 跳过 / 失败"。</li>
 * </ol>
 */
@Component
public class DatabaseCommentInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCommentInitializer.class);

    private final JdbcTemplate jdbc;

    public DatabaseCommentInitializer(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(30)
    public void init() {
        int applied = 0;
        int skipped = 0;
        int failed = 0;
        for (String[] table : TABLES) {
            try {
                if (tableCommentMissing(table[0])) {
                    jdbc.execute("ALTER TABLE " + table[0] + " COMMENT = '" + table[1] + "'");
                    applied++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
                log.warn("补表注释失败 {}：SQL=[ALTER TABLE {} COMMENT = '{}'] 原因={}",
                    table[0], table[0], table[1], e.getMessage());
            }
        }
        for (Object[] column : COLUMNS) {
            try {
                if (fillColumnCommentIfMissing((String) column[0], (String) column[1], (String) column[2])) {
                    applied++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                failed++;
                // 失败必须带具体 SQL 片段，否则运维只能看到一个"bad SQL grammar"而无从下手
                log.warn("补列注释失败 {}.{}：原因={}", column[0], column[1], e.getMessage());
                log.warn("  失败 SQL 上下文: 表={} 列={} 目标注释={}", column[0], column[1], column[2]);
            }
        }
        log.info("元数据注释检查完成：本次补齐 {} 条，跳过 {} 条（注释已存在，Flyway 为唯一事实源），失败 {} 条",
            applied, skipped, failed);
        if (failed > 0) {
            log.warn("元数据注释补齐有 {} 条失败（不影响启动；上面每条都带表/列/SQL 上下文，可人工执行 ALTER TABLE ... COMMENT 补齐）",
                failed);
        }
    }

    /**
     * 表注释是否**缺失**（为空）。
     *
     * <p>刻意不比较"是否与目标文案一致"：注释的唯一事实源是 Flyway，
     * 只要不是空的就说明有人（迁移脚本）已经给了它一个正式注释，本组件不该去改写。</p>
     */
    private boolean tableCommentMissing(String table) {
        String current = jdbc.query(
            "SELECT TABLE_COMMENT FROM INFORMATION_SCHEMA.TABLES "
                + "WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND TABLE_NAME = ?",
            rs -> rs.next() ? rs.getString(1) : null, table);
        return current != null && current.isBlank();
    }

    /**
     * 仅当列注释**为空**时才补，且重建 DDL 时：
     * <ul>
     *   <li>字符串型默认值加单引号并转义内部单引号（曾经的缺陷：直接拼接生成
     *       {@code DEFAULT TABLE} → ERROR 1064，每次启动稳定刷 3 条 WARN）；</li>
     *   <li>数值默认值不加引号，{@code CURRENT_TIMESTAMP} 等表达式不加引号；</li>
     *   <li>保留 {@code CHARACTER SET} / {@code COLLATE}，避免这一次 DDL 顺手改掉列属性。</li>
     * </ul>
     */
    private boolean fillColumnCommentIfMissing(String table, String name, String comment) {
        ColumnState state = jdbc.query(
            "SELECT COLUMN_TYPE, IS_NULLABLE, COLUMN_DEFAULT, EXTRA, COLUMN_COMMENT, "
                + "CHARACTER_SET_NAME, COLLATION_NAME "
                + "FROM INFORMATION_SCHEMA.COLUMNS "
                + "WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND TABLE_NAME = ? AND COLUMN_NAME = ?",
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                String type = rs.getString("COLUMN_TYPE");
                String nullable = "NO".equals(rs.getString("IS_NULLABLE")) ? " NOT NULL" : "";
                String extra = rs.getString("EXTRA");
                String extraClause = extra == null || extra.isEmpty() ? "" : " " + extra;
                String charset = rs.getString("CHARACTER_SET_NAME");
                String collation = rs.getString("COLLATION_NAME");
                StringBuilder ddl = new StringBuilder(type)
                    .append(nullable)
                    .append(defaultClause(type, rs.getString("COLUMN_DEFAULT")))
                    .append(extraClause);
                if (charset != null && !charset.isEmpty()) {
                    ddl.append(" CHARACTER SET ").append(charset);
                }
                if (collation != null && !collation.isEmpty()) {
                    ddl.append(" COLLATE ").append(collation);
                }
                return new ColumnState(ddl.toString(), rs.getString("COLUMN_COMMENT"));
            },
            table, name);
        if (state == null || (state.comment() != null && !state.comment().isBlank())) {
            return false;
        }
        jdbc.execute(String.format("ALTER TABLE %s MODIFY COLUMN %s %s COMMENT '%s'",
            table, name, state.ddl(), comment));
        return true;
    }

    /** 当前列定义（用于重建 DDL）与当前注释 */
    private record ColumnState(String ddl, String comment) { }

    /**
     * 生成 {@code DEFAULT ...} 子句（含正确转义）。
     *
     * <p>这是 D7 的根因修复点：{@code COLUMN_DEFAULT} 是"裸值"，直接拼进 DDL 会把字符串默认值写成
     * {@code DEFAULT TABLE}（正确是 {@code DEFAULT 'TABLE'}）→ ERROR 1064，且每次启动都刷 WARN。</p>
     *
     * <p>规则：{@code NULL}/表达式（{@code CURRENT_TIMESTAMP} 等）与数值按原样输出；
     * 位字面量 {@code b'...'} 原样输出；其余一律按字符串字面量加单引号并转义内部单引号。</p>
     */
    static String defaultClause(String columnType, String rawDefault) {
        if (rawDefault == null || rawDefault.isEmpty()) {
            return "";
        }
        String trimmed = rawDefault.trim();
        String upper = trimmed.toUpperCase(java.util.Locale.ROOT);
        if ("NULL".equals(upper)) {
            return " DEFAULT NULL";
        }
        if (upper.startsWith("CURRENT_TIMESTAMP") || upper.startsWith("CURRENT_DATE")
            || upper.startsWith("CURRENT_TIME") || upper.startsWith("NOW()")
            || upper.startsWith("UUID") || upper.startsWith("(")) {
            // 表达式/函数：不能加引号，否则会被当成字符串字面量
            return " DEFAULT " + trimmed;
        }
        if (upper.startsWith("B'") || upper.startsWith("X'")) {
            return " DEFAULT " + trimmed; // 位/十六进制字面量
        }
        String type = columnType == null ? "" : columnType.toUpperCase(java.util.Locale.ROOT);
        boolean numericType = type.startsWith("INT") || type.startsWith("BIGINT") || type.startsWith("SMALLINT")
            || type.startsWith("TINYINT") || type.startsWith("MEDIUMINT") || type.startsWith("DECIMAL")
            || type.startsWith("NUMERIC") || type.startsWith("FLOAT") || type.startsWith("DOUBLE")
            || type.startsWith("REAL") || type.startsWith("BIT") || type.startsWith("BOOL")
            || type.startsWith("YEAR");
        if (numericType && trimmed.matches("[+-]?\\d+(\\.\\d+)?")) {
            return " DEFAULT " + trimmed; // 数值默认值不加引号
        }
        return " DEFAULT '" + trimmed.replace("'", "''") + "'";
    }

    private static final String[][] TABLES = {
        { "datasource", "数据源配置表" },
        { "sync_task", "同步任务配置表" },
        { "sync_record", "同步执行记录表" },
        { "sync_error", "同步失败行明细表" },
    };

    private static final Object[][] COLUMNS = {
        // ---------- datasource ----------
        { "datasource", "id", "数据源ID" },
        { "datasource", "name", "数据源名称" },
        { "datasource", "db_type", "数据库类型：MYSQL/DM8" },
        { "datasource", "host", "主机地址" },
        { "datasource", "port", "端口号" },
        { "datasource", "database_name", "数据库名" },
        { "datasource", "username", "登录用户名" },
        { "datasource", "password", "登录密码（AES-GCM 加密存储，ENC( 前缀标识密文）" },
        { "datasource", "created_at", "创建时间" },
        { "datasource", "updated_at", "更新时间" },
        // ---------- sync_task ----------
        { "sync_task", "id", "任务ID" },
        { "sync_task", "name", "任务名称" },
        { "sync_task", "source_ds_id", "源数据源ID" },
        { "sync_task", "target_ds_id", "目标数据源ID" },
        { "sync_task", "source_table", "源表名" },
        { "sync_task", "target_table", "目标表名" },
        { "sync_task", "sync_mode", "同步模式：FULL/INCR/FULL_INCR" },
        { "sync_task", "incr_column", "增量字段（自增数值列或时间戳列）" },
        { "sync_task", "incr_value", "增量起始值（历史字段，保留兼容）" },
        { "sync_task", "cursor_value", "增量游标：上一次成功同步的最后一个增量字段值（空=首次）" },
        { "sync_task", "order_column", "键集分页排序列，空则自动推断" },
        { "sync_task", "cron_expression", "Quartz Cron 调度表达式" },
        { "sync_task", "page_size", "单次抓取行数（内存 chunk）" },
        { "sync_task", "batch_size", "JDBC 批量提交行数" },
        { "sync_task", "mapping_json", "字段映射配置（JSON 数组）" },
        { "sync_task", "source_mode", "数据源模式：TABLE/CUSTOM_SQL" },
        { "sync_task", "source_sql", "自定义查询 SQL" },
        { "sync_task", "status", "任务状态：ENABLED/DISABLED（与 enabled 同步维护）" },
        { "sync_task", "safety_lag_seconds", "增量读上界安全滞后秒数" },
        { "sync_task", "lookback_seconds", "增量读下界回看秒数" },
        { "sync_task", "full_sync_strategy", "全量策略：TRUNCATE/DELETE/SWAP" },
        { "sync_task", "error_policy_json", "错误处理策略（JSON）" },
        { "sync_task", "enabled", "是否启用调度：1 启用 0 停用" },
        { "sync_task", "created_at", "创建时间" },
        { "sync_task", "updated_at", "更新时间" },
        // ---------- sync_record ----------
        { "sync_record", "id", "执行记录ID" },
        { "sync_record", "task_id", "关联任务ID" },
        { "sync_record", "run_key", "执行幂等键（运行中为 RUNNING-{taskId}，兼作互斥令牌）" },
        { "sync_record", "start_time", "开始执行时间" },
        { "sync_record", "end_time", "结束时间" },
        { "sync_record", "status", "执行状态：RUNNING/COMPLETED/FAILED/CANCELLED" },
        { "sync_record", "total_rows", "源表总行数（预检统计，可空）" },
        { "sync_record", "read_rows", "累计读取行数" },
        { "sync_record", "write_rows", "累计写入行数" },
        { "sync_record", "skipped_rows", "被跳过的坏行数" },
        { "sync_record", "error_rows", "失败行数" },
        { "sync_record", "start_cursor", "本次生效的增量读下界" },
        { "sync_record", "end_cursor", "本次成功提交的最后一行增量值（仅成功时非空）" },
        { "sync_record", "read_millis", "读取阶段耗时（毫秒）" },
        { "sync_record", "write_millis", "写入阶段耗时（毫秒）" },
        { "sync_record", "total_millis", "总耗时（毫秒）" },
        { "sync_record", "error_message", "错误信息（已清洗，不含明文口令）" },
        { "sync_record", "preflight_json", "预检结果（JSON 数组）" },
        { "sync_record", "trigger_type", "触发方式：SCHEDULED/MANUAL" },
        // ---------- sync_error ----------
        { "sync_error", "id", "错误行ID" },
        { "sync_error", "record_id", "关联执行记录ID" },
        { "sync_error", "task_id", "关联任务ID" },
        { "sync_error", "phase", "失败阶段：PREFLIGHT/READ/MAP/WRITE" },
        { "sync_error", "row_key", "失败行主键值（尽力而为）" },
        { "sync_error", "message", "错误原因" },
        { "sync_error", "row_data", "源行数据（JSON，超长截断）" },
        { "sync_error", "retryable", "是否可重试：1 可重试 0 致命" },
        { "sync_error", "created_at", "记录时间" },
    };
}
