package com.datasync.core.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次同步执行的结果。
 *
 * <p><b>水位推进口径（关键）</b>：只有 {@code success == true} 且 {@code endCursor != null} 时，
 * 调用方才允许把 {@code endCursor} 写回任务水位。任何失败/取消/试运行，
 * {@code endCursor} 一律为 null —— 宁可下次重复读（幂等 upsert 保证不重），也绝不丢数。
 */
public final class SyncRunResult {

    /** 任务状态常量。 */
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    private boolean success;
    private String status;
    private long readRows;
    private long writtenRows;
    private long skippedRows;
    private long readMillis;
    private long writeMillis;
    private long totalMillis;
    /** 本次生效的读下界（字符串形式，可空）。 */
    private String startCursor;
    /** 成功时的新水位（可空；!= null 才允许推进 incrValue）。 */
    private String endCursor;
    /** 最后提交行的 order key，用于断点续跑（可空）。 */
    private String lastCommittedKey;
    private String errorMessage;
    private List<SyncError> errors = new ArrayList<>();
    private List<PreflightIssue> preflightIssues = new ArrayList<>();

    public SyncRunResult() {
    }

    /** 构造一个失败结果（不含任何已提交数据、endCursor 恒为 null）。 */
    public static SyncRunResult failure(String status, String errorMessage,
                                        List<PreflightIssue> preflightIssues) {
        SyncRunResult r = new SyncRunResult();
        r.success = false;
        r.status = status;
        r.errorMessage = errorMessage;
        if (preflightIssues != null) {
            r.preflightIssues = new ArrayList<>(preflightIssues);
        }
        return r;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public long getReadRows() {
        return readRows;
    }

    public void setReadRows(long readRows) {
        this.readRows = readRows;
    }

    public long getWrittenRows() {
        return writtenRows;
    }

    public void setWrittenRows(long writtenRows) {
        this.writtenRows = writtenRows;
    }

    public long getSkippedRows() {
        return skippedRows;
    }

    public void setSkippedRows(long skippedRows) {
        this.skippedRows = skippedRows;
    }

    public long getReadMillis() {
        return readMillis;
    }

    public void setReadMillis(long readMillis) {
        this.readMillis = readMillis;
    }

    public long getWriteMillis() {
        return writeMillis;
    }

    public void setWriteMillis(long writeMillis) {
        this.writeMillis = writeMillis;
    }

    public long getTotalMillis() {
        return totalMillis;
    }

    public void setTotalMillis(long totalMillis) {
        this.totalMillis = totalMillis;
    }

    public String getStartCursor() {
        return startCursor;
    }

    public void setStartCursor(String startCursor) {
        this.startCursor = startCursor;
    }

    public String getEndCursor() {
        return endCursor;
    }

    public void setEndCursor(String endCursor) {
        this.endCursor = endCursor;
    }

    public String getLastCommittedKey() {
        return lastCommittedKey;
    }

    public void setLastCommittedKey(String lastCommittedKey) {
        this.lastCommittedKey = lastCommittedKey;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public List<SyncError> getErrors() {
        return errors;
    }

    public void setErrors(List<SyncError> errors) {
        this.errors = errors == null ? new ArrayList<>() : errors;
    }

    public List<PreflightIssue> getPreflightIssues() {
        return preflightIssues;
    }

    public void setPreflightIssues(List<PreflightIssue> preflightIssues) {
        this.preflightIssues = preflightIssues == null ? new ArrayList<>() : preflightIssues;
    }

    @Override
    public String toString() {
        return "SyncRunResult{success=" + success + ", status=" + status
                + ", read=" + readRows + ", written=" + writtenRows + ", skipped=" + skippedRows
                + ", startCursor=" + startCursor + ", endCursor=" + endCursor
                + ", lastCommittedKey=" + lastCommittedKey
                + ", totalMillis=" + totalMillis
                + ", errorMessage=" + errorMessage + "}";
    }
}
