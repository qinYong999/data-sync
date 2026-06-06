package com.datasync.server.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name = "sync_task")
public class SyncTaskEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 200) private String name;
    @Column(name = "source_ds_id", nullable = false) private Long sourceDsId;
    @Column(name = "target_ds_id", nullable = false) private Long targetDsId;
    @Column(name = "source_table", nullable = false, length = 200) private String sourceTable;
    @Column(name = "target_table", nullable = false, length = 200) private String targetTable;
    @Column(name = "sync_mode", nullable = false, length = 20) private String syncMode;
    @Column(name = "incr_column", length = 100) private String incrColumn;
    @Column(name = "incr_value", length = 255) private String incrValue;
    @Column(name = "cron_expression", length = 100) private String cronExpression;
    @Column(name = "page_size") private Integer pageSize = 1000;
    @Column(name = "batch_size") private Integer batchSize = 500;
    @Column(name = "mapping_json", columnDefinition = "TEXT") private String mappingJson;
    @Column(length = 20) private String status = "DISABLED";
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
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
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime t) { this.createdAt = t; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
}