package com.datasync.core.job.writer;

import com.datasync.core.model.enums.DbType;
import javax.sql.DataSource;

/**
 * 达梦8 批量写入器
 */
public class Dm8BatchWriter extends JdbcBatchWriter {

    public Dm8BatchWriter(DataSource dataSource, String table,
                          String[] columns, boolean fullSync) {
        super(dataSource, table, columns, DbType.DM8, fullSync);
    }
}