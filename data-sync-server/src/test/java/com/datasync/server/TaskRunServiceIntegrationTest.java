package com.datasync.server;

import com.datasync.core.engine.RunMetricsListener;
import com.datasync.core.engine.SyncEngine;
import com.datasync.core.model.SyncRunResult;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.server.entity.SyncRecordEntity;
import com.datasync.server.log.RunLog;
import com.datasync.server.service.TaskRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D11 互斥 + 水位安全的集成验证（用可阻塞的假引擎，精确控制执行时序）。
 *
 * <p>覆盖 Lead 要求的"水位未推进"证据链：失败不推进、取消不推进；以及验收项 5
 * "同一任务并发触发 5 次只有 1 个实例 RUNNING"。</p>
 *
 * <p>本类使用任务 ID 9201（见基类的隔离约定）。</p>
 */
class TaskRunServiceIntegrationTest extends AbstractServerIntegrationTest {

    private static final long TASK_ID = 9201L;

    /** 可阻塞的假引擎：进入 run 后等待放行，便于并发/取消断言 */
    static class StubEngine implements SyncEngine {

        final CountDownLatch entered = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger runCount = new AtomicInteger();
        final AtomicLong cancelledTaskId = new AtomicLong(-1L);
        volatile SyncRunResult nextResult;
        /** 引擎线程实际拿到的监听器（用于验证回调路径的非阻塞性，D1） */
        volatile RunMetricsListener lastListener;

        @Override
        public SyncRunResult run(SyncTaskConfig config, DataSource source, DataSource target, RunMetricsListener listener) {
            lastListener = listener;
            runCount.incrementAndGet();
            entered.countDown();
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return nextResult;
        }

        @Override
        public void cancel(long taskId) {
            cancelledTaskId.set(taskId);
            release.countDown();
        }

        void reset() {
            release = new CountDownLatch(1);
            runCount.set(0);
            cancelledTaskId.set(-1L);
            nextResult = null;
            // entered 是 final：只在第一次使用时生效，后续用例不再依赖它
        }

        static SyncRunResult completed(String endCursor) {
            SyncRunResult result = new SyncRunResult();
            result.setSuccess(true);
            result.setStatus("COMPLETED");
            result.setReadRows(10);
            result.setWrittenRows(10);
            result.setEndCursor(endCursor);
            result.setTotalMillis(12);
            result.setErrors(List.of());
            return result;
        }

        static SyncRunResult failed() {
            SyncRunResult result = new SyncRunResult();
            result.setSuccess(false);
            result.setStatus("FAILED");
            result.setReadRows(4);
            result.setWrittenRows(0);
            // 故意给出 endCursor：TaskRunService 在失败时也绝不能推进水位
            result.setEndCursor("999999");
            result.setErrorMessage("目标表字段类型不兼容");
            result.setErrors(List.of());
            return result;
        }

        static SyncRunResult cancelled() {
            SyncRunResult result = new SyncRunResult();
            result.setSuccess(false);
            result.setStatus("CANCELLED");
            result.setReadRows(3);
            result.setWrittenRows(3);
            result.setEndCursor("999999");
            result.setErrorMessage("任务已取消");
            result.setErrors(List.of());
            return result;
        }
    }

    @TestConfiguration
    static class StubEngineConfiguration {
        static final StubEngine ENGINE = new StubEngine();

        @Bean
        @Primary
        SyncEngine stubSyncEngine() {
            return ENGINE;
        }
    }

    @Autowired
    private TaskRunService taskRunService;

    private StubEngine engine() {
        return StubEngineConfiguration.ENGINE;
    }

