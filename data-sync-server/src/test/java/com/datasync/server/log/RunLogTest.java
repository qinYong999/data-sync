package com.datasync.server.log;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 事件总线按 run 打标签：A 任务的日志绝不能串到 B 任务的会话。
 */
class RunLogTest {

    @Test
    @DisplayName("带标签的消息可解析出 taskId/recordId 与剥离标签后的正文")
    void decodeTaggedMessage() {
        RunLog.Tagged tagged = RunLog.decode(RunLog.tag(4L, 9L) + "已写入 1000 行");

        assertThat(tagged).isNotNull();
        assertThat(tagged.taskId()).isEqualTo(4L);
        assertThat(tagged.recordId()).isEqualTo(9L);
        assertThat(tagged.payload()).isEqualTo("已写入 1000 行");
    }

    @Test
    @DisplayName("无标签的全局消息解析为 null（由调用方决定广播）")
    void decodeUntaggedMessage() {
        assertThat(RunLog.decode("服务已启动")).isNull();
        assertThat(RunLog.decode(null)).isNull();
    }

    @Test
    @DisplayName("标签可被 SyncEventBus 原样传递，订阅者拿到的是打标后的文本")
    void publishThroughEventBus() throws Exception {
        List<String> received = new ArrayList<>();
        CountDownLatch latch = new CountDownLatch(1);
        java.util.function.Consumer<String> consumer = message -> {
            received.add(message);
            latch.countDown();
        };
        com.datasync.core.job.SyncEventBus.subscribe(consumer);
        try {
            RunLog.publish(7L, 11L, "▶ 开始执行");
            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
        } finally {
            com.datasync.core.job.SyncEventBus.unsubscribe(consumer);
        }
        assertThat(received).hasSize(1);
        RunLog.Tagged tagged = RunLog.decode(received.get(0));
        assertThat(tagged).isNotNull();
        assertThat(tagged.taskId()).isEqualTo(7L);
        assertThat(tagged.recordId()).isEqualTo(11L);
        assertThat(tagged.payload()).isEqualTo("▶ 开始执行");
        assertThat(received.get(0)).startsWith("[#run task=7 record=11]");
    }

    @Test
    @DisplayName("标签是纯文本，写进日志文件也看得懂")
    void tagIsHumanReadable() {
        assertThat(RunLog.tag(1L, 2L)).isEqualTo("[#run task=1 record=2] ");
        assertThat(LocalDateTime.now()).isNotNull();
    }
}
