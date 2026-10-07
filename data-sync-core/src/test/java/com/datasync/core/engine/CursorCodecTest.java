package com.datasync.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.jdbc.ColumnMeta;
import com.datasync.core.mapper.ValueText;
import java.math.BigDecimal;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 复合游标编解码（Lead 决策 1）。
 */
class CursorCodecTest {

    private static final ColumnMeta TS =
            new ColumnMeta("updated_at", "DATETIME", Types.TIMESTAMP, true, false, 2);
    private static final ColumnMeta BIGINT =
            new ColumnMeta("id", "BIGINT", Types.BIGINT, false, true, 1);
    private static final ColumnMeta YEAR =
            new ColumnMeta("y", "YEAR", Types.DATE, true, false, 1);

    @Test
    @DisplayName("标量游标：排序键只有增量列本身")
    void scalarCursor() {
        String text = CursorCodec.encode(12345, BIGINT, List.of(), List.of());
        assertEquals("12345", text);
        assertFalse(CursorCodec.isComposite(text));
        assertEquals("12345", CursorCodec.scalarOf(text));
        assertTrue(CursorCodec.tiePartsOf(text).isEmpty());
    }

    @Test
    @DisplayName("复合游标：增量列不唯一时带上 tie-breaker 分量（竖线分隔，运维可读）")
    void compositeCursor() {
        String text = CursorCodec.encode(LocalDateTime.of(2026, 1, 2, 3, 4, 5), TS, List.of("id"), List.of(7));
        assertEquals("2026-01-02 03:04:05|7", text);
        assertTrue(CursorCodec.isComposite(text));
        assertEquals("2026-01-02 03:04:05", CursorCodec.scalarOf(text));
        assertEquals(List.of("7"), CursorCodec.tiePartsOf(text));
        // 运维必须能直接读懂：不含 Base64/二进制/引号
        assertFalse(text.contains("{"));
        assertFalse(text.contains("\""));
    }

    @Test
    @DisplayName("复合游标 round-trip：编码 → 解码 → 按列类型还原，值完全一致")
    void compositeRoundTrip() {
        LocalDateTime incr = LocalDateTime.of(2026, 5, 30, 21, 1, 3, 762_127_000);
        ColumnMeta ts = new ColumnMeta("updated_at", "DATETIME", Types.TIMESTAMP, true, false, 2);
        ColumnMeta id = new ColumnMeta("id", "BIGINT", Types.BIGINT, false, true, 1);
        String text = CursorCodec.encode(incr, ts, List.of("id"), List.of(42L, 7));

        assertEquals("2026-05-30 21:01:03.762127|42|7", text);
        String scalarText = CursorCodec.scalarOf(text);
        assertEquals(incr, CursorCodec.parseComponent(scalarText, ts));
        List<String> tieParts = CursorCodec.tiePartsOf(text);
        assertEquals(2, tieParts.size());
        assertEquals(42L, CursorCodec.parseComponent(tieParts.get(0), id));
        assertEquals(7L, CursorCodec.parseComponent(tieParts.get(1), id));
        assertEquals(incr, ValueText.parseLocalDateTime(scalarText), "水位文本必须能无损反解");

        // 旧格式（JSON 数组）仍可读，避免升级期水位失效
        assertEquals("2026-05-30 21:01:03.762127",
                CursorCodec.scalarOf("[\"2026-05-30 21:01:03.762127\",\"42\"]"));
        assertEquals(List.of("42"), CursorCodec.tiePartsOf("[\"2026-05-30 21:01:03.762127\",\"42\"]"));
    }

    @Test
    @DisplayName("复合游标分量能按列类型还原")
    void componentParsing() {
        assertEquals(LocalDateTime.of(2026, 1, 2, 3, 4, 5),
                CursorCodec.parseComponent("2026-01-02 03:04:05", TS));
        assertEquals(7L, CursorCodec.parseComponent("7", BIGINT));
        assertEquals(2024, CursorCodec.parseComponent("2024", YEAR));
        assertEquals(new BigDecimal("9.99"),
                CursorCodec.parseComponent("9.99",
                        new ColumnMeta("n", "DECIMAL", Types.DECIMAL, true, false, 1)));
        assertEquals("abc", CursorCodec.parseComponent("abc",
                new ColumnMeta("s", "VARCHAR", Types.VARCHAR, true, false, 1)));
        assertNull(CursorCodec.parseComponent(null, TS));
        assertThrows(IllegalArgumentException.class, () -> CursorCodec.parseComponent("abc", BIGINT));
    }

