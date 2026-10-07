package com.datasync.core.dialect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.jdbc.TableRef;
import com.datasync.core.model.enums.DbType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MySQL 方言 SQL 文本断言（生产主线，全部为文本级校验，不需要数据库）。
 */
class MySqlDialectTest {

    private final MySqlDialect d = new MySqlDialect();

    @Test
    @DisplayName("标识符引用与非法字符拦截")
    void quote() {
        assertEquals("`id`", d.quote("id"));
        assertEquals("`db`.`tbl`", d.quote("db.tbl"));
        assertEquals("`tbl`", d.qualifySchema(null, "tbl"));
        assertEquals("`db`.`tbl`", d.qualifySchema("db", "tbl"));
        assertEquals("`用户表`", d.quote("用户表"));
        assertThrows(IllegalArgumentException.class, () -> d.quote("t; DROP TABLE x"));
        assertThrows(IllegalArgumentException.class, () -> d.quote("t--"));
        assertThrows(IllegalArgumentException.class, () -> d.quote(""));
    }

    @Test
    @DisplayName("键集分页：单列键、首页无谓词")
    void keysetFirstPage() {
        String sql = d.buildKeysetSelect(TableRef.of("t"), List.of("id", "name"), List.of("id"),
                KeysetPosition.first(), 100, List.of());
        assertEquals("SELECT `id`, `name` FROM `t` ORDER BY `id` ASC LIMIT 100", sql);
    }

    @Test
    @DisplayName("键集分页：单列键续跑用简单比较")
    void keysetNextPage() {
        String sql = d.buildKeysetSelect(TableRef.of("t"), List.of("id", "name"), List.of("id"),
                KeysetPosition.of(List.of("id"), List.of(5)), 100, List.of());
        assertEquals("SELECT `id`, `name` FROM `t` WHERE `id` > ? ORDER BY `id` ASC LIMIT 100", sql);
    }

    @Test
    @DisplayName("键集分页：多列主键必须展开式 OR（行构造器不会下推索引，实测慢 8000 倍）")
    void keysetMultiColumnExpanded() {
        String sql = d.buildKeysetSelect(TableRef.of("t"), List.of("a", "b"), List.of("a", "b"),
                KeysetPosition.of(List.of("a", "b"), List.of(1, 2)), 50, List.of());
        assertEquals("SELECT `a`, `b` FROM `t`"
                + " WHERE ((`a` > ?) OR (`a` = ? AND `b` > ?))"
                + " ORDER BY `a` ASC, `b` ASC LIMIT 50", sql);
        // 谓词绝不能出现行构造器写法
        assertFalse(sql.contains("`a`, `b`) >"), "不得使用行构造器比较: " + sql);
    }

    @Test
    @DisplayName("键集分页：3 列键展开成 3 个分支、6 个占位符，参数按前缀重复")
    void keysetThreeColumnsExpanded() {
        KeysetPosition after = KeysetPosition.of(List.of("a", "b", "c"), List.of(1, 2, 3));
        String sql = d.buildKeysetSelect(TableRef.of("t"), List.of("a", "b", "c"), List.of("a", "b", "c"),
                after, 10, List.of());
        assertEquals("SELECT `a`, `b`, `c` FROM `t`"
                + " WHERE ((`a` > ?) OR (`a` = ? AND `b` > ?) OR (`a` = ? AND `b` = ? AND `c` > ?))"
                + " ORDER BY `a` ASC, `b` ASC, `c` ASC LIMIT 10", sql);

        // 占位符个数必须是 n(n+1)/2
        assertEquals(6, sql.chars().filter(ch -> ch == '?').count(), "3 列键应有 6 个占位符");

        // 参数必须与占位符顺序严格一致：[v1] + [v1,v2] + [v1,v2,v3]
        assertEquals(List.of(1, 1, 2, 1, 2, 3), d.buildKeysetParameters(List.of("a", "b", "c"), after));
        // 单列键：1 个占位符、1 个参数
        KeysetPosition single = KeysetPosition.of(List.of("a"), List.of(9));
        assertEquals(List.of(9), d.buildKeysetParameters(List.of("a"), single));
        // 第一页：无谓词、无参数
        assertEquals(List.of(), d.buildKeysetParameters(List.of("a", "b"), KeysetPosition.first()));
        assertEquals("", d.buildKeysetPredicate(List.of("a", "b"), KeysetPosition.first()));
    }

