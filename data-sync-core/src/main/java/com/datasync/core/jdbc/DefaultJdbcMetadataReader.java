package com.datasync.core.jdbc;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 默认元数据读取器：全部基于 {@link DatabaseMetaData}，MySQL / DM8 通用。
 *
 * <p>三个必须处理的坑：
 * <ol>
 *   <li><b>catalog 与 schema 的口径差异</b>：MySQL 把 database 放在 catalog 里、schema 恒为 null；
 *       DM8 反过来。这里先按 catalog → schema → 两者 → 通配的顺序"解析出真实三元组"
 *       （catalog/schema/表名），后续查询都用解析结果，避免反复试探。</li>
 *   <li><b>标识符大小写</b>：DM8 与 H2 把未加引号的标识符存成大写，MySQL 在 Linux 上区分大小写。
 *       因此表名按「原样 → 大写 → 小写」依次尝试；解析出真实名字后就以数据库返回的名字为准。</li>
 *   <li><b>元数据模式串的通配符</b>：{@code getColumns} 的 tableNamePattern 里 {@code _} 是单字符通配符，
 *       表名 {@code user_data} 会误匹配 {@code userXdata}，必须按驱动的转义符转义。</li>
 * </ol>
 */
public final class DefaultJdbcMetadataReader implements JdbcMetadataReader {

    private static final String[] TABLE_TYPES = {"TABLE", "VIEW"};

    @Override
    public TableMeta read(Connection c, TableRef table) {
        if (c == null || table == null || table.isBlank()) {
            throw new IllegalArgumentException("读取表元数据需要连接与表名");
        }
        try {
            Resolved resolved = resolve(c, table);
            if (resolved == null) {
                return TableMeta.empty(table);
            }
            List<RawColumn> columns = readColumns(c, resolved);
            if (columns.isEmpty()) {
                return TableMeta.empty(table);
            }
            List<String> pkNames = readPrimaryKeyNames(c, resolved);
            List<List<String>> uniqueKeyNames = readUniqueKeyNames(c, resolved, false);

            List<ColumnMeta> metas = new ArrayList<>(columns.size());
            for (RawColumn rc : columns) {
                boolean pk = false;
                for (String p : pkNames) {
                    if (p.equalsIgnoreCase(rc.name())) {
                        pk = true;
                        break;
                    }
                }
                metas.add(new ColumnMeta(rc.name(), rc.typeName(), rc.jdbcType(), rc.nullable(), pk,
                        rc.ordinal(), rc.columnSize(), rc.decimalDigits()));
            }

            List<ColumnMeta> pkColumns = new ArrayList<>();
            for (String p : pkNames) {
                ColumnMeta m = findIn(metas, p);
                if (m != null) {
                    pkColumns.add(m);
                }
            }

            List<List<ColumnMeta>> uniqueKeys = new ArrayList<>();
            for (List<String> key : uniqueKeyNames) {
                List<ColumnMeta> group = new ArrayList<>();
                for (String k : key) {
                    ColumnMeta m = findIn(metas, k);
                    if (m != null) {
                        group.add(m);
                    }
                }
                if (!group.isEmpty()) {
                    uniqueKeys.add(group);
                }
            }
            return new TableMeta(table, metas, pkColumns, uniqueKeys);
        } catch (SQLException e) {
            throw new IllegalStateException("读取表[" + table.name() + "]元数据失败: " + e.getMessage(), e);
        }
    }

    /** 表（或视图）是否存在。 */
    boolean exists(Connection c, TableRef table) {
        try {
            return resolve(c, table) != null;
        } catch (SQLException e) {
            throw new IllegalStateException("检测表[" + table.name() + "]是否存在失败: " + e.getMessage(), e);
        }
    }

