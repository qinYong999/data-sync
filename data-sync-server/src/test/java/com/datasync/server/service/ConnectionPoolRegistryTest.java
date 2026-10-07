package com.datasync.server.service;

import com.datasync.server.config.AppProperties;
import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.repository.DataSourceRepository;
import com.datasync.server.security.CredentialCipher;
import com.datasync.server.security.LegacyPasswordUpgrader;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D12：连接池注册表——按数据源复用、配置变更失效、关闭统一销毁；
 * D9：历史明文口令在首次取池时自动升级为密文。
 *
 * <p>需要本机 MySQL（127.0.0.1:3306，root/123456）与测试库 {@code datasync_test_platform}。</p>
 */
class ConnectionPoolRegistryTest {

    private static final String TEST_DB = "datasync_test_platform";

    private DataSourceRepository repository;
    private AppProperties properties;
    private ConnectionPoolRegistry registry;
    private LegacyPasswordUpgrader legacyPasswordUpgrader;

    @BeforeEach
    void setUp() {
        repository = mock(DataSourceRepository.class);
        properties = new AppProperties();
        properties.getSecurity().setSecretKey("0123456789abcdef0123456789abcdef");
        CredentialCipher cipher = new CredentialCipher(properties, new MockEnvironment());
        legacyPasswordUpgrader = new LegacyPasswordUpgrader(repository, cipher);
        registry = new ConnectionPoolRegistry(repository, cipher, properties, legacyPasswordUpgrader);
    }

    @AfterEach
    void tearDown() {
        registry.closeAll();
    }

    private DataSourceEntity entity(Long id, String password, String database) {
        DataSourceEntity entity = new DataSourceEntity();
        entity.setId(id);
        entity.setName("测试源-" + id);
        entity.setDbType("MYSQL");
        entity.setHost("127.0.0.1");
        entity.setPort(3306);
        entity.setDatabaseName(database);
        entity.setUsername("root");
        entity.passwordCipher(password);
        when(repository.findById(id)).thenReturn(Optional.of(entity));
        when(repository.save(any(DataSourceEntity.class))).thenAnswer(invocation -> invocation.getArgument(0));
        return entity;
    }

    @Test
    @DisplayName("同一数据源反复取池返回同一个池实例")
    void poolIsReused() throws Exception {
        CredentialCipher cipher = new CredentialCipher(properties, new MockEnvironment());
        entity(1L, cipher.encrypt("123456"), TEST_DB);

        DataSource first = registry.get(1L);
        DataSource second = registry.get(1L);

        assertThat(first).isSameAs(second);
        assertThat(registry.cachedPoolCount()).isEqualTo(1);
        try (Connection connection = first.getConnection()) {
            assertThat(connection.isValid(3)).isTrue();
        }
    }

    @Test
    @DisplayName("invalidate 之后重新建池（配置变更失效）")
    void invalidateRecreatesPool() {
        CredentialCipher cipher = new CredentialCipher(properties, new MockEnvironment());
        entity(2L, cipher.encrypt("123456"), TEST_DB);

        DataSource first = registry.get(2L);
        registry.invalidate(2L);
        DataSource second = registry.get(2L);

        assertThat(((HikariDataSource) first).isClosed()).isTrue();
        assertThat(second).isNotSameAs(first);
        assertThat(registry.cachedPoolCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("历史明文口令自动升级为 ENC(...) 密文（D9）")
    void legacyPlaintextPasswordIsUpgradedAndPersisted() {
        // 明文 = 真实口令：升级写库后连接池仍能建起来（旧库里就是这么存的）
        entity(3L, "123456", TEST_DB);

        registry.get(3L);

        verify(repository).save(org.mockito.ArgumentMatchers.argThat(
            saved -> saved.passwordCipher() != null
                && saved.passwordCipher().startsWith("ENC(")
                && !saved.passwordCipher().contains("123456-plain-legacy")));
    }

    @Test
    @DisplayName("已是密文的口令不会被重复写库")
    void encryptedPasswordIsNotRewritten() {
        CredentialCipher cipher = new CredentialCipher(properties, new MockEnvironment());
        entity(4L, cipher.encrypt("123456"), TEST_DB);

        registry.get(4L);

        verify(repository, never()).save(any(DataSourceEntity.class));
    }

    @Test
    @DisplayName("池状态快照包含连接数指标（供 /api/system/info）")
    void snapshotExposesMetrics() {
        CredentialCipher cipher = new CredentialCipher(properties, new MockEnvironment());
        entity(5L, cipher.encrypt("123456"), TEST_DB);

        registry.get(5L);
        List<Map<String, Object>> snapshot = registry.snapshot();

        assertThat(snapshot).hasSize(1);
        Map<String, Object> item = snapshot.get(0);
        assertThat(item).containsEntry("id", 5L)
            .containsEntry("dbType", "MYSQL")
            .containsEntry("host", "127.0.0.1")
            .containsEntry("port", 3306)
            .containsEntry("databaseName", TEST_DB)
            .containsEntry("maximumPoolSize", 8)
            .containsEntry("cached", true);
        assertThat(item.get("activeConnections")).isNotNull();
    }

    @Test
    @DisplayName("closeAll 关闭全部连接池")
    void closeAllClosesPools() {
        CredentialCipher cipher = new CredentialCipher(properties, new MockEnvironment());
        entity(6L, cipher.encrypt("123456"), TEST_DB);
        DataSource dataSource = registry.get(6L);

        registry.closeAll();

        assertThat(((HikariDataSource) dataSource).isClosed()).isTrue();
        assertThat(registry.cachedPoolCount()).isZero();
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    @DisplayName("连不上/口令错：抛中文异常且消息里不含明文口令")
    void unreachableDatasourceGivesChineseMessageWithoutPassword() {
        CredentialCipher cipher = new CredentialCipher(properties, new MockEnvironment());
        entity(7L, cipher.encrypt("definitely-wrong-password"), TEST_DB);

        assertThatThrownBy(() -> registry.get(7L))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("连接池创建失败")
            .hasMessageNotContaining("definitely-wrong-password");
    }

    @Test
    @DisplayName("数据源不存在 → 中文 404 业务异常")
    void missingDatasourceThrowsNotFound() {
        when(repository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> registry.get(404L))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("数据源不存在");
    }
}
