package com.datasync.core.mapper;

import com.datasync.core.model.enums.DbType;

/**
 * 按「源库类型 → 目标库类型」选择类型映射器。
 *
 * <p>生产主线是 MySQL → MySQL；MySQL → DM8 的结构完整但<b>未在真实达梦实例验证</b>（契约 D13）。
 */
public final class TypeMappers {

    private static final TypeMapper MYSQL_TO_MYSQL = new MySqlToMySqlTypeMapper();
    private static final TypeMapper MYSQL_TO_DM8 = new MySqlToDm8TypeMapper();

    private TypeMappers() {
    }

    public static TypeMapper of(DbType source, DbType target) {
        if (target == DbType.DM8) {
            return MYSQL_TO_DM8;
        }
        return MYSQL_TO_MYSQL;
    }

    /** 只关心目标库类型时的选择。 */
    public static TypeMapper forTarget(DbType target) {
        return target == DbType.DM8 ? MYSQL_TO_DM8 : MYSQL_TO_MYSQL;
    }
}
