package com.datasync.core.dialect;

import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.jdbc.TableRef;
import com.datasync.core.model.enums.DbType;
import java.util.ArrayList;
import java.util.List;

/**
 * 达梦 DM8 方言。
 *
 * <p><b>⚠ 未在真实达梦实例验证（契约 D13）</b>：本机没有 DM8 实例也没有达梦 JDBC 驱动，
 * 所有 SQL 文本仅通过单元测试断言，未做过真机联调。请勿在生产上直接使用 DM8 链路。
 *
 * <p>与 MySQL 的差异：
 * <ul>
 *   <li>标识符用双引号引用，且大小写敏感（DM8 默认大写存储）。</li>
 *   <li>多列键集分页用元组展开 {@code (k1>? OR (k1=? AND k2>?))} —— 不依赖行构造器支持情况。</li>
 *   <li>幂等写入用 {@code MERGE INTO ... USING (SELECT ? AS c ... FROM DUAL)}。</li>
 *   <li>不支持 {@code CREATE TABLE ... LIKE}，因此 SWAP 全量策略不可用（预检报 STRATEGY_UNSUPPORTED）。</li>
 *   <li>改表名用 {@code ALTER TABLE ... RENAME TO}。</li>
 * </ul>
 */
public final class Dm8Dialect extends AbstractDialect {

    @Override
    public DbType dbType() {
        return DbType.DM8;
    }

    @Override
    char quoteChar() {
        return '"';
    }

    @Override
    public String userName() {
        return "SELECT USER FROM DUAL";
    }

    @Override
    public String limitClause(int pageSize) {
        if (pageSize <= 0) {
            throw new IllegalArgumentException("pageSize 必须大于 0，当前: " + pageSize);
        }
        // DM8 兼容 LIMIT 语法；若目标实例关闭了该兼容项，可改为 ROWNUM 双层包装
        return "LIMIT " + pageSize;
    }

    @Override
    public String buildTruncate(TableRef table) {
        return "TRUNCATE TABLE " + qualify(table);
    }

    // 键集谓词不再单独实现：展开式 OR 与 MySQL 共用 SqlDialect 的默认实现
    // （行构造器在 MySQL 上不会下推成索引范围访问，实测慢 8000 倍；达梦更没有可下推的保证）。

    @Override
    public String buildUpsert(TableRef table, List<String> columns, List<String> keyColumns) {
        requireUpsertKeys(keyColumns);
        StringBuilder src = new StringBuilder("SELECT ");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                src.append(", ");
            }
            src.append("? AS ").append(quote(columns.get(i)));
        }
        src.append(" FROM DUAL");

        StringBuilder on = new StringBuilder();
        for (String k : keyColumns) {
            if (on.length() > 0) {
                on.append(" AND ");
            }
            on.append("t.").append(quote(k)).append(" = s.").append(quote(k));
        }

        StringBuilder sb = new StringBuilder("MERGE INTO ").append(qualify(table)).append(" t USING (")
                .append(src).append(") s ON (").append(on).append(')');

        List<String> updatable = minusKeys(columns, keyColumns);
        if (!updatable.isEmpty()) {
            sb.append(" WHEN MATCHED THEN UPDATE SET ");
            for (int i = 0; i < updatable.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                String c = quote(updatable.get(i));
                sb.append("t.").append(c).append(" = s.").append(c);
            }
        }
        // 全部列都是键列时不写 WHEN MATCHED：MERGE 的 UPDATE 子句不能为空，
        // 且 Oracle/DM 语法不允许更新 ON 条件里的列。此时语义为"存在即跳过"，仍然幂等。
        sb.append(" WHEN NOT MATCHED THEN INSERT (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(quote(columns.get(i)));
        }
        sb.append(") VALUES (");
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("s.").append(quote(columns.get(i)));
        }
        sb.append(')');
        return sb.toString();
    }

    @Override
    public String buildCreateTableLike(TableRef source, TableRef newTable) {
        throw new UnsupportedOperationException("DM8 不支持 CREATE TABLE ... LIKE，"
                + "请改用 TRUNCATE 或 DELETE 全量策略（未在真实达梦实例验证）");
    }

    @Override
    public String buildRenameTable(TableRef from, TableRef to) {
        return "ALTER TABLE " + qualify(from) + " RENAME TO " + qualify(to);
    }
}
