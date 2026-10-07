package com.datasync.core.jdbc;

import java.util.Objects;

/**
 * 表引用：{@code schema} + {@code table}。
 *
 * <p>{@code schema} 可空；对 MySQL 而言它对应 JDBC 的 catalog（即 database 名），
 * 对 DM8 对应 schema。元数据读取时会同时按 catalog/schema 两种口径探测，调用方不用关心差异。
 */
public final class TableRef {

    private final String schema;
    private final String table;

    private TableRef(String schema, String table) {
        this.schema = schema == null || schema.isBlank() ? null : schema.trim();
        this.table = table == null ? null : table.trim();
    }

    /** 单段表名（无库名/模式名）。 */
    public static TableRef of(String table) {
        return new TableRef(null, table);
    }

    /** 指定 schema（MySQL 下即 database）的表。 */
    public static TableRef of(String schema, String table) {
        return new TableRef(schema, table);
    }

    /** 解析 {@code schema.table} / {@code catalog.schema.table} / {@code table}。 */
    public static TableRef parse(String qualified) {
        if (qualified == null || qualified.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        String s = qualified.trim();
        int lastDot = s.lastIndexOf('.');
        if (lastDot < 0) {
            return new TableRef(null, s);
        }
        String schemaPart = s.substring(0, lastDot);
        String tablePart = s.substring(lastDot + 1);
        // 三段式只取最后两段：MySQL 的 catalog.schema.table 三段写法在驱动层也只认前两段
        int prevDot = schemaPart.lastIndexOf('.');
        if (prevDot >= 0) {
            schemaPart = schemaPart.substring(prevDot + 1);
        }
        return new TableRef(schemaPart, tablePart);
    }

    public String schema() {
        return schema;
    }

    public String table() {
        return table;
    }

    /** {@code schema.table}（schema 为空时退化为 {@code table}）。 */
    public String name() {
        return schema == null ? table : schema + "." + table;
    }

    /** 限定名是否为空。 */
    public boolean isBlank() {
        return table == null || table.isEmpty();
    }

    /** 只保留表名。 */
    public TableRef withoutSchema() {
        return new TableRef(null, table);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TableRef other)) {
            return false;
        }
        return Objects.equals(schema, other.schema) && Objects.equals(table, other.table);
    }

    @Override
    public int hashCode() {
        return Objects.hash(schema, table);
    }

    @Override
    public String toString() {
        return name();
    }
}
