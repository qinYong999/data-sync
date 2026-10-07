package com.datasync.core.jdbc;

import java.sql.Connection;

/**
 * 表元数据读取器。
 *
 * <p>实现必须基于 {@link java.sql.DatabaseMetaData}（不做 {@code SHOW} / 方言专有查询），
 * 这样 MySQL 与 DM8 共用一套逻辑。
 */
public interface JdbcMetadataReader {

    /**
     * 读表结构（列、主键、唯一键）。
     *
     * @return 表不存在时返回 {@link TableMeta#empty(TableRef)}，不返回 null
     */
    TableMeta read(Connection c, TableRef table);
}
