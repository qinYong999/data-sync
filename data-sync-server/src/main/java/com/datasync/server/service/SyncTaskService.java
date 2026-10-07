package com.datasync.server.service;

import com.datasync.core.model.PreflightIssue;
import com.datasync.core.preflight.CustomSqlGuard;
import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.model.TaskDTO;
import com.datasync.server.repository.DataSourceRepository;
import com.datasync.server.repository.SyncTaskRepository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 任务 CRUD、调度表达式校验与落库（契约 §4.1）。
 *
 * <p>校验的目标是"错误在保存时就被拦住并给出中文原因"，而不是等到执行时才炸：
 * 同步模式、增量字段、全量策略、cron 表达式、分页/批量大小、自定义 SQL 都在这里校验。</p>
 */
@Service
public class SyncTaskService {

    private static final Logger log = LoggerFactory.getLogger(SyncTaskService.class);

    private final SyncTaskRepository taskRepo;
    private final DataSourceRepository dsRepo;
    private final SyncSchedulerService schedulerService;
    private final SyncTaskConfigMapper configMapper;
    private final ObjectMapper mapper;

    public SyncTaskService(SyncTaskRepository taskRepo, DataSourceRepository dsRepo,
                           SyncSchedulerService schedulerService, SyncTaskConfigMapper configMapper,
                           ObjectMapper mapper) {
        this.taskRepo = taskRepo;
        this.dsRepo = dsRepo;
        this.schedulerService = schedulerService;
        this.configMapper = configMapper;
        this.mapper = mapper;
    }

    public Page<SyncTaskEntity> findAll(Pageable pageable) {
        return taskRepo.findAll(pageable);
    }

    public SyncTaskEntity findById(Long id) {
        return taskRepo.findById(id).orElseThrow(() -> AppException.notFound("任务不存在: " + id));
    }

    @Transactional
    public SyncTaskEntity create(TaskDTO dto) {
        validate(dto);
        SyncTaskEntity entity = new SyncTaskEntity();
        apply(entity, dto);
        // 新建任务默认停用（与历史行为一致），需要显式启用才会进入调度
        entity.applyEnabled(dto.getEnabled() != null && dto.getEnabled());
        SyncTaskEntity saved = taskRepo.save(entity);
        syncSchedule(saved);
        return saved;
    }

    @Transactional
    public SyncTaskEntity update(Long id, TaskDTO dto) {
        SyncTaskEntity entity = findById(id);
        validate(dto);
        apply(entity, dto);
        if (dto.getEnabled() != null) {
            entity.applyEnabled(dto.getEnabled());
        }
        SyncTaskEntity saved = taskRepo.save(entity);
        syncSchedule(saved);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        findById(id);
        schedulerService.unregisterJob(id);
        taskRepo.deleteById(id);
    }

    @Transactional
    public SyncTaskEntity enableTask(Long id) {
        SyncTaskEntity entity = findById(id);
        entity.applyEnabled(true);
        SyncTaskEntity saved = taskRepo.save(entity);
        syncSchedule(saved);
        return saved;
    }

    @Transactional
    public SyncTaskEntity disableTask(Long id) {
        SyncTaskEntity entity = findById(id);
        entity.applyEnabled(false);
        SyncTaskEntity saved = taskRepo.save(entity);
        schedulerService.unregisterJob(id);
        return saved;
    }

    @Transactional
    public SyncTaskEntity updateSchedule(Long id, String cron) {
        SyncTaskEntity entity = findById(id);
        String normalized = cron == null || cron.isBlank() ? null : cron.trim();
        if (normalized != null) {
            // cron 非法 → 400 中文提示，而不是 500
            SyncSchedulerService.validateCron(normalized);
        }
        entity.setCronExpression(normalized);
        SyncTaskEntity saved = taskRepo.save(entity);
        syncSchedule(saved);
        return saved;
    }

