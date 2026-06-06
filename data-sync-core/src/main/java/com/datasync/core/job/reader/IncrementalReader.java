package com.datasync.core.job.reader;

import com.datasync.core.job.SqlBuilder;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import javax.sql.DataSource;
import java.util.Map;

/**
 * 增量读取器 — 基于时间戳字段过滤增量数据
 */
public class IncrementalReader extends JdbcCursorItemReader<Map<String, Object>> {

    public IncrementalReader(DataSource dataSource, String table,
                             String incrColumn, String orderBy, Object lastSyncValue) {
        setDataSource(dataSource);
        setSql(SqlBuilder.buildSelectIncremental(table, incrColumn, orderBy));
        setFetchSize(1000);
        setPreparedStatementSetter(ps -> {
            if (lastSyncValue instanceof java.util.Date d) {
                ps.setTimestamp(1, new java.sql.Timestamp(d.getTime()));
            } else if (lastSyncValue instanceof String s) {
                ps.setString(1, s);
            } else {
                ps.setObject(1, lastSyncValue);
            }
        });
        setRowMapper(new PageReader.MapRowMapper());
    }
}