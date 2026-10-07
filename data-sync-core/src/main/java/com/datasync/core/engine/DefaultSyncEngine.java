package com.datasync.core.engine;

import com.datasync.core.dialect.SqlDialect;
import com.datasync.core.jdbc.ColumnMeta;
import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.jdbc.TableRef;
import com.datasync.core.mapper.DefaultValueConverter;
import com.datasync.core.mapper.TypeMappers;
import com.datasync.core.mapper.ValueConversionException;
import com.datasync.core.mapper.ValueText;
import com.datasync.core.model.ErrorPolicy;
import com.datasync.core.model.FullSyncStrategy;
import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncError;
import com.datasync.core.model.SyncRunResult;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.preflight.DefaultPreflighter;
import com.datasync.core.preflight.MetadataGuard;
import com.datasync.core.preflight.Preflighter;
import com.datasync.core.preflight.SyncPlan;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 默认同步引擎（自研流式实现，不依赖 Spring Batch）。
 *
 * <h2>四件生死大事</h2>
 * <ol>
 *   <li><b>不丢</b>：水位只在整个任务成功后推进一次，推进值 = 本次<b>成功提交的最后一行</b>的增量列值。
 *       绝不 {@code SELECT MAX(incr)}（旧实现在任务成功后查 MAX 并写回游标，只要读取阶段漏过任何一行，
 *       那行数据就永久丢了）。任务失败/取消时 {@code endCursor == null}，调用方不得推进水位。</li>
 *   <li><b>不重</b>：目标写入是幂等 upsert（{@code INSERT ... ON DUPLICATE KEY UPDATE} / DM8 {@code MERGE}），
 *       匹配键取自目标表元数据（主键 → 唯一键），可被 {@code FieldMapping.primaryKey} 覆盖。
 *       因此重放、重复执行、断点重跑都安全。</li>
 *   <li><b>可续</b>：全量分页一律键集分页 {@code WHERE key > ? ORDER BY key LIMIT n}，
 *       排序键按「orderColumn → 增量列 → 主键（多列元组）→ 唯一键」解析并强制追加唯一 tie-breaker；
 *       没有任何 OFFSET 分页路径。</li>
 *   <li><b>可查</b>：预检不通过时一行数据都不写，问题以稳定错误码返回；
 *       坏行按 {@code ErrorPolicy} 落 {@code sync_error} 或快速失败。</li>
 * </ol>
 *
 * <h2>事务与重试口径</h2>
 * <ul>
 *   <li>一个 chunk（{@code batchSize} 行）一个事务：提交成功后才回调 {@code onChunk}。</li>
 *   <li>重试以 chunk / 分页为单位；重试用尽后若 {@code skipBadRows=true}，逐行隔离出坏行
 *       （能写的照常提交），坏行累计超过 {@code maxSkipRows} 时整体失败。</li>
 *   <li>{@code readRows} 计入重试导致的重复读取行数；{@code writtenRows} 只计真正提交成功的行数
 *       （upsert 在 MySQL 下返回 1/2/0 是"影响行数"而非提交行数，这里不用它）。</li>
 * </ul>
 *
 * <h2>重启语义（Lead 决策 2，必须记住）</h2>
 * <p>任何一次执行失败 / 被取消 / 进程被杀，<b>下次执行一律从上次成功的水位重新开始</b>，丢弃本次进度：
 * {@code lastCommittedKey} 与 {@code ChunkProgress.cursor()} 只用于"进度可观测"（运维看大表跑到哪了），
 * <b>不参与恢复</b>，也不存在 chunk 级水位持久化。因为写入是幂等 upsert，重放一定安全。
 * "全量同步"没有断点续传，失败就是重跑。
 *
 * <h2>资源纪律（Lead 决策 6）</h2>
 * <p>传入的 {@link DataSource} 是平台持有的共享连接池：<b>引擎绝不 close 它</b>，
 * 只借/还 {@link Connection}（一律 try-with-resources）。SWAP 策略的暂存表在成功或失败路径都会清理。
 *
 * <p>线程安全：实例只持有不可变依赖与一个并发取消标志表，单个实例可以被多个任务并发调用。
 */
public final class DefaultSyncEngine implements SyncEngine {

    private static final Logger log = LoggerFactory.getLogger(DefaultSyncEngine.class);

    /** 常量列（字段映射里只给了 defaultValue）的合成源类型。 */
    private static final ColumnMeta CONSTANT_META =
            new ColumnMeta("__default__", "VARCHAR", Types.VARCHAR, true, false, 0);

    private static final int MAX_MESSAGE_LENGTH = 2000;

    /**
     * 每个 chunk 输出一行分段耗时（系统属性 {@code -Ddatasync.engine.chunk.trace=true} 打开）。
     *
     * <p>默认关闭、不改变生产行为；排查"数据库空闲但 chunk 很慢"这类问题时必须打开它——
     * 外部只能证明"不在数据库"，只有分段埋点能指出时间到底花在读、映射、写、提交还是回调上。
     */
    private static final boolean CHUNK_TRACE = Boolean.getBoolean("datasync.engine.chunk.trace");

    private final Preflighter preflighter;

    /** 运行中的取消标志：key = taskId。 */
    private final Map<Long, AtomicBoolean> activeRuns = new ConcurrentHashMap<>();

    public DefaultSyncEngine() {
        this((Preflighter) null);
    }

    /** 便捷构造：等价于 {@code new DefaultSyncEngine(new DefaultPreflighter(guard))}。 */
    public DefaultSyncEngine(MetadataGuard guard) {
        this(guard == null ? null : new DefaultPreflighter(guard));
    }

    /**
     * @param preflighter 注入的预检器（可带元数据库防护）；为 null 时引擎只用内部计划校验。
     *                    注意 {@code new DefaultSyncEngine(null)} 会产生编译期歧义，请用无参构造。
     */
    public DefaultSyncEngine(Preflighter preflighter) {
        this.preflighter = preflighter;
    }

