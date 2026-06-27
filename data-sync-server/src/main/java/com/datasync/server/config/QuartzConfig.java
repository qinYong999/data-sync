package com.datasync.server.config;

import com.datasync.server.job.SyncQuartzJob;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;

/**
 * Quartz 配置 — 将 Spring ApplicationContext 注入 Quartz Job
 */
@Configuration
public class QuartzConfig {

    public QuartzConfig(ApplicationContext applicationContext) {
        SyncQuartzJob.setApplicationContext(applicationContext);
    }
}
