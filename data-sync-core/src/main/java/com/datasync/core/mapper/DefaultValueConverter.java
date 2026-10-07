package com.datasync.core.mapper;

import com.datasync.core.jdbc.ColumnMeta;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.BitSet;
import java.util.Locale;

/**
 * 默认值转换器：JDK 类型 + JDBC 类型双维度归一。
 *
 * <p>处理的关键边界（这些都是旧实现真实踩过的坑）：
 * <ul>
 *   <li><b>null</b>：一律返回 null，由引擎用 {@code setNull(i, targetJdbcType)} 落库。</li>
 *   <li><b>BIT(1)</b>：MySQL 驱动可能返回 {@code byte[]}、{@code Boolean} 或 {@code BitSet}，
 *       统一归一为 {@code Boolean}；{@code BIT(n&gt;1)} 的 byte[] 不误判。</li>
 *   <li><b>YEAR</b>：驱动默认返回 {@code java.sql.Date}（不是数字！），必须取年份转成 Integer，
 *       否则会被当字符串/日期写坏。</li>
 *   <li><b>JSR-310</b>：{@code LocalDateTime/LocalDate/LocalTime/OffsetDateTime} 与
 *       {@code java.sql.*}/{@code java.util.Date} 之间双向可转。</li>
 *   <li><b>BigDecimal</b>：转字符时用 {@code toPlainString()}（避免 1E+2 这种科学计数法）。</li>
 *   <li><b>byte[]</b>：转字符时按 UTF-8 解码；转二进制原样保留。</li>
 * </ul>
 */
public final class DefaultValueConverter implements ValueConverter {

    /**
     * 类型映射器：真正参与生产链路（不是摆设）。
     *
     * <p>转换分两步走：先按「源类型名 → 目标库类型名」让 {@link TypeMapper} 做跨库归一，
     * 再按<b>目标列的真实 jdbcType</b> 收敛成驱动能安全写入的 Java 值。
     * 为 null 时跳过第一步（单测与无类型信息的场景）。
     */
    private final TypeMapper typeMapper;

    public DefaultValueConverter() {
        this(null);
    }

    public DefaultValueConverter(TypeMapper typeMapper) {
        this.typeMapper = typeMapper;
    }

    @Override
    public Object convert(Object source, ColumnMeta sourceMeta, ColumnMeta targetMeta) {
        if (source == null) {
            return null;
        }
        if (targetMeta == null) {
            return source;
        }
        Object normalized = normalizeSource(source, sourceMeta, targetMeta);
        if (typeMapper != null && sourceMeta != null) {
            normalized = applyTypeMapper(normalized, sourceMeta, targetMeta);
        }
        return toTarget(normalized, sourceMeta, targetMeta);
    }

    /** 跨库类型归一：按源类型名映射到目标库类型名，再按该类型名转换值。 */
    private Object applyTypeMapper(Object value, ColumnMeta sourceMeta, ColumnMeta targetMeta) {
        try {
            String targetTypeName = typeMapper.mapTypeName(sourceMeta.typeName());
            if (targetTypeName == null || targetTypeName.isBlank()) {
                return value;
            }
            Object mapped = typeMapper.mapValue(value, targetTypeName);
            // 映射器无能为力（返回原值/返回 null）时保留上一步结果，避免把值弄丢
            return mapped == null ? value : mapped;
        } catch (ValueConversionException e) {
            throw e;
        } catch (RuntimeException e) {
            // 类型映射失败不应阻断同步：退回按目标列 jdbcType 的通用转换
            return value;
        }
    }

    // ------------------------------------------------------------------
    // 源侧归一
    // ------------------------------------------------------------------

    private Object normalizeSource(Object v, ColumnMeta src, ColumnMeta tgt) {
        if (src == null) {
            return v;
        }
        String typeName = upper(src.typeName());
        boolean bitLike = src.jdbcType() == Types.BIT || src.jdbcType() == Types.BOOLEAN
                || typeName.startsWith("BIT") || "BOOLEAN".equals(typeName) || "BOOL".equals(typeName);
        if (bitLike && !isBinaryTarget(tgt)) {
            Boolean b = tryBoolean(v);
            if (b != null) {
                return b;
            }
        }
        if (typeName.startsWith("YEAR")) {
            return toYear(v);
        }
        return v;
    }

    /** 十进制年份（YEAR 源列）。 */
    private Integer toYear(Object v) {
        return toYearValue(v);
    }