    @Override
    public void cancel(long taskId) {
        AtomicBoolean flag = activeRuns.get(taskId);
        if (flag != null) {
            flag.set(true);
            log.info("任务 [{}] 收到协作式取消请求", taskId);
        } else {
            log.debug("任务 [{}] 当前没有运行中的实例，取消请求忽略", taskId);
        }
    }

    @Override
    public SyncRunResult run(SyncTaskConfig cfg, DataSource src, DataSource dst, RunMetricsListener listener) {
        RunState st = new RunState(cfg);
        AtomicBoolean cancelFlag = null;
        SyncRunResult result = st.result;
        try {
            if (cfg == null) {
                return fail(st, "任务配置为空，无法执行");
            }
            if (src == null || dst == null) {
                return fail(st, "源数据源或目标数据源未配置");
            }
            // ---- 预检：不通过则一行数据都不写 ----
            SyncPlan plan = SyncPlan.resolve(cfg, src, dst);
            st.plan = plan;
            if (preflighter != null) {
                mergeIssues(plan.issues(), safeCheck(cfg, src, dst));
            }
            result.setPreflightIssues(plan.issues());
            if (plan.hasError()) {
                return fail(st, "预检未通过：" + String.join("；", plan.errorMessages()));
            }
            st.converter = new DefaultValueConverter(TypeMappers.of(plan.sourceDbType(), plan.targetDbType()));

            // ---- 注册取消标志 ----
            if (cfg.getId() != null) {
                cancelFlag = activeRuns.computeIfAbsent(cfg.getId(), k -> new AtomicBoolean(false));
                st.cancelFlag = cancelFlag;
            }
            log.info("开始同步 {} | {}", plan, cfg.isDryRun() ? "试运行(不写目标)" : "正式执行");

            execute(plan, cfg, src, dst, listener, st);

            result.setSuccess(true);
            result.setStatus(SyncRunResult.STATUS_COMPLETED);
            result.setErrorMessage(null);
            log.info("同步完成 {} | 读取 {} 行 / 提交 {} 行 / 跳过 {} 行 | 水位 {} → {} | 耗时 {}ms",
                    cfg.getName(), st.readRows, st.writtenRows, st.skippedRows,
                    result.getStartCursor(), result.getEndCursor(), System.currentTimeMillis() - st.startedMillis);
        } catch (CancelledException e) {
            result.setSuccess(false);
            result.setStatus(SyncRunResult.STATUS_CANCELLED);
            result.setErrorMessage("任务被取消：" + e.getMessage());
            log.warn("同步被取消：{}", e.getMessage());
        } catch (Throwable t) {
            result.setSuccess(false);
            result.setStatus(SyncRunResult.STATUS_FAILED);
            result.setErrorMessage(message(t));
            log.error("同步失败: {}", message(t), t);
        } finally {
            if (cancelFlag != null && cfg != null && cfg.getId() != null) {
                activeRuns.remove(cfg.getId(), cancelFlag);
            }
            if (!st.result.isSuccess()) {
                // 失败/取消路径清理 SWAP 暂存表，并保证不推进水位
                dropStagingQuietly(st);
                st.result.setEndCursor(null);
            }
            finish(st);
        }
        return result;
    }

    // ==================================================================
    // 主流程
    // ==================================================================

    private void execute(SyncPlan plan, SyncTaskConfig cfg, DataSource srcDs, DataSource dstDs,
                         RunMetricsListener listener, RunState st) throws Exception {
        ErrorPolicy policy = cfg.getErrorPolicy();
        int pageSize = plan.pageSize();
        int batchSize = plan.batchSize();
        st.dstDs = dstDs;

        // 1) 全量策略：清理/准备目标表（预检已确认策略与权限；dryRun 不碰目标）
        if (plan.fullMode()) {
            prepareTarget(plan, cfg, dstDs, st);
        }
        // 写入 SQL 必须在所有模式下都构建（增量模式同样要写目标表！
        // 这里漏掉会导致 prepareStatement(null)，驱动直接报 "SQL String cannot be NULL"）
        buildWriteSql(plan, st);

        // 2) 水位（锚点在开始读取时取一次并固定）
        Watermark wm = PageQueryBuilder.watermarkRead(plan)
                ? watermarkOf(plan, cfg, srcDs)
                : Watermark.none();
        st.watermark = wm;
        st.result.setStartCursor(wm.startCursorText);

        // 3) 主循环：读一页 → 映射 → 攒够 batchSize 就提交一个 chunk
        //
        // 内存边界（Lead 决策：写进注释防止后人误以为这里能"预取优化"）：
        //   任意时刻「已读未写」的行数 ≤ pageSize + batchSize - 1
        //   因为每轮循环体必然把 buffer 排空到 < batchSize 才回到下一次 fetchPage，
        //   而一页最多 pageSize 行。这里**没有**多线程流水线、没有预取队列。
        //   因此"用源游标 − 目标已写行数"推断预取深度是错的：那只是同一进度的两个口径。
        if (PageQueryBuilder.watermarkRead(plan) && !plan.incrColumnIndexed()) {
            log.warn("增量字段[{}]没有索引：每页都会全表扫描，大表增量会明显变慢（建议立即建索引）",
                    plan.incrColumn());
        }
        KeysetPosition after = wm.initialPosition;
        List<PendingRow> buffer = new ArrayList<>(Math.max(batchSize, 16));
        while (true) {
            checkCancelled(st);
            long readStart = System.currentTimeMillis();
            List<Map<String, Object>> rows = fetchPage(plan, cfg, srcDs, after, wm, policy, st);
            st.fetchMillis += System.currentTimeMillis() - readStart;
            st.readMillis = st.fetchMillis;
            if (rows.isEmpty()) {
                break;
            }
            st.readRows += rows.size();

            long mapStart = System.currentTimeMillis();
            for (Map<String, Object> row : rows) {
                try {
                    buffer.add(mapRow(plan, row, st));
                } catch (FatalSyncException f) {
                    throw f;
                } catch (RuntimeException e) {
                    handleBadRow(st, "MAP", row, e, plan);
                }
            }
            st.mapMillis += System.currentTimeMillis() - mapStart;
            while (buffer.size() >= batchSize) {
                List<PendingRow> chunk = new ArrayList<>(buffer.subList(0, batchSize));
                buffer.subList(0, batchSize).clear();
                commitChunk(plan, cfg, dstDs, chunk, listener, st);
            }

            KeysetPosition next = positionAfter(plan, rows);
            if (rows.size() < pageSize) {
                break;
            }
            if (!next.isFirst() && next.values().equals(after.values())) {
                throw new FatalSyncException("键集分页游标未推进（排序键可能不唯一），已中止以避免死循环");
            }
            after = next;
        }
        if (!buffer.isEmpty()) {
            commitChunk(plan, cfg, dstDs, buffer, listener, st);
        }
        logPhaseBreakdown(st);

        // 4) SWAP：暂存表写完后原子换名
        if (st.swap && !cfg.isDryRun()) {
            finishSwap(plan, dstDs, st);
        }
    }

