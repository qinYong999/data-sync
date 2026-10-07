package com.datasync.core.mapper;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.BitSet;

/**
 * 值的文本化与解析（水位的字符串表示、字符列写入、日志展示都走这里）。
 *
 * <p>时间统一用 {@code yyyy-MM-dd HH:mm:ss[.f]} 形式：带空格分隔符更贴近 MySQL/DM8 的字面量风格，
 * 小数位仅在存在时输出并保留原始精度，因此「格式化 → 写库 → 读回 → 解析」是无损的
 * （水位绝不会因为格式化而变大，最坏情况是变小后多读几行，而多读由幂等 upsert 兜底）。
 */
public final class ValueText {

    /** 兼容 {@code 2024-01-01 12:00:00}、{@code 2024-01-01T12:00:00.123}、{@code 2024-01-01}。 */
    private static final DateTimeFormatter FLEX = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .optionalStart()
            .appendLiteral(' ')
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .optionalEnd()
            .toFormatter();

    private ValueText() {
    }

    /** 值的标准文本形式；null 原样返回。 */
    public static String format(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof BigDecimal bd) {
            return bd.toPlainString();
        }
        if (value instanceof LocalDateTime ldt) {
            return formatLocalDateTime(ldt);
        }
        if (value instanceof LocalDate ld) {
            return ld.toString();
        }
        if (value instanceof LocalTime lt) {
            return lt.toString();
        }
        if (value instanceof OffsetDateTime odt) {
            return odt.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        }
        if (value instanceof java.sql.Timestamp ts) {
            return formatLocalDateTime(ts.toLocalDateTime());
        }
        if (value instanceof java.sql.Date d) {
            return d.toLocalDate().toString();
        }
        if (value instanceof java.sql.Time t) {
            return t.toLocalTime().toString();
        }
        if (value instanceof java.util.Date d) {
            return formatLocalDateTime(LocalDateTime.ofInstant(d.toInstant(), java.time.ZoneId.systemDefault()));
        }
        if (value instanceof Boolean b) {
            // 布尔统一用 1/0 表达，与 MySQL BIT/TINYINT(1) 一致
            return b ? "1" : "0";
        }
        if (value instanceof byte[] bytes) {
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        }
        if (value instanceof BitSet bs) {
            return bs.toString();
        }
        return value.toString();
    }

    /** {@code yyyy-MM-dd HH:mm:ss}（纳秒非 0 时补小数位，去尾零）。 */
    public static String formatLocalDateTime(LocalDateTime ldt) {
        String base = String.format("%04d-%02d-%02d %02d:%02d:%02d",
                ldt.getYear(), ldt.getMonthValue(), ldt.getDayOfMonth(),
                ldt.getHour(), ldt.getMinute(), ldt.getSecond());
        int nano = ldt.getNano();
        if (nano == 0) {
            return base;
        }
        String frac = String.format("%09d", nano);
        int end = frac.length();
        while (end > 1 && frac.charAt(end - 1) == '0') {
            end--;
        }
        return base + "." + frac.substring(0, end);
    }

    /** 解析时间文本，容忍 ISO 'T' 分隔与带时区偏移的写法。 */
    public static LocalDateTime parseLocalDateTime(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("时间值不能为空");
        }
        String s = text.trim();
        if (hasZoneSuffix(s)) {
            return OffsetDateTime.parse(s, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toLocalDateTime();
        }
        String normalized = s.replace('T', ' ').replace('t', ' ');
        return LocalDateTime.parse(normalized, FLEX);
    }

    /** 解析日期文本（只取日期部分）。 */
    public static LocalDate parseLocalDate(String text) {
        return parseLocalDateTime(text).toLocalDate();
    }

    private static boolean hasZoneSuffix(String s) {
        if (s.endsWith("Z") || s.endsWith("z")) {
            return true;
        }
        int t = s.indexOf('T') >= 0 ? s.indexOf('T') : s.indexOf(' ');
        if (t < 0) {
            return false;
        }
        String timePart = s.substring(t + 1);
        return timePart.contains("+") || (timePart.lastIndexOf('-') > 0);
    }

    /** 解析为 BigDecimal（水位回看计算用）。 */
    public static BigDecimal parseDecimal(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("数值不能为空");
        }
        try {
            return new BigDecimal(text.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("无法把[" + text + "]解析为数值水位", e);
        }
    }
}