    /**
     * 游标值的规范化：水位列的值在写进 {@code sync_task.cursor_value} 之前必须先归一，
     * 否则 {@code YEAR}（驱动返回 {@code java.sql.Date}）会被格式化成 {@code 2024-01-01}，
     * 下次解析水位时就会当成时间戳，语义直接错位。
     *
     * @param sourceMeta 水位列元数据（决定怎么归一）
     */
    public static Object normalizeForCursor(Object value, ColumnMeta sourceMeta) {
        if (value == null || sourceMeta == null) {
            return value;
        }
        String typeName = upper(sourceMeta.typeName());
        if (typeName.startsWith("YEAR")) {
            return toYearValue(value);
        }
        return value;
    }

    /** YEAR 值 → 年份整数（Date/Number/String 都能吃）。 */
    public static Integer toYearValue(Object v) {
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().getYear();
        }
        if (v instanceof LocalDate ld) {
            return ld.getYear();
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.getYear();
        }
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v instanceof String s) {
            String t = s.trim();
            try {
                return new BigDecimal(t).intValue();
            } catch (NumberFormatException ignore) {
                return ValueText.parseLocalDate(t).getYear();
            }
        }
        throw new ValueConversionException("无法把 YEAR 值[" + v + "](" + v.getClass().getName() + ")转换为年份");
    }

    // ------------------------------------------------------------------
    // 目标侧转换
    // ------------------------------------------------------------------

    private Object toTarget(Object v, ColumnMeta src, ColumnMeta tgt) {
        // YEAR 目标必须写整数年份：MySQL 的 YEAR 列在以 DATE 形态绑定时会报
        // "Data truncated for column 'yr'"，而驱动把 YEAR 也报成 jdbcType=DATE，
        // 所以只能靠类型名区分（真机验证过：Integer/String/Short 可用，LocalDate 不行）。
        if ("YEAR".equals(TypeNames.base(tgt.typeName()))) {
            return toYearValue(v instanceof Boolean b ? (b ? 1 : 0) : v);
        }
        return switch (tgt.jdbcType()) {
            case Types.BOOLEAN, Types.BIT -> toBooleanOrRaw(v, src, tgt);
            case Types.TINYINT, Types.SMALLINT, Types.INTEGER -> toInteger(v, src, tgt);
            case Types.BIGINT -> toLong(v, src, tgt);
            case Types.DECIMAL, Types.NUMERIC -> toDecimal(v, src, tgt);
            case Types.FLOAT, Types.REAL, Types.DOUBLE -> toDouble(v, src, tgt);
            case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR, Types.NCHAR, Types.NVARCHAR,
                 Types.LONGNVARCHAR, Types.CLOB, Types.NCLOB -> toText(v, tgt);
            case Types.DATE -> toLocalDate(v, src, tgt);
            case Types.TIME -> toLocalTime(v, src, tgt);
            case Types.TIMESTAMP -> toLocalDateTime(v, src, tgt);
            case Types.TIMESTAMP_WITH_TIMEZONE, Types.TIME_WITH_TIMEZONE -> toOffsetDateTime(v, src, tgt);
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> toBytes(v, tgt);
            default -> byTypeName(v, src, tgt);
        };
    }

    /** 驱动未给出标准 jdbcType 时，按类型名兜底（DM8 的 NUMBER/RAW 等）。 */
    private Object byTypeName(Object v, ColumnMeta src, ColumnMeta tgt) {
        String t = upper(tgt.typeName());
        if (t.startsWith("VARCHAR") || t.startsWith("CHAR") || t.contains("TEXT") || t.contains("CLOB")) {
            return toText(v, tgt);
        }
        if (t.contains("BLOB") || t.equals("RAW") || t.startsWith("BINARY") || t.startsWith("VARBINARY")) {
            return toBytes(v, tgt);
        }
        if (t.equals("DATE")) {
            return toLocalDate(v, src, tgt);
        }
        if (t.contains("TIMESTAMP") || t.equals("DATETIME") || t.contains("DATETIME")) {
            return toLocalDateTime(v, src, tgt);
        }
        if (t.equals("TIME") || t.startsWith("TIME(")) {
            return toLocalTime(v, src, tgt);
        }
        if (t.equals("YEAR")) {
            return toYear(v);
        }
        if (t.equals("NUMBER") || t.equals("DECIMAL") || t.equals("NUMERIC")) {
            return toDecimal(v, src, tgt);
        }
        if (t.contains("INT")) {
            return toLong(v, src, tgt);
        }
        if (t.equals("FLOAT") || t.equals("DOUBLE") || t.equals("REAL")) {
            return toDouble(v, src, tgt);
        }
        // 未知目标类型：不做转换，交给驱动（用户自定义类型等）
        return v;
    }

    /**
     * 布尔/位目标：BIT(1) 归一为 Boolean；BIT(n&gt;1) 的 byte[] 原样透传（不能被误判成布尔）。
     */
    private Object toBooleanOrRaw(Object v, ColumnMeta src, ColumnMeta tgt) {
        Boolean b = tryBoolean(v);
        if (b != null) {
            return b;
        }
        if (v instanceof byte[] && src != null && tgt.jdbcType() == Types.BIT
                && upper(src.typeName()).startsWith("BIT")) {
            return v;
        }
        throw new ValueConversionException("无法把[" + v + "](" + typeLabel(src) + ")转换为布尔值写入 "
                + typeLabel(tgt));
    }

    /** 尽力转布尔；确实不是布尔语义时返回 null（而不是抛错）。 */
    private Boolean tryBoolean(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        if (v instanceof BitSet bs) {
            return !bs.isEmpty() && bs.get(0);
        }
        if (v instanceof byte[] bytes) {
            if (bytes.length == 0) {
                return Boolean.FALSE;
            }
            if (bytes.length == 1) {
                // MySQL BIT(1) 的标准返回形式
                return bytes[0] != 0;
            }
            return null;
        }
        if (v instanceof Number n) {
            return n.doubleValue() != 0d;
        }
        if (v instanceof String s) {
            String t = s.trim().toLowerCase(Locale.ROOT);
            return switch (t) {
                case "1", "true", "t", "y", "yes", "on" -> Boolean.TRUE;
                case "0", "false", "f", "n", "no", "off", "" -> Boolean.FALSE;
                default -> null;
            };
        }
        return null;
    }

    private Integer toInteger(Object v, ColumnMeta src, ColumnMeta tgt) {
        if (v instanceof Boolean b) {
            return b ? 1 : 0;
        }
        BigDecimal dec = asDecimal(v, src, tgt);
        if (dec != null) {
            return narrowToInt(dec, src, tgt);
        }
        throw convFail(v, src, tgt);
    }

    private Long toLong(Object v, ColumnMeta src, ColumnMeta tgt) {
        if (v instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        BigDecimal dec = asDecimal(v, src, tgt);
        if (dec != null) {
            BigDecimal rounded = dec.setScale(0, RoundingMode.HALF_UP);
            checkIntegerRange(rounded, tgt);
            try {
                return rounded.longValueExact();
            } catch (ArithmeticException e) {
                throw new ValueConversionException("数值[" + dec.toPlainString() + "]超出目标列 "
                        + typeLabel(tgt) + " 的取值范围", e);
            }
        }
        throw convFail(v, src, tgt);
    }

    /** 缩小到整型：显式四舍五入 + 显式范围校验，绝不依赖驱动的静默截断。 */
    private Integer narrowToInt(BigDecimal dec, ColumnMeta src, ColumnMeta tgt) {
        BigDecimal rounded = dec.setScale(0, RoundingMode.HALF_UP);
        checkIntegerRange(rounded, tgt);
        try {
            return rounded.intValueExact();
        } catch (ArithmeticException e) {
            throw new ValueConversionException("数值[" + dec.toPlainString() + "]超出目标列 "
                    + typeLabel(tgt) + " 的取值范围", e);
        }
    }

    /** TINYINT/SMALLINT/INT 的范围校验（MySQL 非严格模式会静默截断，必须在这里拦住）。 */
    private void checkIntegerRange(BigDecimal rounded, ColumnMeta tgt) {
        BigInteger[] bounds = integerBounds(tgt.jdbcType());
        if (bounds == null) {
            return;
        }
        BigInteger v = rounded.toBigIntegerExact();
        if (v.compareTo(bounds[0]) < 0 || v.compareTo(bounds[1]) > 0) {
            throw new ValueConversionException("数值[" + rounded.toPlainString() + "]超出目标列 "
                    + typeLabel(tgt) + " 的取值范围 [" + bounds[0] + ", " + bounds[1] + "]");
        }
    }

    private static BigInteger[] integerBounds(int jdbcType) {
        return switch (jdbcType) {
            case Types.TINYINT -> new BigInteger[]{BigInteger.valueOf(-128), BigInteger.valueOf(127)};
            case Types.SMALLINT -> new BigInteger[]{BigInteger.valueOf(-32768), BigInteger.valueOf(32767)};
            default -> null;
        };
    }

    private BigDecimal toDecimal(Object v, ColumnMeta src, ColumnMeta tgt) {
        BigDecimal dec;
        if (v instanceof BigDecimal bd) {
            dec = bd;
        } else if (v instanceof Boolean b) {
            dec = b ? BigDecimal.ONE : BigDecimal.ZERO;
        } else if (v instanceof Number n) {
            dec = new BigDecimal(n.toString());
        } else if (v instanceof String s) {
            dec = parseNumber(s, tgt);
        } else {
            throw convFail(v, src, tgt);
        }
        if (tgt.columnSize() <= 0) {
            return dec;
        }
        BigDecimal scaled = dec.setScale(tgt.decimalDigits(), RoundingMode.HALF_UP);
        int intDigits = scaled.precision() - scaled.scale();
        int maxIntDigits = tgt.columnSize() - tgt.decimalDigits();
        if (intDigits > maxIntDigits) {
            throw new ValueConversionException("数值[" + dec.toPlainString() + "]整数位(" + intDigits
                    + ")超出目标列 " + typeLabel(tgt) + " 可容纳的整数位(" + maxIntDigits + ")");
        }
        return scaled;
    }

    private Double toDouble(Object v, ColumnMeta src, ColumnMeta tgt) {
        if (v instanceof Boolean b) {
            return b ? 1d : 0d;
        }
        BigDecimal dec = asDecimal(v, src, tgt);
        if (dec != null) {
            return dec.doubleValue();
        }
        throw convFail(v, src, tgt);
    }

    /** 尽力把值看成数值；不是数值语义时返回 null。 */
    private BigDecimal asDecimal(Object v, ColumnMeta src, ColumnMeta tgt) {
        if (v instanceof BigDecimal bd) {
            return bd;
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof String s) {
            return parseNumber(s, tgt);
        }
        return null;
    }

    private BigDecimal parseNumber(String s, ColumnMeta tgt) {
        String t = s.trim();
        if (t.isEmpty()) {
            throw new ValueConversionException("空字符串无法写入数值列 " + typeLabel(tgt));
        }
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            throw new ValueConversionException("无法把字符串[" + s + "]解析为数值写入 " + typeLabel(tgt), e);
        }
    }

    private String toText(Object v, ColumnMeta tgt) {
        String s = ValueText.format(v);
        if (s != null && tgt != null && tgt.columnSize() > 0 && isLengthBounded(tgt)
                && s.length() > tgt.columnSize()) {
            throw new ValueConversionException("字符串长度 " + s.length() + " 超过目标列 " + typeLabel(tgt)
                    + " 的最大长度 " + tgt.columnSize() + "（拒绝静默截断，请扩大目标列或先在源侧处理）");
        }
        return s;
    }

    /** 是否是有长度上限的字符类型（TEXT/CLOB 不算）。 */
    private static boolean isLengthBounded(ColumnMeta tgt) {
        String base = com.datasync.core.mapper.TypeNames.base(tgt.typeName());
        return base.equals("CHAR") || base.equals("VARCHAR") || base.equals("NCHAR")
                || base.equals("NVARCHAR") || base.equals("VARCHAR2") || base.equals("NVARCHAR2");
    }

    private LocalDate toLocalDate(Object v, ColumnMeta src, ColumnMeta tgt) {
        if (v instanceof LocalDate ld) {
            return ld;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.toLocalDate();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalDate();
        }
        if (v instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()).toLocalDate();
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toLocalDate();
        }
        if (v instanceof String s) {
            return ValueText.parseLocalDateTime(s).toLocalDate();
        }
        // YEAR 源列 → 日期目标：取该年 1 月 1 日（YEAR 本身没有月日信息）
        if (isYearSource(src) && v instanceof Number n) {
            return LocalDate.of(n.intValue(), 1, 1);
        }
        throw convFail(v, src, tgt);
    }

    private LocalTime toLocalTime(Object v, ColumnMeta src, ColumnMeta tgt) {
        if (v instanceof LocalTime lt) {
            return lt;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.toLocalTime();
        }
        if (v instanceof java.sql.Time t) {
            return t.toLocalTime();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().toLocalTime();
        }
        if (v instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault()).toLocalTime();
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toLocalTime();
        }
        if (v instanceof String s) {
            return ValueText.parseLocalDateTime(s).toLocalTime();
        }
        throw convFail(v, src, tgt);
    }

    private LocalDateTime toLocalDateTime(Object v, ColumnMeta src, ColumnMeta tgt) {
        if (v instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (v instanceof LocalDate ld) {
            return ld.atStartOfDay();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        if (v instanceof java.sql.Date d) {
            return d.toLocalDate().atStartOfDay();
        }
        if (v instanceof java.sql.Time t) {
            return LocalDate.of(1970, 1, 1).atTime(t.toLocalTime());
        }
        if (v instanceof java.util.Date d) {
            return LocalDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
        }
        if (v instanceof OffsetDateTime odt) {
            return odt.toLocalDateTime();
        }
        if (v instanceof String s) {
            return ValueText.parseLocalDateTime(s);
        }
        if (isYearSource(src) && v instanceof Number n) {
            return LocalDate.of(n.intValue(), 1, 1).atStartOfDay();
        }
        throw convFail(v, src, tgt);
    }

    private OffsetDateTime toOffsetDateTime(Object v, ColumnMeta src, ColumnMeta tgt) {
        if (v instanceof OffsetDateTime odt) {
            return odt;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (v instanceof LocalDate ld) {
            return ld.atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (v instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime().atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (v instanceof java.util.Date d) {
            return OffsetDateTime.ofInstant(d.toInstant(), ZoneId.systemDefault());
        }
        if (v instanceof String s) {
            return ValueText.parseLocalDateTime(s).atZone(ZoneId.systemDefault()).toOffsetDateTime();
        }
        throw convFail(v, src, tgt);
    }

    private byte[] toBytes(Object v, ColumnMeta tgt) {
        byte[] bytes;
        if (v instanceof byte[] b) {
            bytes = b;
        } else if (v instanceof BitSet bs) {
            bytes = bs.toByteArray();
        } else if (v instanceof String s) {
            bytes = s.getBytes(StandardCharsets.UTF_8);
        } else if (v instanceof Boolean b) {
            bytes = new byte[]{(byte) (b ? 1 : 0)};
        } else if (v instanceof Number n) {
            bytes = n.toString().getBytes(StandardCharsets.UTF_8);
        } else {
            bytes = ValueText.format(v).getBytes(StandardCharsets.UTF_8);
        }
        if (tgt != null && tgt.columnSize() > 0 && isFixedBinary(tgt) && bytes.length > tgt.columnSize()) {
            throw new ValueConversionException("二进制长度 " + bytes.length + " 超过目标列 " + typeLabel(tgt)
                    + " 的最大长度 " + tgt.columnSize() + "（拒绝静默截断）");
        }
        return bytes;
    }

    private static boolean isFixedBinary(ColumnMeta tgt) {
        String base = TypeNames.base(tgt.typeName());
        return base.equals("BINARY") || base.equals("VARBINARY") || base.equals("RAW");
    }

    // ------------------------------------------------------------------
    // 类型兼容性（预检 TYPE_INCOMPATIBLE 用）
    // ------------------------------------------------------------------

    /**
     * 源列能否安全转换到目标列。
     *
     * <p>委托 {@link TypeCompatibility}（预检与运行时共用的唯一能力表）。
     *
     * @deprecated 直接用 {@link TypeCompatibility#canConvert(ColumnMeta, ColumnMeta)}，
     *         需要 WARN 级提示时用 {@link TypeCompatibility#check(ColumnMeta, ColumnMeta)}。
     */
    @Deprecated
    public static boolean isCompatible(ColumnMeta source, ColumnMeta target) {
        return TypeCompatibility.canConvert(source, target);
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static boolean isBooleanTarget(ColumnMeta tgt) {
        return tgt.jdbcType() == Types.BOOLEAN || tgt.jdbcType() == Types.BIT
                || upper(tgt.typeName()).startsWith("BOOL");
    }

    private static boolean isBinaryTarget(ColumnMeta tgt) {
        return tgt.isBinary() || upper(tgt.typeName()).contains("BLOB")
                || upper(tgt.typeName()).startsWith("BINARY") || upper(tgt.typeName()).startsWith("VARBINARY");
    }

    private static boolean isYearSource(ColumnMeta src) {
        return src != null && upper(src.typeName()).startsWith("YEAR");
    }

    private static String upper(String s) {
        return s == null ? "" : s.toUpperCase(Locale.ROOT);
    }

    private static String typeLabel(ColumnMeta c) {
        if (c == null) {
            return "未知列";
        }
        return c.name() + "(" + c.typeName() + ")";
    }

    private ValueConversionException convFail(Object v, ColumnMeta src, ColumnMeta tgt) {
        return new ValueConversionException("无法把值[" + v + "](" + (v == null ? "null" : v.getClass().getSimpleName())
                + ", 源列 " + typeLabel(src) + ")转换为目标列 " + typeLabel(tgt));
    }
}
