package com.datasync.core.job.processor;

import com.datasync.core.mapper.MySqlToDm8TypeMapper;
import com.datasync.core.model.FieldMapping;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ColumnMappingProcessorTest {

    @Test
    void testProcessWithMapping() throws Exception {
        List<FieldMapping> mappings = List.of(
            new FieldMapping("name", "user_name"),
            new FieldMapping("age", "age")
        );
        ColumnMappingProcessor processor = new ColumnMappingProcessor(mappings, new MySqlToDm8TypeMapper());
        Map<String, Object> input = Map.of("name", "张三", "age", 25, "extra", "忽略我");
        Map<String, Object> result = processor.process(input);
        assertEquals(2, result.size());
        assertEquals("张三", result.get("user_name"));
        assertEquals(25, result.get("age"));
    }

    @Test
    void testProcessWithDefaultValue() throws Exception {
        List<FieldMapping> mappings = List.of(
            new FieldMapping("name", "name"),
            new FieldMapping(null, "status")
        );
        mappings.get(1).setDefaultValue("ACTIVE");
        ColumnMappingProcessor processor = new ColumnMappingProcessor(mappings, new MySqlToDm8TypeMapper());
        Map<String, Object> result = processor.process(Map.of("name", "test"));
        assertEquals("test", result.get("name"));
        assertEquals("ACTIVE", result.get("status"));
    }

    @Test
    void testProcessEmpty() throws Exception {
        ColumnMappingProcessor processor = new ColumnMappingProcessor(List.of(), new MySqlToDm8TypeMapper());
        assertTrue(processor.process(Map.of("a", 1)).isEmpty());
    }
}