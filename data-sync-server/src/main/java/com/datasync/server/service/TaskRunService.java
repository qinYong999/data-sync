package com.datasync.server.service;

import com.datasync.core.engine.AsyncRunMetricsListener;
import com.datasync.core.engine.RunMetricsListener;
import com.datasync.core.engine.SyncEngine;
import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncError;
import com.datasync.core.model.SyncRunResult;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.server.config.AppProperties;
import com.datasync.server.entity.SyncRecordEntity;
import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.log.RunLog;
import com.datasync.server.repository.SyncTaskRepository;
import com.datasync.server.security.CredentialCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * 触发执行（手动/定时）、每任务互斥、有界线程池、记录落库、事件推送（契约 §4.1 / D11）。
 *
 * <p>三层保障，缺一不可：</p>
 * <ol>
 *   <li><b>应用级许可</b>：{@code ConcurrentHashMap<taskId, Semaphore(1)>}，重复触发立即返回中文提示；</li>
 *   <li><b>数据库唯一约束</b>：{@code sync_record.run_key = "RUNNING-{taskId}"}，多实例部署也只有一个能插进去；</li>
 *   <li><b>有界线程池</b>：{@code app.sync.worker-threads} 决定并发度，队列满时明确拒绝而不是无声堆积。</li>
 * </ol>
 *
 * <p>水位（{@code sync_task.cursor_value}）只有"引擎成功且给出 endCursor"时才推进一次；
 * 失败/取消/停机一律不推进——这是修掉"读失败也推进 incrValue 导致永久丢数"的关键。</p>
 */
@Service
public class TaskRunService {

    private static final Logger log = LoggerFactory.getLogger(TaskRunService.class);

    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";

    /** 进度回调的异步队列容量（队列满丢最旧：进度是累计采样，中间态可丢） */
    private static final int LISTENER_QUEUE_CAPACITY = 64;
    /** run() 返回后等待最后一次进度投递的超时（毫秒） */
    private static final long LISTENER_FLUSH_TIMEOUT_MS = 2000L;

    private final SyncEngine engine;
    private final SyncTaskRepository taskRepo;
    private final ConnectionPoolRegistry poolRegistry;
    private final RunRecordStore recordStore;
    private final SyncTaskConfigMapper configMapper;
    private final ThreadPoolTaskExecutor executor;
    private final AppProperties properties;

    /**
     * taskId -> 应用级运行许可（D11）。
     *
     * <p><b>为什么是 {@link Semaphore} 而不是 {@code ReentrantLock}</b>：许可是在触发线程
     * （HTTP/Quartz）里获取、在 worker 线程里释放的。{@code ReentrantLock} 是<b>线程私有</b>的，
     * 跨线程 unlock 会抛 {@link IllegalMonitorStateException}；若为了保护而加
     * {@code isHeldByCurrentThread()} 判断，则释放被静默跳过 —— 结果就是"任务跑完一次以后
     * 永远显示正在执行中，再也触发不了"。{@code Semaphore} 不绑定持有线程，谁都能 release，
     * 这才是这条链路上正确的互斥原语。</p>
     */
    private final ConcurrentHashMap<Long, Semaphore> runPermits = new ConcurrentHashMap<>();
    /** taskId -> 运行中记录ID */
    private final ConcurrentHashMap<Long, Long> runningRuns = new ConcurrentHashMap<>();
    /** 已请求取消的 taskId（仅用于日志与展示） */
    private final Set<Long> cancelRequested = ConcurrentHashMap.newKeySet();

