package com.datasync.core.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.jdbc.ColumnMeta;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.BitSet;
import java.util.Date;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 值转换边界测试（任务要求 5）。
 *
 * <p>这些组合正是旧实现真实写坏过的数据：YEAR 被当日期/字符串、BIT 被原样塞进目标、
 * null 用 setObject 写、超长字符串被静默截断、整数溢出被静默截断。
 */
class DefaultValueConverterTest {

    private final DefaultValueConverter conv = new DefaultValueConverter();

    private static ColumnMeta col(String name, String typeName, int jdbcType) {
        return new ColumnMeta(name, typeName, jdbcType, true, false, 0);
    }

    private static ColumnMeta sized(String name, String typeName, int jdbcType, int size, int scale) {
        return new ColumnMeta(name, typeName, jdbcType, true, false, 0, size, scale);
    }

    @Test
    @DisplayName("null 一律返回 null（由引擎用 setNull 落库）")
    void nullStaysNull() {
        assertNull(conv.convert(null, col("a", "VARCHAR", Types.VARCHAR), col("b", "INT", Types.INTEGER)));
        assertNull(conv.convert(null, null, null));
    }

    @Test
    @DisplayName("目标元数据缺失时原样透传")
    void withoutTargetMeta() {
        assertEquals("x", conv.convert("x", col("a", "VARCHAR", Types.VARCHAR), null));
    }

    @Test
    @DisplayName("BIT(1)：byte[]{1} → Boolean.TRUE，byte[]{0} → FALSE，BitSet 也支持")
    void bitOneToBoolean() {
        ColumnMeta src = col("flag", "BIT", Types.BIT);
        ColumnMeta tgt = col("flag", "BIT", Types.BIT);
        assertEquals(Boolean.TRUE, conv.convert(new byte[]{1}, src, tgt));
        assertEquals(Boolean.FALSE, conv.convert(new byte[]{0}, src, tgt));

        BitSet bs = new BitSet();
        bs.set(0);
        assertEquals(Boolean.TRUE, conv.convert(bs, src, tgt));
        assertEquals(Boolean.TRUE, conv.convert(Boolean.TRUE, src, tgt));
    }

    @Test
    @DisplayName("BIT(1) → TINYINT/INT/VARCHAR 的语义化落值")
    void bitToOthers() {
        ColumnMeta src = col("flag", "BIT", Types.BIT);
        assertEquals(1, conv.convert(new byte[]{1}, src, col("n", "TINYINT", Types.TINYINT)));
        assertEquals(0, conv.convert(new byte[]{0}, src, col("n", "INT", Types.INTEGER)));
        assertEquals("1", conv.convert(new byte[]{1}, src, col("s", "VARCHAR", Types.VARCHAR)));
    }

    @Test
    @DisplayName("BIT(n>1) 的 byte[] 不能被误判成布尔")
    void bitMultiBytePassThrough() {
        ColumnMeta src = col("bits", "BIT", Types.BIT);
        ColumnMeta tgt = col("bits", "BIT", Types.BIT);
        byte[] raw = new byte[]{1, 2, 3};
        Object out = conv.convert(raw, src, tgt);
        assertInstanceOf(byte[].class, out);
        assertEquals(3, ((byte[]) out).length);
    }

    @Test
    @DisplayName("YEAR：驱动返回 java.sql.Date，必须转成年份数字（旧实现当日期/字符串写坏了）")
    void yearHandling() {
        ColumnMeta src = col("y", "YEAR", Types.DATE);
        // MySQL YEAR 列在驱动里默认是 java.sql.Date（2024-01-01）
        Object value = new java.sql.Date(LocalDate.of(2024, 1, 1).toEpochDay() * 86400000L);
        assertEquals(2024, conv.convert(value, src, col("y", "INT", Types.INTEGER)));
        assertEquals("2024", conv.convert(value, src, col("y", "VARCHAR", Types.VARCHAR)));
        assertEquals(LocalDate.of(2024, 1, 1),
                conv.convert(value, src, col("y", "DATE", Types.DATE)));
        assertEquals(2024L, conv.convert(2024, src, col("y", "BIGINT", Types.BIGINT)));
        assertEquals(2024, conv.convert("2024", src, col("y", "INT", Types.INTEGER)));
    }

