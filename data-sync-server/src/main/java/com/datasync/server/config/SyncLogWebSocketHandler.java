package com.datasync.server.config;

import com.datasync.core.job.SyncEventBus;
import com.datasync.server.log.RunLog;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * 实时日志 WebSocket（{@code /ws/logs}）。
 *
 * <p><b>D1 修复的核心</b>：总线消息<b>绝不在投递线程上写 socket</b>。
 * 以前这里直接在 {@link SyncEventBus} 的消费者里 {@code sendMessage}，
 * 而 {@link ConcurrentWebSocketSessionDecorator} 的语义是"必要时阻塞调用方"（最多 5 秒），
 * 于是引擎线程被一个挂着的浏览器页面拖慢几十倍（28588ms 里 28065ms 花在回调上）。</p>
 *
 * <p>现在每个会话有<b>自己的有界队列</b>，由<b>独立的发送线程</b>串行 drain：
 * 投递方只做一次 O(1) 入队；队列满则丢最旧并计数（进度是可合并的中间态）。
 * 单个慢客户端最多占住一个发送线程，既不影响引擎，也不影响其它会话。</p>
 *
 * <p>会话过滤（{@code ?taskId=}/{@code ?recordId=}）与标签剥离逻辑保持不变：
 * 带过滤的会话只会收到匹配 run 的消息，客户端拿到的仍是纯文本行。</p>
 *
 * <p><b>每会话队列上限做成系统属性</b>（{@code -Ddatasync.ws.session-queue-capacity=256}）：
 * 一是让"丢弃计数器"能被<b>端到端验证</b>（调到很小就能在真机上逼出丢弃，
 * 证明这个运维指标真的会动），二是给消费者更慢的部署留旋钮。
 * 非法值（非数字、{@code <= 0}）回落默认值并 WARN，绝不在类初始化时抛异常。</p>
 */