    @BeforeEach
    void prepareData() throws Exception {
        engine().reset();
        createCommonDatasources();
        ensureTable("t_src_9201");
        ensureTable("t_dst_9201");
        createTask(TASK_ID, "并发测试任务", "t_src_9201", "t_dst_9201", "FULL", null, null);

        // 隔离自检：本类的用例共用同一个 TaskRunService 单例。
        // 若上一个用例留下了未结束的执行，本类的并发用例会表现为"5 次触发全被拒绝"，
        // 因此这里先等静止并给出明确的失败信息（而不是让下游用例给出误导性的断言失败）。
        long deadline = System.currentTimeMillis() + 15_000L;
        while (taskRunService.runningCount() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertThat(taskRunService.runningCount())
            .as("测试隔离自检：上一个用例的执行尚未结束，仍在运行的任务=" + taskRunService.runningTaskIds())
            .isZero();
    }

    @Test
    @DisplayName("验收项 5：同一任务并发触发 5 次 → 只有 1 次被受理，其余给明确中文提示")
    void concurrentTriggersOnlyOneAccepted() throws Exception {
        int attempts = 5;
        ExecutorService pool = Executors.newFixedThreadPool(attempts);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<TaskRunService.TriggerResult>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < attempts; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await(5, TimeUnit.SECONDS);
                    return taskRunService.triggerManual(TASK_ID);
                }));
            }
            startGate.countDown();

            List<TaskRunService.TriggerResult> results = new ArrayList<>();
            for (Future<TaskRunService.TriggerResult> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }

            long accepted = results.stream().filter(TaskRunService.TriggerResult::accepted).count();
            String rejections = results.stream().filter(r -> !r.accepted())
                .map(r -> r.code() + "：" + r.message())
                .distinct()
                .collect(Collectors.joining(" | "));
            assertThat(accepted)
                .as("同一任务并发触发只能受理 1 次（拒绝原因：%s）", rejections)
                .isEqualTo(1);
            results.stream().filter(r -> !r.accepted()).forEach(r -> {
                assertThat(r.code()).isEqualTo("TASK_RUNNING");
                assertThat(r.message()).contains("任务正在执行中");
            });

            // 提交与执行是异步的：等 worker 真正进入引擎，再断言"只跑了一次"
            long deadline = System.currentTimeMillis() + 10_000L;
            while (engine().runCount.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20L);
            }
            assertThat(engine().runCount.get()).as("只有被受理的那一次真正进入了引擎").isEqualTo(1);
            assertThat(taskRunService.isRunning(TASK_ID)).isTrue();

            Long runningRecords = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM sync_record WHERE task_id = ? AND status = 'RUNNING'", Long.class, TASK_ID);
            assertThat(runningRecords).as("数据库里同时只能有 1 条 RUNNING 记录").isEqualTo(1L);
        } finally {
            engine().release.countDown();
            pool.shutdownNow();
        }
        awaitFinished(TASK_ID);
    }

    @Test
    @DisplayName("取消：引擎收到 cancel，记录落 CANCELLED，且水位不推进")
    void cancelDoesNotAdvanceCursor() throws Exception {
        createTask(TASK_ID, "取消任务", "t_src_9201", "t_dst_9201", "INCR", "id", "500");
        String cursorBefore = cursorOf(TASK_ID);
        engine().nextResult = StubEngine.cancelled();

        TaskRunService.TriggerResult trigger = taskRunService.triggerManual(TASK_ID);
        assertThat(trigger.accepted()).isTrue();
        Long recordId = trigger.recordId();
        awaitRunning(TASK_ID);

        assertThat(taskRunService.cancel(TASK_ID)).isTrue();
        awaitFinished(TASK_ID);

        assertThat(engine().cancelledTaskId.get()).isEqualTo(TASK_ID);
        assertThat(recordStatus(recordId)).isEqualTo(SyncRecordEntity.STATUS_CANCELLED);
        assertThat(cursorOf(TASK_ID)).as("取消绝不能推进水位").isEqualTo(cursorBefore).isEqualTo("500");
    }

    @Test
    @DisplayName("失败：即使引擎给出 endCursor 也不推进水位")
    void failureDoesNotAdvanceCursor() throws Exception {
        createTask(TASK_ID, "失败任务", "t_src_9201", "t_dst_9201", "INCR", "id", "500");
        engine().nextResult = StubEngine.failed();

        TaskRunService.TriggerResult trigger = taskRunService.triggerManual(TASK_ID);
        assertThat(trigger.accepted()).isTrue();
        awaitRunning(TASK_ID);
        engine().release.countDown();
        awaitFinished(TASK_ID);

        assertThat(recordStatus(trigger.recordId())).isEqualTo(SyncRecordEntity.STATUS_FAILED);
        assertThat(cursorOf(TASK_ID)).as("失败绝不能推进水位").isEqualTo("500");
    }

    @Test
    @DisplayName("成功：水位推进到 endCursor，且 run_key 释放为 {taskId}-{recordId}")
    void successAdvancesCursorAndReleasesRunKey() throws Exception {
        createTask(TASK_ID, "成功任务", "t_src_9201", "t_dst_9201", "INCR", "id", "500");
        engine().nextResult = StubEngine.completed("900");

        TaskRunService.TriggerResult trigger = taskRunService.triggerManual(TASK_ID);
        awaitRunning(TASK_ID);
        engine().release.countDown();
        awaitFinished(TASK_ID);

        assertThat(recordStatus(trigger.recordId())).isEqualTo(SyncRecordEntity.STATUS_COMPLETED);
        assertThat(cursorOf(TASK_ID)).isEqualTo("900");
        assertThat(runKeyOf(trigger.recordId())).isEqualTo(TASK_ID + "-" + trigger.recordId());
    }

    @Test
    @DisplayName("优雅停机：cancelAllAndAwait 取消运行中任务，落 CANCELLED 且水位不推进")
    void gracefulShutdownCancelsRunningRunWithoutAdvancingCursor() throws Exception {
        createTask(TASK_ID, "停机任务", "t_src_9201", "t_dst_9201", "INCR", "id", "500");
        engine().nextResult = StubEngine.cancelled();

        TaskRunService.TriggerResult trigger = taskRunService.triggerManual(TASK_ID);
        assertThat(trigger.accepted()).isTrue();
        awaitRunning(TASK_ID);

        // 等价于 GracefulShutdownListener 在 ContextClosedEvent 上执行的收尾
        taskRunService.cancelAllAndAwait(10);

        assertThat(engine().cancelledTaskId.get()).isEqualTo(TASK_ID);
        assertThat(taskRunService.runningCount()).as("停机收尾后不应再有运行中任务").isZero();
        assertThat(recordStatus(trigger.recordId())).isEqualTo(SyncRecordEntity.STATUS_CANCELLED);
        assertThat(cursorOf(TASK_ID)).as("停机取消绝不能推进水位").isEqualTo("500");
        // 停机时必须释放运行许可，否则重启前该任务再也触发不了
        engine().reset();
        assertThat(taskRunService.triggerManual(TASK_ID).accepted()).as("许可必须已释放").isTrue();
        awaitRunning(TASK_ID);
        engine().release.countDown();
        awaitFinished(TASK_ID);
    }

    @Test
    @DisplayName("D1：慢客户端不得拖慢引擎线程 —— 引擎拿到的回调路径必须非阻塞")
    void slowClientDoesNotBlockEngineCallbackPath() throws Exception {
        createTask(TASK_ID, "背压测试任务", "t_src_9201", "t_dst_9201", "INCR", "id", "500");
        engine().nextResult = StubEngine.completed(null);

        // 模拟"挂着的浏览器"：与 engine 埋点实验同源，首条回调阻塞 700ms
        AtomicInteger calls = new AtomicInteger();
        java.util.function.Consumer<String> slowClient = message -> {
            if (calls.getAndIncrement() == 0) {
                try {
                    Thread.sleep(700L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        com.datasync.core.job.SyncEventBus.subscribe(slowClient);
        long droppedBefore = RunLog.droppedMessages();
        try {
            TaskRunService.TriggerResult trigger = taskRunService.triggerManual(TASK_ID);
            assertThat(trigger.accepted()).isTrue();
            awaitRunning(TASK_ID);

            RunMetricsListener engineSideListener = engine().lastListener;
            assertThat(engineSideListener).as("引擎线程应拿到包装后的监听器").isNotNull();

            int chunks = 200;
            long start = System.nanoTime();
            for (int i = 0; i < chunks; i++) {
                engineSideListener.onChunk(
                    new com.datasync.core.engine.ChunkProgress(i * 1000L, i * 1000L, 0L, "k" + i, String.valueOf(i), i));
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000L;

            assertThat(elapsedMs)
                .as("%d 次进度回调必须立即返回（若被慢客户端回压，这里会 > %d ms）", chunks, chunks * 700)
                .isLessThan(1000L);
            System.out.println("[D1] 慢客户端(700ms/次)在线时，" + chunks + " 次进度回调耗时 " + elapsedMs
                + " ms（修复前应为 " + (chunks * 700) + " ms 量级）");

            engine().release.countDown();
            awaitFinished(TASK_ID);
        } finally {
            com.datasync.core.job.SyncEventBus.unsubscribe(slowClient);
            RunLog.flush(10_000L);
        }
    }

    /** 等"已经开始执行"（isRunning 为真），避免在没有真正进入 run 时就断言 */
    private void awaitRunning(long taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000L;
        while (!taskRunService.isRunning(taskId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(taskRunService.isRunning(taskId)).as("任务应已进入运行状态").isTrue();
    }

    private void awaitFinished(long taskId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (taskRunService.isRunning(taskId) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50L);
        }
        assertThat(taskRunService.isRunning(taskId)).as("任务应在超时前结束").isFalse();
    }
}
