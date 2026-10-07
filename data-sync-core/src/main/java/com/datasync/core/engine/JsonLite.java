package com.datasync.core.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 工具（core 不引入 Jackson）。
 *
 * <p>只做两件事：把行数据序列化成 {@code SyncError.rowData}，以及解析复合游标里的字符串分量。
 * 解析失败一律抛出 {@link IllegalArgumentException}，绝不做"尽力而为"的猜测。
 */
final class JsonLite {

    private JsonLite() {
    }

    /** JSON 字符串转义。 */
    static String escape(String s) {
        if (s == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(s.length() + 8);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 行数据 → JSON 对象（值是任意 JDBC 对象，统一走 toString 形式，长度由调用方截断）。 */
    static String toJson(Map<String, Object> row) {
        if (row == null) {
            return "{}";
        }
        StringBuilder sb = new StringBuilder(128);
        sb.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> e : row.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(escape(e.getKey())).append(':');
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof byte[] bytes) {
                sb.append(escape("<binary " + bytes.length + " bytes>"));
            } else {
                sb.append(escape(String.valueOf(v)));
            }
        }
        return sb.append('}').toString();
    }

    /** 是否为 JSON 数组形态的复合游标。 */
    static boolean looksLikeArray(String text) {
        if (text == null) {
            return false;
        }
        String t = text.trim();
        return t.length() >= 2 && t.charAt(0) == '[' && t.charAt(t.length() - 1) == ']';
    }

    /** 把一个字符串列表编码成 JSON 数组（复合游标用）。 */
    static String toArray(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escape(parts.get(i)));
        }
        return sb.append(']').toString();
    }

    /** 解析只含字符串/数字/布尔/null 的 JSON 数组；其它形态抛异常。 */
    static List<String> parseArray(String text) {
        String t = text == null ? "" : text.trim();
        if (!looksLikeArray(t)) {
            throw new IllegalArgumentException("不是复合游标（JSON 数组）形式: " + text);
        }
        List<String> out = new ArrayList<>();
        int i = 1;
        int n = t.length() - 1;
        while (i < n) {
            char c = t.charAt(i);
            if (Character.isWhitespace(c) || c == ',') {
                i++;
                continue;
            }
            if (c == '"') {
                StringBuilder sb = new StringBuilder();
                i++;
                boolean closed = false;
                while (i < n) {
                    char d = t.charAt(i);
                    if (d == '\\') {
                        if (i + 1 >= n) {
                            throw new IllegalArgumentException("复合游标字符串转义不完整: " + text);
                        }
                        char e = t.charAt(i + 1);
                        switch (e) {
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'u' -> {
                                if (i + 5 >= n) {
                                    throw new IllegalArgumentException("复合游标 \\u 转义不完整: " + text);
                                }
                                sb.append((char) Integer.parseInt(t.substring(i + 2, i + 6), 16));
                                i += 4;
                            }
                            default -> sb.append(e);
                        }
                        i += 2;
                        continue;
                    }
                    if (d == '"') {
                        closed = true;
                        i++;
                        break;
                    }
                    sb.append(d);
                    i++;
                }
                if (!closed) {
                    throw new IllegalArgumentException("复合游标字符串未闭合: " + text);
                }
                out.add(sb.toString());
                continue;
            }
            int start = i;
            while (i < n && t.charAt(i) != ',') {
                i++;
            }
            String token = t.substring(start, i).trim();
            if (token.equals("null")) {
                out.add(null);
            } else {
                out.add(token);
            }
        }
        return out;
    }
}
