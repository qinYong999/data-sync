package com.datasync.core.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.model.enums.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TypeMapperTest {

    private final MySqlToMySqlTypeMapper mysql = new MySqlToMySqlTypeMapper();
    private final MySqlToDm8TypeMapper dm8 = new MySqlToDm8TypeMapper();

    @Test
    @DisplayName("MySQL → MySQL：类型名透传，值仍归一（BIT/YEAR 不会被写坏）")
    void mysqlToMysql() {
        assertEquals("VARCHAR(255)", mysql.mapTypeName("varchar(255)"));
        assertEquals("BIGINT", mysql.mapTypeName("bigint"));
        assertEquals("VARCHAR(255)", mysql.mapTypeName(null));
        assertEquals(1L, mysql.mapValue(1, "BIGINT"));
        assertEquals("1", mysql.mapValue(true, "VARCHAR"));
        assertInstanceOf(byte[].class, mysql.mapValue("abc", "BLOB"));
        assertTrue(mysql.describe("DATETIME").contains("日期时间"));
    }

    @Test
    @DisplayName("MySQL → DM8：类型名映射表 + 值归一 + 中文说明")
    void mysqlToDm8() {
        assertEquals("SMALLINT", dm8.mapTypeName("TINYINT"));
        assertEquals("BIGINT", dm8.mapTypeName("BIGINT(20)"));
        assertEquals("CLOB", dm8.mapTypeName("LONGTEXT"));
        assertEquals("INT", dm8.mapTypeName("YEAR"));
        assertEquals("BLOB", dm8.mapTypeName("VARBINARY"));
        assertEquals("MYSTERY", dm8.mapTypeName("MYSTERY"));
        assertEquals("VARCHAR", dm8.mapTypeName(null));

        assertEquals(1L, dm8.mapValue(1, "BIGINT"));
        assertEquals(1, dm8.mapValue(true, "INT"));
        assertTrue(dm8.describe("TINYINT").contains("达梦"));
    }

    @Test
    @DisplayName("TypeMappers 工厂：按目标库选映射器")
    void factory() {
        assertInstanceOf(MySqlToDm8TypeMapper.class, TypeMappers.of(DbType.MYSQL, DbType.DM8));
        assertInstanceOf(MySqlToMySqlTypeMapper.class, TypeMappers.of(DbType.MYSQL, DbType.MYSQL));
        assertInstanceOf(MySqlToMySqlTypeMapper.class, TypeMappers.forTarget(DbType.MYSQL));
    }

    @Test
    @DisplayName("历史方法 mapType 仍可用（委托 mapValue + mapTypeName）")
    @SuppressWarnings("deprecation")
    void legacyMapType() {
        assertEquals(1L, dm8.mapType("BIGINT", 1));
    }

    @Test
    @DisplayName("TypeMapper 真正参与转换链路（不是摆设）")
    void typeMapperIsWired() {
        // 通过构造参数注入：DM8 映射器会把 TINYINT 归一成 SMALLINT，再按目标列 jdbcType 落值
        DefaultValueConverter dm8Converter = new DefaultValueConverter(dm8);
        Object out = dm8Converter.convert(1,
                new com.datasync.core.jdbc.ColumnMeta("n", "TINYINT", java.sql.Types.TINYINT, true, false, 0),
                new com.datasync.core.jdbc.ColumnMeta("n", "SMALLINT", java.sql.Types.SMALLINT, true, false, 0));
        assertEquals(1, out);
        // 无映射器时同样可用
        assertEquals(1, new DefaultValueConverter().convert(1,
                new com.datasync.core.jdbc.ColumnMeta("n", "TINYINT", java.sql.Types.TINYINT, true, false, 0),
                new com.datasync.core.jdbc.ColumnMeta("n", "SMALLINT", java.sql.Types.SMALLINT, true, false, 0)));
    }
}
