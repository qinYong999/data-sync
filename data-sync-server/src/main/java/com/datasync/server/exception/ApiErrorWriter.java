package com.datasync.server.exception;

import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一错误响应体（契约 §4.3）：
 * <pre>{"timestamp":"...","status":400,"code":"INCR_COLUMN_MISSING","message":"中文消息","details":["..."]}</pre>
 *
 * <p>过滤器链里的认证/授权失败也要用同一个形状，否则前端要写两套解析。</p>
 *
 * <p>注意：Spring Boot 4 使用 Jackson 3，包名是 {@code tools.jackson.*}（不是 {@code com.fasterxml.jackson.databind}）。</p>
 */
public final class ApiErrorWriter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ApiErrorWriter() {
    }

    public static Map<String, Object> body(int status, String code, String message, List<String> details) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("status", status);
        body.put("code", code);
        body.put("message", message);
        body.put("details", details == null ? List.of() : details);
        return body;
    }

    public static void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json;charset=UTF-8");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        MAPPER.writeValue(response.getWriter(), body(status, code, message, List.of()));
    }
}
