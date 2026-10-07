package com.datasync.server.service;

import com.datasync.core.engine.ChunkProgress;
import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncError;
import com.datasync.core.model.SyncRunResult;
import com.datasync.server.entity.SyncErrorEntity;
import com.datasync.server.entity.SyncRecordEntity;
import com.datasync.server.repository.SyncErrorRepository;
import com.datasync.server.repository.SyncRecordRepository;
import com.datasync.server.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@code sync_record} / {@code sync_error} 持久化（契约 §4.1）。
 *
 * <p>互斥令牌：运行中的记录 {@code run_key = "RUNNING-{taskId}"}，由唯一索引
 * {@code uk_sync_record_run_key} 保证同一任务同时只有一条 RUNNING 记录——
 * 这是 D11 的"数据库级"那一半保障（应用级是 {@code TaskRunService} 的内存锁）。
 * 结束时把 run_key 改写为 {@code {taskId}-{recordId}}，既释放令牌又留下稳定的幂等键。</p>
 */
@Service
public class RunRecordStore {

    private static final Logger log = LoggerFactory.getLogger(RunRecordStore.class);

    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final int MAX_ROW_DATA_LENGTH = 4000;
    private static final String ORPHAN_MESSAGE = "进程异常退出，该次执行未完成";
    private static final long PROGRESS_FLUSH_INTERVAL_MS = 2000L;

    private final SyncRecordRepository recordRepo;
    private final SyncErrorRepository errorRepo;
    private final ObjectMapper mapper;

    /** 进度落库节流：每个 record 最多每 {@link #PROGRESS_FLUSH_INTERVAL_MS} 毫秒写一次库 */
    private final Map<Long, Long> lastProgressFlush = new ConcurrentHashMap<>();

    public RunRecordStore(SyncRecordRepository recordRepo, SyncErrorRepository errorRepo, ObjectMapper mapper) {
        this.recordRepo = recordRepo;
        this.errorRepo = errorRepo;
        this.mapper = mapper;
    }

    /**
     * 尝试开始一次执行：占用 {@code RUNNING-{taskId}} 令牌。
     *
     * @return 已落库的记录；返回 {@code null} 表示该任务已有运行中实例（唯一索引冲突）
     */
    public SyncRecordEntity tryStartRun(Long taskId, String triggerType) {
        SyncRecordEntity record = new SyncRecordEntity();
        record.setTaskId(taskId);
        record.setRunKey(SyncRecordEntity.RUNNING_KEY_PREFIX + taskId);
        record.setStartTime(LocalDateTime.now());
        record.setStatus(SyncRecordEntity.STATUS_RUNNING);
        record.setTriggerType(triggerType);
        record.setTotalRows(0L);
        record.setReadRows(0L);
        record.setWriteRows(0L);
        record.setSkippedRows(0L);
        record.setErrorRows(0L);
        try {
            // 本方法刻意不带外层事务：saveAndFlush 自带事务，冲突时不会污染调用方事务
            return recordRepo.saveAndFlush(record);
        } catch (DataIntegrityViolationException e) {
            log.info("任务 {} 已存在运行中的执行记录，本次触发被拒绝", taskId);
            return null;
        }
    }

