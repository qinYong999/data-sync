package com.datasync.server.security;

import com.datasync.server.exception.ApiErrorWriter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;

import java.io.IOException;

/**
 * 授权/CSRF 失败处理：一律返回统一 JSON 错误体。
 *
 * <p>CSRF 失败给独立错误码 {@code CSRF_INVALID}，前端据此重新获取 token 后重试一次；
 * 其余授权失败给 {@code FORBIDDEN}。</p>
 */
public class ApiAccessDeniedHandler implements AccessDeniedHandler {

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        if (accessDeniedException instanceof CsrfException) {
            ApiErrorWriter.write(response, HttpServletResponse.SC_FORBIDDEN, "CSRF_INVALID",
                "CSRF 校验失败：会话令牌缺失或已过期，请重新获取 CSRF 令牌后再提交");
            return;
        }
        ApiErrorWriter.write(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "没有权限执行该操作");
    }
}
