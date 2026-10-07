package com.datasync.core.jdbc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 数据库标识符（库名/表名/列名）的安全校验与拆分。
 *
 * <p>所有拼进 SQL 的用户输入都必须先过这里，再做方言引用（{@code dialect.quote}）。
 * 允许的字符集：{@code [A-Za-z0-9_$\u4e00-\u9fa5]}，段之间用 {@code .} 分隔（最多 3 段）。
 * 该字符集里没有任何引号、分号、空格、注释符号，因此通过校验 + 引用后不可能造成 SQL 注入。
 */
public final class Identifiers {

    /** 单段标识符允许的字符：字母、数字、下划线、美元符、汉字。 */
    private static final Pattern PART = Pattern.compile("^[A-Za-z0-9_$\\u4e00-\\u9fa5]+$");

    /** 标识符最多允许的段数（catalog.schema.table）。 */
    private static final int MAX_PARTS = 3;

    private Identifiers() {
    }

    /** 是否为合法标识符（允许 {@code a} / {@code a.b} / {@code a.b.c}）。 */
    public static boolean isValid(String identifier) {
        if (identifier == null) {
            return false;
        }
        String trimmed = identifier.trim();
        if (trimmed.isEmpty() || !trimmed.equals(identifier)) {
            return false;
        }
        List<String> parts = splitRaw(trimmed);
        if (parts.isEmpty() || parts.size() > MAX_PARTS) {
            return false;
        }
        for (String p : parts) {
            if (!PART.matcher(p).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * 校验标识符合法性，非法即抛 {@link IllegalArgumentException}（中文消息）。
     *
     * @param identifier 待校验标识符
     * @param what       描述（如"源表名"），用于错误消息
     */
    public static void validate(String identifier, String what) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException(what + "不能为空");
        }
        if (!isValid(identifier)) {
            throw new IllegalArgumentException(what + "[" + identifier + "]包含非法字符：只允许字母、数字、下划线、美元符、汉字，"
                    + "以及最多 2 个用于分隔库名/模式名的点号");
        }
    }

    /** 校验并返回拆分后的各段（不含点号）。 */
    public static List<String> parts(String identifier) {
        validate(identifier, "标识符");
        return Collections.unmodifiableList(splitRaw(identifier.trim()));
    }

    private static List<String> splitRaw(String identifier) {
        if (identifier.indexOf('.') < 0) {
            return new ArrayList<>(List.of(identifier));
        }
        List<String> out = new ArrayList<>();
        for (String p : identifier.split("\\.", -1)) {
            out.add(p);
        }
        return out;
    }

    /** 单段标识符是否合法。 */
    public static boolean isValidPart(String part) {
        return part != null && PART.matcher(part).matches();
    }

    /** 供日志/断言使用：列出所有被拒绝的样例。 */
    public static List<String> examplesOfInvalid() {
        return Arrays.asList("t; DROP TABLE x", "t--", "t x", "a..b", "`t`", "t'", "\"t\"", "a.b.c.d", "");
    }

    /** 相等比较（大小写不敏感，与 MySQL 默认行为一致）。 */
    public static boolean equalsIgnoreCase(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    /** 空安全的 trim。 */
    public static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** 调试用：把一个标识符渲染成 "a.b"。 */
    public static String join(List<String> parts) {
        return parts == null ? "" : String.join(".", parts);
    }

    /** 判断两个标识符的最后一段（表名）是否相同。 */
    public static boolean sameTable(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return Objects.equals(lastPart(left), lastPart(right));
    }

    private static String lastPart(String identifier) {
        List<String> parts = splitRaw(identifier.trim());
        return parts.get(parts.size() - 1);
    }
}
