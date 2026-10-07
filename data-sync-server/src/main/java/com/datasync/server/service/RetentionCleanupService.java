package com.datasync.server.service;

import com.datasync.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 元数据保留策略（契约 §4.4 的 {@code app.retention.*}）。
 *
 * <p>执行记录与坏行明细会随时间无限膨胀，最终拖垮元数据库。
 * 清理只删"已结束"的记录（{@code RUNNING} 永不删），时间点由 cron 配置。</p>
 */
@Service
public class RetentionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupService.class);

    private final RunRecordStore recordStore;
    private final AppProperties properties;

    public RetentionCleanupService(RunRecordStore recordStore, AppProperties properties) {
        this.recordStore = recordStore;
        this.properties = properties;
    }

    @Scheduled(cron = "${app.retention.cleanup-cron:0 30 3 * * ?}")
    public void cleanup() {
        AppProperties.Retention retention = properties.getRetention();
        if (!retention.isEnabled()) {
            return;
        }
        try {
            int errors = retention.getErrorDays() > 0
                ? recordStore.purgeErrors(LocalDateTime.now().minusDays(retention.getErrorDays())) : 0;
            int records = retention.getRecordDays() > 0
                ? recordStore.purgeRecords(LocalDateTime.now().minusDays(retention.getRecordDays())) : 0;
            if (records > 0 || errors > 0) {
                log.info("保留策略清理完成：执行记录 {} 条（>{} 天），错误明细 {} 条（>{} 天）",
                    records, retention.getRecordDays(), errors, retention.getErrorDays());
            }
        } catch (Exception e) {
            log.error("保留策略清理失败", e);
        }
    }
}
