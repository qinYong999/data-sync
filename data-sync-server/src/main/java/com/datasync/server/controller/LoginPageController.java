package com.datasync.server.controller;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 服务端登录页（MPA 兜底路径）。
 *
 * <p>SPA 走 {@code POST /api/auth/login}（JSON）；本页给"直接敲地址/curl/运维应急"用，
 * 同时满足契约 §5.9 的"未登录访问页面 302 到 /login"。</p>
 */
@Controller
public class LoginPageController {

    @GetMapping("/login")
    public String loginPage() {
        return "login";
    }
}
