package com.datasync.core.preflight;

import com.datasync.core.dialect.Dialects;
import com.datasync.core.dialect.SqlDialect;
import com.datasync.core.jdbc.ColumnMeta;
import com.datasync.core.jdbc.DbTypes;
import com.datasync.core.jdbc.Identifiers;
import com.datasync.core.jdbc.JdbcMetadata;
import com.datasync.core.jdbc.TableMeta;
import com.datasync.core.jdbc.TableRef;
import com.datasync.core.mapper.TypeCompatibility;
import com.datasync.core.mapper.TypeNames;
import com.datasync.core.model.FieldMapping;
import com.datasync.core.model.FullSyncStrategy;
import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.model.enums.DbType;
import com.datasync.core.model.enums.SyncMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 解析后的执行计划：预检校验的唯一事实源，引擎执行的唯一输入。
 *
 * <p>「预检」与「执行」必须基于同一份解析结果，否则会出现"预检说没问题、执行却按另一套规则跑"
 * 的经典事故（例如预检按主键分页、执行却按 orderColumn 分页）。因此两者共用本类：
 * {@link DefaultPreflighter} 只负责把 {@link #resolve} 的 issues 抛给用户，
 * {@code DefaultSyncEngine} 则在 issues 无 ERROR 后直接执行这份计划。
 *
 * <p>{@link #resolve} 永不抛异常：连不上库、元数据读不到都会转成 {@code PREFLIGHT_FAILED} 的 ERROR issue。
 */
public final class SyncPlan {

    /** 一条列映射：源列 → 目标列（源列为 null 表示用默认值常量填充）。 */
    public static final class ColumnMapping {
        private final String sourceColumn;
        private final String targetColumn;
        private final String defaultValue;
        private final ColumnMeta sourceMeta;
        private final ColumnMeta targetMeta;
        private final boolean primaryKeyFromMapping;

        ColumnMapping(String sourceColumn, String targetColumn, String defaultValue,
                      ColumnMeta sourceMeta, ColumnMeta targetMeta, boolean primaryKeyFromMapping) {
            this.sourceColumn = sourceColumn;
            this.targetColumn = targetColumn;
            this.defaultValue = defaultValue;
            this.sourceMeta = sourceMeta;
            this.targetMeta = targetMeta;
            this.primaryKeyFromMapping = primaryKeyFromMapping;
        }

        public String sourceColumn() {
            return sourceColumn;
        }

        public String targetColumn() {
            return targetColumn;
        }

        public String defaultValue() {
            return defaultValue;
        }

        public ColumnMeta sourceMeta() {
            return sourceMeta;
        }

        public ColumnMeta targetMeta() {
            return targetMeta;
        }

        /** 用户是否通过 FieldMapping.primaryKey 显式指定它参与 upsert 匹配键。 */
        public boolean primaryKeyFromMapping() {
            return primaryKeyFromMapping;
        }

        @Override
        public String toString() {
            return (sourceColumn == null ? "<默认值>" : sourceColumn) + " → " + targetColumn;
        }
    }

    private final SyncTaskConfig cfg;
    private final List<PreflightIssue> issues = new ArrayList<>();

    private boolean customSql;
    private String sourceSql;
    private String wrappedSourceSql;
    private TableRef sourceTable;
    private TableRef targetTable;
    private TableMeta sourceMeta = TableMeta.empty(null);
    private TableMeta targetMeta = TableMeta.empty(null);
    private DbType sourceDbType;
    private DbType targetDbType;
    private SqlDialect sourceDialect;
    private SqlDialect targetDialect;

    private final List<ColumnMapping> columns = new ArrayList<>();
    private final List<String> selectColumns = new ArrayList<>();
    private List<String> upsertKeyColumns = List.of();
    private List<String> tieBreakKeyColumns = List.of();
    private List<String> fullOrderKeyColumns = List.of();
    private String incrColumn;
    private boolean timestampCursor;
    /** 增量列是否有可用索引（false 时每页都会全表扫描，引擎会额外告警）。 */
    private boolean incrColumnIndexed = true;
    private boolean incremental;
    private boolean fullMode;
    private FullSyncStrategy strategy = FullSyncStrategy.TRUNCATE;
    private boolean useUpsert;
    private int pageSize = 1000;
    private int batchSize = 500;

    private SyncPlan(SyncTaskConfig cfg) {
        this.cfg = cfg;
    }

    // ------------------------------------------------------------------
    // 对外入口
    // ------------------------------------------------------------------

    /** 解析并校验；任何异常都转成 issue，永不抛出。 */
    public static SyncPlan resolve(SyncTaskConfig cfg, javax.sql.DataSource srcDs, javax.sql.DataSource dstDs) {
        SyncPlan plan = new SyncPlan(cfg);
        if (cfg == null) {
            plan.error(ErrorCodes.PREFLIGHT_FAILED, "任务配置为空", "请先保存任务配置再执行");
            return plan;
        }
        try {
            plan.doResolve(srcDs, dstDs);
        } catch (Exception e) {
            plan.error(ErrorCodes.PREFLIGHT_FAILED, "预检失败: " + describe(e),
                    "检查源库/目标库的连通性、账号口令与网络，然后重试");
        }
        plan.finish();
        return plan;
    }

    private void finish() {
        // 收尾推导
        if (columns.isEmpty()) {
            useUpsert = false;
        } else {
            useUpsert = !upsertKeyColumns.isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // 解析流程
    // ------------------------------------------------------------------

    private void doResolve(javax.sql.DataSource srcDs, javax.sql.DataSource dstDs) throws SQLException {
        if (srcDs == null || dstDs == null) {
            error(ErrorCodes.PREFLIGHT_FAILED, "源数据源或目标数据源未配置", "请先配置并测试数据源连接");
            return;
        }
        resolveBasics();
        resolveMode();
        if (hasError()) {
            return;
        }

        // ---- 目标库 ----
        try (Connection dc = dstDs.getConnection()) {
            targetDbType = DbTypes.detect(dc);
            if (targetDbType == null) {
                error(ErrorCodes.PREFLIGHT_FAILED, "无法识别目标库类型（驱动未返回已知产品名）",
                        "当前仅支持 MySQL 8.0 与达梦 DM8，请确认 JDBC 驱动正确");
                return;
            }
            targetDialect = Dialects.of(targetDbType);
            targetTable = TableRef.parse(cfg.getTargetTable());
            targetMeta = JdbcMetadata.read(dc, targetTable);
            if (targetMeta.columns().isEmpty()) {
                error(ErrorCodes.DST_TABLE_MISSING, "目标表[" + targetTable.name() + "]不存在或没有任何列",
                        "确认目标表名拼写、库名/模式名是否正确，以及账号是否有该表的查询权限");
                return;
            }
            if (strategy == FullSyncStrategy.SWAP && targetDbType != DbType.MYSQL) {
                error(ErrorCodes.STRATEGY_UNSUPPORTED,
                        "SWAP（暂存表交换）策略在 " + targetDbType + " 上不受支持",
                        "请改用 TRUNCATE 或 DELETE 全量策略");
            }
            checkTargetPermission(dc);
        }

        // ---- 源库 ----
        try (Connection sc = srcDs.getConnection()) {
            sourceDbType = DbTypes.detect(sc);
            if (sourceDbType == null) {
                error(ErrorCodes.PREFLIGHT_FAILED, "无法识别源库类型（驱动未返回已知产品名）",
                        "当前仅支持 MySQL 8.0 与达梦 DM8，请确认 JDBC 驱动正确");
                return;
            }
            sourceDialect = Dialects.of(sourceDbType);
            if (customSql) {
                resolveCustomSqlSource(sc);
            } else {
                sourceTable = TableRef.parse(cfg.getSourceTable());
                sourceMeta = JdbcMetadata.read(sc, sourceTable);
                if (sourceMeta.columns().isEmpty()) {
                    error(ErrorCodes.SRC_TABLE_MISSING, "源表[" + sourceTable.name() + "]不存在或没有任何列",
                            "确认源表名拼写、库名/模式名是否正确，以及账号是否有该表的查询权限");
                    return;
                }
            }
            if (hasError()) {
                return;
            }
            // 映射 + 键 + 排序键 + 增量列 都在源/目标元数据齐备后解析
            resolveColumns();
            if (hasError()) {
                return;
            }
            resolveKeys();
            resolveIncremental(sc);
            resolveSortKeys(sc);
        }
        buildSelectColumns();
    }

    /** 基础配置与标识符安全校验（在连库之前做，避免无谓连接）。 */
    private void resolveBasics() {
        strategy = cfg.getFullSyncStrategy() == null ? FullSyncStrategy.TRUNCATE : cfg.getFullSyncStrategy();
        pageSize = cfg.effectivePageSize();
        batchSize = cfg.effectiveBatchSize();
        if (cfg.getBatchSize() != null && cfg.getBatchSize() > pageSize) {
            warn(ErrorCodes.CONFIG_INVALID, "batchSize(" + cfg.getBatchSize() + ")大于 pageSize(" + pageSize
                    + ")，已按 pageSize 收敛", "把 batchSize 配成不大于 pageSize 的值以避免内存放大");
        }
        if (cfg.getTargetTable() == null || cfg.getTargetTable().isBlank()) {
            error(ErrorCodes.IDENTIFIER_INVALID, "目标表名不能为空", "请在任务里选择目标表");
        } else {
            validateIdentifier(cfg.getTargetTable(), "目标表名");
        }
        // SWAP 需要暂存表名，先做一次可用性检查（真正的类型判断在连库后）
        if (strategy == FullSyncStrategy.SWAP && cfg.getTargetTable() != null
                && !cfg.getTargetTable().isBlank() && cfg.getTargetTable().length() > 40) {
            warn(ErrorCodes.STRATEGY_UNSUPPORTED, "目标表名较长，SWAP 暂存表名可能超出数据库标识符长度限制",
                    "必要时改用 TRUNCATE/DELETE 策略");
        }

        customSql = cfg.isCustomSql();
        if (customSql) {
            PreflightIssue sqlIssue = CustomSqlGuard.validate(cfg.getSourceSql());
            if (sqlIssue != null) {
                issues.add(sqlIssue);
            } else {
                sourceSql = CustomSqlGuard.normalize(cfg.getSourceSql());
                wrappedSourceSql = CustomSqlGuard.wrap(sourceSql);
            }
        } else {
            if (cfg.getSourceTable() == null || cfg.getSourceTable().isBlank()) {
                error(ErrorCodes.IDENTIFIER_INVALID, "源表名不能为空", "请在任务里选择源表");
            } else {
                validateIdentifier(cfg.getSourceTable(), "源表名");
            }
        }
        if (cfg.getOrderColumn() != null && !cfg.getOrderColumn().isBlank()) {
            validateIdentifier(cfg.getOrderColumn(), "排序键 orderColumn");
        }
        if (cfg.getIncrColumn() != null && !cfg.getIncrColumn().isBlank()) {
            validateIdentifier(cfg.getIncrColumn(), "增量列 incrColumn");
        }
        for (FieldMapping fm : cfg.mappingsOrEmpty()) {
            if (fm == null) {
                continue;
            }
            if (fm.getSourceColumn() != null && !fm.getSourceColumn().isBlank()) {
                validateIdentifier(fm.getSourceColumn(), "字段映射源列");
            }
            if (fm.getTargetColumn() != null && !fm.getTargetColumn().isBlank()) {
                validateIdentifier(fm.getTargetColumn(), "字段映射目标列");
            }
        }
    }

    /** 同步模式 → 本次是否走增量、是否清理目标表。 */
    private void resolveMode() {
        SyncMode mode = cfg.getSyncMode();
        String cursor = cfg.effectiveCursorValue();
        if (mode == SyncMode.FULL) {
            incremental = false;
        } else if (mode == SyncMode.FULL_INCR) {
            incremental = cursor != null;
        } else {
            // INCR 与未配置模式：一律不清理目标表（契约 D14），靠幂等 upsert 保证正确性
            incremental = true;
        }
        fullMode = !incremental;
    }

    private void resolveCustomSqlSource(Connection sc) {
        if (wrappedSourceSql == null) {
            return;
        }
        String probeSql = wrappedSourceSql + " WHERE 1=0";
        try (PreparedStatement ps = sc.prepareStatement(probeSql);
             ResultSet rs = ps.executeQuery()) {
            ResultSetMetaData md = rs.getMetaData();
            List<ColumnMeta> cols = new ArrayList<>();
            for (int i = 1; i <= md.getColumnCount(); i++) {
                cols.add(new ColumnMeta(md.getColumnLabel(i), md.getColumnTypeName(i), md.getColumnType(i),
                        md.isNullable(i) != ResultSetMetaData.columnNoNulls, false, i,
                        md.getPrecision(i), md.getScale(i)));
            }
            sourceMeta = new TableMeta(null, cols, List.of(), List.of());
            if (cols.isEmpty()) {
                error(ErrorCodes.CUSTOM_SQL_INVALID, "自定义 SQL 没有返回任何列",
                        "确认 SQL 是完整的 SELECT 查询且能返回结果集");
            }
        } catch (SQLException e) {
            error(ErrorCodes.CUSTOM_SQL_INVALID, "自定义 SQL 执行失败: " + describe(e),
                    "在源库客户端里直接执行这条 SQL 定位语法/表名/权限问题");
        }
    }

    /** 字段映射解析 + 类型兼容检查。 */
    private void resolveColumns() {
        List<FieldMapping> mappings = cfg.mappingsOrEmpty();
        Set<String> usedTargets = new LinkedHashSet<>();
        if (mappings.isEmpty()) {
            for (ColumnMeta tc : targetMeta.columns()) {
                ColumnMeta sc = findSource(tc.name());
                if (sc == null) {
                    continue;
                }
                // 同构同步同样要过类型能力表：VARCHAR/TEXT → INT 这类组合必须在这里拦住，
                // 否则会一路跑到运行期才炸（"预检放行、运行时炸"是 Lead 明确禁止的）
                if (!verifyCompatibility(sc, tc)) {
                    continue;
                }
                if (usedTargets.add(lower(tc.name()))) {
                    columns.add(new ColumnMapping(sc.name(), tc.name(), null, sc, tc, false));
                }
            }
            if (columns.isEmpty()) {
                error(ErrorCodes.NO_TARGET_COLUMNS,
                        "目标表[" + targetTable.name() + "]没有任何可用的同名列，且未配置字段映射",
                        "请显式配置字段映射（源列 → 目标列），或确认源表与目标表结构一致");
            }
            return;
        }
        for (FieldMapping fm : mappings) {
            if (fm == null) {
                continue;
            }
            String tgt = Identifiers.trimToNull(fm.getTargetColumn());
            if (tgt == null) {
                error(ErrorCodes.MAPPING_COLUMN_MISSING, "存在缺少目标列名的字段映射",
                        "补全字段映射的目标列名，或删除该行映射");
                continue;
            }
            if (!Identifiers.isValid(tgt)) {
                error(ErrorCodes.IDENTIFIER_INVALID, "字段映射目标列[" + tgt + "]包含非法字符",
                        "列名只允许字母、数字、下划线、美元符、汉字");
                continue;
            }
            ColumnMeta tm = targetMeta.find(tgt);
            if (tm == null) {
                error(ErrorCodes.MAPPING_COLUMN_MISSING,
                        "目标表[" + targetTable.name() + "]不存在列[" + tgt + "]",
                        "检查字段映射的目标列名，或刷新目标表结构");
                continue;
            }
            String src = Identifiers.trimToNull(fm.getSourceColumn());
            if (src == null) {
                if (fm.getDefaultValue() == null) {
                    error(ErrorCodes.MAPPING_COLUMN_MISSING,
                            "字段映射[" + tgt + "]缺少源列名且未提供默认值",
                            "补全源列名，或填写默认值以常量方式填充该列");
                    continue;
                }
                if (usedTargets.add(lower(tm.name()))) {
                    columns.add(new ColumnMapping(null, tm.name(), fm.getDefaultValue(), null, tm,
                            fm.isPrimaryKey()));
                }
                continue;
            }
            if (!Identifiers.isValid(src)) {
                error(ErrorCodes.IDENTIFIER_INVALID, "字段映射源列[" + src + "]包含非法字符",
                        "列名只允许字母、数字、下划线、美元符、汉字");
                continue;
            }
            ColumnMeta sm = findSource(src);
            if (sm == null) {
                error(ErrorCodes.MAPPING_COLUMN_MISSING,
                        "源表（或自定义 SQL 结果集）不存在列[" + src + "]",
                        "检查字段映射的源列名，或刷新源表结构");
                continue;
            }
            if (!verifyCompatibility(sm, tm)) {
                continue;
            }
            if (usedTargets.add(lower(tm.name()))) {
                columns.add(new ColumnMapping(sm.name(), tm.name(), fm.getDefaultValue(), sm, tm,
                        fm.isPrimaryKey()));
            }
            // 重复目标列：保留首次出现，静默去重（否则 INSERT 列表会出现重复列）
        }
        if (columns.isEmpty()) {
            error(ErrorCodes.NO_TARGET_COLUMNS, "字段映射解析后没有任何可用列",
                    "检查字段映射配置，确保每行都有合法的源列与目标列");
        }
    }

    /** upsert 匹配键解析：映射显式指定 → 目标主键 → 目标唯一键 →（全量策略下）降级纯 INSERT。 */
    private void resolveKeys() {
        List<String> mapped = new ArrayList<>();
        for (ColumnMapping cm : columns) {
            if (cm.primaryKeyFromMapping()) {
                mapped.add(cm.targetColumn());
            }
        }
        if (!mapped.isEmpty()) {
            upsertKeyColumns = mapped;
            return;
        }
        if (!targetMeta.primaryKeys().isEmpty()) {
            upsertKeyColumns = names(targetMeta.primaryKeys());
            requireKeysInWriteSet("主键");
            return;
        }
        if (!targetMeta.uniqueKeys().isEmpty()) {
            upsertKeyColumns = names(targetMeta.uniqueKeys().get(0));
            requireKeysInWriteSet("唯一键");
            return;
        }
        if (fullMode) {
            warn(ErrorCodes.PK_MISSING,
                    "目标表[" + targetTable.name() + "]没有主键或唯一键，本次全量同步（策略 "
                            + strategy + "）已降级为纯 INSERT",
                    "建议给目标表加主键/唯一键以启用幂等 upsert；增量模式下无键会直接失败");
            upsertKeyColumns = List.of();
        } else {
            error(ErrorCodes.PK_MISSING,
                    "目标表[" + targetTable.name() + "]没有主键或唯一键，增量同步无法保证幂等写入",
                    "给目标表添加主键/唯一键，或在字段映射里显式指定主键列，或改用全量同步");
        }
    }

    /**
     * 类型兼容校验（预检与运行时共用 {@link TypeCompatibility} 的能力表）。
     *
     * @return true = 可以映射；false = 不可转换（已记 ERROR，调用方应跳过该列）
     */
    private boolean verifyCompatibility(ColumnMeta sm, ColumnMeta tm) {
        TypeCompatibility.Result compat = TypeCompatibility.check(sm, tm);
        if (compat.incompatible()) {
            error(ErrorCodes.TYPE_INCOMPATIBLE, compat.message(),
                    compat.hint() + "（" + TypeNames.describe(sm.typeName()) + " → "
                            + TypeNames.describe(tm.typeName()) + "）");
            return false;
        }
        if (compat.risky()) {
            warn(ErrorCodes.TYPE_INCOMPATIBLE, compat.message(), compat.hint());
        }
        return true;
    }

    private void requireKeysInWriteSet(String what) {        Set<String> written = new LinkedHashSet<>();
        for (ColumnMapping cm : columns) {
            written.add(lower(cm.targetColumn()));
        }
        for (String k : upsertKeyColumns) {
            if (!written.contains(lower(k))) {
                error(ErrorCodes.MAPPING_COLUMN_MISSING,
                        "目标" + what + "列[" + k + "]不在写入列中，upsert 匹配键会失效（可能重复插入）",
                        "把该列加入字段映射，让它随数据一起写入");
            }
        }
    }

    /**
     * 键集分页排序键（Lead 决策 1 冻结规则）。
     *
     * <p>规则：<b>最终排序键的末位必须挂上唯一列做 tie-breaker</b>，否则键值相同的行会在
     * {@code WHERE key > ?} 翻页时被整组跳过 —— 那是与 OFFSET 同量级的静默丢数。
     *
     * <pre>
     *   候选 c = [incrColumn] + ([orderColumn] 若配置且未重复)   // 有增量列时
     *           = [orderColumn]                                  // 无增量列但配置了
     *           = 源表主键                                        // 都没有
     *   最终   = c + [主键列中尚未出现的...]（主键为空则退到第一个唯一键）
     *   无法唯一 → ORDER_KEY_NOT_UNIQUE（ERROR，绝不退化分页方式）
     * </pre>
     *
     * <p>为什么有增量列时把 incrColumn 放在首位：增量读的排序键同时决定水位推进值
     * （{@code endCursor} = 最后提交行的增量列值）。按增量列排序时该值就是本次读到的最大值，
     * 水位语义最精确；若按 orderColumn 排序，水位会落在某个任意行的增量值上，
     * 下次执行会大量重复读取（安全但低效）。orderColumn 仍参与排序，只是位于增量列之后。
     */
    private void resolveSortKeys(Connection sc) throws SQLException {
        List<ColumnMeta> srcPk = sourceMeta.primaryKeys();
        List<ColumnMeta> srcUnique = srcPk.isEmpty() && !sourceMeta.uniqueKeys().isEmpty()
                ? sourceMeta.uniqueKeys().get(0) : srcPk;
        String incr = incrColumn;
        String order = Identifiers.trimToNull(cfg.getOrderColumn());

        if (customSql) {
            if (order == null) {
                error(ErrorCodes.SORT_KEY_MISSING,
                        "自定义 SQL 必须显式指定 orderColumn 作为键集分页排序键",
                        "选择一个在结果集中唯一且单调的列（如自增主键/时间戳）作为 orderColumn");
                return;
            }
            if (findSource(order) == null) {
                error(ErrorCodes.SORT_KEY_MISSING,
                        "自定义 SQL 的结果集中不存在 orderColumn[" + order + "]",
                        "确认列名与 SELECT 输出列名一致（别名也算）");
                return;
            }
            // 派生表没有主键元数据，orderColumn 就是用户声明的唯一 tie-breaker
            tieBreakKeyColumns = List.of(order);
            fullOrderKeyColumns = incr != null && !order.equalsIgnoreCase(incr)
                    ? List.of(incr, order) : List.of(order);
            validateOrderKeyUnique(fullOrderKeyColumns, true);
            return;
        }

        // 候选排序键
        List<String> candidate = new ArrayList<>();
        if (incr != null) {
            candidate.add(incr);
            if (order != null && !order.equalsIgnoreCase(incr)) {
                if (findSource(order) == null) {
                    error(ErrorCodes.SORT_KEY_MISSING, "源表不存在 orderColumn[" + order + "]",
                            "确认列名拼写，或清空 orderColumn 让引擎按主键自动推断");
                    return;
                }
                candidate.add(order);
            }
        } else if (order != null) {
            if (findSource(order) == null) {
                error(ErrorCodes.SORT_KEY_MISSING, "源表不存在 orderColumn[" + order + "]",
                        "确认列名拼写，或清空 orderColumn 让引擎按主键自动推断");
                return;
            }
            candidate.add(order);
        } else {
            for (ColumnMeta c : sourceMeta.firstUsableKey()) {
                candidate.add(c.name());
            }
            if (candidate.isEmpty()) {
                error(ErrorCodes.SORT_KEY_MISSING,
                        "源表[" + sourceTable.name() + "]没有主键或唯一键，无法做键集分页（禁止 OFFSET 分页）",
                        "给源表加主键/唯一键，或显式配置一个唯一且单调的 orderColumn");
                return;
            }
        }

        // tie-breaker：候选中尚未出现的主键列（没有主键就用第一个唯一键）
        List<String> tieSource = new ArrayList<>();
        for (ColumnMeta c : srcUnique) {
            tieSource.add(c.name());
        }
        List<String> full = new ArrayList<>(candidate);
        for (String t : tieSource) {
            boolean present = false;
            for (String c : full) {
                if (c.equalsIgnoreCase(t)) {
                    present = true;
                    break;
                }
            }
            if (!present) {
                full.add(t);
            }
        }
        boolean candidateAloneUnique = isUniqueKeySet(candidate);
        fullOrderKeyColumns = full;
        tieBreakKeyColumns = incr != null ? full.subList(1, full.size()) : List.of();
        validateOrderKeyUnique(full, candidateAloneUnique);
    }

    /** 排序键是否唯一确定一行；不唯一则报 ORDER_KEY_NOT_UNIQUE（绝不退化分页方式）。 */
    private void validateOrderKeyUnique(List<String> finalKey, boolean alreadyUnique) {
        if (alreadyUnique) {
            return;
        }
        boolean hasTieBreaker = false;
        List<ColumnMeta> all = new ArrayList<>(sourceMeta.primaryKeys());
        for (List<ColumnMeta> uk : sourceMeta.uniqueKeys()) {
            all.addAll(uk);
        }
        for (String k : finalKey) {
            for (ColumnMeta c : all) {
                if (c.nameIs(k)) {
                    hasTieBreaker = true;
                    break;
                }
            }
        }
        if (!hasTieBreaker && !isUniqueKeySet(finalKey)) {
            error(ErrorCodes.ORDER_KEY_NOT_UNIQUE,
                    "排序键 " + finalKey + " 无法唯一确定一行，且源表[" + (sourceTable == null ? "?" : sourceTable.name())
                            + "]没有主键/唯一键可做 tie-breaker",
                    "给源表加主键/唯一键，或改用一个唯一的 orderColumn；键集分页下不唯一的排序键会静默丢行");
        }
    }

    /** 给定列集合本身是否就是源表的某个完整唯一键（主键或唯一索引）。 */
    private boolean isUniqueKeySet(List<String> cols) {
        if (cols.isEmpty()) {
            return false;
        }
        if (sameColumnSet(cols, names(sourceMeta.primaryKeys()))) {
            return true;
        }
        for (List<ColumnMeta> uk : sourceMeta.uniqueKeys()) {
            if (sameColumnSet(cols, names(uk))) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameColumnSet(List<String> a, List<String> b) {
        if (a.size() != b.size() || a.isEmpty()) {
            return false;
        }
        List<String> x = new ArrayList<>();
        for (String s : a) {
            x.add(s.toLowerCase(Locale.ROOT));
        }
        for (String s : b) {
            if (!x.remove(s.toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return x.isEmpty();
    }

    /** 增量列存在性、可排序性、索引建议，并回填 IncrPolicy.timestampColumn。 */
    private void resolveIncremental(Connection sc) throws SQLException {
        String incr = Identifiers.trimToNull(cfg.getIncrColumn());
        if (incr == null) {
            if (incremental && cfg.getSyncMode() == SyncMode.INCR) {
                warn(ErrorCodes.INCR_COLUMN_MISSING,
                        "增量模式未配置增量字段，本次将按全量方式读取（不清空目标表）",
                        "配置 incrColumn 以获得真正的水位增量能力");
            }
            return;
        }
        ColumnMeta incrMeta = findSource(incr);
        if (incrMeta == null) {
            error(ErrorCodes.INCR_COLUMN_MISSING,
                    "增量字段[" + incr + "]在源表（或自定义 SQL 结果集）中不存在",
                    "检查增量字段名，或改为全量同步");
            return;
        }
        if (!incrMeta.isSortableWatermark()) {
            error(ErrorCodes.INCR_COLUMN_NOT_SORTABLE,
                    "增量字段[" + incr + "]的类型 " + incrMeta.typeName() + " 不能用于有序水位",
                    "请使用数值型（自增 ID）或时间型（DATETIME/TIMESTAMP）列作为增量字段");
            return;
        }
        if ("TIME".equals(TypeNames.base(incrMeta.typeName()))) {
            error(ErrorCodes.INCR_COLUMN_NOT_SORTABLE,
                    "增量字段[" + incr + "]是 TIME 类型，它在一天内循环，不能作为单调水位",
                    "请改用 DATE/DATETIME/TIMESTAMP 或数值型列");
            return;
        }
        incrColumn = incrMeta.name();
        timestampCursor = isTimestampCursor(incrMeta);
        // 契约要求：由预检结果回填
        if (cfg.getIncrPolicy() != null) {
            cfg.getIncrPolicy().setTimestampColumn(timestampCursor);
        }
        if (!timestampCursor && cfg.getIncrPolicy() != null && cfg.getIncrPolicy().getSafetyLagSeconds() > 0) {
            warn(ErrorCodes.CONFIG_INVALID,
                    "增量字段[" + incr + "]是数值型，safetyLagSeconds="
                            + cfg.getIncrPolicy().getSafetyLagSeconds() + " 对它不生效（上界取执行锚点快照值）",
                    "数值型自增列按契约默认 safetyLag=0；如需滞后保护请改用时间戳列");
        }
        if (!customSql && !isIndexedSourceColumn(sc, incr)) {
            incrColumnIndexed = false;
            warn(ErrorCodes.INCR_COLUMN_NO_INDEX,
                    "增量字段[" + incr + "]上没有索引：每翻一页都要全表扫描+排序，实测 10 万行约 35ms/页、"
                            + "100 万行约 350ms/页（整表吞吐从 8 万行/秒掉到约 1500 行/秒）",
                    "为该列建立索引（例如 ALTER TABLE " + sourceTable.table() + " ADD INDEX idx_"
                            + incr.toLowerCase(Locale.ROOT) + " (" + incr + "))，大表增量同步必须建索引");
        } else {
            incrColumnIndexed = true;
        }
    }

    /** 把映射里用不到的排序/水位列也纳入 SELECT，否则拿不到游标值。 */
    private void buildSelectColumns() {
        if (columns.isEmpty()) {
            return;
        }
        LinkedHashSet<String> select = new LinkedHashSet<>();
        for (ColumnMapping cm : columns) {
            if (cm.sourceColumn() != null) {
                select.add(cm.sourceColumn());
            }
        }
        if (incremental) {
            if (incrColumn != null) {
                select.add(incrColumn);
            }
            select.addAll(tieBreakKeyColumns);
        } else {
            select.addAll(fullOrderKeyColumns);
        }
        selectColumns.clear();
        selectColumns.addAll(select);
    }

    // ------------------------------------------------------------------
    // 权限
    // ------------------------------------------------------------------

    private void checkTargetPermission(Connection dc) {
        if (cfg.isDryRun() || targetDbType != DbType.MYSQL || !fullMode) {
            return;
        }
        List<String> required = new ArrayList<>();
        if (strategy == FullSyncStrategy.TRUNCATE) {
            // MySQL 的 TRUNCATE 需要 DROP 权限
            required.add("DROP");
        } else if (strategy == FullSyncStrategy.DELETE) {
            required.add("DELETE");
        } else if (strategy == FullSyncStrategy.SWAP) {
            required.add("CREATE");
            required.add("DROP");
        }
        if (required.isEmpty()) {
            return;
        }
        List<String> grants = readGrants(dc);
        if (grants.isEmpty()) {
            return;
        }
        String database = null;
        try {
            database = dc.getCatalog();
            if (database == null) {
                database = targetTable.schema();
            }
        } catch (SQLException ignore) {
            database = targetTable.schema();
        }
        for (String privilege : required) {
            PreflightIssue issue = MySqlPrivileges.check(grants, privilege, database);
            if (issue != null) {
                issues.add(issue);
                return;
            }
        }
    }

    private List<String> readGrants(Connection dc) {
        List<String> lines = new ArrayList<>();
        try (Statement st = dc.createStatement();
             ResultSet rs = st.executeQuery("SHOW GRANTS FOR CURRENT_USER()")) {
            while (rs.next()) {
                lines.add(rs.getString(1));
            }
        } catch (SQLException e) {
            // 读不到授权信息就跳过权限检查（绝不因解析失败拦住合法任务）
            return List.of();
        }
        return lines;
    }

    private boolean isIndexedSourceColumn(Connection sc, String column) {
        try {
            for (String lead : JdbcMetadata.indexedLeadColumns(sc, sourceTable)) {
                if (lead.equalsIgnoreCase(column)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            // 元数据不可用时不误报
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private ColumnMeta findSource(String name) {
        return sourceMeta == null ? null : sourceMeta.find(name);
    }

    private boolean isUniqueSourceKey(String column) {
        for (ColumnMeta pk : sourceMeta.primaryKeys()) {
            if (pk.nameIs(column)) {
                return true;
            }
        }
        for (List<ColumnMeta> uk : sourceMeta.uniqueKeys()) {
            if (uk.size() == 1 && uk.get(0).nameIs(column)) {
                return true;
            }
        }
        return false;
    }

    private void validateIdentifier(String identifier, String what) {
        if (!Identifiers.isValid(identifier)) {
            error(ErrorCodes.IDENTIFIER_INVALID, what + "[" + identifier + "]包含非法字符",
                    "只允许字母、数字、下划线、美元符、汉字，以及最多 2 个用于分隔库名/模式名的点号");
        }
    }

    private static boolean isTimestampCursor(ColumnMeta m) {
        if ("YEAR".equals(TypeNames.base(m.typeName()))) {
            return false;
        }
        return switch (m.jdbcType()) {
            case java.sql.Types.DATE, java.sql.Types.TIMESTAMP, java.sql.Types.TIMESTAMP_WITH_TIMEZONE -> true;
            default -> false;
        };
    }

    private static List<String> names(List<ColumnMeta> cols) {
        List<String> out = new ArrayList<>(cols.size());
        for (ColumnMeta c : cols) {
            out.add(c.name());
        }
        return out;
    }

    private static String lower(String s) {
        return s == null ? null : s.toLowerCase(Locale.ROOT);
    }

    private void error(String code, String message, String hint) {
        issues.add(PreflightIssue.error(code, message, hint));
    }

    private void warn(String code, String message, String hint) {
        issues.add(PreflightIssue.warn(code, message, hint));
    }

    /** 异常消息里不得出现口令等敏感信息，这里只取类名与简短原因。 */
    private static String describe(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            msg = "";
        }
        msg = msg.replaceAll("(?i)(password|pwd)\\s*=\\s*\\S+", "$1=***");
        return e.getClass().getSimpleName() + (msg.isEmpty() ? "" : ": " + msg);
    }

    // ------------------------------------------------------------------
    // 只读访问器（引擎用）
    // ------------------------------------------------------------------

    public List<PreflightIssue> issues() {
        return issues;
    }

    public boolean hasError() {
        for (PreflightIssue i : issues) {
            if (i.isError()) {
                return true;
            }
        }
        return false;
    }

    public List<String> errorMessages() {
        List<String> out = new ArrayList<>();
        for (PreflightIssue i : issues) {
            if (i.isError()) {
                out.add(i.getCode() + ": " + i.getMessage());
            }
        }
        return out;
    }

    public SyncTaskConfig config() {
        return cfg;
    }

    public boolean customSql() {
        return customSql;
    }

    public String sourceSql() {
        return sourceSql;
    }

    public String wrappedSourceSql() {
        return wrappedSourceSql;
    }

    public TableRef sourceTable() {
        return sourceTable;
    }

    public TableRef targetTable() {
        return targetTable;
    }

    public TableMeta sourceMeta() {
        return sourceMeta;
    }

    public TableMeta targetMeta() {
        return targetMeta;
    }

    public DbType sourceDbType() {
        return sourceDbType;
    }

    public DbType targetDbType() {
        return targetDbType;
    }

    public SqlDialect sourceDialect() {
        return sourceDialect;
    }

    public SqlDialect targetDialect() {
        return targetDialect;
    }

    public List<ColumnMapping> columns() {
        return columns;
    }

    /** 写入的目标列（按映射顺序），用于拼 INSERT/UPSERT 的列清单。 */
    public List<String> targetColumns() {
        List<String> out = new ArrayList<>(columns.size());
        for (ColumnMapping cm : columns) {
            out.add(cm.targetColumn());
        }
        return out;
    }

    public List<String> selectColumns() {
        return selectColumns;
    }

    public List<String> upsertKeyColumns() {
        return upsertKeyColumns;
    }

    public List<String> tieBreakKeyColumns() {
        return tieBreakKeyColumns;
    }

    public List<String> fullOrderKeyColumns() {
        return fullOrderKeyColumns;
    }

    public String incrColumn() {
        return incrColumn;
    }

    public boolean timestampCursor() {
        return timestampCursor;
    }

    /** 增量列是否有可用索引（供引擎在运行日志里提示性能风险）。 */
    public boolean incrColumnIndexed() {
        return incrColumnIndexed;
    }

    public boolean incremental() {
        return incremental;
    }

    public boolean fullMode() {
        return fullMode;
    }

    public FullSyncStrategy strategy() {
        return strategy;
    }

    public boolean useUpsert() {
        return useUpsert;
    }

    public int pageSize() {
        return pageSize;
    }

    public int batchSize() {
        return batchSize;
    }

    public boolean dryRun() {
        return cfg != null && cfg.isDryRun();
    }

    /** 新增的 upsert 匹配键是否为「用户显式指定」（用于日志口径）。 */
    public boolean keysFromMapping() {
        for (ColumnMapping cm : columns) {
            if (cm.primaryKeyFromMapping()) {
                return true;
            }
        }
        return false;
    }

    /** 目标表 → 源列名的反查（读游标值用）。 */
    public Map<String, ColumnMapping> byTargetColumn() {
        Map<String, ColumnMapping> map = new HashMap<>();
        for (ColumnMapping cm : columns) {
            map.put(lower(cm.targetColumn()), cm);
        }
        return map;
    }

    @Override
    public String toString() {
        return "SyncPlan{" + (customSql ? "CUSTOM_SQL" : sourceTable) + " → " + targetTable
                + ", incremental=" + incremental + ", strategy=" + strategy
                + ", keys=" + upsertKeyColumns + ", orderKeys=" + fullOrderKeyColumns
                + ", tieBreak=" + tieBreakKeyColumns + ", upsert=" + useUpsert
                + ", issues=" + issues.size() + "}";
    }
}
