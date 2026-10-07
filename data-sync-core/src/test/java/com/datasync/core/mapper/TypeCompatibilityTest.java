package com.datasync.core.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.jdbc.ColumnMeta;
import java.sql.Types;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 类型转换能力表（Lead 决策 5）：预检与运行时共用的判定口径。
 */
class TypeCompatibilityTest {

    private static ColumnMeta col(String name, String typeName, int jdbcType) {
        return new ColumnMeta(name, typeName, jdbcType, true, false, 0);
    }

    private static ColumnMeta sized(String name, String typeName, int jdbcType, int size, int scale) {
        return new ColumnMeta(name, typeName, jdbcType, true, false, 0, size, scale);
    }

    @Test
    @DisplayName("VARCHAR → INT：可转换但有风险（非数字值会成坏行）")
    void varcharToInt() {
        TypeCompatibility.Result r = TypeCompatibility.check(
                col("s", "VARCHAR", Types.VARCHAR), col("n", "INT", Types.INTEGER));
        assertTrue(r.risky(), r.toString());
        assertTrue(TypeCompatibility.canConvert(col("s", "VARCHAR", Types.VARCHAR),
                col("n", "INT", Types.INTEGER)));
    }

    @Test
    @DisplayName("TEXT → INT：语义上不可能安全转换（Lead 举的例子）")
    void textToInt() {
        TypeCompatibility.Result r = TypeCompatibility.check(
                col("remark", "TEXT", Types.CLOB), col("remark", "INT", Types.INTEGER));
        assertTrue(r.incompatible(), r.toString());
        assertTrue(r.message().contains("remark"), r.message());
        assertFalse(TypeCompatibility.canConvert(col("remark", "TEXT", Types.CLOB),
                col("remark", "INT", Types.INTEGER)));
    }

    @Test
    @DisplayName("TEXT → VARCHAR(10)：可转换但可能超长")
    void textToNarrowVarchar() {
        TypeCompatibility.Result r = TypeCompatibility.check(
                sized("s", "TEXT", Types.CLOB, 65535, 0), sized("s", "VARCHAR", Types.VARCHAR, 10, 0));
        assertTrue(r.risky(), r.toString());
        assertTrue(r.message().contains("10"), r.message());
    }

    @Test
    @DisplayName("DATETIME → DATE / TIMESTAMP → DATE：可转换但丢时间部分")
    void timestampToDate() {
        assertTrue(TypeCompatibility.check(col("t", "DATETIME", Types.TIMESTAMP),
                col("d", "DATE", Types.DATE)).risky());
        assertTrue(TypeCompatibility.check(col("t", "TIMESTAMP", Types.TIMESTAMP),
                col("d", "DATE", Types.DATE)).risky());
    }

    @Test
    @DisplayName("BIGINT → INT：范围收缩，可能溢出")
    void bigintToInt() {
        assertTrue(TypeCompatibility.check(col("n", "BIGINT", Types.BIGINT),
                col("n", "INT", Types.INTEGER)).risky());
        assertTrue(TypeCompatibility.check(col("n", "INT", Types.INTEGER),
                col("n", "INT", Types.INTEGER)).ok());
    }

    @Test
    @DisplayName("DECIMAL(38,10) → DECIMAL(10,2)：精度丢失")
    void decimalShrink() {
        assertTrue(TypeCompatibility.check(sized("n", "DECIMAL", Types.DECIMAL, 38, 10),
                sized("n", "DECIMAL", Types.DECIMAL, 10, 2)).risky());
        assertTrue(TypeCompatibility.check(sized("n", "DECIMAL", Types.DECIMAL, 10, 2),
                sized("n", "DECIMAL", Types.DECIMAL, 38, 10)).ok());
    }

    @Test
    @DisplayName("BIT(1) → TINYINT / JSON → TEXT / INT → VARCHAR：安全")
    void safeCombinations() {
        assertTrue(TypeCompatibility.check(col("b", "BIT", Types.BIT),
                col("b", "TINYINT", Types.TINYINT)).ok());
        assertTrue(TypeCompatibility.check(col("j", "JSON", Types.OTHER),
                col("j", "TEXT", Types.CLOB)).ok());
        assertTrue(TypeCompatibility.check(col("n", "INT", Types.INTEGER),
                col("n", "VARCHAR", Types.VARCHAR)).ok());
        assertTrue(TypeCompatibility.check(col("y", "YEAR", Types.DATE),
                col("y", "INT", Types.INTEGER)).ok());
    }

    @Test
    @DisplayName("时间 → 数值 / 数值 → 时间：不可转换")
    void temporalNumericMismatch() {
        assertTrue(TypeCompatibility.check(col("t", "DATETIME", Types.TIMESTAMP),
                col("n", "INT", Types.INTEGER)).incompatible());
        assertTrue(TypeCompatibility.check(col("n", "INT", Types.INTEGER),
                col("t", "DATETIME", Types.TIMESTAMP)).incompatible());
        assertTrue(TypeCompatibility.check(col("n", "INT", Types.INTEGER),
                col("d", "DATE", Types.DATE)).incompatible());
    }

    @Test
    @DisplayName("字符 → 时间：可转换但格式不符会成坏行")
    void stringToTemporal() {
        assertTrue(TypeCompatibility.check(col("s", "VARCHAR", Types.VARCHAR),
                col("t", "DATETIME", Types.TIMESTAMP)).risky());
    }

    @Test
    @DisplayName("元数据缺失时不阻断")
    void nullMeta() {
        assertTrue(TypeCompatibility.check(null, null).ok());
        assertTrue(TypeCompatibility.canConvert(null, null));
    }

    @Test
    @DisplayName("同类型始终安全")
    void sameTypes() {
        assertTrue(TypeCompatibility.check(sized("s", "VARCHAR", Types.VARCHAR, 100, 0),
                sized("s", "VARCHAR", Types.VARCHAR, 100, 0)).ok());
        assertTrue(TypeCompatibility.check(col("n", "BIGINT", Types.BIGINT),
                col("n", "BIGINT", Types.BIGINT)).ok());
    }

    @Test
    @DisplayName("类型名解析工具")
    void typeNames() {
        assertEquals("VARCHAR", TypeNames.base("varchar(255)"));
        assertEquals("DECIMAL", TypeNames.base("DECIMAL(10,2)"));
        assertEquals("INT", TypeNames.base("int unsigned"));
        assertEquals(Types.BIGINT, TypeNames.jdbcTypeOf("bigint(20)"));
        assertEquals(Types.OTHER, TypeNames.jdbcTypeOf("GEOMETRY"));
        assertTrue(TypeNames.describe("DATETIME").contains("日期时间"));
        assertTrue(TypeNames.isMySqlOnly("YEAR"));
        assertFalse(TypeNames.isMySqlOnly("BIGINT"));
    }
}
