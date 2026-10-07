package com.datasync.core.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.jdbc.TableRef;
import com.datasync.core.model.enums.DbType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 达梦 DM8 方言 SQL 文本断言。
 *
 * <p><b>未在真实达梦实例验证</b>（契约 D13）：本机没有 DM8 实例与驱动，这里只能做文本级断言。
 */
class Dm8DialectTest {

    private final Dm8Dialect d = new Dm8Dialect();

    @Test
    @DisplayName("类注释必须写明未在真实达梦实例验证")
    void documentsUnverifiedStatus() throws Exception {
        String source = new String(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(
                        "src/main/java/com/datasync/core/dialect/Dm8Dialect.java")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(source.contains("未在真实达梦实例验证"), "类注释必须标注未验证状态");
    }

    @Test
    @DisplayName("双引号引用与非法字符拦截")
    void quote() {
        assertEquals("\"id\"", d.quote("id"));
        assertEquals("\"schema\".\"tbl\"", d.quote("schema.tbl"));
        assertEquals("\"tbl\"", d.qualifySchema(null, "tbl"));
        assertThrows(IllegalArgumentException.class, () -> d.quote("t; drop"));
    }

    @Test
    @DisplayName("多列键集分页用元组展开（不依赖行构造器）")
    void keysetExpandedTuple() {
        String sql = d.buildKeysetSelect(TableRef.of("t"), List.of("a", "b", "c"), List.of("a", "b"),
                KeysetPosition.of(List.of("a", "b"), List.of(1, 2)), 100, List.of());
        assertEquals("SELECT \"a\", \"b\", \"c\" FROM \"t\""
                + " WHERE ((\"a\" > ?) OR (\"a\" = ? AND \"b\" > ?))"
                + " ORDER BY \"a\" ASC, \"b\" ASC LIMIT 100", sql);
    }

    @Test
    @DisplayName("三列键集分页展开为三个分支")
    void keysetThreeColumns() {
        String sql = d.buildKeysetSelect(TableRef.of("t"), List.of("a", "b", "c"), List.of("a", "b", "c"),
                KeysetPosition.of(List.of("a", "b", "c"), List.of(1, 2, 3)), 10, List.of());
        assertEquals("SELECT \"a\", \"b\", \"c\" FROM \"t\""
                + " WHERE ((\"a\" > ?) OR (\"a\" = ? AND \"b\" > ?) OR (\"a\" = ? AND \"b\" = ? AND \"c\" > ?))"
                + " ORDER BY \"a\" ASC, \"b\" ASC, \"c\" ASC LIMIT 10", sql);
    }

    @Test
    @DisplayName("单列键仍用简单比较")
    void keysetSingleColumn() {
        String sql = d.buildKeysetSelect(TableRef.of("t"), List.of("id"), List.of("id"),
                KeysetPosition.of(List.of("id"), List.of(9)), 10, List.of());
        assertEquals("SELECT \"id\" FROM \"t\" WHERE \"id\" > ? ORDER BY \"id\" ASC LIMIT 10", sql);
    }

    @Test
    @DisplayName("MERGE INTO 幂等写入（含 WHEN MATCHED / WHEN NOT MATCHED）")
    void upsert() {
        String sql = d.buildUpsert(TableRef.of("t"), List.of("id", "name"), List.of("id"));
        assertEquals("MERGE INTO \"t\" t USING (SELECT ? AS \"id\", ? AS \"name\" FROM DUAL) s"
                + " ON (t.\"id\" = s.\"id\")"
                + " WHEN MATCHED THEN UPDATE SET t.\"name\" = s.\"name\""
                + " WHEN NOT MATCHED THEN INSERT (\"id\", \"name\") VALUES (s.\"id\", s.\"name\")", sql);
    }

    @Test
    @DisplayName("全键列时省略 WHEN MATCHED（MERGE 的 UPDATE 子句不能为空）")
    void upsertAllKeys() {
        String sql = d.buildUpsert(TableRef.of("t"), List.of("a", "b"), List.of("a", "b"));
        assertEquals("MERGE INTO \"t\" t USING (SELECT ? AS \"a\", ? AS \"b\" FROM DUAL) s"
                + " ON (t.\"a\" = s.\"a\" AND t.\"b\" = s.\"b\")"
                + " WHEN NOT MATCHED THEN INSERT (\"a\", \"b\") VALUES (s.\"a\", s.\"b\")", sql);
    }

    @Test
    @DisplayName("DM8 不支持 CREATE TABLE ... LIKE（SWAP 策略预检报 STRATEGY_UNSUPPORTED）")
    void createTableLikeUnsupported() {
        UnsupportedOperationException e = assertThrows(UnsupportedOperationException.class,
                () -> d.buildCreateTableLike(TableRef.of("t"), TableRef.of("s")));
        assertTrue(e.getMessage().contains("未在真实达梦实例验证"), e.getMessage());
    }

    @Test
    @DisplayName("改表名 / LIMIT / 当前用户")
    void misc() {
        assertEquals("ALTER TABLE \"a\" RENAME TO \"b\"", d.buildRenameTable(TableRef.of("a"), TableRef.of("b")));
        assertEquals("LIMIT 20", d.limitClause(20));
        assertEquals("SELECT USER FROM DUAL", d.userName());
        assertEquals(DbType.DM8, d.dbType());
        assertEquals(1000, d.streamFetchSize());
    }
}