    // ==================================================================
    // 目标准备 / SWAP
    // ==================================================================

    private void prepareTarget(SyncPlan plan, SyncTaskConfig cfg, DataSource dstDs, RunState st) throws Exception {
        FullSyncStrategy strategy = plan.strategy();
        SqlDialect d = plan.targetDialect();
        TableRef target = plan.targetTable();
        st.writeTable = target;
        if (cfg.isDryRun()) {
            log.info("试运行：跳过目标清理（策略 {}）", strategy);
            return;
        }
        switch (strategy) {
            case DELETE -> {
                log.info("全量策略 DELETE：清空目标表 {}", target);
                executeDdlOrDml(dstDs, d.buildDeleteAll(target));
            }
            case SWAP -> {
                String stagingName = stagingName(cfg, target);
                st.staging = TableRef.of(target.schema(), stagingName);
                st.swap = true;
                st.writeTable = st.staging;
                log.info("全量策略 SWAP：暂存表 {} → 完成后与 {} 交换", st.staging, target);
                try {
                    executeDdlOrDml(dstDs, d.buildDropTable(st.staging));
                } catch (SQLException e) {
                    log.debug("清理历史暂存表失败（可忽略）: {}", e.getMessage());
                }
                executeDdlOrDml(dstDs, d.buildCreateTableLike(target, st.staging));
            }
            case TRUNCATE -> {
                log.info("全量策略 TRUNCATE：清空目标表 {}", target);
                executeDdlOrDml(dstDs, d.buildTruncate(target));
            }
            default -> throw new FatalSyncException("不支持的全量策略: " + strategy);
        }
    }

    /**
     * SWAP 收尾：目标 → 备份名，暂存表 → 目标名，删除备份。
     * 第二步失败时尽力把备份名改回目标名，避免"目标表消失"。
     */
    private void finishSwap(SyncPlan plan, DataSource dstDs, RunState st) throws SQLException {
        SqlDialect d = plan.targetDialect();
        TableRef target = plan.targetTable();
        TableRef staging = st.staging;
        TableRef backup = TableRef.of(target.schema(), backupName(staging.table()));
        executeDdlOrDml(dstDs, d.buildRenameTable(target, backup));
        try {
            executeDdlOrDml(dstDs, d.buildRenameTable(staging, target));
        } catch (SQLException e) {
            log.error("暂存表换名失败，正在回滚目标表名", e);
            try {
                executeDdlOrDml(dstDs, d.buildRenameTable(backup, target));
            } catch (SQLException rollbackFailure) {
                log.error("回滚目标表名也失败了，请人工处理：{} → {}", backup, target, rollbackFailure);
                throw new FatalSyncException("SWAP 换名失败且回滚失败，目标表当前名为 " + backup.name()
                        + "，请人工重命名回 " + target.name(), rollbackFailure);
            }
            throw e;
        }
        try {
            executeDdlOrDml(dstDs, d.buildDropTable(backup));
        } catch (SQLException e) {
            log.warn("删除 SWAP 备份表 {} 失败（不影响数据正确性）：{}", backup, e.getMessage());
        }
        st.swapDone = true;
    }

    /** 失败路径清理暂存表（尽力而为，绝不掩盖原始异常）。 */
    private void dropStagingQuietly(RunState st) {
        if (st.staging == null || st.swapDone || st.plan == null) {
            return;
        }
        try {
            executeDdlOrDml(st.dstDs, st.plan.targetDialect().buildDropTable(st.staging));
            log.info("已清理 SWAP 暂存表 {}", st.staging);
        } catch (Exception e) {
            log.warn("清理 SWAP 暂存表 {} 失败：{}", st.staging, e.getMessage());
        }
    }

    private static String stagingName(SyncTaskConfig cfg, TableRef target) {
        String task = cfg.getId() == null ? "x" : String.valueOf(cfg.getId());
        long suffix = Math.abs(System.nanoTime() % 100000L);
        String name = "ds_swap_" + task + "_" + suffix;
        if (name.length() > 60) {
            name = name.substring(0, 60);
        }
        return name;
    }

    private static String backupName(String stagingTable) {
        return stagingTable + "_old";
    }

    // ==================================================================
    // 读取
    // ==================================================================

