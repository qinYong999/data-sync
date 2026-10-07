package com.datasync.server.service;

import com.datasync.server.config.AppProperties;
import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.repository.DataSourceRepository;
import com.datasync.server.security.CredentialCipher;
import com.datasync.server.security.LegacyPasswordUpgrader;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每数据源连接池注册表（D12）。
 *
 * <p>原来每次执行同步都新建/销毁一对 Hikari 池，定时任务高频触发时就是连接风暴。
 * 现在按数据源 ID 缓存池，配置变更（含口令）时失效重建，应用关闭时统一销毁。</p>
 *
 * <p>同时承担 D9 的"历史明文口令自动升级"：首次取池时若发现 {@code datasource.password}
 * 仍是明文，就地加密回写，之后所有读取都是密文。</p>
 */
@Component
public class ConnectionPoolRegistry implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ConnectionPoolRegistry.class);

    private final DataSourceRepository repository;
    private final CredentialCipher cipher;
    private final AppProperties properties;
    private final LegacyPasswordUpgrader legacyPasswordUpgrader;
    private final Map<Long, PoolEntry> pools = new ConcurrentHashMap<>();

    private volatile boolean running;

    public ConnectionPoolRegistry(DataSourceRepository repository, CredentialCipher cipher, AppProperties properties,
                                  LegacyPasswordUpgrader legacyPasswordUpgrader) {
        this.repository = repository;
        this.cipher = cipher;
        this.properties = properties;
        this.legacyPasswordUpgrader = legacyPasswordUpgrader;
    }

    /**
     * 取（必要时创建）指定数据源的连接池。调用方**不要**关闭返回的 DataSource：
     * 它的生命周期由本注册表管理。
     */
    public DataSource get(Long dsId) {
        DataSourceEntity entity = repository.findById(dsId)
            .orElseThrow(() -> AppException.notFound("数据源不存在: " + dsId));
        // 兜底升级：真正的保障在启动扫描与读取路径（都不依赖连通性）
        legacyPasswordUpgrader.upgradeIfLegacy(entity);

        String fingerprint = fingerprint(entity);
        PoolEntry entry = pools.compute(dsId, (id, existing) -> {
            if (existing != null && existing.fingerprint().equals(fingerprint)) {
                return existing;
            }
            if (existing != null) {
                log.info("数据源 {} 连接配置已变更，重建连接池", id);
                closeQuietly(existing.dataSource(), id);
            }
            return createEntry(id, entity, fingerprint);
        });
        return entry.dataSource();
    }

    /** 数据源增删改后失效对应连接池 */
    public void invalidate(Long dsId) {
        PoolEntry removed = pools.remove(dsId);
        if (removed != null) {
            closeQuietly(removed.dataSource(), dsId);
            log.info("已释放数据源 {} 的连接池", dsId);
        }
    }

    /** 池状态快照，供 {@code GET /api/system/info} 展示 */
    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> list = new ArrayList<>();
        pools.forEach((id, entry) -> {
            HikariDataSource ds = entry.dataSource();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", id);
            item.put("name", entry.name());
            item.put("poolName", ds.getPoolName());
            item.put("dbType", entry.dbType());
            item.put("host", entry.host());
            item.put("port", entry.port());
            item.put("databaseName", entry.databaseName());
            item.put("maximumPoolSize", ds.getMaximumPoolSize());
            item.put("minimumIdle", ds.getMinimumIdle());
            item.put("activeConnections", ds.getHikariPoolMXBean() == null ? -1 : ds.getHikariPoolMXBean().getActiveConnections());
            item.put("idleConnections", ds.getHikariPoolMXBean() == null ? -1 : ds.getHikariPoolMXBean().getIdleConnections());
            item.put("totalConnections", ds.getHikariPoolMXBean() == null ? -1 : ds.getHikariPoolMXBean().getTotalConnections());
            item.put("threadsAwaitingConnection", ds.getHikariPoolMXBean() == null ? -1 : ds.getHikariPoolMXBean().getThreadsAwaitingConnection());
            item.put("cached", true);
            item.put("createdAt", entry.createdAt());
            list.add(item);
        });
        return list;
    }

    /** 已缓存的池数量 */
    public int cachedPoolCount() {
        return pools.size();
    }

    /** JDBC URL 构造（数据源 CRUD 的连通性测试也复用它，保证两者指向同一个库） */
    public static String jdbcUrl(String dbType, String host, Integer port, String database) {
        if (dbType == null) {
            throw new IllegalArgumentException("不支持的数据库类型: null");
        }
        return switch (dbType.toUpperCase()) {
            case "MYSQL" -> String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                    + "&characterEncoding=utf8&useUnicode=true&rewriteBatchedStatements=true",
                host, port, database);
            case "DM8" -> String.format("jdbc:dm://%s:%d/%s", host, port, database);
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    public static String driverClassName(String dbType) {
        if (dbType == null) {
            throw new IllegalArgumentException("不支持的数据库类型: null");
        }
        return switch (dbType.toUpperCase()) {
            case "MYSQL" -> "com.mysql.cj.jdbc.Driver";
            case "DM8" -> "dm.jdbc.driver.DmDriver";
            default -> throw new IllegalArgumentException("不支持的数据库类型: " + dbType);
        };
    }

    // ---------------------------------------------------------------- 内部实现

    private PoolEntry createEntry(Long id, DataSourceEntity entity, String fingerprint) {
        String plainPassword = cipher.decrypt(entity.passwordCipher());
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl(entity.getDbType(), entity.getHost(), entity.getPort(), entity.getDatabaseName()));
        config.setUsername(entity.getUsername());
        config.setPassword(plainPassword);
        config.setDriverClassName(driverClassName(entity.getDbType()));
        AppProperties.Pool pool = properties.getPool();
        config.setMaximumPoolSize(Math.max(1, pool.getMaxSizePerDatasource()));
        config.setMinimumIdle(Math.max(0, Math.min(pool.getMinIdlePerDatasource(), pool.getMaxSizePerDatasource())));
        config.setIdleTimeout(pool.getIdleTimeoutSeconds() * 1000L);
        config.setMaxLifetime(pool.getMaxLifetimeSeconds() * 1000L);
        config.setConnectionTimeout(pool.getConnectionTimeoutSeconds() * 1000L);
        config.setPoolName("ds-" + id + "-" + String.valueOf(entity.getDbType()).toLowerCase());
        config.setAutoCommit(true);
        try {
            HikariDataSource ds = new HikariDataSource(config);
            log.info("已为数据源 {}（{}:{}/{}）创建连接池，最大连接数 {}",
                id, entity.getHost(), entity.getPort(), entity.getDatabaseName(), config.getMaximumPoolSize());
            return new PoolEntry(ds, fingerprint, entity.getName(), entity.getDbType(), entity.getHost(),
                entity.getPort(), entity.getDatabaseName(), LocalDateTime.now());
        } catch (RuntimeException e) {
            // 连接池创建失败会把 JDBC URL/用户名带进消息，必须清洗口令后再抛出
            String reason = CredentialCipher.scrub(e.getMessage(), plainPassword);
            throw AppException.badRequest("DATASOURCE_UNREACHABLE",
                "数据源 " + id + " 连接池创建失败：" + reason);
        }
    }

    private String fingerprint(DataSourceEntity entity) {
        return String.join("|",
            String.valueOf(entity.getDbType()),
            String.valueOf(entity.getHost()),
            String.valueOf(entity.getPort()),
            String.valueOf(entity.getDatabaseName()),
            String.valueOf(entity.getUsername()),
            String.valueOf(entity.passwordCipher()));
    }

    private void closeQuietly(HikariDataSource ds, Long id) {
        try {
            ds.close();
        } catch (Exception e) {
            log.warn("关闭数据源 {} 的连接池失败: {}", id, e.getMessage());
        }
    }

    // ---------------------------------------------------------------- 生命周期

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        closeAll();
        running = false;
    }

    @Override
    public void stop(Runnable callback) {
        stop();
        callback.run();
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isAutoStartup() {
        return true;
    }

    /**
     * 阶段值刻意小于 {@code GracefulShutdownService}：Spring 停机时**先停高阶段**，
     * 因此同步任务先收尾，之后才关闭这些池。
     */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    @PreDestroy
    public void closeAll() {
        if (pools.isEmpty()) {
            return;
        }
        log.info("正在关闭 {} 个数据源连接池", pools.size());
        pools.keySet().forEach(this::invalidate);
    }

    /** 一个已缓存的连接池 */
    private record PoolEntry(HikariDataSource dataSource, String fingerprint, String name, String dbType,
                             String host, Integer port, String databaseName, LocalDateTime createdAt) {
    }
}