    public TaskRunService(SyncEngine engine, SyncTaskRepository taskRepo, ConnectionPoolRegistry poolRegistry,
                          RunRecordStore recordStore, SyncTaskConfigMapper configMapper,
                          @Qualifier("syncTaskExecutor") ThreadPoolTaskExecutor executor,
                          AppProperties properties) {
        this.engine = engine;
        this.taskRepo = taskRepo;
        this.poolRegistry = poolRegistry;
        this.recordStore = recordStore;
        this.configMapper = configMapper;
        this.executor = executor;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 触发

    /** 手动触发：受理后立即返回，实际执行在有界线程池里 */
    public TriggerResult triggerManual(Long taskId) {
        return trigger(taskId, TRIGGER_MANUAL);
    }

    /** 定时触发：绝不让异常冲进 Quartz 线程 */
    public TriggerResult triggerScheduled(Long taskId) {
        try {
            return trigger(taskId, TRIGGER_SCHEDULED);
        } catch (AppException e) {
            log.warn("定时触发任务 {} 失败：{}", taskId, e.getMessage());
            RunLog.publishSystem("✗ 定时触发任务 " + taskId + " 失败：" + e.getMessage());
            return TriggerResult.rejected(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("定时触发任务 {} 异常", taskId, e);
            return TriggerResult.rejected("TRIGGER_FAILED", "定时触发失败，请查看服务端日志");
        }
    }

    public TriggerResult trigger(Long taskId, String triggerType) {
        SyncTaskEntity task = taskRepo.findById(taskId)
            .orElseThrow(() -> AppException.notFound("任务不存在: " + taskId));

        // D11：每任务最多 1 个运行实例。DB 唯一索引(run_key=RUNNING-{taskId})硬性保证 1，
        // 因此这里即使配置写大了也按 1 生效（并把配置问题喊出来，而不是默默偏离）。
        int configured = properties.getSync().getMaxConcurrentPerTask();
        if (configured > 1) {
            log.warn("app.sync.max-concurrent-per-task={} 大于 1，但 D11 与数据库唯一约束要求每任务最多 1 个实例，已按 1 生效", configured);
        }
        Semaphore permit = runPermits.computeIfAbsent(taskId, ignored -> new Semaphore(1));
        if (!permit.tryAcquire()) {
            return TriggerResult.rejected("TASK_RUNNING", "任务正在执行中，已忽略本次触发");
        }

        SyncRecordEntity record;
        try {
            record = recordStore.tryStartRun(taskId, triggerType);
        } catch (RuntimeException e) {
            permit.release();
            throw e;
        }
        if (record == null) {
            // 应用级许可没拦住（多实例部署 / 上次进程残留），数据库唯一约束兜住了
            permit.release();
            return TriggerResult.rejected("TASK_RUNNING", "任务正在执行中（数据库已存在运行实例），已忽略本次触发");
        }

        runningRuns.put(taskId, record.getId());
        try {
            executor.execute(() -> executeRun(task.getId(), record.getId(), triggerType));
        } catch (RejectedExecutionException e) {
            runningRuns.remove(taskId);
            permit.release();
            String message = "同步线程池繁忙（并发上限 " + properties.getSync().getWorkerThreads() + "），本次触发被拒绝，请稍后重试";
            recordStore.finishRun(record.getId(), SyncRecordEntity.STATUS_FAILED, null, message);
            throw AppException.unavailable("SYSTEM_BUSY", message);
        }
        return TriggerResult.accepted(record.getId(), "任务已受理，正在执行");
    }

    // ------------------------------------------------------------------ 取消

    /**
     * 协作式取消（{@code POST /api/tasks/{id}/cancel}）。
     *
     * @return true = 确有运行中实例并已发出取消请求；false = 当前没有运行中实例
     */
    public boolean cancel(Long taskId) {
        Long recordId = runningRuns.get(taskId);
        if (recordId == null) {
            return false;
        }
        cancelRequested.add(taskId);
        engine.cancel(taskId);
        RunLog.publish(taskId, recordId, "⏹ 已请求取消，将在当前 chunk 提交后停止（水位不会推进）");
        log.info("已请求取消任务 {} 的执行（record {}）", taskId, recordId);
        return true;
    }

    // ------------------------------------------------------------------ 状态查询

    public boolean isRunning(Long taskId) {
        return runningRuns.containsKey(taskId);
    }

    public Long runningRecordId(Long taskId) {
        return runningRuns.get(taskId);
    }

    public int runningCount() {
        return runningRuns.size();
    }

    public Set<Long> runningTaskIds() {
        return Collections.unmodifiableSet(Set.copyOf(runningRuns.keySet()));
    }

    // ------------------------------------------------------------------ 执行主体

    private void executeRun(Long taskId, Long recordId, String triggerType) {
        String status = SyncRecordEntity.STATUS_FAILED;
        SyncRunResult result = null;
        String errorMessage = null;
        long startedAt = System.currentTimeMillis();
        long droppedAtStart = RunLog.droppedMessages();
        String taskName = "任务" + taskId;
        AsyncRunMetricsListener asyncListener = null;
        try {
            SyncTaskEntity task = taskRepo.findById(taskId).orElse(null);
            if (task == null) {
                errorMessage = "任务不存在（可能在执行前被删除）: " + taskId;
                recordStore.finishRun(recordId, SyncRecordEntity.STATUS_FAILED, null, errorMessage);
                return;
            }
            taskName = task.getName();

            // 数据源连接池由注册表统一管理（D12），这里绝不 close
            DataSource sourceDs = poolRegistry.get(task.getSourceDsId());
            DataSource targetDs = poolRegistry.get(task.getTargetDsId());
            SyncTaskConfig config = configMapper.toConfig(task);

            RunLog.publish(taskId, recordId, "▶ 任务 [" + taskName + "] 开始执行（"
                + ("SCHEDULED".equals(triggerType) ? "定时" : "手动") + "触发）"
                + " | 源: " + task.getSourceTable() + " → 目标: " + task.getTargetTable()
                + " | 模式: " + configMapper.describeMode(task)
                + " | 每页 " + config.getPageSize() + " 行 / 每批 " + config.getBatchSize() + " 行");

            // 契约 §3.3（Lead 已修订）：进度是**累计绝对值的采样**，回调"尽力而为"——
            // 允许合并/丢弃中间回调；唯一硬保证是 run() 返回前最后一次进度已投递、
            // 且 SyncRunResult 的最终统计永远准确。因此 updateProgress 的节流是合法的。
            // 另一条硬约束：**回调方必须非阻塞**（D1）。这里两件事都做到了：
            //   1) updateProgress 内部 2 秒节流，常态是纯内存判断；
            //   2) RunLog.publish 只做一次有界队列 offer，真正的总线投递在独立调度线程上，
            //      WebSocket 发送还在各自的发送线程上（见 SyncLogWebSocketHandler）。
            RunMetricsListener listener = progress -> {
                recordStore.updateProgress(recordId, progress);
                RunLog.publish(taskId, recordId, "  · 已读取 " + progress.readRows() + " 行，已写入 "
                    + progress.writtenRows() + " 行，已跳过 " + progress.skippedRows() + " 行（"
                    + progress.elapsedMillis() + "ms）");
            };
            // 纵深防御（Lead 要求的第三步）：即使将来 WebSocket 又出问题，或新增了 Kafka/外部埋点，
            // 也不会再拖慢同步主循环。语义 = 单线程 + 有界队列 + 队列满丢最旧 + 永不阻塞调用方。
            asyncListener = AsyncRunMetricsListener.wrapping(listener, LISTENER_QUEUE_CAPACITY);

            result = engine.run(config, sourceDs, targetDs, asyncListener);
            status = mapStatus(result);
            publishPreflightIssues(taskId, recordId, result);
            if (result != null && result.getErrors() != null && !result.getErrors().isEmpty()) {
                int saved = recordStore.saveErrors(recordId, taskId, result.getErrors());
                log.info("任务 {} 落库坏行明细 {} 条", taskId, saved);
            }
            if (result != null && !result.isSuccess() && result.getErrorMessage() != null) {
                errorMessage = CredentialCipher.scrub(result.getErrorMessage());
            }
        } catch (Exception e) {
            status = SyncRecordEntity.STATUS_FAILED;
            errorMessage = "同步执行异常：" + CredentialCipher.scrub(messageOf(e));
            log.error("任务 {} 执行异常（record {}）", taskId, recordId, e);
        } finally {
            // 0) 契约 §3.3（修订后）的唯一硬保证：run() 返回前最后一次进度必须已投递
            long callbackDrops = 0L;
            if (asyncListener != null) {
                try {
                    asyncListener.flushAndClose(LISTENER_FLUSH_TIMEOUT_MS);
                    callbackDrops = asyncListener.droppedCallbacks();
                } catch (Exception e) {
                    log.debug("刷新进度回调失败（忽略）：{}", e.getMessage());
                }
                if (callbackDrops > 0) {
                    log.warn("任务 {}（record {}）的进度回调被丢弃 {} 次（累计采样语义，最终统计以 sync_record 为准）",
                        taskId, recordId, callbackDrops);
                }
            }
            // 1) 水位推进（只有成功且有 endCursor 才推）——失败即视为本次执行失败
            try {
                if (SyncRecordEntity.STATUS_COMPLETED.equals(status) && result != null && result.getEndCursor() != null) {
                    advanceCursor(taskId, recordId, result.getEndCursor());
                } else if (SyncRecordEntity.STATUS_COMPLETED.equals(status) && result != null) {
                    RunLog.publish(taskId, recordId, "  · 引擎未给出新水位，本次不推进游标");
                }
            } catch (Exception e) {
                status = SyncRecordEntity.STATUS_FAILED;
                errorMessage = "同步已完成但水位推进失败：" + CredentialCipher.scrub(messageOf(e)) + "（下次执行会重放，upsert 幂等安全）";
                log.error("任务 {} 水位推进失败", taskId, e);
            }
            // 2) 落库执行记录
            try {
                recordStore.finishRun(recordId, status, result, errorMessage);
            } catch (Exception e) {
                log.error("落库执行记录失败（record {}）", recordId, e);
            }
            // 3) 收尾：释放互斥资源 + 推送摘要
            cancelRequested.remove(taskId);
            runningRuns.remove(taskId);
            releasePermit(taskId);
            RunLog.publish(taskId, recordId, buildSummary(taskName, status, result, errorMessage,
                System.currentTimeMillis() - startedAt));
            // 让 UI 尽快看到终态；这里已在 worker 线程（不在引擎的 chunk 循环里），
            // 且超时即返回，绝不无限等待慢客户端。
            RunLog.flush(500L);
            long dropped = RunLog.droppedMessages() - droppedAtStart;
            if (dropped > 0) {
                // 观测点：进度采样被丢弃说明消费端（浏览器）跟不上，但同步主循环没有被拖慢
                log.warn("任务 {}（record {}）执行期间有 {} 条实时进度因队列满被丢弃"
                    + "（进度为累计采样，最终统计以 sync_record 为准）", taskId, recordId, dropped);
            }
            log.info("任务 {}（record {}）执行结束，状态 {}（进度消息丢弃 {} 条）",
                taskId, recordId, status, dropped);
        }
    }

    /**
     * 释放任务运行许可。Semaphore 不绑定线程，worker 线程可以安全释放触发线程获取的许可；
     * 释放次数被严格限制为"每次成功获取恰好释放一次"，多余的释放不会把许可放大。
     */
    private void releasePermit(Long taskId) {
        Semaphore permit = runPermits.get(taskId);
        if (permit != null) {
            permit.release();
        }
    }

    /** 只有成功的执行才推进水位；取消/失败一律不动 {@code cursor_value} */
    private void advanceCursor(Long taskId, Long recordId, String endCursor) {
        SyncTaskEntity fresh = taskRepo.findById(taskId).orElse(null);
        if (fresh == null) {
            return;
        }
        String previous = fresh.effectiveCursor();
        fresh.setCursorValue(endCursor);
        taskRepo.save(fresh);
        RunLog.publish(taskId, recordId, "  · 增量游标已推进: " + fresh.getIncrColumn() + " = " + endCursor
            + (previous == null ? "（首次）" : "（原 " + previous + "）"));
    }

    private void publishPreflightIssues(Long taskId, Long recordId, SyncRunResult result) {
        if (result == null || result.getPreflightIssues() == null) {
            return;
        }
        for (PreflightIssue issue : result.getPreflightIssues()) {
            RunLog.publish(taskId, recordId, "  · 预检[" + issue.getLevel() + "][" + issue.getCode() + "] "
                + issue.getMessage() + (issue.getHint() == null ? "" : "（建议：" + issue.getHint() + "）"));
        }
    }

    private static String mapStatus(SyncRunResult result) {
        if (result == null) {
            return SyncRecordEntity.STATUS_FAILED;
        }
        String status = result.getStatus();
        if (status == null || status.isBlank()) {
            return result.isSuccess() ? SyncRecordEntity.STATUS_COMPLETED : SyncRecordEntity.STATUS_FAILED;
        }
        return switch (status.trim().toUpperCase()) {
            case "COMPLETED" -> SyncRecordEntity.STATUS_COMPLETED;
            case "CANCELLED" -> SyncRecordEntity.STATUS_CANCELLED;
            default -> SyncRecordEntity.STATUS_FAILED;
        };
    }

    private static String buildSummary(String taskName, String status, SyncRunResult result,
                                       String errorMessage, long elapsedMillis) {
        long read = result == null ? 0 : result.getReadRows();
        long written = result == null ? 0 : result.getWrittenRows();
        long skipped = result == null ? 0 : result.getSkippedRows();
        String stats = " | 读取 " + read + " 行 / 写入 " + written + " 行 / 跳过 " + skipped + " 行 | 耗时 " + elapsedMillis + "ms";
        return switch (status) {
            case SyncRecordEntity.STATUS_COMPLETED -> "✓ 任务 [" + taskName + "] 执行成功" + stats
                + (result != null && result.getEndCursor() != null ? " | 新水位 " + result.getEndCursor() : " | 无增量水位");
            case SyncRecordEntity.STATUS_CANCELLED -> "⏹ 任务 [" + taskName + "] 已取消" + stats + " | 水位未推进";
            default -> "✗ 任务 [" + taskName + "] 执行失败" + stats + " | 水位未推进"
                + (errorMessage == null ? "" : " | 原因: " + errorMessage);
        };
    }

    private static String messageOf(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------ 停机

    /**
     * 优雅停机：取消所有运行中任务并等待收尾；窗口内没结束的记录强制标记 CANCELLED。
     * <b>任何情况下都不推进水位。</b>
     */
    public void cancelAllAndAwait(long timeoutSeconds) {
        if (runningRuns.isEmpty()) {
            return;
        }
        log.warn("应用正在停机：取消 {} 个运行中的同步任务，最长等待 {} 秒", runningRuns.size(), timeoutSeconds);
        List<Long> taskIds = new ArrayList<>(runningRuns.keySet());
        for (Long taskId : taskIds) {
            try {
                cancel(taskId);
            } catch (Exception e) {
                log.warn("停机取消任务 {} 失败：{}", taskId, e.getMessage());
            }
        }
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutSeconds) * 1000L;
        while (!runningRuns.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(200L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        for (Map.Entry<Long, Long> entry : runningRuns.entrySet()) {
            log.warn("任务 {} 未在停机等待窗口内结束，强制标记 CANCELLED（水位未推进）", entry.getKey());
            try {
                recordStore.finishRun(entry.getValue(), SyncRecordEntity.STATUS_CANCELLED, null,
                    "应用停机，任务未在等待窗口内结束，已标记取消（水位未推进）");
            } catch (Exception e) {
                log.error("强制标记执行记录 {} 为 CANCELLED 失败", entry.getValue(), e);
            }
        }
        runningRuns.clear();
        cancelRequested.clear();
    }

    /** 触发起步结果 */
    public record TriggerResult(boolean accepted, String code, String message, Long recordId) {
        public static TriggerResult accepted(Long recordId, String message) {
            return new TriggerResult(true, "ACCEPTED", message, recordId);
        }

        public static TriggerResult rejected(String code, String message) {
            return new TriggerResult(false, code, message, null);
        }
    }
}
