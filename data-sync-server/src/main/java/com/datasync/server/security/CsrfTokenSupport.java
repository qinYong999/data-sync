package com.datasync.server.security;

import com.datasync.server.config.AppProperties;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.DeferredCsrfToken;
import org.springframework.stereotype.Component;

/**
 * CSRF token 下发（SPA 契约）。
 *
 * <p>下发的是**会话里的原文 token**，前端原样放进 {@code X-CSRF-TOKEN} 头即可；
 * 服务端渲染表单里 Thymeleaf 注入的是 XOR 掩码值，两者都能通过校验
 * （见 {@link AppCsrfTokenRequestHandler}）。</p>
 */
@Component
public class CsrfTokenSupport {

    private static final Logger log = LoggerFactory.getLogger(CsrfTokenSupport.class);

    private final CsrfTokenRepository csrfTokenRepository;
    private final AppProperties properties;

    public CsrfTokenSupport(CsrfTokenRepository csrfTokenRepository, AppProperties properties) {
        this.csrfTokenRepository = csrfTokenRepository;
        this.properties = properties;
    }

    public boolean isSecurityEnabled() {
        return properties.getSecurity().isEnabled();
    }

    /** 安全关闭时返回 null（前端拦截器需容忍 null 且不加 CSRF 头） */
    public String rawToken(HttpServletRequest request, HttpServletResponse response) {
        if (!isSecurityEnabled()) {
            return null;
        }
        try {
            DeferredCsrfToken deferred = csrfTokenRepository.loadDeferredToken(request, response);
            return deferred.get().getToken();
        } catch (Exception e) {
            log.debug("生成 CSRF token 失败：{}", e.getMessage());
            return null;
        }
    }
}
