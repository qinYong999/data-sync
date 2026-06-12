package com.datasync.core.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * 同步任务 Job 生命周期监听器 — 在任务开始和结束时通过日志和 WebSocket 推送消息
 */
public class SyncJobListener implements JobExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(SyncJobListener.class);

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        String msg = "▶ 同步任务 [" + jobName + "] 开始执行";
        log.info(msg);
        SyncEventBus.publish(msg);
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        BatchStatus status = jobExecution.getStatus();
        long durationMs = java.time.Duration.between(jobExecution.getStartTime(), jobExecution.getEndTime()).toMillis();
        String durationStr = formatDuration(durationMs);

        // 汇总各步骤统计
        long totalRead = 0, totalWrite = 0, totalSkip = 0;
        StringBuilder stepDetail = new StringBuilder();
        for (var step : jobExecution.getStepExecutions()) {
            totalRead += step.getReadCount();
            totalWrite += step.getWriteCount();
            totalSkip += step.getSkipCount();
            stepDetail.append(" [").append(step.getStepName())
                .append(" 读:").append(step.getReadCount())
                .append(" 写:").append(step.getWriteCount())
                .append("]");
        }

        String statusEmoji = status == BatchStatus.COMPLETED ? "✓" : "✗";
        String msg = statusEmoji + " 同步任务 [" + jobName + "] "
            + (status == BatchStatus.COMPLETED ? "已完成" : "失败: " + status)
            + " | 耗时: " + durationStr
            + " | 读取: " + totalRead + " 行"
            + " | 写入: " + totalWrite + " 行"
            + (totalSkip > 0 ? " | 跳过: " + totalSkip + " 行" : "");

        log.info(msg);
        log.info("步骤明细:{}", stepDetail);
        SyncEventBus.publish(msg);
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long sec = ms / 1000;
        if (sec < 60) return sec + "s " + (ms % 1000) + "ms";
        long min = sec / 60;
        return min + "m " + (sec % 60) + "s";
    }
}
