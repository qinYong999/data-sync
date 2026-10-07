package com.datasync.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.MySqlTestSupport;
import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.model.ErrorPolicy;
import com.datasync.core.model.FullSyncStrategy;
import com.datasync.core.model.IncrPolicy;
import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncRunResult;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.model.enums.SyncMode;
import com.datasync.core.preflight.MetadataGuard;
import com.datasync.core.preflight.SyncPlan;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 引擎真机 MySQL 端到端测试（生产主线 MySQL → MySQL）。
 *
 * <p>覆盖任务书要求的核心语义：键集分页不丢不重、幂等 upsert、水位只在成功后推进、
 * endCursor 等于最后提交行（不是 MAX）、坏行策略、chunk 重试、协作式取消、dryRun、预检拦截。
 */
class DefaultSyncEngineMySqlTest {

    private static final long TASK_ID = 9001L;

    private DataSource srcDs;
    private DataSource dstDs;
    private DefaultSyncEngine engine;

    @BeforeAll
    static void setUpClass() {
        MySqlTestSupport.assumeAvailable();
    }

    @BeforeEach
    void setUp() {
        srcDs = MySqlTestSupport.dataSource();
        dstDs = MySqlTestSupport.dataSource();
        engine = new DefaultSyncEngine();
        MySqlTestSupport.exec(dstDs,
                "DROP TABLE IF EXISTS dst_big", "DROP TABLE IF EXISTS dst_multi", "DROP TABLE IF EXISTS dst_incr",
                "DROP TABLE IF EXISTS dst_fail", "DROP TABLE IF EXISTS dst_types", "DROP TABLE IF EXISTS sync_task");
        MySqlTestSupport.exec(srcDs,
                "DROP TABLE IF EXISTS src_big", "DROP TABLE IF EXISTS src_multi", "DROP TABLE IF EXISTS src_incr",
                "DROP TABLE IF EXISTS src_fail", "DROP TABLE IF EXISTS src_types");
    }

    // ------------------------------------------------------------------
    // 全量：键集分页不丢不重
    // ------------------------------------------------------------------

