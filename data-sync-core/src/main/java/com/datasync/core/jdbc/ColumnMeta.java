package com.datasync.core.jdbc;

import java.sql.Types;

/**
 * 列元数据（不可变）。
 *
 * <p>{@code typeName} 是数据库自己报的类型名（MySQL 如 {@code VARCHAR}/{@code BIT}/{@code YEAR}，
 * DM8 如 {@code VARCHAR2}/{@code NUMBER}），类型兼容性判断与值转换都以它 + {@code jdbcType} 为准。
 */
public final class ColumnMeta {

    private final String name;
    private final String typeName;
    private final int jdbcType;
    private final boolean nullable;
    private final boolean primaryKey;
    private final int ordinal;
    /** 字符长度 / 数值精度（未知为 0）。 */
    private final int columnSize;
    /** 小数位数（未知为 0）。 */
    private final int decimalDigits;

    public ColumnMeta(String name, String typeName, int jdbcType, boolean nullable,
                      boolean primaryKey, int ordinal) {
        this(name, typeName, jdbcType, nullable, primaryKey, ordinal, 0, 0);
    }

    public ColumnMeta(String name, String typeName, int jdbcType, boolean nullable,
                      boolean primaryKey, int ordinal, int columnSize, int decimalDigits) {
        this.name = name;
        this.typeName = typeName == null ? "" : typeName;
        this.jdbcType = jdbcType;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
        this.ordinal = ordinal;
        this.columnSize = columnSize;
        this.decimalDigits = decimalDigits;
    }

    public static ColumnMeta of(String name, int jdbcType) {
        return new ColumnMeta(name, typeNameOf(jdbcType), jdbcType, true, false, 0);
    }

    public String name() {
        return name;
    }

    public String typeName() {
        return typeName;
    }

    public int jdbcType() {
        return jdbcType;
    }

    public boolean nullable() {
        return nullable;
    }

    public boolean primaryKey() {
        return primaryKey;
    }

    public int ordinal() {
        return ordinal;
    }

    /** 字符长度 / 数值精度（元数据未提供时为 0，表示"未知，不限制"）。 */
    public int columnSize() {
        return columnSize;
    }

    /** 小数位数（元数据未提供时为 0）。 */
    public int decimalDigits() {
        return decimalDigits;
    }

    /** 返回一个标记为/取消标记为主键的副本。 */
    public ColumnMeta withPrimaryKey(boolean pk) {
        return new ColumnMeta(name, typeName, jdbcType, nullable, pk, ordinal, columnSize, decimalDigits);
    }

    /** 列名是否相同（忽略大小写）。 */
    public boolean nameIs(String other) {
        return name != null && other != null && name.equalsIgnoreCase(other.trim());
    }

    /** 是否为数值类型（可用于单调水位）。 */
    public boolean isNumeric() {
        return switch (jdbcType) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER, Types.BIGINT,
                 Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC, Types.DECIMAL -> true;
            default -> false;
        };
    }

    /** 是否为时间/日期类型（可用于单调水位）。 */
    public boolean isTemporal() {
        return switch (jdbcType) {
            case Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE -> true;
            // MySQL YEAR 在驱动里可能报 DATE，也可能报 SMALLINT；DM8 的 YEAR 报 SMALLINT
            default -> "YEAR".equalsIgnoreCase(typeName) || "DATETIME".equalsIgnoreCase(typeName);
        };
    }

    /** 是否可安全用于水位排序（数值或日期时间）。 */
    public boolean isSortableWatermark() {
        return isNumeric() || isTemporal();
    }

    /** 是否为二进制类型。 */
    public boolean isBinary() {
        return switch (jdbcType) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> true;
            default -> false;
        };
    }

    /** 是否为字符类型。 */
    public boolean isCharacter() {
        return switch (jdbcType) {
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> true;
            default -> false;
        };
    }

    private static String typeNameOf(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT -> "TINYINT";
            case Types.SMALLINT -> "SMALLINT";
            case Types.INTEGER -> "INTEGER";
            case Types.BIGINT -> "BIGINT";
            case Types.DECIMAL -> "DECIMAL";
            case Types.NUMERIC -> "NUMERIC";
            case Types.FLOAT -> "FLOAT";
            case Types.REAL -> "REAL";
            case Types.DOUBLE -> "DOUBLE";
            case Types.CHAR -> "CHAR";
            case Types.VARCHAR -> "VARCHAR";
            case Types.DATE -> "DATE";
            case Types.TIME -> "TIME";
            case Types.TIMESTAMP -> "TIMESTAMP";
            case Types.TIMESTAMP_WITH_TIMEZONE -> "TIMESTAMP WITH TIME ZONE";
            case Types.BINARY -> "BINARY";
            case Types.VARBINARY -> "VARBINARY";
            case Types.BLOB -> "BLOB";
            case Types.CLOB -> "CLOB";
            case Types.BOOLEAN -> "BOOLEAN";
            case Types.BIT -> "BIT";
            default -> "TYPE_" + jdbcType;
        };
    }

    @Override
    public String toString() {
        return name + " " + typeName + "(jdbcType=" + jdbcType + ")"
                + (primaryKey ? " PK" : "") + (nullable ? "" : " NOT NULL");
    }
}
