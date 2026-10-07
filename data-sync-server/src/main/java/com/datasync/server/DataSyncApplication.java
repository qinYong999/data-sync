package com.datasync.server;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * DataSync 服务端启动类。
 *
 * <p>{@code @EnableScheduling} 供保留策略清理等轻量周期任务使用；
 * 同步任务的调度走 Quartz（JDBC JobStore），两者互不干扰。</p>
 */
@SpringBootApplication
@EnableScheduling
public class DataSyncApplication {

    public static void main(String[] args) {
        SpringApplication.run(DataSyncApplication.class, args);
    }
}
