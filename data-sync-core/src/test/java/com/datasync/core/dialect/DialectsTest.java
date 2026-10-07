package com.datasync.core.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.model.enums.DbType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DialectsTest {

    @Test
    @DisplayName("按类型取方言且实例复用")
    void of() {
        assertEquals(DbType.MYSQL, Dialects.of(DbType.MYSQL).dbType());
        assertEquals(DbType.DM8, Dialects.of(DbType.DM8).dbType());
        assertSame(Dialects.of(DbType.MYSQL), Dialects.of(DbType.MYSQL));
    }

    @Test
    @DisplayName("null / 未注册类型抛中文异常")
    void unknown() {
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class, () -> Dialects.of(null));
        assertTrue(e1.getMessage().contains("不能为空"), e1.getMessage());
        assertTrue(Dialects.isSupported(DbType.MYSQL));
        assertFalse(Dialects.isSupported(null));
    }
}
