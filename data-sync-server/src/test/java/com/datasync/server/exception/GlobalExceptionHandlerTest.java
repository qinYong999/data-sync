package com.datasync.server.exception;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统一错误响应体（契约 §4.3）+ 不泄漏 SQL/堆栈。
 */
class GlobalExceptionHandlerTest {

    @RestController
    static class ProbeController {

        @GetMapping("/probe/business")
        void business() {
            throw AppException.badRequest("INCR_COLUMN_MISSING", "增量字段在源表不存在: incr_id");
        }

        @GetMapping("/probe/conflict")
        void conflict() {
            throw AppException.conflict("TASK_NOT_RUNNING", "任务当前未在运行，无需取消");
        }

        @GetMapping("/probe/notfound")
        void notFound() {
            throw AppException.notFound("任务不存在: 42");
        }

        @GetMapping("/probe/sql-leak")
        void sqlLeak() {
            throw new RuntimeException(
                "PreparedStatementCallback; bad SQL grammar [SELECT * FROM t_src WHERE id > ?] SQLSTATE 42S22 jdbc:mysql://127.0.0.1:3306/datasync");
        }

        @GetMapping("/probe/data-access")
        void dataAccess() {
            throw new DataAccessResourceFailureException("Communications link failure jdbc:mysql://127.0.0.1:3306/datasync");
        }

        @GetMapping("/probe/boom")
        void boom() {
            throw new IllegalStateException("内部实现细节：空指针 at com.datasync.server.Foo.bar(Foo.java:42)");
        }

        @GetMapping("/probe/bad-argument")
        void badArgument() {
            throw new IllegalArgumentException("端口号必须在 1-65535 之间");
        }

        @GetMapping("/probe/denied")
        void denied() {
            throw new AccessDeniedException("Access is denied");
        }
    }

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();

    @Test
    @DisplayName("业务异常：统一错误体，含 code 与中文 message")
    void businessExceptionBody() throws Exception {
        mockMvc.perform(get("/probe/business").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.status").value(400))
            .andExpect(jsonPath("$.code").value("INCR_COLUMN_MISSING"))
            .andExpect(jsonPath("$.message").value("增量字段在源表不存在: incr_id"))
            .andExpect(jsonPath("$.details").isArray())
            .andExpect(jsonPath("$.timestamp").exists());
    }

    @Test
    @DisplayName("409 状态被保留（取消未运行的返回码）")
    void conflictKeepsStatus() throws Exception {
        mockMvc.perform(get("/probe/conflict"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("TASK_NOT_RUNNING"));
    }

    @Test
    @DisplayName("404 状态被保留")
    void notFoundKeepsStatus() throws Exception {
        mockMvc.perform(get("/probe/notfound"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    @DisplayName("未预期的 RuntimeException（含 SQL 文本）→ 500，且 SQL/JDBC 细节绝不外泄")
    void sqlDetailsAreNotLeaked() throws Exception {
        mockMvc.perform(get("/probe/sql-leak"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("服务器内部错误，请联系管理员查看服务端日志"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("SELECT"))))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("jdbc:mysql"))));
    }

    @Test
    @DisplayName("参数非法 → 400（仅 IllegalArgumentException，避免把服务端 bug 误报成参数问题）")
    void illegalArgumentIsBadRequest() throws Exception {
        mockMvc.perform(get("/probe/bad-argument"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BAD_REQUEST"))
            .andExpect(jsonPath("$.message").value("端口号必须在 1-65535 之间"));
    }

    @Test
    @DisplayName("数据库异常统一 500，且不回显驱动细节")
    void dataAccessIsGeneric() throws Exception {
        mockMvc.perform(get("/probe/data-access"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("DATABASE_ERROR"))
            .andExpect(jsonPath("$.message").value("数据库访问失败，请联系管理员查看服务端日志"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.not(
                org.hamcrest.Matchers.containsString("jdbc:mysql"))));
    }

    @Test
    @DisplayName("未预期异常统一 500，隐藏内部细节")
    void unexpectedIsGeneric() throws Exception {
        mockMvc.perform(get("/probe/boom"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
            .andExpect(jsonPath("$.message").value("服务器内部错误，请联系管理员查看服务端日志"));
    }

    @Test
    @DisplayName("安全异常必须保持 403，不能被兜底处理器变成 500")
    void accessDeniedStays403() throws Exception {
        mockMvc.perform(get("/probe/denied"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /**
     * 元测试（变异测试思路）：故意换上一个"把异常消息原样回显"的错误处理器，
     * 断言它确实会泄漏 SQL。这证明上面那些"不得包含 SELECT/jdbc:mysql"的断言不是摆设
     * —— 一旦有人把实现改回回显消息，那几条断言必然变红。
     */
    @RestControllerAdvice
    static class LeakyAdvice {
        @org.springframework.web.bind.annotation.ExceptionHandler(RuntimeException.class)
        org.springframework.http.ResponseEntity<java.util.Map<String, Object>> leak(RuntimeException e) {
            return org.springframework.http.ResponseEntity.badRequest()
                .body(java.util.Map.of("message", String.valueOf(e.getMessage())));
        }
    }

    @Test
    @DisplayName("元测试：换成会泄漏的实现时，泄漏断言确实能捕获（断言非空转）")
    void leakAssertionIsNotVacuous() throws Exception {
        MockMvc leakyMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new LeakyAdvice())
            .build();

        String body = leakyMvc.perform(get("/probe/sql-leak"))
            .andExpect(status().isBadRequest())
            .andReturn().getResponse().getContentAsString();

        assertThat(body).as("泄漏实现必须被断言的 not(containsString(...)) 规则命中")
            .contains("SELECT")
            .contains("jdbc:mysql");
    }
}
