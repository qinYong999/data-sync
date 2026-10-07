package com.datasync.server.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestHandler;
import org.springframework.security.web.csrf.XorCsrfTokenRequestAttributeHandler;
import org.springframework.util.StringUtils;

import java.util.function.Supplier;

/**
 * 同时兼容"原文 token"与"XOR 掩码 token"的 CSRF 请求处理器（D10）。
 *
 * <p>Spring Security 默认的 {@link XorCsrfTokenRequestAttributeHandler} 把下发的 token 做了
 * 每请求掩码（防 BREACH），只能解回掩码值；而 SPA 更希望拿到会话里的原文 token 放进
 * {@code X-CSRF-TOKEN} 头。这里把两条路都打开：</p>
 * <ul>
 *   <li>{@link #handle} 仍走 XOR：Thymeleaf 表单的隐藏域、meta 标签天然是掩码值，安全不打折；</li>
 *   <li>{@link #resolveCsrfTokenValue} 先看请求头：等于原文就按原文比对（SPA 路径，
 *       token 由 {@code GET /api/auth/csrf} 下发）；否则按掩码解码（表单路径）。</li>
 * </ul>
 */
public final class AppCsrfTokenRequestHandler implements CsrfTokenRequestHandler {

    private final CsrfTokenRequestAttributeHandler plain = new CsrfTokenRequestAttributeHandler();
    private final XorCsrfTokenRequestAttributeHandler xor = new XorCsrfTokenRequestAttributeHandler();

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, Supplier<CsrfToken> csrfToken) {
        // 渲染路径统一走 XOR 掩码
        xor.handle(request, response, csrfToken);
    }

    @Override
    public String resolveCsrfTokenValue(HttpServletRequest request, CsrfToken csrfToken) {
        String headerValue = request.getHeader(csrfToken.getHeaderName());
        if (StringUtils.hasText(headerValue)) {
            if (headerValue.equals(csrfToken.getToken())) {
                // SPA：/api/auth/csrf 下发的是会话里的原文 token
                return plain.resolveCsrfTokenValue(request, csrfToken);
            }
            try {
                // MPA：下发的可能是 XOR 掩码值
                return xor.resolveCsrfTokenValue(request, csrfToken);
            } catch (RuntimeException decodeFailure) {
                return headerValue;
            }
        }
        return xor.resolveCsrfTokenValue(request, csrfToken);
    }
}
