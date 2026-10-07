package com.datasync.server.controller;

import com.datasync.server.config.SyncLogWebSocketHandler;
import com.datasync.server.security.CsrfTokenSupport;
import com.datasync.server.service.SystemInfoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 系统信息（契约 §4.3 新增端点）。
 *
 * <p>需登录访问；响应里额外带回 {@code csrfToken} 与 {@code securityEnabled}，
 * 供前端在会话内刷新 token，并兼容 {@code app.security.enabled=false} 的本地开发模式。</p>
 */
@RestController
@RequestMapping("/api/system")
public class SystemInfoController {

    private final SystemInfoService systemInfoService;
    private final CsrfTokenSupport csrfTokenSupport;
    private final SyncLogWebSocketHandler logWebSocketHandler;

    public SystemInfoController(SystemInfoService systemInfoService, CsrfTokenSupport csrfTokenSupport,
                               SyncLogWebSocketHandler logWebSocketHandler) {
        this.systemInfoService = systemInfoService;
        this.csrfTokenSupport = csrfTokenSupport;
        this.logWebSocketHandler = logWebSocketHandler;
    }

    @GetMapping("/info")
    public Map<String, Object> info(HttpServletRequest request, HttpServletResponse response) {
        Map<String, Object> info = systemInfoService.snapshot();
        info.put("securityEnabled", csrfTokenSupport.isSecurityEnabled());
        info.put("csrfToken", csrfTokenSupport.rawToken(request, response));
        // D1 观测点：慢客户端导致的实时日志丢弃条数与在线会话数（证明"丢中间态"而非"背压阻塞"）
        info.put("websocketDroppedMessages", logWebSocketHandler.droppedMessages());
        info.put("websocketSessions", logWebSocketHandler.sessionCount());
        return info;
    }
}