    @Test
    @DisplayName("键集分页：没有排序键直接报错，禁止退化为 OFFSET")
    void keysetWithoutKeyRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> d.buildKeysetSelect(TableRef.of("t"), List.of("a"), List.of(),
                        KeysetPosition.first(), 10, List.of()));
        assertTrue(e.getMessage().contains("键集分页"), e.getMessage());
        assertTrue(e.getMessage().contains("OFFSET"), e.getMessage());
    }

    @Test
    @DisplayName("水位查询：上下界 + 展开式 tie-breaker")
    void watermarkSelect() {
        String first = d.buildWatermarkSelect(TableRef.of("t"), List.of("id", "updated_at"),
                "updated_at", "?", "?", List.of("id"), KeysetPosition.first(), 1000);
        assertEquals("SELECT `id`, `updated_at` FROM `t` WHERE `updated_at` > ? AND `updated_at` <= ?"
                + " ORDER BY `updated_at` ASC, `id` ASC LIMIT 1000", first);

        KeysetPosition after = KeysetPosition.of(List.of("updated_at", "id"),
                List.of("2026-01-01 10:00:00", 7));
        String next = d.buildWatermarkSelect(TableRef.of("t"), List.of("id", "updated_at"),
                "updated_at", "?", null, List.of("id"), after, 1000);
        assertEquals("SELECT `id`, `updated_at` FROM `t` WHERE `updated_at` > ?"
                + " AND ((`updated_at` > ?) OR (`updated_at` = ? AND `id` > ?))"
                + " ORDER BY `updated_at` ASC, `id` ASC LIMIT 1000", next);
        assertEquals(List.of("2026-01-01 10:00:00", "2026-01-01 10:00:00", 7),
                d.buildKeysetParameters(List.of("updated_at", "id"), after));
    }

    @Test
    @DisplayName("水位查询：上下界为 null 时不生成占位符")
    void watermarkWithoutBounds() {
        String sql = d.buildWatermarkSelect(TableRef.of("t"), List.of("id"), "id", null, null,
                List.of("id"), KeysetPosition.first(), 10);
        assertEquals("SELECT `id` FROM `t` ORDER BY `id` ASC LIMIT 10", sql);
    }

    @Test
    @DisplayName("幂等 upsert：ON DUPLICATE KEY UPDATE 排除键列")
    void upsert() {
        String sql = d.buildUpsert(TableRef.of("t"), List.of("id", "name", "age"), List.of("id"));
        assertEquals("INSERT INTO `t` (`id`, `name`, `age`) VALUES (?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE `name` = VALUES(`name`), `age` = VALUES(`age`)", sql);
    }

    @Test
    @DisplayName("幂等 upsert：全部列都是键列时用自赋值占位（MySQL 不允许空 UPDATE 子句）")
    void upsertAllKeys() {
        String sql = d.buildUpsert(TableRef.of("t"), List.of("a", "b"), List.of("a", "b"));
        assertEquals("INSERT INTO `t` (`a`, `b`) VALUES (?, ?) ON DUPLICATE KEY UPDATE `a` = `a`", sql);
    }

    @Test
    @DisplayName("幂等 upsert：没有键列必须报错（防止静默退化成普通 INSERT）")
    void upsertWithoutKeysRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> d.buildUpsert(TableRef.of("t"), List.of("a"), List.of()));
    }

    @Test
    @DisplayName("DDL/DML 文本")
    void ddl() {
        assertEquals("TRUNCATE TABLE `db`.`t`", d.buildTruncate(TableRef.of("db", "t")));
        assertEquals("DELETE FROM `t`", d.buildDeleteAll(TableRef.of("t")));
        assertEquals("INSERT INTO `t` (`a`, `b`) VALUES (?, ?)", d.buildInsert(TableRef.of("t"), List.of("a", "b")));
        assertEquals("CREATE TABLE `s` LIKE `t`", d.buildCreateTableLike(TableRef.of("t"), TableRef.of("s")));
        assertEquals("RENAME TABLE `a` TO `b`", d.buildRenameTable(TableRef.of("a"), TableRef.of("b")));
        assertEquals("DROP TABLE `a`", d.buildDropTable(TableRef.of("a")));
        assertEquals("SELECT MAX(`id`) FROM `t`", d.buildMaxValue(TableRef.of("t"), "id"));
        assertEquals("SELECT COUNT(*) FROM `t`", d.buildCount(TableRef.of("t"), List.of()));
        assertEquals("SELECT COUNT(*) FROM `t` WHERE `a` = 1", d.buildCount(TableRef.of("t"), List.of("`a` = 1")));
    }

    @Test
    @DisplayName("多行 VALUES：单条语句写多行（不依赖驱动 rewriteBatchedStatements）")
    void multiRowValues() {
        assertEquals("INSERT INTO `t` (`id`, `name`) VALUES (?, ?), (?, ?), (?, ?)",
                d.buildInsertMultiRow(TableRef.of("t"), List.of("id", "name"), 3));
        assertEquals("INSERT INTO `t` (`id`, `name`) VALUES (?, ?), (?, ?)"
                + " ON DUPLICATE KEY UPDATE `name` = VALUES(`name`)",
                d.buildUpsertMultiRow(TableRef.of("t"), List.of("id", "name"), List.of("id"), 2));
        // 单行形态必须与既有 SQL 完全一致（向后兼容）
        assertEquals(d.buildInsert(TableRef.of("t"), List.of("id", "name")),
                d.buildInsertMultiRow(TableRef.of("t"), List.of("id", "name"), 1));
        assertEquals(d.buildUpsert(TableRef.of("t"), List.of("id", "name"), List.of("id")),
                d.buildUpsertMultiRow(TableRef.of("t"), List.of("id", "name"), List.of("id"), 1));
    }

    @Test
    @DisplayName("占位符上限：按列数切分，绝不越过 MySQL 的 65535 上限")
    void placeholderLimit() {
        assertEquals(15000, d.maxRowsPerStatement(4));
        assertEquals(1000, d.maxRowsPerStatement(60));
        assertEquals(1, d.maxRowsPerStatement(60000));
        assertEquals(1, d.maxRowsPerStatement(0));
        assertTrue(d.maxRowsPerStatement(4) * 4 <= 65535);
        assertTrue(d.maxRowsPerStatement(60) * 60 <= 65535);
    }

    @Test
    @DisplayName("LIMIT 与流式 fetchSize（大表 OOM 的关键）")
    void limitAndFetchSize() {
        assertEquals("LIMIT 500", d.limitClause(500));
        assertThrows(IllegalArgumentException.class, () -> d.limitClause(0));
        assertEquals(Integer.MIN_VALUE, d.streamFetchSize());
        assertEquals(DbType.MYSQL, d.dbType());
        assertEquals("SELECT CURRENT_USER()", d.userName());
    }
}
