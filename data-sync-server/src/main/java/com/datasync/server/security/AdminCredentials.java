package com.datasync.server.security;

import com.datasync.server.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * 管理员凭证解析（D10）。
 *
 * <p>规则：</p>
 * <ul>
 *   <li>prod/production profile 且未配置 {@code app.security.password} → <b>拒绝启动</b>
 *       （宁可起不来，也不要一个默认口令的管理后台）；</li>
 *   <li>非生产且未配置 → 使用开发默认口令并打印醒目警告；</li>
 *   <li>{@code app.security.enabled=false} → 不校验口令，但仍打印警告。</li>
 * </ul>
 */
@Component
public class AdminCredentials {

    private static final Logger log = LoggerFactory.getLogger(AdminCredentials.class);

    /** 仅本地开发使用的默认口令；生产 profile 下永远不会走到这里 */
    public static final String DEV_DEFAULT_PASSWORD = "admin123";

    private final String username;
    private final String rawPassword;
    private final boolean devDefault;
    private final boolean securityEnabled;

    public AdminCredentials(AppProperties properties, Environment environment) {
        AppProperties.Security security = properties.getSecurity();
        this.securityEnabled = security.isEnabled();
        this.username = security.getUsername() == null || security.getUsername().isBlank()
            ? "admin" : security.getUsername().trim();
        String password = security.getPassword();
        boolean prod = environment.acceptsProfiles(Profiles.of("prod", "production"));

        if (password == null || password.isBlank()) {
            if (prod) {
                throw new IllegalStateException(
                    "生产环境必须配置 app.security.password（环境变量 DATASYNC_ADMIN_PASSWORD），否则拒绝启动");
            }
            this.rawPassword = DEV_DEFAULT_PASSWORD;
            this.devDefault = true;
            log.warn("未配置 app.security.password，已启用开发默认管理员口令（用户名 {}，口令 {}）；"
                + "生产环境必须通过环境变量 DATASYNC_ADMIN_PASSWORD 注入", this.username, DEV_DEFAULT_PASSWORD);
        } else {
            this.rawPassword = password;
            this.devDefault = false;
        }
        if (!securityEnabled) {
            log.warn("app.security.enabled=false：认证与 CSRF 已全部关闭，仅可用于本地开发，禁止在生产启用");
        }
    }

    public String getUsername() { return username; }
    public String getRawPassword() { return rawPassword; }
    public boolean isDevDefault() { return devDefault; }
    public boolean isSecurityEnabled() { return securityEnabled; }
}
