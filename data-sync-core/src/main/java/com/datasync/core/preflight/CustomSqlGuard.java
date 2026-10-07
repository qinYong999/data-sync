package com.datasync.core.preflight;

import com.datasync.core.model.PreflightIssue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 自定义 SQL 守卫：只允许单条只读 SELECT。
 *
 * <p>校验策略是「先剥壳再查词」：
 * <ol>
 *   <li>剥离字符串字面量、反引号/双引号标识符、行注释与块注释，得到"纯代码"；
 *       这样 {@code WHERE name = 'drop table'} 里的关键字不会被误判，
 *       {@code UPDATE_} 这类含关键字的列名也不会被误伤。</li>
 *   <li>纯代码里若出现分号后还有内容 → 多语句，拒绝。</li>
 *   <li>第一个词必须是 {@code SELECT} 或 {@code WITH}。</li>
 *   <li>词法单元里只要出现写操作/危险关键字 → 拒绝。</li>
 * </ol>
 *
 * <p>这只是"配置期"的静态防线：真正的语法正确性由预检用
 * {@code SELECT * FROM (&lt;sql&gt;) _ds_src WHERE 1=0} 到源库实探一次来确认。
 */
public final class CustomSqlGuard {

    /** 危险/写操作关键字（词法单元级比较，避免子串误伤）。 */
    private static final Set<String> BLOCKED = Set.of(
            "INSERT", "UPDATE", "DELETE", "DROP", "TRUNCATE", "ALTER", "CREATE", "REPLACE",
            "GRANT", "REVOKE", "EXECUTE", "CALL", "MERGE", "RENAME", "LOCK", "UNLOCK",
            "OUTFILE", "DUMPFILE", "LOAD_FILE", "SHUTDOWN", "SET", "COMMIT", "ROLLBACK",
            "START", "BEGIN", "HANDLER", "PREPARE", "DEALLOCATE", "FLUSH", "KILL",
            "INTO");

    /** 允许作为首词的关键字。 */
    private static final Set<String> ALLOWED_HEAD = Set.of("SELECT", "WITH");

    private CustomSqlGuard() {
    }

    /**
     * 校验自定义 SQL。
     *
     * @return null 表示合法；否则返回 {@code CUSTOM_SQL_INVALID} 的 ERROR issue
     */
    public static PreflightIssue validate(String sql) {
        if (sql == null || sql.isBlank()) {
            return PreflightIssue.error(ErrorCodes.CUSTOM_SQL_INVALID,
                    "自定义 SQL 为空", "请填写一条完整的只读 SELECT 查询");
        }
        String code = stripLiteralsAndComments(sql);
        // 去掉末尾分号后若还有分号 → 多语句
        String trimmed = code.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.contains(";")) {
            return PreflightIssue.error(ErrorCodes.CUSTOM_SQL_INVALID,
                    "自定义 SQL 包含多条语句（检测到分号）",
                    "只允许一条 SELECT 查询，请删除多余语句");
        }
        List<String> tokens = tokens(trimmed);
        if (tokens.isEmpty()) {
            return PreflightIssue.error(ErrorCodes.CUSTOM_SQL_INVALID,
                    "自定义 SQL 不包含任何可执行语句", "请填写一条完整的只读 SELECT 查询");
        }
        String head = tokens.get(0).toUpperCase(Locale.ROOT);
        if (!ALLOWED_HEAD.contains(head)) {
            return PreflightIssue.error(ErrorCodes.CUSTOM_SQL_INVALID,
                    "自定义 SQL 必须以 SELECT（或 WITH ... SELECT）开头，当前是: " + head,
                    "数据同步只读取数据，请改写成 SELECT 查询");
        }
        for (String t : tokens) {
            String upper = t.toUpperCase(Locale.ROOT);
            if (BLOCKED.contains(upper)) {
                return PreflightIssue.error(ErrorCodes.CUSTOM_SQL_INVALID,
                        "自定义 SQL 中包含禁止的关键字: " + upper,
                        "自定义 SQL 只允许只读查询，禁止写库/改结构/多语句");
            }
        }
        return null;
    }

    /** 是否合法。 */
    public static boolean isValid(String sql) {
        return validate(sql) == null;
    }

    /**
     * 把自定义 SQL 包装成派生表，便于在其上做键集分页 / 预览 / 元数据探测。
     *
     * <p>预览示例：{@code CustomSqlGuard.wrap(sql) + " " + dialect.limitClause(100)}。
     */
    public static String wrap(String sql) {
        return "SELECT * FROM (" + normalize(sql) + ") _ds_src";
    }

    /** 去掉首尾空白与末尾分号。 */
    public static String normalize(String sql) {
        if (sql == null) {
            return "";
        }
        String s = sql.trim();
        while (s.endsWith(";")) {
            s = s.substring(0, s.length() - 1).trim();
        }
        return s;
    }

    /** 剥离字面量/注释后的"纯代码"（单测与排错用）。 */
    public static String stripLiteralsAndComments(String sql) {
        StringBuilder out = new StringBuilder(sql.length());
        int i = 0;
        int n = sql.length();
        while (i < n) {
            char ch = sql.charAt(i);
            char next = i + 1 < n ? sql.charAt(i + 1) : '\0';
            if (ch == '\'') {
                i = skipQuoted(sql, i, '\'', out);
            } else if (ch == '"') {
                i = skipQuoted(sql, i, '"', out);
            } else if (ch == '`') {
                i = skipQuoted(sql, i, '`', out);
            } else if (ch == '-' && next == '-') {
                i = skipLine(sql, i, out);
            } else if (ch == '#') {
                i = skipLine(sql, i, out);
            } else if (ch == '/' && next == '*') {
                i = skipBlock(sql, i, out);
            } else {
                out.append(ch);
                i++;
            }
        }
        return out.toString();
    }

    private static int skipQuoted(String sql, int start, char quote, StringBuilder out) {
        int i = start + 1;
        int n = sql.length();
        out.append(' ');
        while (i < n) {
            char c = sql.charAt(i);
            if (c == '\\' && quote == '\'' && i + 1 < n) {
                i += 2;
                continue;
            }
            if (c == quote) {
                if (i + 1 < n && sql.charAt(i + 1) == quote) {
                    i += 2;
                    continue;
                }
                return i + 1;
            }
            i++;
        }
        return i;
    }

    private static int skipLine(String sql, int start, StringBuilder out) {
        int i = start;
        int n = sql.length();
        while (i < n && sql.charAt(i) != '\n') {
            i++;
        }
        out.append(' ');
        return i;
    }

    private static int skipBlock(String sql, int start, StringBuilder out) {
        int i = start + 2;
        int n = sql.length();
        while (i < n - 1) {
            if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                out.append(' ');
                return i + 2;
            }
            i++;
        }
        return n;
    }

    /** 按「字母数字下划线美元符」切词，其余字符作为分隔符。 */
    public static List<String> tokens(String code) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '$') {
                cur.append(c);
            } else if (cur.length() > 0) {
                out.add(cur.toString());
                cur.setLength(0);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /** 供界面展示：把 SQL 压成单行并截断。 */
    public static String summarize(String sql) {
        if (sql == null) {
            return "";
        }
        String oneLine = Arrays.stream(sql.split("\\s+"))
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(" "));
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "...";
    }
}
