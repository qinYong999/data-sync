package com.datasync.server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 应用自身配置（契约 §4.4 冻结的 {@code app.*} 键）。
 *
 * <p>所有字段都带默认值：即使 application.yml 里缺少某个键，应用仍能启动，
 * 只是用默认值。这样配置的增删不会把启动变成一场事故。</p>
 */
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private Security security = new Security();
    private Sync sync = new Sync();
    private Pool pool = new Pool();
    private Retention retention = new Retention();

    public Security getSecurity() { return security; }
    public void setSecurity(Security security) { this.security = security; }
    public Sync getSync() { return sync; }
    public void setSync(Sync sync) { this.sync = sync; }
    public Pool getPool() { return pool; }
    public void setPool(Pool pool) { this.pool = pool; }
    public Retention getRetention() { return retention; }
    public void setRetention(Retention retention) { this.retention = retention; }

    /** 认证与凭证加密（D9/D10） */
    public static class Security {
        /** false = 本地开发全放行并关闭 CSRF（生产禁止） */
        private boolean enabled = true;
        private String username = "admin";
        /** 管理员密码；prod profile 下为空则拒绝启动 */
        private String password = "";
        /** AES-GCM 密钥（≥32 字符）；prod profile 下为空则拒绝启动 */
        private String secretKey = "";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
    }

    /** 同步执行（D11/D12） */
    public static class Sync {
        private int workerThreads = 4;
        private int maxConcurrentPerTask = 1;
        private int defaultPageSize = 1000;
        private int defaultBatchSize = 500;
        private long defaultSafetyLagSeconds = 0L;
        private long defaultLookbackSeconds = 0L;
        private long queryTimeoutSeconds = 300L;
        /** 停机时等待运行中任务收尾的秒数，超时后强制标记 CANCELLED */
        private long shutdownTimeoutSeconds = 60L;

        public int getWorkerThreads() { return workerThreads; }
        public void setWorkerThreads(int workerThreads) { this.workerThreads = workerThreads; }
        public int getMaxConcurrentPerTask() { return maxConcurrentPerTask; }
        public void setMaxConcurrentPerTask(int maxConcurrentPerTask) { this.maxConcurrentPerTask = maxConcurrentPerTask; }
        public int getDefaultPageSize() { return defaultPageSize; }
        public void setDefaultPageSize(int defaultPageSize) { this.defaultPageSize = defaultPageSize; }
        public int getDefaultBatchSize() { return defaultBatchSize; }
        public void setDefaultBatchSize(int defaultBatchSize) { this.defaultBatchSize = defaultBatchSize; }
        public long getDefaultSafetyLagSeconds() { return defaultSafetyLagSeconds; }
        public void setDefaultSafetyLagSeconds(long v) { this.defaultSafetyLagSeconds = v; }
        public long getDefaultLookbackSeconds() { return defaultLookbackSeconds; }
        public void setDefaultLookbackSeconds(long v) { this.defaultLookbackSeconds = v; }
        public long getQueryTimeoutSeconds() { return queryTimeoutSeconds; }
        public void setQueryTimeoutSeconds(long queryTimeoutSeconds) { this.queryTimeoutSeconds = queryTimeoutSeconds; }
        public long getShutdownTimeoutSeconds() { return shutdownTimeoutSeconds; }
        public void setShutdownTimeoutSeconds(long v) { this.shutdownTimeoutSeconds = v; }
    }

    /** 每数据源连接池（D12） */
    public static class Pool {
        private int maxSizePerDatasource = 8;
        private int minIdlePerDatasource = 1;
        private long idleTimeoutSeconds = 600L;
        private long maxLifetimeSeconds = 1800L;
        private long connectionTimeoutSeconds = 30L;

        public int getMaxSizePerDatasource() { return maxSizePerDatasource; }
        public void setMaxSizePerDatasource(int v) { this.maxSizePerDatasource = v; }
        public int getMinIdlePerDatasource() { return minIdlePerDatasource; }
        public void setMinIdlePerDatasource(int v) { this.minIdlePerDatasource = v; }
        public long getIdleTimeoutSeconds() { return idleTimeoutSeconds; }
        public void setIdleTimeoutSeconds(long v) { this.idleTimeoutSeconds = v; }
        public long getMaxLifetimeSeconds() { return maxLifetimeSeconds; }
        public void setMaxLifetimeSeconds(long v) { this.maxLifetimeSeconds = v; }
        public long getConnectionTimeoutSeconds() { return connectionTimeoutSeconds; }
        public void setConnectionTimeoutSeconds(long v) { this.connectionTimeoutSeconds = v; }
    }

    /** 元数据保留策略 */
    public static class Retention {
        private boolean enabled = true;
        private int recordDays = 30;
        private int errorDays = 7;
        private String cleanupCron = "0 30 3 * * ?";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public int getRecordDays() { return recordDays; }
        public void setRecordDays(int recordDays) { this.recordDays = recordDays; }
        public int getErrorDays() { return errorDays; }
        public void setErrorDays(int errorDays) { this.errorDays = errorDays; }
        public String getCleanupCron() { return cleanupCron; }
        public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
    }
}
