package com.datasync.server.log;

import com.datasync.core.job.SyncEventBus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D1：慢消费者（模拟被背压的 WebSocket 客户端）**不得阻塞投递方线程**。
 *
 * <p>复现方式与 engine 的埋点实验一致：挂一个每次回调阻塞 700ms 的消费者，
 * 然后在"引擎线程"上打 2000+ 条进度。修复前这里会花掉 2000×700ms；
 * 修复后投递方只做一次有界队列 offer，立即返回，队列满则丢最旧并计数。</p>
 */
class RunLogNonBlockingTest {

    @Test
    @DisplayName("慢消费者不会阻塞投递方；积压超容量时丢中间态并计数")
    void slowConsumerDoesNotBlockPublisher() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Consumer<String> slowConsumer = message -> {
            // 只让第一条真正慢：足以把调度线程卡住、让队列积压
            if (calls.getAndIncrement() == 0) {
                try {
                    Thread.sleep(700L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        SyncEventBus.subscribe(slowConsumer);
        long droppedBefore = RunLog.droppedMessages();
        try {
            int messages = 2300; // > 队列容量 2000，必然触发丢弃
            long start = System.nanoTime();
            for (int i = 0; i < messages; i++) {
                RunLog.publish(1L, 1L, "进度 " + i);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(elapsedMs)
                .as("投递 %d 条进度必须立即返回（若被慢消费者回压，这里会 > %d ms）", messages, messages * 700)
                .isLessThan(1000L);
            assertThat(RunLog.droppedMessages())
                .as("队列满时应丢最旧并计数，而不是阻塞")
                .isGreaterThan(droppedBefore);
            System.out.println("[D1] 慢消费者(700ms/次)在线时，投递 " + messages + " 条进度耗时 " + elapsedMs
                + " ms，丢弃 " + (RunLog.droppedMessages() - droppedBefore) + " 条，峰值积压 "
                + RunLog.peakQueueDepth() + " 条");
            // D8：丢弃计数在正常负载下恒为 0，所以额外提供"永远有信息量"的背压指示——历史最高水位。
            // 慢消费者在线时它必须被抬起来（证明这个 gauge 不是死的）。
            assertThat(RunLog.peakQueueDepth())
                .as("慢消费者在线时，积压水位必须被观测到（> 0）")
                .isGreaterThan(0);
        } finally {
            SyncEventBus.unsubscribe(slowConsumer);
            // 排空积压，避免影响其它测试（调度线程是同一个静态线程）
            assertThat(RunLog.flush(10_000L)).as("积压应在超时前排空").isTrue();
        }
    }

    @Test
    @DisplayName("队列上限可配：默认 2000；非法值（0/负数/非数字）回落到默认值且不抛异常")
    void queueCapacityIsConfigurableWithSafeFallback() {
        String property = RunLog.QUEUE_CAPACITY_PROPERTY;
        assertThat(RunLog.queueCapacity()).as("默认值必须与历史版本一致").isEqualTo(2000);
        try {
            System.setProperty(property, "8");
            assertThat(RunLog.resolveCapacity(property, 2000)).as("合法值应生效").isEqualTo(8);
            System.setProperty(property, "0");
            assertThat(RunLog.resolveCapacity(property, 2000)).as("0 必须回落默认值").isEqualTo(2000);
            System.setProperty(property, "-5");
            assertThat(RunLog.resolveCapacity(property, 2000)).as("负数必须回落默认值").isEqualTo(2000);
            System.setProperty(property, "not-a-number");
            assertThat(RunLog.resolveCapacity(property, 2000)).as("非数字必须回落默认值（不能抛异常）").isEqualTo(2000);
        } finally {
            System.clearProperty(property);
        }
        assertThat(RunLog.resolveCapacity(property, 2000)).as("未设置时用默认值").isEqualTo(2000);
    }

    @Test
    @DisplayName("正常消费者仍能收到消息（异步投递不丢正常路径）")
    void messagesAreStillDeliveredToHealthyConsumer() throws Exception {
        java.util.List<String> received = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(3);
        Consumer<String> healthy = message -> {
            received.add(message);
            latch.countDown();
        };
        SyncEventBus.subscribe(healthy);
        try {
            RunLog.publish(7L, 9L, "第一条");
            RunLog.publish(7L, 9L, "第二条");
            RunLog.publishSystem("系统消息");
            assertThat(latch.await(5, TimeUnit.SECONDS)).as("健康消费者应在超时前收到全部消息").isTrue();
        } finally {
            SyncEventBus.unsubscribe(healthy);
        }
        assertThat(received).anyMatch(m -> m.contains("第一条"));
        assertThat(received).anyMatch(m -> m.contains("第二条"));
        assertThat(received).anyMatch(m -> m.contains("系统消息"));
    }
}
