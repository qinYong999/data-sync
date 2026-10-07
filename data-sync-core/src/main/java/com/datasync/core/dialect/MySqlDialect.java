package com.datasync.core.dialect;

import com.datasync.core.jdbc.TableRef;
import com.datasync.core.model.enums.DbType;
import java.util.List;

/**
 * MySQL 8.0 方言（生产主线，已在本机 MySQL 8.0 真机验证）。
 *
 * <p>关键点：
 * <ul>
 *   <li>多列键集分页用 SQL 标准元组比较 {@code (k1,k2) &gt; (?,?)}，MySQL 优化器可下推索引，
 *       不会退化成"只比较第一列"。</li>
 *   <li>流式读取 {@code fetchSize = Integer.MIN_VALUE}（Connector/J 约定），
 *       否则大表整表读入内存必然 OOM。</li>
 *   <li>幂等写入 {@code INSERT ... ON DUPLICATE KEY UPDATE col = VALUES(col)}：
 *       {@code VALUES()} 在 8.0.20+ 标记为 deprecated 但 8.0/8.4 均可用，
 *       而 8.0.19 以下只认这种写法，因此这里选兼容面最广的形式。</li>
 * </ul>
 */
public final class MySqlDialect extends AbstractDialect {

    /** 单条 SQL 的占位符上限（MySQL 硬上限 65535，留余量）。 */
    private static final int PLACEHOLDER_LIMIT = 60_000;

    @Override
    public DbType dbType() {
        return DbType.MYSQL;
    }

    @Override
    char quoteChar() {
        return '`';
    }

    @Override
    public String userName() {
        return "SELECT CURRENT_USER()";
    }

    @Override
    public String limitClause(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize 必须大于 0，当前: " + pageSize);
        }
        return "LIMIT " + pageSize;
    }

    @Override
    public int streamFetchSize() {
        // Connector/J 的流式开关：只有 MIN_VALUE 才会逐行从服务器拉取
        return Integer.MIN_VALUE;
    }

    @Override
    public String buildTruncate(TableRef table) {
        return "TRUNCATE TABLE " + qualify(table);
    }

    @Override
    public String buildUpsert(TableRef table, List<String> columns, List<String> keyColumns) {
        requireUpsertKeys(keyColumns);
        String insert = buildInsert(table, columns);
        List<String> updatable = minusKeys(columns, keyColumns);
        if (updatable.isEmpty()) {
            // 所有列都是键列：UPDATE 子句不能为空，用一个自赋值占位保证语法合法
            return insert + " ON DUPLICATE KEY UPDATE " + quote(keyColumns.get(0))
                    + " = " + quote(keyColumns.get(0));
        }
        StringBuilder sb = new StringBuilder(insert).append(" ON DUPLICATE KEY UPDATE ");
        for (int i = 0; i < updatable.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String c = quote(updatable.get(i));
            sb.append(c).append(" = VALUES(").append(c).append(')');
        }
        return sb.toString();
    }

    @Override
    public int maxRowsPerStatement(int columnCount) {
        // MySQL 单条语句的占位符上限是 65535，留出余量按 60000 计算；
        // 行列都很多时至少保证 1 行，让调用方退化为 addBatch。
        if (columnCount <= 0) {
            return 1;
        }
        return Math.max(1, PLACEHOLDER_LIMIT / columnCount);
    }

    @Override
    public String buildUpsertMultiRow(TableRef table, List<String> columns, List<String> keyColumns,
                                      int rowCount) {
        requireUpsertKeys(keyColumns);
        String insert = buildInsertMultiRow(table, columns, rowCount);
        List<String> updatable = minusKeys(columns, keyColumns);
        if (updatable.isEmpty()) {
            // 所有列都是键列：UPDATE 子句不能为空，用一个自赋值占位保证语法合法
            return insert + " ON DUPLICATE KEY UPDATE " + quote(keyColumns.get(0))
                    + " = " + quote(keyColumns.get(0));
        }
        StringBuilder sb = new StringBuilder(insert).append(" ON DUPLICATE KEY UPDATE ");
        for (int i = 0; i < updatable.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String c = quote(updatable.get(i));
            sb.append(c).append(" = VALUES(").append(c).append(')');
        }
        return sb.toString();
    }

    @Override
    public String buildCreateTableLike(TableRef source, TableRef newTable) {
        return "CREATE TABLE " + qualify(newTable) + " LIKE " + qualify(source);
    }

    @Override
    public String buildRenameTable(TableRef from, TableRef to) {
        return "RENAME TABLE " + qualify(from) + " TO " + qualify(to);
    }
}
