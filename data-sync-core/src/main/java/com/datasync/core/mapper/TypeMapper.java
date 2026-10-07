package com.datasync.core.mapper;

/**
 * 数据库类型映射接口。
 *
 * <p>实现必须保证：
 * <ul>
 *   <li>{@link #mapTypeName(String)} 对未知类型给出安全兜底（不抛异常，宁可退化为字符串）。</li>
 *   <li>{@link #mapValue(Object, String)} 对不可转换的值抛
 *       {@link ValueConversionException}，由引擎按坏行策略处理；不要静默返回 null 冒充数据。</li>
 *   <li>{@link #describe(String)} 返回中文说明，供界面与日志展示。</li>
 * </ul>
 */
public interface TypeMapper {

    /** 源类型名 → 目标数据库类型名（含长度/精度建议）。 */
    String mapTypeName(String sourceTypeName);

    /** 按目标类型名转换值。 */
    Object mapValue(Object sourceValue, String targetTypeName);

    /** 中文类型说明。 */
    String describe(String sourceTypeName);

    /**
     * 历史方法（v1 接口留档）：按源类型名转换值。
     *
     * @deprecated 用 {@link #mapValue(Object, String)} + {@link #mapTypeName(String)} 组合替代。
     */
    @Deprecated
    default Object mapType(String sourceType, Object value) {
        return mapValue(value, mapTypeName(sourceType));
    }
}
