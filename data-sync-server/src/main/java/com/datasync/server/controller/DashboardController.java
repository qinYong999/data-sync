package com.datasync.server.controller;

import com.datasync.server.entity.SyncRecordEntity;
import com.datasync.server.model.DashboardVO;
import com.datasync.server.repository.SyncRecordRepository;
import com.datasync.server.repository.SyncTaskRepository;
import com.datasync.server.service.TaskRunService;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 仪表盘统计。
 *
 * <p>修掉两个既有 bug：</p>
 * <ol>
 *   <li>成功数按 {@code "SUCCESS"} 统计，而库里其实是引擎原样状态 {@code COMPLETED}，恒为 0；</li>
 *   <li>{@code runningTasks} 原来统计的是 enabled=true 的任务数，与"正在运行"完全无关。</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private static final List<String> SUCCESS_STATUSES = List.of("COMPLETED", "SUCCESS");

    private final SyncTaskRepository taskRepo;
    private final SyncRecordRepository recordRepo;
    private final TaskRunService taskRunService;

    public DashboardController(SyncTaskRepository taskRepo, SyncRecordRepository recordRepo,
                               TaskRunService taskRunService) {
        this.taskRepo = taskRepo;
        this.recordRepo = recordRepo;
        this.taskRunService = taskRunService;
    }

    @GetMapping("/overview")
    public DashboardVO overview() {
        DashboardVO vo = new DashboardVO();
        vo.setTotalTasks(taskRepo.count());
        vo.setRunningTasks(taskRunService.runningCount());
        vo.setFailedTasks(recordRepo.countByStatus(SyncRecordEntity.STATUS_FAILED));
        vo.setSuccessTasks(SUCCESS_STATUSES.stream().mapToLong(recordRepo::countByStatus).sum());
        vo.setTotalRecords(recordRepo.count());
        vo.setTotalReadRows(recordRepo.sumReadRows());
        return vo;
    }

    @GetMapping("/recent-fails")
    public List<SyncRecordEntity> recentFails() {
        return recordRepo.findByStatusOrderByStartTimeDesc(SyncRecordEntity.STATUS_FAILED, PageRequest.of(0, 20));
    }
}
