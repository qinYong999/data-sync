package com.datasync.server.service;

import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.job.SyncQuartzJob;
import com.datasync.server.repository.SyncTaskRepository;
import jakarta.annotation.PostConstruct;
import org.quartz.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 同步任务定时调度服务 — 管理 Quartz 触发器
 */
@Service
public class SyncSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SyncSchedulerService.class);
    private static final String GROUP = "syncTasks";

    private final Scheduler scheduler;
    private final SyncTaskRepository taskRepo;

    public SyncSchedulerService(Scheduler scheduler, SyncTaskRepository taskRepo) {
        this.scheduler = scheduler;
        this.taskRepo = taskRepo;
    }

    /** 启动时加载所有已启用的定时任务 */
    @PostConstruct
    public void init() {
        try {
            List<SyncTaskEntity> enabledTasks = taskRepo.findByStatusAndCronExpressionIsNotNull("ENABLED");
            for (SyncTaskEntity task : enabledTasks) {
                if (task.getCronExpression() != null && !task.getCronExpression().isBlank()) {
                    registerJob(task.getId(), task.getCronExpression());
                }
            }
            log.info("定时调度初始化完成，已加载 {} 个定时任务", enabledTasks.size());
        } catch (Exception e) {
            log.error("定时调度初始化失败", e);
        }
    }

    /** 注册定时任务 */
    public void registerJob(Long taskId, String cronExpression) {
        try {
            JobKey jobKey = new JobKey("syncJob_" + taskId, GROUP);
            if (scheduler.checkExists(jobKey)) {
                // 更新触发器
                TriggerKey triggerKey = new TriggerKey("trigger_" + taskId, GROUP);
                CronTrigger newTrigger = TriggerBuilder.newTrigger()
                        .withIdentity(triggerKey)
                        .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                        .forJob(jobKey)
                        .build();
                scheduler.rescheduleJob(triggerKey, newTrigger);
                log.info("更新定时任务 {}: {}", taskId, cronExpression);
                return;
            }

            // 新建 Job
            JobDetail jobDetail = JobBuilder.newJob(SyncQuartzJob.class)
                    .withIdentity(jobKey)
                    .usingJobData("taskId", taskId)
                    .build();

            CronTrigger trigger = TriggerBuilder.newTrigger()
                    .withIdentity("trigger_" + taskId, GROUP)
                    .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression))
                    .forJob(jobKey)
                    .build();

            scheduler.scheduleJob(jobDetail, trigger);
            log.info("注册定时任务 {}: {}", taskId, cronExpression);
        } catch (Exception e) {
            log.error("注册定时任务 {} 失败", taskId, e);
        }
    }

    /** 取消定时任务 */
    public void unregisterJob(Long taskId) {
        try {
            JobKey jobKey = new JobKey("syncJob_" + taskId, GROUP);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("取消定时任务 {}", taskId);
            }
        } catch (Exception e) {
            log.error("取消定时任务 {} 失败", taskId, e);
        }
    }

    /** 检查是否已注册 */
    public boolean isRegistered(Long taskId) {
        try {
            return scheduler.checkExists(new JobKey("syncJob_" + taskId, GROUP));
        } catch (Exception e) {
            return false;
        }
    }
}