    @Test
    @DisplayName("历史/手工写入的标量与非法形态都不抛错")
    void tolerantParsing() {
        assertEquals("2026-01-01 10:00:00", CursorCodec.scalarOf("2026-01-01 10:00:00"));
        assertEquals("12345", CursorCodec.scalarOf("12345"));
        // 残缺 JSON：退回标量语义（宁可多读，绝不丢数）
        assertEquals("[1,2", CursorCodec.scalarOf("[1,2"));
        assertFalse(CursorCodec.isComposite(null));
        assertNull(CursorCodec.scalarOf(null));
        assertTrue(CursorCodec.tiePartsOf("[1,2").isEmpty());
        // 手工改坏的水位（多段）也只取第一段，不抛错
        assertEquals("100", CursorCodec.scalarOf("100|bad|extra"));
        assertTrue(CursorCodec.isComposite("100|1"));
        assertFalse(CursorCodec.isComposite("100"));
    }

    @Test
    @DisplayName("水位回看：时间型按秒减、数值型按数值减")
    void lookback() {
        assertEquals(LocalDateTime.of(2026, 1, 2, 2, 59, 5),
                CursorCodec.minusSeconds(LocalDateTime.of(2026, 1, 2, 3, 4, 5), 300, TS));
        assertEquals(940L, CursorCodec.minusSeconds(1000L, 60, BIGINT));
        assertEquals(1000L, CursorCodec.minusSeconds(1000L, 0, BIGINT));
        assertEquals(new BigDecimal("10.5"),
                CursorCodec.minusSeconds(new BigDecimal("20.5"), 10,
                        new ColumnMeta("n", "DECIMAL", Types.DECIMAL, true, false, 1)));
    }

    @Test
    @DisplayName("数值收窄：按列类型选 Java 类型（避免 DECIMAL 绑到 INT 列上）")
    void narrow() {
        assertEquals(1L, CursorCodec.narrow(new BigDecimal("1"), BIGINT));
        assertEquals(1, CursorCodec.narrow(new BigDecimal("1"),
                new ColumnMeta("n", "INT", Types.INTEGER, true, false, 1)));
        assertEquals(new BigDecimal("1.5"), CursorCodec.narrow(new BigDecimal("1.5"),
                new ColumnMeta("n", "DECIMAL", Types.DECIMAL, true, false, 1)));
        assertEquals(2024, CursorCodec.narrow(new BigDecimal("2024"), YEAR));
    }

    @Test
    @DisplayName("YEAR 水位规范化为年份数字（否则会写成 2024-01-01）")
    void yearCursor() {
        java.sql.Date date = java.sql.Date.valueOf("2024-01-01");
        assertEquals("2024", CursorCodec.encode(date, YEAR, List.of(), List.of()));
        assertEquals("2024", ValueText.format(
                com.datasync.core.mapper.DefaultValueConverter.normalizeForCursor(date, YEAR)));
    }

    @Test
    @DisplayName("JSON 工具：转义与解析")
    void jsonLite() {
        assertEquals("\"a\\\"b\"", JsonLite.escape("a\"b"));
        assertEquals("[\"a\",\"b\"]", JsonLite.toArray(List.of("a", "b")));
        assertEquals(List.of("a", "b"), JsonLite.parseArray("[\"a\", \"b\"]"));
        assertTrue(JsonLite.looksLikeArray("[]"));
        assertFalse(JsonLite.looksLikeArray("[]x"));
        String json = JsonLite.toJson(new java.util.LinkedHashMap<>(java.util.Map.of("a", 1, "b", "x")));
        assertTrue(json.contains("\"a\":1"), json);
        assertTrue(json.contains("\"b\":\"x\""), json);
        assertThrows(IllegalArgumentException.class, () -> JsonLite.parseArray("nope"));
    }
}
