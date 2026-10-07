package com.datasync.server.security;

import com.datasync.server.exception.ApiErrorWriter;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;

import java.io.IOException;

/**
 * 未认证处理（契约 §4.3 / §5.9）：
 * <ul>
 *   <li>{@code /api/**} 或声明了 JSON 的请求 → <b>401 + 统一 JSON 错误体</b>（前端拦截器据此跳登录页）；</li>
 *   <li>其它（浏览器直接访问页面）→ <b>302 到 /login</b>。</li>
 * </ul>
 */
public class ApiAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final LoginUrlAuthenticationEntryPoint loginRedirect = new LoginUrlAuthenticationEntryPoint("/login");

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException, ServletException {
        if (isApiRequest(request)) {
            ApiErrorWriter.write(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED",
                "未登录或会话已失效，请重新登录");
            return;
        }
        loginRedirect.commence(request, response, authException);
    }

    static boolean isApiRequest(HttpServletRequest request) {
        String uri = request.getRequestURI();
        if (uri != null && uri.startsWith("/api/")) {
            return true;
        }
        String accept = request.getHeader("Accept");
        if (accept != null && accept.contains("application/json")) {
            return true;
        }
        return "XMLHttpRequest".equalsIgnoreCase(request.getHeader("X-Requested-With"));
    }
}
