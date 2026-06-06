package com.datasync.core.job;

import com.datasync.core.model.enums.DbType;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SqlBuilderTest {

    @Test
    void testBuildSelectPage() {
        String sql = SqlBuilder.buildSelectPage("user", "id", 1000, 0);
        assertEquals("SELECT * FROM user ORDER BY id LIMIT 1000 OFFSET 0", sql);
    }

    @Test
    void testBuildSelectIncremental() {
        String sql = SqlBuilder.buildSelectIncremental("user", "updated_at", "id");
        assertEquals("SELECT * FROM user WHERE updated_at > ? ORDER BY updated_at, id", sql);
    }

    @Test
    void testBuildUpsertSql_MySql() {
        String sql = SqlBuilder.buildUpsertSql("user", new String[]{"id", "name", "age"}, new String[]{"id"}, DbType.MYSQL);
        assertTrue(sql.contains("ON DUPLICATE KEY UPDATE"));
        assertTrue(sql.contains("name = VALUES(name)"));
    }

    @Test
    void testBuildUpsertSql_Dm8() {
        String sql = SqlBuilder.buildUpsertSql("user", new String[]{"id", "name", "age"}, new String[]{"id"}, DbType.DM8);
        assertTrue(sql.contains("MERGE INTO"));
    }

    @Test
    void testBuildTruncateSql() {
        assertEquals("TRUNCATE TABLE user", SqlBuilder.buildTruncateSql("user"));
    }

    @Test
    void testBuildCountSql() {
        assertEquals("SELECT COUNT(*) FROM user", SqlBuilder.buildCountSql("user"));
    }

    @Test
    void testBuildInsertSql() {
        String sql = SqlBuilder.buildInsertSql("user", new String[]{"id", "name"});
        assertEquals("INSERT INTO user (id, name) VALUES (?, ?)", sql);
    }
}