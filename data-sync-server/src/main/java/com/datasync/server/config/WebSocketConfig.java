package com.datasync.server.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final SyncLogWebSocketHandler logHandler;
    public WebSocketConfig(SyncLogWebSocketHandler logHandler) { this.logHandler = logHandler; }
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(logHandler, "/ws/logs").setAllowedOrigins("*");
    }
}