    @Test
    @DisplayName("JSR-310 与 java.sql 时间类型互转")
    void jsr310() {
        LocalDateTime ldt = LocalDateTime.of(2026, 1, 2, 3, 4, 5);
        assertEquals(ldt, conv.convert(ldt, col("t", "DATETIME", Types.TIMESTAMP),
                col("t", "TIMESTAMP", Types.TIMESTAMP)));
        assertEquals(ldt, conv.convert(Timestamp.valueOf(ldt), col("t", "DATETIME", Types.TIMESTAMP),
                col("t", "DATETIME", Types.TIMESTAMP)));
        assertEquals(ldt.toLocalDate(), conv.convert(ldt, col("t", "DATETIME", Types.TIMESTAMP),
                col("d", "DATE", Types.DATE)));
        assertEquals(ldt.toLocalTime(), conv.convert(ldt, col("t", "DATETIME", Types.TIMESTAMP),
                col("tm", "TIME", Types.TIME)));
        assertEquals(LocalDate.of(2026, 1, 2).atStartOfDay(),
                conv.convert(LocalDate.of(2026, 1, 2), col("d", "DATE", Types.DATE),
                        col("t", "DATETIME", Types.TIMESTAMP)));
        assertEquals(ldt, conv.convert(OffsetDateTime.of(ldt, ZoneOffset.ofHours(8)),
                col("t", "TIMESTAMP", Types.TIMESTAMP), col("t", "DATETIME", Types.TIMESTAMP)));
        assertEquals(ldt, conv.convert("2026-01-02 03:04:05", col("s", "VARCHAR", Types.VARCHAR),
                col("t", "DATETIME", Types.TIMESTAMP)));
        assertEquals(ldt, conv.convert("2026-01-02T03:04:05", col("s", "VARCHAR", Types.VARCHAR),
                col("t", "DATETIME", Types.TIMESTAMP)));
    }

    @Test
    @DisplayName("时间 → 字符串用可读格式，且不含科学计数法")
    void temporalToText() {
        assertEquals("2026-01-02 03:04:05",
                conv.convert(LocalDateTime.of(2026, 1, 2, 3, 4, 5), col("t", "DATETIME", Types.TIMESTAMP),
                        col("s", "VARCHAR", Types.VARCHAR)));
        assertEquals("2026-01-02 03:04:05.123",
                conv.convert(LocalDateTime.of(2026, 1, 2, 3, 4, 5, 123_000_000),
                        col("t", "DATETIME", Types.TIMESTAMP), col("s", "VARCHAR", Types.VARCHAR)));
        assertEquals("2026-01-02", conv.convert(LocalDate.of(2026, 1, 2), col("d", "DATE", Types.DATE),
                col("s", "VARCHAR", Types.VARCHAR)));
        assertEquals("03:04:05", conv.convert(LocalTime.of(3, 4, 5), col("t", "TIME", Types.TIME),
                col("s", "VARCHAR", Types.VARCHAR)));
    }

    @Test
    @DisplayName("BigDecimal：转字符串用 toPlainString（避免 1E+2）")
    void bigDecimalText() {
        assertEquals("100", conv.convert(new BigDecimal("1E+2"), col("n", "DECIMAL", Types.DECIMAL),
                col("s", "VARCHAR", Types.VARCHAR)));
        assertEquals(new BigDecimal("123.45"), conv.convert(new BigDecimal("123.45"),
                col("n", "DECIMAL", Types.DECIMAL), col("n", "DECIMAL", Types.DECIMAL)));
        assertEquals(new BigDecimal("42"), conv.convert(42, col("n", "INT", Types.INTEGER),
                col("n", "DECIMAL", Types.DECIMAL)));
    }

    @Test
    @DisplayName("小数 → 整型：显式四舍五入（不是静默截断）")
    void decimalToInt() {
        assertEquals(2, conv.convert(new BigDecimal("1.5"), col("n", "DECIMAL", Types.DECIMAL),
                col("n", "INT", Types.INTEGER)));
        assertEquals(-2, conv.convert(new BigDecimal("-1.5"), col("n", "DECIMAL", Types.DECIMAL),
                col("n", "INT", Types.INTEGER)));
    }

    @Test
    @DisplayName("整型溢出：必须抛异常（旧实现会静默截断）")
    void integerOverflow() {
        ValueConversionException e1 = assertThrows(ValueConversionException.class,
                () -> conv.convert(3_000_000_000L, col("n", "BIGINT", Types.BIGINT),
                        col("n", "INT", Types.INTEGER)));
        assertTrue(e1.getMessage().contains("超出"), e1.getMessage());
        assertThrows(ValueConversionException.class,
                () -> conv.convert(200, col("n", "INT", Types.INTEGER), col("n", "TINYINT", Types.TINYINT)));
        assertThrows(ValueConversionException.class,
                () -> conv.convert(40000, col("n", "INT", Types.INTEGER), col("n", "SMALLINT", Types.SMALLINT)));
    }

