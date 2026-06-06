package com.datasync.server.controller;
import com.datasync.server.entity.SyncRecordEntity;
import com.datasync.server.repository.SyncRecordRepository;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api")
public class RecordController {
    private final SyncRecordRepository repo;
    public RecordController(SyncRecordRepository repo) { this.repo = repo; }
    @GetMapping("/tasks/{taskId}/records") List<SyncRecordEntity> records(@PathVariable Long taskId) { return repo.findByTaskIdOrderByStartTimeDesc(taskId); }
    @GetMapping("/records/{id}") SyncRecordEntity get(@PathVariable Long id) { return repo.findById(id).orElseThrow(() -> new RuntimeException("记录不存在: " + id)); }
}