package com.datasync.server.controller;
import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.model.TaskDTO;
import com.datasync.server.service.SyncTaskService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final SyncTaskService service;
    public TaskController(SyncTaskService service) { this.service = service; }
    @GetMapping List<SyncTaskEntity> list() { return service.findAll(); }
    @GetMapping("/{id}") SyncTaskEntity get(@PathVariable Long id) { return service.findById(id); }
    @PostMapping SyncTaskEntity create(@RequestBody TaskDTO dto) { return service.create(dto); }
    @PutMapping("/{id}") SyncTaskEntity update(@PathVariable Long id, @RequestBody TaskDTO dto) { return service.update(id, dto); }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable Long id) { service.delete(id); return ResponseEntity.ok().build(); }
    @PostMapping("/{id}/enable") ResponseEntity<Void> enable(@PathVariable Long id) { service.enableTask(id); return ResponseEntity.ok().build(); }
    @PostMapping("/{id}/disable") ResponseEntity<Void> disable(@PathVariable Long id) { service.disableTask(id); return ResponseEntity.ok().build(); }
    @PutMapping("/{id}/schedule") SyncTaskEntity schedule(@PathVariable Long id, @RequestBody Map<String,String> body) { return service.updateSchedule(id, body.get("cronExpression")); }
}