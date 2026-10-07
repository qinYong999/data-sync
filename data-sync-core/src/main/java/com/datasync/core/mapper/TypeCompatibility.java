package com.datasync.core.mapper;

import com.datasync.core.jdbc.ColumnMeta;
import java.sql.Types;
import java.util.Locale;

/**
 * 类型转换能力表 —— <b>预检与运行时的唯一事实源</b>（Lead 决策 5）。
 *
 * <p>为什么必须只有一张表：如果预检按一套规则放行、运行时按另一套规则转换，就会出现
 * "预检通过、执行到一半炸"或"预检拦住、其实完全能转"两类事故，而且没人能说清到底哪套是对的。
 * 因此 {@code SyncPlan} 的 TYPE_INCOMPATIBLE 判定与 {@link DefaultValueConverter} 的运行时转换
 * 共用本类。
 *
 * <p>三档结论：
 * <ul>
 *   <li>{@link Level#OK}：有定义且无信息损失风险。</li>
 *   <li>{@link Level#RISKY}：转换有定义，但可能因数据本身失败或丢失精度（超长、溢出、小数位收缩、
 *       字符串解析失败……）。预检报 WARN，运行期失败会变成 {@code sync_error} 里的坏行。</li>
 *   <li>{@link Level#INCOMPATIBLE}：语义上不可能安全转换（例如文本大字段 → 数值、日期 → 数值）。
 *       预检报 ERROR，绝不执行。</li>
 * </ul>
 */
public final class TypeCompatibility {

    /** 三档结论。 */
    public enum Level {
        OK,
        RISKY,
        INCOMPATIBLE
    }

    /** 判定结果。 */
    public static final class Result {
        private final Level level;
        private final String message;
        private final String hint;

        Result(Level level, String message, String hint) {
            this.level = level;
            this.message = message;
            this.hint = hint;
        }

        public Level level() {
            return level;
        }

        public String message() {
            return message;
        }

        public String hint() {
            return hint;
        }

        public boolean ok() {
            return level == Level.OK;
        }

        public boolean risky() {
            return level == Level.RISKY;
        }

        public boolean incompatible() {
            return level == Level.INCOMPATIBLE;
        }

        @Override
        public String toString() {
            return level + ": " + message;
        }
    }

    private TypeCompatibility() {
    }

    /** 能否转换（RISKY 也算能转）。 */
    public static boolean canConvert(ColumnMeta source, ColumnMeta target) {
        return check(source, target).level != Level.INCOMPATIBLE;
    }

    /** 完整判定（含中文原因与修复建议）。 */
    public static Result check(ColumnMeta source, ColumnMeta target) {
        if (source == null || target == null) {
            return new Result(Level.OK, null, null);
        }
        String src = label(source);
        String tgt = label(target);

        // ---- 目标：字符 / 大文本 ----
        if (target.isCharacter()) {
            if (source.isCharacter() && target.columnSize() > 0 && source.columnSize() > target.columnSize()
                    && isLengthBounded(target)) {
                return new Result(Level.RISKY,
                        "源列[" + src + "]长度 " + source.columnSize() + " 大于目标列[" + tgt + "]长度 "
                                + target.columnSize() + "，超长值会成为坏行（不会静默截断）",
                        "扩大目标列长度，或在源侧过滤/截断超长数据");
            }
            return new Result(Level.OK, null, null);
        }

        // ---- 目标：二进制 ----
        if (target.isBinary()) {
            return new Result(Level.OK, null, null);
        }

        // ---- 目标：布尔 ----
        if (isBoolean(target)) {
            if (source.isTemporal() && !isYear(source)) {
                return incompatible(src, tgt, "日期时间无法安全转换为布尔值");
            }
            return new Result(Level.OK, null, null);
        }

        // ---- 目标：数值 ----
        if (target.isNumeric()) {
            if (source.isNumeric()) {
                Result rangeCheck = checkNumericShrink(source, target, src, tgt);
                return rangeCheck == null ? new Result(Level.OK, null, null) : rangeCheck;
            }
            if (isYear(source)) {
                return new Result(Level.OK, null, null);
            }
            if (source.isCharacter()) {
                if (isLargeText(source)) {
                    return incompatible(src, tgt,
                            "文本大字段通常存放自由文本，无法安全转换为数值");
                }
                return new Result(Level.RISKY,
                        "源列[" + src + "]是字符串，转换为数值列[" + tgt + "]时非数字值会成为坏行",
                        "确认该列确实只存数字；否则修正字段映射或目标列类型");
            }
            if (source.isTemporal()) {
                return incompatible(src, tgt,
                        "日期时间无法安全转换为数值（驱动会静默写成 20260101 这类垃圾值）");
            }
            if (source.isBinary()) {
                return incompatible(src, tgt, "二进制列无法安全转换为数值");
            }
            return new Result(Level.OK, null, null);
        }

        // ---- 目标：日期时间 ----
        if (target.isTemporal()) {
            if (source.isTemporal()) {
                if (isTimestampLike(source) && isDateOnly(target)) {
                    return new Result(Level.RISKY,
                            "源列[" + src + "]含时间部分，写入日期列[" + tgt + "]时会丢弃时间",
                            "确认业务只关心日期；否则把目标列改成 DATETIME/TIMESTAMP");
                }
                return new Result(Level.OK, null, null);
            }
            if (isYear(source)) {
                return new Result(Level.OK, null, null);
            }
            if (source.isCharacter()) {
                return new Result(Level.RISKY,
                        "源列[" + src + "]是字符串，转换为日期时间列[" + tgt + "]时格式不符的值会成为坏行",
                        "确认字符串形如 yyyy-MM-dd HH:mm:ss；否则调整源数据或目标列类型");
            }
            if (source.isNumeric()) {
                return incompatible(src, tgt, "数值无法安全转换为日期时间（除 YEAR 以外）");
            }
            if (source.isBinary()) {
                return incompatible(src, tgt, "二进制列无法安全转换为日期时间");
            }
            return new Result(Level.OK, null, null);
        }

        // ---- 未知目标类型：透传，不阻断 ----
        return new Result(Level.OK, null, null);
    }

