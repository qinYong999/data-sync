package com.datasync.core.engine;

import com.datasync.core.jdbc.ColumnMeta;
import com.datasync.core.mapper.DefaultValueConverter;
import com.datasync.core.mapper.ValueText;
import java.math.BigDecimal;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/**
 * 游标编解码（Lead 决策 1：水位必须能承载复合游标）。
 *
 * <p><b>编码格式（面向运维可读：能直接在 {@code sync_task.cursor_value} 里看懂、必要时手工修正）</b>：
 * <ul>
 *   <li><b>标量游标</b>：排序键只有增量列本身（增量列就是主键/唯一键）→ 直接存值的文本形式，
 *       例如 {@code 12345} 或 {@code 2026-05-30 21:01:03}。与历史数据完全兼容。</li>
 *   <li><b>复合游标</b>：增量列不唯一、需要主键做 tie-breaker 时 → 用竖线分隔，
 *       例如 {@code 2026-05-30 21:01:03.762127|42}，第 1 段是增量列值，其余是 tie-breaker 分量。</li>
 * </ul>
 * 不使用 Base64/二进制。分量只可能是数值或时间（水位列的取值域），竖线不会与分量本身冲突；
 * 万一真出现竖线，段数校验会失败并安全退回标量语义（多读几行，绝不丢数）。
 *
 * <p>解码时：复合游标取第 1 段作为增量列的标量下界（标量比较永远安全），
 * 其余分量仅在"无回看窗口"时用于把首页的键集位置精确还原成元组比较
 * {@code (incr, pk) > (?, ?)}；为兼容早期写过的 JSON 数组形态，也接受 {@code ["v","k"]}。
 */
final class CursorCodec {

    /** 复合游标的分量分隔符（可读优先）。 */
    static final char SEPARATOR = '|';

    private CursorCodec() {
    }

    /** 是否需要复合游标（排序键除增量列外还有 tie-breaker）。 */
    static boolean needsComposite(List<String> tieBreakColumns) {
        return tieBreakColumns != null && !tieBreakColumns.isEmpty();
    }

    /**
     * 编码水位。
     *
     * @param incrValue  最后提交行的增量列值
     * @param incrMeta   增量列元数据
     * @param tieColumns 排序键里除增量列以外的列（可为空）
     * @param tieValues  与 tieColumns 对应的值
     */
    static String encode(Object incrValue, ColumnMeta incrMeta, List<String> tieColumns, List<Object> tieValues) {
        String scalar = ValueText.format(DefaultValueConverter.normalizeForCursor(incrValue, incrMeta));
        if (scalar == null || tieColumns == null || tieColumns.isEmpty()) {
            return scalar;
        }
        StringBuilder sb = new StringBuilder(scalar);
        for (Object v : tieValues) {
            sb.append(SEPARATOR).append(ValueText.format(v));
        }
        return sb.toString();
    }

    /** 是否为复合游标文本。 */
    static boolean isComposite(String cursorText) {
        if (cursorText == null) {
            return false;
        }
        return cursorText.indexOf(SEPARATOR) >= 0 || JsonLite.looksLikeArray(cursorText);
    }

    /** 取标量分量（增量列值文本）；复合游标取第 1 段。解析失败时原样返回。 */
    static String scalarOf(String cursorText) {
        if (cursorText == null) {
            return null;
        }
        if (JsonLite.looksLikeArray(cursorText)) {
            try {
                List<String> parts = JsonLite.parseArray(cursorText);
                return parts.isEmpty() ? null : parts.get(0);
            } catch (RuntimeException e) {
                return cursorText;
            }
        }
        int idx = cursorText.indexOf(SEPARATOR);
        return idx < 0 ? cursorText : cursorText.substring(0, idx);
    }

    /** 取 tie-breaker 分量（不含标量）；标量游标返回空列表。 */
    static List<String> tiePartsOf(String cursorText) {
        if (cursorText == null) {
            return List.of();
        }
        if (JsonLite.looksLikeArray(cursorText)) {
            List<String> parts;
            try {
                parts = JsonLite.parseArray(cursorText);
            } catch (RuntimeException e) {
                return List.of();
            }
            return parts.size() <= 1 ? List.of() : new ArrayList<>(parts.subList(1, parts.size()));
        }
        int idx = cursorText.indexOf(SEPARATOR);
        if (idx < 0) {
            return List.of();
        }
        String rest = cursorText.substring(idx + 1);
        if (rest.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(java.util.Arrays.asList(rest.split("\\" + SEPARATOR, -1)));
    }

    /**
     * 按列类型解析游标里的某个分量。
     *
     * @param text 分量文本
     * @param meta 对应列的元数据（决定 Java 类型）
     */
    static Object parseComponent(String text, ColumnMeta meta) {
        if (text == null) {
            return null;
        }
        String typeName = meta == null ? "" : meta.typeName().toUpperCase(java.util.Locale.ROOT);
        if (meta == null) {
            return text;
        }
        if (typeName.startsWith("YEAR")) {
            return DefaultValueConverter.toYearValue(text);
        }
        if (meta.isTemporal()) {
            return ValueText.parseLocalDateTime(text);
        }
        if (meta.isNumeric()) {
            BigDecimal dec = ValueText.parseDecimal(text);
            return switch (meta.jdbcType()) {
                case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> dec.intValue();
                case Types.BIGINT -> dec.longValue();
                case Types.FLOAT, Types.REAL, Types.DOUBLE -> dec.doubleValue();
                default -> dec;
            };
        }
        return text;
    }

    /** 水位回看：数值型按数值减，时间型按秒减。 */
    static Object minusSeconds(Object value, long seconds, ColumnMeta meta) {
        if (value == null || seconds == 0L) {
            return value;
        }
        if (value instanceof java.time.LocalDateTime ldt) {
            return ldt.minusSeconds(seconds);
        }
        if (value instanceof java.time.OffsetDateTime odt) {
            return odt.minusSeconds(seconds);
        }
        if (value instanceof java.time.LocalDate ld) {
            return ld.atStartOfDay().minusSeconds(seconds);
        }
        if (value instanceof BigDecimal dec) {
            return narrow(dec.subtract(BigDecimal.valueOf(seconds)), meta);
        }
        if (value instanceof Number n) {
            return narrow(new BigDecimal(n.toString()).subtract(BigDecimal.valueOf(seconds)), meta);
        }
        return value;
    }

    /** 把 BigDecimal 收窄到列的 Java 类型，避免把 DECIMAL 绑到 INT 列上导致索引失效。 */
    static Object narrow(BigDecimal dec, ColumnMeta meta) {
        if (meta == null) {
            return dec;
        }
        String typeName = meta.typeName() == null ? "" : meta.typeName().toUpperCase(java.util.Locale.ROOT);
        if (typeName.startsWith("YEAR")) {
            return dec.intValue();
        }
        return switch (meta.jdbcType()) {
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> dec.intValue();
            case Types.BIGINT -> dec.longValue();
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> dec.doubleValue();
            default -> dec;
        };
    }
}
