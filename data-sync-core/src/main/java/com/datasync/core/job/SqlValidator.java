package com.datasync.core.job;

import java.util.Arrays;
import java.util.List;

/**
 * SQL 安全校验工具 — 仅允许只读 SELECT 查询
 */
public class SqlValidator {

    private static final List<String> BLOCKED_KEYWORDS = Arrays.asList(
        "INSERT ", "UPDATE ", "DELETE ", "DROP ", "TRUNCATE ", "ALTER ",
        "CREATE ", "REPLACE ", "GRANT ", "REVOKE ", "EXECUTE ", "CALL ",
        "LOAD ", "INTO OUTFILE", "INTO DUMPFILE"
    );

    public static void validateSelectSql(String sql) {
        if (sql == null || sql.isBlank()) {
            throw new IllegalArgumentException("SQL 不能为空");
        }
        String upper = sql.toUpperCase().trim();
        if (!upper.startsWith("SELECT")) {
            throw new IllegalArgumentException("仅支持 SELECT 查询");
        }
        // 检查是否包含分号（多语句注入）
        if (upper.contains(";")) {
            int semiIdx = upper.indexOf(';');
            if (semiIdx < upper.length() - 1) {
                String afterSemi = upper.substring(semiIdx + 1).trim();
                if (!afterSemi.isEmpty()) {
                    throw new IllegalArgumentException("SQL 中不能包含多条语句");
                }
            }
        }
        // 检查禁止的关键字
        for (String keyword : BLOCKED_KEYWORDS) {
            if (upper.contains(keyword)) {
                throw new IllegalArgumentException("SQL 中包含禁止的关键字: " + keyword.trim());
            }
        }
    }
}