    @Test
    @DisplayName("全量同步 5000 行 / pageSize=500 / batchSize=200：逐行比对源与目标完全一致")
    void fullSyncMultiPage() {
        createBigTables(5000);
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        cfg.setPageSize(500);
        cfg.setBatchSize(200);

        List<Long> progressRead = new ArrayList<>();
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, p -> progressRead.add(p.writtenRows()));

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals("COMPLETED", r.getStatus());
        assertEquals(5000, r.getReadRows());
        assertEquals(5000, r.getWrittenRows());
        assertEquals(0, r.getSkippedRows());
        assertNull(r.getEndCursor(), "没有增量列时不应推进水位");
        assertEquals(25, progressRead.size(), "5000 行 / batchSize 200 = 25 个 chunk 回调");
        assertEquals(5000L, MySqlTestSupport.count(dstDs, "dst_big"));
        assertEquals(MySqlTestSupport.dump(srcDs, "src_big", "id"),
                MySqlTestSupport.dump(dstDs, "dst_big", "id"), "源与目标必须逐行一致");
    }

    @Test
    @DisplayName("幂等：同一任务连续执行 3 次，行数不增长、内容不变")
    void idempotentFullSync() {
        createBigTables(1000);
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        cfg.setPageSize(300);
        cfg.setBatchSize(150);
        List<List<String>> first = null;
        for (int i = 0; i < 3; i++) {
            SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);
            assertTrue(r.isSuccess(), "第 " + (i + 1) + " 次执行失败: " + r.getErrorMessage());
            assertEquals(1000L, MySqlTestSupport.count(dstDs, "dst_big"), "第 " + (i + 1) + " 次执行后行数变化");
            List<List<String>> now = MySqlTestSupport.dump(dstDs, "dst_big", "id");
            if (first == null) {
                first = now;
            } else {
                assertEquals(first, now, "第 " + (i + 1) + " 次执行后内容变化");
            }
        }
    }

    @Test
    @DisplayName("复合主键 (a,b)：键集分页元组比较不丢不重")
    void compositePrimaryKey() {
        MySqlTestSupport.exec(srcDs,
                "CREATE TABLE src_multi (a INT NOT NULL, b INT NOT NULL, v VARCHAR(20),"
                        + " PRIMARY KEY (a, b)) ENGINE=InnoDB",
                "INSERT INTO src_multi (a, b, v) SELECT x.n DIV 60, x.n MOD 60, CONCAT('v-', x.n)"
                        + " FROM (SELECT a.n + b.n*10 + c.n*100 + d.n*1000 AS n FROM"
                        + " (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4"
                        + " UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) a,"
                        + " (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4"
                        + " UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) b,"
                        + " (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4"
                        + " UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) c,"
                        + " (SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4"
                        + " UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) d) x"
                        + " WHERE x.n < 1200",
                "CREATE TABLE dst_multi LIKE src_multi");

        SyncTaskConfig cfg = baseConfig("src_multi", "dst_multi");
        cfg.setPageSize(200);
        cfg.setBatchSize(100);
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(1200, r.getWrittenRows());
        assertEquals(1200L, MySqlTestSupport.count(dstDs, "dst_multi"));
        assertEquals(MySqlTestSupport.dump(srcDs, "src_multi", "a, b"),
                MySqlTestSupport.dump(dstDs, "dst_multi", "a, b"));
    }

    // ------------------------------------------------------------------
    // 增量：水位语义
    // ------------------------------------------------------------------

    @Test
    @DisplayName("增量 endCursor = 最后提交行的增量值（不是 MAX）；safetyLag 决定读上界")
    void incrementalEndCursorIsNotMax() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        LocalDateTime old = now.minusHours(2);
        MySqlTestSupport.exec(srcDs,
                "CREATE TABLE src_incr (id INT NOT NULL PRIMARY KEY, updated_at DATETIME NOT NULL,"
                        + " v VARCHAR(20)) ENGINE=InnoDB",
                "INSERT INTO src_incr VALUES"
                        + " (1, '" + ts(old) + "', 'a'),"
                        + " (2, '" + ts(old.plusMinutes(30)) + "', 'b'),"
                        + " (3, '" + ts(now) + "', 'c'),"
                        + " (4, '" + ts(now) + "', 'd')",
                "CREATE TABLE dst_incr LIKE src_incr");

        SyncTaskConfig cfg = baseConfig("src_incr", "dst_incr");
        cfg.setSyncMode(SyncMode.INCR);
        cfg.setIncrColumn("updated_at");
        cfg.setIncrPolicy(new IncrPolicy(3600, 0));   // 安全滞后 1 小时：now 的两行不应被读到
        cfg.setPageSize(100);
        cfg.setBatchSize(50);

        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(2, r.getReadRows(), "safetyLag=3600 只应读到 2 小时前与 1.5 小时前的两行");
        assertEquals(2, r.getWrittenRows());
        assertNotNull(r.getEndCursor());
        // 复合游标：增量值|主键（竖线分隔，运维可直接读懂）。
        // 第 1 段必须是最后提交行的 updated_at，而不是 MAX(updated_at)
        assertTrue(r.getEndCursor().contains("|"), "updated_at 不唯一，应生成复合游标: " + r.getEndCursor());
        assertTrue(r.getEndCursor().startsWith(ts(old.plusMinutes(30))), r.getEndCursor());
        assertTrue(r.getEndCursor().endsWith("|2"), "复合游标必须带主键分量: " + r.getEndCursor());
        assertFalse(r.getEndCursor().contains(ts(now)), "绝不能把 MAX(updated_at) 当水位");

        // 第二次执行：用上一次的 endCursor 续跑，safetyLag=0，应只读到 now 的两行
        cfg.setIncrPolicy(new IncrPolicy(0, 0));
        cfg.setCursorValue(r.getEndCursor());
        SyncRunResult r2 = engine.run(cfg, srcDs, dstDs, null);
        assertTrue(r2.isSuccess(), r2.getErrorMessage());
        assertEquals(2, r2.getReadRows(), "第二次应只读到 now 的两行: " + r2.getEndCursor());
        assertEquals(4L, MySqlTestSupport.count(dstDs, "dst_incr"));
        assertEquals(MySqlTestSupport.dump(srcDs, "src_incr", "id"),
                MySqlTestSupport.dump(dstDs, "dst_incr", "id"));

        // 第三次执行：无新数据，水位不倒退、目标不变
        cfg.setCursorValue(r2.getEndCursor());
        SyncRunResult r3 = engine.run(cfg, srcDs, dstDs, null);
        assertTrue(r3.isSuccess(), r3.getErrorMessage());
        assertEquals(0, r3.getReadRows());
        assertEquals(4L, MySqlTestSupport.count(dstDs, "dst_incr"));
    }

    @Test
    @DisplayName("增量回看窗口 lookback：水位之前的行会被重新读入")
    void lookbackWindow() {
        LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
        MySqlTestSupport.exec(srcDs,
                "CREATE TABLE src_incr (id INT NOT NULL PRIMARY KEY, updated_at DATETIME NOT NULL,"
                        + " v VARCHAR(20)) ENGINE=InnoDB",
                "INSERT INTO src_incr VALUES"
                        + " (1, '" + ts(now.minusMinutes(30)) + "', 'a'),"
                        + " (2, '" + ts(now.minusMinutes(10)) + "', 'b'),"
                        + " (3, '" + ts(now.minusMinutes(5)) + "', 'c')",
                "CREATE TABLE dst_incr LIKE src_incr");

        SyncTaskConfig cfg = baseConfig("src_incr", "dst_incr");
        cfg.setSyncMode(SyncMode.INCR);
        cfg.setIncrColumn("updated_at");
        cfg.setCursorValue(ts(now.minusMinutes(10)));
        cfg.setIncrPolicy(new IncrPolicy(0, 600));   // 回看 10 分钟
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(2, r.getReadRows(), "回看 10 分钟应把 10 分钟前那行也重读进来（幂等 upsert 保证不重）");
        assertEquals(2L, MySqlTestSupport.count(dstDs, "dst_incr"));
        List<List<String>> dumped = MySqlTestSupport.dump(dstDs, "dst_incr", "id");
        assertEquals("2", dumped.get(1).get(0), "id=2（正好等于水位）必须被回看窗口重读: " + dumped);
        assertEquals("3", dumped.get(2).get(0), dumped.toString());

        // 对比：没有回看窗口时只读到 1 行
        MySqlTestSupport.exec(dstDs, "TRUNCATE TABLE dst_incr");
        cfg.setIncrPolicy(new IncrPolicy(0, 0));
        SyncRunResult r2 = engine.run(cfg, srcDs, dstDs, null);
        assertTrue(r2.isSuccess(), r2.getErrorMessage());
        assertEquals(1, r2.getReadRows());
    }

    // ------------------------------------------------------------------
    // 失败 / 坏行 / 取消 / dryRun
    // ------------------------------------------------------------------

    @Test
    @DisplayName("写入失败：status=FAILED 且 endCursor 为 null（水位绝不推进）")
    void failureKeepsWatermark() {
        createFailTables("INSERT INTO src_fail VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,NULL),(6,'f'),(7,'g')");
        SyncTaskConfig cfg = baseConfig("src_fail", "dst_fail");
        cfg.setSyncMode(SyncMode.INCR);
        cfg.setIncrColumn("id");
        cfg.setPageSize(10);
        cfg.setBatchSize(2);
        cfg.getErrorPolicy().setSkipBadRows(false);

        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertFalse(r.isSuccess());
        assertEquals("FAILED", r.getStatus());
        assertNull(r.getEndCursor(), "失败时水位必须为 null");
        assertNotNull(r.getErrorMessage());
        assertTrue(r.getWrittenRows() < 7, "失败时只应提交部分 chunk: " + r.getWrittenRows());
    }

    @Test
    @DisplayName("坏行跳过：skipBadRows=true 时任务完成、skippedRows=1、坏行落 errors")
    void skipBadRows() {
        createFailTables("INSERT INTO src_fail VALUES (1,'a'),(2,'b'),(3,'c'),(4,'d'),(5,NULL),(6,'f'),(7,'g')");
        SyncTaskConfig cfg = baseConfig("src_fail", "dst_fail");
        cfg.setSyncMode(SyncMode.INCR);
        cfg.setIncrColumn("id");
        cfg.setPageSize(10);
        cfg.setBatchSize(3);
        cfg.getErrorPolicy().setSkipBadRows(true);
        cfg.getErrorPolicy().setMaxSkipRows(10);

        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(7, r.getReadRows());
        assertEquals(6, r.getWrittenRows());
        assertEquals(1, r.getSkippedRows());
        assertEquals(6L, MySqlTestSupport.count(dstDs, "dst_fail"));
        assertFalse(r.getErrors().isEmpty(), "坏行必须落 sync_error 明细");
        assertEquals("WRITE", r.getErrors().get(0).getPhase());
        assertTrue(r.getErrors().get(0).getRowData().contains("5"), r.getErrors().get(0).getRowData());
        assertNotNull(r.getEndCursor());
    }

    @Test
    @DisplayName("坏行超过 maxSkipRows：整体失败，水位不推进")
    void skipLimitExceeded() {
        createFailTables("INSERT INTO src_fail VALUES (1,NULL),(2,NULL),(3,NULL),(4,NULL),(5,'e')");
        SyncTaskConfig cfg = baseConfig("src_fail", "dst_fail");
        cfg.setSyncMode(SyncMode.INCR);
        cfg.setIncrColumn("id");
        cfg.setPageSize(10);
        cfg.setBatchSize(5);
        cfg.getErrorPolicy().setSkipBadRows(true);
        cfg.getErrorPolicy().setMaxSkipRows(2);

        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertFalse(r.isSuccess());
        assertEquals("FAILED", r.getStatus());
        assertNull(r.getEndCursor());
        assertTrue(r.getErrorMessage().contains("超过上限"), r.getErrorMessage());
        assertTrue(r.getSkippedRows() >= 3, "跳过的行数应被记录: " + r.getSkippedRows());
    }

    @Test
    @DisplayName("协作式取消：CANCELLED 且水位不推进")
    void cancelRun() {
        createBigTables(3000);
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        cfg.setId(TASK_ID + 1);
        cfg.setPageSize(500);
        cfg.setBatchSize(500);

        SyncRunResult r = engine.run(cfg, srcDs, dstDs, progress -> engine.cancel(TASK_ID + 1));

        assertEquals("CANCELLED", r.getStatus());
        assertFalse(r.isSuccess());
        assertNull(r.getEndCursor());
        assertTrue(r.getWrittenRows() > 0, "取消前已提交的 chunk 不应回滚: " + r.getWrittenRows());
        assertTrue(r.getWrittenRows() < 3000, "取消后不应跑完: " + r.getWrittenRows());
    }

    @Test
    @DisplayName("dryRun：读完整链路但不写目标、不清空目标、不推进水位")
    void dryRun() {
        createBigTables(1000);
        MySqlTestSupport.exec(dstDs, "INSERT INTO dst_big SELECT * FROM src_big WHERE id = 1");
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        cfg.setDryRun(true);

        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(1000, r.getReadRows());
        assertEquals(0, r.getWrittenRows(), "dryRun 不应写任何数据");
        assertNull(r.getEndCursor(), "dryRun 不得推进水位");
        assertEquals(1L, MySqlTestSupport.count(dstDs, "dst_big"), "dryRun 不得清空目标表");
    }

    // ------------------------------------------------------------------
    // 预检拦截 / 类型往返 / 元数据库防护
    // ------------------------------------------------------------------

    @Test
    @DisplayName("预检不通过（TEXT → INT）：一行都不写，且不清空目标表")
    void preflightBlocksEverything() {
        MySqlTestSupport.exec(srcDs,
                "CREATE TABLE src_types (id INT NOT NULL PRIMARY KEY, remark TEXT) ENGINE=InnoDB",
                "INSERT INTO src_types VALUES (1, 'hello'), (2, 'world')");
        MySqlTestSupport.exec(dstDs,
                "CREATE TABLE dst_types (id INT NOT NULL PRIMARY KEY, remark INT) ENGINE=InnoDB",
                "INSERT INTO dst_types VALUES (99, 99)");

        SyncTaskConfig cfg = baseConfig("src_types", "dst_types");
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertFalse(r.isSuccess());
        assertEquals("FAILED", r.getStatus());
        assertTrue(r.getErrorMessage().contains("预检未通过"), r.getErrorMessage());
        boolean hasTypeIssue = false;
        for (PreflightIssue i : r.getPreflightIssues()) {
            if ("TYPE_INCOMPATIBLE".equals(i.getCode()) && i.isError()) {
                hasTypeIssue = true;
            }
        }
        assertTrue(hasTypeIssue, "应报 TYPE_INCOMPATIBLE: " + r.getPreflightIssues());
        assertEquals(0, r.getReadRows(), "预检失败不应读取任何数据");
        assertEquals(1L, MySqlTestSupport.count(dstDs, "dst_types"), "预检失败不得清空目标表");
    }

    @Test
    @DisplayName("类型往返：BIT(1) / YEAR / DECIMAL / DATETIME 真机一致")
    void typeRoundTrip() {
        MySqlTestSupport.exec(srcDs,
                "CREATE TABLE src_types (id INT NOT NULL PRIMARY KEY, flag BIT(1), yr YEAR,"
                        + " amount DECIMAL(10,2), created DATETIME(0), big BIGINT, note VARCHAR(50),"
                        + " raw VARBINARY(16)) ENGINE=InnoDB",
                "INSERT INTO src_types VALUES"
                        + " (1, b'1', 2024, 1234.56, '2026-01-02 03:04:05', 9007199254740993, '中文备注',"
                        + " X'0102'),"
                        + " (2, b'0', 1999, -0.01, '2000-12-31 23:59:59', -1, NULL, NULL)",
                "CREATE TABLE dst_types LIKE src_types");

        SyncTaskConfig cfg = baseConfig("src_types", "dst_types");
        cfg.setPageSize(10);
        cfg.setBatchSize(5);
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(2, r.getWrittenRows());
        assertEquals(MySqlTestSupport.dump(srcDs, "src_types", "id"),
                MySqlTestSupport.dump(dstDs, "dst_types", "id"));
    }

    @Test
    @DisplayName("空表全量同步：正常完成、不报错")
    void emptySource() {
        createBigTables(0);
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);
        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(0, r.getReadRows());
        assertEquals(0L, MySqlTestSupport.count(dstDs, "dst_big"));
    }

    @Test
    @DisplayName("DELETE / SWAP 全量策略同样可用")
    void otherFullStrategies() {
        createBigTables(300);
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        cfg.setFullSyncStrategy(FullSyncStrategy.DELETE);
        assertTrue(engine.run(cfg, srcDs, dstDs, null).isSuccess());
        assertEquals(300L, MySqlTestSupport.count(dstDs, "dst_big"));

        cfg.setFullSyncStrategy(FullSyncStrategy.SWAP);
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);
        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(300L, MySqlTestSupport.count(dstDs, "dst_big"));
        assertEquals(MySqlTestSupport.dump(srcDs, "src_big", "id"),
                MySqlTestSupport.dump(dstDs, "dst_big", "id"));
    }

    @Test
    @DisplayName("元数据库防护在引擎执行路径生效：目标表命中元数据表名直接拒绝")
    void metadataGuardOnExecutionPath() {
        createBigTables(10);
        MySqlTestSupport.exec(dstDs, "CREATE TABLE sync_task LIKE src_big");
        // 目标库 URL 就是元数据库 URL（同一个库），且目标表名是元数据表
        DefaultSyncEngine guarded = new DefaultSyncEngine(MetadataGuard.of(MySqlTestSupport.URL));
        SyncTaskConfig cfg = baseConfig("src_big", "sync_task");

        SyncRunResult r = guarded.run(cfg, srcDs, dstDs, null);

        assertFalse(r.isSuccess());
        boolean hasGuardIssue = false;
        for (PreflightIssue i : r.getPreflightIssues()) {
            if ("TARGET_IS_METADATA".equals(i.getCode())) {
                hasGuardIssue = true;
            }
        }
        assertTrue(hasGuardIssue, "应报 TARGET_IS_METADATA: " + r.getPreflightIssues());
        assertEquals(0L, MySqlTestSupport.count(dstDs, "sync_task"), "必须一行都没写");
    }

    // ------------------------------------------------------------------
    // 决策 1 证据：引擎真实生成的键集谓词 SQL 文本（最不放心的静默丢数路径）
    // ------------------------------------------------------------------

    @Test
    @DisplayName("决策1证据：源表复合主键 (a,b) → 键集谓词必须是展开式 OR（行构造器不下推索引）")
    void evidenceKeysetPredicateCompositePk() {
        MySqlTestSupport.exec(srcDs,
                "CREATE TABLE src_multi (a INT NOT NULL, b INT NOT NULL, v VARCHAR(10),"
                        + " PRIMARY KEY (a, b)) ENGINE=InnoDB",
                "CREATE TABLE dst_multi LIKE src_multi");
        SyncTaskConfig cfg = baseConfig("src_multi", "dst_multi");
        cfg.setPageSize(200);
        SyncPlan plan = SyncPlan.resolve(cfg, srcDs, dstDs);
        assertFalse(plan.hasError(), plan.issues().toString());
        assertEquals(List.of("a", "b"), plan.fullOrderKeyColumns());

        PageQuery firstPage = PageQueryBuilder.build(plan, KeysetPosition.first(), Watermark.none(), 200);
        assertEquals("SELECT `a`, `b`, `v` FROM `src_multi` ORDER BY `a` ASC, `b` ASC LIMIT 200",
                firstPage.sql);
        assertTrue(firstPage.params.isEmpty());

        PageQuery nextPage = PageQueryBuilder.build(plan,
                KeysetPosition.of(List.of("a", "b"), List.of(10, 20)), Watermark.none(), 200);
        assertEquals("SELECT `a`, `b`, `v` FROM `src_multi`"
                + " WHERE ((`a` > ?) OR (`a` = ? AND `b` > ?))"
                + " ORDER BY `a` ASC, `b` ASC LIMIT 200", nextPage.sql);
        // 2 列键 → 3 个占位符 → 参数按前缀重复展开
        assertEquals(List.of(10, 10, 20), nextPage.params);
    }

    @Test
    @DisplayName("决策1证据：增量列 updated_at 不唯一 → 谓词带主键 tie-breaker (updated_at, id) > (?, ?)")
    void evidenceKeysetPredicateNonUniqueIncrementalColumn() throws Exception {
        MySqlTestSupport.exec(srcDs,
                "CREATE TABLE src_incr (id INT NOT NULL PRIMARY KEY, updated_at DATETIME NOT NULL,"
                        + " v VARCHAR(20)) ENGINE=InnoDB",
                "CREATE TABLE dst_incr LIKE src_incr");
        SyncTaskConfig cfg = baseConfig("src_incr", "dst_incr");
        cfg.setSyncMode(SyncMode.INCR);
        cfg.setIncrColumn("updated_at");
        cfg.setCursorValue("2026-05-30 21:01:03|5");   // 上一轮留下的复合水位（增量值|主键）
        cfg.setIncrPolicy(new IncrPolicy(0, 0));
        cfg.setPageSize(100);

        SyncPlan plan = SyncPlan.resolve(cfg, srcDs, dstDs);
        assertFalse(plan.hasError(), plan.issues().toString());
        assertEquals(List.of("updated_at", "id"), plan.fullOrderKeyColumns(), "必须把主键追加为 tie-breaker");
        assertEquals(List.of("id"), plan.tieBreakKeyColumns());

        Watermark wm;
        try (java.sql.Connection c = srcDs.getConnection()) {
            wm = Watermark.forIncremental(plan, cfg, c);
        }
        // 复合水位 + 无回看窗口 → 首页直接用元组位置续跑
        assertEquals(List.of("updated_at", "id"), wm.initialPosition.keyColumns());
        assertEquals(LocalDateTime.of(2026, 5, 30, 21, 1, 3), wm.initialPosition.values().get(0));
        assertEquals(5, wm.initialPosition.values().get(1));
        assertNull(wm.lowerValue, "复合位置已经蕴含下界，不应再生成标量下界");

        PageQuery q = PageQueryBuilder.build(plan, wm.initialPosition, wm, 100);
        assertEquals("SELECT `id`, `updated_at`, `v` FROM `src_incr`"
                + " WHERE `updated_at` <= ?"
                + " AND ((`updated_at` > ?) OR (`updated_at` = ? AND `id` > ?))"
                + " ORDER BY `updated_at` ASC, `id` ASC LIMIT 100", q.sql);
        // 参数顺序：上界 + 展开后的游标分量（1 + n(n+1)/2 = 4）
        assertEquals(4, q.params.size(), "参数顺序：上界 + 展开的游标分量: " + q.params);
        assertEquals(LocalDateTime.of(2026, 5, 30, 21, 1, 3), q.params.get(1));
        assertEquals(LocalDateTime.of(2026, 5, 30, 21, 1, 3), q.params.get(2));
        assertEquals(5, q.params.get(3));

        // 标量水位（手工写入/旧格式）：退回标量下界，首页无位置谓词
        cfg.setCursorValue("2026-05-30 21:01:03");
        Watermark scalarWm;
        try (java.sql.Connection c = srcDs.getConnection()) {
            scalarWm = Watermark.forIncremental(plan, cfg, c);
        }
        assertTrue(scalarWm.initialPosition.isFirst());
        assertEquals(LocalDateTime.of(2026, 5, 30, 21, 1, 3), scalarWm.lowerValue);
        PageQuery q2 = PageQueryBuilder.build(plan, scalarWm.initialPosition, scalarWm, 100);
        assertEquals("SELECT `id`, `updated_at`, `v` FROM `src_incr`"
                + " WHERE `updated_at` > ? AND `updated_at` <= ?"
                + " ORDER BY `updated_at` ASC, `id` ASC LIMIT 100", q2.sql);
        assertEquals(2, q2.params.size(), q2.params.toString());
    }

    @Test
    @DisplayName("chunk 超过单语句占位符上限：同一事务内拆多条语句，参数下标不能错位")
    void chunkSplitsAcrossStatements() {
        final int colCount = 60;              // 61 列 → 单语句上限 60000/61 ≈ 983 行
        final int rows = 2500;                // 2500 行 → 必须拆成 3 条语句
        StringBuilder ddl = new StringBuilder("CREATE TABLE src_wide (id INT NOT NULL PRIMARY KEY");
        StringBuilder insertCols = new StringBuilder("id");
        StringBuilder insertVals = new StringBuilder("x.n + 1");
        for (int j = 1; j <= colCount; j++) {
            ddl.append(", c").append(j).append(" INT NOT NULL");
            insertCols.append(", c").append(j);
            insertVals.append(", (x.n + 1) * 100 + ").append(j);
        }
        ddl.append(") ENGINE=InnoDB");
        MySqlTestSupport.exec(srcDs, "DROP TABLE IF EXISTS src_wide", ddl.toString(),
                "INSERT INTO src_wide (" + insertCols + ") SELECT " + insertVals
                        + " FROM (SELECT a.n + b.n*10 + c.n*100 + d.n*1000 AS n FROM"
                        + digitsAlias("a") + ", " + digitsAlias("b") + ", " + digitsAlias("c") + ", "
                        + digitsAlias("d") + ") x WHERE x.n < " + rows);
        MySqlTestSupport.recreateLike(dstDs, "src_wide", "dst_wide");

        SyncTaskConfig cfg = baseConfig("src_wide", "dst_wide");
        cfg.setPageSize(rows);
        cfg.setBatchSize(rows);
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(rows, r.getWrittenRows());
        // 聚合校验：参数下标错位会立刻在这里暴露（列值互相串位）
        String agg = "SELECT COUNT(*), SUM(c1), SUM(c30), SUM(c60) FROM ";
        assertEquals(scalarAgg(srcDs, agg + "src_wide"), scalarAgg(dstDs, agg + "dst_wide"));
        assertEquals(MySqlTestSupport.dump(srcDs, "src_wide", "id"),
                MySqlTestSupport.dump(dstDs, "dst_wide", "id"));
        MySqlTestSupport.execQuietly(srcDs, "DROP TABLE IF EXISTS src_wide");
        MySqlTestSupport.execQuietly(dstDs, "DROP TABLE IF EXISTS dst_wide");
    }

    private static String scalarAgg(DataSource ds, String sql) {
        try (java.sql.Connection c = ds.getConnection();
             java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1) + "/" + rs.getBigDecimal(2) + "/" + rs.getBigDecimal(3)
                    + "/" + rs.getBigDecimal(4);
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("聚合校验失败: " + e.getMessage(), e);
        }
    }

    @Test
    @DisplayName("读侧预取有界：任意 chunk 回调时刻，已读未写行数 ≤ pageSize + batchSize - 1")
    void readAheadIsBounded() {
        createBigTables(5000);
        final int pageSize = 1000;
        final int batchSize = 400;
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        cfg.setPageSize(pageSize);
        cfg.setBatchSize(batchSize);

        List<Long> gaps = new ArrayList<>();
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, p -> gaps.add(p.readRows() - p.writtenRows()));

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(5000, r.getWrittenRows());
        assertFalse(gaps.isEmpty(), "应当收到 chunk 回调");
        long max = gaps.stream().mapToLong(Long::longValue).max().orElse(0L);
        long bound = pageSize + batchSize - 1L;
        assertTrue(max <= bound, "已读未写行数突破上界 " + bound + "：实际 " + max + "（回调序列 " + gaps + "）");
        System.out.println("[有界预取] 回调次数=" + gaps.size() + "，最大已读未写=" + max + "，上界=" + bound);
    }

    @Test
    @DisplayName("慢回调不再拖慢同步：700ms/次的回调用 AsyncRunMetricsListener 包装后总耗时不受影响")
    void slowListenerDoesNotSlowDownSync() {
        createBigTables(10000);                     // pageSize 1000 / batchSize 500 → 20 个 chunk
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        cfg.setPageSize(1000);
        cfg.setBatchSize(500);

        List<Long> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
        // 同步调用的话 20 × 700ms = 14s；包装后应当只花同步本身的时间
        AsyncRunMetricsListener metrics = AsyncRunMetricsListener.wrapping(p -> {
            try {
                Thread.sleep(700);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            delivered.add(p.writtenRows());
        }, 4, "it-slow-listener");

        long t0 = System.currentTimeMillis();
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, metrics);
        long elapsed = System.currentTimeMillis() - t0;
        metrics.flushAndClose(5000);

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(10000, r.getWrittenRows());
        assertTrue(elapsed < 5000, "慢回调不得拖慢同步：实际 " + elapsed + "ms（同步调用会是 ~14000ms）");
        assertTrue(metrics.droppedCallbacks() > 0, "慢回调必然丢弃中间态: " + metrics);
        assertEquals(10000L, delivered.get(delivered.size() - 1),
                "收尾必须把最终进度投递出去: " + delivered);
        System.out.println("[慢回调不拖慢同步] 总耗时=" + elapsed + "ms，" + metrics);
    }

    // ------------------------------------------------------------------
    // D1 根因回归：键集谓词必须能被下推成索引范围访问
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D1 回归：深游标每页扫描行数不随位置增长（Handler_read_next 实测，防止分页退化）")
    void keysetDeepCursorDoesNotScanWholeTable() throws Exception {
        final int rows = 100_000;
        String d = digitsAlias("dd");
        MySqlTestSupport.exec(srcDs, "DROP TABLE IF EXISTS src_scan", "DROP TABLE IF EXISTS dst_scan",
                "CREATE TABLE src_scan (id INT NOT NULL PRIMARY KEY, u DATETIME NOT NULL, v VARCHAR(16),"
                        + " INDEX idx_scan_u (u, id)) ENGINE=InnoDB",
                "INSERT INTO src_scan (id, u, v)"
                        + " SELECT x.n + 1, TIMESTAMPADD(SECOND, x.n, '2026-01-01 00:00:00'), CONCAT('v', x.n + 1)"
                        + " FROM (SELECT a.n + b.n*10 + c.n*100 + dd.n*1000 + e.n*10000 AS n FROM "
                        + digitsAlias("a") + ", " + digitsAlias("b") + ", " + digitsAlias("c") + ", "
                        + d + ", " + digitsAlias("e") + ") x WHERE x.n < " + rows);
        MySqlTestSupport.recreateLike(dstDs, "src_scan", "dst_scan");

        SyncTaskConfig cfg = baseConfig("src_scan", "dst_scan");
        cfg.setSyncMode(SyncMode.INCR);
        cfg.setIncrColumn("u");
        cfg.setPageSize(1000);
        SyncPlan plan = SyncPlan.resolve(cfg, srcDs, dstDs);
        assertFalse(plan.hasError(), plan.issues().toString());
        assertEquals(List.of("u", "id"), plan.fullOrderKeyColumns());

        // ⚠ 前提说明（后人换夹具前务必看懂）：本夹具的**索引列顺序 (u, id) 与 ORDER BY `u`,`id` 完全一致**，
        //   "展开式 OR 能被下推成索引范围访问"这个前提在这里成立，所以下面的**绝对断言**才有意义。
        //   真实世界里这个前提并不总成立（DBA 后加的索引、复合键顺序不同），因此我们还补了一条
        //   **不依赖任何前提的相对断言**（见本方法末尾）：展开式 ≤ 行构造器 才是不变量。
        LocalDateTime shallowCursor = LocalDateTime.of(2026, 1, 1, 0, 16, 40);
        LocalDateTime deepCursor = LocalDateTime.of(2026, 1, 2, 3, 30, 0);
        long shallow = scannedRowsForCursor(plan, shallowCursor, 1000);
        long deep = scannedRowsForCursor(plan, deepCursor, 99_000);
        System.out.println("[D1 回归] 浅游标扫描 " + shallow + " 行，深游标扫描 " + deep + " 行（页大小 1000）");

        // 绝对断言：展开式 OR 下推成索引范围访问 → 每页只读 ~pageSize 条索引项，与游标深度无关。
        // 若有人改回行构造器 (u,id) > (?,?)，深游标会退化成 ~99,000 行扫描，这里立刻红。
        assertTrue(deep <= 3 * 1000, "深游标每页扫描行数不得随位置增长，实测 " + deep);
        assertTrue(deep <= shallow + 1000, "深浅游标的扫描行数应同量级：shallow=" + shallow + " deep=" + deep);

        // ---- 相对断言（不依赖索引布局）：同一张表、同一游标位置，产品形态 vs 修复前形态 ----
        Watermark wm;
        try (java.sql.Connection c = srcDs.getConnection()) {
            wm = Watermark.forIncremental(plan, cfg, c);   // 与真实增量执行同形（含 updated_at <= 上界）
        }
        KeysetPosition deepPosition = KeysetPosition.of(List.of("u", "id"), List.of(deepCursor, 99_000));
        PageQuery expanded = PageQueryBuilder.build(plan, deepPosition, wm, plan.pageSize());
        // 把谓词手工换回修复前的行构造器形态，其余（列、上界、排序、LIMIT）逐字相同
        String rowCtorSql = expanded.sql.replace("((`u` > ?) OR (`u` = ? AND `id` > ?))",
                "(`u`, `id`) > (?, ?)");
        assertNotEquals(expanded.sql, rowCtorSql,
                "对照 SQL 必须真的换成行构造器形态，否则对照实验无意义: " + expanded.sql);
        assertTrue(rowCtorSql.contains("(`u`, `id`) >"), rowCtorSql);

        List<Object> rowCtorParams = new ArrayList<>();
        if (wm.upperValue != null) {
            rowCtorParams.add(wm.upperValue);
        }
        rowCtorParams.add(deepCursor);
        rowCtorParams.add(99_000);

        long expandedScan = scannedRowsForSql(expanded.sql, expanded.params);
        long rowCtorScan = scannedRowsForSql(rowCtorSql, rowCtorParams);
        System.out.println("[D1 回归] 同一深游标：展开式扫描 " + expandedScan + " 行，行构造器扫描 "
                + rowCtorScan + " 行（比值 " + (expandedScan == 0 ? "-" : (rowCtorScan / Math.max(1, expandedScan)))
                + "×）");

        assertTrue(expandedScan <= rowCtorScan,
                "展开式不得比行构造器扫得更多：expanded=" + expandedScan + " rowCtor=" + rowCtorScan);
        // 让检测器自证有效：两种形态若扫一样多，说明这个对照实验无法区分它们，
        // 那样的"通过"毫无意义——必须报错而不是通过（与多行 VALUES 的参数错位测试同一思路）
        assertTrue(rowCtorScan >= 10L * Math.max(1L, expandedScan),
                "对照实验必须能区分两种形态：expanded=" + expandedScan + " rowCtor=" + rowCtorScan);
        assertTrue(rowCtorScan > 10_000,
                "行构造器在深游标下应扫描上万行（否则夹具太小、检测器形同虚设）：" + rowCtorScan);

        MySqlTestSupport.execQuietly(srcDs, "DROP TABLE IF EXISTS src_scan");
        MySqlTestSupport.execQuietly(dstDs, "DROP TABLE IF EXISTS dst_scan");
    }

    /** 用给定游标跑一次真实分页读，返回服务端因该语句读取的索引项数（Handler_read_next 增量）。 */
    private long scannedRowsForCursor(SyncPlan plan, LocalDateTime cursorValue, int cursorId)
            throws Exception {
        PageQuery query = PageQueryBuilder.build(plan,
                KeysetPosition.of(List.of("u", "id"), List.of(cursorValue, cursorId)),
                Watermark.none(), plan.pageSize());
        return scannedRowsForSql(query.sql, query.params);
    }

    /** 执行一条分页 SQL，返回该语句造成的会话级 Handler_read_next 增量（服务端真实读取的索引项数）。 */
    private long scannedRowsForSql(String sql, List<Object> params) throws Exception {
        try (java.sql.Connection c = srcDs.getConnection()) {
            long before = handlerReadNext(c);
            try (java.sql.PreparedStatement ps = c.prepareStatement(sql,
                    java.sql.ResultSet.TYPE_FORWARD_ONLY, java.sql.ResultSet.CONCUR_READ_ONLY)) {
                ps.setFetchSize(Integer.MIN_VALUE);
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                try (java.sql.ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rs.getObject(1);
                    }
                }
            }
            return handlerReadNext(c) - before;
        }
    }

    private static long handlerReadNext(java.sql.Connection c) throws java.sql.SQLException {
        try (java.sql.Statement st = c.createStatement();
             java.sql.ResultSet rs = st.executeQuery("SHOW SESSION STATUS LIKE 'Handler_read_next'")) {
            return rs.next() ? rs.getLong(2) : 0L;
        }
    }

    @Test
    @DisplayName("分页 SQL 走真机也正确：pageSize 小于数据量时逐页翻到底")
    void pagingCoversAllRows() {
        createBigTables(1234);
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        cfg.setPageSize(100);
        cfg.setBatchSize(100);
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);
        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(1234, r.getReadRows());
        assertEquals(1234L, MySqlTestSupport.count(dstDs, "dst_big"));
    }

    @Test
    @DisplayName("10 万行全量同步（验收口径第 2 条；-Ddatasync.it.bigrows=true 时启用）")
    void fullSyncHundredThousandRows() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("datasync.it.bigrows"),
                "默认跳过 10 万行大表测试（用 -Ddatasync.it.bigrows=true 启用）");
        createBigTablesForHundredThousand();
        SyncTaskConfig cfg = baseConfig("src_big", "dst_big");
        cfg.setPageSize(1000);
        cfg.setBatchSize(500);

        long t0 = System.currentTimeMillis();
        SyncRunResult r = engine.run(cfg, srcDs, dstDs, null);
        long elapsed = System.currentTimeMillis() - t0;

        assertTrue(r.isSuccess(), r.getErrorMessage());
        assertEquals(100000, r.getReadRows());
        assertEquals(100000, r.getWrittenRows());
        assertEquals(0, r.getSkippedRows());
        assertEquals(100000L, MySqlTestSupport.count(dstDs, "dst_big"));
        // 逐行校验：把两表按主键排序后整体比对（10 万行不会 OOM）
        assertEquals(MySqlTestSupport.dump(srcDs, "src_big", "id"),
                MySqlTestSupport.dump(dstDs, "dst_big", "id"));
        System.out.println("[10 万行全量同步] 读取 " + r.getReadRows() + " 行 / 提交 " + r.getWrittenRows()
                + " 行，耗时 " + elapsed + "ms（含逐行校验）");
    }

    private void createBigTablesForHundredThousand() {
        MySqlTestSupport.exec(srcDs, "DROP TABLE IF EXISTS src_big",
                "CREATE TABLE src_big (id INT NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL,"
                        + " amount DECIMAL(12,2), created DATETIME, flag BIT(1), yr YEAR) ENGINE=InnoDB");
        String d = "(SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4"
                + " UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) ";
        MySqlTestSupport.exec(srcDs,
                "INSERT INTO src_big (id, name, amount, created, flag, yr)"
                        + " SELECT x.n + 1, CONCAT('name-', x.n + 1), (x.n + 1) / 100,"
                        + " TIMESTAMPADD(SECOND, x.n, '2026-01-01 00:00:00'), b'1', 2024"
                        + " FROM (SELECT a.n + b.n*10 + c.n*100 + dd.n*1000 + e.n*10000 AS n FROM "
                        + d + "a, " + d + "b, " + d + "c, " + d + "dd, " + d + "e) x WHERE x.n < 100000");
        MySqlTestSupport.recreateLike(dstDs, "src_big", "dst_big");
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private SyncTaskConfig baseConfig(String srcTable, String dstTable) {
        SyncTaskConfig cfg = new SyncTaskConfig();
        cfg.setId(TASK_ID);
        cfg.setName("真机测试");
        cfg.setSourceMode("TABLE");
        cfg.setSourceTable(srcTable);
        cfg.setTargetTable(dstTable);
        cfg.setSyncMode(SyncMode.FULL);
        cfg.setFullSyncStrategy(FullSyncStrategy.TRUNCATE);
        cfg.setPageSize(500);
        cfg.setBatchSize(200);
        cfg.setErrorPolicy(new ErrorPolicy());
        return cfg;
    }

    private void createBigTables(int rows) {
        MySqlTestSupport.exec(srcDs, "DROP TABLE IF EXISTS src_big",
                "CREATE TABLE src_big (id INT NOT NULL PRIMARY KEY, name VARCHAR(64) NOT NULL,"
                        + " amount DECIMAL(12,2), created DATETIME, flag BIT(1), yr YEAR) ENGINE=InnoDB");
        if (rows > 0) {
            MySqlTestSupport.exec(srcDs,
                    "INSERT INTO src_big (id, name, amount, created, flag, yr)"
                            + " SELECT x.n + 1, CONCAT('name-', x.n + 1), (x.n + 1) / 100,"
                            + " TIMESTAMPADD(SECOND, x.n, '2026-01-01 00:00:00'), b'1', 2024"
                            + " FROM (SELECT a.n + b.n*10 + c.n*100 + d.n*1000 AS n FROM"
                            + digitsAlias("a") + ", " + digitsAlias("b") + ", " + digitsAlias("c") + ", "
                            + digitsAlias("d") + ") x WHERE x.n < " + rows);
        }
        MySqlTestSupport.recreateLike(dstDs, "src_big", "dst_big");
    }

    private void createFailTables(String insertSql) {
        MySqlTestSupport.exec(srcDs,
                "CREATE TABLE src_fail (id INT NOT NULL PRIMARY KEY, name VARCHAR(20) NULL) ENGINE=InnoDB",
                insertSql);
        MySqlTestSupport.exec(dstDs,
                "CREATE TABLE dst_fail (id INT NOT NULL PRIMARY KEY, name VARCHAR(20) NOT NULL) ENGINE=InnoDB");
    }

    private static String digitsAlias(String alias) {
        return "(SELECT 0 n UNION SELECT 1 UNION SELECT 2 UNION SELECT 3 UNION SELECT 4"
                + " UNION SELECT 5 UNION SELECT 6 UNION SELECT 7 UNION SELECT 8 UNION SELECT 9) " + alias;
    }

    private static String ts(LocalDateTime t) {
        return com.datasync.core.mapper.ValueText.formatLocalDateTime(t);
    }
}
