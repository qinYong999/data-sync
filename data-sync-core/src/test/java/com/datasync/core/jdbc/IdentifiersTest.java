package com.datasync.core.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class IdentifiersTest {

    @Test
    @DisplayName("合法标识符：字母数字下划线美元符汉字，最多三段")
    void valid() {
        assertTrue(Identifiers.isValid("t"));
        assertTrue(Identifiers.isValid("t_1"));
        assertTrue(Identifiers.isValid("$tmp"));
        assertTrue(Identifiers.isValid("用户表"));
        assertTrue(Identifiers.isValid("db.tbl"));
        assertTrue(Identifiers.isValid("catalog.db.tbl"));
    }

    @Test
    @DisplayName("非法标识符：注入、空白、引号、空段一律拒绝")
    void invalid() {
        for (String bad : Identifiers.examplesOfInvalid()) {
            assertFalse(Identifiers.isValid(bad), "应拒绝: " + bad);
        }
        assertFalse(Identifiers.isValid(null));
        assertFalse(Identifiers.isValid(" t"));
        assertFalse(Identifiers.isValid("t "));
        assertFalse(Identifiers.isValid("a.b.c.d"));
        assertFalse(Identifiers.isValid("t;drop"));
    }

    @Test
    @DisplayName("validate 给出中文异常")
    void validate() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Identifiers.validate("t; DROP TABLE x", "源表名"));
        assertTrue(e.getMessage().contains("源表名"), e.getMessage());
        assertTrue(e.getMessage().contains("非法字符"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate(null, "目标表名"));
        assertThrows(IllegalArgumentException.class, () -> Identifiers.validate("  ", "目标表名"));
    }

    @Test
    @DisplayName("拆分与工具方法")
    void utils() {
        assertEquals(List.of("db", "tbl"), Identifiers.parts("db.tbl"));
        assertEquals(List.of("tbl"), Identifiers.parts("tbl"));
        assertTrue(Identifiers.equalsIgnoreCase("TBL", "tbl"));
        assertEquals("t", Identifiers.trimToNull("  t  "));
        assertEquals(null, Identifiers.trimToNull("   "));
        assertEquals("db.tbl", Identifiers.join(List.of("db", "tbl")));
        assertTrue(Identifiers.sameTable("db1.t", "db2.t"));
        assertTrue(Identifiers.isValidPart("a1_$"));
        assertFalse(Identifiers.isValidPart("a-b"));
    }
}
