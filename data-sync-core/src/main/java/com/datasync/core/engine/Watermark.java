package com.datasync.core.engine;

import com.datasync.core.jdbc.ColumnMeta;
import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.model.IncrPolicy;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.preflight.SyncPlan;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 本次执行的读水位。
 *
 * <p>语义（契约 D3 / 任务要求 2）：
 * <pre>
 *   下界 lower = 上次水位 - lookback          （时间戳列建议 lookback ≥ 60s）
 *   上界 upper = 执行锚点 - safetyLag          （锚点在开始读取时取一次并固定）
 * </pre>
 *
 * <p><b>锚点不是水位来源</b>：时间戳列的锚点是"执行时刻"；数值列的锚点是执行开始时的
 * {@code MAX(incr)} 快照。锚点只用于限制本次读取范围，最终推进的水位永远是"本次成功提交的最后一行"
 * 的增量列值（见 {@link DefaultSyncEngine}），绝不使用 {@code SELECT MAX()} 推进水位 ——
 * 那正是旧实现丢数的根因。
 */
final class Watermark {

    /** 读下界（null = 不限制）。 */
    final Object lowerValue;
    /** 读上界（null = 不限制）。 */
    final Object upperValue;
    /** 首页的键集位置；第一页为 {@link KeysetPosition#first()}。 */
    final KeysetPosition initialPosition;
    /** 本次生效的水位原文（用于 SyncRunResult.startCursor）。 */
    final String startCursorText;
    /** 诊断用文本。 */
    final String lowerText;
    final String upperText;

    private Watermark(Object lowerValue, Object upperValue, KeysetPosition initialPosition,
                      String startCursorText, String lowerText, String upperText) {
        this.lowerValue = lowerValue;
        this.upperValue = upperValue;
        this.initialPosition = initialPosition;
        this.startCursorText = startCursorText;
        this.lowerText = lowerText;
        this.upperText = upperText;
    }

    /** 全量模式：没有任何水位约束。 */
    static Watermark none() {
        return new Watermark(null, null, KeysetPosition.first(), null, null, null);
    }

    /** 增量模式：计算上下界与首页位置。 */
    static Watermark forIncremental(SyncPlan plan, SyncTaskConfig cfg, Connection srcConn) throws SQLException {
        IncrPolicy policy = cfg.getIncrPolicy() == null ? new IncrPolicy() : cfg.getIncrPolicy();
        ColumnMeta incrMeta = plan.sourceMeta() == null ? null : plan.sourceMeta().find(plan.incrColumn());
        Object upper;
        String upperText;
        if (plan.timestampCursor()) {
            LocalDateTime anchor = LocalDateTime.now();
            LocalDateTime u = anchor.minusSeconds(Math.max(0L, policy.getSafetyLagSeconds()));
            upper = u;
            upperText = com.datasync.core.mapper.ValueText.formatLocalDateTime(u);
        } else {
            upper = readAnchorMax(plan, srcConn);
            upperText = upper == null ? null : com.datasync.core.mapper.ValueText.format(upper);
        }

        String cursorText = cfg.effectiveCursorValue();
        if (cursorText == null || cursorText.isBlank()) {
            return new Watermark(null, upper, KeysetPosition.first(), null, null, upperText);
        }
        String scalarText = CursorCodec.scalarOf(cursorText);
        Object scalar = CursorCodec.parseComponent(scalarText, incrMeta);
        long lookback = Math.max(0L, policy.getLookbackSeconds());
        List<String> tieColumns = plan.tieBreakKeyColumns();
        List<String> tieParts = CursorCodec.tiePartsOf(cursorText);

        // 复合游标 + 无回看窗口：首页直接用元组位置续跑，等价于 (incr, pk) > (?, ?)
        if (!tieParts.isEmpty() && tieColumns != null && tieColumns.size() == tieParts.size() && lookback == 0L) {
            List<String> ordered = new ArrayList<>();
            ordered.add(plan.incrColumn());
            ordered.addAll(tieColumns);
            List<Object> values = new ArrayList<>();
            values.add(scalar);
            for (int i = 0; i < tieColumns.size(); i++) {
                ColumnMeta meta = plan.sourceMeta() == null ? null : plan.sourceMeta().find(tieColumns.get(i));
                values.add(CursorCodec.parseComponent(tieParts.get(i), meta));
            }
            return new Watermark(null, upper, KeysetPosition.of(ordered, values), cursorText,
                    scalarText, upperText);
        }

        Object lower = CursorCodec.minusSeconds(scalar, lookback, incrMeta);
        return new Watermark(lower, upper, KeysetPosition.first(), cursorText,
                com.datasync.core.mapper.ValueText.format(lower), upperText);
    }

    /**
     * 数值型增量列的读上界锚点：执行开始时的 {@code MAX(incr)} 快照。
     *
     * <p>它是"读到哪里为止"，不是"水位推进到哪里"——两者绝不能混为一谈。
     */
    private static Object readAnchorMax(SyncPlan plan, Connection srcConn) throws SQLException {
        String sql;
        if (plan.customSql()) {
            sql = "SELECT MAX(" + plan.sourceDialect().quote(plan.incrColumn()) + ") FROM ("
                    + plan.sourceSql() + ") _ds_src";
        } else {
            sql = plan.sourceDialect().buildMaxValue(plan.sourceTable(), plan.incrColumn());
        }
        try (Statement st = srcConn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            if (!rs.next()) {
                return null;
            }
            Object v = rs.getObject(1);
            if (v == null) {
                return null;
            }
            if (v instanceof Number n) {
                ColumnMeta meta = plan.sourceMeta() == null ? null : plan.sourceMeta().find(plan.incrColumn());
                return CursorCodec.narrow(new BigDecimal(n.toString()), meta);
            }
            return v;
        }
    }

    @Override
    public String toString() {
        return "Watermark{lower=" + lowerText + ", upper=" + upperText
                + ", position=" + initialPosition + "}";
    }
}
