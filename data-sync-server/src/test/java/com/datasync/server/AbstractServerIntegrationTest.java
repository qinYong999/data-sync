package com.datasync.server;

import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.repository.SyncErrorRepository;
import com.datasync.server.repository.SyncRecordRepository;
import com.datasync.server.repository.SyncTaskRepository;
import com.datasync.server.security.CredentialCipher;
import com.datasync.server.service.SyncTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;

/**
 * server 模块集成测试基类（真机 MySQL + 真 Flyway 迁移 + 真 Quartz JDBC JobStore）。
 *
 * <p>用独立测试库 {@code datasync_test_platform}，绝不动生产库 {@code datasync}。
 * 元数据表结构由 Lead 的 Flyway 迁移建立，Hibernate 以 {@code ddl-auto=validate}
 * 校验实体与迁移是否一致——这条本身就是对实体/DDL 对齐的自动化检查。</p>
 *
 * <h3>测试隔离约定（每个测试类用互不重叠的 ID 段，避免跨类互相污染）</h3>
 * <ul>
 *   <li>9000~9099：数据源（9001 源 / 9002 目标）</li>
 *   <li>9100~9199：RunRecordStore 相关任务</li>
 *   <li>9200~9299：TaskRunService（并发/取消）相关任务</li>
 *   <li>9400~9499：启动自愈夹具（故意<b>不</b>清理，必须活到上下文启动之后）</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "spring.datasource.url=jdbc:mysql://127.0.0.1:3306/datasync_test_platform?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8&rewriteBatchedStatements=true",
    "spring.datasource.username=root",
    "spring.datasource.password=123456",
    "spring.flyway.enabled=true",
    "spring.jpa.hibernate.ddl-auto=validate",
    // 生产用 JDBC JobStore（表由 Flyway V3 建）。这里**刻意不做任何 jobStore 覆盖**：
    // 走的就是 application.yml 的真实配置，才能验证生产路径。
    // （曾经的缺陷：yml 里手写 org.quartz.jobStore.class=JobStoreTX → Boot 注入的 DataSource
    //   无法生效 → 启动 100% 失败 "DataSource name not set."。用 memory 覆盖测试正好会掩盖它。）
    "spring.quartz.job-store-type=jdbc",
    "spring.quartz.jdbc.initialize-schema=never",
    "app.security.enabled=true",
    "app.security.username=admin",
    "app.security.password=test-admin-123",
    "app.security.secret-key=0123456789abcdef0123456789abcdef",
    "app.sync.worker-threads=2",
    "app.retention.enabled=false"
})
public abstract class AbstractServerIntegrationTest {

    /** 测试库连接串（静态夹具在上下文启动前就要用它） */
    protected static final String TEST_DB_URL =
        "jdbc:mysql://127.0.0.1:3306/datasync_test_platform?useSSL=false&allowPublicKeyRetrieval=true"
            + "&serverTimezone=Asia/Shanghai&characterEncoding=utf8";
    protected static final String TEST_DB_USER = "root";
    protected static final String TEST_DB_PASSWORD = "123456";

    /** 各测试类使用的数据源 ID（公用的两个） */
    protected static final long DS_SOURCE_ID = 9001L;
    protected static final long DS_TARGET_ID = 9002L;

    @Autowired protected MockMvc mockMvc;
    @Autowired protected DataSource metadataDataSource;
    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected ObjectMapper objectMapper;
    @Autowired protected SyncTaskRepository taskRepository;
    @Autowired protected SyncRecordRepository recordRepository;
    @Autowired protected SyncErrorRepository errorRepository;
    @Autowired protected SyncTaskService taskService;
    @Autowired protected CredentialCipher credentialCipher;

    /** 上下文启动前的静态夹具用连接 */
    protected static Connection testConnection() throws Exception {
        return DriverManager.getConnection(TEST_DB_URL, TEST_DB_USER, TEST_DB_PASSWORD);
    }

    @BeforeEach
    void cleanTestData() {
        // 只清理"每个测试自己造"的 ID 段，绝不碰 9400+ 的启动自愈夹具
        jdbcTemplate.update("DELETE FROM sync_error WHERE task_id BETWEEN 9000 AND 9399");
        jdbcTemplate.update("DELETE FROM sync_record WHERE task_id BETWEEN 9000 AND 9399");
        jdbcTemplate.update("DELETE FROM sync_task WHERE id BETWEEN 9000 AND 9399");
        jdbcTemplate.update("DELETE FROM datasource WHERE id BETWEEN 9000 AND 9099");
    }

    /** 造一个真实存在的数据源记录（指向本机 MySQL 测试库），口令用真实 AES-GCM 密文 */
    protected void createDatasource(long id, String name) {
        jdbcTemplate.update(
            "INSERT INTO datasource (id, name, db_type, host, port, database_name, username, password, created_at, updated_at) "
                + "VALUES (?, ?, 'MYSQL', '127.0.0.1', 3306, 'datasync_test_platform', 'root', ?, NOW(), NOW()) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), password = VALUES(password)",
            id, name, credentialCipher.encrypt("123456"));
    }

    /** 两个公用数据源（9001 源 / 9002 目标） */
    protected void createCommonDatasources() {
        createDatasource(DS_SOURCE_ID, "集成测试源");
        createDatasource(DS_TARGET_ID, "集成测试目标");
    }

    /** 造一个任务（默认停用），返回实体 */
    protected SyncTaskEntity createTask(long id, String name, String sourceTable, String targetTable,
                                        String syncMode, String incrColumn, String cursorValue) {
        jdbcTemplate.update(
            "INSERT INTO sync_task (id, name, source_ds_id, target_ds_id, source_table, target_table, sync_mode, "
                + "incr_column, cursor_value, cron_expression, page_size, batch_size, mapping_json, source_mode, "
                + "source_sql, status, safety_lag_seconds, lookback_seconds, full_sync_strategy, enabled, "
                + "created_at, updated_at) "
                + "VALUES (?, ?, 9001, 9002, ?, ?, ?, ?, ?, NULL, 100, 50, NULL, 'TABLE', NULL, 'DISABLED', 0, 0, "
                + "'TRUNCATE', 0, NOW(), NOW()) "
                + "ON DUPLICATE KEY UPDATE name = VALUES(name), cursor_value = VALUES(cursor_value)",
            id, name, sourceTable, targetTable, syncMode, incrColumn, cursorValue);
        return taskRepository.findById(id).orElseThrow();
    }

    protected static void ensureTable(String table) {
        try (Connection connection = testConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS " + table + " (id BIGINT PRIMARY KEY, name VARCHAR(50))");
        } catch (Exception e) {
            throw new IllegalStateException("准备测试表失败: " + table, e);
        }
    }

    protected String cursorOf(long taskId) {
        List<String> values = jdbcTemplate.queryForList(
            "SELECT cursor_value FROM sync_task WHERE id = ?", String.class, taskId);
        return values.isEmpty() ? null : values.get(0);
    }

    protected String recordStatus(long recordId) {
        return jdbcTemplate.queryForObject("SELECT status FROM sync_record WHERE id = ?", String.class, recordId);
    }

    protected String runKeyOf(long recordId) {
        return jdbcTemplate.queryForObject("SELECT run_key FROM sync_record WHERE id = ?", String.class, recordId);
    }
}
