package com.datasync.core.model;

/**
 * 错误处理策略（见冻结契约 §1 D8）。
 *
 * <p>重试以「chunk」为单位：一个 chunk 失败后按 {@code retryBackoffMs} 指数退避重做该 chunk；
 * 重试用尽后若 {@code skipBadRows=true}，则逐行隔离出坏行（落 sync_error），
 * 坏行累计数超过 {@code maxSkipRows} 时整个任务失败（水位不推进）。
 */
public class ErrorPolicy {

    /** 单个 chunk 的重试次数（不含首次执行）。 */
    private int maxRetries = 3;

    /** 重试基础退避毫秒数，第 n 次退避 = retryBackoffMs * 2^(n-1)。 */
    private long retryBackoffMs = 2000L;

    /**
     * true = 坏行跳过并落 sync_error；false = 快速失败。
     *
     * <p><b>作用范围仅限"行级"失败</b>：MAP（值转换）与 WRITE（写目标）阶段能定位到具体行，
     * 因此可以跳过并记录。READ 阶段（分页 SELECT、结果集遍历，例如源库里的零日期
     * {@code Zero date value prohibited}）**不适用**：整批读取失败时无法判断"跳到哪一行"才安全，
     * 一律判定任务失败且水位不推进。运维不要把 {@code skipBadRows=true} 理解成
     * "源库里的脏数据都能容忍"。
     */
    private boolean skipBadRows = false;

    /** 允许跳过的坏行总数上限，超过则整体失败。 */
    private long maxSkipRows = 100L;

    /** 落库的坏行明细上限。 */
    private int maxErrorsRecorded = 200;

    public ErrorPolicy() {
    }

    public ErrorPolicy(boolean skipBadRows, long maxSkipRows, int maxRetries) {
        this.skipBadRows = skipBadRows;
        this.maxSkipRows = maxSkipRows;
        this.maxRetries = maxRetries;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = Math.max(0, maxRetries);
    }

    public long getRetryBackoffMs() {
        return retryBackoffMs;
    }

    public void setRetryBackoffMs(long retryBackoffMs) {
        this.retryBackoffMs = Math.max(0L, retryBackoffMs);
    }

    public boolean isSkipBadRows() {
        return skipBadRows;
    }

    public void setSkipBadRows(boolean skipBadRows) {
        this.skipBadRows = skipBadRows;
    }

    public long getMaxSkipRows() {
        return maxSkipRows;
    }

    public void setMaxSkipRows(long maxSkipRows) {
        this.maxSkipRows = Math.max(0L, maxSkipRows);
    }

    public int getMaxErrorsRecorded() {
        return maxErrorsRecorded;
    }

    public void setMaxErrorsRecorded(int maxErrorsRecorded) {
        this.maxErrorsRecorded = Math.max(0, maxErrorsRecorded);
    }

    @Override
    public String toString() {
        return "ErrorPolicy{maxRetries=" + maxRetries + ", backoffMs=" + retryBackoffMs
                + ", skipBadRows=" + skipBadRows + ", maxSkipRows=" + maxSkipRows + "}";
    }
}
