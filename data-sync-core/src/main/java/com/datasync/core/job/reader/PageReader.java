package com.datasync.core.job.reader;

import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.MySqlPagingQueryProvider;
import org.springframework.jdbc.core.RowMapper;
import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通用分页读取器 — 将一行数据读取为 Map<String, Object>
 * 基于 Spring Batch JdbcPagingItemReader 实现真正的 keyset 分页
 */
public class PageReader extends JdbcPagingItemReader<Map<String, Object>> {

    public PageReader(DataSource dataSource, String table, String orderBy, int pageSize) {
        MySqlPagingQueryProvider provider = new MySqlPagingQueryProvider();
        provider.setSelectClause("SELECT *");
        provider.setFromClause("FROM " + table);
        provider.setSortKeys(Map.of(orderBy, Order.ASCENDING));

        setDataSource(dataSource);
        setQueryProvider(provider);
        setPageSize(pageSize);
        setRowMapper(new MapRowMapper());
        try {
            afterPropertiesSet();
        } catch (Exception e) {
            throw new RuntimeException("PageReader 初始化失败", e);
        }
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