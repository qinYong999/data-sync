package com.datasync.server.config;

import com.datasync.server.log.RunLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket 会话过滤：A 任务的实时日志不能串到 B 任务的会话。
 *
 * <p>过滤规则与 {@code SyncLogWebSocketHandler} 内部分发逻辑一致：
 * 会话带 {@code taskId}/{@code recordId} 时只接收标签匹配的消息；不带过滤则接收全部。</p>
 */
class SyncLogWebSocketFilterTest {

    @Test
    @DisplayName("按 taskId 过滤：只接收该任务的消息")
    void filtersByTaskId() {
        SyncLogWebSocketHandler.Filter filter = new SyncLogWebSocketHandler.Filter(4L, null);

        assertThat(filter.accepts(new RunLog.Tagged(4L, 9L, "任务4的日志"))).isTrue();
        assertThat(filter.accepts(new RunLog.Tagged(5L, 10L, "任务5的日志"))).isFalse();
    }

    @Test
    @DisplayName("按 recordId 过滤：同任务的不同执行互不干扰")
    void filtersByRecordId() {
        SyncLogWebSocketHandler.Filter filter = new SyncLogWebSocketHandler.Filter(null, 9L);

        assertThat(filter.accepts(new RunLog.Tagged(4L, 9L, "本次执行"))).isTrue();
        assertThat(filter.accepts(new RunLog.Tagged(4L, 11L, "另一次执行"))).isFalse();
    }

    @Test
    @DisplayName("taskId + recordId 同时过滤：两个条件都要满足")
    void filtersByBoth() {
        SyncLogWebSocketHandler.Filter filter = new SyncLogWebSocketHandler.Filter(4L, 9L);

        assertThat(filter.accepts(new RunLog.Tagged(4L, 9L, "命中"))).isTrue();
        assertThat(filter.accepts(new RunLog.Tagged(4L, 10L, "record 不符"))).isFalse();
        assertThat(filter.accepts(new RunLog.Tagged(5L, 9L, "task 不符"))).isFalse();
    }
}
