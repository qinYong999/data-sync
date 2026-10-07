package com.datasync.server.service;

import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.preflight.Preflighter;
import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.repository.SyncTaskRepository;
import com.datasync.server.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 预检服务（D5 / 契约 §4.3 的 {@code GET /api/tasks/{id}/preflight}）。
 *
 * <p>与引擎执行路径共用同一个（带 {@code MetadataGuard} 的）{@link Preflighter}，
 * 保证"界面上预检通过"与"引擎执行时的预检"是同一套判定。</p>
 *
 * <p>数据源连不上、元数据读不到这类"预检本身完不成"的情况，返回
 * {@code PREFLIGHT_FAILED} 问题项而不是抛 500 —— 运维需要的是可读原因，不是白屏。</p>
 */
@Service
public class PreflightService {

    private static final Logger log = LoggerFactory.getLogger(PreflightService.class);

    private final Preflighter preflighter;
    private final SyncTaskRepository taskRepo;
    private final ConnectionPoolRegistry poolRegistry;
    private final SyncTaskConfigMapper configMapper;

    public PreflightService(Preflighter preflighter, SyncTaskRepository taskRepo,
                            ConnectionPoolRegistry poolRegistry, SyncTaskConfigMapper configMapper) {
        this.preflighter = preflighter;
        this.taskRepo = taskRepo;
        this.poolRegistry = poolRegistry;
        this.configMapper = configMapper;
    }

    public PreflightResult check(Long taskId) {
        SyncTaskEntity task = taskRepo.findById(taskId)
            .orElseThrow(() -> AppException.notFound("任务不存在: " + taskId));
        SyncTaskConfig config = configMapper.toConfig(task);
        try {
            List<PreflightIssue> issues = preflighter.check(config,
                poolRegistry.get(task.getSourceDsId()), poolRegistry.get(task.getTargetDsId()));
            return PreflightResult.of(issues);
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            String reason = CredentialCipher.scrub(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("任务 {} 预检无法完成：{}", taskId, reason);
            return new PreflightResult(true, List.of(new PreflightResult.IssueView("ERROR", "PREFLIGHT_FAILED",
                "预检无法完成：" + reason, "请检查源/目标数据源配置、网络连通性与账号权限后重试")));
        }
    }

    /** 预检响应体：{ hasError, issues:[{level,code,message,hint}] } */
    public record PreflightResult(boolean hasError, List<PreflightResult.IssueView> issues) {

        public record IssueView(String level, String code, String message, String hint) { }

        public static PreflightResult of(List<PreflightIssue> issues) {
            List<IssueView> views = new ArrayList<>();
            boolean hasError = false;
            if (issues != null) {
                for (PreflightIssue issue : issues) {
                    String level = issue.getLevel() == null ? "WARN" : issue.getLevel().name();
                    if ("ERROR".equals(level)) {
                        hasError = true;
                    }
                    views.add(new IssueView(level, issue.getCode(), issue.getMessage(), issue.getHint()));
                }
            }
            return new PreflightResult(hasError, views);
        }
    }
}
