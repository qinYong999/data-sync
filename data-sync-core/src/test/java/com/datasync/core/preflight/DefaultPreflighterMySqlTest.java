package com.datasync.core.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.MySqlTestSupport;
import com.datasync.core.model.FieldMapping;
import com.datasync.core.model.FullSyncStrategy;
import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.model.enums.SyncMode;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 预检错误码矩阵（真机 MySQL）。
 *
 * <p>口径：预检必须在写任何数据之前把配置错误全部暴露出来，并给出稳定错误码 + 中文修复建议。
 */
class DefaultPreflighterMySqlTest {

    private DataSource srcDs;
    private DataSource dstDs;

    @BeforeAll
    static void setUpClass() {
        MySqlTestSupport.assumeAvailable();
    }

    @BeforeEach
    void setUp() {
        srcDs = MySqlTestSupport.dataSource();
        dstDs = MySqlTestSupport.dataSource();
        MySqlTestSupport.exec(dstDs,
                "DROP TABLE IF EXISTS dst_ok", "DROP TABLE IF EXISTS dst_nopk", "DROP TABLE IF EXISTS dst_t",
                "DROP TABLE IF EXISTS dst_a", "DROP TABLE IF EXISTS dst_nokey", "DROP TABLE IF EXISTS sync_task",
                "DROP TABLE IF EXISTS sync_record");
        MySqlTestSupport.exec(srcDs,
                "DROP TABLE IF EXISTS src_ok", "DROP TABLE IF EXISTS src_t", "DROP TABLE IF EXISTS src_a",
                "DROP TABLE IF EXISTS src_nokey");
        MySqlTestSupport.exec(srcDs,
                "CREATE TABLE src_ok (id INT NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL,"
                        + " updated_at DATETIME, created DATETIME, INDEX idx_src_ok_updated (updated_at))"
                        + " ENGINE=InnoDB",
                "CREATE TABLE src_t (id INT NOT NULL PRIMARY KEY, remark TEXT) ENGINE=InnoDB",
                "CREATE TABLE src_a (x INT NOT NULL PRIMARY KEY) ENGINE=InnoDB",
                "CREATE TABLE src_nokey (a INT NOT NULL, b INT NOT NULL, v VARCHAR(10)) ENGINE=InnoDB",
                "INSERT INTO src_ok VALUES (1, 'a', '2026-01-01 00:00:00', '2026-01-01 00:00:00')",
                "INSERT INTO src_a VALUES (1)");
        MySqlTestSupport.exec(dstDs,
                "CREATE TABLE dst_ok (id INT NOT NULL PRIMARY KEY, name VARCHAR(50), updated_at DATETIME,"
                        + " created DATETIME) ENGINE=InnoDB",
                "CREATE TABLE dst_nopk (id INT, name VARCHAR(50), updated_at DATETIME, created DATETIME)"
                        + " ENGINE=InnoDB",
                "CREATE TABLE dst_t (id INT NOT NULL PRIMARY KEY, remark INT) ENGINE=InnoDB",
                "CREATE TABLE dst_a (y INT NOT NULL PRIMARY KEY) ENGINE=InnoDB",
                "CREATE TABLE dst_nokey (a INT, b INT, v VARCHAR(10)) ENGINE=InnoDB",
                "CREATE TABLE sync_task (id INT NOT NULL PRIMARY KEY, name VARCHAR(50)) ENGINE=InnoDB");
    }

    // ------------------------------------------------------------------

    @Test
    @DisplayName("正常配置：无 ERROR（可以有 WARN）")
    void okConfig() {
        List<PreflightIssue> issues = check(base("src_ok", "dst_ok"));
        assertFalse(hasError(issues), dump(issues));
    }

    @Test
    @DisplayName("SRC_TABLE_MISSING / DST_TABLE_MISSING")
    void tableMissing() {
        assertTrue(hasErrorCode(check(base("no_such_src", "dst_ok")), ErrorCodes.SRC_TABLE_MISSING));
        assertTrue(hasErrorCode(check(base("src_ok", "no_such_dst")), ErrorCodes.DST_TABLE_MISSING));
    }

    @Test
    @DisplayName("NO_TARGET_COLUMNS：源与目标没有任何同名列且未配置映射")
    void noTargetColumns() {
        assertTrue(hasErrorCode(check(base("src_a", "dst_a")), ErrorCodes.NO_TARGET_COLUMNS));
    }

    @Test
    @DisplayName("MAPPING_COLUMN_MISSING：映射引用了不存在的列")
    void mappingColumnMissing() {
        SyncTaskConfig cfg = base("src_ok", "dst_ok");
        cfg.setFieldMappings(List.of(new FieldMapping("id", "id"), new FieldMapping("no_such_column", "name")));
        List<PreflightIssue> issues = check(cfg);
        assertTrue(hasErrorCode(issues, ErrorCodes.MAPPING_COLUMN_MISSING), dump(issues));

        SyncTaskConfig cfg2 = base("src_ok", "dst_ok");
        cfg2.setFieldMappings(List.of(new FieldMapping("id", "id"), new FieldMapping("name", "no_such_target")));
        assertTrue(hasErrorCode(check(cfg2), ErrorCodes.MAPPING_COLUMN_MISSING));
    }