    private Watermark watermarkOf(SyncPlan plan, SyncTaskConfig cfg, DataSource srcDs) throws SQLException {
        // 只借连接，不关池
        try (Connection c = srcDs.getConnection()) {
            return Watermark.forIncremental(plan, cfg, c);
        }
    }

    private List<Map<String, Object>> fetchPage(SyncPlan plan, SyncTaskConfig cfg, DataSource srcDs,
                                                KeysetPosition after, Watermark wm, ErrorPolicy policy,
                                                RunState st) throws Exception {
        PageQuery query = PageQueryBuilder.build(plan, after, wm, plan.pageSize());
        int attempt = 0;
        while (true) {
            checkCancelled(st);
            try {
                return queryPage(plan, cfg, srcDs, query, st);
            } catch (SQLException e) {
                if (attempt < policy.getMaxRetries() && RetryPolicy.isRetryable(e)) {
                    attempt++;
                    long backoff = RetryPolicy.backoffMillis(policy, attempt);
                    log.warn("读取源数据失败，第 {}/{} 次重试（退避 {}ms）：{}",
                            attempt, policy.getMaxRetries(), backoff, e.getMessage());
                    sleepInterruptibly(backoff, st);
                    continue;
                }
                throw new FatalSyncException("读取源数据失败（已重试 " + attempt + " 次）：" + message(e), e);
            }
        }
    }

