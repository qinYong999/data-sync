package com.datasync.core.mapper;

import java.util.Map;

/**
 * MySQL → 达梦8 类型映射实现
 */
public class MySqlToDm8TypeMapper implements TypeMapper {

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
        Map.entry("CHAR", "CHAR"),
        Map.entry("VARCHAR", "VARCHAR"),
        Map.entry("TINYTEXT", "VARCHAR(255)"),
        Map.entry("TEXT", "TEXT"),
        Map.entry("MEDIUMTEXT", "TEXT"),
        Map.entry("LONGTEXT", "CLOB"),
        Map.entry("BLOB", "BLOB"),
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
    public Object mapType(String sourceType, Object value) {
        if (value == null) return null;
        String upperType = sourceType.toUpperCase();
        return switch (upperType) {
            case "TINYINT" -> ((Number) value).shortValue();
            case "YEAR" -> value instanceof String s ? Integer.parseInt(s) : ((Number) value).intValue();
            case "BIT" -> {
                if (value instanceof Boolean) yield ((Boolean) value) ? 1 : 0;
                yield ((Number) value).intValue();
            }
            default -> value;
        };
    }

    @Override
    public String mapTypeName(String sourceType) {
        if (sourceType == null) return "VARCHAR";
        String upper = sourceType.toUpperCase();
        String baseType = upper.contains("(") ? upper.substring(0, upper.indexOf('(')) : upper;
        return TYPE_MAP.getOrDefault(baseType, "VARCHAR");
    }
}