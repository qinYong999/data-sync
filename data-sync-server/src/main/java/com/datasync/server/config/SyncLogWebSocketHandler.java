package com.datasync.server.config;

import com.datasync.core.job.SyncEventBus;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class SyncLogWebSocketHandler extends TextWebSocketHandler {
    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();

    @PostConstruct
    public void init() {
        SyncEventBus.subscribe(message -> {
            for (WebSocketSession s : sessions) {
                try { if (s.isOpen()) s.sendMessage(new TextMessage(message)); } catch (Exception ignored) {}
            }
        });
    }
    @Override public void afterConnectionEstablished(WebSocketSession session) { sessions.add(session); }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) { sessions.remove(session); }
}
