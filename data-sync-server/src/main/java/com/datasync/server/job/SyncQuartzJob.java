package com.datasync.server.job;

import com.datasync.server.service.SyncExecutionService;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.quartz.QuartzJobBean;

/**
 * Quartz 定时任务 — 触发同步任务执行
 */
public class SyncQuartzJob extends QuartzJobBean {

    private static final Logger log = LoggerFactory.getLogger(SyncQuartzJob.class);
    private static ApplicationContext applicationContext;

    private Long taskId;

    /** 由 Spring 启动时设置 */
    public static void setApplicationContext(ApplicationContext ctx) {
        applicationContext = ctx;
    }

    public void setTaskId(Long taskId) {
        this.taskId = taskId;
    }

    @Override
    protected void executeInternal(JobExecutionContext context) throws JobExecutionException {
        if (taskId == null) {
            log.warn("定时任务参数不完整: taskId 为空");
            return;
        }
        try {
            SyncExecutionService service = applicationContext.getBean(SyncExecutionService.class);
            log.info("定时触发同步任务 {}", taskId);
            service.executeTask(taskId, "SCHEDULED");
        } catch (Exception e) {
            log.error("定时任务 {} 执行失败", taskId, e);
        }
    }
}
