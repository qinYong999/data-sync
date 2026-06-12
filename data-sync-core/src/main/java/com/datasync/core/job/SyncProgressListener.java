package com.datasync.core.job;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;

/**
 * 同步进度监听器 — 每处理一定数量的 chunk 后推送进度到 WebSocket
 */
public class SyncProgressListener implements ChunkListener {

    private static final Logger log = LoggerFactory.getLogger(SyncProgressListener.class);
    private static final int REPORT_INTERVAL = 10; // 每 10 个 chunk 报告一次

    private int chunkCount = 0;
    private long lastReportTime = System.currentTimeMillis();

    @Override
    public void beforeChunk(ChunkContext context) {
        // 不做操作
    }

    @Override
    public void afterChunk(ChunkContext context) {
        chunkCount++;
        if (chunkCount % REPORT_INTERVAL == 0) {
            long now = System.currentTimeMillis();
            long elapsed = now - lastReportTime;
            lastReportTime = now;
            long readCount = context.getStepContext().getStepExecution().getReadCount();
            long writeCount = context.getStepContext().getStepExecution().getWriteCount();
            String stepName = context.getStepContext().getStepName();

            String msg = "  · 进度 [" + stepName + "] 已读取 " + readCount + " 行, 已写入 " + writeCount + " 行"
                + " (最近 " + REPORT_INTERVAL + " 批耗时 " + elapsed + "ms)";
            log.info(msg);
            SyncEventBus.publish(msg);
        }
    }

    @Override
    public void afterChunkError(ChunkContext context) {
        String stepName = context.getStepContext().getStepName();
        String msg = "  · 警告 [" + stepName + "] 批次处理出错，正在进行重试...";
        log.warn(msg);
        SyncEventBus.publish(msg);
    }
}
