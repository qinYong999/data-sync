package com.datasync.core.jdbc;

import com.datasync.core.model.enums.DbType;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Locale;

/**
 * 从 JDBC 连接识别数据库类型。
 *
 * <p>为什么需要它：{@code SyncEngine.run(cfg, src, dst, listener)} 的入参只有两个 {@code DataSource}，
 * 配置里没有（也不应该有）数据库类型字段——数据源的真实类型以连接的产品名为准，
 * 避免"配置写 MySQL、实际连的是达梦"这类静默错配。
 */
public final class DbTypes {

    private DbTypes() {
    }

    /** 识别连接对应的数据库类型；识别不出返回 null。 */
    public static DbType detect(Connection c) {
        if (c == null) {
            return null;
        }
        try {
            DatabaseMetaData md = c.getMetaData();
            DbType fromProduct = fromProductName(md.getDatabaseProductName());
            if (fromProduct != null) {
                return fromProduct;
            }
            return fromProductName(md.getDriverName());
        } catch (SQLException e) {
            return null;
        }
    }

    /** 按产品名/驱动名判断类型。 */
    public static DbType fromProductName(String name) {
        if (name == null) {
            return null;
        }
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("mysql") || n.contains("mariadb") || n.contains("percona")) {
            return DbType.MYSQL;
        }
        if (n.contains("dm") || n.contains("达梦") || n.contains("dameng")) {
            return DbType.DM8;
        }
        return null;
    }
}
