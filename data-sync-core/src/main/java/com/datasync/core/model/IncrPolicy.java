package com.datasync.core.model;

/**
 * 增量水位策略。
 *
 * <p>读范围语义（见冻结契约 §1 D3）：
 * <pre>
 *   lower = 上次水位 - lookbackSeconds     （时间戳列、数值列统一按此规则向下回看）
 *   upper = 执行锚点 - safetyLagSeconds    （执行锚点必须在开始读取时取一次并固定）
 *   读取条件： cursor &gt; lower AND cursor &lt;= upper
 * </pre>
 *
 * <p>其中 {@code timestampColumn} 由预检根据源表增量列的真实类型回填，
 * 调用方不需要（也不应该）自己猜。
 */
public class IncrPolicy {

    /** 安全滞后秒数：上界 = 执行锚点 - safetyLag；数值型自增列默认 0。 */
    private long safetyLagSeconds = 0L;

    /** 回看窗口秒数：下界 = 水位 - lookback；时间戳列建议 ≥ 60。 */
    private long lookbackSeconds = 0L;

    /** 增量列是否为时间类型（由预检结果回填）。 */
    private boolean timestampColumn = false;

    public IncrPolicy() {
    }

    public IncrPolicy(long safetyLagSeconds, long lookbackSeconds) {
        this.safetyLagSeconds = safetyLagSeconds;
        this.lookbackSeconds = lookbackSeconds;
    }

    public long getSafetyLagSeconds() {
        return safetyLagSeconds;
    }

    public void setSafetyLagSeconds(long safetyLagSeconds) {
        this.safetyLagSeconds = safetyLagSeconds;
    }

    public long getLookbackSeconds() {
        return lookbackSeconds;
    }

    public void setLookbackSeconds(long lookbackSeconds) {
        this.lookbackSeconds = lookbackSeconds;
    }

    public boolean isTimestampColumn() {
        return timestampColumn;
    }

    public void setTimestampColumn(boolean timestampColumn) {
        this.timestampColumn = timestampColumn;
    }

    @Override
    public String toString() {
        return "IncrPolicy{safetyLag=" + safetyLagSeconds + "s, lookback=" + lookbackSeconds
                + "s, timestampColumn=" + timestampColumn + "}";
    }
}
