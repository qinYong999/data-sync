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
    public void init() {
        for (String[] t : TABLES) {
            try {
                jdbc.execute("ALTER TABLE " + t[0] + " COMMENT = '" + t[1] + "'");
                log.info("表注释: {} = {}", t[0], t[1]);
            } catch (Exception e) {
                log.debug("跳过 {}: {}", t[0], e.getMessage());
            }
        }
        for (Object[] c : COLUMNS) {
            try {
                String t = (String)c[0], col = (String)c[1], cmt = (String)c[2];
                String ddl = jdbc.queryForObject(
                    "SELECT COLUMN_TYPE, IS_NULLABLE, COALESCE(COLUMN_DEFAULT,''), EXTRA " +
                    "FROM INFORMATION_SCHEMA.COLUMNS " +
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
                    t, col
                );
                jdbc.execute(String.format("ALTER TABLE %s MODIFY COLUMN %s %s COMMENT '%s'", t, col, ddl, cmt));
                log.debug("列注释: {}.{} [{}]", t, col, cmt);
            } catch (Exception e) {
                log.debug("跳过 {}.{}: {}", c[0], c[1], e.getMessage());
            }
        }
    }

    private static final String[][] TABLES = {
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

    private static final Object[][] COLUMNS = {
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
        {"sync_task", "page_size", "每次读取行数（默认1000）"},
        {"sync_task", "batch_size", "每批写入行数（默认500）"},
        {"sync_task", "mapping_json", "字段映射配置JSON"},
        {"sync_task", "status", "任务状态：ENABLED/DISABLED"},
        {"sync_task", "created_at", "创建时间"},
        {"sync_task", "updated_at", "更新时间"},
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
        {"BATCH_JOB_INSTANCE", "JOB_INSTANCE_ID", "任务实例ID（主键）"},
        {"BATCH_JOB_INSTANCE", "VERSION", "乐观锁版本号"},
        {"BATCH_JOB_INSTANCE", "JOB_NAME", "任务名称"},
        {"BATCH_JOB_INSTANCE", "JOB_KEY", "任务唯一键（用于去重）"},
        {"BATCH_JOB_EXECUTION", "JOB_EXECUTION_ID", "任务执行ID（主键）"},
        {"BATCH_JOB_EXECUTION", "VERSION", "乐观锁版本号"},
        {"BATCH_JOB_EXECUTION", "JOB_INSTANCE_ID", "关联任务实例ID"},
        {"BATCH_JOB_EXECUTION", "CREATE_TIME", "创建时间"},
        {"BATCH_JOB_EXECUTION", "START_TIME", "开始时间"},
        {"BATCH_JOB_EXECUTION", "END_TIME", "结束时间"},
        {"BATCH_JOB_EXECUTION", "STATUS", "执行状态：COMPLETED/FAILED/STARTED/STOPPED"},
        {"BATCH_JOB_EXECUTION", "EXIT_CODE", "退出码"},
        {"BATCH_JOB_EXECUTION", "EXIT_MESSAGE", "退出信息"},
        {"BATCH_JOB_EXECUTION", "LAST_UPDATED", "最后更新时间"},
        {"BATCH_JOB_EXECUTION", "JOB_CONFIGURATION_LOCATION", "任务配置位置"},
        {"BATCH_JOB_EXECUTION_PARAMS", "JOB_EXECUTION_ID", "关联任务执行ID"},
        {"BATCH_JOB_EXECUTION_PARAMS", "TYPE_CD", "参数类型：STRING/DATE/LONG/DOUBLE"},
        {"BATCH_JOB_EXECUTION_PARAMS", "KEY_NAME", "参数键名"},
        {"BATCH_JOB_EXECUTION_PARAMS", "STRING_VAL", "字符串参数值"},
        {"BATCH_JOB_EXECUTION_PARAMS", "DATE_VAL", "日期参数值"},
        {"BATCH_JOB_EXECUTION_PARAMS", "LONG_VAL", "长整型参数值"},
        {"BATCH_JOB_EXECUTION_PARAMS", "DOUBLE_VAL", "浮点型参数值"},
        {"BATCH_JOB_EXECUTION_PARAMS", "IDENTIFYING", "是否标识参数：Y/N"},
        {"BATCH_JOB_EXECUTION_CONTEXT", "JOB_EXECUTION_ID", "关联任务执行ID"},
        {"BATCH_JOB_EXECUTION_CONTEXT", "SHORT_CONTEXT", "上下文简短内容"},
        {"BATCH_JOB_EXECUTION_CONTEXT", "SERIALIZED_CONTEXT", "上下文序列化内容"},
        {"BATCH_STEP_EXECUTION", "STEP_EXECUTION_ID", "步骤执行ID（主键）"},
        {"BATCH_STEP_EXECUTION", "VERSION", "乐观锁版本号"},
        {"BATCH_STEP_EXECUTION", "STEP_NAME", "步骤名称"},
        {"BATCH_STEP_EXECUTION", "JOB_EXECUTION_ID", "关联任务执行ID"},
        {"BATCH_STEP_EXECUTION", "START_TIME", "开始时间"},
        {"BATCH_STEP_EXECUTION", "END_TIME", "结束时间"},
        {"BATCH_STEP_EXECUTION", "STATUS", "执行状态：COMPLETED/FAILED/STARTED/STOPPED"},
        {"BATCH_STEP_EXECUTION", "COMMIT_COUNT", "提交事务次数"},
        {"BATCH_STEP_EXECUTION", "READ_COUNT", "读取记录数"},
        {"BATCH_STEP_EXECUTION", "FILTER_COUNT", "过滤记录数"},
        {"BATCH_STEP_EXECUTION", "WRITE_COUNT", "写入记录数"},
        {"BATCH_STEP_EXECUTION", "READ_SKIP_COUNT", "读取跳过数"},
        {"BATCH_STEP_EXECUTION", "WRITE_SKIP_COUNT", "写入跳过数"},
        {"BATCH_STEP_EXECUTION", "PROCESS_SKIP_COUNT", "处理跳过数"},
        {"BATCH_STEP_EXECUTION", "ROLLBACK_COUNT", "回滚次数"},
        {"BATCH_STEP_EXECUTION", "EXIT_CODE", "退出码"},
        {"BATCH_STEP_EXECUTION", "EXIT_MESSAGE", "退出信息"},
        {"BATCH_STEP_EXECUTION", "LAST_UPDATED", "最后更新时间"},
        {"BATCH_STEP_EXECUTION_CONTEXT", "STEP_EXECUTION_ID", "关联步骤执行ID"},
        {"BATCH_STEP_EXECUTION_CONTEXT", "SHORT_CONTEXT", "上下文简短内容"},
        {"BATCH_STEP_EXECUTION_CONTEXT", "SERIALIZED_CONTEXT", "上下文序列化内容"},
    };
}
