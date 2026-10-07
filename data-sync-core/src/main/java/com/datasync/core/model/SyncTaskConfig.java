package com.datasync.core.model;

import com.datasync.core.model.enums.SyncMode;
import com.datasync.core.model.enums.SyncStatus;
import java.util.ArrayList;
import java.util.List;

/**
 * 同步任务配置（引擎的唯一输入）。
 *
 * <p>字段说明见冻结契约 §3.1。几个容易踩坑的点：
 * <ul>
 *   <li>{@code cursorValue} 是<b>上次成功</b>提交的最后一行增量列值；引擎只读它，绝不自己算
 *       {@code SELECT MAX(...)}。它由平台侧从 DB 读出后传入，null = 首次执行。</li>
 *   <li>{@code incrValue} 是历史字段（元数据表 {@code incr_value} 列），仅作兼容兜底；
 *       新代码一律读写 {@code cursorValue}。</li>
 *   <li>{@code orderColumn} 为 null 时由引擎/预检按「增量列 → 单列主键 → 多列主键 → 唯一键」推断。</li>
 * </ul>
 */
public class SyncTaskConfig {

    private Long id;
    private String name;
    private Long sourceDsId;
    private Long targetDsId;
    private String sourceTable;
    private String targetTable;
    private SyncMode syncMode;

    /** 增量字段（单调：自增数值 或 时间戳）。 */
    private String incrColumn;

    /** 上次成功水位（由平台从 DB 读出后传入，null = 首次）。 */
    private String cursorValue;

    /** 历史兼容字段，等价于旧版 incrValue；仅当 cursorValue 为 null 时被引擎读取。 */
    @Deprecated
    private String incrValue;

    /** 键集分页排序列；null 时自动推断。 */
    private String orderColumn;

    /** 单次抓取行数（内存 chunk 上限）。 */
    private Integer pageSize = 1000;

    /** JDBC 批量提交行数（&lt;= pageSize）。 */
    private Integer batchSize = 500;

    /** 字段映射；空/null = 按目标表列名同名映射。 */
    private List<FieldMapping> fieldMappings;

    /** "TABLE" | "CUSTOM_SQL"。 */
    private String sourceMode;

    /** sourceMode=CUSTOM_SQL 时使用的只读查询。 */
    private String sourceSql;

    private IncrPolicy incrPolicy = new IncrPolicy();

    private FullSyncStrategy fullSyncStrategy = FullSyncStrategy.TRUNCATE;

    private ErrorPolicy errorPolicy = new ErrorPolicy();

    /** 单次抓取的 JDBC fetchSize；真实生效值由方言决定（MySQL 流式）。 */
    private int fetchSize = 1000;

    /** 单条语句超时秒数，0 = 不限制。 */
    private Long queryTimeoutSeconds = 0L;

    /** 试运行：只读源库、跑完整链路，但不写目标、不推进水位。 */
    private boolean dryRun = false;

    /** 历史字段：cron 表达式由平台侧调度使用，引擎不使用。 */
    private String cronExpression;

    /** 历史字段：任务启停状态，引擎不使用。 */
    private SyncStatus status = SyncStatus.DISABLED;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Long getSourceDsId() {
        return sourceDsId;
    }

    public void setSourceDsId(Long sourceDsId) {
        this.sourceDsId = sourceDsId;
    }

    public Long getTargetDsId() {
        return targetDsId;
    }

