package com.datasync.core.dialect;

import com.datasync.core.jdbc.Identifiers;
import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.jdbc.TableRef;
import java.util.ArrayList;
import java.util.List;

/**
 * 方言公共实现：所有拼 SQL 的地方都必须经过 {@link #quote(String)}，
 * 由它做最后一道标识符校验（含点号的分段校验），从而杜绝 SQL 注入。
 */
abstract class AbstractDialect implements SqlDialect {

    /** 单列排序方向：全部升序（水位必须升序，降序会让游标语义失效）。 */
    protected static final String ASC = " ASC";

    abstract char quoteChar();

    @Override
    public String quote(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        String s = identifier.trim();
        char q = quoteChar();
        // 调用方已手动引用过则原样返回（引擎内部不会这么做，留给上层便利）
        if (s.length() >= 2 && s.charAt(0) == q && s.charAt(s.length() - 1) == q) {
            return s;
        }
        String[] parts = s.split("\\.", -1);
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (!Identifiers.isValidPart(part)) {
                throw new IllegalArgumentException("非法标识符[" + identifier
                        + "]：只允许字母、数字、下划线、美元符、汉字");
            }
            if (i > 0) {
                sb.append('.');
            }
            sb.append(q).append(part.replace(String.valueOf(q), String.valueOf(q) + q)).append(q);
        }
        return sb.toString();
    }

    @Override
    public String qualifySchema(String schema, String table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        if (schema == null || schema.isBlank()) {
            return quote(table);
        }
        return quote(schema) + "." + quote(table);
    }

    /** 表引用 → {@code `schema`.`table`}。 */
    protected String qualify(TableRef table) {
        if (table == null || table.isBlank()) {
            throw new IllegalArgumentException("表引用不能为空");
        }
        return qualifySchema(table.schema(), table.table());
    }

    protected String selectList(List<String> selectColumns) {
        if (selectColumns == null || selectColumns.isEmpty()) {
            throw new IllegalArgumentException("查询列不能为空");
        }
        StringBuilder sb = new StringBuilder();
        for (String c : selectColumns) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(quote(c));
        }
        return sb.toString();
    }

    protected String orderBy(List<String> keyColumns) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("排序键不能为空（禁止退化为 OFFSET 分页）");
        }
        StringBuilder sb = new StringBuilder();
        for (String c : keyColumns) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(quote(c)).append(ASC);
        }
        return sb.toString();
    }

    protected String whereClause(List<String> predicates) {
        List<String> effective = new ArrayList<>();
        if (predicates != null) {
            for (String p : predicates) {
                if (p != null && !p.isBlank()) {
                    effective.add(p);
                }
            }
        }
        return effective.isEmpty() ? "" : " WHERE " + String.join(" AND ", effective);
    }

    @Override
    public String buildKeysetSelect(TableRef table, List<String> selectColumns, List<String> keyColumns,
                                    KeysetPosition after, int pageSize, List<String> extraPredicates) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("键集分页必须有排序键（单列主键/多列主键/唯一键），禁止 OFFSET 分页");
        }
        List<String> predicates = new ArrayList<>();
        if (extraPredicates != null) {
            predicates.addAll(extraPredicates);
        }
        String keyset = buildKeysetPredicate(keyColumns, after);
        if (!keyset.isEmpty()) {
            predicates.add(keyset);
        }
        return "SELECT " + selectList(selectColumns)
                + " FROM " + qualify(table)
                + whereClause(predicates)
                + " ORDER BY " + orderBy(keyColumns)
                + " " + limitClause(pageSize);
    }

    @Override
    public String buildWatermarkSelect(TableRef table, List<String> selectColumns, String cursorColumn,
                                       String lowerExclusive, String upperInclusive, List<String> keyColumns,
                                       KeysetPosition after, int pageSize) {
        if (cursorColumn == null || cursorColumn.isBlank()) {
            throw new IllegalArgumentException("水位列不能为空");
        }
        String cursor = quote(cursorColumn);
        List<String> predicates = new ArrayList<>();
        if (lowerExclusive != null) {
            predicates.add(cursor + " > ?");
        }
        if (upperInclusive != null) {
            predicates.add(cursor + " <= ?");
        }
        // 顺序键 = 水位列 + 唯一键，保证同一水位值下的多行也能稳定翻页
        List<String> orderedKeys = new ArrayList<>();
        orderedKeys.add(cursorColumn);
        if (keyColumns != null) {
            for (String k : keyColumns) {
                if (k != null && !k.isBlank() && !k.equalsIgnoreCase(cursorColumn)) {
                    orderedKeys.add(k);
                }
            }
        }
        String keyset = buildKeysetPredicate(orderedKeys, after);
        if (!keyset.isEmpty()) {
            predicates.add(keyset);
        }
        return "SELECT " + selectList(selectColumns)
                + " FROM " + qualify(table)
                + whereClause(predicates)
                + " ORDER BY " + orderBy(orderedKeys)
                + " " + limitClause(pageSize);
    }

    @Override
    public String buildCount(TableRef table, List<String> extraPredicates) {
        return "SELECT COUNT(*) FROM " + qualify(table) + whereClause(extraPredicates);
    }

    @Override
    public String buildDeleteAll(TableRef table) {
        return "DELETE FROM " + qualify(table);
    }

    @Override
    public String buildInsert(TableRef table, List<String> columns) {
        return buildInsertMultiRow(table, columns, 1);
    }

    @Override
    public String buildInsertMultiRow(TableRef table, List<String> columns, int rowCount) {
        if (columns == null || columns.isEmpty()) {
            throw new IllegalArgumentException("插入列不能为空");
        }
        if (rowCount <= 0) {
            throw new IllegalArgumentException("行数必须大于 0，当前: " + rowCount);
        }
        StringBuilder cols = new StringBuilder();
        StringBuilder marks = new StringBuilder();
        for (String c : columns) {
            if (cols.length() > 0) {
                cols.append(", ");
                marks.append(", ");
            }
            cols.append(quote(c));
            marks.append('?');
        }
        StringBuilder values = new StringBuilder();
        for (int r = 0; r < rowCount; r++) {
            if (r > 0) {
                values.append(", ");
            }
            values.append('(').append(marks).append(')');
        }
        return "INSERT INTO " + qualify(table) + " (" + cols + ") VALUES " + values;
    }

    @Override
    public String buildMaxValue(TableRef table, String column) {
        if (column == null || column.isBlank()) {
            throw new IllegalArgumentException("列名不能为空");
        }
        return "SELECT MAX(" + quote(column) + ") FROM " + qualify(table);
    }

    @Override
    public String buildDropTable(TableRef table) {
        return "DROP TABLE " + qualify(table);
    }

    /** 供子类复用：从列集合里剔除键列，保持原顺序。 */
    protected static List<String> minusKeys(List<String> columns, List<String> keyColumns) {
        List<String> out = new ArrayList<>();
        for (String c : columns) {
            boolean isKey = false;
            if (keyColumns != null) {
                for (String k : keyColumns) {
                    if (k != null && k.equalsIgnoreCase(c)) {
                        isKey = true;
                        break;
                    }
                }
            }
            if (!isKey) {
                out.add(c);
            }
        }
        return out;
    }

    protected static void requireUpsertKeys(List<String> keyColumns) {
        if (keyColumns == null || keyColumns.isEmpty()) {
            throw new IllegalArgumentException("幂等 upsert 需要至少一个键列；若目标表确实无主键/唯一键，"
                    + "请改用全量 TRUNCATE/DELETE/SWAP 策略（引擎会降级为纯 INSERT）");
        }
    }
}