    /** 进度节流落库（每个 chunk 回调一次，异常只记 debug，绝不影响同步） */
    public void updateProgress(Long recordId, ChunkProgress progress) {
        if (recordId == null || progress == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastProgressFlush.get(recordId);
        if (last != null && now - last < PROGRESS_FLUSH_INTERVAL_MS) {
            return;
        }
        lastProgressFlush.put(recordId, now);
        try {
            recordRepo.updateProgress(recordId, progress.readRows(), progress.writtenRows(), progress.skippedRows());
        } catch (Exception e) {
            log.debug("更新执行进度失败（忽略）：{}", e.getMessage());
        }
    }

    /**
     * 结束一次执行：落状态/统计/游标，并把 run_key 从互斥令牌改写成稳定的幂等键。
     *
     * @param status       RUNNING / COMPLETED / FAILED / CANCELLED
     * @param result       引擎结果，可为 null（例如线程池拒绝、启动阶段异常）
     * @param errorMessage 面向运维的中文错误信息（调用方负责清洗口令）
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishRun(Long recordId, String status, SyncRunResult result, String errorMessage) {
        lastProgressFlush.remove(recordId);
        SyncRecordEntity record = recordRepo.findById(recordId).orElse(null);
        if (record == null) {
            log.warn("执行记录 {} 不存在，无法落库结束状态", recordId);
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        record.setEndTime(now);
        record.setStatus(status);
        if (result != null) {
            record.setReadRows(result.getReadRows());
            record.setWriteRows(result.getWrittenRows());
            record.setSkippedRows(result.getSkippedRows());
            record.setErrorRows(result.getErrors() == null ? 0L : (long) result.getErrors().size());
            record.setStartCursor(truncate(result.getStartCursor(), 255));
            // 只有成功且引擎给出 endCursor 时才记录新水位（不推进 sync_task）
            record.setEndCursor(truncate(result.getEndCursor(), 255));
            record.setReadMillis(result.getReadMillis());
            record.setWriteMillis(result.getWriteMillis());
            record.setTotalMillis(result.getTotalMillis());
            String message = errorMessage != null ? errorMessage : result.getErrorMessage();
            record.setErrorMessage(truncate(message, MAX_MESSAGE_LENGTH));
            if (result.getPreflightIssues() != null && !result.getPreflightIssues().isEmpty()) {
                record.setPreflightJson(toJson(result.getPreflightIssues()));
            }
        } else {
            record.setErrorMessage(truncate(errorMessage, MAX_MESSAGE_LENGTH));
        }
        if (record.getTotalMillis() <= 0 && record.getStartTime() != null) {
            record.setTotalMillis(java.time.Duration.between(record.getStartTime(), now).toMillis());
        }
        // 释放互斥令牌（唯一索引从此只约束这个稳定的幂等键）
        record.setRunKey(record.getTaskId() + "-" + record.getId());
        recordRepo.save(record);
    }

    /** 写入坏行明细（D8），上限由 core 的 ErrorPolicy 控制，这里再做长度保护 */
    @Transactional
    public int saveErrors(Long recordId, Long taskId, List<SyncError> errors) {
        if (errors == null || errors.isEmpty()) {
            return 0;
        }
        List<SyncErrorEntity> entities = new ArrayList<>(errors.size());
        for (SyncError error : errors) {
            SyncErrorEntity entity = new SyncErrorEntity();
            entity.setRecordId(recordId);
            entity.setTaskId(taskId);
            entity.setPhase(truncate(error.getPhase(), 16));
            entity.setRowKey(truncate(error.getRowKey(), 255));
            entity.setMessage(truncate(error.getMessage(), 1000));
            entity.setRowData(truncate(error.getRowData(), MAX_ROW_DATA_LENGTH));
            entity.setRetryable(error.isRetryable());
            entity.setCreatedAt(LocalDateTime.now());
            entities.add(entity);
        }
        errorRepo.saveAll(entities);
        return entities.size();
    }

    /**
     * 启动自愈：把上次进程被 kill 时残留的 RUNNING 记录标记为 FAILED 并释放 run_key 令牌。
     * <b>不推进任何任务水位</b>——未完成的执行必须重跑。
     *
     * @return 自愈的记录数
     */
    @Transactional
    public int recoverOrphanRuns() {
        List<SyncRecordEntity> orphans = recordRepo.findByStatus(SyncRecordEntity.STATUS_RUNNING);
        if (orphans.isEmpty()) {
            return 0;
        }
        for (SyncRecordEntity record : orphans) {
            record.setStatus(SyncRecordEntity.STATUS_FAILED);
            record.setEndTime(LocalDateTime.now());
            record.setErrorMessage(ORPHAN_MESSAGE);
            record.setRunKey(record.getTaskId() + "-" + record.getId());
            if (record.getTotalMillis() <= 0 && record.getStartTime() != null) {
                record.setTotalMillis(java.time.Duration.between(record.getStartTime(), record.getEndTime()).toMillis());
            }
            recordRepo.save(record);
        }
        log.warn("检测到 {} 条上次进程异常退出遗留的 RUNNING 执行记录，已标记为 FAILED 并释放运行令牌（水位未推进）",
            orphans.size());
        return orphans.size();
    }

    public Page<SyncRecordEntity> findRecords(Long taskId, Pageable pageable) {
        return recordRepo.findByTaskIdOrderByStartTimeDesc(taskId, pageable);
    }

    public SyncRecordEntity findRecord(Long recordId) {
        return recordRepo.findById(recordId).orElse(null);
    }

    public Page<SyncErrorEntity> findErrors(Long recordId, String phase, Pageable pageable) {
        if (phase == null || phase.isBlank()) {
            return errorRepo.findByRecordIdOrderByIdAsc(recordId, pageable);
        }
        return errorRepo.findByRecordIdAndPhaseOrderByIdAsc(recordId, phase.trim().toUpperCase(), pageable);
    }

    public long countRecords(Long taskId) {
        return recordRepo.countByTaskId(taskId);
    }

    public long countByStatus(String status) {
        return recordRepo.countByStatus(status);
    }

    public long countAll() {
        return recordRepo.count();
    }

    /** 保留策略：清理过期执行记录与错误明细 */
    @Transactional
    public int purgeRecords(LocalDateTime before) {
        return recordRepo.deleteFinishedBefore(before);
    }

    @Transactional
    public int purgeErrors(LocalDateTime before) {
        return errorRepo.deleteBefore(before);
    }

    private String toJson(List<PreflightIssue> issues) {
        try {
            return mapper.writeValueAsString(issues.stream().map(issue -> Map.of(
                "level", String.valueOf(issue.getLevel()),
                "code", String.valueOf(issue.getCode()),
                "message", String.valueOf(issue.getMessage()),
                "hint", String.valueOf(issue.getHint())
            )).toList());
        } catch (Exception e) {
            return null;
        }
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** 供调用方统一清洗错误消息，避免明文口令落库 */
    public static String scrub(String message, String... secrets) {
        return CredentialCipher.scrub(message, secrets);
    }
}