    @Test
    @DisplayName("DECIMAL 精度溢出：必须抛异常")
    void decimalPrecision() {
        ColumnMeta tgt = sized("n", "DECIMAL", Types.DECIMAL, 10, 2);
        assertEquals(new BigDecimal("12345678.13"),
                conv.convert(new BigDecimal("12345678.128"), col("n", "DECIMAL", Types.DECIMAL), tgt));
        ValueConversionException e = assertThrows(ValueConversionException.class,
                () -> conv.convert(new BigDecimal("123456789.12"), col("n", "DECIMAL", Types.DECIMAL), tgt));
        assertTrue(e.getMessage().contains("整数位"), e.getMessage());
    }

    @Test
    @DisplayName("字符串大写/超长：必须抛异常（拒绝静默截断）")
    void textLength() {
        ColumnMeta tgt = sized("s", "VARCHAR", Types.VARCHAR, 5, 0);
        assertEquals("12345", conv.convert("12345", col("s", "VARCHAR", Types.VARCHAR), tgt));
        ValueConversionException e = assertThrows(ValueConversionException.class,
                () -> conv.convert("123456", col("s", "VARCHAR", Types.VARCHAR), tgt));
        assertTrue(e.getMessage().contains("最大长度"), e.getMessage());
        // CLOB/TEXT 目标不受长度限制
        assertEquals("123456", conv.convert("123456", col("s", "VARCHAR", Types.VARCHAR),
                col("s", "TEXT", Types.CLOB)));
    }

    @Test
    @DisplayName("字符串 → 数值：可解析则转换，不可解析抛异常（不是驱动抛的看不懂的异常）")
    void stringToNumber() {
        assertEquals(123, conv.convert("123", col("s", "VARCHAR", Types.VARCHAR),
                col("n", "INT", Types.INTEGER)));
        assertEquals(new BigDecimal("1.25"), conv.convert("1.25", col("s", "VARCHAR", Types.VARCHAR),
                col("n", "DECIMAL", Types.DECIMAL)));
        ValueConversionException e = assertThrows(ValueConversionException.class,
                () -> conv.convert("abc", col("s", "VARCHAR", Types.VARCHAR),
                        col("n", "INT", Types.INTEGER)));
        assertTrue(e.getMessage().contains("解析为数值"), e.getMessage());
    }

    @Test
    @DisplayName("byte[] 与字符/二进制目标")
    void bytes() {
        byte[] raw = "中文".getBytes(StandardCharsets.UTF_8);
        assertEquals("中文", conv.convert(raw, col("b", "BLOB", Types.BLOB),
                col("s", "VARCHAR", Types.VARCHAR)));
        assertEquals("abc", new String((byte[]) conv.convert("abc", col("s", "VARCHAR", Types.VARCHAR),
                col("b", "BLOB", Types.BLOB)), StandardCharsets.UTF_8));
        // VARBINARY 长度上限
        assertThrows(ValueConversionException.class,
                () -> conv.convert("abcdef", col("s", "VARCHAR", Types.VARCHAR),
                        sized("b", "VARBINARY", Types.VARBINARY, 3, 0)));
    }

    @Test
    @DisplayName("布尔 → 数值/字符串")
    void booleanToOthers() {
        ColumnMeta src = col("b", "BOOLEAN", Types.BOOLEAN);
        assertEquals(1, conv.convert(Boolean.TRUE, src, col("n", "INT", Types.INTEGER)));
        assertEquals(0L, conv.convert(Boolean.FALSE, src, col("n", "BIGINT", Types.BIGINT)));
        assertEquals("1", conv.convert(Boolean.TRUE, src, col("s", "VARCHAR", Types.VARCHAR)));
        assertEquals(BigDecimal.ONE, conv.convert(Boolean.TRUE, src, col("n", "DECIMAL", Types.DECIMAL)));
    }

    @Test
    @DisplayName("不可转换：时间 → 数值 抛异常")
    void incompatibleThrows() {
        assertThrows(ValueConversionException.class,
                () -> conv.convert(LocalDateTime.now(), col("t", "DATETIME", Types.TIMESTAMP),
                        col("n", "INT", Types.INTEGER)));
        assertThrows(ValueConversionException.class,
                () -> conv.convert(new Date(), col("t", "TIMESTAMP", Types.TIMESTAMP),
                        col("n", "BIGINT", Types.BIGINT)));
    }

    @Test
    @DisplayName("canConvert 与预检共用同一张能力表")
    void canConvertShared() {
        assertTrue(conv.canConvert(col("s", "VARCHAR", Types.VARCHAR), col("n", "INT", Types.INTEGER)));
        assertFalse(conv.canConvert(col("s", "TEXT", Types.CLOB), col("n", "INT", Types.INTEGER)));
        // 已废弃的静态入口委托同一张表（DATETIME→DATE 属 RISKY，可转换）
        assertTrue(DefaultValueConverter.isCompatible(col("t", "DATETIME", Types.TIMESTAMP),
                col("d", "DATE", Types.DATE)));
    }
}
