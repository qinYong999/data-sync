package com.datasync.core.job.reader;

import org.springframework.batch.item.database.JdbcCursorItemReader;
import javax.sql.DataSource;
import java.util.Map;

/**
 * 自定义 SQL 读取器 — 执行用户提供的 SELECT 查询作为数据源
 * 使用游标方式流式读取，避免对复杂 SQL（JOIN/GROUP BY/UNION）做分页包装
 */
public class CustomSqlReader extends JdbcCursorItemReader<Map<String, Object>> {

    public CustomSqlReader(DataSource dataSource, String customSql, int fetchSize) {
        setDataSource(dataSource);
        setSql(customSql);
        setFetchSize(fetchSize);
        setRowMapper(new PageReader.MapRowMapper());
        try {
            afterPropertiesSet();
        } catch (Exception e) {
            throw new RuntimeException("CustomSqlReader 初始化失败", e);
        }
    }
}
