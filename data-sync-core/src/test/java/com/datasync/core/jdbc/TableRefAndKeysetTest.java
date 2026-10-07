package com.datasync.core.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class TableRefAndKeysetTest {

    @Test
    @DisplayName("TableRef 解析与限定名")
    void tableRef() {
        assertEquals("t", TableRef.parse("t").name());
        assertEquals("db.t", TableRef.parse("db.t").name());
        assertEquals("db.t", TableRef.parse("db.t").toString());
        assertEquals("db", TableRef.parse("db.t").schema());
        assertEquals("t", TableRef.parse("db.t").table());
        // 三段式只取最后两段（MySQL 三段写法在驱动层同样只认前两段）
        assertEquals("db.t", TableRef.parse("cat.db.t").name());
        assertEquals(TableRef.of("db", "t"), TableRef.of("db", "t"));
        assertEquals(TableRef.of("db", "t").hashCode(), TableRef.of("db", "t").hashCode());
        assertNotEquals(TableRef.of("db", "t"), TableRef.of("other", "t"));
        assertEquals("t", TableRef.of("db", "t").withoutSchema().name());
        assertThrows(IllegalArgumentException.class, () -> TableRef.parse("  "));
        assertTrue(TableRef.of("t").isBlank() == false);
    }

    @Test
    @DisplayName("KeysetPosition：first / of / 一致性校验")
    void keysetPosition() {
        assertTrue(KeysetPosition.first().isFirst());
        assertEquals(0, KeysetPosition.first().size());
        KeysetPosition p = KeysetPosition.of(List.of("a", "b"), List.of(1, 2));
        assertEquals(List.of("a", "b"), p.keyColumns());
        assertEquals(List.of(1, 2), p.values());
        assertEquals(2, p.size());
        assertTrue(p.toString().contains("a"));
        assertThrows(IllegalArgumentException.class,
                () -> KeysetPosition.of(List.of("a", "b"), List.of(1)));
        assertTrue(KeysetPosition.of(List.of(), List.of()).isFirst());
    }

    @Test
    @DisplayName("ColumnMeta 的类型判定")
    void columnMeta() {
        ColumnMeta bigint = ColumnMeta.of("id", java.sql.Types.BIGINT);
        assertTrue(bigint.isNumeric());
        assertTrue(bigint.isSortableWatermark());
        ColumnMeta dt = new ColumnMeta("t", "DATETIME", java.sql.Types.TIMESTAMP, false, false, 2);
        assertTrue(dt.isTemporal());
        assertTrue(dt.isSortableWatermark());
        ColumnMeta txt = new ColumnMeta("s", "TEXT", java.sql.Types.CLOB, true, false, 3);
        assertTrue(txt.isCharacter());
        assertTrue(!txt.isSortableWatermark());
        assertTrue(txt.nameIs("s"));
        assertTrue(bigint.withPrimaryKey(true).primaryKey());
        assertEquals("YEAR", new ColumnMeta("y", "YEAR", java.sql.Types.DATE, true, false, 0).typeName());
    }

    @Test
    @DisplayName("TableMeta.find 忽略大小写与限定前缀")
    void tableMetaFind() {
        ColumnMeta a = new ColumnMeta("Id", "BIGINT", java.sql.Types.BIGINT, false, true, 1);
        ColumnMeta b = new ColumnMeta("name", "VARCHAR", java.sql.Types.VARCHAR, true, false, 2);
        TableMeta meta = new TableMeta(TableRef.of("t"), List.of(a, b), List.of(a), List.of());
        assertEquals(a, meta.find("id"));
        assertEquals(a, meta.find("t.id"));
        assertEquals(b, meta.find("NAME"));
        assertEquals(null, meta.find("missing"));
        assertTrue(meta.hasPrimaryKey());
        assertEquals(List.of(a), meta.firstUsableKey());
        assertEquals(List.of("Id", "name"), meta.columnNames());
    }
}
