package com.datasync.core.mapper;

import com.datasync.core.jdbc.ColumnMeta;

/**
 * MySQL → MySQL 类型映射（生产主线：同构库同步）。
 *
 * <p>同构同步下类型名保持原样，但值仍要过一遍归一化：不同版本/字符集的驱动返回形式可能不同
 * （例如 BIT(1) 返回 byte[]，YEAR 返回 java.sql.Date），这里复用 {@link DefaultValueConverter}
 * 保证写入形式稳定。
 */
public final class MySqlToMySqlTypeMapper implements TypeMapper {

    private static final DefaultValueConverter CONVERTER = new DefaultValueConverter();

    @Override
    public String mapTypeName(String sourceTypeName) {
        String base = TypeNames.base(sourceTypeName);
        if (base.isEmpty()) {
            return "VARCHAR(255)";
        }
        // 同构：保留原始类型名（含长度/精度），直接透传
        return sourceTypeName.trim().toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public Object mapValue(Object sourceValue, String targetTypeName) {
        if (sourceValue == null) {
            return null;
        }
        int jdbcType = TypeNames.jdbcTypeOf(targetTypeName);
        if (jdbcType == java.sql.Types.OTHER) {
            return sourceValue;
        }
        ColumnMeta target = new ColumnMeta("target", TypeNames.base(targetTypeName), jdbcType, true, false, 0);
        return CONVERTER.convert(sourceValue, null, target);
    }

    @Override
    public String describe(String sourceTypeName) {
        return TypeNames.describe(sourceTypeName);
    }
}
