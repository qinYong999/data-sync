package com.datasync.core.job;

import com.datasync.core.job.reader.PageReader;
import com.datasync.core.job.writer.JdbcBatchWriter;
import com.datasync.core.model.FieldMapping;
import com.datasync.core.model.enums.DbType;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 核心同步流程集成测试 — 使用 H2 模拟源和目标库
 */
class SyncJobIntegrationTest {

    private DataSource createDataSource(String name) {
        return new EmbeddedDatabaseBuilder()
            .setName(name)
            .setType(EmbeddedDatabaseType.H2)
            .build();
    }

    @Test
    void testFullSyncFlow() throws Exception {
        DataSource sourceDs = createDataSource("source");
        DataSource targetDs = createDataSource("target");

        JdbcTemplate sourceJdbc = new JdbcTemplate(sourceDs);
        JdbcTemplate targetJdbc = new JdbcTemplate(targetDs);

        // 源库建表并插入数据
        sourceJdbc.execute("CREATE TABLE source_user (id INT PRIMARY KEY, name VARCHAR(100), age INT)");
        sourceJdbc.execute("INSERT INTO source_user VALUES (1, '张三', 25)");
        sourceJdbc.execute("INSERT INTO source_user VALUES (2, '李四', 30)");

        // 目标库建表
        targetJdbc.execute("CREATE TABLE target_user (id INT PRIMARY KEY, name VARCHAR(100), age INT)");

        // 使用 PageReader 读取源数据（H2 列名默认大写）
        PageReader reader = new PageReader(sourceDs, "source_user", "id", 100);
        reader.afterPropertiesSet();
        reader.open(new org.springframework.batch.item.ExecutionContext());

        Map<String, Object> row1 = reader.read();
        assertNotNull(row1);
        // H2 返回大写列名
        assertEquals("张三", row1.get("NAME"));

        Map<String, Object> row2 = reader.read();
        assertNotNull(row2);
        assertNull(reader.read()); // 无更多数据
        reader.close();

        // 使用 JdbcBatchWriter 写入目标
        JdbcBatchWriter writer = new JdbcBatchWriter(targetDs, "target_user",
            new String[]{"ID", "NAME", "AGE"}, DbType.MYSQL, false);

        targetJdbc.execute("TRUNCATE TABLE target_user");

        var chunk = new org.springframework.batch.item.Chunk<Map<String, Object>>();
        chunk.add(row1);
        chunk.add(row2);
        writer.write(chunk);

        // 验证目标数据
        List<Map<String, Object>> result = targetJdbc.queryForList("SELECT * FROM target_user ORDER BY id");
        assertEquals(2, result.size());
        assertEquals("李四", result.get(1).get("name"));
    }
}