package com.datasync.core.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.model.PreflightIssue;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 元数据库防护（Lead 决策 3）：绝不把平台自己的元数据库当成同步目标。
 *
 * <p>用 JDK 动态代理伪造 DataSource，避免为测试引入 H2/MySQL 依赖，也不依赖 Mockito 的字节码增强。
 */
class MetadataGuardTest {

    private static final String META_URL =
            "jdbc:mysql://127.0.0.1:3306/datasync?useSSL=false&serverTimezone=Asia/Shanghai";

    @Test
    @DisplayName("URL 解析与可检查性")
    void parse() {
        assertFalse(MetadataGuard.of(null).isCheckable());
        assertFalse(MetadataGuard.of("  ").isCheckable());
        assertFalse(MetadataGuard.of("jdbc:h2:mem:x").isCheckable());
        MetadataGuard g = MetadataGuard.of(META_URL);
        assertTrue(g.isCheckable());
        assertTrue(g.toString().contains("datasync"));
    }

    @Test
    @DisplayName("元数据表名识别（含 Quartz 前缀）")
    void metadataTables() {
        assertTrue(MetadataGuard.isMetadataTable("datasource"));
        assertTrue(MetadataGuard.isMetadataTable("sync_task"));
        assertTrue(MetadataGuard.isMetadataTable("sync_record"));
        assertTrue(MetadataGuard.isMetadataTable("sync_error"));
        assertTrue(MetadataGuard.isMetadataTable("flyway_schema_history"));
        assertTrue(MetadataGuard.isMetadataTable("QRTZ_TRIGGERS"));
        assertTrue(MetadataGuard.isMetadataTable("qrtz_locks"));
        assertTrue(MetadataGuard.isMetadataTable("datasync.sync_task"));
        assertTrue(MetadataGuard.isMetadataTable("SYNC_TASK"));
        assertFalse(MetadataGuard.isMetadataTable("biz_order"));
        assertFalse(MetadataGuard.isMetadataTable(null));
    }

    @Test
    @DisplayName("命中元数据库 + 命中元数据表 → ERROR")
    void hitMetadataTable() {
        List<PreflightIssue> issues = new ArrayList<>();
        MetadataGuard.of(META_URL).check(fakeDataSource(META_URL, "datasync"), "sync_task", issues);
        assertEquals(1, issues.size());
        assertEquals(ErrorCodes.TARGET_IS_METADATA, issues.get(0).getCode());
        assertEquals(PreflightIssue.Level.ERROR, issues.get(0).getLevel());
        assertTrue(issues.get(0).getHint().contains("sync_task")
                || issues.get(0).getHint().contains("元数据表"), issues.get(0).getHint());
    }

    @Test
    @DisplayName("命中元数据库但目标表是业务表 → WARN（放行但要求确认）")
    void hitOtherTable() {
        List<PreflightIssue> issues = new ArrayList<>();
        MetadataGuard.of(META_URL).check(fakeDataSource(META_URL, "datasync"), "biz_order", issues);
        assertEquals(1, issues.size());
        assertEquals(PreflightIssue.Level.WARN, issues.get(0).getLevel());
    }

    @Test
    @DisplayName("契约签名的 check(target, issues)：无法区分表名时按最严重处理")
    void contractSignature() {
        List<PreflightIssue> issues = new ArrayList<>();
        MetadataGuard.of(META_URL).check(fakeDataSource(META_URL, "datasync"), issues);
        assertEquals(1, issues.size());
        assertEquals(PreflightIssue.Level.ERROR, issues.get(0).getLevel());
    }

    @Test
    @DisplayName("host/port/database 三元组一致也算命中（URL 拼写不同不误判）")
    void hostPortDatabaseMatch() {
        List<PreflightIssue> issues = new ArrayList<>();
        MetadataGuard.of(META_URL).check(
                fakeDataSource("jdbc:mysql://127.0.0.1:3306/datasync", "datasync"), "sync_record", issues);
        assertEquals(1, issues.size());
        assertEquals(PreflightIssue.Level.ERROR, issues.get(0).getLevel());
    }

