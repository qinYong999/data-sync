package com.datasync.core.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 事件总线：线程安全 + 异常隔离 + 订阅者不泄漏（任务要求 §3.7）。
 */
class SyncEventBusTest {

    @AfterEach
    void cleanup() {
        SyncEventBus.clear();
    }

    @Test
    @DisplayName("订阅 / 发布 / 退订")
    void basic() {
        List<String> received = new ArrayList<>();
        Consumer<String> consumer = received::add;
        SyncEventBus.subscribe(consumer);
        SyncEventBus.publish("a");
        assertEquals(List.of("a"), received);
        SyncEventBus.unsubscribe(consumer);
        SyncEventBus.publish("b");
        assertEquals(List.of("a"), received);
        assertEquals(0, SyncEventBus.subscriberCount());
    }

    @Test
    @DisplayName("重复订阅只保留一份（重连不会让消息翻倍）")
    void duplicateSubscribe() {
        AtomicInteger count = new AtomicInteger();
        Consumer<String> consumer = m -> count.incrementAndGet();
        SyncEventBus.subscribe(consumer);
        SyncEventBus.subscribe(consumer);
        SyncEventBus.publish("x");
        assertEquals(1, count.get());
        assertEquals(1, SyncEventBus.subscriberCount());
    }

    @Test
    @DisplayName("消费者抛异常被隔离：不影响其他订阅者")
    void consumerIsolation() {
        List<String> received = new ArrayList<>();
        SyncEventBus.subscribe(m -> {
            throw new IllegalStateException("模拟 WebSocket 通道异常");
        });
        SyncEventBus.subscribe(received::add);
        SyncEventBus.publish("hello");
        assertEquals(List.of("hello"), received);
    }

    @Test
    @DisplayName("订阅者在回调里退订自己不会破坏遍历")
    void selfUnsubscribe() {
        List<String> received = new ArrayList<>();
        Consumer<String> self = new Consumer<>() {
            @Override
            public void accept(String m) {
                received.add(m);
                SyncEventBus.unsubscribe(this);
            }
        };
        SyncEventBus.subscribe(self);
        SyncEventBus.subscribe(received::add);
        SyncEventBus.publish("1");
        SyncEventBus.publish("2");
        assertEquals(1, SyncEventBus.subscriberCount(), "自退订的那个应已移除，另一个仍在");
        assertEquals(3, received.size(), "第一次发布两个订阅者各收一次，第二次只剩一个: " + received);
    }

    @Test
    @DisplayName("并发发布/订阅不抛异常")
    void concurrentPublish() throws InterruptedException {
        List<String> received = java.util.Collections.synchronizedList(new ArrayList<>());
        SyncEventBus.subscribe(received::add);
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                for (int j = 0; j < 50; j++) {
                    SyncEventBus.publish("m" + j);
                }
            });
            t.start();
            workers.add(t);
        }
        start.countDown();
        for (Thread t : workers) {
            t.join();
        }
        assertEquals(threads * 50, received.size());
    }

    @Test
    @DisplayName("null 安全")
    void nullSafe() {
        SyncEventBus.subscribe(null);
        SyncEventBus.unsubscribe(null);
        SyncEventBus.unsubscribe(m -> { });
        SyncEventBus.publish(null);
        assertEquals(0, SyncEventBus.subscriberCount());
        assertTrue(true);
    }
}
