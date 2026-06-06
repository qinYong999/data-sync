package com.datasync.server.model;
import java.util.List;
public class TaskDTO {
    private Long id; private String name; private Long sourceDsId; private Long targetDsId;
    private String sourceTable; private String targetTable; private String syncMode;
    private String incrColumn; private String incrValue; private String cronExpression;
    private Integer pageSize; private Integer batchSize;
    private List<FieldMappingItem> fieldMappings;
    public static class FieldMappingItem { private String sourceColumn; private String targetColumn; private String defaultValue; private boolean primaryKey;
        public String getSourceColumn() { return sourceColumn; } public void setSourceColumn(String v) { this.sourceColumn = v; }
        public String getTargetColumn() { return targetColumn; } public void setTargetColumn(String v) { this.targetColumn = v; }
        public String getDefaultValue() { return defaultValue; } public void setDefaultValue(String v) { this.defaultValue = v; }
        public boolean isPrimaryKey() { return primaryKey; } public void setPrimaryKey(boolean v) { this.primaryKey = v; }
    }
    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
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
    public List<FieldMappingItem> getFieldMappings() { return fieldMappings; } public void setFieldMappings(List<FieldMappingItem> v) { this.fieldMappings = v; }
}