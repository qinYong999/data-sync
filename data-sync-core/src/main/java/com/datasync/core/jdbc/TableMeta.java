package com.datasync.core.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 表元数据：列集合 + 主键 + 唯一键集合。
 *
 * <p>{@code uniqueKeys()} 是排序键回退链的最后一环（单列主键 → 多列主键 → 唯一键），
 * 没有它就只能退化成 LIMIT/OFFSET，这是契约明令禁止的。
 */
public final class TableMeta {

    private final TableRef table;
    private final List<ColumnMeta> columns;
    private final List<ColumnMeta> primaryKeys;
    private final List<List<ColumnMeta>> uniqueKeys;

    public TableMeta(TableRef table, List<ColumnMeta> columns, List<ColumnMeta> primaryKeys,
                     List<List<ColumnMeta>> uniqueKeys) {
        this.table = table;
        this.columns = Collections.unmodifiableList(new ArrayList<>(columns == null ? List.of() : columns));
        this.primaryKeys = Collections.unmodifiableList(new ArrayList<>(primaryKeys == null ? List.of() : primaryKeys));
        List<List<ColumnMeta>> uk = new ArrayList<>();
        if (uniqueKeys != null) {
            for (List<ColumnMeta> k : uniqueKeys) {
                if (k != null && !k.isEmpty()) {
                    uk.add(Collections.unmodifiableList(new ArrayList<>(k)));
                }
            }
        }
        this.uniqueKeys = Collections.unmodifiableList(uk);
    }

    public static TableMeta empty(TableRef table) {
        return new TableMeta(table, List.of(), List.of(), List.of());
    }

    public TableRef table() {
        return table;
    }

    public List<ColumnMeta> columns() {
        return columns;
    }

    public List<ColumnMeta> primaryKeys() {
        return primaryKeys;
    }

    /** 唯一键（含唯一索引）列表，每个元素是一组有序的列。 */
    public List<List<ColumnMeta>> uniqueKeys() {
        return uniqueKeys;
    }

    /** 按列名（忽略大小写）查找，找不到返回 null。 */
    public ColumnMeta find(String nameIgnoringCase) {
        if (nameIgnoringCase == null) {
            return null;
        }
        String target = nameIgnoringCase.trim();
        int dot = target.lastIndexOf('.');
        if (dot >= 0) {
            target = target.substring(dot + 1);
        }
        for (ColumnMeta c : columns) {
            if (c.nameIs(target)) {
                return c;
            }
        }
        return null;
    }

    /** 所有列名（原样大小写）。 */
    public List<String> columnNames() {
        List<String> names = new ArrayList<>(columns.size());
        for (ColumnMeta c : columns) {
            names.add(c.name());
        }
        return names;
    }

    public boolean hasPrimaryKey() {
        return !primaryKeys.isEmpty();
    }

    /**
     * 可选的排序/匹配键回退链：单列主键 → 多列主键 → 第一个唯一键。
     * 返回空列表表示该表没有任何可用键。
     */
    public List<ColumnMeta> firstUsableKey() {
        if (primaryKeys.size() == 1) {
            return primaryKeys;
        }
        if (!primaryKeys.isEmpty()) {
            return primaryKeys;
        }
        for (List<ColumnMeta> uk : uniqueKeys) {
            if (!uk.isEmpty()) {
                return uk;
            }
        }
        return List.of();
    }

    @Override
    public String toString() {
        return "TableMeta{" + (table == null ? "?" : table.name()) + ", columns=" + columns.size()
                + ", pk=" + primaryKeys + ", uniqueKeys=" + uniqueKeys.size() + "}";
    }
}
