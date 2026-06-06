package com.datasync.server.controller;

import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.model.TaskDTO;
import com.datasync.server.service.DataSourceService;
import com.datasync.server.service.SyncTaskService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final SyncTaskService taskService;
    private final DataSourceService dsService;

    public TaskController(SyncTaskService taskService, DataSourceService dsService) {
        this.taskService = taskService;
        this.dsService = dsService;
    }

    @GetMapping
    public Page<SyncTaskEntity> list(Pageable pageable) {
        return taskService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public SyncTaskEntity get(@PathVariable Long id) { return taskService.findById(id); }

    @PostMapping
    public SyncTaskEntity create(@RequestBody TaskDTO dto) { return taskService.create(dto); }

    @PutMapping("/{id}")
    public SyncTaskEntity update(@PathVariable Long id, @RequestBody TaskDTO dto) { return taskService.update(id, dto); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { taskService.delete(id); return ResponseEntity.ok().build(); }

    @PostMapping("/{id}/enable")
    public ResponseEntity<Void> enable(@PathVariable Long id) { taskService.enableTask(id); return ResponseEntity.ok().build(); }

    @PostMapping("/{id}/disable")
    public ResponseEntity<Void> disable(@PathVariable Long id) { taskService.disableTask(id); return ResponseEntity.ok().build(); }

    @PutMapping("/{id}/schedule")
    public SyncTaskEntity schedule(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return taskService.updateSchedule(id, body.get("cronExpression"));
    }

    /** ???????????? */
    @GetMapping("/{id}/columns")
    public Map<String, List<Map<String, Object>>> getColumns(@PathVariable Long id) {
        SyncTaskEntity task = taskService.findById(id);
        var srcCols = dsService.getTableColumns(task.getSourceDsId(), task.getSourceTable());
        var tgtCols = dsService.getTableColumns(task.getTargetDsId(), task.getTargetTable());
        return Map.of("sourceColumns", srcCols, "targetColumns", tgtCols);
    }
}