    @Test
    @DisplayName("目标不是元数据库时绝不误报")
    void noFalsePositive() {
        List<PreflightIssue> issues = new ArrayList<>();
        MetadataGuard.of(META_URL).check(
                fakeDataSource("jdbc:mysql://127.0.0.1:3306/other_db", "other_db"), "sync_task", issues);
        assertTrue(issues.isEmpty(), "同实例不同库不应报错: " + issues);
        issues.clear();
        MetadataGuard.of(META_URL).check(
                fakeDataSource("jdbc:mysql://10.0.0.9:3306/datasync", "datasync"), "sync_task", issues);
        assertTrue(issues.isEmpty(), "不同主机不应报错: " + issues);
    }

    @Test
    @DisplayName("连不上目标库时放过（不误报）")
    void connectionFailureIgnored() {
        List<PreflightIssue> issues = new ArrayList<>();
        DataSource broken = (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{DataSource.class}, (proxy, method, args) -> {
                    if ("getConnection".equals(method.getName())) {
                        throw new java.sql.SQLException("连不上");
                    }
                    return null;
                });
        MetadataGuard.of(META_URL).check(broken, "sync_task", issues);
        assertTrue(issues.isEmpty());
        // 未配置元数据库 URL 时完全不检查
        issues.clear();
        MetadataGuard.of(null).check(fakeDataSource(META_URL, "datasync"), "sync_task", issues);
        assertTrue(issues.isEmpty());
    }

    @Test
    @DisplayName("guard 为 null 时 DefaultPreflighter 正常工作")
    void nullGuard() {
        assertNotNull(new DefaultPreflighter().check(null, null, null));
        // 追加式入口（避免与契约三参 check 的三 null 歧义）
        List<PreflightIssue> issues = new ArrayList<>();
        new DefaultPreflighter().checkInto(null, fakeDataSource(META_URL, "datasync"), issues);
        assertTrue(issues.isEmpty());
    }

    // ------------------------------------------------------------------

    private static DataSource fakeDataSource(String url, String catalog) {
        InvocationHandler dsHandler = (proxy, method, args) -> switch (method.getName()) {
            case "getConnection" -> fakeConnection(url, catalog);
            case "toString" -> "FakeDataSource[" + url + "]";
            default -> null;
        };
        return (DataSource) Proxy.newProxyInstance(MetadataGuardTest.class.getClassLoader(),
                new Class<?>[]{DataSource.class}, dsHandler);
    }

    private static Connection fakeConnection(String url, String catalog) {
        InvocationHandler connHandler = (proxy, method, args) -> switch (method.getName()) {
            case "getMetaData" -> fakeMetaData(url);
            case "getCatalog" -> catalog;
            case "close" -> null;
            case "isClosed" -> false;
            default -> null;
        };
        return (Connection) Proxy.newProxyInstance(MetadataGuardTest.class.getClassLoader(),
                new Class<?>[]{Connection.class}, connHandler);
    }

    private static DatabaseMetaData fakeMetaData(String url) {
        InvocationHandler mdHandler = (proxy, method, args) -> switch (method.getName()) {
            case "getURL" -> url;
            default -> null;
        };
        return (DatabaseMetaData) Proxy.newProxyInstance(MetadataGuardTest.class.getClassLoader(),
                new Class<?>[]{DatabaseMetaData.class}, mdHandler);
    }

    @Test
    @DisplayName("辅助方法自检：假连接可用")
    void fakeDataSourceWorks() throws Exception {
        try (Connection c = fakeDataSource(META_URL, "datasync").getConnection()) {
            assertEquals(META_URL, c.getMetaData().getURL());
            assertEquals("datasync", c.getCatalog());
        }
        assertNull(null);
    }
}