    /** 让 Quartz 注册状态与任务当前配置一致 */
    private void syncSchedule(SyncTaskEntity entity) {
        boolean schedulable = entity.isEnabled()
            && entity.getCronExpression() != null && !entity.getCronExpression().isBlank();
        try {
            if (schedulable) {
                schedulerService.registerJob(entity.getId(), entity.getCronExpression());
            } else {
                schedulerService.unregisterJob(entity.getId());
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("同步 Quartz 调度失败（任务 {}）", entity.getId(), e);
            throw new IllegalStateException("调度注册失败：" + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------ 校验

    private void validate(TaskDTO dto) {
        if (dto == null) {
            throw AppException.badRequest("INVALID_TASK", "任务参数不能为空");
        }
        // D2：未知字段必须明确拒绝，不能让"字段名拼错/错用 DB 列名"变成静默丢弃
        List<String> unknown = dto.unknownFields();
        if (!unknown.isEmpty()) {
            throw AppException.badRequest("UNKNOWN_FIELD",
                "请求体包含未知字段：" + String.join(", ", unknown)
                    + "。请检查字段名拼写（接口字段名与数据库列名不一定相同：错误处理策略请用 errorPolicy，"
                    + "也接受 DB 列名风格的 errorPolicyJson）",
                unknown);
        }
        requireText(dto.getName(), "任务名称");
        requireText(dto.getSourceTable(), "源表名");
        requireText(dto.getTargetTable(), "目标表名");
        if (dto.getSourceDsId() == null || dsRepo.findById(dto.getSourceDsId()).isEmpty()) {
            throw AppException.badRequest("SOURCE_DATASOURCE_MISSING",
                "源数据源不存在: " + dto.getSourceDsId());
        }
        if (dto.getTargetDsId() == null || dsRepo.findById(dto.getTargetDsId()).isEmpty()) {
            throw AppException.badRequest("TARGET_DATASOURCE_MISSING",
                "目标数据源不存在: " + dto.getTargetDsId());
        }
        var mode = SyncTaskConfigMapper.parseMode(dto.getSyncMode());
        if (mode != com.datasync.core.model.enums.SyncMode.FULL
            && (dto.getIncrColumn() == null || dto.getIncrColumn().isBlank())) {
            throw AppException.badRequest("INCR_COLUMN_MISSING",
                "增量同步（INCR / FULL_INCR）必须配置增量字段");
        }
        if (dto.getFullSyncStrategy() != null && !dto.getFullSyncStrategy().isBlank()) {
            SyncTaskConfigMapper.parseStrategy(dto.getFullSyncStrategy());
        }
        if (dto.getPageSize() != null && dto.getPageSize() <= 0) {
            throw AppException.badRequest("INVALID_PAGE_SIZE", "每页行数必须大于 0");
        }
        if (dto.getBatchSize() != null && dto.getBatchSize() <= 0) {
            throw AppException.badRequest("INVALID_BATCH_SIZE", "每批行数必须大于 0");
        }
        if (dto.getPageSize() != null && dto.getBatchSize() != null
            && dto.getBatchSize() > dto.getPageSize()) {
            throw AppException.badRequest("INVALID_BATCH_SIZE", "每批行数不能大于每页行数");
        }
        if (dto.getSafetyLagSeconds() != null && dto.getSafetyLagSeconds() < 0) {
            throw AppException.badRequest("INVALID_SAFETY_LAG", "安全滞后秒数不能为负");
        }
        if (dto.getLookbackSeconds() != null && dto.getLookbackSeconds() < 0) {
            throw AppException.badRequest("INVALID_LOOKBACK", "回看窗口秒数不能为负");
        }
        if (dto.getCronExpression() != null && !dto.getCronExpression().isBlank()) {
            SyncSchedulerService.validateCron(dto.getCronExpression().trim());
        }
        if ("CUSTOM_SQL".equalsIgnoreCase(dto.getSourceMode())) {
            PreflightIssue issue = CustomSqlGuard.validate(dto.getSourceSql());
            if (issue != null) {
                throw AppException.badRequest(
                    issue.getCode() == null ? "CUSTOM_SQL_INVALID" : issue.getCode(), issue.getMessage());
            }
        }
        if (dto.getErrorPolicy() != null || dto.getErrorPolicyJson() != null) {
            configMapper.writeErrorPolicy(normalizeErrorPolicy(dto));
        }
    }

    /**
     * 归一化错误处理策略：优先 {@code errorPolicy}；否则接受 DB 列名风格的
     * {@code errorPolicyJson}（JSON 对象或 JSON 字符串两种形态都支持）。
     *
     * <p>这条别名是为了兼容前端既有用法（它也按 DB 列名拼请求体），
     * 同时把"静默丢弃"变成"真正生效"。</p>
     */
    private JsonNode normalizeErrorPolicy(TaskDTO dto) {
        JsonNode policy = dto.getErrorPolicy();
        if (policy != null && !policy.isNull()) {
            return policy;
        }
        JsonNode alias = dto.getErrorPolicyJson();
        if (alias == null || alias.isNull()) {
            return null;
        }
        if (alias.isObject()) {
            return alias;
        }
        if (alias.isString()) {
            String text = alias.asString();
            if (text.isBlank()) {
                return null;
            }
            try {
                JsonNode parsed = mapper.readTree(text);
                if (parsed == null || !parsed.isObject()) {
                    throw AppException.badRequest("INVALID_ERROR_POLICY",
                        "errorPolicyJson 必须是 JSON 对象，例如 {\"skipBadRows\":true}");
                }
                return parsed;
            } catch (AppException e) {
                throw e;
            } catch (Exception e) {
                throw AppException.badRequest("INVALID_ERROR_POLICY",
                    "errorPolicyJson 不是合法的 JSON（无法解析为对象）");
            }
        }
        throw AppException.badRequest("INVALID_ERROR_POLICY",
            "errorPolicyJson 必须是 JSON 对象或其字符串形式");
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AppException.badRequest("INVALID_TASK", field + "不能为空");
        }
    }

    // ------------------------------------------------------------------ 映射

    private void apply(SyncTaskEntity entity, TaskDTO dto) {
        entity.setName(dto.getName().trim());
        entity.setSourceDsId(dto.getSourceDsId());
        entity.setTargetDsId(dto.getTargetDsId());
        entity.setSourceTable(dto.getSourceTable().trim());
        entity.setTargetTable(dto.getTargetTable().trim());
        entity.setSyncMode(dto.getSyncMode().trim().toUpperCase());
        entity.setIncrColumn(blankToNull(dto.getIncrColumn()));
        entity.setOrderColumn(blankToNull(dto.getOrderColumn()));
        // 水位：cursorValue 优先，incrValue 为历史别名；两者都为空表示不动（保留库中值）
        String cursor = dto.getCursorValue() != null ? dto.getCursorValue() : dto.getIncrValue();
        if (cursor != null && !cursor.isBlank()) {
            entity.setCursorValue(cursor.trim());
        }
        entity.setCronExpression(blankToNull(dto.getCronExpression()));
        if (dto.getPageSize() != null) {
            entity.setPageSize(dto.getPageSize());
        }
        if (dto.getBatchSize() != null) {
            entity.setBatchSize(dto.getBatchSize());
        }
        if (dto.getSafetyLagSeconds() != null) {
            entity.setSafetyLagSeconds(dto.getSafetyLagSeconds());
        }
        if (dto.getLookbackSeconds() != null) {
            entity.setLookbackSeconds(dto.getLookbackSeconds());
        }
        if (dto.getFullSyncStrategy() != null && !dto.getFullSyncStrategy().isBlank()) {
            entity.setFullSyncStrategy(dto.getFullSyncStrategy().trim().toUpperCase());
        }
        if (dto.getErrorPolicy() != null || dto.getErrorPolicyJson() != null) {
            // errorPolicy 与 DB 列名风格别名 errorPolicyJson 等价，统一归一化后落库
            JsonNode policy = normalizeErrorPolicy(dto);
            if (policy != null) {
                entity.setErrorPolicyJson(configMapper.writeErrorPolicy(policy));
            } else if (dto.getErrorPolicyJson() != null) {
                // 显式传了空字符串/空对象：视为清空策略
                entity.setErrorPolicyJson(null);
            }
        }
        if (dto.getSourceMode() != null && !dto.getSourceMode().isBlank()) {
            entity.setSourceMode(dto.getSourceMode().trim().toUpperCase());
        } else if (entity.getSourceMode() == null) {
            entity.setSourceMode("TABLE");
        }
        if (dto.getSourceSql() != null) {
            entity.setSourceSql(blankToNull(dto.getSourceSql()));
        }
        if (dto.getFieldMappings() != null) {
            try {
                entity.setMappingJson(mapper.writeValueAsString(dto.getFieldMappings()));
            } catch (JacksonException e) {
                throw AppException.badRequest("INVALID_MAPPING_JSON", "字段映射序列化失败");
            }
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 供测试与内部使用的只读视图 */
    public List<SyncTaskEntity> findByStatus(String status) {
        return taskRepo.findByStatus(status);
    }
}
