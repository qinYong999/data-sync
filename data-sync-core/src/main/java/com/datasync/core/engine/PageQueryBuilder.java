package com.datasync.core.engine;

import com.datasync.core.dialect.SqlDialect;
import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.preflight.SyncPlan;
import java.util.ArrayList;
import java.util.List;

/**
 * 一页查询：SQL 文本 + 按顺序绑定的参数。
 *
 * <p>无论走方言还是走自定义 SQL 包装，参数顺序都固定为：
 * {@code [lower?] + [upper?] + [键集游标值...]}。
 */
final class PageQuery {

    final String sql;
    final List<Object> params;

    PageQuery(String sql, List<Object> params) {
        this.sql = sql;
        this.params = params;
    }

    @Override
    public String toString() {
        return sql + " " + params;
    }
}

/**
 * 分页查询构造器：把「执行计划 + 水位 + 游标位置」翻译成一条键集分页 SQL。
 *
 * <p>两条路径：
 * <ul>
 *   <li>普通表：交给方言的 {@code buildKeysetSelect} / {@code buildWatermarkSelect}。</li>
 *   <li>自定义 SQL：把用户 SQL 包成派生表 {@code SELECT ... FROM (<sql>) _ds_src WHERE ...}，
 *       在外层做键集分页（内层 SQL 由 preflight 的 CUSTOM_SQL_INVALID 校验过）。</li>
 * </ul>
 */
final class PageQueryBuilder {

    private PageQueryBuilder() {
    }

    /** 是否走水位区间读取（增量模式且配置了增量列）。 */
    static boolean watermarkRead(SyncPlan plan) {
        return plan.incremental() && plan.incrColumn() != null;
    }

    static PageQuery build(SyncPlan plan, KeysetPosition after, Watermark wm, int pageSize) {
        boolean watermark = watermarkRead(plan);
        SqlDialect d = plan.sourceDialect();
        List<String> tieColumns = watermark ? plan.tieBreakKeyColumns() : plan.fullOrderKeyColumns();
        List<String> orderKeys = watermark ? watermarkOrderKeys(plan) : plan.fullOrderKeyColumns();

        List<String> predicates = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if (watermark) {
            if (wm.lowerValue != null) {
                predicates.add(d.quote(plan.incrColumn()) + " > ?");
                params.add(wm.lowerValue);
            }
            if (wm.upperValue != null) {
                predicates.add(d.quote(plan.incrColumn()) + " <= ?");
                params.add(wm.upperValue);
            }
        }
        String keyset = d.buildKeysetPredicate(orderKeys, after);
        if (!keyset.isEmpty()) {
            predicates.add(keyset);
            // 参数必须用与谓词配对的方法生成：展开式 OR 的占位符是 n(n+1)/2 个（前缀重复展开），
            // 直接 addAll(after.values()) 会少绑参数、错位绑定
            params.addAll(d.buildKeysetParameters(orderKeys, after));
        }

        if (plan.customSql()) {
            StringBuilder sb = new StringBuilder("SELECT ");
            List<String> cols = plan.selectColumns();
            for (int i = 0; i < cols.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(d.quote(cols.get(i)));
            }
            sb.append(" FROM (").append(plan.sourceSql()).append(") _ds_src");
            if (!predicates.isEmpty()) {
                sb.append(" WHERE ").append(String.join(" AND ", predicates));
            }
            sb.append(" ORDER BY ");
            for (int i = 0; i < orderKeys.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(d.quote(orderKeys.get(i))).append(" ASC");
            }
            sb.append(' ').append(d.limitClause(pageSize));
            return new PageQuery(sb.toString(), params);
        }

        String sql;
        if (watermark) {
            sql = d.buildWatermarkSelect(plan.sourceTable(), plan.selectColumns(), plan.incrColumn(),
                    wm.lowerValue != null ? "?" : null,
                    wm.upperValue != null ? "?" : null,
                    tieColumns, after, pageSize);
        } else {
            sql = d.buildKeysetSelect(plan.sourceTable(), plan.selectColumns(), orderKeys, after,
                    pageSize, List.of());
        }
        return new PageQuery(sql, params);
    }

    /** 水位读的顺序键 = 增量列 + tie-breaker（元组比较，绝不只比第一列）。 */
    static List<String> watermarkOrderKeys(SyncPlan plan) {
        List<String> ordered = new ArrayList<>();
        ordered.add(plan.incrColumn());
        if (plan.tieBreakKeyColumns() != null) {
            for (String c : plan.tieBreakKeyColumns()) {
                if (c != null && !c.equalsIgnoreCase(plan.incrColumn())) {
                    ordered.add(c);
                }
            }
        }
        return ordered;
    }
}
