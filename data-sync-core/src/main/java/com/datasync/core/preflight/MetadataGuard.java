package com.datasync.core.preflight;

import com.datasync.core.model.PreflightIssue;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;

/**
 * 元数据库防护（Lead 决策 3）：防止把平台自身的元数据库当成同步目标。
 *
 * <p>为什么必须有它：一次误配的 FULL + TRUNCATE 会把数据源配置、任务定义、执行记录全部抹掉，
 * 而这类事故是不可逆的。
 *
 * <p>分层原则：core 不认识 Spring，元数据库地址由平台层（唯一知道 {@code spring.datasource.url} 的地方）
 * 通过 {@link #of(String)} 注入；URL 为空表示不做这项检查。
 *
 * <p>判定采取"能确证才报"的策略：解析不出 host/port/database、连不上目标库、驱动不返回 URL
 * 等任何不确定情况一律放过，绝不制造误报把合法任务拦下来。
 */
public final class MetadataGuard {

    /** 平台自身的元数据表名（命中即 ERROR）。 */
    private static final List<String> METADATA_TABLES = List.of(
            "datasource", "sync_task", "sync_record", "sync_error", "flyway_schema_history");

    /** Quartz 的表以 QRTZ_ 开头。 */
    private static final String QUARTZ_PREFIX = "QRTZ_";

    private static final Pattern URL_HOST_PORT = Pattern.compile("//([^/:,]+):(\\d+)");
    private static final Pattern URL_DATABASE = Pattern.compile("//[^/]+/([^?;]+)");

    private final String rawUrl;
    private final String host;
    private final Integer port;
    private final String database;
    private final boolean checkable;

    private MetadataGuard(String rawUrl, String host, Integer port, String database, boolean checkable) {
        this.rawUrl = rawUrl;
        this.host = host;
        this.port = port;
        this.database = database;
        this.checkable = checkable;
    }

    /**
     * @param metadataJdbcUrl 平台元数据库的 JDBC URL；null/空 = 返回一个不做检查的 guard
     */
    public static MetadataGuard of(String metadataJdbcUrl) {
        if (metadataJdbcUrl == null || metadataJdbcUrl.isBlank()) {
            return new MetadataGuard(null, null, null, null, false);
        }
        String url = metadataJdbcUrl.trim();
        String h = null;
        Integer p = null;
        String db = null;
        Matcher m = URL_HOST_PORT.matcher(url);
        if (m.find()) {
            h = m.group(1);
            try {
                p = Integer.valueOf(m.group(2));
            } catch (NumberFormatException ignore) {
                p = null;
            }
        }
        Matcher dm = URL_DATABASE.matcher(url);
        if (dm.find()) {
            db = dm.group(1);
        }
        boolean checkable = h != null && p != null && db != null && !db.isBlank();
        return new MetadataGuard(url, h, p, db, checkable);
    }

    /** 是否具备检查能力（URL 可解析）。 */
    public boolean isCheckable() {
        return checkable;
    }

    /**
     * 判定目标连接是否就是元数据库本身（契约签名）。
     *
     * <p>此重载拿不到目标表名，因此一旦判定命中就按最严重情况报 ERROR。
     */
    public void check(DataSource target, List<PreflightIssue> issues) {
        check(target, null, issues);
    }

    /**
     * 判定目标连接是否就是元数据库本身，并结合目标表名给出 ERROR/WARN。
     *
     * @param targetTableName 目标表名（可空）；命中元数据库且表名属于元数据表 → ERROR，否则 WARN
     */
    public void check(DataSource target, String targetTableName, List<PreflightIssue> issues) {
        if (!checkable || target == null || issues == null) {
            return;
        }
        try (Connection c = target.getConnection()) {
            DatabaseMetaData md = c.getMetaData();
            String targetUrl = md == null ? null : md.getURL();
            String catalog = null;
            try {
                catalog = c.getCatalog();
            } catch (Exception ignore) {
                catalog = null;
            }
            if (!isSameDatabase(targetUrl, catalog)) {
                return;
            }
            String table = targetTableName == null ? null : targetTableName.trim();
            if (table != null && !table.isEmpty() && !isMetadataTable(table)) {
                issues.add(PreflightIssue.warn(ErrorCodes.TARGET_IS_METADATA,
                        "目标库是平台自身的元数据库，目标表[" + table + "]不是平台元数据表，但仍在同一个库里",
                        "确认这不是误配；建议同步到业务库，避免与平台数据混用同一实例"));
                return;
            }
            String detail = table == null || table.isEmpty()
                    ? "目标库就是平台自身的元数据库"
                    : "目标表[" + table + "]是平台自身的元数据表";
            issues.add(PreflightIssue.error(ErrorCodes.TARGET_IS_METADATA,
                    detail + "（" + database + "），继续执行可能清空平台配置与执行记录",
                    "把目标数据源改到业务库；元数据表(" + String.join("/", METADATA_TABLES)
                            + "/QRTZ_*) 绝不可以作为同步目标"));
        } catch (Exception e) {
            // 连不上/元数据不可用：不做判断，绝不误报
        }
    }

    private boolean isSameDatabase(String targetUrl, String targetCatalog) {
        if (targetUrl != null && normalize(targetUrl).equals(normalize(rawUrl))) {
            return true;
        }
        if (targetCatalog == null || database == null || !targetCatalog.equalsIgnoreCase(database)) {
            return false;
        }
        if (host == null || port == null || targetUrl == null) {
            return false;
        }
        Matcher m = URL_HOST_PORT.matcher(targetUrl);
        if (!m.find()) {
            return false;
        }
        String targetHost = m.group(1);
        String targetPort = m.group(2);
        return host.equalsIgnoreCase(targetHost) && String.valueOf(port).equals(targetPort);
    }

    /** 表名是否属于平台元数据表。 */
    public static boolean isMetadataTable(String tableName) {
        if (tableName == null) {
            return false;
        }
        String t = tableName.trim();
        int dot = t.lastIndexOf('.');
        if (dot >= 0) {
            t = t.substring(dot + 1);
        }
        String upper = t.toUpperCase(Locale.ROOT);
        if (upper.startsWith(QUARTZ_PREFIX)) {
            return true;
        }
        for (String name : METADATA_TABLES) {
            if (name.equalsIgnoreCase(t)) {
                return true;
            }
        }
        return false;
    }

    private static String normalize(String url) {
        String u = url.trim().toLowerCase(Locale.ROOT);
        int q = u.indexOf('?');
        if (q >= 0) {
            u = u.substring(0, q);
        }
        while (u.endsWith("/") || u.endsWith(";")) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }

    @Override
    public String toString() {
        return checkable ? "MetadataGuard{" + host + ":" + port + "/" + database + "}" : "MetadataGuard{disabled}";
    }
}
