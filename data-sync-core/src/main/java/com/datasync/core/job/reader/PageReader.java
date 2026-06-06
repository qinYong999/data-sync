package com.datasync.core.job.reader;

import com.datasync.core.job.SqlBuilder;
import org.springframework.batch.item.ExecutionContext;
import org.springframework.batch.item.ItemStreamException;
import org.springframework.batch.item.database.JdbcCursorItemReader;
import org.springframework.jdbc.core.RowMapper;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用分页读取器 — 将一行数据读取为 Map<String, Object>
 */
public class PageReader extends JdbcCursorItemReader<Map<String, Object>> {

    public PageReader(DataSource dataSource, String table, String orderBy, int pageSize) {
        setDataSource(dataSource);
        setSql(SqlBuilder.buildSelectPage(table, orderBy, pageSize, 0));
        setFetchSize(pageSize);
        setRowMapper(new MapRowMapper());
    }

    /**
     * 将 ResultSet 转为 Map
     */
    public static class MapRowMapper implements RowMapper<Map<String, Object>> {
        @Override
        public Map<String, Object> mapRow(ResultSet rs, int rowNum) throws java.sql.SQLException {
            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= colCount; i++) {
                row.put(meta.getColumnLabel(i), rs.getObject(i));
            }
            return row;
        }
    }
}