    public void setTargetDsId(Long targetDsId) {
        this.targetDsId = targetDsId;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getTargetTable() {
        return targetTable;
    }

    public void setTargetTable(String targetTable) {
        this.targetTable = targetTable;
    }

    public SyncMode getSyncMode() {
        return syncMode;
    }

    public void setSyncMode(SyncMode syncMode) {
        this.syncMode = syncMode;
    }

    public String getIncrColumn() {
        return incrColumn;
    }

    public void setIncrColumn(String incrColumn) {
        this.incrColumn = incrColumn;
    }

    public String getCursorValue() {
        return cursorValue;
    }

    public void setCursorValue(String cursorValue) {
        this.cursorValue = cursorValue;
    }

    @Deprecated
    public String getIncrValue() {
        return incrValue;
    }

    @Deprecated
    public void setIncrValue(String incrValue) {
        this.incrValue = incrValue;
    }

    public String getOrderColumn() {
        return orderColumn;
    }

    public void setOrderColumn(String orderColumn) {
        this.orderColumn = orderColumn;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public Integer getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(Integer batchSize) {
        this.batchSize = batchSize;
    }

    public List<FieldMapping> getFieldMappings() {
        return fieldMappings;
    }

    public void setFieldMappings(List<FieldMapping> fieldMappings) {
        this.fieldMappings = fieldMappings;
    }

    public String getSourceMode() {
        return sourceMode;
    }

    public void setSourceMode(String sourceMode) {
        this.sourceMode = sourceMode;
    }

    public String getSourceSql() {
        return sourceSql;
    }

    public void setSourceSql(String sourceSql) {
        this.sourceSql = sourceSql;
    }

    public IncrPolicy getIncrPolicy() {
        return incrPolicy;
    }

    public void setIncrPolicy(IncrPolicy incrPolicy) {
        this.incrPolicy = incrPolicy == null ? new IncrPolicy() : incrPolicy;
    }

    public FullSyncStrategy getFullSyncStrategy() {
        return fullSyncStrategy;
    }

    public void setFullSyncStrategy(FullSyncStrategy fullSyncStrategy) {
        this.fullSyncStrategy = fullSyncStrategy;
    }

    public ErrorPolicy getErrorPolicy() {
        return errorPolicy;
    }

    public void setErrorPolicy(ErrorPolicy errorPolicy) {
        this.errorPolicy = errorPolicy == null ? new ErrorPolicy() : errorPolicy;
    }

    public int getFetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int fetchSize) {
        this.fetchSize = fetchSize;
    }

    public Long getQueryTimeoutSeconds() {
        return queryTimeoutSeconds;
    }

    public void setQueryTimeoutSeconds(Long queryTimeoutSeconds) {
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public boolean isDryRun() {
        return dryRun;
    }

    public void setDryRun(boolean dryRun) {
        this.dryRun = dryRun;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public void setCronExpression(String cronExpression) {
        this.cronExpression = cronExpression;
    }

    public SyncStatus getStatus() {
        return status;
    }

    public void setStatus(SyncStatus status) {
        this.status = status;
    }

    /** 是否自定义 SQL 数据源。 */
    public boolean isCustomSql() {
        return "CUSTOM_SQL".equalsIgnoreCase(sourceMode == null ? "" : sourceMode.trim());
    }

    /**
     * 本次执行生效的读下界原文：优先 {@code cursorValue}，为空时回落到历史字段 {@code incrValue}。
     * 回落会有风险（历史字段语义是"上次值"），调用方应尽快迁移到 cursorValue。
     */
    public String effectiveCursorValue() {
        if (cursorValue != null && !cursorValue.isBlank()) {
            return cursorValue;
        }
        if (incrValue != null && !incrValue.isBlank()) {
            return incrValue;
        }
        return null;
    }

    /** pageSize 兜底（<=0 时按 1000）。 */
    public int effectivePageSize() {
        return pageSize == null || pageSize <= 0 ? 1000 : pageSize;
    }

    /** batchSize 兜底并夹到 pageSize 以内。 */
    public int effectiveBatchSize() {
        int bs = batchSize == null || batchSize <= 0 ? 500 : batchSize;
        int ps = effectivePageSize();
        return Math.min(bs, ps);
    }

    /** 映射列表的只读副本（永不返回 null）。 */
    public List<FieldMapping> mappingsOrEmpty() {
        return fieldMappings == null ? new ArrayList<>() : fieldMappings;
    }

    @Override
    public String toString() {
        return "SyncTaskConfig{id=" + id + ", name=" + name
                + ", source=" + sourceTable + ", target=" + targetTable
                + ", mode=" + syncMode + ", incrColumn=" + incrColumn
                + ", cursorValue=" + cursorValue + ", orderColumn=" + orderColumn
                + ", pageSize=" + pageSize + ", batchSize=" + batchSize
                + ", strategy=" + fullSyncStrategy + ", dryRun=" + dryRun + "}";
    }
}
