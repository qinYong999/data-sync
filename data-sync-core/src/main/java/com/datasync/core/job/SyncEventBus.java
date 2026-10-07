package com.datasync.core.job;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 同步事件总线（契约 §3.7，弱化为可选）。
 *
 * <p>线程安全与"订阅者不泄漏"是这个类唯一的两个设计目标：
 * <ul>
 *   <li>底层用 {@link CopyOnWriteArrayList}：发布时遍历的是快照，订阅者在回调里
 *       {@code unsubscribe} 自己或别人都不会破坏遍历（旧的 {@code forEach} 实现会）。</li>
 *   <li>发布时逐个消费者 try/catch：某个 WebSocket 通道异常绝不能让同步任务失败，
 *       更不能让后面的订阅者收不到消息。</li>
 *   <li>{@link #publish} 是高频路径，不加锁；{@link #subscribe}/{@link #unsubscribe} 有写锁开销，
 *       但订阅动作只在连接建立/断开时发生，量级远小于发布。</li>
 * </ul>
 */
public final class SyncEventBus {

    private static final Logger log = LoggerFactory.getLogger(SyncEventBus.class);

    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    private SyncEventBus() {
    }

    /** 订阅（同一个消费者重复订阅只会保留一份，避免连接重连导致消息翻倍）。 */
    public static void subscribe(Consumer<String> consumer) {
        if (consumer == null) {
            return;
        }
        if (!LISTENERS.contains(consumer)) {
            LISTENERS.add(consumer);
        }
    }

    /** 退订；调用方应在连接关闭时调用，否则订阅者会一直被强引用（内存泄漏）。 */
    public static void unsubscribe(Consumer<String> consumer) {
        if (consumer == null) {
            return;
        }
        LISTENERS.remove(consumer);
    }

    /** 发布一条消息；message 为 null 时忽略。任何消费者抛异常都被吞掉并记 warn。 */
    public static void publish(String message) {
        if (message == null) {
            return;
        }
        for (Consumer<String> listener : LISTENERS) {
            try {
                listener.accept(message);
            } catch (Throwable t) {
                log.warn("同步事件消费者异常（已隔离，不影响其他订阅者与同步任务）：{}", t.toString());
            }
        }
    }

    /** 当前订阅者数量（监控/测试用）。 */
    public static int subscriberCount() {
        return LISTENERS.size();
    }

    /** 清空订阅者（应用关闭或测试隔离用）。 */
    public static void clear() {
        LISTENERS.clear();
    }
}
