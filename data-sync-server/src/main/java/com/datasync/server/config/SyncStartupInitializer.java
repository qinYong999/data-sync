package com.datasync.server.config;

import com.datasync.server.security.LegacyPasswordUpgrader;
import com.datasync.server.service.RunRecordStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动自检与自愈（D11 / §5.10）。
 *
 * <p>进程被 kill、容器被 OOM 杀掉之后，{@code sync_record} 里会残留
 * {@code status='RUNNING'}、{@code run_key='RUNNING-{taskId}'} 的记录。
 * 因为 run_key 上有唯一索引，这条残留会<b>永久占住互斥令牌</b>，让该任务再也触发不了。
 * 因此启动时必须：</p>
 * <ol>
 *   <li>把残留 RUNNING 标记为 FAILED（{@code error_message='进程异常退出，该次执行未完成'}）；
 *       也可以把 run_key 改写为 {@code {taskId}-{recordId}} 释放令牌；</li>
 *   <li><b>不推进任何任务的水位</b>——未完成的执行必须靠重跑（幂等 upsert）保证最终一致。</li>
 * </ol>
 *
 * <p>顺带把历史数据里 {@code status} 与 {@code enabled} 不一致的行校正过来
 * （旧版本只维护 status）。</p>
 */
@Component
public class SyncStartupInitializer {

    private static final Logger log = LoggerFactory.getLogger(SyncStartupInitializer.class);

    private final RunRecordStore recordStore;
    private final JdbcTemplate jdbc;
    private final LegacyPasswordUpgrader legacyPasswordUpgrader;

    public SyncStartupInitializer(RunRecordStore recordStore, JdbcTemplate jdbc,
                                  LegacyPasswordUpgrader legacyPasswordUpgrader) {
        this.recordStore = recordStore;
        this.jdbc = jdbc;
        this.legacyPasswordUpgrader = legacyPasswordUpgrader;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(10)
    public void onReady() {
        // 0) 历史明文口令全量升级（D9）：不依赖数据库连通性，
        //    因此"躺在旧库里连不上的数据源"也能完成升级——那正是旧库最常见的形态。
        try {
            legacyPasswordUpgrader.upgradeAll();
        } catch (Exception e) {
            log.error("启动扫描（历史明文口令升级）失败，请人工检查 datasource.password", e);
        }
        try {
            int recovered = recordStore.recoverOrphanRuns();
            if (recovered > 0) {
                log.warn("启动自愈：{} 条残留 RUNNING 执行记录已标记为 FAILED 并释放运行令牌（水位未推进）", recovered);
            }
        } catch (Exception e) {
            log.error("启动自愈（残留 RUNNING 记录）失败，请人工检查 sync_record", e);
        }
        try {
            int normalized = jdbc.update(
                "UPDATE sync_task SET enabled = CASE WHEN status = 'ENABLED' THEN 1 ELSE 0 END "
                    + "WHERE enabled <> CASE WHEN status = 'ENABLED' THEN 1 ELSE 0 END");
            if (normalized > 0) {
                log.info("启动自检：已校正 {} 条任务的 enabled/status 一致性", normalized);
            }
        } catch (Exception e) {
            log.warn("启动自检（enabled/status 一致性）失败：{}", e.getMessage());
        }
    }
}
