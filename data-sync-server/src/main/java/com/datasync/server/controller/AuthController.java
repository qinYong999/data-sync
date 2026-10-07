package com.datasync.server.controller;

import com.datasync.server.config.AppProperties;
import com.datasync.server.security.AdminCredentials;
import com.datasync.server.security.CsrfTokenSupport;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * SPA 认证接口（契约 §4.3 未列，属补充端点，已向 Lead 与 ui 备案）。
 *
 * <p>服务端仍是标准的"表单登录 + 会话"，这里只是把登录/登出做成 JSON，
 * 让 Vue 前端不必跟随 302 解析 HTML：</p>
 * <ul>
 *   <li>{@code GET  /api/auth/csrf}（匿名）→ 下发会话原文 CSRF token；</li>
 *   <li>{@code GET  /api/auth/session}（匿名）→ 登录状态；</li>
 *   <li>{@code POST /api/auth/login}（匿名但需 CSRF 头）→ JSON 登录；</li>
 *   <li>{@code POST /api/auth/logout}（需登录）→ 注销并失效会话。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository securityContextRepository;
    private final CsrfTokenSupport csrfTokenSupport;
    private final AppProperties properties;
    private final AdminCredentials adminCredentials;

    public AuthController(AuthenticationManager authenticationManager,
                          SecurityContextRepository securityContextRepository,
                          CsrfTokenSupport csrfTokenSupport,
                          AppProperties properties,
                          AdminCredentials adminCredentials) {
        this.authenticationManager = authenticationManager;
        this.securityContextRepository = securityContextRepository;
        this.csrfTokenSupport = csrfTokenSupport;
        this.properties = properties;
        this.adminCredentials = adminCredentials;
    }

    public record LoginRequest(String username, String password) { }

    @GetMapping("/csrf")
    public Map<String, Object> csrf(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> body = new LinkedHashMap<>();
        boolean enabled = properties.getSecurity().isEnabled();
        body.put("securityEnabled", enabled);
        body.put("headerName", "X-CSRF-TOKEN");
        body.put("parameterName", "_csrf");
        body.put("token", csrfTokenSupport.rawToken(request, response));
        return body;
    }

    @GetMapping("/session")
    public Map<String, Object> session(HttpServletRequest request, HttpServletResponse response,
                                      Authentication authentication) {
        boolean enabled = properties.getSecurity().isEnabled();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("securityEnabled", enabled);
        body.put("authenticated", !enabled || (authentication != null && authentication.isAuthenticated()));
        body.put("username", authentication == null ? null : authentication.getName());
        body.put("csrfToken", csrfTokenSupport.rawToken(request, response));
        return body;
    }

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody LoginRequest request,
                                                     HttpServletRequest httpRequest,
                                                     HttpServletResponse httpResponse) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!properties.getSecurity().isEnabled()) {
            // 认证已关闭：直接视为已登录，前端无需分支
            body.put("success", true);
            body.put("authenticated", true);
            body.put("securityEnabled", false);
            body.put("username", adminCredentials.getUsername());
            body.put("csrfToken", null);
            body.put("message", "认证已关闭（app.security.enabled=false）");
            return ResponseEntity.ok(body);
        }
        if (request == null || request.username() == null || request.username().isBlank()
            || request.password() == null || request.password().isEmpty()) {
            body.put("timestamp", java.time.LocalDateTime.now().toString());
            body.put("status", 400);
            body.put("code", "INVALID_CREDENTIALS_INPUT");
            body.put("message", "请输入用户名和密码");
            body.put("details", java.util.List.of());
            return ResponseEntity.badRequest().body(body);
        }
        try {
            Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.username(), request.password()));
            SecurityContext context = SecurityContextHolder.createEmptyContext();
            context.setAuthentication(authentication);
            SecurityContextHolder.setContext(context);
            securityContextRepository.saveContext(context, httpRequest, httpResponse);

            body.put("success", true);
            body.put("authenticated", true);
            body.put("securityEnabled", true);
            body.put("username", authentication.getName());
            body.put("csrfToken", csrfTokenSupport.rawToken(httpRequest, httpResponse));
            body.put("message", "登录成功");
            log.info("管理员 {} 登录成功", authentication.getName());
            return ResponseEntity.ok(body);
        } catch (AuthenticationException e) {
            log.warn("登录失败：用户名 {} 认证不通过", request.username());
            body.put("timestamp", java.time.LocalDateTime.now().toString());
            body.put("status", 401);
            body.put("code", "BAD_CREDENTIALS");
            body.put("message", "用户名或密码错误");
            body.put("details", java.util.List.of());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(body);
        }
    }

    @PostMapping("/logout")
    public Map<String, Object> logout(HttpServletRequest request, HttpServletResponse response,
                                      Authentication authentication) {
        new SecurityContextLogoutHandler().logout(request, response, authentication);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", "已退出登录");
        return body;
    }
}
