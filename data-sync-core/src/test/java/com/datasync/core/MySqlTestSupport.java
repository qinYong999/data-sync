package com.datasync.core;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 真机 MySQL 测试支撑（本机 127.0.0.1:3306，独立测试库 datasync_test_engine）。
 *
 * <p>MySQL 是生产主线（契约 D13），所以引擎与预检的关键行为必须跑在真库上，
 * 而不是靠 H2 兼容模式"看起来像"。MySQL 不可用时用 {@link Assumptions} 跳过，
 * 保证在没有数据库的机器上 {@code mvn test} 依然全绿。
 */
public final class MySqlTestSupport {

    public static final String HOST = "127.0.0.1";
    public static final int PORT = 3306;
    public static final String USER = "root";
    public static final String PASSWORD = "123456";

    /**
     * 测试库名：**每次 JVM 一个独立库**，避免与并发的另一次构建/验收互相 DROP 对方的表。
     *
     * <p>这不是洁癖：本机实测过一次"Lead 跑全量构建的同时我跑 core 测试"，
     * 两个进程在同一张表上交替 DROP/CREATE，导致 16 个用例以
     * {@code Table 'src_ok' already exists} / {@code doesn't exist} 的诡异方式失败。
     * 可用 {@code -Ddatasync.test.db=xxx} 指定固定库名（需要共享数据时用）。
     */
    public static final String DATABASE = System.getProperty("datasync.test.db",
            "datasync_test_engine_" + ProcessHandle.current().pid());

    private static final String PARAMS =
            "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
                    + "&characterEncoding=utf8&rewriteBatchedStatements=true&useServerPrepStmts=false";

    public static final String ADMIN_URL = "jdbc:mysql://" + HOST + ":" + PORT + "/" + PARAMS;
    public static final String URL = "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE + PARAMS;

    private static Boolean available;
    private static boolean cleanupRegistered;

    private MySqlTestSupport() {
    }

    /** 不可用时跳过整个测试类。 */
    public static void assumeAvailable() {
        Assumptions.assumeTrue(canConnect(), "本机 MySQL(" + HOST + ":" + PORT + ") 不可用，跳过真机验证测试");
        ensureDatabase();
        registerCleanup();
        available = true;
    }

    public static boolean canConnect() {
        if (available != null) {
            return available;
        }
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD)) {
            available = c.isValid(3);
        } catch (SQLException e) {
            available = false;
        }
        return available;
    }

    private static void ensureDatabase() {
        try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("CREATE DATABASE IF NOT EXISTS " + DATABASE + " DEFAULT CHARACTER SET utf8mb4");
        } catch (SQLException e) {
            throw new IllegalStateException("创建测试库 " + DATABASE + " 失败: " + e.getMessage(), e);
        }
    }

    /** JVM 退出时删掉本次运行的测试库（异常退出会留下空壳库，不影响正确性）。 */
    private static void registerCleanup() {
        if (cleanupRegistered || System.getProperty("datasync.test.db") != null) {
            return;
        }
        cleanupRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try (Connection c = DriverManager.getConnection(ADMIN_URL, USER, PASSWORD);
                 Statement st = c.createStatement()) {
                st.execute("DROP DATABASE IF EXISTS " + DATABASE);
            } catch (Exception ignore) {
                // 清不掉就算了（下次运行会用新的库名）
            }
        }, "drop-test-database"));
    }

    /** 每次调用返回独立连接的数据源（测试里不需要连接池语义）。 */
    public static DataSource dataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource(URL, USER, PASSWORD);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return ds;
    }

    public static void exec(DataSource ds, String... sqls) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            for (String sql : sqls) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("执行 SQL 失败: " + e.getMessage(), e);
        }
    }

    public static void execQuietly(DataSource ds, String sql) {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        } catch (SQLException ignore) {
            // 清理语句失败无所谓
        }
    }

    public static long count(DataSource ds, String table) {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException("统计行数失败: " + e.getMessage(), e);
        }
    }

    /**
     * 重建目标表：先删后建（{@code CREATE TABLE ... LIKE}），并自检结果。
     *
     * <p>DDL 偶尔会碰上 InnoDB 数据字典的瞬时状态（刚 drop 完立刻 create），因此失败重试一次，
     * 仍然失败就直接抛错——比让后续断言以"目标表不存在"的形式爆炸清楚得多。
     */
    public static void recreateLike(DataSource ds, String srcTable, String dstTable) {
        SQLException last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
                st.execute("DROP TABLE IF EXISTS " + dstTable);
                st.execute("CREATE TABLE " + dstTable + " LIKE " + srcTable);
            } catch (SQLException e) {
                last = e;
                sleep(200);
                continue;
            }
            if (tableExists(ds, dstTable)) {
                return;
            }
            sleep(200);
        }
        throw new IllegalStateException("重建表 " + dstTable + " 失败"
                + (last == null ? "（创建后自检不到该表）" : ": " + last.getMessage()), last);
    }

    /** 表是否存在（DDL 之后自检，避免"以为建好了"导致预检报 DST_TABLE_MISSING 这种误导性失败）。 */
    public static boolean tableExists(DataSource ds, String table) {
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1 FROM " + table + " LIMIT 1")) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 把整表读成字符串矩阵（含列名），用于逐行比对源与目标。 */
    public static List<List<String>> dump(DataSource ds, String table, String orderBy) {        List<List<String>> rows = new ArrayList<>();
        String sql = "SELECT * FROM " + table + " ORDER BY " + orderBy;
        try (Connection c = ds.getConnection();
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            int n = rs.getMetaData().getColumnCount();
            List<String> header = new ArrayList<>();
            for (int i = 1; i <= n; i++) {
                header.add(rs.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT));
            }
            rows.add(header);
            while (rs.next()) {
                List<String> row = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    Object v = rs.getObject(i);
                    if (v == null) {
                        row.add("<null>");
                    } else if (v instanceof byte[] bytes) {
                        row.add("0x" + toHex(bytes));
                    } else {
                        row.add(String.valueOf(v));
                    }
                }
                rows.add(row);
            }
            return rows;
        } catch (SQLException e) {
            throw new IllegalStateException("读取表失败: " + e.getMessage(), e);
        }
    }

    private static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
