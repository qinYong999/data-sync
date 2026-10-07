package com.datasync.core.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JDBC 元数据读取（H2 内存库，纯逻辑，不需要 MySQL）。
 *
 * <p>重点覆盖两个坑：catalog/schema 口径差异、元数据模式串里下划线的通配符语义。
 */
class JdbcMetadataReaderTest {

    private static Connection conn;

    @BeforeAll
    static void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:h2:mem:meta;DB_CLOSE_DELAY=-1;MODE=MySQL", "sa", "");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE meta_multi (a INT NOT NULL, b INT NOT NULL, v VARCHAR(10),"
                    + " PRIMARY KEY (a, b))");
            st.execute("CREATE TABLE meta_pk (id BIGINT NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL,"
                    + " updated_at TIMESTAMP, code VARCHAR(20) UNIQUE)");
            st.execute("CREATE INDEX idx_meta_pk_updated ON meta_pk (updated_at)");
            // 下划线通配符陷阱：t_a 与 txa 只差一个字符
            st.execute("CREATE TABLE t_a (x INT PRIMARY KEY)");
            st.execute("CREATE TABLE txa (y INT PRIMARY KEY)");
            st.execute("CREATE VIEW v_meta AS SELECT id FROM meta_pk");
        }
    }

    @AfterAll
    static void tearDown() throws SQLException {
        if (conn != null) {
            conn.close();
        }
    }

    @Test
    @DisplayName("读列 + 主键 + 唯一键")
    void readTable() {
        TableMeta meta = JdbcMetadata.read(conn, TableRef.of("meta_pk"));
        assertEquals(4, meta.columns().size());
        assertTrue(meta.columns().get(0).nameIs("id"), meta.columnNames().toString());
        assertEquals(1, meta.columns().get(0).ordinal());
        assertTrue(meta.columns().get(0).primaryKey());
        assertFalse(meta.columns().get(1).primaryKey());
        assertEquals(1, meta.primaryKeys().size());
        assertTrue(meta.primaryKeys().get(0).nameIs("id"));
        assertTrue(meta.columns().get(3).columnSize() >= 20);
    }

    @Test
    @DisplayName("多列主键按 KEY_SEQ 顺序返回")
    void multiColumnPrimaryKey() {
        TableMeta meta = JdbcMetadata.read(conn, TableRef.of("meta_multi"));
        List<ColumnMeta> pk = meta.primaryKeys();
        assertEquals(2, pk.size());
        assertTrue(pk.get(0).nameIs("a"), pk.toString());
        assertTrue(pk.get(1).nameIs("b"), pk.toString());
        assertEquals(2, meta.firstUsableKey().size());
    }

    @Test
    @DisplayName("唯一键被识别（顺序键回退链的最后一环）")
    void uniqueKeys() {
        TableMeta meta = JdbcMetadata.read(conn, TableRef.of("meta_pk"));
        boolean hasCodeUnique = false;
        for (List<ColumnMeta> uk : meta.uniqueKeys()) {
            if (uk.size() == 1 && uk.get(0).nameIs("code")) {
                hasCodeUnique = true;
            }
        }
        assertTrue(hasCodeUnique, "唯一约束 code 应被识别为唯一键: " + meta.uniqueKeys());
    }

    @Test
    @DisplayName("表存在性判断（视图也算存在）")
    void tableExists() {
        assertTrue(JdbcMetadata.tableExists(conn, TableRef.of("meta_pk")));
        assertTrue(JdbcMetadata.tableExists(conn, TableRef.of("v_meta")));
        assertFalse(JdbcMetadata.tableExists(conn, TableRef.of("no_such_table")));
    }

    @Test
    @DisplayName("元数据模式串必须转义下划线，否则 t_a 会误匹配 txa")
    void underscoreMustBeEscaped() {
        TableMeta ta = JdbcMetadata.read(conn, TableRef.of("t_a"));
        assertEquals(1, ta.columns().size());
        assertTrue(ta.columns().get(0).nameIs("x"), ta.columns().toString());
        TableMeta txa = JdbcMetadata.read(conn, TableRef.of("txa"));
        assertTrue(txa.columns().get(0).nameIs("y"), txa.columns().toString());
    }

    @Test
    @DisplayName("索引首列（增量列索引建议用，含非唯一索引）")
    void indexedColumns() {
        List<String> leads = JdbcMetadata.indexedLeadColumns(conn, TableRef.of("meta_pk"));
        assertTrue(leads.stream().anyMatch(s -> s.equalsIgnoreCase("updated_at")),
                "普通索引 updated_at 应算索引首列: " + leads);
        assertTrue(leads.stream().anyMatch(s -> s.equalsIgnoreCase("id")),
                "主键也算索引首列: " + leads);
    }

    @Test
    @DisplayName("列举表 + 不存在的表返回空元数据（不抛异常）")
    void listAndMissing() {
        List<TableRef> tables = JdbcMetadata.listTables(conn);
        assertTrue(tables.stream().anyMatch(t -> "meta_pk".equalsIgnoreCase(t.table())));
        TableMeta empty = JdbcMetadata.read(conn, TableRef.of("no_such_table"));
        assertTrue(empty.columns().isEmpty());
        assertTrue(empty.primaryKeys().isEmpty());
    }

    @Test
    @DisplayName("连接类型识别（H2 不在支持范围内，返回 null 而不是乱猜）")
    void dbTypeDetection() {
        assertEquals(null, DbTypes.detect(conn));
        assertEquals(com.datasync.core.model.enums.DbType.MYSQL, DbTypes.fromProductName("MySQL"));
        assertEquals(com.datasync.core.model.enums.DbType.MYSQL, DbTypes.fromProductName("MariaDB"));
        assertEquals(com.datasync.core.model.enums.DbType.DM8, DbTypes.fromProductName("DM DBMS"));
        assertEquals(null, DbTypes.fromProductName("PostgreSQL"));
        assertEquals(null, DbTypes.fromProductName(null));
    }
}
