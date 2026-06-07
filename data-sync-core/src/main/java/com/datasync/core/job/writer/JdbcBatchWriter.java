package com.datasync.core.job.writer;

import com.datasync.core.model.enums.DbType;
import org.springframework.batch.item.Chunk;
import org.springframework.batch.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.Map;
import java.util.stream.Stream;

/**
 * JDBC 批量写入器
 */
public class JdbcBatchWriter implements ItemWriter<Map<String, Object>> {

    protected final JdbcTemplate jdbcTemplate;
    protected final String table;
    protected String[] columns;
    protected final DbType dbType;
    protected final boolean fullSync;
    protected String insertSql;
    protected final String truncateSql;
    protected boolean truncated = false;

    public JdbcBatchWriter(DataSource dataSource, String table, String[] columns,
                           DbType dbType, boolean fullSync) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.table = table;
        this.columns = columns;
        this.dbType = dbType;
        this.fullSync = fullSync;
        this.insertSql = (columns != null && columns.length > 0) ? BuildInsertSql(table, columns) : null;
        this.truncateSql = "TRUNCATE TABLE " + table;
    }

    public static String BuildInsertSql(String table, String[] columns) {
        String cols = String.join(", ", columns);
        String placeholders = String.join(", ", Stream.generate(() -> "?")
            .limit(columns.length).toArray(String[]::new));
        return String.format("INSERT INTO %s (%s) VALUES (%s)", table, cols, placeholders);
    }

    @Override
    public void write(Chunk<? extends Map<String, Object>> chunk) {
        if (chunk.isEmpty()) return;

        // 全量同步：首次写入前截断目标表
        if (fullSync && !truncated) {
            jdbcTemplate.execute(truncateSql);
            truncated = true;
        }

        // 未指定列时从第一行数据自动发现列名
        if (insertSql == null) {
            Map<String, Object> firstRow = chunk.getItems().get(0);
            columns = firstRow.keySet().toArray(new String[0]);
            insertSql = BuildInsertSql(table, columns);
        }

        final String[] cols = columns;
        jdbcTemplate.batchUpdate(insertSql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                Map<String, Object> row = chunk.getItems().get(i);
                for (int j = 0; j < cols.length; j++) {
                    ps.setObject(j + 1, row.get(cols[j]));
                }
            }

            @Override
            public int getBatchSize() {
                return chunk.getItems().size();
            }
        });
    }
}