package com.datasync.core.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.model.PreflightIssue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 自定义 SQL 守门（迁移自旧 SqlValidator 的能力，错误码统一为 CUSTOM_SQL_INVALID）。
 */
class CustomSqlGuardTest {

    @Test
    @DisplayName("合法只读查询")
    void valid() {
        assertNull(CustomSqlGuard.validate("SELECT id, name FROM t WHERE name = 'drop table x'"));
        assertNull(CustomSqlGuard.validate("SELECT * FROM t ORDER BY id"));
        assertNull(CustomSqlGuard.validate("WITH x AS (SELECT 1 AS a) SELECT a FROM x"));
        assertNull(CustomSqlGuard.validate("SELECT * FROM t -- 这里写 DELETE 也不算数"));
        assertNull(CustomSqlGuard.validate("SELECT * FROM t /* INSERT INTO x VALUES (1) */"));
        assertNull(CustomSqlGuard.validate("SELECT * FROM t WHERE updated_at > NOW()"));
        assertTrue(CustomSqlGuard.isValid("SELECT 1"));
    }

    @Test
    @DisplayName("非 SELECT 开头一律拒绝")
    void notSelect() {
        PreflightIssue i = CustomSqlGuard.validate("UPDATE t SET a = 1");
        assertNotNull(i);
        assertEquals(ErrorCodes.CUSTOM_SQL_INVALID, i.getCode());
        assertTrue(i.isError());
        assertNotNull(CustomSqlGuard.validate("DELETE FROM t"));
        assertNotNull(CustomSqlGuard.validate("SHOW TABLES"));
        assertNotNull(CustomSqlGuard.validate(""));
        assertNotNull(CustomSqlGuard.validate(null));
    }

    @Test
    @DisplayName("多语句注入必须拒绝")
    void multiStatement() {
        PreflightIssue i = CustomSqlGuard.validate("SELECT 1; DROP TABLE t");
        assertNotNull(i);
        assertTrue(i.getMessage().contains("多条语句"), i.getMessage());
        // 仅末尾一个分号是允许的
        assertNull(CustomSqlGuard.validate("SELECT 1;"));
    }

    @Test
    @DisplayName("危险关键字（含 INTO OUTFILE）拒绝")
    void dangerousKeywords() {
        assertNotNull(CustomSqlGuard.validate("SELECT * FROM t INTO OUTFILE '/tmp/x'"));
        assertNotNull(CustomSqlGuard.validate("select * from t; truncate table t"));
        assertNotNull(CustomSqlGuard.validate("SELECT LOAD_FILE('/etc/passwd')"));
        // 列名里含关键字的子串不算命中
        assertNull(CustomSqlGuard.validate("SELECT updated_at, created_at, settings FROM t"));
        // 纯查询（带 OFFSET 只是性能差，不是安全/正确性问题）
        assertNull(CustomSqlGuard.validate("SELECT * FROM t LIMIT 1 OFFSET 2"));
    }

    @Test
    @DisplayName("包装成派生表与外层别名")
    void wrap() {
        assertEquals("SELECT * FROM (SELECT 1) _ds_src", CustomSqlGuard.wrap("SELECT 1"));
        assertEquals("SELECT 1", CustomSqlGuard.normalize("  SELECT 1 ;  "));
        assertEquals("", CustomSqlGuard.normalize(null));
    }

    @Test
    @DisplayName("剥离字面量与注释")
    void strip() {
        String stripped = CustomSqlGuard.stripLiteralsAndComments("SELECT 'a''b' FROM t -- comment");
        assertFalse(stripped.contains("a"));
        assertFalse(stripped.contains("comment"));
        assertTrue(stripped.contains("SELECT"));
        assertTrue(CustomSqlGuard.tokens("SELECT a FROM t").contains("FROM"));
        assertTrue(CustomSqlGuard.summarize("  SELECT\n  *  ").equals("SELECT *"));
    }
}
