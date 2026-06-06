package com.datasync.core.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

public class SyncJobListener implements JobExecutionListener {
    private static final Logger log = LoggerFactory.getLogger(SyncJobListener.class);

    @Override
    public void beforeJob(JobExecution jobExecution) {
        String msg = "???? [" + jobExecution.getJobInstance().getJobName() + "] ????";
        log.info(msg);
        SyncEventBus.publish(msg);
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        BatchStatus status = jobExecution.getStatus();
        long duration = java.time.Duration.between(jobExecution.getStartTime(), jobExecution.getEndTime()).toMillis();
        String msg = "???? [" + jobExecution.getJobInstance().getJobName() + "] ???????: " + status + "???: " + duration + "ms";
        log.info(msg);
        SyncEventBus.publish(msg);
    }
}
