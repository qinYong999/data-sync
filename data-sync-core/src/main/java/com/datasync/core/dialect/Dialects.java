package com.datasync.core.dialect;

import com.datasync.core.model.enums.DbType;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * 方言注册表。未注册类型抛 {@link IllegalArgumentException}（中文消息）。
 *
 * <p>方言实例无状态、线程安全，全局复用同一份。
 */
public final class Dialects {

    private static final Map<DbType, SqlDialect> REGISTRY;

    static {
        Map<DbType, SqlDialect> m = new EnumMap<>(DbType.class);
        m.put(DbType.MYSQL, new MySqlDialect());
        m.put(DbType.DM8, new Dm8Dialect());
        REGISTRY = Collections.unmodifiableMap(m);
    }

    private Dialects() {
    }

    public static SqlDialect of(DbType type) {
        if (type == null) {
            throw new IllegalArgumentException("数据库类型不能为空");
        }
        SqlDialect dialect = REGISTRY.get(type);
        if (dialect == null) {
            throw new IllegalArgumentException("未注册的数据库类型: " + type
                    + "，当前仅支持: " + REGISTRY.keySet());
        }
        return dialect;
    }

    /** 是否已注册该类型。 */
    public static boolean isSupported(DbType type) {
        return type != null && REGISTRY.containsKey(type);
    }

    /** 已注册的类型集合。 */
    public static Set<DbType> supported() {
        return REGISTRY.keySet();
    }
}
