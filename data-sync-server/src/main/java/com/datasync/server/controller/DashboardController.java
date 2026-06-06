package com.datasync.server.controller;
import com.datasync.server.entity.SyncRecordEntity;
import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.model.DashboardVO;
import com.datasync.server.repository.SyncRecordRepository;
import com.datasync.server.repository.SyncTaskRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final SyncTaskRepository taskRepo;
    private final SyncRecordRepository recordRepo;
    public DashboardController(SyncTaskRepository taskRepo, SyncRecordRepository recordRepo) { this.taskRepo = taskRepo; this.recordRepo = recordRepo; }
    @GetMapping("/overview") DashboardVO overview() {
        List<SyncTaskEntity> tasks = taskRepo.findAll();
        List<SyncRecordEntity> records = recordRepo.findAll();
        DashboardVO vo = new DashboardVO();
        vo.setTotalTasks(tasks.size());
        vo.setRunningTasks(tasks.stream().filter(t -> "ENABLED".equals(t.getStatus())).count());
        vo.setFailedTasks(records.stream().filter(r -> "FAILED".equals(r.getStatus())).count());
        vo.setSuccessTasks(records.stream().filter(r -> "SUCCESS".equals(r.getStatus())).count());
        vo.setTotalRecords(records.size());
        vo.setTotalReadRows(records.stream().mapToLong(SyncRecordEntity::getReadRows).sum());
        return vo;
    }
    @GetMapping("/recent-fails") List<SyncRecordEntity> recentFails() {
        return recordRepo.findAll().stream()
            .filter(r -> "FAILED".equals(r.getStatus()))
            .sorted((a,b) -> b.getStartTime().compareTo(a.getStartTime()))
            .limit(20).toList();
    }
}