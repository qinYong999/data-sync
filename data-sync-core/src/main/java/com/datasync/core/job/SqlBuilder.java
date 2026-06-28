package com.datasync.core.job;

import com.datasync.core.model.enums.DbType;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * SQL 构建工具类
 */
public class SqlBuilder {

    public static String buildSelectPage(String table, String orderBy, int pageSize, long offset) {
        return String.format("SELECT * FROM %s ORDER BY %s LIMIT %d OFFSET %d", table, orderBy, pageSize, offset);
    }

    public static String buildSelectIncremental(String table, String incrColumn, String orderBy) {
        return String.format("SELECT * FROM %s WHERE %s > ? ORDER BY %s, %s", table, incrColumn, incrColumn, orderBy);
    }

    public static String buildCountSql(String table) {
        return String.format("SELECT COUNT(*) FROM %s", table);
    }

    public static String buildTruncateSql(String table) {
        return String.format("TRUNCATE TABLE %s", table);
    }

    public static String buildInsertSql(String table, String[] columns) {
        String cols = String.join(", ", columns);
        String placeholders = Stream.generate(() -> "?").limit(columns.length).collect(Collectors.joining(", "));
        return String.format("INSERT INTO %s (%s) VALUES (%s)", table, cols, placeholders);
    }

    public static String buildUpsertSql(String table, String[] columns, String[] pkColumns, DbType dbType) {
        return switch (dbType) {
            case MYSQL -> buildMySqlUpsert(table, columns, pkColumns);
            case DM8 -> buildDm8Merge(table, columns, pkColumns);
        };
    }

    private static String buildMySqlUpsert(String table, String[] columns, String[] pkColumns) {
        String cols = String.join(", ", columns);
        String placeholders = Stream.generate(() -> "?").limit(columns.length).collect(Collectors.joining(", "));
        java.util.Set<String> pkSet = java.util.Set.of(pkColumns);
        String updateClause = Stream.of(columns)
            .filter(c -> !pkSet.contains(c))
            .map(c -> c + " = VALUES(" + c + ")")
            .collect(Collectors.joining(", "));
        return String.format("INSERT INTO %s (%s) VALUES (%s) ON DUPLICATE KEY UPDATE %s",
            table, cols, placeholders, updateClause);
    }

    public static String buildCustomSqlMeta(String customSql) {
        return "SELECT * FROM (" + customSql + ") _sql_wrapper LIMIT 0";
    }

    public static String buildCustomSqlPreview(String customSql, int limit) {
        return "SELECT * FROM (" + customSql + ") _sql_wrapper LIMIT " + limit;
    }

    public static String buildCustomSqlCount(String customSql) {
        return "SELECT COUNT(*) FROM (" + customSql + ") _sql_wrapper";
    }

    private static String buildDm8Merge(String table, String[] columns, String[] pkColumns) {
        String cols = String.join(", ", columns);
        String placeholders = Stream.generate(() -> "?").limit(columns.length).collect(Collectors.joining(", "));
        String joinOn = Stream.of(pkColumns).map(c -> "t." + c + " = s." + c).collect(Collectors.joining(" AND "));
        String updateSet = Stream.of(columns)
            .filter(c -> !java.util.Set.of(pkColumns).contains(c))
            .map(c -> "t." + c + " = s." + c)
            .collect(Collectors.joining(", "));
        String insertVals = Stream.of(columns).map(c -> "s." + c).collect(Collectors.joining(", "));
        return String.format("MERGE INTO %s t USING (SELECT %s FROM DUAL) s ON (%s) " +
            "WHEN MATCHED THEN UPDATE SET %s WHEN NOT MATCHED THEN INSERT (%s) VALUES (%s)",
            table, placeholders, joinOn, updateSet, cols, insertVals);
    }
}