    /**
     * 所有索引的「首列」列名（含唯一索引与非唯一索引）。
     *
     * <p>用于预检 INCR_COLUMN_NO_INDEX 建议：只有当某列是某个索引的第一列时，
     * 以它为条件的范围扫描才能命中索引。
     */
    List<String> indexedLeadColumns(Connection c, TableRef table) {
        try {
            Resolved resolved = resolve(c, table);
            if (resolved == null) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            // 必须包含非唯一索引：增量列上建普通索引才是最常见的优化方式
            for (List<String> group : readUniqueKeyNames(c, resolved, false)) {
                if (!group.isEmpty() && !out.contains(group.get(0))) {
                    out.add(group.get(0));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("读取表[" + table.name() + "]索引信息失败: " + e.getMessage(), e);
        }
    }

    /** 连接可见的表/视图列表。 */
    List<TableRef> listTables(Connection c) {
        List<TableRef> out = new ArrayList<>();
        try {
            DatabaseMetaData md = c.getMetaData();
            try (ResultSet rs = md.getTables(safeCatalog(c), safeSchema(c), "%", TABLE_TYPES)) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String tableSchema = rs.getString("TABLE_SCHEM");
                    String tableCat = rs.getString("TABLE_CAT");
                    String effective = tableSchema != null && !tableSchema.isBlank() ? tableSchema : tableCat;
                    TableRef ref = TableRef.of(effective, tableName);
                    if (!out.contains(ref)) {
                        out.add(ref);
                    }
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("列举表失败: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // 内部实现
    // ------------------------------------------------------------------

    /** 解析出数据库认账的 (catalog, schema, 表名) 三元组；表不存在返回 null。 */
    private Resolved resolve(Connection c, TableRef table) throws SQLException {
        DatabaseMetaData md = c.getMetaData();
        for (String[] probe : candidates(c, table)) {
            for (String name : nameVariants(table.table())) {
                try (ResultSet rs = md.getTables(probe[0], probe[1], escape(c, name), TABLE_TYPES)) {
                    if (rs.next()) {
                        return new Resolved(rs.getString("TABLE_CAT"), rs.getString("TABLE_SCHEM"),
                                rs.getString("TABLE_NAME"));
                    }
                }
            }
        }
        return null;
    }

    private List<RawColumn> readColumns(Connection c, Resolved r) throws SQLException {
        List<RawColumn> rows = new ArrayList<>();
        try (ResultSet rs = c.getMetaData().getColumns(r.catalog(), r.schema(), escape(c, r.tableName()), "%")) {
            while (rs.next()) {
                rows.add(new RawColumn(
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("DATA_TYPE"),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        rs.getInt("ORDINAL_POSITION"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS")));
            }
        }
        rows.sort(Comparator.comparingInt(RawColumn::ordinal));
        return rows;
    }

    private List<String> readPrimaryKeyNames(Connection c, Resolved r) throws SQLException {
        Map<Integer, String> bySeq = new LinkedHashMap<>();
        try (ResultSet rs = c.getMetaData().getPrimaryKeys(r.catalog(), r.schema(), r.tableName())) {
            while (rs.next()) {
                bySeq.put(rs.getInt("KEY_SEQ"), rs.getString("COLUMN_NAME"));
            }
        }
        List<Integer> seqs = new ArrayList<>(bySeq.keySet());
        seqs.sort(Integer::compareTo);
        List<String> out = new ArrayList<>();
        for (Integer s : seqs) {
            out.add(bySeq.get(s));
        }
        return out;
    }

    /**
     * 唯一索引（{@code uniqueOnly=true}）或全部索引（{@code false}）的列组合，按 ORDINAL_POSITION 排序。
     *
     * @return 每个元素是一个索引的列序列
     */
    private List<List<String>> readUniqueKeyNames(Connection c, Resolved r, boolean uniqueOnly) throws SQLException {
        // INDEX_NAME → (ORDINAL_POSITION → COLUMN_NAME)
        Map<String, Map<Integer, String>> grouped = new LinkedHashMap<>();
        try (ResultSet rs = c.getMetaData().getIndexInfo(r.catalog(), r.schema(), r.tableName(),
                uniqueOnly, false)) {
            while (rs.next()) {
                short type = rs.getShort("TYPE");
                if (type == DatabaseMetaData.tableIndexStatistic) {
                    continue;
                }
                if (uniqueOnly && rs.getBoolean("NON_UNIQUE")) {
                    continue;
                }
                String indexName = rs.getString("INDEX_NAME");
                String columnName = rs.getString("COLUMN_NAME");
                if (indexName == null || columnName == null) {
                    continue;
                }
                grouped.computeIfAbsent(indexName, k -> new LinkedHashMap<>())
                        .put(rs.getInt("ORDINAL_POSITION"), columnName);
            }
        }
        List<List<String>> out = new ArrayList<>();
        for (Map<Integer, String> cols : grouped.values()) {
            List<Integer> positions = new ArrayList<>(cols.keySet());
            positions.sort(Integer::compareTo);
            List<String> group = new ArrayList<>();
            for (Integer p : positions) {
                group.add(cols.get(p));
            }
            if (!group.isEmpty()) {
                out.add(group);
            }
        }
        return out;
    }

    /** 探测顺序：catalog → schema → catalog+schema → 通配。 */
    private List<String[]> candidates(Connection c, TableRef table) {
        String catalog = table.schema() != null ? table.schema() : safeCatalog(c);
        String schema = table.schema() != null ? table.schema() : safeSchema(c);
        List<String[]> out = new ArrayList<>();
        if (catalog != null) {
            out.add(new String[]{catalog, null});
        }
        if (schema != null) {
            out.add(new String[]{null, schema});
        }
        if (catalog != null && schema != null) {
            out.add(new String[]{catalog, schema});
        }
        out.add(new String[]{null, null});
        return out;
    }

    /** 表名的大小写变体（DM8/H2 存大写，MySQL 视配置而定）。 */
    private static List<String> nameVariants(String table) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        out.add(table);
        out.add(table.toUpperCase(Locale.ROOT));
        out.add(table.toLowerCase(Locale.ROOT));
        return new ArrayList<>(out);
    }

    private static String safeCatalog(Connection c) {
        try {
            return c.getCatalog();
        } catch (SQLException e) {
            return null;
        }
    }

    private static String safeSchema(Connection c) {
        try {
            return c.getSchema();
        } catch (SQLException | AbstractMethodError e) {
            return null;
        }
    }

    /** 用驱动自己的转义符转义 {@code %} 与 {@code _}，避免下划线变成通配符。 */
    static String escape(Connection c, String name) {
        String escapeChar = "\\";
        try {
            String fromDriver = c.getMetaData().getSearchStringEscape();
            if (fromDriver != null && !fromDriver.isEmpty()) {
                escapeChar = fromDriver;
            }
        } catch (SQLException ignore) {
            // 用默认反斜杠
        }
        return name.replace(escapeChar, escapeChar + escapeChar)
                .replace("_", escapeChar + "_")
                .replace("%", escapeChar + "%");
    }

    private static ColumnMeta findIn(List<ColumnMeta> metas, String name) {
        for (ColumnMeta m : metas) {
            if (m.nameIs(name)) {
                return m;
            }
        }
        return null;
    }

    /** 解析后的三元组。 */
    private record Resolved(String catalog, String schema, String tableName) {
    }

    /** getColumns 的行投影。 */
    private record RawColumn(String name, String typeName, int jdbcType, boolean nullable, int ordinal,
                             int columnSize, int decimalDigits) {
    }
}
