package com.datasync.server.controller;

import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.model.TaskDTO;
import com.datasync.server.service.DataSourceService;
import com.datasync.server.service.PreflightService;
import com.datasync.server.service.SyncTaskService;
import com.datasync.server.service.TaskRunService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务接口（契约 §4.3）。
 *
 * <p>新增：{@code GET /{id}/preflight}（写数据之前的预检）与 {@code POST /{id}/cancel}
 * （协作式取消运行中任务，未运行时返回 409 {@code TASK_NOT_RUNNING}）。</p>
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

    private final SyncTaskService taskService;
    private final DataSourceService dsService;
    private final TaskRunService taskRunService;
    private final PreflightService preflightService;

    public TaskController(SyncTaskService taskService, DataSourceService dsService,
                          TaskRunService taskRunService, PreflightService preflightService) {
        this.taskService = taskService;
        this.dsService = dsService;
        this.taskRunService = taskRunService;
        this.preflightService = preflightService;
    }

    @GetMapping
    public Page<SyncTaskEntity> list(Pageable pageable) {
        return taskService.findAll(pageable);
    }

    @GetMapping("/{id}")
    public SyncTaskEntity get(@PathVariable Long id) {
        return taskService.findById(id);
    }

    @PostMapping
    public SyncTaskEntity create(@RequestBody TaskDTO dto) {
        return taskService.create(dto);
    }

    @PutMapping("/{id}")
    public SyncTaskEntity update(@PathVariable Long id, @RequestBody TaskDTO dto) {
        return taskService.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        taskService.delete(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/enable")
    public SyncTaskEntity enable(@PathVariable Long id) {
        return taskService.enableTask(id);
    }

    @PostMapping("/{id}/disable")
    public SyncTaskEntity disable(@PathVariable Long id) {
        return taskService.disableTask(id);
    }

    @PutMapping("/{id}/schedule")
    public SyncTaskEntity schedule(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return taskService.updateSchedule(id, body.get("cronExpression"));
    }

    /**
     * 手动触发。保持既有响应形状（HTTP 200 + {success,message}），
     * 被互斥拒绝时 success=false 且 code=TASK_RUNNING，不抛 500。
     */
    @PostMapping("/{id}/trigger")
    public ResponseEntity<Map<String, Object>> trigger(@PathVariable Long id) {
        TaskRunService.TriggerResult result;
        try {
            result = taskRunService.triggerManual(id);
        } catch (AppException e) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("success", false);
            body.put("code", e.getCode());
            body.put("message", e.getMessage());
            // 资源不存在 → 404；线程池繁忙 → 503；其余按业务状态码
            return ResponseEntity.status(e.getStatus()).body(body);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", result.accepted());
        body.put("code", result.code());
        body.put("message", result.message());
        body.put("recordId", result.recordId());
        return ResponseEntity.ok(body);
    }

    /** 取消运行中的任务；未运行时 409 */
    @PostMapping("/{id}/cancel")
    public Map<String, Object> cancel(@PathVariable Long id) {
        SyncTaskEntity task = taskService.findById(id);
        Long recordId = taskRunService.runningRecordId(id);
        boolean cancelled = taskRunService.cancel(id);
        if (!cancelled) {
            throw AppException.conflict("TASK_NOT_RUNNING",
                "任务 [" + task.getName() + "] 当前未在运行，无需取消");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("taskId", id);
        body.put("recordId", recordId);
        body.put("message", "已请求取消运行中的任务");
        return body;
    }

    /** 预检：不写任何数据，只回报问题清单（含 code 供前端出修复建议） */
    @GetMapping("/{id}/preflight")
    public PreflightService.PreflightResult preflight(@PathVariable Long id) {
        return preflightService.check(id);
    }

    /** 获取源/目标表字段信息 */
    @GetMapping("/{id}/columns")
    public Map<String, List<Map<String, Object>>> getColumns(@PathVariable Long id) {
        SyncTaskEntity task = taskService.findById(id);
        return Map.of(
            "sourceColumns", dsService.getTableColumns(task.getSourceDsId(), task.getSourceTable()),
            "targetColumns", dsService.getTableColumns(task.getTargetDsId(), task.getTargetTable()));
    }
}
