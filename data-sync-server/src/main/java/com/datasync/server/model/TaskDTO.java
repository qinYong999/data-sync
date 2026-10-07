package com.datasync.server.model;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import tools.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务创建/更新请求体。
 *
 * <p>新增字段对应契约 §4.2 的新列：{@code cursorValue} / {@code orderColumn} /
 * {@code safetyLagSeconds} / {@code lookbackSeconds} / {@code fullSyncStrategy} /
 * {@code errorPolicy}；{@code incrValue} 作为历史别名保留（读时等价于 cursorValue）。</p>
 *
 * <p><b>未知字段一律 400</b>：这是配置型接口，静默丢弃会让"配了但没生效"极难自查
 * （QA 误用 DB 列名 {@code errorPolicyJson} 正是如此）。实现方式是 {@link JsonAnySetter}
 * 把未识别字段收集起来，再由 {@code SyncTaskService} 统一拒绝——
 * 比 {@code @JsonIgnoreProperties(ignoreUnknown = false)} 可靠（后者在
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} 的全局配置下并不生效）。</p>
 *
 * <p>数据库列名风格的 {@code errorPolicyJson} 已作为显式别名被接受，见 {@link #errorPolicyJson}。</p>
 */
public class TaskDTO {

    private Long id;
    private String name;
    private Long sourceDsId;
    private Long targetDsId;
    private String sourceTable;
    private String targetTable;
    private String syncMode;
    private String incrColumn;
    /** 历史别名：等价于 cursorValue */
    private String incrValue;
    private String cursorValue;
    private String orderColumn;
    private String cronExpression;
    private Integer pageSize;
    private Integer batchSize;
    private String sourceMode;
    private String sourceSql;
    private Long safetyLagSeconds;
    private Long lookbackSeconds;
    private String fullSyncStrategy;
    /** 错误处理策略：{maxRetries,retryBackoffMs,skipBadRows,maxSkipRows,maxErrorsRecorded} */
    private JsonNode errorPolicy;
    /**
     * DB 列名风格的别名（前端既有用法）：内容可以是 JSON 对象，也可以是 JSON 字符串。
     * 与 {@link #errorPolicy} 等价；两者同时出现时以 {@code errorPolicy} 为准。
     */
    private JsonNode errorPolicyJson;
    /** 是否启用调度；为空时沿用任务当前状态 */
    private Boolean enabled;
    private List<FieldMappingItem> fieldMappings;

    public static class FieldMappingItem {
        private String sourceColumn;
        private String targetColumn;
        private String defaultValue;
        private boolean primaryKey;
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
    public String getCursorValue() { return cursorValue; } public void setCursorValue(String v) { this.cursorValue = v; }
    public String getOrderColumn() { return orderColumn; } public void setOrderColumn(String v) { this.orderColumn = v; }
    public String getCronExpression() { return cronExpression; } public void setCronExpression(String v) { this.cronExpression = v; }
    public Integer getPageSize() { return pageSize; } public void setPageSize(Integer v) { this.pageSize = v; }
    public Integer getBatchSize() { return batchSize; } public void setBatchSize(Integer v) { this.batchSize = v; }
    public String getSourceMode() { return sourceMode; } public void setSourceMode(String v) { this.sourceMode = v; }
    public String getSourceSql() { return sourceSql; } public void setSourceSql(String v) { this.sourceSql = v; }
    public Long getSafetyLagSeconds() { return safetyLagSeconds; } public void setSafetyLagSeconds(Long v) { this.safetyLagSeconds = v; }
    public Long getLookbackSeconds() { return lookbackSeconds; } public void setLookbackSeconds(Long v) { this.lookbackSeconds = v; }
    public String getFullSyncStrategy() { return fullSyncStrategy; } public void setFullSyncStrategy(String v) { this.fullSyncStrategy = v; }
    public JsonNode getErrorPolicy() { return errorPolicy; } public void setErrorPolicy(JsonNode v) { this.errorPolicy = v; }
    public JsonNode getErrorPolicyJson() { return errorPolicyJson; } public void setErrorPolicyJson(JsonNode v) { this.errorPolicyJson = v; }
    public Boolean getEnabled() { return enabled; } public void setEnabled(Boolean v) { this.enabled = v; }
    public List<FieldMappingItem> getFieldMappings() { return fieldMappings; } public void setFieldMappings(List<FieldMappingItem> v) { this.fieldMappings = v; }

    /** 未识别的请求体字段（由 Jackson 收集，由服务层统一拒绝并给出中文提示） */
    private final Map<String, Object> unknownFields = new LinkedHashMap<>();

    @JsonAnySetter
    public void putUnknownField(String name, Object value) {
        unknownFields.put(name, value);
    }

    /** 方法名刻意不是 JavaBean getter：避免被当成响应字段序列化出去 */
    public List<String> unknownFields() {
        return List.copyOf(unknownFields.keySet());
    }
}
