package com.datasync.core.jdbc;

import java.sql.Connection;
import java.util.List;

/**
 * 元数据静态门面：引擎与预检的默认入口。
 */
public final class JdbcMetadata {

    private static final DefaultJdbcMetadataReader READER = new DefaultJdbcMetadataReader();

    private JdbcMetadata() {
    }

    /** 表/视图是否存在。 */
    public static boolean tableExists(Connection c, TableRef t) {
        if (t == null || t.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        Identifiers.validate(t.table(), "表名");
        if (t.schema() != null) {
            Identifiers.validate(t.schema(), "库名/模式名");
        }
        return READER.exists(c, t);
    }

    /** 当前连接可见的所有表/视图。 */
    public static List<TableRef> listTables(Connection c) {
        return READER.listTables(c);
    }

    /** 读表结构（表不存在时返回空 TableMeta）。 */
    public static TableMeta read(Connection c, TableRef t) {
        return READER.read(c, t);
    }

    /** 表上所有索引的首列名（预检增量列索引建议用）。 */
    public static List<String> indexedLeadColumns(Connection c, TableRef t) {
        if (t == null || t.isBlank()) {
            throw new IllegalArgumentException("表名不能为空");
        }
        return READER.indexedLeadColumns(c, t);
    }
}