    @Test
    @DisplayName("TYPE_INCOMPATIBLE：TEXT → INT 必须在写任何数据之前拦下")
    void typeIncompatible() {
        List<PreflightIssue> issues = check(base("src_t", "dst_t"));
        assertTrue(hasErrorCode(issues, ErrorCodes.TYPE_INCOMPATIBLE), dump(issues));
        boolean mentionsColumn = false;
        for (PreflightIssue i : issues) {
            if (ErrorCodes.TYPE_INCOMPATIBLE.equals(i.getCode()) && i.getMessage().contains("remark")) {
                mentionsColumn = true;
            }
        }
        assertTrue(mentionsColumn, "错误信息要能定位到列: " + dump(issues));
    }

    @Test
    @DisplayName("PK_MISSING：增量模式目标表无主键 → ERROR")
    void pkMissingIncremental() {
        SyncTaskConfig cfg = base("src_ok", "dst_nopk");
        cfg.setSyncMode(SyncMode.INCR);
        cfg.setIncrColumn("id");
        assertTrue(hasErrorCode(check(cfg), ErrorCodes.PK_MISSING));
    }

    @Test
    @DisplayName("PK_MISSING：全量 + TRUNCATE 下无主键 → 降级为 WARN（纯 INSERT）")
    void pkMissingFullDegrades() {
        List<PreflightIssue> issues = check(base("src_ok", "dst_nopk"));
        assertFalse(hasError(issues), "全量策略下无主键应放行: " + dump(issues));
        assertTrue(hasCode(issues, ErrorCodes.PK_MISSING), dump(issues));
    }

    @Test
    @DisplayName("INCR_COLUMN_MISSING / INCR_COLUMN_NOT_SORTABLE")
    void incrColumnProblems() {
        SyncTaskConfig missing = base("src_ok", "dst_ok");
        missing.setSyncMode(SyncMode.INCR);
        missing.setIncrColumn("no_such_column");
        assertTrue(hasErrorCode(check(missing), ErrorCodes.INCR_COLUMN_MISSING));

        SyncTaskConfig notSortable = base("src_ok", "dst_ok");
        notSortable.setSyncMode(SyncMode.INCR);
        notSortable.setIncrColumn("name");
        assertTrue(hasErrorCode(check(notSortable), ErrorCodes.INCR_COLUMN_NOT_SORTABLE));
    }

    @Test
    @DisplayName("INCR_COLUMN_NO_INDEX：增量列无索引 → WARN（不阻断）")
    void incrColumnNoIndex() {
        SyncTaskConfig cfg = base("src_ok", "dst_ok");
        cfg.setSyncMode(SyncMode.INCR);
        cfg.setIncrColumn("created");   // 这一列没有索引
        List<PreflightIssue> issues = check(cfg);
        assertTrue(hasCode(issues, ErrorCodes.INCR_COLUMN_NO_INDEX), dump(issues));
        assertFalse(hasError(issues), dump(issues));
        // 有索引的 updated_at 不应报
        cfg.setIncrColumn("updated_at");
        assertFalse(hasCode(check(cfg), ErrorCodes.INCR_COLUMN_NO_INDEX));
    }

    @Test
    @DisplayName("IDENTIFIER_INVALID：表名含注入字符")
    void identifierInvalid() {
        SyncTaskConfig cfg = base("src_ok", "dst_ok");
        cfg.setSourceTable("src_ok; DROP TABLE dst_ok");
        assertTrue(hasErrorCode(check(cfg), ErrorCodes.IDENTIFIER_INVALID));

        SyncTaskConfig cfg2 = base("src_ok", "dst_ok");
        cfg2.setOrderColumn("id` = 1 --");
        assertTrue(hasErrorCode(check(cfg2), ErrorCodes.IDENTIFIER_INVALID));
    }

    @Test
    @DisplayName("SORT_KEY_MISSING：源表无主键/唯一键且未配 orderColumn（禁止 OFFSET 分页）")
    void sortKeyMissing() {
        assertTrue(hasErrorCode(check(base("src_nokey", "dst_nokey")), ErrorCodes.SORT_KEY_MISSING));
    }

    @Test
    @DisplayName("ORDER_KEY_NOT_UNIQUE：排序键不唯一且无主键可做 tie-breaker")
    void orderKeyNotUnique() {
        SyncTaskConfig cfg = base("src_nokey", "dst_nokey");
        cfg.setOrderColumn("v");
        assertTrue(hasErrorCode(check(cfg), ErrorCodes.ORDER_KEY_NOT_UNIQUE));
    }

    @Test
    @DisplayName("orderColumn 不唯一但有主键 → 自动追加主键做 tie-breaker，不报错")
    void orderColumnGetsTieBreaker() {
        SyncTaskConfig cfg = base("src_ok", "dst_ok");
        cfg.setOrderColumn("name");   // name 不唯一，但主键 id 存在
        List<PreflightIssue> issues = check(cfg);
        assertFalse(hasError(issues), dump(issues));

        SyncPlan plan = SyncPlan.resolve(cfg, srcDs, dstDs);
        assertEquals(List.of("name", "id"), plan.fullOrderKeyColumns(), "必须把主键追加为 tie-breaker");
    }

