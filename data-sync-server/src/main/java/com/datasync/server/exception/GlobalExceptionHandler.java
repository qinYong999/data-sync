package com.datasync.server.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 全局异常处理（契约 §4.3）：所有错误都返回统一响应体
 * {@code {timestamp,status,code,message,details}}。
 *
 * <p>两条铁律：</p>
 * <ol>
 *   <li><b>不泄漏</b>：SQL、JDBC URL、堆栈、明文口令一律不出响应；</li>
 *   <li><b>不吞掉安全异常</b>：{@link AccessDeniedException} 必须显式处理成 403，
 *       否则会被兜底的 {@code Exception} 处理器变成 500，Authorization 语义就丢了。</li>
 * </ol>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Jackson 2/3 的未知字段报错文本（两种都兼容） */
    private static final Pattern UNRECOGNIZED_FIELD = Pattern.compile("Unrecognized field \"([^\"]+)\"");
    private static final Pattern UNKNOWN_PROPERTY = Pattern.compile("Unknown property '([^']+)'");

    @ExceptionHandler(AppException.class)
    public ResponseEntity<Map<String, Object>> handleApp(AppException e) {
        HttpStatus status = e.getStatus();
        if (status.is5xxServerError()) {
            log.error("业务异常（{}）：{}", e.getCode(), e.getMessage(), e);
        } else {
            log.warn("请求被拒绝（{}）：{}", e.getCode(), e.getMessage());
        }
        return ResponseEntity.status(status)
            .body(ApiErrorWriter.body(status.value(), e.getCode(), e.getMessage(), e.getDetails()));
    }

    /** 安全异常必须在兜底处理器之前被截获，否则 403/401 会退化成 500 */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<Map<String, Object>> handleAccessDenied(AccessDeniedException e) {
        log.warn("访问被拒绝：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
            .body(ApiErrorWriter.body(403, "FORBIDDEN", "没有权限执行该操作", List.of()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> handleAuthentication(AuthenticationException e) {
        log.warn("认证失败：{}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(ApiErrorWriter.body(401, "UNAUTHENTICATED", "未登录或会话已失效，请重新登录", List.of()));
    }

    /** 数据库异常：只给通用中文提示，SQL 与驱动细节进日志 */
    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<Map<String, Object>> handleDataAccess(DataAccessException e) {
        log.error("数据库访问失败", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorWriter.body(500, "DATABASE_ERROR", "数据库访问失败，请联系管理员查看服务端日志", List.of()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest().body(ApiErrorWriter.body(400, "INVALID_PARAMETER",
            "请求参数格式非法：" + e.getName(), List.of()));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> handleMissingParam(MissingServletRequestParameterException e) {
        return ResponseEntity.badRequest().body(ApiErrorWriter.body(400, "MISSING_PARAMETER",
            "缺少必需的请求参数：" + e.getParameterName(), List.of()));
    }

    /**
     * 请求体解析失败。**未知字段单独说清楚**：配置型接口里"字段名拼错被静默丢弃"
     * 会让"配了但没生效"极难自查（QA 误用 DB 列名 {@code errorPolicyJson} 正是如此）。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> handleUnreadable(HttpMessageNotReadableException e) {
        String unknownField = extractUnknownField(e);
        if (unknownField != null) {
            String message = "请求体包含未知字段：" + unknownField
                + "。请检查字段名拼写（接口字段名与数据库列名不一定相同，"
                + "例如错误处理策略请用 errorPolicy 或 errorPolicyJson）";
            log.warn("请求体包含未知字段：{}", unknownField);
            return ResponseEntity.badRequest()
                .body(ApiErrorWriter.body(400, "UNKNOWN_FIELD", message, List.of(unknownField)));
        }
        log.warn("请求体解析失败：{}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiErrorWriter.body(400, "INVALID_REQUEST_BODY",
            "请求体格式非法（JSON 解析失败），请检查字段名与类型", List.of()));
    }

    /** 从 Jackson 异常消息里提取未知字段名（不依赖具体异常类，跨 Jackson 版本都成立） */
    private static String extractUnknownField(Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            String message = cause.getMessage();
            if (message != null) {
                Matcher unrecognized = UNRECOGNIZED_FIELD.matcher(message);
                if (unrecognized.find()) {
                    return unrecognized.group(1);
                }
                Matcher unknownProperty = UNKNOWN_PROPERTY.matcher(message);
                if (unknownProperty.find()) {
                    return unknownProperty.group(1);
                }
            }
            cause = cause.getCause();
        }
        return null;
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
            .body(ApiErrorWriter.body(405, "METHOD_NOT_ALLOWED", "请求方法不被支持：" + e.getMethod(), List.of()));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiErrorWriter.body(404, "NOT_FOUND", "请求的资源不存在", List.of()));
    }

    /**
     * 参数非法（用户输入引发，例如无法解析的数字/枚举）→ 400。
     *
     * <p>注意这里**只有** {@link IllegalArgumentException}：曾经的实现把整个
     * {@link RuntimeException} 都映射成 400，结果服务器自身的 NPE/IllegalStateException
     * 会被误报成"客户端参数错误"，掩盖真实故障。业务错误请用 {@link AppException}。</p>
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException e) {
        String message = sanitize(e.getMessage());
        log.warn("参数非法：{}", message);
        return ResponseEntity.badRequest().body(ApiErrorWriter.body(400, "BAD_REQUEST", message, List.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception e) {
        log.error("未预期的服务器内部错误", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiErrorWriter.body(500, "INTERNAL_ERROR", "服务器内部错误，请联系管理员查看服务端日志", List.of()));
    }

    /** 明显的底层细节（SQL/JDBC/驱动）一律不外泄 */
    private static String sanitize(String message) {
        if (message == null || message.isBlank()) {
            return "请求处理失败";
        }
        String lower = message.toLowerCase();
        if (lower.contains("select ") || lower.contains("insert ") || lower.contains("delete from")
            || lower.contains("update ") && lower.contains(" set ") || lower.contains("jdbc:")
            || lower.contains("com.mysql") || lower.contains("sqlexception") || lower.contains("syntax error")
            || lower.contains("sqlstate")) {
            return "请求处理失败：底层数据访问出错，请查看服务端日志";
        }
        return message;
    }
}
