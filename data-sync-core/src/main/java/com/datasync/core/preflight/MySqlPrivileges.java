package com.datasync.core.preflight;

import com.datasync.core.model.PreflightIssue;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MySQL 授权解析（预检 PERMISSION_DENIED 用）。
 *
 * <p>只做"能确证才报错"的判断：{@code SHOW GRANTS} 拿不到（非 MySQL、权限不足、语法不支持）时
 * 一律放过，绝不因为解析不出来就拦住合法任务。
 */
public final class MySqlPrivileges {

    private MySqlPrivileges() {
    }

    /**
     * 判断授权行是否覆盖所需权限。
     *
     * @param grantLines  {@code SHOW GRANTS FOR CURRENT_USER()} 的每一行
     * @param privilege   需要的权限名，如 {@code DROP} / {@code DELETE} / {@code CREATE}
     * @param database    目标库名（用于判断授权范围是否覆盖）
     * @return 覆盖则返回 null，否则返回 ERROR issue
     */
    public static PreflightIssue check(List<String> grantLines, String privilege, String database) {
        if (grantLines == null || grantLines.isEmpty()) {
            return null;
        }
        String need = privilege.toUpperCase(Locale.ROOT);
        List<String> scopes = new ArrayList<>();
        boolean parsedAny = false;
        for (String line : grantLines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            String upper = line.toUpperCase(Locale.ROOT);
            int grantIdx = upper.indexOf("GRANT ");
            int onIdx = upper.indexOf(" ON ");
            int toIdx = upper.indexOf(" TO ");
            if (grantIdx < 0 || onIdx < 0 || toIdx < 0 || toIdx <= onIdx) {
                continue;
            }
            parsedAny = true;
            String privs = upper.substring(grantIdx + "GRANT ".length(), onIdx);
            String scope = upper.substring(onIdx + " ON ".length(), toIdx).replace("`", "").trim();
            boolean covers = false;
            for (String p : privs.split(",")) {
                String token = p.trim();
                if (token.equals("ALL PRIVILEGES") || token.equals("ALL") || token.equals(need)) {
                    covers = true;
                    break;
                }
            }
            if (!covers) {
                continue;
            }
            if (scopeApplies(scope, database)) {
                return null;
            }
            scopes.add(scope);
        }
        if (!parsedAny) {
            // 一行都解析不出来（非 MySQL 方言/异常输出/权限不足看不到授权）：不做判断，绝不误报
            return null;
        }
        return PreflightIssue.error(ErrorCodes.PERMISSION_DENIED,
                "目标库账号缺少 " + need + " 权限（现有授权范围: " + (scopes.isEmpty() ? "未覆盖目标库" : scopes) + "）",
                "请给目标库账号授予 " + need + " 权限（例如 GRANT " + need + " ON `"
                        + (database == null ? "your_db" : database) + "`.* TO 'user'@'%'）后重试");
    }

    /** 授权范围是否覆盖目标库：{@code *.*}、{@code db.*}、{@code db.table}。 */
    static boolean scopeApplies(String scope, String database) {
        if (scope == null || scope.isEmpty()) {
            return false;
        }
        if (scope.startsWith("*.")) {
            return true;
        }
        if (database == null || database.isEmpty()) {
            return false;
        }
        String db = database.toUpperCase(Locale.ROOT);
        return scope.equalsIgnoreCase(db) || scope.toUpperCase(Locale.ROOT).startsWith(db + ".");
    }
}