    @Test
    @DisplayName("CUSTOM_SQL_INVALID：非 SELECT / 语法错误 / 表不存在")
    void customSqlInvalid() {
        SyncTaskConfig notSelect = base("src_ok", "dst_ok");
        notSelect.setSourceMode("CUSTOM_SQL");
        notSelect.setSourceSql("UPDATE src_ok SET name = 'x'");
        notSelect.setOrderColumn("id");
        assertTrue(hasErrorCode(check(notSelect), ErrorCodes.CUSTOM_SQL_INVALID));

        SyncTaskConfig badTable = base("src_ok", "dst_ok");
        badTable.setSourceMode("CUSTOM_SQL");
        badTable.setSourceSql("SELECT id, name FROM no_such_table");
        badTable.setOrderColumn("id");
        assertTrue(hasErrorCode(check(badTable), ErrorCodes.CUSTOM_SQL_INVALID));

        SyncTaskConfig missingOrder = base("src_ok", "dst_ok");
        missingOrder.setSourceMode("CUSTOM_SQL");
        missingOrder.setSourceSql("SELECT id, name FROM src_ok");
        assertTrue(hasErrorCode(check(missingOrder), ErrorCodes.SORT_KEY_MISSING));
    }

    @Test
    @DisplayName("CUSTOM_SQL 正常路径：包装查询 + orderColumn 无误报")
    void customSqlOk() {
        SyncTaskConfig cfg = base("src_ok", "dst_ok");
        cfg.setSourceMode("CUSTOM_SQL");
        cfg.setSourceSql("SELECT id, name, updated_at, created FROM src_ok WHERE id > 0");
        cfg.setOrderColumn("id");
        List<PreflightIssue> issues = check(cfg);
        assertFalse(hasError(issues), dump(issues));

        SyncPlan plan = SyncPlan.resolve(cfg, srcDs, dstDs);
        assertTrue(plan.customSql());
        assertTrue(plan.wrappedSourceSql().contains("_ds_src"), plan.wrappedSourceSql());
        assertEquals(4, plan.columns().size());
    }

    @Test
    @DisplayName("TARGET_IS_METADATA：目标库就是元数据库且目标表命中元数据表名")
    void metadataGuard() {
        SyncTaskConfig cfg = base("src_ok", "sync_task");
        DefaultPreflighter guarded = new DefaultPreflighter(MetadataGuard.of(MySqlTestSupport.URL));
        List<PreflightIssue> issues = guarded.check(cfg, srcDs, dstDs);
        assertTrue(hasErrorCode(issues, ErrorCodes.TARGET_IS_METADATA), dump(issues));

        // 元数据库里的业务表只给 WARN
        SyncTaskConfig other = base("src_ok", "dst_ok");
        List<PreflightIssue> warnIssues = guarded.check(other, srcDs, dstDs);
        boolean warn = false;
        for (PreflightIssue i : warnIssues) {
            if (ErrorCodes.TARGET_IS_METADATA.equals(i.getCode()) && !i.isError()) {
                warn = true;
            }
        }
        assertTrue(warn, "元数据库里的业务表应给 WARN: " + dump(warnIssues));
        assertFalse(hasError(warnIssues), dump(warnIssues));
    }

    @Test
    @DisplayName("预检不掉连接：同一批 issues 可以重复求值")
    void issuesAreStable() {
        List<PreflightIssue> a = check(base("src_ok", "dst_ok"));
        List<PreflightIssue> b = check(base("src_ok", "dst_ok"));
        assertEquals(a.size(), b.size());
        assertNotNull(a);
    }

    // ------------------------------------------------------------------

    private SyncTaskConfig base(String srcTable, String dstTable) {
        SyncTaskConfig cfg = new SyncTaskConfig();
        cfg.setId(9100L);
        cfg.setName("预检测试");
        cfg.setSourceMode("TABLE");
        cfg.setSourceTable(srcTable);
        cfg.setTargetTable(dstTable);
        cfg.setSyncMode(SyncMode.FULL);
        cfg.setFullSyncStrategy(FullSyncStrategy.TRUNCATE);
        return cfg;
    }

    private List<PreflightIssue> check(SyncTaskConfig cfg) {
        return new DefaultPreflighter().check(cfg, srcDs, dstDs);
    }

    private static boolean hasError(List<PreflightIssue> issues) {
        for (PreflightIssue i : issues) {
            if (i.isError()) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasCode(List<PreflightIssue> issues, String code) {
        for (PreflightIssue i : issues) {
            if (code.equals(i.getCode())) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasErrorCode(List<PreflightIssue> issues, String code) {
        for (PreflightIssue i : issues) {
            if (code.equals(i.getCode()) && i.isError()) {
                return true;
            }
        }
        return false;
    }

    private static String dump(List<PreflightIssue> issues) {
        StringBuilder sb = new StringBuilder();
        for (PreflightIssue i : issues) {
            sb.append("\n  ").append(i);
        }
        return sb.toString();
    }
}
