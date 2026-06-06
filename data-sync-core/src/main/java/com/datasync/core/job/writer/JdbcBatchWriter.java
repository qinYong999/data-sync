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
    protected final String[] columns;
    protected final DbType dbType;
    protected final boolean fullSync;
    protected final String insertSql;
    protected final String truncateSql;

    public JdbcBatchWriter(DataSource dataSource, String table, String[] columns,
                           DbType dbType, boolean fullSync) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
        this.table = table;
        this.columns = columns;
        this.dbType = dbType;
        this.fullSync = fullSync;
        this.insertSql = BuildInsertSql(table, columns);
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
        jdbcTemplate.batchUpdate(insertSql, new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
            @Override
            public void setValues(java.sql.PreparedStatement ps, int i) throws java.sql.SQLException {
                Map<String, Object> row = chunk.getItems().get(i);
                for (int j = 0; j < columns.length; j++) {
                    ps.setObject(j + 1, row.get(columns[j]));
                }
            }

            @Override
            public int getBatchSize() {
                return chunk.getItems().size();
            }
        });
    }
}