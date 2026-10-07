package com.datasync.server.exception;

import org.springframework.http.HttpStatus;

import java.util.List;

/**
 * 业务异常：携带稳定的错误码与 HTTP 状态，由 {@code GlobalExceptionHandler} 统一转成
 * 契约 §4.3 的错误响应体 {@code {timestamp,status,code,message,details}}。
 *
 * <p>约定：message 一律是面向运维的中文，不含 SQL、堆栈、口令等敏感内容。</p>
 */
public class AppException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> details;

    public AppException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of());
    }

    public AppException(HttpStatus status, String code, String message, List<String> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public List<String> getDetails() { return details; }

    /** 404：资源不存在 */
    public static AppException notFound(String message) {
        return new AppException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /** 400：参数或配置非法 */
    public static AppException badRequest(String code, String message) {
        return new AppException(HttpStatus.BAD_REQUEST, code, message);
    }

    /** 400：参数或配置非法（带明细） */
    public static AppException badRequest(String code, String message, List<String> details) {
        return new AppException(HttpStatus.BAD_REQUEST, code, message, details);
    }

    /** 409：状态冲突（如任务已在运行、调度表达式冲突） */
    public static AppException conflict(String code, String message) {
        return new AppException(HttpStatus.CONFLICT, code, message);
    }

    /** 503：系统繁忙（线程池已满等），可重试 */
    public static AppException unavailable(String code, String message) {
        return new AppException(HttpStatus.SERVICE_UNAVAILABLE, code, message);
    }
}
