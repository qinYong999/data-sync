package com.datasync.server.service;
import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.model.TaskDTO;
import com.datasync.server.repository.DataSourceRepository;
import com.datasync.server.repository.SyncTaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import java.util.List;
@Service
public class SyncTaskService {
    private final SyncTaskRepository taskRepo;
    private final DataSourceRepository dsRepo;
    private final ObjectMapper mapper = new ObjectMapper();
    public SyncTaskService(SyncTaskRepository taskRepo, DataSourceRepository dsRepo) { this.taskRepo = taskRepo; this.dsRepo = dsRepo; }
    public List<SyncTaskEntity> findAll() { return taskRepo.findAll(); }
    public SyncTaskEntity findById(Long id) { return taskRepo.findById(id).orElseThrow(() -> new RuntimeException("任务不存在: " + id)); }
    public SyncTaskEntity create(TaskDTO dto) {
        dsRepo.findById(dto.getSourceDsId()).orElseThrow(() -> new RuntimeException("源数据源不存在: " + dto.getSourceDsId()));
        dsRepo.findById(dto.getTargetDsId()).orElseThrow(() -> new RuntimeException("目标数据源不存在: " + dto.getTargetDsId()));
        return taskRepo.save(toEntity(dto));
    }
    public SyncTaskEntity update(Long id, TaskDTO dto) {
        SyncTaskEntity e = findById(id);
        e.setName(dto.getName()); e.setSourceDsId(dto.getSourceDsId()); e.setTargetDsId(dto.getTargetDsId());
        e.setSourceTable(dto.getSourceTable()); e.setTargetTable(dto.getTargetTable());
        e.setSyncMode(dto.getSyncMode()); e.setIncrColumn(dto.getIncrColumn()); e.setIncrValue(dto.getIncrValue());
        e.setCronExpression(dto.getCronExpression());
        if (dto.getPageSize() != null) e.setPageSize(dto.getPageSize());
        if (dto.getBatchSize() != null) e.setBatchSize(dto.getBatchSize());
        if (dto.getFieldMappings() != null) try { e.setMappingJson(mapper.writeValueAsString(dto.getFieldMappings())); } catch (JsonProcessingException ex) { throw new RuntimeException("序列化失败", ex); }
        return taskRepo.save(e);
    }
    public void delete(Long id) { taskRepo.deleteById(id); }
    public void enableTask(Long id) { SyncTaskEntity e = findById(id); e.setStatus("ENABLED"); taskRepo.save(e); }
    public void disableTask(Long id) { SyncTaskEntity e = findById(id); e.setStatus("DISABLED"); taskRepo.save(e); }
    public SyncTaskEntity updateSchedule(Long id, String cron) { SyncTaskEntity e = findById(id); e.setCronExpression(cron); return taskRepo.save(e); }
    private SyncTaskEntity toEntity(TaskDTO dto) {
        SyncTaskEntity e = new SyncTaskEntity();
        e.setName(dto.getName()); e.setSourceDsId(dto.getSourceDsId()); e.setTargetDsId(dto.getTargetDsId());
        e.setSourceTable(dto.getSourceTable()); e.setTargetTable(dto.getTargetTable());
        e.setSyncMode(dto.getSyncMode()); e.setIncrColumn(dto.getIncrColumn()); e.setIncrValue(dto.getIncrValue());
        e.setCronExpression(dto.getCronExpression());
        if (dto.getPageSize() != null) e.setPageSize(dto.getPageSize());
        if (dto.getBatchSize() != null) e.setBatchSize(dto.getBatchSize());
        if (dto.getFieldMappings() != null) try { e.setMappingJson(mapper.writeValueAsString(dto.getFieldMappings())); } catch (JsonProcessingException ex) { throw new RuntimeException("序列化失败", ex); }
        return e;
    }
}