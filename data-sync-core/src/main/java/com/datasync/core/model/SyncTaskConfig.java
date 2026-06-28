package com.datasync.core.model;

import com.datasync.core.model.enums.SyncMode;
import com.datasync.core.model.enums.SyncStatus;
import java.util.List;

/**
 * 同步任务配置
 */
public class SyncTaskConfig {
    private Long id;
    private String name;
    private Long sourceDsId;
    private Long targetDsId;
    private String sourceTable;
    private String targetTable;
    private String sourceMode;
    private String sourceSql;
    private SyncMode syncMode;
    private String incrColumn;
    private String incrValue;
    private String cronExpression;
    private int pageSize = 1000;
    private int batchSize = 500;
    private List<FieldMapping> fieldMappings;
    private SyncStatus status = SyncStatus.DISABLED;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Long getSourceDsId() { return sourceDsId; }
    public void setSourceDsId(Long sourceDsId) { this.sourceDsId = sourceDsId; }
    public Long getTargetDsId() { return targetDsId; }
    public void setTargetDsId(Long targetDsId) { this.targetDsId = targetDsId; }
    public String getSourceTable() { return sourceTable; }
    public void setSourceTable(String sourceTable) { this.sourceTable = sourceTable; }
    public String getTargetTable() { return targetTable; }
    public void setTargetTable(String targetTable) { this.targetTable = targetTable; }
    public String getSourceMode() { return sourceMode; }
    public void setSourceMode(String sourceMode) { this.sourceMode = sourceMode; }
    public String getSourceSql() { return sourceSql; }
    public void setSourceSql(String sourceSql) { this.sourceSql = sourceSql; }
    public SyncMode getSyncMode() { return syncMode; }
    public void setSyncMode(SyncMode syncMode) { this.syncMode = syncMode; }
    public String getIncrColumn() { return incrColumn; }
    public void setIncrColumn(String incrColumn) { this.incrColumn = incrColumn; }
    public String getIncrValue() { return incrValue; }
    public void setIncrValue(String incrValue) { this.incrValue = incrValue; }
    public String getCronExpression() { return cronExpression; }
    public void setCronExpression(String cronExpression) { this.cronExpression = cronExpression; }
    public int getPageSize() { return pageSize; }
    public void setPageSize(int pageSize) { this.pageSize = pageSize; }
    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
    public List<FieldMapping> getFieldMappings() { return fieldMappings; }
    public void setFieldMappings(List<FieldMapping> fieldMappings) { this.fieldMappings = fieldMappings; }
    public SyncStatus getStatus() { return status; }
    public void setStatus(SyncStatus status) { this.status = status; }
}
