package com.datasync.server.job;

import com.datasync.server.service.TaskRunService;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz 定时任务 — 触发同步任务执行。
 *
 * <p>{@link DisallowConcurrentExecution} 保证同一个任务（同一 JobKey）的两次触发不会重叠：
 * Quartz 会把该标记持久化到 {@code QRTZ_JOB_DETAILS.IS_NONCONCURRENT}，
 * 即使多实例共享 JDBC JobStore 也生效。</p>
 *
 * <p>作业执行异常一律自行吞掉并记日志：抛回 Quartz 只会触发无意义的恢复语义，
 * 真正的失败信息已经落在 {@code sync_record} 里。</p>
 */
@DisallowConcurrentExecution
public class SyncQuartzJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(SyncQuartzJob.class);

    private static ApplicationContext applicationContext;

    private Long taskId;

    /** 由 {@code QuartzConfig} 在启动时注入 */
    public static void setApplicationContext(ApplicationContext context) {
        applicationContext = context;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        if (taskId == null) {
            log.warn("定时任务参数不完整：taskId 为空，已跳过本次触发");
            return;
        }
        if (applicationContext == null) {
            log.error("定时任务 {} 无法执行：Spring 上下文尚未注入", taskId);
            return;
        }
        try {
            TaskRunService taskRunService = applicationContext.getBean(TaskRunService.class);
            TaskRunService.TriggerResult result = taskRunService.triggerScheduled(taskId);
            if (result.accepted()) {
                log.info("定时触发同步任务 {} 已受理（record {}）", taskId, result.recordId());
            } else {
                log.info("定时触发同步任务 {} 未被受理：{}（{}）", taskId, result.message(), result.code());
            }
        } catch (Exception e) {
            log.error("定时任务 {} 执行异常", taskId, e);
        }
    }
}
