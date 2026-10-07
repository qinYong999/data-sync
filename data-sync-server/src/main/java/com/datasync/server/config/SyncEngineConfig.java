package com.datasync.server.config;

import com.datasync.core.engine.DefaultSyncEngine;
import com.datasync.core.engine.SyncEngine;
import com.datasync.core.preflight.DefaultPreflighter;
import com.datasync.core.preflight.MetadataGuard;
import com.datasync.core.preflight.Preflighter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * 引擎装配（D1：不再有 Spring Batch Job 实例爆炸）。
 *
 * <p>关键点：把<b>平台自己的元数据库 JDBC URL</b> 注入 {@link MetadataGuard}，
 * 这样"目标表被误配成 datasync 元数据库里的表"会在写入任何数据之前被拦下
 * （事故场景：一次 FULL 同步把全平台的数据源配置 TRUNCATE 掉）。
 * 引擎真实执行路径与 {@code GET /api/tasks/{id}/preflight} 端点共用同一个 Preflighter，
 * 两条路径都带这道防护。</p>
 */
@Configuration
public class SyncEngineConfig {

    private static final Logger log = LoggerFactory.getLogger(SyncEngineConfig.class);

    @Bean
    public MetadataGuard metadataGuard(Environment environment) {
        String url = environment.getProperty("spring.datasource.url");
        MetadataGuard guard = MetadataGuard.of(url);
        if (url == null || url.isBlank()) {
            log.warn("未能从 spring.datasource.url 读到元数据库地址，TARGET_IS_METADATA 防护将跳过（功能不降级）");
        } else {
            log.info("已启用 TARGET_IS_METADATA 防护：目标表若指向平台元数据库将被拒绝");
        }
        return guard;
    }

    @Bean
    public Preflighter preflighter(MetadataGuard metadataGuard) {
        return new DefaultPreflighter(metadataGuard);
    }

    @Bean
    public SyncEngine syncEngine(Preflighter preflighter) {
        // DefaultSyncEngine 内含取消标志表，可被多个任务并发调用
        return new DefaultSyncEngine(preflighter);
    }
}
