package com.datasync.core.model;

/**
 * 字段映射配置
 */
public class FieldMapping {
    private String sourceColumn;
    private String targetColumn;
    private String defaultValue;
    private boolean primaryKey;

    public FieldMapping() {}

    public FieldMapping(String sourceColumn, String targetColumn) {
        this.sourceColumn = sourceColumn;
        this.targetColumn = targetColumn;
    }

    public String getSourceColumn() { return sourceColumn; }
    public void setSourceColumn(String sourceColumn) { this.sourceColumn = sourceColumn; }
    public String getTargetColumn() { return targetColumn; }
    public void setTargetColumn(String targetColumn) { this.targetColumn = targetColumn; }
    public String getDefaultValue() { return defaultValue; }
    public void setDefaultValue(String defaultValue) { this.defaultValue = defaultValue; }
    public boolean isPrimaryKey() { return primaryKey; }
    public void setPrimaryKey(boolean primaryKey) { this.primaryKey = primaryKey; }
}