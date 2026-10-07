package com.datasync.server.service;

import com.datasync.core.model.ErrorPolicy;
import com.datasync.core.model.FieldMapping;
import com.datasync.core.model.FullSyncStrategy;
import com.datasync.core.model.IncrPolicy;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.model.enums.SyncMode;
import com.datasync.server.config.AppProperties;
import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.exception.AppException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * {@code SyncTaskEntity} → {@code SyncTaskConfig} 映射（契约 §3.1 / §4.2）。
 *
 * <p>要点：{@code cursorValue} 由 server 从库里读出后传入，<b>null 表示首次同步</b>；
 * 引擎据此决定水位区间与是否走首次全量。</p>
 *
 * <p>所有 JSON 列（mapping_json / error_policy_json）解析失败都只抛可读的中文业务异常，
 * 绝不冒泡成 500 —— 历史库里的旧格式数据是很容易踩的坑。</p>
 */
@Component
public class SyncTaskConfigMapper {

    private static final Logger log = LoggerFactory.getLogger(SyncTaskConfigMapper.class);

    private static final Set<String> ERROR_POLICY_FIELDS = Set.of(
        "maxRetries", "retryBackoffMs", "skipBadRows", "maxSkipRows", "maxErrorsRecorded");

    private final AppProperties properties;
    private final ObjectMapper mapper;

    public SyncTaskConfigMapper(AppProperties properties, ObjectMapper mapper) {
        this.properties = properties;
        this.mapper = mapper;
    }

    public SyncTaskConfig toConfig(SyncTaskEntity entity) {
        SyncTaskConfig config = new SyncTaskConfig();
        config.setId(entity.getId());
        config.setName(entity.getName());
        config.setSourceTable(entity.getSourceTable());
        config.setTargetTable(entity.getTargetTable());
        config.setSyncMode(parseMode(entity.getSyncMode()));
        config.setIncrColumn(blankToNull(entity.getIncrColumn()));
        // 水位：库里 cursor_value 优先，历史行回落到 incr_value；null = 首次
        config.setCursorValue(entity.effectiveCursor());
        config.setOrderColumn(blankToNull(entity.getOrderColumn()));
        config.setSourceMode(entity.getSourceMode() == null ? "TABLE" : entity.getSourceMode());
        config.setSourceSql(blankToNull(entity.getSourceSql()));
        config.setFieldMappings(parseFieldMappings(entity.getMappingJson()));

        AppProperties.Sync sync = properties.getSync();
        int pageSize = entity.getPageSize() == null || entity.getPageSize() <= 0
            ? sync.getDefaultPageSize() : entity.getPageSize();
        int batchSize = entity.getBatchSize() == null || entity.getBatchSize() <= 0
            ? sync.getDefaultBatchSize() : entity.getBatchSize();
        config.setPageSize(pageSize);
        // batchSize 不允许超过 pageSize（契约约定 <= pageSize）
        config.setBatchSize(Math.min(batchSize, pageSize));
        config.setFetchSize(pageSize);
        config.setQueryTimeoutSeconds(sync.getQueryTimeoutSeconds());
        config.setDryRun(false);

        IncrPolicy incrPolicy = new IncrPolicy();
        incrPolicy.setSafetyLagSeconds(entity.getSafetyLagSeconds() >= 0
            ? entity.getSafetyLagSeconds() : sync.getDefaultSafetyLagSeconds());
        incrPolicy.setLookbackSeconds(entity.getLookbackSeconds() >= 0
            ? entity.getLookbackSeconds() : sync.getDefaultLookbackSeconds());
        // timestampColumn 由引擎的预检结果回填，这里不猜
        config.setIncrPolicy(incrPolicy);

        config.setFullSyncStrategy(parseStrategy(entity.getFullSyncStrategy()));
        config.setErrorPolicy(parseErrorPolicy(entity.getErrorPolicyJson()));
        return config;
    }

