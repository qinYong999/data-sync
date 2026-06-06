package com.datasync.server.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseCommentInitializer {

    private static final Logger log = LoggerFactory.getLogger(DatabaseCommentInitializer.class);
    private final JdbcTemplate jdbc;

    public DatabaseCommentInitializer(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @EventListener(ApplicationReadyEvent.class)
    public void addTableComments() {
        String[][] tables = {
            {"datasource",               "数据源配置表"},
            {"sync_task",                "同步任务配置表"},
            {"sync_record",              "同步执行记录表"},
            {"BATCH_JOB_INSTANCE",       "Spring Batch 任务实例表"},
            {"BATCH_JOB_EXECUTION",      "Spring Batch 任务执行记录表"},
            {"BATCH_JOB_EXECUTION_PARAMS", "Spring Batch 任务执行参数表"},
            {"BATCH_JOB_EXECUTION_CONTEXT", "Spring Batch 任务执行上下文表"},
            {"BATCH_STEP_EXECUTION",     "Spring Batch 步骤执行记录表"},
            {"BATCH_STEP_EXECUTION_CONTEXT", "Spring Batch 步骤执行上下文表"},
        };
        for (String[] t : tables) {
            try {
                jdbc.execute("ALTER TABLE " + t[0] + " COMMENT = '" + t[1] + "'");
                log.info("添加表注释: {} = {}", t[0], t[1]);
            } catch (Exception e) {
                log.debug("跳过 {}: {}", t[0], e.getMessage());
            }
        }

        // 列级注释查询 INFORMATION_SCHEMA 后再追加
        addSafeColumnComments();
    }

    private void addSafeColumnComments() {
        Object[][] cols = {
            // datasource
            {"datasource", "id", "数据源ID"},
            {"datasource", "name", "数据源名称"},
            {"datasource", "db_type", "数据库类型：MYSQL/DM8"},
            {"datasource", "host", "主机地址"},
            {"datasource", "port", "端口号"},
            {"datasource", "database_name", "数据库名"},
            {"datasource", "username", "登录用户名"},
            {"datasource", "password", "登录密码（加密存储）"},
            {"datasource", "created_at", "创建时间"},
            {"datasource", "updated_at", "更新时间"},
            // sync_task
            {"sync_task", "id", "任务ID"},
            {"sync_task", "name", "任务名称"},
            {"sync_task", "source_ds_id", "源数据源ID"},
            {"sync_task", "target_ds_id", "目标数据源ID"},
            {"sync_task", "source_table", "源表名"},
            {"sync_task", "target_table", "目标表名"},
            {"sync_task", "sync_mode", "同步模式：FULL/INCR/FULL_INCR"},
            {"sync_task", "incr_column", "增量同步时间戳列名"},
            {"sync_task", "incr_value", "增量同步起始值"},
            {"sync_task", "cron_expression", "Quartz Cron 调度表达式"},
            {"sync_task", "page_size", "每次读取行数(默认1000)"},
            {"sync_task", "batch_size", "每批写入行数(默认500)"},
            {"sync_task", "mapping_json", "字段映射配置JSON"},
            {"sync_task", "status", "任务状态：ENABLED/DISABLED"},
            {"sync_task", "created_at", "创建时间"},
            {"sync_task", "updated_at", "更新时间"},
            // sync_record
            {"sync_record", "id", "记录ID"},
            {"sync_record", "task_id", "关联任务ID"},
            {"sync_record", "start_time", "开始执行时间"},
            {"sync_record", "end_time", "结束时间"},
            {"sync_record", "status", "执行状态：RUNNING/SUCCESS/FAILED/STOPPED"},
            {"sync_record", "total_rows", "总行数"},
            {"sync_record", "read_rows", "读取行数"},
            {"sync_record", "write_rows", "写入行数"},
            {"sync_record", "error_rows", "失败行数"},
            {"sync_record", "error_message", "错误信息"},
            {"sync_record", "trigger_type", "触发方式：SCHEDULED/MANUAL"},
        };
        for (Object[] c : cols) {
            try {
                String table = (String)c[0];
                String col   = (String)c[1];
                String cmt   = (String)c[2];
                // 先查询当前列的精确定义
                String ddl = jdbc.queryForObject(
                    "SELECT COLUMN_TYPE, IS_NULLABLE, COALESCE(COLUMN_DEFAULT,''), EXTRA FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = (SELECT DATABASE()) AND TABLE_NAME = ? AND COLUMN_NAME = ?",
                    (rs, rn) -> {
                        String type = rs.getString("COLUMN_TYPE");
                        String nullable = "NO".equals(rs.getString("IS_NULLABLE")) ? " NOT NULL" : "";
                        String def = rs.getString(3);
                        String defClause = def.isEmpty() ? "" : " DEFAULT " + def;
                        String extra = rs.getString("EXTRA");
                        String extraClause = extra.isEmpty() ? "" : " " + extra;
                        return type + nullable + defClause + extraClause;
                    },
                    table, col
                );
                jdbc.execute(String.format("ALTER TABLE %s MODIFY COLUMN %s %s COMMENT '%s'", table, col, ddl, cmt));
                log.debug("列注释: {}.{}", table, col);
            } catch (Exception e) {
                log.debug("跳过 {}.{}: {}", c[0], c[1], e.getMessage());
            }
        }
    }
}