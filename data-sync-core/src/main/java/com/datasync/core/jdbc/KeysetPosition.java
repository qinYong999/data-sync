package com.datasync.core.jdbc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 键集分页（keyset paging）的游标位置。
 *
 * <p>{@link #first()} 表示"第一页"（没有 {@code WHERE key > ?} 谓词）；
 * 其余情况由 {@link #of(List, List)} 携带上一次抓取的最后一行的键值。
 *
 * <p>约定：{@code keyColumns} 的顺序必须与 SQL 里 {@code ORDER BY} 的键列顺序完全一致，
 * {@code values} 一一对应。多列时必须做元组展开比较（{@code (k1>?) OR (k1=? AND k2>?)}）或
 * 元组比较（{@code (k1,k2) > (?,?)}），绝不允许只比较第一列。
 */
public final class KeysetPosition {

    private static final KeysetPosition FIRST = new KeysetPosition(List.of(), List.of());

    private final List<String> keyColumns;
    private final List<Object> values;

    private KeysetPosition(List<String> keyColumns, List<Object> values) {
        this.keyColumns = Collections.unmodifiableList(new ArrayList<>(keyColumns));
        this.values = Collections.unmodifiableList(new ArrayList<>(values));
    }

    /** 第一页：无游标谓词。 */
    public static KeysetPosition first() {
        return FIRST;
    }

    /** 带游标位置。 */
    public static KeysetPosition of(List<String> keyColumns, List<Object> values) {
        List<String> cols = keyColumns == null ? List.of() : keyColumns;
        List<Object> vals = values == null ? List.of() : values;
        if (cols.size() != vals.size()) {
            throw new IllegalArgumentException("键集游标的列数(" + cols.size() + ")与值个数(" + vals.size() + ")不一致");
        }
        if (cols.isEmpty()) {
            return FIRST;
        }
        return new KeysetPosition(cols, vals);
    }

    public List<String> keyColumns() {
        return keyColumns;
    }

    public List<Object> values() {
        return values;
    }

    public boolean isFirst() {
        return values.isEmpty();
    }

    public int size() {
        return values.size();
    }

    @Override
    public String toString() {
        return isFirst() ? "KeysetPosition{first}" : "KeysetPosition{" + keyColumns + "=" + values + "}";
    }
}
