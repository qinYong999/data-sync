package com.datasync.core.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 异步进度采样器（Lead 决策 2）：验"永不阻塞调用方 + 最终进度必达 + 丢弃可观测"。
 */
class AsyncRunMetricsListenerTest {

    private static ChunkProgress progress(long written) {
        return new ChunkProgress(written, written, 0, "k" + written, null, written);
    }

    @Test
    @DisplayName("快速回调：全部投递，零丢弃，收尾后线程退出")
    void fastDelegateDeliversEverything() {
        List<Long> received = new CopyOnWriteArrayList<>();
        AsyncRunMetricsListener wrapper = AsyncRunMetricsListener.wrapping(p -> received.add(p.writtenRows()), 8);
        for (int i = 1; i <= 5; i++) {
            wrapper.onChunk(progress(i));
        }
        wrapper.flushAndClose(2000);

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L), new ArrayList<>(received));
        assertEquals(5, wrapper.deliveredCallbacks());
        assertEquals(0, wrapper.droppedCallbacks());
        assertTrue(wrapper.isTerminated(), "收尾后内部线程必须已退出");
    }

    @Test
    @DisplayName("慢回调（300ms/次）下 onChunk 永不阻塞：200 次提交耗时 < 500ms，且丢弃被记账")
    void neverBlocksCaller() {
        AsyncRunMetricsListener wrapper = AsyncRunMetricsListener.wrapping(p -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 4);

        long t0 = System.nanoTime();
        for (int i = 1; i <= 200; i++) {
            wrapper.onChunk(progress(i));
        }
        long submitMillis = (System.nanoTime() - t0) / 1_000_000;

        assertTrue(submitMillis < 500, "提交 200 次只应花毫秒级，实际 " + submitMillis + "ms");
        assertTrue(wrapper.droppedCallbacks() > 0, "慢回调必然丢中间态");
        assertEquals(200, wrapper.submittedCallbacks());
        wrapper.flushAndClose(2000);
        assertEquals(wrapper.submittedCallbacks(),
                wrapper.deliveredCallbacks() + wrapper.droppedCallbacks(), "投递 + 丢弃必须等于提交总数");
    }

    @Test
    @DisplayName("队列满时丢最旧的中间态、保留最新；收尾只投递最新一件")
    void dropsOldestAndDeliversNewest() throws Exception {
        List<Long> received = new CopyOnWriteArrayList<>();
        CountDownLatch gate = new CountDownLatch(1);
        AsyncRunMetricsListener wrapper = AsyncRunMetricsListener.wrapping(p -> {
            try {
                gate.await(3, TimeUnit.SECONDS);   // 第一件卡住，逼出"队列满"
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            received.add(p.writtenRows());
        }, 3);

        for (int i = 1; i <= 50; i++) {
            wrapper.onChunk(progress(i));
        }
        gate.countDown();
        wrapper.flushAndClose(3000);

        assertFalse(received.isEmpty());
        assertEquals(50L, received.get(received.size() - 1),
                "收尾必须投递**最新**的进度（不是最旧的）: " + received);
        assertTrue(wrapper.droppedCallbacks() > 0, "中间态必须有丢弃计数");
    }

    @Test
    @DisplayName("回调抛异常被吞掉并计入丢弃，不影响调用方")
    void delegateExceptionSwallowed() {
        AtomicInteger calls = new AtomicInteger();
        AsyncRunMetricsListener wrapper = AsyncRunMetricsListener.wrapping(p -> {
            calls.incrementAndGet();
            throw new IllegalStateException("模拟回调内部异常");
        }, 4);
        wrapper.onChunk(progress(1));
        wrapper.flushAndClose(2000);
        assertTrue(calls.get() >= 1);
        assertTrue(wrapper.droppedCallbacks() >= 1);
        assertTrue(wrapper.isTerminated());
    }

    @Test
    @DisplayName("close 幂等；关闭后的提交是 no-op；null 进度被忽略")
    void closeSemantics() {
        List<Long> received = new CopyOnWriteArrayList<>();
        AsyncRunMetricsListener wrapper = AsyncRunMetricsListener.wrapping(p -> received.add(p.writtenRows()), 4);
        wrapper.onChunk(progress(1));
        wrapper.flushAndClose(1000);
        long submittedAfterClose = wrapper.submittedCallbacks();

        wrapper.onChunk(progress(2));      // 关闭后：忽略
        wrapper.onChunk(null);             // null：忽略
        wrapper.close();                   // 幂等
        wrapper.close();

        assertEquals(submittedAfterClose, wrapper.submittedCallbacks(), "关闭后不应再计入提交");
        assertEquals(List.of(1L), new ArrayList<>(received));
    }

    @Test
    @DisplayName("内部线程必须是 daemon 且名字可辨识（不阻止 JVM 退出）")
    void threadIsDaemonAndNamed() throws Exception {
        AsyncRunMetricsListener wrapper = AsyncRunMetricsListener.wrapping(p -> { }, 2, "task-42");
        try {
            Thread worker = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("datasync-metrics"))
                    .findFirst().orElse(null);
            assertTrue(worker != null, "应存在 datasync-metrics 线程");
            assertTrue(worker.isDaemon(), "内部线程必须是 daemon");
            assertTrue(worker.getName().contains("task-42"), "线程名应带任务标识: " + worker.getName());
        } finally {
            wrapper.flushAndClose(500);
        }
    }

    @Test
    @DisplayName("delegate 为空必须立刻报错（中文消息）")
    void nullDelegateRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> AsyncRunMetricsListener.wrapping(null, 4));
        assertTrue(e.getMessage().contains("不能为空"), e.getMessage());
    }

    @Test
    @DisplayName("容量非法时按 1 处理，不会抛异常")
    void capacityIsClamped() {
        AsyncRunMetricsListener wrapper = AsyncRunMetricsListener.wrapping(p -> { }, 0);
        wrapper.onChunk(progress(1));
        wrapper.flushAndClose(500);
        assertTrue(wrapper.isTerminated());
    }

    @Test
    @DisplayName("wrapping 后仍满足 @FunctionalInterface 的用法（可直接当 listener 传）")
    void usableAsListener() {
        RunMetricsListener listener = AsyncRunMetricsListener.wrapping(p -> { }, 2);
        listener.onChunk(progress(1));
        ((AsyncRunMetricsListener) listener).flushAndClose(500);
    }
}
