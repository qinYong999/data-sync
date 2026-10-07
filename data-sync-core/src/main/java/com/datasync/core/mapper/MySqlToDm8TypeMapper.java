package com.datasync.core.mapper;

import com.datasync.core.jdbc.ColumnMeta;
import java.util.Locale;
import java.util.Map;

/**
 * MySQL → 达梦 DM8 类型映射实现。
 *
 * <p><b>⚠ 未在真实达梦实例验证（契约 D13）</b>：本机没有 DM8 实例与达梦 JDBC 驱动，
 * 类型名映射与取值归一仅通过单元测试断言。
 */
public final class MySqlToDm8TypeMapper implements TypeMapper {

    private static final DefaultValueConverter CONVERTER = new DefaultValueConverter();

    private static final Map<String, String> TYPE_MAP = Map.ofEntries(
            Map.entry("TINYINT", "SMALLINT"),
            Map.entry("SMALLINT", "SMALLINT"),
            Map.entry("MEDIUMINT", "INT"),
            Map.entry("INT", "INT"),
            Map.entry("INTEGER", "INT"),
            Map.entry("BIGINT", "BIGINT"),
            Map.entry("FLOAT", "FLOAT"),
            Map.entry("DOUBLE", "DOUBLE"),
            Map.entry("DECIMAL", "DECIMAL"),
            Map.entry("NUMERIC", "DECIMAL"),
            Map.entry("CHAR", "CHAR"),
            Map.entry("VARCHAR", "VARCHAR"),
            Map.entry("TINYTEXT", "VARCHAR(255)"),
            Map.entry("TEXT", "TEXT"),
            Map.entry("MEDIUMTEXT", "TEXT"),
            Map.entry("LONGTEXT", "CLOB"),
            Map.entry("BLOB", "BLOB"),
            Map.entry("TINYBLOB", "BLOB"),
            Map.entry("MEDIUMBLOB", "BLOB"),
            Map.entry("LONGBLOB", "BLOB"),
            Map.entry("DATE", "DATE"),
            Map.entry("DATETIME", "TIMESTAMP"),
            Map.entry("TIMESTAMP", "TIMESTAMP"),
            Map.entry("TIME", "TIME"),
            Map.entry("YEAR", "INT"),
            Map.entry("BINARY", "BLOB"),
            Map.entry("VARBINARY", "BLOB"),
            Map.entry("BIT", "INT"),
            Map.entry("JSON", "CLOB"),
            Map.entry("SET", "TEXT"),
            Map.entry("ENUM", "VARCHAR(255)")
    );

    @Override
    public String mapTypeName(String sourceTypeName) {
        String base = TypeNames.base(sourceTypeName);
        String mapped = TYPE_MAP.get(base);
        if (mapped != null) {
            return mapped;
        }
        if (base.isEmpty()) {
            return "VARCHAR";
        }
        // 未登记的类型：原样返回，交给预检的 TYPE_INCOMPATIBLE 判定，不在这里假装支持
        return base;
    }

    @Override
    public Object mapValue(Object sourceValue, String targetTypeName) {
        if (sourceValue == null) {
            return null;
        }
        // 先按 MySQL 源类型做一次归一（BIT(1)→Boolean、YEAR→Integer 等），再按 DM8 目标类型收敛
        String target = TypeNames.base(targetTypeName);
        int jdbcType = TypeNames.jdbcTypeOf(target);
        if (jdbcType == java.sql.Types.OTHER) {
            return sourceValue;
        }
        ColumnMeta targetMeta = new ColumnMeta("target", target, jdbcType, true, false, 0);
        return CONVERTER.convert(sourceValue, null, targetMeta);
    }

    @Override
    public String describe(String sourceTypeName) {
        String base = TypeNames.base(sourceTypeName);
        String mapped = TYPE_MAP.get(base);
        if (mapped == null) {
            return TypeNames.describe(sourceTypeName);
        }
        return TypeNames.describe(sourceTypeName) + " → 达梦 " + mapped;
    }

    /** 归一化后的类型名（大写、无参数）。 */
    static String normalize(String typeName) {
        return typeName == null ? "" : typeName.trim().toUpperCase(Locale.ROOT);
    }
}