    private static Result checkNumericShrink(ColumnMeta source, ColumnMeta target, String src, String tgt) {
        long srcRange = integerRange(source.jdbcType());
        long tgtRange = integerRange(target.jdbcType());
        if (srcRange > 0 && tgtRange > 0 && srcRange > tgtRange) {
            return new Result(Level.RISKY,
                    "源列[" + src + "]的整型范围大于目标列[" + tgt + "]，超出范围的值会成为坏行（不会静默截断）",
                    "确认数据不会溢出，或把目标列类型放大（如 INT → BIGINT）");
        }
        if (isFloating(source) && integerRange(target.jdbcType()) > 0) {
            return new Result(Level.RISKY,
                    "源列[" + src + "]是浮点类型，写入整型列[" + tgt + "]会四舍五入",
                    "确认业务接受取整；否则把目标列改成 DECIMAL/DOUBLE");
        }
        if (isDecimalLike(source) && isDecimalLike(target)) {
            int srcIntDigits = source.columnSize() - source.decimalDigits();
            int tgtIntDigits = target.columnSize() - target.decimalDigits();
            if (target.columnSize() > 0 && (srcIntDigits > tgtIntDigits
                    || source.decimalDigits() > target.decimalDigits())) {
                return new Result(Level.RISKY,
                        "源列[" + src + "]精度(" + source.columnSize() + "," + source.decimalDigits()
                                + ")超出目标列[" + tgt + "]精度(" + target.columnSize() + ","
                                + target.decimalDigits() + ")，会发生小数位四舍五入或整数位溢出",
                        "放大目标列精度，或在源侧先做数值规整");
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static Result incompatible(String src, String tgt, String reason) {
        return new Result(Level.INCOMPATIBLE,
                "源列[" + src + "]无法安全转换到目标列[" + tgt + "]：" + reason,
                "调整字段映射或目标列类型（" + TypeNames.describe(srcTypeOf(src)) + " → 目标需为兼容类型）");
    }

    private static String srcTypeOf(String label) {
        int l = label.lastIndexOf('(');
        int r = label.lastIndexOf(')');
        return l >= 0 && r > l ? label.substring(l + 1, r) : "";
    }

    private static String label(ColumnMeta c) {
        return c.name() + "(" + c.typeName() + ")";
    }

    private static boolean isBoolean(ColumnMeta c) {
        return c.jdbcType() == Types.BOOLEAN || c.jdbcType() == Types.BIT
                || c.typeName().toUpperCase(Locale.ROOT).startsWith("BOOL");
    }

    private static boolean isYear(ColumnMeta c) {
        return TypeNames.base(c.typeName()).equals("YEAR");
    }

    private static boolean isLargeText(ColumnMeta c) {
        String base = TypeNames.base(c.typeName());
        return base.contains("TEXT") || base.equals("CLOB") || base.equals("NCLOB")
                || c.jdbcType() == Types.CLOB || c.jdbcType() == Types.NCLOB
                || c.jdbcType() == Types.LONGVARCHAR || c.jdbcType() == Types.LONGNVARCHAR;
    }

    private static boolean isLengthBounded(ColumnMeta c) {
        String base = TypeNames.base(c.typeName());
        return base.equals("CHAR") || base.equals("VARCHAR") || base.equals("NCHAR")
                || base.equals("NVARCHAR") || base.equals("VARCHAR2") || base.equals("NVARCHAR2");
    }

    private static boolean isTimestampLike(ColumnMeta c) {
        return c.jdbcType() == Types.TIMESTAMP || c.jdbcType() == Types.TIMESTAMP_WITH_TIMEZONE;
    }

    private static boolean isDateOnly(ColumnMeta c) {
        return c.jdbcType() == Types.DATE;
    }

    private static boolean isFloating(ColumnMeta c) {
        return c.jdbcType() == Types.FLOAT || c.jdbcType() == Types.REAL || c.jdbcType() == Types.DOUBLE;
    }

    private static boolean isDecimalLike(ColumnMeta c) {
        return c.jdbcType() == Types.DECIMAL || c.jdbcType() == Types.NUMERIC;
    }

    /** 整型位宽（用于范围收缩判断）；非整型返回 0。 */
    static long integerRange(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT -> 8L;
            case Types.SMALLINT -> 16L;
            case Types.INTEGER -> 32L;
            case Types.BIGINT -> 64L;
            default -> 0L;
        };
    }
}
