package com.datasync.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 启动自愈必须发生在**真实启动路径**上（`SyncStartupInitializer` 的 ApplicationReadyEvent），
 * 而不是只在测试里手工调用 {@code recoverOrphanRuns()}。
 *
 * <p>做法：夹具在 {@code @BeforeAll}（即 Spring 上下文创建<b>之前</b>）用裸 JDBC 写入
 * 一条 {@code status='RUNNING'} 且占着 {@code run_key='RUNNING-9450'} 的残留记录，
 * 然后让应用正常启动。如果自愈真的挂在启动事件上，这条记录必须已经被改成 FAILED
 * 且令牌已释放——否则该任务会永久无法触发（这是生产上"进程被 kill 后任务再也跑不起来"的根因）。</p>
 *
 * <p>本类刻意使用独立属性（app.sync.worker-threads=5）以获得<b>独立上下文</b>，
 * 保证它的上下文是在夹具写入之后才创建的。</p>
 */
@org.springframework.test.context.TestPropertySource(properties = {
    "app.sync.worker-threads=5"
})
class StartupSelfHealingIntegrationTest extends AbstractServerIntegrationTest {

    private static final long TASK_ID = 9450L;
    private static final long RECORD_ID = 9450L;

    @org.junit.jupiter.api.BeforeAll
    static void insertOrphanRunningRecordBeforeContextStarts() throws Exception {
        try (Connection connection = testConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                "INSERT INTO datasource (id, name, db_type, host, port, database_name, username, password, "
                    + "created_at, updated_at) VALUES (9001, '启动自愈源', 'MYSQL', '127.0.0.1', 3306, "
                    + "'datasync_test_platform', 'root', 'ENC(placeholder)', NOW(), NOW()) "
                    + "ON DUPLICATE KEY UPDATE name = VALUES(name)");
            statement.executeUpdate(
                "INSERT INTO datasource (id, name, db_type, host, port, database_name, username, password, "
                    + "created_at, updated_at) VALUES (9002, '启动自愈目标', 'MYSQL', '127.0.0.1', 3306, "
                    + "'datasync_test_platform', 'root', 'ENC(placeholder)', NOW(), NOW()) "
                    + "ON DUPLICATE KEY UPDATE name = VALUES(name)");
            // 任务水位固定在 500：自愈之后必须仍然是 500
            statement.executeUpdate(
                "INSERT INTO sync_task (id, name, source_ds_id, target_ds_id, source_table, target_table, sync_mode, "
                    + "incr_column, cursor_value, page_size, batch_size, source_mode, status, safety_lag_seconds, "
                    + "lookback_seconds, full_sync_strategy, enabled, created_at, updated_at) "
                    + "VALUES (9450, '启动自愈夹具任务', 9001, 9002, 't_src_9450', 't_dst_9450', 'INCR', 'id', '500', "
                    + "100, 50, 'TABLE', 'DISABLED', 0, 0, 'TRUNCATE', 0, NOW(), NOW()) "
                    + "ON DUPLICATE KEY UPDATE cursor_value = '500'");
            // 模拟"上次进程被 kill"：RUNNING 记录占着互斥令牌
            statement.executeUpdate("DELETE FROM sync_record WHERE id = 9450");
            statement.executeUpdate(
                "INSERT INTO sync_record (id, task_id, run_key, start_time, status, read_rows, write_rows, "
                    + "skipped_rows, error_rows, read_millis, write_millis, total_millis, trigger_type) "
                    + "VALUES (9450, 9450, 'RUNNING-9450', NOW(), 'RUNNING', 123, 120, 0, 0, 0, 0, 0, 'MANUAL')");
            // 另一个夹具：已启用 + 有 cron 的任务，用来验证"启动时重新注册调度"这条真实路径
            statement.executeUpdate(
                "INSERT INTO sync_task (id, name, source_ds_id, target_ds_id, source_table, target_table, sync_mode, "
                    + "cron_expression, page_size, batch_size, source_mode, status, safety_lag_seconds, "
                    + "lookback_seconds, full_sync_strategy, enabled, created_at, updated_at) "
                    + "VALUES (9451, '启动重注册夹具任务', 9001, 9002, 't_src_9451', 't_dst_9451', 'FULL', "
                    + "'0 0 4 * * ?', 100, 50, 'TABLE', 'ENABLED', 0, 0, 'TRUNCATE', 1, NOW(), NOW()) "
                    + "ON DUPLICATE KEY UPDATE cron_expression = '0 0 4 * * ?', status = 'ENABLED', enabled = 1");
            // D6 夹具：一条"连不上"的旧数据源（端口错误 + 明文口令）。
            // 期望：应用启动时（无需任何连通、无需跑同步）就把它升级成密文。
            statement.executeUpdate(
                "INSERT INTO datasource (id, name, db_type, host, port, database_name, username, password, "
                    + "created_at, updated_at) VALUES (9452, '连不上的旧数据源', 'MYSQL', '127.0.0.1', 13306, "
                    + "'no_such_db', 'root', 'planted_plain_9452', NOW(), NOW()) "
                    + "ON DUPLICATE KEY UPDATE password = 'planted_plain_9452'");
        }
    }

    @Test
    @DisplayName("D6：连不上的旧数据源在启动时也必须完成明文→密文升级（不依赖数据库连通性）")
    void startupUpgradesLegacyPasswordOfUnreachableDatasource() {
        String stored = jdbcTemplate.queryForObject(
            "SELECT password FROM datasource WHERE id = 9452", String.class);
        assertThat(stored).as("连不上的旧数据源同样必须升级——这是 D6 的要害")
            .startsWith("ENC(")
            .doesNotContain("planted_plain_9452");
    }

    @Test
    @DisplayName("应用启动即自愈：残留 RUNNING → FAILED、令牌释放、水位不推进")
    void startupHealsOrphanRunningRecord() throws Exception {
        try (Connection connection = testConnection();
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                 "SELECT status, run_key, error_message FROM sync_record WHERE id = 9450")) {
            assertThat(rs.next()).as("夹具记录必须存在（否则本测试无意义）").isTrue();
            assertThat(rs.getString("status")).as("启动时必须被改成 FAILED").isEqualTo("FAILED");
            assertThat(rs.getString("error_message")).contains("进程异常退出");
            assertThat(rs.getString("run_key")).as("必须释放互斥令牌").isEqualTo("9450-9450");
        }
        assertThat(cursorOf(TASK_ID)).as("自愈绝不能推进水位").isEqualTo("500");
    }

    @Test
    @DisplayName("应用启动即把已启用任务重新注册到 Quartz（JDBC JobStore，重启不丢调度）")
    void startupRegistersEnabledTasksIntoJdbcJobStore() {
        Integer jobs = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM QRTZ_JOB_DETAILS WHERE JOB_NAME = 'syncJob_9451'", Integer.class);
        assertThat(jobs).as("已启用任务必须在启动时被重新注册（否则重启后定时任务全丢）").isEqualTo(1);
        String nonConcurrent = jdbcTemplate.queryForObject(
            "SELECT IS_NONCONCURRENT FROM QRTZ_JOB_DETAILS WHERE JOB_NAME = 'syncJob_9451'", String.class);
        assertThat(nonConcurrent).as("@DisallowConcurrentExecution 必须被持久化").isEqualTo("1");
        Integer triggers = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM QRTZ_TRIGGERS WHERE TRIGGER_NAME = 'trigger_9451'", Integer.class);
        assertThat(triggers).isEqualTo(1);
        String cron = jdbcTemplate.queryForObject(
            "SELECT CRON_EXPRESSION FROM QRTZ_CRON_TRIGGERS WHERE TRIGGER_NAME = 'trigger_9451'", String.class);
        assertThat(cron).isEqualTo("0 0 4 * * ?");
    }
}