@Component
public class SyncLogWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(SyncLogWebSocketHandler.class);

    /** 背压只允许落在发送线程上，因此这里的超时不再影响同步主循环 */
    private static final int SEND_TIME_LIMIT_MS = 2000;
    private static final int BUFFER_SIZE_LIMIT = 256 * 1024;
    /** 系统属性名：{@code -Ddatasync.ws.session-queue-capacity=<正整数>} */
    public static final String SESSION_QUEUE_CAPACITY_PROPERTY = "datasync.ws.session-queue-capacity";
    /** 默认每会话排队上限（生产行为与历史版本一致） */
    public static final int DEFAULT_SESSION_QUEUE_CAPACITY = 256;

    /** 每会话排队上限：超了丢最旧（UI 只需要看到"最新进度"）。可配，见类注释 */
    private static final int SESSION_QUEUE_CAPACITY =
        resolveCapacity(SESSION_QUEUE_CAPACITY_PROPERTY, DEFAULT_SESSION_QUEUE_CAPACITY);
    private static final int SENDER_THREADS = 2;

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicLong dropped = new AtomicLong();
    private final Consumer<String> busConsumer = this::onBusMessage;
    private final ExecutorService senders = Executors.newFixedThreadPool(SENDER_THREADS, runnable -> {
        Thread thread = new Thread(runnable, "ws-log-sender");
        thread.setDaemon(true);
        return thread;
    });

    @PostConstruct
    public void subscribe() {
        SyncEventBus.subscribe(busConsumer);
        log.info("实时日志已订阅 SyncEventBus（按 run 标签分发；发送在独立线程，不回压引擎；会话队列上限 {}）",
            SESSION_QUEUE_CAPACITY);
    }

    /**
     * 解析容量配置：非法值（非数字、{@code <= 0}、null）回落默认值并 WARN，
     * 保证类初始化永不抛异常把应用搞挂。包级可见便于单测直接覆盖保护逻辑。
     */
    static int resolveCapacity(String propertyName, int defaultValue) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.isBlank()) {
            return defaultValue;
        }
        Integer configured = null;
        try {
            configured = Integer.decode(raw.trim());
        } catch (NumberFormatException ignored) {
            // 交给下面统一 WARN：解析失败也要可见（QA 反馈：非数字此前是静默回落）
        }
        if (configured == null || configured <= 0) {
            log.warn("系统属性 {} 的值非法（{}），已回落到默认值 {}", propertyName, raw, defaultValue);
            return defaultValue;
        }
        if (configured != defaultValue) {
            log.info("系统属性 {} 覆盖为 {}（默认 {}）", propertyName, configured, defaultValue);
        }
        return configured;
    }

    /** 当前生效的每会话队列上限（观测/测试用） */
    public static int sessionQueueCapacity() {
        return SESSION_QUEUE_CAPACITY;
    }

    @PreDestroy
    public void unsubscribe() {
        SyncEventBus.unsubscribe(busConsumer);
        for (SessionState state : sessions.values()) {
            try {
                state.session.close(CloseStatus.SERVER_ERROR);
            } catch (Exception ignored) {
                // 关闭失败无需处理
            }
        }
        sessions.clear();
        senders.shutdownNow();
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        WebSocketSession concurrent = new ConcurrentWebSocketSessionDecorator(
            session, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        Filter filter = parseFilter(session.getUri());
        SessionState state = new SessionState(concurrent, filter);
        sessions.put(session.getId(), state);
        enqueue(state, filter == null
            ? "已连接实时日志（全局视图）"
            : "已连接实时日志（仅显示 task=" + filter.taskId()
                + (filter.recordId() == null ? "" : " record=" + filter.recordId()) + "）");
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("WebSocket 传输异常（会话 {}）：{}", session.getId(), exception.getMessage());
        sessions.remove(session.getId());
    }

    /** 当前在线会话数 */
    public int sessionCount() {
        return sessions.size();
    }

    /** 因会话队列满而丢弃的消息条数（观测用：> 0 说明有客户端跟不上，但同步不受影响） */
    public long droppedMessages() {
        return dropped.get();
    }

    private void onBusMessage(String raw) {
        RunLog.Tagged tagged = RunLog.decode(raw);
        String payload = tagged == null ? raw : tagged.payload();
        for (SessionState state : sessions.values()) {
            if (tagged != null && state.filter != null && !state.filter.accepts(tagged)) {
                continue;
            }
            enqueue(state, payload);
        }
    }

    /** 只入队，绝不阻塞（本方法可能运行在引擎线程/调度线程上） */
    private void enqueue(SessionState state, String payload) {
        if (payload == null) {
            return;
        }
        if (!state.queue.offer(payload)) {
            // 队列满：丢最旧、保最新
            if (state.queue.poll() != null) {
                dropped.incrementAndGet();
            }
            state.queue.offer(payload);
        }
        scheduleDrain(state);
    }

    private void scheduleDrain(SessionState state) {
        if (!state.draining.compareAndSet(false, true)) {
            return;
        }
        try {
            senders.execute(() -> drain(state));
        } catch (RejectedExecutionException e) {
            state.draining.set(false);
            log.debug("发送线程池已满，本批实时日志被丢弃");
        }
    }

    private void drain(SessionState state) {
        try {
            String payload;
            while ((payload = state.queue.poll()) != null) {
                send(state.session, payload);
            }
        } finally {
            state.draining.set(false);
        }
        // 收尾竞态兜底：drain 结束的瞬间又入队了新消息
        if (!state.queue.isEmpty()) {
            scheduleDrain(state);
        }
    }

    private void send(WebSocketSession session, String payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception e) {
            // 慢客户端/断开都不应影响同步执行
            log.debug("WebSocket 推送失败（会话 {}）：{}", session.getId(), e.getMessage());
        }
    }

    private static Filter parseFilter(URI uri) {
        if (uri == null || uri.getQuery() == null || uri.getQuery().isBlank()) {
            return null;
        }
        Long taskId = null;
        Long recordId = null;
        for (String pair : uri.getQuery().split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) {
                continue;
            }
            String key = pair.substring(0, idx);
            String value = pair.substring(idx + 1);
            try {
                if ("taskId".equals(key)) {
                    taskId = Long.valueOf(value);
                } else if ("recordId".equals(key)) {
                    recordId = Long.valueOf(value);
                }
            } catch (NumberFormatException ignored) {
                // 非法参数视为不过滤
            }
        }
        return taskId == null && recordId == null ? null : new Filter(taskId, recordId);
    }

    /** 会话过滤条件：taskId / recordId 任一匹配即接收 */
    record Filter(Long taskId, Long recordId) {
        boolean accepts(RunLog.Tagged tagged) {
            if (taskId != null && !taskId.equals(tagged.taskId())) {
                return false;
            }
            return recordId == null || recordId.equals(tagged.recordId());
        }
    }

    /** 单会话发送状态：有界队列 + 是否已有 drain 任务在跑 */
    private static final class SessionState {
        private final WebSocketSession session;
        private final Filter filter;
        private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(SESSION_QUEUE_CAPACITY);
        private final AtomicBoolean draining = new AtomicBoolean();

        private SessionState(WebSocketSession session, Filter filter) {
            this.session = session;
            this.filter = filter;
        }
    }
}
