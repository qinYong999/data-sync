package com.datasync.core.mapper;

import com.datasync.core.jdbc.ColumnMeta;

/**
 * 值转换器：把源端 JDBC 取出的值转成目标列能安全接收的形式。
 *
 * <p>约定：返回 {@code null} 表示目标列写入 SQL NULL；调用方必须用
 * {@code PreparedStatement.setNull(index, targetMeta.jdbcType())} 落库，
 * <b>禁止</b> {@code setObject(index, null)}（部分驱动会抛异常或写成错误类型）。
 */
public interface ValueConverter {

    /**
     * @param source     源值（可为 null）
     * @param sourceMeta 源列元数据（可为 null，表示未知）
     * @param targetMeta 目标列元数据（可为 null，表示不转换）
     * @return 目标值，或 null
     */
    Object convert(Object source, ColumnMeta sourceMeta, ColumnMeta targetMeta);

    /**
     * 该组合是否可转换（预检与运行时共用 {@link TypeCompatibility} 的能力表）。
     *
     * <p>返回 false 的组合必须在预检阶段以 {@code TYPE_INCOMPATIBLE} ERROR 拦住，
     * 运行期永远不应该真正执行到它。
     */
    default boolean canConvert(ColumnMeta sourceMeta, ColumnMeta targetMeta) {
        return TypeCompatibility.canConvert(sourceMeta, targetMeta);
    }
}
