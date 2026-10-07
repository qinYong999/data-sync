package com.datasync.core.mapper;

import java.sql.Types;
import java.util.Locale;

/**
 * 类型名工具：解析「带参数的数据库类型名」并映射到 {@link java.sql.Types}。
 *
 * <p>MySQL 的类型名形如 {@code VARCHAR(255)}/{@code DECIMAL(10,2)}/{@code ENUM('a','b')}，
 * DM8 形如 {@code VARCHAR2(255)}/{@code NUMBER(10,2)}；这里统一取基名做判断。
 */
public final class TypeNames {

    private TypeNames() {
    }

    /** 取基名（去参数、去空格、转大写）。 */
    public static String base(String typeName) {
        if (typeName == null) {
            return "";
        }
        String t = typeName.trim().toUpperCase(Locale.ROOT);
        int paren = t.indexOf('(');
        if (paren > 0) {
            t = t.substring(0, paren).trim();
        }
        // MySQL 的 UNSIGNED / ZEROFILL 修饰
        int space = t.indexOf(' ');
        if (space > 0) {
            t = t.substring(0, space);
        }
        return t;
    }

    /** 基名 → java.sql.Types；未知类型返回 {@link Types#OTHER}。 */
    public static int jdbcTypeOf(String typeName) {
        return switch (base(typeName)) {
            case "TINYINT" -> Types.TINYINT;
            case "SMALLINT", "YEAR" -> Types.SMALLINT;
            case "MEDIUMINT", "INT", "INTEGER", "INT4", "SERIAL" -> Types.INTEGER;
            case "BIGINT", "INT8", "BIGSERIAL" -> Types.BIGINT;
            case "FLOAT" -> Types.FLOAT;
            case "DOUBLE", "REAL", "FLOAT8" -> Types.DOUBLE;
            case "DECIMAL", "NUMERIC", "NUMBER", "DEC", "FIXED" -> Types.DECIMAL;
            case "BIT" -> Types.BIT;
            case "BOOL", "BOOLEAN" -> Types.BOOLEAN;
            case "CHAR", "NCHAR", "CHARACTER" -> Types.CHAR;
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2", "CHARACTER VARYING" -> Types.VARCHAR;
            case "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB", "TINYTEXT " -> Types.CLOB;
            case "JSON" -> Types.LONGVARCHAR;
            case "ENUM", "SET" -> Types.VARCHAR;
            case "BINARY" -> Types.BINARY;
            case "VARBINARY", "RAW" -> Types.VARBINARY;
            case "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "IMAGE" -> Types.BLOB;
            case "DATE" -> Types.DATE;
            case "TIME" -> Types.TIME;
            case "DATETIME", "TIMESTAMP", "TIMESTAMP WITHOUT TIME ZONE" -> Types.TIMESTAMP;
            case "TIMESTAMP WITH TIME ZONE", "TIMESTAMPTZ" -> Types.TIMESTAMP_WITH_TIMEZONE;
            default -> Types.OTHER;
        };
    }

    /** 中文类型说明（日志/界面展示用）。 */
    public static String describe(String typeName) {
        return switch (base(typeName)) {
            case "TINYINT" -> "微整型（-128~127）";
            case "SMALLINT" -> "短整型";
            case "MEDIUMINT", "INT", "INTEGER" -> "整型";
            case "BIGINT" -> "长整型";
            case "FLOAT" -> "单精度浮点";
            case "DOUBLE", "REAL" -> "双精度浮点";
            case "DECIMAL", "NUMERIC", "NUMBER" -> "定点小数（精确数值）";
            case "BIT" -> "位类型（BIT(1) 按布尔处理）";
            case "BOOL", "BOOLEAN" -> "布尔";
            case "CHAR", "NCHAR" -> "定长字符串";
            case "VARCHAR", "VARCHAR2", "NVARCHAR", "NVARCHAR2" -> "变长字符串";
            case "TEXT", "TINYTEXT", "MEDIUMTEXT", "LONGTEXT", "CLOB", "NCLOB" -> "大文本";
            case "JSON" -> "JSON 文本";
            case "ENUM", "SET" -> "枚举/集合（按字符串）";
            case "BINARY" -> "定长二进制";
            case "VARBINARY", "RAW" -> "变长二进制";
            case "BLOB", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB" -> "二进制大对象";
            case "DATE" -> "日期";
            case "TIME" -> "时间";
            case "DATETIME", "TIMESTAMP" -> "日期时间";
            case "YEAR" -> "年份（按整型处理）";
            default -> "未知类型（" + typeName + "），按原值透传";
        };
    }

    /** 是否为 MySQL 专有类型（DM8 无直接对应，需要改名）。 */
    public static boolean isMySqlOnly(String typeName) {
        return switch (base(typeName)) {
            case "TINYINT", "MEDIUMINT", "YEAR", "ENUM", "SET", "JSON", "TINYTEXT", "MEDIUMTEXT",
                 "LONGTEXT", "TINYBLOB", "MEDIUMBLOB", "LONGBLOB" -> true;
            default -> false;
        };
    }
}
