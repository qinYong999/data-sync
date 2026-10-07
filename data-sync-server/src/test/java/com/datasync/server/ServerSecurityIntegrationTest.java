package com.datasync.server;

import com.datasync.server.service.TaskRunService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 安全（D10）+ 新端点（契约 §4.3）+ Quartz JDBC JobStore 的集成验证。
 *
 * <p>Lead 提醒过的坑：CSRF 断言与 formLogin 断言分开写；重定向用 {@code redirectedUrl}。</p>
 */
class ServerSecurityIntegrationTest extends AbstractServerIntegrationTest {

    private static final Pattern TOKEN = Pattern.compile("\"token\"\\s*:\\s*\"([^\"]+)\"");

    @Autowired
    private TaskRunService taskRunService;

    @Autowired
    private org.quartz.Scheduler scheduler;

    private static String extractToken(String json) {
        Matcher matcher = TOKEN.matcher(json == null ? "" : json);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String csrfToken(MockHttpSession session) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/csrf").session(session))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.headerName").value("X-CSRF-TOKEN"))
            .andReturn();
        String token = extractToken(result.getResponse().getContentAsString());
        assertThat(token).as("CSRF token 必须下发").isNotBlank();
        return token;
    }

    private MockHttpSession loggedInSession() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);
        mockMvc.perform(post("/api/auth/login")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"test-admin-123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(true))
            .andExpect(jsonPath("$.username").value("admin"));
        return session;
    }

    @Test
    @DisplayName("§5.9 未登录访问 /api/** → 401 JSON（不是 302）")
    void unauthenticatedApiReturns401Json() throws Exception {
        mockMvc.perform(get("/api/tasks").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
            .andExpect(jsonPath("$.message").value("未登录或会话已失效，请重新登录"));
    }

    @Test
    @DisplayName("会话过期与未登录行为一致（401）")
    void expiredSessionBehavesLikeAnonymous() throws Exception {
        MockHttpSession stale = new MockHttpSession();
        stale.invalidate();
        mockMvc.perform(get("/api/tasks").session(stale).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    @DisplayName("非 /api 的浏览器导航仍 302 到登录页（§5.9 的另一半口径）")
    void browserNavigationRedirectsToLogin() throws Exception {
        mockMvc.perform(get("/ws/logs"))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrl("/login"));
    }

    @Test
    @DisplayName("/actuator/health 匿名可读")
    void actuatorHealthIsAnonymous() throws Exception {
        mockMvc.perform(get("/actuator/health"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("/api/auth/csrf 与 /api/auth/session 匿名可达")
    void authBootstrapEndpointsAreAnonymous() throws Exception {
        mockMvc.perform(get("/api/auth/session"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authenticated").value(false))
            .andExpect(jsonPath("$.securityEnabled").value(true));
    }

    @Test
    @DisplayName("JSON 登录成功后即可访问 /api/**")
    void jsonLoginThenAccessApi() throws Exception {
        MockHttpSession session = loggedInSession();

        mockMvc.perform(get("/api/tasks").session(session))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("口令错误 → 401 BAD_CREDENTIALS（中文提示）")
    void wrongPasswordIsRejected() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String token = csrfToken(session);

        mockMvc.perform(post("/api/auth/login")
                .session(session)
                .header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"))
            .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    @DisplayName("缺少 CSRF 头的写请求被拒（403 CSRF_INVALID）")
    void postWithoutCsrfIsRejected() throws Exception {
        MockHttpSession session = loggedInSession();

        mockMvc.perform(post("/api/tasks/9001/cancel").session(session))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("CSRF_INVALID"));
    }

    @Test
    @DisplayName("带上 CSRF 头后请求真正到达控制器（任务不存在 → 404 业务错误）")
    void postWithCsrfReachesController() throws Exception {
        MockHttpSession session = loggedInSession();
        String token = csrfToken(session);

        mockMvc.perform(post("/api/tasks/9001/cancel").session(session).header("X-CSRF-TOKEN", token))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("GET /api/system/info 字段齐全，含 dm8Verified=false 与 poolSummary 数组")
    void systemInfoFields() throws Exception {
        mockMvc.perform(get("/api/system/info").session(loggedInSession()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").exists())
            .andExpect(jsonPath("$.dbType").value(org.hamcrest.Matchers.containsString("MySQL")))
            .andExpect(jsonPath("$.dm8Verified").value(false))
            .andExpect(jsonPath("$.activeTasks").isNumber())
            .andExpect(jsonPath("$.runningTasks").isNumber())
            .andExpect(jsonPath("$.poolSummary").isArray())
            .andExpect(jsonPath("$.securityEnabled").value(true))
            .andExpect(jsonPath("$.csrfToken").isNotEmpty());
    }

    @Test
    @DisplayName("GET /api/tasks/{id}/records 为 Spring Data Page 形状")
    void recordsEndpointReturnsPage() throws Exception {
        createCommonDatasources();
        createTask(9001L, "分页任务", "t_src", "t_dst", "FULL", null, null);
        jdbcTemplate.update(
            "INSERT INTO sync_record (task_id, run_key, start_time, end_time, status, read_rows, write_rows, "
                + "skipped_rows, error_rows, read_millis, write_millis, total_millis, trigger_type) "
                + "VALUES (9001, '9001-1', NOW(), NOW(), 'COMPLETED', 10, 10, 2, 0, 5, 6, 11, 'MANUAL')");

        mockMvc.perform(get("/api/tasks/9001/records?page=0&size=10").session(loggedInSession()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content").isArray())
            .andExpect(jsonPath("$.content[0].status").value("COMPLETED"))
            .andExpect(jsonPath("$.content[0].skippedRows").value(2))
            .andExpect(jsonPath("$.content[0].totalMillis").value(11))
            .andExpect(jsonPath("$.totalElements").value(1))
            .andExpect(jsonPath("$.number").value(0));
    }

    @Test
    @DisplayName("GET /api/records/{id}/errors 为 Page 形状且支持 phase 过滤")
    void errorsEndpointReturnsPage() throws Exception {
        createCommonDatasources();
        createTask(9001L, "坏行任务", "t_src", "t_dst", "FULL", null, null);
        jdbcTemplate.update(
            "INSERT INTO sync_record (id, task_id, run_key, start_time, end_time, status, skipped_rows, error_rows, "
                + "read_millis, write_millis, total_millis, trigger_type) "
                + "VALUES (9002, 9001, '9001-9002', NOW(), NOW(), 'COMPLETED', 1, 1, 1, 1, 2, 'MANUAL')");
        jdbcTemplate.update(
            "INSERT INTO sync_error (record_id, task_id, phase, row_key, message, row_data, retryable, created_at) "
                + "VALUES (9002, 9001, 'WRITE', '42', '字段超长', '{\"id\":42}', 0, NOW())");

        mockMvc.perform(get("/api/records/9002/errors").session(loggedInSession()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.content[0].phase").value("WRITE"))
            .andExpect(jsonPath("$.content[0].rowKey").value("42"))
            .andExpect(jsonPath("$.totalElements").value(1));

        mockMvc.perform(get("/api/records/9002/errors?phase=READ").session(loggedInSession()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @DisplayName("§5.6 预检在写数据之前失败：目标表不存在 → DST_TABLE_MISSING（真引擎预检）")
    void preflightReportsMissingTargetTable() throws Exception {
        createCommonDatasources();
        ensureTable("t_src_9001");
        createTask(9001L, "预检任务", "t_src_9001", "no_such_table_xyz_9001", "FULL", null, null);

        mockMvc.perform(get("/api/tasks/9001/preflight").session(loggedInSession()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasError").value(true))
            .andExpect(jsonPath("$.issues[*].code",
                org.hamcrest.Matchers.hasItem("DST_TABLE_MISSING")));
    }

    @Test
    @DisplayName("未运行的任务调用 cancel → 409 TASK_NOT_RUNNING")
    void cancelWhenNotRunningReturns409() throws Exception {
        createCommonDatasources();
        createTask(9001L, "取消任务", "t_src", "t_dst", "FULL", null, null);
        MockHttpSession session = loggedInSession();
        String token = csrfToken(session);

        mockMvc.perform(post("/api/tasks/9001/cancel").session(session).header("X-CSRF-TOKEN", token))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TASK_NOT_RUNNING"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("当前未在运行")));
        assertThat(taskRunService.isRunning(9001L)).isFalse();
    }

    private String passwordOf(long datasourceId) {
        return jdbcTemplate.queryForObject(
            "SELECT password FROM datasource WHERE id = ?", String.class, datasourceId);
    }

    @Test
    @DisplayName("D6：GET 读取路径即把历史明文口令升级为密文（不需要连通、不需要跑同步）")
    void readPathUpgradesLegacyPlaintextPassword() throws Exception {
        jdbcTemplate.update(
            "INSERT INTO datasource (id, name, db_type, host, port, database_name, username, password, "
                + "created_at, updated_at) VALUES (9003, '历史明文数据源', 'MYSQL', '127.0.0.1', 3306, "
                + "'datasync_test_platform', 'root', 'planted_plaintext_pwd', NOW(), NOW()) "
                + "ON DUPLICATE KEY UPDATE password = VALUES(password)");
        assertThat(passwordOf(9003L)).as("前置：该行确实是明文").isEqualTo("planted_plaintext_pwd");

        mockMvc.perform(get("/api/datasources/9003").session(loggedInSession()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.password").value(""));

        assertThat(passwordOf(9003L)).as("读取路径必须就地升级为密文")
            .startsWith("ENC(")
            .doesNotContain("planted_plaintext_pwd");
    }

    @Test
    @DisplayName("D2：请求体里的未知字段必须 400 并指名道姓（不再静默忽略）")
    void unknownFieldIsRejectedWithClearMessage() throws Exception {
        createCommonDatasources();
        MockHttpSession session = loggedInSession();
        String token = csrfToken(session);
        String body = "{\"name\":\"未知字段任务\",\"sourceDsId\":9001,\"targetDsId\":9002,"
            + "\"sourceTable\":\"t_src\",\"targetTable\":\"t_dst\",\"syncMode\":\"FULL\","
            + "\"errorPolicyy\":{\"skipBadRows\":true}}";

        mockMvc.perform(post("/api/tasks").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("UNKNOWN_FIELD"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("errorPolicyy")));
    }

    @Test
    @DisplayName("D2：DB 列名风格别名 errorPolicyJson 被显式接受，并真正落到 error_policy_json")
    void errorPolicyJsonAliasIsApplied() throws Exception {
        createCommonDatasources();
        MockHttpSession session = loggedInSession();
        String token = csrfToken(session);
        String body = "{\"name\":\"别名任务\",\"sourceDsId\":9001,\"targetDsId\":9002,"
            + "\"sourceTable\":\"t_src\",\"targetTable\":\"t_dst\",\"syncMode\":\"FULL\","
            + "\"errorPolicyJson\":\"{\\\"skipBadRows\\\":true,\\\"maxSkipRows\\\":5}\"}";

        String response = mockMvc.perform(post("/api/tasks").session(session).header("X-CSRF-TOKEN", token)
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();

        Matcher idMatcher = Pattern.compile("\"id\"\\s*:\\s*(\\d+)").matcher(response);
        assertThat(idMatcher.find()).as("创建响应里应包含任务 id").isTrue();
        long createdId = Long.parseLong(idMatcher.group(1));
        try {
            String stored = jdbcTemplate.queryForObject(
                "SELECT error_policy_json FROM sync_task WHERE id = ?", String.class, createdId);
            assertThat(stored).as("别名必须真正生效，而不是被静默丢弃").contains("skipBadRows").contains("true");
        } finally {
            jdbcTemplate.update("DELETE FROM sync_task WHERE id = ?", createdId);
        }
    }

    @Test
    @DisplayName("Quartz 使用 JDBC JobStore：启用任务后 QRTZ_ 表里出现持久化 Job，且标记非并发")
    void quartzUsesJdbcJobStore() throws Exception {
        createCommonDatasources();
        createTask(9001L, "定时任务", "t_src", "t_dst", "FULL", null, null);

        taskService.updateSchedule(9001L, "0 0 3 * * ?");
        taskService.enableTask(9001L);

        Integer jobs = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM QRTZ_JOB_DETAILS WHERE JOB_NAME = 'syncJob_9001'", Integer.class);
        assertThat(jobs).as("启用的任务必须落入 QRTZ_JOB_DETAILS（JDBC JobStore）").isEqualTo(1);
        String nonConcurrent = jdbcTemplate.queryForObject(
            "SELECT IS_NONCONCURRENT FROM QRTZ_JOB_DETAILS WHERE JOB_NAME = 'syncJob_9001'", String.class);
        assertThat(nonConcurrent).as("@DisallowConcurrentExecution 必须持久化到 Quartz").isEqualTo("1");
        Integer triggers = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM QRTZ_TRIGGERS WHERE TRIGGER_NAME = 'trigger_9001'", Integer.class);
        assertThat(triggers).isEqualTo(1);

        // 停用后必须清除，避免"停用了还在跑"
        taskService.disableTask(9001L);
        Integer afterDisable = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM QRTZ_JOB_DETAILS WHERE JOB_NAME = 'syncJob_9001'", Integer.class);
        assertThat(afterDisable).isZero();
    }

    @Test
    @DisplayName("生产配置的 JDBC JobStore 真的在用 QRTZ 表（不是 RAMJobStore，V3 没有白建）")
    void quartzJdbcStoreIsActuallyUsed() throws Exception {
        // 直接问调度器自己：JobStore 的实现类就是"生产配置是否生效"的硬证据
        String jobStoreClass = scheduler.getMetaData().getJobStoreClass().getName();
        assertThat(jobStoreClass).as("必须是 JDBC JobStore 实现（生产配置生效）").contains("JobStore");
        assertThat(jobStoreClass).as("绝不能退化成内存存储").doesNotContain("RAMJobStore");
        assertThat(scheduler.getSchedulerName()).isEqualTo("DataSyncScheduler");

        // V3 建出的 QRTZ_ 表必须真实存在（qa 在库里能看到调度痕迹）
        Integer tables = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME LIKE 'QRTZ\\_%'",
            Integer.class);
        assertThat(tables).as("V3 应建出 11 张 QRTZ_ 表").isGreaterThanOrEqualTo(11);

        // 注意：这里刻意**不**断言 QRTZ_LOCKS / QRTZ_SCHEDULER_STATE 有数据 ——
        //   · QRTZ_LOCKS 的行是官方脚本里的种子数据（V3 未内联 INSERT），且 StdJDBCDelegate 不做行锁；
        //   · QRTZ_SCHEDULER_STATE 只在 isClustered=true 时才写心跳，本项目为单实例。
        // 用它们做断言会把"配置正确"误判成失败。
    }
}
