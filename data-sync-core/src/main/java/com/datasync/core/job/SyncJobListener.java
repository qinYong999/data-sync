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
        // 不做额外操作，由 SyncExecutionService 发送开始消息
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        BatchStatus status = jobExecution.getStatus();
        long durationMs = java.time.Duration.between(jobExecution.getStartTime(), jobExecution.getEndTime()).toMillis();
        String durationStr = formatDuration(durationMs);

        // 汇总各步骤统计
        long totalRead = 0, totalWrite = 0, totalSkip = 0, totalCommit = 0;
        StringBuilder stepDetail = new StringBuilder();
        for (var step : jobExecution.getStepExecutions()) {
            totalRead += step.getReadCount();
            totalWrite += step.getWriteCount();
            totalSkip += step.getSkipCount();
            totalCommit += step.getCommitCount();
            stepDetail.append(" [").append(step.getStepName())
                .append(" 读:").append(step.getReadCount())
                .append(" 写:").append(step.getWriteCount())
                .append(" 提交:").append(step.getCommitCount())
                .append("]");
        }

        // 构建状态消息
        boolean completed = status == BatchStatus.COMPLETED;
        String statusIcon = completed ? "✓" : "✗";
        String statusText = completed ? "已完成" : "失败(" + status + ")";

        StringBuilder msg = new StringBuilder();
        msg.append(statusIcon).append(" 同步任务 [").append(jobName).append("] ").append(statusText);
        msg.append(" | 耗时: ").append(durationStr);
        msg.append(" | 读取: ").append(totalRead).append(" 行");
        msg.append(" | 写入: ").append(totalWrite).append(" 行");

        // 增量同步且写入为0时提示数据无变化
        if (completed && totalWrite == 0) {
            msg.append(" | 源数据无变更，跳过写入");
        }
        // 全量同步时提示已清空重写
        if (completed && totalWrite > 0 && totalWrite == totalRead) {
            msg.append(" (全量重写)");
        }

        if (totalSkip > 0) {
            msg.append(" | 跳过: ").append(totalSkip).append(" 行");
        }

        log.info(msg.toString());
        log.debug("步骤明细:{}", stepDetail);
        SyncEventBus.publish(msg.toString());
    }

    private static String formatDuration(long ms) {
        if (ms < 1000) return ms + "ms";
        long sec = ms / 1000;
        if (sec < 60) return sec + "s " + (ms % 1000) + "ms";
        long min = sec / 60;
        return min + "m " + (sec % 60) + "s";
    }
}
