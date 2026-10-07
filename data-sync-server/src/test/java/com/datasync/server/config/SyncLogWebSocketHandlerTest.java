package com.datasync.server.config;

import com.datasync.core.job.SyncEventBus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D1：WebSocket 发送必须发生在**发送线程**上，绝不能在总线投递线程（也就是引擎线程）上写 socket。
 *
 * <p>模拟一个"挂着不动的浏览器"：{@code sendMessage} 每次阻塞 700ms
 * （与 engine 埋点实验里的 716ms/chunk 同源）。断言：总线 publish 立即返回；
 * 消息最终仍被投递（只是在发送线程上慢慢发）。</p>
 */
class SyncLogWebSocketHandlerTest {

    private final SyncLogWebSocketHandler handler = new SyncLogWebSocketHandler();

    @AfterEach
    void tearDown() {
        handler.unsubscribe();
    }

    private WebSocketSession slowSession(String id) throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("/ws/logs"));
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getTextMessageSizeLimit()).thenReturn(8192);
        when(session.getBinaryMessageSizeLimit()).thenReturn(8192);
        // 慢客户端：每条消息阻塞 700ms（ConcurrentWebSocketSessionDecorator 的背压语义）
        doAnswer(invocation -> {
            Thread.sleep(700L);
            return null;
        }).when(session).sendMessage(any(TextMessage.class));
        return session;
    }

    @Test
    @DisplayName("会话队列上限可配：默认 256；非法值回落到默认值且不抛异常")
    void sessionQueueCapacityIsConfigurableWithSafeFallback() {
        String property = SyncLogWebSocketHandler.SESSION_QUEUE_CAPACITY_PROPERTY;
        assertThat(SyncLogWebSocketHandler.sessionQueueCapacity())
            .as("默认值必须与历史版本一致").isEqualTo(256);
        try {
            System.setProperty(property, "4");
            assertThat(SyncLogWebSocketHandler.resolveCapacity(property, 256)).isEqualTo(4);
            System.setProperty(property, "0");
            assertThat(SyncLogWebSocketHandler.resolveCapacity(property, 256)).isEqualTo(256);
            System.setProperty(property, "abc");
            assertThat(SyncLogWebSocketHandler.resolveCapacity(property, 256)).isEqualTo(256);
        } finally {
            System.clearProperty(property);
        }
    }

    @Test
    @DisplayName("总线投递不被慢客户端阻塞；消息在发送线程上最终送达")
    void slowClientDoesNotBlockBusThread() throws Exception {
        handler.subscribe();
        WebSocketSession session = slowSession("slow-1");
        handler.afterConnectionEstablished(session);

        long start = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            SyncEventBus.publish("业务日志 " + i);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

        assertThat(elapsedMs)
            .as("20 条日志的投递必须立即返回（若在投递线程上写 socket，会 > 14000 ms）")
            .isLessThan(500L);
        // 但消息确实在发送线程上被发出（1 条欢迎语 + 若干业务日志，超时给足慢客户端时间）
        verify(session, timeout(TimeUnit.SECONDS.toMillis(30)).atLeastOnce())
            .sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("会话队列满时丢最旧并计数（不发散、不阻塞）")
    void sessionQueueOverflowDropsOldest() throws Exception {
        handler.subscribe();
        WebSocketSession session = slowSession("slow-2");
        handler.afterConnectionEstablished(session);

        long droppedBefore = handler.droppedMessages();
        for (int i = 0; i < 600; i++) {
            SyncEventBus.publish("洪水日志 " + i);
        }
        // 单会话队列 256，且发送线程远慢于投递（700ms/条）→ 必然丢弃
        assertThat(handler.droppedMessages())
            .as("慢客户端导致的丢弃必须被计数（证明是丢中间态而不是背压阻塞）")
            .isGreaterThan(droppedBefore);
    }

    @Test
    @DisplayName("带 taskId 过滤的会话只收到匹配 run 的消息")
    void filteredSessionOnlyReceivesMatchingRun() throws Exception {
        handler.subscribe();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("filtered");
        when(session.isOpen()).thenReturn(true);
        when(session.getUri()).thenReturn(URI.create("/ws/logs?taskId=42"));
        when(session.getAttributes()).thenReturn(new HashMap<>());
        when(session.getTextMessageSizeLimit()).thenReturn(8192);
        when(session.getBinaryMessageSizeLimit()).thenReturn(8192);
        doAnswer(invocation -> null).when(session).sendMessage(any(TextMessage.class));
        handler.afterConnectionEstablished(session);

        SyncEventBus.publish(com.datasync.server.log.RunLog.tag(42L, 1L) + "任务42的日志");
        SyncEventBus.publish(com.datasync.server.log.RunLog.tag(99L, 2L) + "任务99的日志");

        verify(session, timeout(TimeUnit.SECONDS.toMillis(10)).times(2))
            .sendMessage(any(TextMessage.class)); // 欢迎语 + 任务42 的那条
    }
}
