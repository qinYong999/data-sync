package com.datasync.core.preflight;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.datasync.core.model.PreflightIssue;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * MySQL 授权解析（PERMISSION_DENIED）。
 *
 * <p>原则：解析不出来就放过，只有"确证缺少权限"才报错。
 */
class MySqlPrivilegesTest {

    @Test
    @DisplayName("ALL PRIVILEGES 覆盖一切")
    void allPrivileges() {
        List<String> grants = List.of("GRANT ALL PRIVILEGES ON *.* TO `root`@`localhost` WITH GRANT OPTION");
        assertNull(MySqlPrivileges.check(grants, "DROP", "datasync"));
        assertNull(MySqlPrivileges.check(grants, "CREATE", "anything"));
    }

    @Test
    @DisplayName("库级授权覆盖目标库")
    void databaseScoped() {
        List<String> grants = List.of("GRANT SELECT, INSERT, DROP ON `datasync`.* TO `u`@`%`");
        assertNull(MySqlPrivileges.check(grants, "DROP", "datasync"));
        PreflightIssue i = MySqlPrivileges.check(grants, "CREATE", "datasync");
        assertNotNull(i);
        assertEquals(ErrorCodes.PERMISSION_DENIED, i.getCode());
        assertEquals(PreflightIssue.Level.ERROR, i.getLevel());
    }

    @Test
    @DisplayName("授权范围不含目标库 → 报 PERMISSION_DENIED")
    void otherDatabase() {
        List<String> grants = List.of("GRANT DROP ON `other`.* TO `u`@`%`");
        assertNotNull(MySqlPrivileges.check(grants, "DROP", "datasync"));
        // 表级授权只在同库表上生效
        assertNull(MySqlPrivileges.check(List.of("GRANT DROP ON `datasync`.`t` TO `u`@`%`"), "DROP", "datasync"));
    }

    @Test
    @DisplayName("拿不到授权信息时不误报")
    void noGrants() {
        assertNull(MySqlPrivileges.check(List.of(), "DROP", "datasync"));
        assertNull(MySqlPrivileges.check(null, "DROP", "datasync"));
        assertNull(MySqlPrivileges.check(List.of("USAGE ON *.* TO `u`@`%`"), "DROP", null));
    }

    @Test
    @DisplayName("大小写与反引号容错")
    void caseInsensitive() {
        assertNull(MySqlPrivileges.check(List.of("grant all privileges on *.* to 'u'@'%'"), "delete", "x"));
        assertNull(MySqlPrivileges.check(List.of("GRANT DELETE ON DATASYNC.* TO 'u'@'%'"), "DELETE", "datasync"));
    }
}
