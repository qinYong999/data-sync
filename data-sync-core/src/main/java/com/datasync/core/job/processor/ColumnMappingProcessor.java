package com.datasync.core.job.processor;

import com.datasync.core.mapper.TypeMapper;
import com.datasync.core.model.FieldMapping;
import org.springframework.batch.item.ItemProcessor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列映射处理器 — 按 FieldMapping 配置将源行转为目标行
 */
public class ColumnMappingProcessor implements ItemProcessor<Map<String, Object>, Map<String, Object>> {

    private final List<FieldMapping> fieldMappings;
    private final TypeMapper typeMapper;

    public ColumnMappingProcessor(List<FieldMapping> fieldMappings, TypeMapper typeMapper) {
        this.fieldMappings = fieldMappings;
        this.typeMapper = typeMapper;
    }

    @Override
    public Map<String, Object> process(Map<String, Object> sourceRow) {
        // 无字段映射配置时透传所有列
        if (fieldMappings == null || fieldMappings.isEmpty()) {
            return new LinkedHashMap<>(sourceRow);
        }
        Map<String, Object> targetRow = new LinkedHashMap<>();
        for (FieldMapping mapping : fieldMappings) {
            String sourceCol = mapping.getSourceColumn();
            if (sourceCol != null && !sourceCol.isBlank() && sourceRow.containsKey(sourceCol)) {
                targetRow.put(mapping.getTargetColumn(), sourceRow.get(sourceCol));
            } else {
                if (mapping.getDefaultValue() != null) {
                    targetRow.put(mapping.getTargetColumn(), mapping.getDefaultValue());
                }
            }
        }
        return targetRow;
    }
}