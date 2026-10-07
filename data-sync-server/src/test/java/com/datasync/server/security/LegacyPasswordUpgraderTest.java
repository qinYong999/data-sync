package com.datasync.server.security;

import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.repository.DataSourceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D6：历史明文口令升级必须<b>不依赖数据库连通性</b>。
 *
 * <p>QA 的实测结论：旧库里最常见的是"躺着一条连不上的旧数据源"（口令错/库下线），
 * 而它恰恰是明文长期留存的场景。因此这里用纯 Mockito 证明升级路径不碰任何连接。</p>
 */
class LegacyPasswordUpgraderTest {

    private DataSourceRepository repository;
    private CredentialCipher cipher;
    private LegacyPasswordUpgrader upgrader;

    @BeforeEach
    void setUp() {
        repository = mock(DataSourceRepository.class);
        com.datasync.server.config.AppProperties properties = new com.datasync.server.config.AppProperties();
        properties.getSecurity().setSecretKey("0123456789abcdef0123456789abcdef");
        cipher = new CredentialCipher(properties, new MockEnvironment());
        upgrader = new LegacyPasswordUpgrader(repository, cipher);
    }

    private DataSourceEntity plaintextDatasource(long id, String password) {
        DataSourceEntity entity = new DataSourceEntity();
        entity.setId(id);
        entity.setName("旧数据源-" + id);
        entity.setDbType("MYSQL");
        entity.setHost("127.0.0.1");
        entity.setPort(3306);
        entity.setDatabaseName("gone_away_db");
        entity.setUsername("root");
        entity.passwordCipher(password);
        return entity;
    }

    @Test
    @DisplayName("单条升级：明文 → ENC(...)，且只写一次库")
    void upgradesSinglePlaintextRow() {
        DataSourceEntity entity = plaintextDatasource(1L, "plain-password");
        when(repository.save(any(DataSourceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(upgrader.upgradeIfLegacy(entity)).isTrue();

        ArgumentCaptor<DataSourceEntity> captor = ArgumentCaptor.forClass(DataSourceEntity.class);
        verify(repository).save(captor.capture());
        String stored = captor.getValue().passwordCipher();
        assertThat(stored).startsWith("ENC(").doesNotContain("plain-password");
        assertThat(cipher.decrypt(stored)).isEqualTo("plain-password");
    }

    @Test
    @DisplayName("已是密文的行不再写库（读取路径常态零写库）")
    void skipsAlreadyEncryptedRow() {
        DataSourceEntity entity = plaintextDatasource(2L, cipher.encrypt("123456"));

        assertThat(upgrader.upgradeIfLegacy(entity)).isFalse();
        verify(repository, never()).save(any(DataSourceEntity.class));
    }

    @Test
    @DisplayName("启动扫描：一次把库里所有明文行升级（不建立任何业务库连接）")
    void startupScanUpgradesAllLegacyRowsWithoutAnyConnection() {
        DataSourceEntity wrongPassword = plaintextDatasource(3L, "wrong-password-for-offline-db");
        DataSourceEntity correctPassword = plaintextDatasource(4L, "correct-password");
        when(repository.findLegacyPlaintextPasswords()).thenReturn(List.of(wrongPassword, correctPassword));
        when(repository.save(any(DataSourceEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        int upgraded = upgrader.upgradeAll();

        assertThat(upgraded).isEqualTo(2);
        assertThat(wrongPassword.passwordCipher()).startsWith("ENC(")
            .as("连不上的旧数据源也必须升级——这才是 D6 的要害").doesNotContain("wrong-password-for-offline-db");
        assertThat(cipher.decrypt(wrongPassword.passwordCipher())).isEqualTo("wrong-password-for-offline-db");
        assertThat(correctPassword.passwordCipher()).startsWith("ENC(");
    }

    @Test
    @DisplayName("空口令不处理（避免每次启动都做一次无意义的写）")
    void emptyPasswordIsIgnored() {
        DataSourceEntity entity = plaintextDatasource(5L, "");

        assertThat(upgrader.upgradeIfLegacy(entity)).isFalse();
        verify(repository, never()).save(any(DataSourceEntity.class));
    }
}
