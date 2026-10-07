package com.datasync.server.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/**
 * 同步任务配置表（契约 §4.2）。
 *
 * <p>列名与类型以 Flyway 迁移（{@code db/migration/V1__baseline_schema.sql}）为唯一事实源，
 * Hibernate 侧 {@code ddl-auto=validate} 只做校验：NOT NULL 列用原始类型，可空列用包装类型。</p>
 */
@Entity
@Table(name = "sync_task")
@org.hibernate.annotations.Comment("同步任务配置表")
public class SyncTaskEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("任务ID")
    private Long id;

    @Column(nullable = false, length = 200)
    @Comment("任务名称")
    private String name;

    @Column(name = "source_ds_id", nullable = false)
    @Comment("源数据源ID")
    private Long sourceDsId;

    @Column(name = "target_ds_id", nullable = false)
    @Comment("目标数据源ID")
    private Long targetDsId;

    @Column(name = "source_table", nullable = false, length = 200)
    @Comment("源表名")
    private String sourceTable;

    @Column(name = "target_table", nullable = false, length = 200)
    @Comment("目标表名")
    private String targetTable;

    @Column(name = "sync_mode", nullable = false, length = 20)
    @Comment("同步模式：FULL / INCR / FULL_INCR")
    private String syncMode;

    @Column(name = "incr_column", length = 100)
    @Comment("增量字段（自增数值列或时间戳列）")
    private String incrColumn;

    @Column(name = "incr_value", length = 255)
    @Comment("增量起始值（历史字段，保留兼容；新逻辑只读写 cursor_value）")
    private String incrValue;

    @Column(name = "cursor_value", length = 255)
    @Comment("增量游标：上一次成功同步的最后一个增量字段值（null = 首次）")
    private String cursorValue;

    @Column(name = "order_column", length = 128)
    @Comment("键集分页排序列，空则自动推断")
    private String orderColumn;

    @Column(name = "cron_expression", length = 100)
    @Comment("Quartz Cron 调度表达式")
    private String cronExpression;

    @Column(name = "page_size")
    @Comment("每次读取的行数（内存 chunk）")
    private Integer pageSize = 1000;

    @Column(name = "batch_size")
    @Comment("每批写入的行数")
    private Integer batchSize = 500;

    @Column(name = "mapping_json", columnDefinition = "TEXT")
    @Comment("字段映射配置（JSON格式）")
    private String mappingJson;

    @Column(name = "source_mode", length = 20)
    @Comment("数据源模式：TABLE / CUSTOM_SQL")
    private String sourceMode = "TABLE";

    @Column(name = "source_sql", columnDefinition = "TEXT")
    @Comment("自定义查询SQL（source_mode为CUSTOM_SQL时使用）")
    private String sourceSql;

    @Column(length = 20)
    @Comment("任务状态：ENABLED / DISABLED（与 enabled 列同步维护）")
    private String status = "DISABLED";

    @Column(name = "safety_lag_seconds", nullable = false)
    @Comment("增量读上界安全滞后秒数（规避未提交事务）")
    private long safetyLagSeconds = 0L;

    @Column(name = "lookback_seconds", nullable = false)
    @Comment("增量读下界回看秒数（补迟到的历史写入）")
    private long lookbackSeconds = 0L;

    @Column(name = "full_sync_strategy", nullable = false, length = 16)
    @Comment("全量策略：TRUNCATE / DELETE / SWAP")
    private String fullSyncStrategy = "TRUNCATE";

    @Column(name = "error_policy_json", columnDefinition = "TEXT")
    @Comment("错误处理策略（JSON：重试次数/退避/是否跳过坏行/坏行上限）")
    private String errorPolicyJson;

    @Column(name = "enabled", nullable = false, columnDefinition = "TINYINT(1)")
    @Comment("是否启用调度：1 启用 0 停用（与 status 同步维护）")
    private boolean enabled = true;

    @Column(name = "created_at")
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    /** 读取有效的增量水位：优先 cursor_value，历史行回落到 incr_value */
    public String effectiveCursor() {
        if (cursorValue != null && !cursorValue.isBlank()) {
            return cursorValue;
        }
        if (incrValue != null && !incrValue.isBlank()) {
            return incrValue;
        }
        return null;
    }

    /** status 与 enabled 永远同步写入，避免两个字段互相打脸 */
    public void applyEnabled(boolean value) {
        this.enabled = value;
        this.status = value ? "ENABLED" : "DISABLED";
    }

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public Long getSourceDsId() { return sourceDsId; } public void setSourceDsId(Long v) { this.sourceDsId = v; }
    public Long getTargetDsId() { return targetDsId; } public void setTargetDsId(Long v) { this.targetDsId = v; }
    public String getSourceTable() { return sourceTable; } public void setSourceTable(String v) { this.sourceTable = v; }
    public String getTargetTable() { return targetTable; } public void setTargetTable(String v) { this.targetTable = v; }
    public String getSyncMode() { return syncMode; } public void setSyncMode(String v) { this.syncMode = v; }
    public String getIncrColumn() { return incrColumn; } public void setIncrColumn(String v) { this.incrColumn = v; }
    public String getIncrValue() { return incrValue; } public void setIncrValue(String v) { this.incrValue = v; }
    public String getCursorValue() { return cursorValue; } public void setCursorValue(String v) { this.cursorValue = v; }
    public String getOrderColumn() { return orderColumn; } public void setOrderColumn(String v) { this.orderColumn = v; }
    public String getCronExpression() { return cronExpression; } public void setCronExpression(String v) { this.cronExpression = v; }
    public Integer getPageSize() { return pageSize; } public void setPageSize(Integer v) { this.pageSize = v; }
    public Integer getBatchSize() { return batchSize; } public void setBatchSize(Integer v) { this.batchSize = v; }
    public String getMappingJson() { return mappingJson; } public void setMappingJson(String v) { this.mappingJson = v; }
    public String getSourceMode() { return sourceMode; } public void setSourceMode(String v) { this.sourceMode = v; }
    public String getSourceSql() { return sourceSql; } public void setSourceSql(String v) { this.sourceSql = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public long getSafetyLagSeconds() { return safetyLagSeconds; } public void setSafetyLagSeconds(long v) { this.safetyLagSeconds = v; }
    public long getLookbackSeconds() { return lookbackSeconds; } public void setLookbackSeconds(long v) { this.lookbackSeconds = v; }
    public String getFullSyncStrategy() { return fullSyncStrategy; } public void setFullSyncStrategy(String v) { this.fullSyncStrategy = v; }
    public String getErrorPolicyJson() { return errorPolicyJson; } public void setErrorPolicyJson(String v) { this.errorPolicyJson = v; }
    public boolean isEnabled() { return enabled; } public void setEnabled(boolean v) { this.enabled = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime t) { this.createdAt = t; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
}
