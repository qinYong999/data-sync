package com.datasync.core.model;

/**
 * 预检问题（见冻结契约 §3.6：code 是稳定错误码，前端按码给修复建议）。
 */
public final class PreflightIssue {

    /** 级别：WARN 不阻断执行，ERROR 在写入任何数据之前终止任务。 */
    public enum Level {
        WARN,
        ERROR
    }

    private Level level;
    private String code;
    private String message;
    private String hint;

    public PreflightIssue() {
    }

    public PreflightIssue(Level level, String code, String message, String hint) {
        this.level = level;
        this.code = code;
        this.message = message;
        this.hint = hint;
    }

    public static PreflightIssue error(String code, String message, String hint) {
        return new PreflightIssue(Level.ERROR, code, message, hint);
    }

    public static PreflightIssue warn(String code, String message, String hint) {
        return new PreflightIssue(Level.WARN, code, message, hint);
    }

    public Level getLevel() {
        return level;
    }

    public void setLevel(Level level) {
        this.level = level;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getHint() {
        return hint;
    }

    public void setHint(String hint) {
        this.hint = hint;
    }

    public boolean isError() {
        return level == Level.ERROR;
    }

    @Override
    public String toString() {
        return "[" + level + "] " + code + " " + message + (hint == null ? "" : " | 建议: " + hint);
    }
}
