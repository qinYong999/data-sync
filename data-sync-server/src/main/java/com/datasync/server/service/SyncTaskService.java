package com.datasync.server.service;

import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.model.TaskDTO;
import com.datasync.server.repository.DataSourceRepository;
import com.datasync.server.repository.SyncTaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SyncTaskService {
    private final SyncTaskRepository taskRepo;
    private final DataSourceRepository dsRepo;
    private final SyncSchedulerService schedulerService;
    private final ObjectMapper mapper = new ObjectMapper();

    public SyncTaskService(SyncTaskRepository taskRepo, DataSourceRepository dsRepo,
                           SyncSchedulerService schedulerService) {
        this.taskRepo = taskRepo; this.dsRepo = dsRepo;
        this.schedulerService = schedulerService;
    }

    public Page<SyncTaskEntity> findAll(Pageable pageable) { return taskRepo.findAll(pageable); }
    public SyncTaskEntity findById(Long id) {
        return taskRepo.findById(id).orElseThrow(() -> new RuntimeException("任务不存在: " + id));
    }

    @Transactional
    public SyncTaskEntity create(TaskDTO dto) {
        dsRepo.findById(dto.getSourceDsId()).orElseThrow(() -> new RuntimeException("源数据源不存在: " + dto.getSourceDsId()));
        dsRepo.findById(dto.getTargetDsId()).orElseThrow(() -> new RuntimeException("目标数据源不存在: " + dto.getTargetDsId()));
        SyncTaskEntity entity = taskRepo.save(toEntity(dto));
        // 创建时如果已启用且有cron表达式，注册定时任务
        if ("ENABLED".equals(entity.getStatus()) && hasCron(entity)) {
            schedulerService.registerJob(entity.getId(), entity.getCronExpression());
        }
        return entity;
    }

    @Transactional
    public SyncTaskEntity update(Long id, TaskDTO dto) {
        SyncTaskEntity e = findById(id);
        boolean cronChanged = dto.getCronExpression() != null
            && !dto.getCronExpression().equals(e.getCronExpression());
        e.setName(dto.getName()); e.setSourceDsId(dto.getSourceDsId()); e.setTargetDsId(dto.getTargetDsId());
        e.setSourceTable(dto.getSourceTable()); e.setTargetTable(dto.getTargetTable());
        e.setSyncMode(dto.getSyncMode()); e.setIncrColumn(dto.getIncrColumn()); e.setIncrValue(dto.getIncrValue());
        e.setCronExpression(dto.getCronExpression());
        if (dto.getSourceMode() != null) e.setSourceMode(dto.getSourceMode());
        if (dto.getSourceSql() != null) e.setSourceSql(dto.getSourceSql());
        if (dto.getPageSize() != null) e.setPageSize(dto.getPageSize());
        if (dto.getBatchSize() != null) e.setBatchSize(dto.getBatchSize());
        if (dto.getFieldMappings() != null) try {
            e.setMappingJson(mapper.writeValueAsString(dto.getFieldMappings()));
        } catch (JsonProcessingException ex) { throw new RuntimeException("字段映射JSON序列化失败", ex); }
        SyncTaskEntity saved = taskRepo.save(e);
        // 重新调度
        if (cronChanged) {
            if ("ENABLED".equals(saved.getStatus()) && hasCron(saved)) {
                schedulerService.registerJob(saved.getId(), saved.getCronExpression());
            } else {
                schedulerService.unregisterJob(saved.getId());
            }
        }
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        schedulerService.unregisterJob(id);
        taskRepo.deleteById(id);
    }

    @Transactional
    public void enableTask(Long id) {
        SyncTaskEntity e = findById(id);
        e.setStatus("ENABLED");
        taskRepo.save(e);
        if (hasCron(e)) {
            schedulerService.registerJob(e.getId(), e.getCronExpression());
        }
    }

    @Transactional
    public void disableTask(Long id) {
        SyncTaskEntity e = findById(id);
        e.setStatus("DISABLED");
        taskRepo.save(e);
        schedulerService.unregisterJob(id);
    }

    @Transactional
    public SyncTaskEntity updateSchedule(Long id, String cron) {
        SyncTaskEntity e = findById(id);
        e.setCronExpression(cron);
        SyncTaskEntity saved = taskRepo.save(e);
        if ("ENABLED".equals(saved.getStatus()) && hasCron(saved)) {
            schedulerService.registerJob(saved.getId(), cron);
        } else {
            schedulerService.unregisterJob(id);
        }
        return saved;
    }

    private boolean hasCron(SyncTaskEntity e) {
        return e.getCronExpression() != null && !e.getCronExpression().isBlank();
    }

    private SyncTaskEntity toEntity(TaskDTO dto) {
        SyncTaskEntity e = new SyncTaskEntity();
        e.setName(dto.getName()); e.setSourceDsId(dto.getSourceDsId()); e.setTargetDsId(dto.getTargetDsId());
        e.setSourceTable(dto.getSourceTable()); e.setTargetTable(dto.getTargetTable());
        e.setSyncMode(dto.getSyncMode()); e.setIncrColumn(dto.getIncrColumn()); e.setIncrValue(dto.getIncrValue());
        e.setCronExpression(dto.getCronExpression());
        if (dto.getSourceMode() != null) e.setSourceMode(dto.getSourceMode());
        if (dto.getSourceSql() != null) e.setSourceSql(dto.getSourceSql());
        if (dto.getPageSize() != null) e.setPageSize(dto.getPageSize());
        if (dto.getBatchSize() != null) e.setBatchSize(dto.getBatchSize());
        if (dto.getFieldMappings() != null) try {
            e.setMappingJson(mapper.writeValueAsString(dto.getFieldMappings()));
        } catch (JsonProcessingException ex) { throw new RuntimeException("字段映射JSON序列化失败", ex); }
        return e;
    }
}