    private List<Map<String, Object>> queryPage(SyncPlan plan, SyncTaskConfig cfg, DataSource srcDs,
                                                PageQuery query, RunState st) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>(plan.pageSize());
        // 读侧拆成四段计时：借连接 / 建语句+绑参 / 执行+取行 / 归还连接。
        // 为什么必须拆：某次真机验收里 98.7% 的时间落在"读"，但服务端查询只要 2.84ms，
        // 数据库在等待期间完全空闲——"借连接"和"归还连接"都在读窗口内、却都不产生任何 SQL，
        // 只有把它们单独量出来才能定位。
        long t0 = System.currentTimeMillis();
        Connection c = srcDs.getConnection();
        st.poolWaitMillis += System.currentTimeMillis() - t0;
        try {
            long t1 = System.currentTimeMillis();
            PreparedStatement ps = c.prepareStatement(query.sql,
                    ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            // MySQL 流式三件套：MIN_VALUE + 只进 + 只读，缺一个都会把整表读进内存
            ps.setFetchSize(plan.sourceDialect().streamFetchSize());
            ps.setFetchDirection(ResultSet.FETCH_FORWARD);
            Long timeout = cfg.getQueryTimeoutSeconds();
            if (timeout != null && timeout > 0) {
                ps.setQueryTimeout(timeout.intValue());
            }
            for (int i = 0; i < query.params.size(); i++) {
                Object v = query.params.get(i);
                if (v == null) {
                    ps.setNull(i + 1, Types.NULL);
                } else {
                    ps.setObject(i + 1, v);
                }
            }
            st.prepareMillis += System.currentTimeMillis() - t1;

            long execStart = System.currentTimeMillis();
            try (ResultSet rs = ps.executeQuery()) {
                ResultSetMetaData md = rs.getMetaData();
                int n = md.getColumnCount();
                String[] labels = new String[n];
                for (int i = 0; i < n; i++) {
                    labels[i] = md.getColumnLabel(i + 1);
                }
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>(n * 2);
                    for (int i = 0; i < n; i++) {
                        Object v = rs.getObject(i + 1);
                        row.put(labels[i], v);
                        // 列名大小写不敏感访问（MySQL 元数据大小写随建表语句，配置里可能是另一种写法）
                        row.put(labels[i].toLowerCase(Locale.ROOT), v);
                    }
                    out.add(row);
                }
            }
            st.queryExecuteMillis += System.currentTimeMillis() - execStart;
            ps.close();
        } finally {
            long closeStart = System.currentTimeMillis();
            c.close();
            st.connCloseMillis += System.currentTimeMillis() - closeStart;
        }
        return out;
    }

    /** 从一页数据的最后一行构造下一个键集游标位置。 */
    private KeysetPosition positionAfter(SyncPlan plan, List<Map<String, Object>> rows) {
        List<String> orderKeys = PageQueryBuilder.watermarkRead(plan)
                ? PageQueryBuilder.watermarkOrderKeys(plan)
                : plan.fullOrderKeyColumns();
        Map<String, Object> last = rows.get(rows.size() - 1);
        List<Object> values = new ArrayList<>(orderKeys.size());
        for (String key : orderKeys) {
            Object v = last.get(key.toLowerCase(Locale.ROOT));
            if (v == null) {
                throw new FatalSyncException("排序键[" + key + "]的值为 NULL，键集分页无法继续"
                        + "（唯一键允许 NULL 时不能作为分页排序键）");
            }
            values.add(v);
        }
        return KeysetPosition.of(orderKeys, values);
    }

    // ==================================================================
    // 映射与写入
    // ==================================================================

    private PendingRow mapRow(SyncPlan plan, Map<String, Object> row, RunState st) {
        List<SyncPlan.ColumnMapping> cols = plan.columns();
        Object[] values = new Object[cols.size()];
        for (int i = 0; i < cols.size(); i++) {
            SyncPlan.ColumnMapping cm = cols.get(i);
            Object raw;
            ColumnMeta srcMeta;
            if (cm.sourceColumn() == null) {
                raw = cm.defaultValue();
                srcMeta = CONSTANT_META;
            } else {
                String lookup = cm.sourceColumn().toLowerCase(Locale.ROOT);
                if (!row.containsKey(lookup)) {
                    throw new FatalSyncException("查询结果缺少列[" + cm.sourceColumn()
                            + "]，这是引擎内部错误（SELECT 列与映射不一致）");
                }
                raw = row.get(lookup);
                srcMeta = cm.sourceMeta();
            }
            values[i] = st.converter.convert(raw, srcMeta, cm.targetMeta());
        }
        // 增量列必须非空，否则水位会被写成 NULL 或悄悄跳过这一行
        if (plan.incrColumn() != null) {
            Object incr = row.get(plan.incrColumn().toLowerCase(Locale.ROOT));
            if (incr == null) {
                throw new ValueConversionException("增量列[" + plan.incrColumn()
                        + "]的值为 NULL，无法用于水位推进");
            }
        }
        return new PendingRow(values, row);
    }

    private void commitChunk(SyncPlan plan, SyncTaskConfig cfg, DataSource dstDs, List<PendingRow> rows,
                             RunMetricsListener listener, RunState st) throws Exception {
        if (rows.isEmpty()) {
            return;
        }
        st.chunkCount++;
        long chunkStart = System.currentTimeMillis();
        long between = st.lastChunkEndAt == 0L ? 0L : chunkStart - st.lastChunkEndAt;
        ErrorPolicy policy = cfg.getErrorPolicy();
        if (cfg.isDryRun()) {
            // 试运行：跑完整的读取/映射/转换链路，但不写目标、不推进水位
            st.lastCommittedRow = rows.get(rows.size() - 1);
            notifyChunk(listener, st);
            st.lastChunkEndAt = System.currentTimeMillis();
            return;
        }
        long writeBefore = st.bindExecuteMillis + st.commitMillis;
        int attempt = 0;
        SQLException lastError = null;
        while (true) {
            checkCancelled(st);
            try {
                long t0 = System.currentTimeMillis();
                writeChunk(plan, dstDs, rows, st);
                st.writeMillis += System.currentTimeMillis() - t0;
                st.writtenRows += rows.size();
                st.lastCommittedRow = rows.get(rows.size() - 1);
                break;
            } catch (SQLException e) {
                lastError = e;
                if (attempt < policy.getMaxRetries() && RetryPolicy.isRetryable(e)) {
                    attempt++;
                    long backoff = RetryPolicy.backoffMillis(policy, attempt);
                    log.warn("chunk 写入失败，第 {}/{} 次重试（退避 {}ms）：{}",
                            attempt, policy.getMaxRetries(), backoff, e.getMessage());
                    sleepInterruptibly(backoff, st);
                    continue;
                }
                break;
            }
        }
        if (lastError != null) {
            if (!policy.isSkipBadRows()) {
                recordError(st, "WRITE", null, message(lastError), null, RetryPolicy.isRetryable(lastError));
                throw new FatalSyncException("写入 chunk 失败（" + rows.size() + " 行，重试 " + attempt
                        + " 次后放弃）：" + message(lastError), lastError);
            }
            isolateBadRows(plan, policy, dstDs, rows, st);
        }
        st.writeMillis = st.bindExecuteMillis + st.commitMillis;
        long notifyStart = System.currentTimeMillis();
        notifyChunk(listener, st);
        st.notifyMillis += System.currentTimeMillis() - notifyStart;
        long chunkEnd = System.currentTimeMillis();
        st.lastChunkEndAt = chunkEnd;
        if (CHUNK_TRACE && log.isInfoEnabled()) {
            log.info("chunk={} rows={} read={}ms map={}ms write={}ms(execute={}/commit={}) notify={}ms"
                            + " between={}ms total={}ms",
                    st.chunkCount, rows.size(),
                    st.fetchMillis - st.lastLoggedFetch, st.mapMillis - st.lastLoggedMap,
                    (st.bindExecuteMillis + st.commitMillis) - writeBefore,
                    st.bindExecuteMillis - st.lastLoggedExec, st.commitMillis - st.lastLoggedCommit,
                    st.notifyMillis - st.lastLoggedNotify, between, chunkEnd - chunkStart);
            st.lastLoggedFetch = st.fetchMillis;
            st.lastLoggedMap = st.mapMillis;
            st.lastLoggedExec = st.bindExecuteMillis;
            st.lastLoggedCommit = st.commitMillis;
            st.lastLoggedNotify = st.notifyMillis;
        }
    }

    /**
     * 写出一个 chunk（单事务：提交成功才返回）。
     *
     * <p>写性能的关键在这里：{@code addBatch/executeBatch} 的实际效率完全取决于连接参数
     * {@code rewriteBatchedStatements}——不开时 1000 行 upsert 会退化成 1000 次独立往返
     * （本机实测 240ms vs 30ms，8 倍）。core 不能假设平台侧 URL 一定开了这个参数，
     * 所以对支持的方言（MySQL）直接生成**多行 VALUES 单语句**；行数超过占位符上限时
     * 在**同一个事务内**拆成多条语句，保证 chunk 仍然是原子的。
     */
    private void writeChunk(SyncPlan plan, DataSource dstDs, List<PendingRow> rows, RunState st)
            throws SQLException {
        long poolStart = System.currentTimeMillis();
        try (Connection c = dstDs.getConnection()) {
            st.poolWaitMillis += System.currentTimeMillis() - poolStart;
            boolean originalAutoCommit = c.getAutoCommit();
            c.setAutoCommit(false);
            try {
                int maxRows = st.maxRowsPerStatement;
                long execStart = System.currentTimeMillis();
                if (maxRows > 1) {
                    for (int from = 0; from < rows.size(); from += maxRows) {
                        List<PendingRow> sub = rows.subList(from, Math.min(rows.size(), from + maxRows));
                        try (PreparedStatement ps = c.prepareStatement(st.writeSqlFor(sub.size()))) {
                            int param = 1;
                            for (PendingRow r : sub) {
                                param = bindRow(ps, plan, r, param);
                            }
                            ps.executeUpdate();
                        }
                    }
                } else {
                    try (PreparedStatement ps = c.prepareStatement(st.writeSql)) {
                        for (PendingRow r : rows) {
                            bindRow(ps, plan, r, 1);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                st.bindExecuteMillis += System.currentTimeMillis() - execStart;
                long commitStart = System.currentTimeMillis();
                c.commit();
                st.commitMillis += System.currentTimeMillis() - commitStart;
            } catch (SQLException e) {
                try {
                    c.rollback();
                } catch (SQLException rollbackFailure) {
                    log.warn("chunk 回滚失败：{}", rollbackFailure.getMessage());
                }
                throw e;
            } finally {
                try {
                    c.setAutoCommit(originalAutoCommit);
                } catch (SQLException ignore) {
                    // 归还连接池时由池负责复位
                }
            }
        }
    }

    /**
     * 坏行隔离：整块失败后逐行重做，能写的照常提交，写不进的落 sync_error。
     *
     * @return 成功提交的行数
     */
    private int isolateBadRows(SyncPlan plan, ErrorPolicy policy, DataSource dstDs,
                               List<PendingRow> rows, RunState st) throws Exception {
        int ok = 0;
        for (PendingRow r : rows) {
            checkCancelled(st);
            try {
                long t0 = System.currentTimeMillis();
                writeChunk(plan, dstDs, List.of(r), st);
                st.writeMillis += System.currentTimeMillis() - t0;
                ok++;
                st.writtenRows++;
                st.lastCommittedRow = r;
            } catch (SQLException e) {
                recordError(st, "WRITE", rowKeyOf(plan, r), message(e), r.sourceRow, false);
                st.skippedRows++;
                log.warn("跳过坏行（WRITE 阶段）：{}", message(e));
                if (st.skippedRows > policy.getMaxSkipRows()) {
                    throw new FatalSyncException("跳过的坏行数(" + st.skippedRows + ")超过上限 "
                            + policy.getMaxSkipRows() + "，任务终止（水位不推进）");
                }
            }
        }
        return ok;
    }

    /**
     * 按目标列 jdbcType 落值：null 必须 {@code setNull(jdbcType)}，绝不用 {@code setObject(i, null)}。
     *
     * @param startParam 本行第一个参数的下标（多行 VALUES 时逐行递增）
     * @return 下一个可用参数下标
     */
    private int bindRow(PreparedStatement ps, SyncPlan plan, PendingRow row, int startParam)
            throws SQLException {
        List<SyncPlan.ColumnMapping> cols = plan.columns();
        int param = startParam;
        for (int i = 0; i < row.values.length; i++) {
            Object v = row.values[i];
            if (v == null) {
                int jdbcType = cols.get(i).targetMeta().jdbcType();
                ps.setNull(param, jdbcType == Types.OTHER ? Types.NULL : jdbcType);
            } else {
                ps.setObject(param, v);
            }
            param++;
        }
        return param;
    }

    private void handleBadRow(RunState st, String phase, Map<String, Object> row, Throwable e, SyncPlan plan) {
        ErrorPolicy policy = st.cfg == null ? new ErrorPolicy() : st.cfg.getErrorPolicy();
        String msg = message(e);
        boolean retryable = e instanceof SQLException && RetryPolicy.isRetryable(e);
        recordError(st, phase, rowKeyOf(plan, row), msg, row, retryable);
        if (!policy.isSkipBadRows()) {
            throw new FatalSyncException(phase + " 阶段处理失败（skipBadRows=false，快速失败）：" + msg, e);
        }
        st.skippedRows++;
        log.warn("跳过坏行（{} 阶段）：{}", phase, msg);
        if (st.skippedRows > policy.getMaxSkipRows()) {
            throw new FatalSyncException("跳过的坏行数(" + st.skippedRows + ")超过上限 "
                    + policy.getMaxSkipRows() + "，任务终止（水位不推进）");
        }
    }

    private void recordError(RunState st, String phase, String rowKey, String msg,
                             Map<String, Object> row, boolean retryable) {
        ErrorPolicy policy = st.cfg == null ? new ErrorPolicy() : st.cfg.getErrorPolicy();
        if (st.result.getErrors().size() >= policy.getMaxErrorsRecorded()) {
            return;
        }
        SyncError error = new SyncError(phase, rowKey, truncate(msg), JsonLite.toJson(row), retryable);
        st.result.getErrors().add(error);
    }

    private String rowKeyOf(SyncPlan plan, Map<String, Object> row) {
        if (plan == null || row == null) {
            return null;
        }
        List<String> keys = plan.upsertKeyColumns().isEmpty()
                ? (plan.fullOrderKeyColumns().isEmpty() ? plan.selectColumns() : plan.fullOrderKeyColumns())
                : plan.upsertKeyColumns();
        StringBuilder sb = new StringBuilder();
        for (String k : keys) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(k).append('=').append(ValueText.format(row.get(k.toLowerCase(Locale.ROOT))));
        }
        return truncate(sb.toString());
    }

    private String rowKeyOf(SyncPlan plan, PendingRow row) {
        return rowKeyOf(plan, row.sourceRow);
    }

    // ==================================================================
    // 收尾与工具
    // ==================================================================

    /** 成功收尾：写入水位（endCursor）。只有走到这里的路径才允许推进。 */
    private void finish(RunState st) {
        SyncRunResult result = st.result;
        result.setReadRows(st.readRows);
        result.setWrittenRows(st.writtenRows);
        result.setSkippedRows(st.skippedRows);
        result.setReadMillis(st.readMillis);
        result.setWriteMillis(st.writeMillis);
        result.setTotalMillis(System.currentTimeMillis() - st.startedMillis);
        if (st.lastCommittedRow != null && st.plan != null) {
            result.setLastCommittedKey(positionText(st.plan, st.lastCommittedRow.sourceRow));
            String cursor = cursorTextOf(st.plan, st.lastCommittedRow.sourceRow);
            if (result.isSuccess() && cursor != null && !st.dryRun) {
                result.setEndCursor(cursor);
            }
        }
    }

    /** 最后提交行的增量列值（复合游标时带上 tie-breaker 分量）。 */
    private String cursorTextOf(SyncPlan plan, Map<String, Object> row) {
        if (plan.incrColumn() == null || row == null) {
            return null;
        }
        Object raw = row.get(plan.incrColumn().toLowerCase(Locale.ROOT));
        if (raw == null) {
            return null;
        }
        ColumnMeta incrMeta = plan.sourceMeta() == null ? null : plan.sourceMeta().find(plan.incrColumn());
        List<String> tieColumns;
        if (PageQueryBuilder.watermarkRead(plan)) {
            tieColumns = plan.tieBreakKeyColumns();
        } else {
            tieColumns = new ArrayList<>();
            for (String k : plan.fullOrderKeyColumns()) {
                if (!k.equalsIgnoreCase(plan.incrColumn())) {
                    tieColumns.add(k);
                }
            }
        }
        List<Object> tieValues = new ArrayList<>();
        for (String k : tieColumns) {
            tieValues.add(row.get(k.toLowerCase(Locale.ROOT)));
        }
        return CursorCodec.encode(raw, incrMeta, tieColumns, tieValues);
    }

    private String positionText(SyncPlan plan, Map<String, Object> row) {
        List<String> orderKeys = PageQueryBuilder.watermarkRead(plan)
                ? PageQueryBuilder.watermarkOrderKeys(plan)
                : plan.fullOrderKeyColumns();
        if (orderKeys.isEmpty()) {
            return null;
        }
        if (orderKeys.size() == 1) {
            return ValueText.format(row.get(orderKeys.get(0).toLowerCase(Locale.ROOT)));
        }
        List<String> parts = new ArrayList<>(orderKeys.size());
        for (String k : orderKeys) {
            parts.add(ValueText.format(row.get(k.toLowerCase(Locale.ROOT))));
        }
        return JsonLite.toArray(parts);
    }

    /**
     * 运行结束时的分段耗时汇总（INFO，生产也打印）。
     *
     * <p>这条日志回答的是运维/QA 最常问的问题：「慢在数据库还是慢在应用？」。
     * 如果 {@code notify} 很大，说明瓶颈在**回调方**（例如 WebSocket 同步推送被背压），
     * 而不是同步引擎或数据库。
     */
    private void logPhaseBreakdown(RunState st) {
        long accounted = st.fetchMillis + st.mapMillis + st.bindExecuteMillis + st.commitMillis
                + st.notifyMillis;
        long total = System.currentTimeMillis() - st.startedMillis;
        long chunks = Math.max(1L, st.chunkCount);
        // 读窗口内的四段（借连接/建语句/取行/归还）之和可能小于"读"，
        // 差额是结果集遍历、计划构造等杂项——留一个"读其它"让它显形
        long readDetail = st.poolWaitMillis + st.prepareMillis + st.queryExecuteMillis + st.connCloseMillis;
        log.info("分段耗时汇总：chunk={} 共 {}ms（{}ms/chunk）| 读 {}ms[借连接 {}ms 建语句 {}ms 取行 {}ms 归还 {}ms 其它 {}ms] | "
                        + "映射 {}ms | 写 {}ms(execute={}ms commit={}ms) | 回调 {}ms | 其它 {}ms",
                st.chunkCount, total, total / chunks,
                st.fetchMillis, st.poolWaitMillis, st.prepareMillis, st.queryExecuteMillis,
                st.connCloseMillis, Math.max(0L, st.fetchMillis - readDetail),
                st.mapMillis,
                st.bindExecuteMillis + st.commitMillis, st.bindExecuteMillis, st.commitMillis,
                st.notifyMillis, Math.max(0L, total - accounted));
    }

    private void notifyChunk(RunMetricsListener listener, RunState st) {        if (listener == null) {
            return;
        }
        ChunkProgress progress = new ChunkProgress(st.readRows, st.writtenRows, st.skippedRows,
                st.lastCommittedRow == null ? null : positionText(st.plan, st.lastCommittedRow.sourceRow),
                st.lastCommittedRow == null ? null : cursorTextOf(st.plan, st.lastCommittedRow.sourceRow),
                System.currentTimeMillis() - st.startedMillis);
        try {
            listener.onChunk(progress);
        } catch (Throwable t) {
            // 回调是运维通道：它炸了不能影响已经提交的数据和任务状态
            log.warn("onChunk 回调异常（已忽略，不影响已提交数据）：{}", message(t));
        }
    }

    private void buildWriteSql(SyncPlan plan, RunState st) {        SqlDialect d = plan.targetDialect();
        // 必须回填字段：多行 SQL 是按行数"按需构建"的（writeSqlFor），
        // 增量模式不经过 prepareTarget，字段留 null 会让构建时报"表引用不能为空"
        st.writeTable = st.writeTable == null ? plan.targetTable() : st.writeTable;
        List<String> columns = plan.targetColumns();
        st.writeDialect = d;
        st.writeColumns = columns;
        st.writeKeyColumns = plan.upsertKeyColumns();
        st.writeUseUpsert = plan.useUpsert();
        st.maxRowsPerStatement = d.maxRowsPerStatement(columns.size());
        st.writeSql = st.writeSqlFor(1);
        log.debug("写入 SQL（每条最多 {} 行）: {}", st.maxRowsPerStatement, st.writeSql);
    }

    private void executeDdlOrDml(DataSource ds, String sql) throws SQLException {
        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute(sql);
        }
    }

    private List<PreflightIssue> safeCheck(SyncTaskConfig cfg, DataSource src, DataSource dst) {
        try {
            List<PreflightIssue> issues = preflighter.check(cfg, src, dst);
            return issues == null ? List.of() : issues;
        } catch (Throwable t) {
            return List.of(PreflightIssue.error("PREFLIGHT_FAILED",
                    "预检执行异常：" + message(t), "检查数据源连通性与账号权限后重试"));
        }
    }

    private static void mergeIssues(List<PreflightIssue> target, List<PreflightIssue> extra) {
        if (extra == null || extra.isEmpty()) {
            return;
        }
        for (PreflightIssue e : extra) {
            if (e == null) {
                continue;
            }
            boolean exists = false;
            for (PreflightIssue t : target) {
                if (t != null && java.util.Objects.equals(t.getCode(), e.getCode())
                        && java.util.Objects.equals(t.getMessage(), e.getMessage())) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                target.add(e);
            }
        }
    }

    private void checkCancelled(RunState st) {
        if (st.cancelFlag != null && st.cancelFlag.get()) {
            throw new CancelledException("已收到取消请求，任务在提交边界处停止（水位不推进）");
        }
    }

    private void sleepInterruptibly(long millis, RunState st) throws InterruptedException {
        long remaining = millis;
        while (remaining > 0) {
            checkCancelled(st);
            long step = Math.min(remaining, 200L);
            Thread.sleep(step);
            remaining -= step;
        }
    }

    private SyncRunResult fail(RunState st, String message) {
        st.result.setSuccess(false);
        st.result.setStatus(SyncRunResult.STATUS_FAILED);
        st.result.setErrorMessage(message);
        return st.result;
    }

    private static String truncate(String s) {
        if (s == null || s.length() <= MAX_MESSAGE_LENGTH) {
            return s;
        }
        return s.substring(0, MAX_MESSAGE_LENGTH);
    }

    /** 异常消息脱敏 + 截断：日志与接口响应里绝不能出现明文口令。 */
    static String message(Throwable t) {
        if (t == null) {
            return "未知错误";
        }
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = t.getClass().getSimpleName();
        }
        msg = msg.replaceAll("(?i)(password|pwd|passwd)\\s*[=:]\\s*\\S+", "$1=***");
        return truncate(msg);
    }

    // ==================================================================
    // 内部类型
    // ==================================================================

    /** 一行待写数据：目标列的值数组 + 源行（出错时用于落 sync_error）。 */
    private static final class PendingRow {
        final Object[] values;
        final Map<String, Object> sourceRow;

        PendingRow(Object[] values, Map<String, Object> sourceRow) {
            this.values = values;
            this.sourceRow = sourceRow;
        }
    }

    /** 单次执行的局部状态（全部字段都是 run() 的局部变量，因此引擎天然线程安全）。 */
    private static final class RunState {
        final SyncTaskConfig cfg;
        final SyncRunResult result = new SyncRunResult();
        final long startedMillis = System.currentTimeMillis();
        final boolean dryRun;
        SyncPlan plan;
        DefaultValueConverter converter = new DefaultValueConverter();
        Watermark watermark;
        AtomicBoolean cancelFlag;
        DataSource dstDs;
        TableRef writeTable;
        String writeSql;
        SqlDialect writeDialect;
        List<String> writeColumns = List.of();
        List<String> writeKeyColumns = List.of();
        boolean writeUseUpsert;
        /** 单条语句最多写多少行（1 = 逐行 addBatch）。 */
        int maxRowsPerStatement = 1;
        /** 按行数缓存的写入 SQL（多行 VALUES 的语句文本随行数变化）。 */
        final Map<Integer, String> writeSqlByRows = new java.util.HashMap<>();
        TableRef staging;
        boolean swap;
        boolean swapDone;
        PendingRow lastCommittedRow;
        long readRows;
        long writtenRows;
        long skippedRows;
        long readMillis;
        long writeMillis;
        // ---- 分段耗时（排查性能用，也用于运行结束时的 INFO 汇总） ----
        /** 分页读取（含结果集遍历）。 */
        long fetchMillis;
        /** 行映射与值转换。 */
        long mapMillis;
        /** 写目标：绑定 + executeUpdate/executeBatch。 */
        long bindExecuteMillis;
        /** 写目标：commit。 */
        long commitMillis;
        /** 进度回调（回调是外部代码，慢在这里一眼可见）。 */
        long notifyMillis;
        /** 从连接池借连接的等待时间（读 + 写合计）。 */
        long poolWaitMillis;
        /** 读侧：建语句 + 设置流式参数 + 绑参。 */
        long prepareMillis;
        /** 读侧：归还连接到池（Hikari 复位/驱逐等）。 */
        long connCloseMillis;
        /** 服务端执行 + 取回行的时间（排除借连接）。 */
        long queryExecuteMillis;
        long chunkCount;
        long lastChunkEndAt;
        long lastLoggedFetch;
        long lastLoggedMap;
        long lastLoggedExec;
        long lastLoggedCommit;
        long lastLoggedNotify;

        RunState(SyncTaskConfig cfg) {
            this.cfg = cfg;
            this.dryRun = cfg != null && cfg.isDryRun();
            this.result.setStatus(SyncRunResult.STATUS_FAILED);
        }

        /** 取（并缓存）写 {@code rowCount} 行的语句。 */
        String writeSqlFor(int rowCount) {
            return writeSqlByRows.computeIfAbsent(rowCount, n -> writeUseUpsert
                    ? writeDialect.buildUpsertMultiRow(writeTable, writeColumns, writeKeyColumns, n)
                    : writeDialect.buildInsertMultiRow(writeTable, writeColumns, n));
        }
    }

    /** 致命错误：立即停止，水位不推进（不做坏行跳过）。 */
    private static final class FatalSyncException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        FatalSyncException(String message) {
            super(message);
        }

        FatalSyncException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 协作式取消。 */
    private static final class CancelledException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        CancelledException(String message) {
            super(message);
        }
    }
}
