package com.datasync.server.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D7：重建列注释 DDL 时，{@code DEFAULT} 子句必须按类型正确转义。
 *
 * <p>旧实现直接拼接 {@code COLUMN_DEFAULT}，把字符串默认值写成 {@code DEFAULT TABLE}
 * → {@code ERROR 1064}，导致每次启动稳定刷 3 条 WARN（QA 实测：source_mode / status /
 * full_sync_strategy）。这些断言把每一种默认值形态钉死。</p>
 */
class DatabaseCommentInitializerTest {

    @Test
    @DisplayName("字符串型默认值必须加单引号（D7 根因：DEFAULT TABLE → DEFAULT 'TABLE'）")
    void stringDefaultIsQuoted() {
        assertThat(DatabaseCommentInitializer.defaultClause("varchar(20)", "TABLE")).isEqualTo(" DEFAULT 'TABLE'");
        assertThat(DatabaseCommentInitializer.defaultClause("varchar(20)", "DISABLED")).isEqualTo(" DEFAULT 'DISABLED'");
        assertThat(DatabaseCommentInitializer.defaultClause("varchar(16)", "TRUNCATE")).isEqualTo(" DEFAULT 'TRUNCATE'");
        assertThat(DatabaseCommentInitializer.defaultClause("varchar(20)", "CUSTOM_SQL"))
            .isEqualTo(" DEFAULT 'CUSTOM_SQL'");
    }

    @Test
    @DisplayName("字符串里的单引号必须转义，不能破坏 DDL")
    void embeddedQuoteIsEscaped() {
        assertThat(DatabaseCommentInitializer.defaultClause("varchar(50)", "it's"))
            .isEqualTo(" DEFAULT 'it''s'");
    }

    @Test
    @DisplayName("数值默认值不加引号")
    void numericDefaultIsBare() {
        assertThat(DatabaseCommentInitializer.defaultClause("bigint", "0")).isEqualTo(" DEFAULT 0");
        assertThat(DatabaseCommentInitializer.defaultClause("int", "1000")).isEqualTo(" DEFAULT 1000");
        assertThat(DatabaseCommentInitializer.defaultClause("tinyint(1)", "1")).isEqualTo(" DEFAULT 1");
        assertThat(DatabaseCommentInitializer.defaultClause("decimal(10,2)", "-1.50")).isEqualTo(" DEFAULT -1.50");
    }

    @Test
    @DisplayName("表达式/函数默认值不加引号（加了会被当成字符串字面量）")
    void expressionDefaultIsBare() {
        assertThat(DatabaseCommentInitializer.defaultClause("datetime(6)", "CURRENT_TIMESTAMP"))
            .isEqualTo(" DEFAULT CURRENT_TIMESTAMP");
        assertThat(DatabaseCommentInitializer.defaultClause("datetime(6)", "CURRENT_TIMESTAMP(6)"))
            .isEqualTo(" DEFAULT CURRENT_TIMESTAMP(6)");
        assertThat(DatabaseCommentInitializer.defaultClause("varchar(36)", "(uuid())"))
            .isEqualTo(" DEFAULT (uuid())");
    }

    @Test
    @DisplayName("NULL 与空默认值")
    void nullAndEmptyDefaults() {
        assertThat(DatabaseCommentInitializer.defaultClause("varchar(20)", null)).isEmpty();
        assertThat(DatabaseCommentInitializer.defaultClause("varchar(20)", "")).isEmpty();
        assertThat(DatabaseCommentInitializer.defaultClause("varchar(20)", "NULL")).isEqualTo(" DEFAULT NULL");
    }

    @Test
    @DisplayName("无符号/位类型默认值按字面量原样输出")
    void bitAndHexLiterals() {
        assertThat(DatabaseCommentInitializer.defaultClause("bit(1)", "b'0'")).isEqualTo(" DEFAULT b'0'");
    }
}
