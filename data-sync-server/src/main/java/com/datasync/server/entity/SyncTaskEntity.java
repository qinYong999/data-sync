package com.datasync.server.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

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
    @Comment("增量同步的时间戳列名")
    private String incrColumn;

    @Column(name = "incr_value", length = 255)
    @Comment("增量同步起始值")
    private String incrValue;

    @Column(name = "cron_expression", length = 100)
    @Comment("Quartz Cron 调度表达式")
    private String cronExpression;

    @Column(name = "page_size")
    @Comment("每次读取的行数")
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
    @Comment("任务状态：ENABLED / DISABLED")
    private String status = "DISABLED";

    @Column(name = "created_at")
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public Long getSourceDsId() { return sourceDsId; } public void setSourceDsId(Long v) { this.sourceDsId = v; }
    public Long getTargetDsId() { return targetDsId; } public void setTargetDsId(Long v) { this.targetDsId = v; }
    public String getSourceTable() { return sourceTable; } public void setSourceTable(String v) { this.sourceTable = v; }
    public String getTargetTable() { return targetTable; } public void setTargetTable(String v) { this.targetTable = v; }
    public String getSyncMode() { return syncMode; } public void setSyncMode(String v) { this.syncMode = v; }
    public String getIncrColumn() { return incrColumn; } public void setIncrColumn(String v) { this.incrColumn = v; }
    public String getIncrValue() { return incrValue; } public void setIncrValue(String v) { this.incrValue = v; }
    public String getCronExpression() { return cronExpression; } public void setCronExpression(String v) { this.cronExpression = v; }
    public Integer getPageSize() { return pageSize; } public void setPageSize(Integer v) { this.pageSize = v; }
    public Integer getBatchSize() { return batchSize; } public void setBatchSize(Integer v) { this.batchSize = v; }
    public String getMappingJson() { return mappingJson; } public void setMappingJson(String v) { this.mappingJson = v; }
    public String getSourceMode() { return sourceMode; } public void setSourceMode(String v) { this.sourceMode = v; }
    public String getSourceSql() { return sourceSql; } public void setSourceSql(String v) { this.sourceSql = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime t) { this.createdAt = t; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
}