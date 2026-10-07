package com.datasync.core.model;

/**
 * 一条坏行/失败明细（落 sync_error 表）。
 */
public final class SyncError {

    /** 发生阶段：PREFLIGHT / READ / MAP / WRITE。 */
    private String phase;

    /** 主键值（尽力而为，可能为空）。 */
    private String rowKey;

    /** 中文错误消息。 */
    private String message;

    /** 源行 JSON，截断到 4000 字符。 */
    private String rowData;

    /** 是否可重试（false = 致命）。 */
    private boolean retryable;

    /** rowData 的最大长度。 */
    public static final int MAX_ROW_DATA_LENGTH = 4000;

    public SyncError() {
    }

    public SyncError(String phase, String rowKey, String message, String rowData, boolean retryable) {
        this.phase = phase;
        this.rowKey = rowKey;
        this.message = message;
        this.rowData = truncate(rowData);
        this.retryable = retryable;
    }

    /** 按契约把行数据截断到 4000 字符。 */
    public static String truncate(String rowData) {
        if (rowData == null || rowData.length() <= MAX_ROW_DATA_LENGTH) {
            return rowData;
        }
        return rowData.substring(0, MAX_ROW_DATA_LENGTH);
    }

    public String getPhase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public String getRowKey() {
        return rowKey;
    }

    public void setRowKey(String rowKey) {
        this.rowKey = rowKey;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getRowData() {
        return rowData;
    }

    public void setRowData(String rowData) {
        this.rowData = truncate(rowData);
    }

    public boolean isRetryable() {
        return retryable;
    }

    public void setRetryable(boolean retryable) {
        this.retryable = retryable;
    }

    @Override
    public String toString() {
        return "SyncError{phase=" + phase + ", rowKey=" + rowKey + ", retryable=" + retryable
                + ", message=" + message + "}";
    }
}
