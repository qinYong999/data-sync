package com.datasync.core.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Job 执行监听器 — 记录开始和结束日志
 */
public class SyncJobListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(SyncJobListener.class);

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("同步任务 [{}] 开始执行", jobExecution.getJobInstance().getJobName());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        BatchStatus status = jobExecution.getStatus();
        long duration = java.time.Duration.between(
            jobExecution.getStartTime(), jobExecution.getEndTime()).toMillis();
        log.info("同步任务 [{}] 执行完毕，状态: {}，耗时: {}ms",
            jobExecution.getJobInstance().getJobName(), status, duration);
    }
}