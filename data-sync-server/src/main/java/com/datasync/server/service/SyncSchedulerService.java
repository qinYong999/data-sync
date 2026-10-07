package com.datasync.server.service;

import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.job.SyncQuartzJob;
import com.datasync.server.repository.SyncTaskRepository;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.JobKey;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.quartz.TriggerBuilder;
import org.quartz.TriggerKey;
import org.quartz.impl.matchers.GroupMatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Service;

import java.text.ParseException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Quartz 调度同步服务（契约 §4.1 / 任务书第 8 条）。
 *
 * <p>修掉改造前的两个问题：</p>
 * <ol>
 *   <li>只在"改 cron"时注册，应用重启后调度全部丢失 → 现在启动时把
 *       <b>所有已启用任务</b>重新注册，并清理 QRTZ 里已不属于启用集合的残留 Job；</li>
 *   <li>cron 非法时报 500 → 现在 {@link #validateCron} 给中文 400 提示。</li>
 * </ol>
 *
 * <p>Job 上有 {@code @DisallowConcurrentExecution}，同一任务的触发不会自我重叠；
 * 加上 {@code TaskRunService} 的双重互斥，即使多实例部署也不会重复执行。</p>
 */
@Service
public class SyncSchedulerService {

    private static final Logger log = LoggerFactory.getLogger(SyncSchedulerService.class);

    public static final String GROUP = "syncTasks";

    private final Scheduler scheduler;
    private final SyncTaskRepository taskRepo;

    public SyncSchedulerService(Scheduler scheduler, SyncTaskRepository taskRepo) {
        this.scheduler = scheduler;
        this.taskRepo = taskRepo;
    }

    /**
     * 启动时重注册全部已启用任务（并清理残留）。
     *
     * <p>放在 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：
     * 那时 JPA 表结构与 Quartz JDBC JobStore 都已就绪。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(20)
    public void registerEnabledTasks() {
        List<SyncTaskEntity> tasks = taskRepo.findEnabledSchedulable();
        Set<Long> enabledIds = new HashSet<>();
        int registered = 0;
        int failed = 0;
        for (SyncTaskEntity task : tasks) {
            enabledIds.add(task.getId());
            try {
                registerJob(task.getId(), task.getCronExpression());
                registered++;
            } catch (Exception e) {
                failed++;
                log.error("启动时注册定时任务 {} 失败：{}", task.getId(), e.getMessage());
            }
        }
        int cleaned = cleanStaleJobs(enabledIds);
        log.info("定时调度初始化完成：启用任务 {} 个，注册成功 {} 个，失败 {} 个，清理残留 {} 个",
            tasks.size(), registered, failed, cleaned);
    }

    /** 删除 QRTZ 里已不在启用集合中的残留 Job（任务在停机期间被删除/停用的情况） */
    private int cleanStaleJobs(Set<Long> enabledIds) {
        int cleaned = 0;
        try {
            for (JobKey key : scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP))) {
                Long taskId = parseTaskId(key.getName());
                if (taskId == null || !enabledIds.contains(taskId)) {
                    scheduler.deleteJob(key);
                    cleaned++;
                    log.info("已清理残留定时任务 {}", key.getName());
                }
            }
        } catch (SchedulerException e) {
            log.warn("清理残留定时任务失败：{}", e.getMessage());
        }
        return cleaned;
    }

    /** 注册（或重新注册）一个任务的 Cron 触发；cron 非法抛中文 400 */
    public void registerJob(Long taskId, String cronExpression) {
        validateCron(cronExpression);
        JobKey jobKey = new JobKey(jobName(taskId), GROUP);
        TriggerKey triggerKey = new TriggerKey(triggerName(taskId), GROUP);
        JobDetail jobDetail = JobBuilder.newJob(SyncQuartzJob.class)
            .withIdentity(jobKey)
            .withDescription("同步任务 " + taskId)
            .usingJobData("taskId", taskId)
            .build();
        CronTrigger trigger = TriggerBuilder.newTrigger()
            .withIdentity(triggerKey)
            .withSchedule(CronScheduleBuilder.cronSchedule(cronExpression)
                // 错过的触发不补跑：补跑会造成一连串重叠执行，数据安全靠下次全量/增量保证
                .withMisfireHandlingInstructionDoNothing())
            .forJob(jobDetail)
            .build();
        try {
            if (scheduler.checkExists(jobKey)) {
                // 先删后建，保证 JobDataMap 与触发器状态与当前配置完全一致
                scheduler.deleteJob(jobKey);
            }
            scheduler.scheduleJob(jobDetail, trigger);
            log.info("已注册定时任务 {}：{}", taskId, cronExpression);
        } catch (SchedulerException e) {
            throw new IllegalStateException("注册定时任务失败：" + e.getMessage(), e);
        }
    }

    public void unregisterJob(Long taskId) {
        try {
            JobKey jobKey = new JobKey(jobName(taskId), GROUP);
            if (scheduler.checkExists(jobKey)) {
                scheduler.deleteJob(jobKey);
                log.info("已取消定时任务 {}", taskId);
            }
        } catch (SchedulerException e) {
            log.warn("取消定时任务 {} 失败：{}", taskId, e.getMessage());
        }
    }

    public boolean isRegistered(Long taskId) {
        try {
            return scheduler.checkExists(new JobKey(jobName(taskId), GROUP));
        } catch (SchedulerException e) {
            return false;
        }
    }

    /** 已注册的任务数量 */
    public int registeredCount() {
        try {
            return scheduler.getJobKeys(GroupMatcher.jobGroupEquals(GROUP)).size();
        } catch (SchedulerException e) {
            return 0;
        }
    }

    /** Quartz 语法的 cron 校验；失败给面向运维的中文提示 */
    public static void validateCron(String cron) {
        if (cron == null || cron.isBlank()) {
            throw AppException.badRequest("INVALID_CRON", "Cron 表达式不能为空");
        }
        try {
            new CronExpression(cron.trim());
        } catch (ParseException e) {
            throw AppException.badRequest("INVALID_CRON",
                "Cron 表达式非法：" + cron.trim() + "（Quartz 语法，示例：0 0/5 * * * ? 表示每 5 分钟）");
        }
    }

    public static boolean isValidCron(String cron) {
        if (cron == null || cron.isBlank()) {
            return false;
        }
        try {
            new CronExpression(cron.trim());
            return true;
        } catch (ParseException e) {
            return false;
        }
    }

    public static String jobName(Long taskId) {
        return "syncJob_" + taskId;
    }

    private static String triggerName(Long taskId) {
        return "trigger_" + taskId;
    }

    private static Long parseTaskId(String jobName) {
        if (jobName == null || !jobName.startsWith("syncJob_")) {
            return null;
        }
        try {
            return Long.valueOf(jobName.substring("syncJob_".length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
