package com.datasync.core.mapper;

/**
 * 数据库类型映射接口
 */
public interface TypeMapper {

    /** 将源类型值转换为目标兼容值 */
    Object mapType(String sourceType, Object value);

    /** 返回源类型对应的目标数据库类型名 */
    String mapTypeName(String sourceType);
}