    /** 用户可见的模式描述（中文），用于实时日志与执行记录 */
    public String describeMode(SyncTaskEntity entity) {
        SyncMode mode = parseMode(entity.getSyncMode());
        String cursor = entity.effectiveCursor();
        return switch (mode) {
            case FULL -> "全量同步（策略：" + entity.getFullSyncStrategy() + "）";
            case INCR -> cursor == null
                ? "增量同步（增量字段=" + entity.getIncrColumn() + "，首次执行：从全表起点开始）"
                : "增量同步（增量字段=" + entity.getIncrColumn() + "，起始水位=" + cursor + "）";
            case FULL_INCR -> cursor == null
                ? "全量+增量（首次执行：先全量）"
                : "全量+增量（增量字段=" + entity.getIncrColumn() + "，起始水位=" + cursor + "）";
        };
    }

    public static SyncMode parseMode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.badRequest("INVALID_SYNC_MODE", "同步模式不能为空，可选值：FULL / INCR / FULL_INCR");
        }
        try {
            return SyncMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("INVALID_SYNC_MODE",
                "同步模式非法：" + raw + "，可选值：FULL / INCR / FULL_INCR");
        }
    }

    public static FullSyncStrategy parseStrategy(String raw) {
        if (raw == null || raw.isBlank()) {
            return FullSyncStrategy.TRUNCATE;
        }
        try {
            return FullSyncStrategy.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("INVALID_FULL_SYNC_STRATEGY",
                "全量同步策略非法：" + raw + "，可选值：TRUNCATE / DELETE / SWAP");
        }
    }

    /** 解析字段映射；旧格式/坏数据只记 WARN 并抛可读中文错误，绝不 500 */
    public List<FieldMapping> parseFieldMappings(String mappingJson) {
        if (mappingJson == null || mappingJson.isBlank()) {
            return List.of();
        }
        try {
            List<FieldMapping> mappings = mapper.readValue(mappingJson, new TypeReference<List<FieldMapping>>() { });
            return mappings == null ? List.of() : mappings;
        } catch (Exception e) {
            log.warn("字段映射解析失败（按空配置继续）：{}", e.getMessage());
            throw AppException.badRequest("INVALID_MAPPING_JSON",
                "解析字段映射配置失败：JSON 格式非法（应为对象数组），请在任务编辑页重新选择字段映射");
        }
    }

    /** 把 error_policy_json 解析成 core 的 ErrorPolicy；非法/缺省一律回落到契约默认值 */
    public ErrorPolicy parseErrorPolicy(String json) {
        ErrorPolicy policy = new ErrorPolicy();
        if (json == null || json.isBlank()) {
            return policy;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (node.hasNonNull("maxRetries")) {
                policy.setMaxRetries(node.get("maxRetries").asInt(policy.getMaxRetries()));
            }
            if (node.hasNonNull("retryBackoffMs")) {
                policy.setRetryBackoffMs(node.get("retryBackoffMs").asLong(policy.getRetryBackoffMs()));
            }
            if (node.hasNonNull("skipBadRows")) {
                policy.setSkipBadRows(node.get("skipBadRows").asBoolean(policy.isSkipBadRows()));
            }
            if (node.hasNonNull("maxSkipRows")) {
                policy.setMaxSkipRows(node.get("maxSkipRows").asLong(policy.getMaxSkipRows()));
            }
            if (node.hasNonNull("maxErrorsRecorded")) {
                policy.setMaxErrorsRecorded(node.get("maxErrorsRecorded").asInt(policy.getMaxErrorsRecorded()));
            }
            return policy;
        } catch (Exception e) {
            log.warn("错误处理策略解析失败（回落默认值）：{}", e.getMessage());
            throw AppException.badRequest("INVALID_ERROR_POLICY",
                "错误处理策略 JSON 非法，应为对象：{maxRetries,retryBackoffMs,skipBadRows,maxSkipRows,maxErrorsRecorded}");
        }
    }

    /** 校验并规范化 error_policy_json（未知字段直接报可读错误） */
    public String writeErrorPolicy(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw AppException.badRequest("INVALID_ERROR_POLICY", "错误处理策略必须是 JSON 对象");
        }
        List<String> unknown = new ArrayList<>();
        for (String name : node.propertyNames()) {
            if (!ERROR_POLICY_FIELDS.contains(name)) {
                unknown.add(name);
            }
        }
        if (!unknown.isEmpty()) {
            throw AppException.badRequest("INVALID_ERROR_POLICY",
                "错误处理策略包含未知字段：" + String.join(", ", unknown), unknown);
        }
        return mapper.writeValueAsString(node);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
