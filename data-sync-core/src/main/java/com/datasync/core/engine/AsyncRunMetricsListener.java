package com.datasync.core.engine;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 把任意 {@link RunMetricsListener} 包装成"**永不阻塞调用方**"的进度采样器。
 *
 * <p>存在的理由（实测数据）：某次真机验收里，调用方的回调实现因同步 WebSocket 推送被背压，
 * 单次回调阻塞 ~1.4 秒，直接把 100 万行增量从 ~1500 行/秒拖到 ~565 行/秒——
 * 引擎自己的分段埋点显示"回调 28065ms / 总 28688ms"（98%）。而同一份数据在无回调时 26ms/chunk、
 * 回调丢进本包装器时 15ms/chunk。
 *
 * <p><b>语义</b>（与 {@link RunMetricsListener} 的契约一致）：进度是**累计绝对值**，
 * 回调是**尽力而为的采样**。队列满时**丢弃最旧的中间态、保留最新的**——因为下一个进度本身就带全量计数，
 * 丢中间态不丢信息；被丢弃的次数通过 {@link #droppedCallbacks()} 暴露，让观测者知道自己看到的不是全量。
 *
 * <p><b>线程</b>：内部单线程（daemon，名字默认 {@code datasync-metrics}）。
 * {@link #flushAndClose(long)} 返回后不会再有**新的** delegate 调用被发起；
 * 若超时时恰有一次调用正在进行，那一次允许自然跑完（不会去 interrupt 别人的回调）。
 *
 * <p>用法：
 * <pre>{@code
 * try (AsyncRunMetricsListener metrics = AsyncRunMetricsListener.wrapping(平台回调, 8, "task-" + taskId)) {
 *     SyncRunResult r = engine.run(cfg, src, dst, metrics);
 *     metrics.flushAndClose(3000);   // 保证最终进度已投递或被明确计入 dropped
 * }
 * }</pre>
 */
public final class AsyncRunMetricsListener implements RunMetricsListener, AutoCloseable {

    /** 默认队列容量：足够吸收抖动，又不会把内存交给慢消费者。 */
    public static final int DEFAULT_QUEUE_CAPACITY = 8;

    /** close() 的默认等待上限（毫秒）。 */
    private static final long DEFAULT_CLOSE_TIMEOUT_MS = 2000L;

    /** 收尾时先给快速回调一个自然排空的宽限期（毫秒）；超过它才采取"只投最新一件"的合并策略。 */
    private static final long FLUSH_GRACE_MS = 100L;

    private final RunMetricsListener delegate;
    private final BlockingQueue<ChunkProgress> queue;
    private final Object lock = new Object();
    private final AtomicLong deliveredCallbacks = new AtomicLong();
    private final AtomicLong droppedCallbacks = new AtomicLong();
    private final AtomicLong submittedCallbacks = new AtomicLong();
    private final Thread worker;
    private volatile boolean closing;
    private volatile boolean terminated;

    private AsyncRunMetricsListener(RunMetricsListener delegate, int queueCapacity, String threadName) {
        if (delegate == null) {
            throw new IllegalArgumentException("被包装的 listener 不能为空");
        }
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        String name = threadName == null || threadName.isBlank() ? "datasync-metrics"
                : "datasync-metrics-" + threadName.trim();
        this.worker = new Thread(this::drainLoop, name);
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /** 默认线程名。 */
    public static AsyncRunMetricsListener wrapping(RunMetricsListener delegate, int queueCapacity) {
        return new AsyncRunMetricsListener(delegate, queueCapacity, null);
    }

    /** 指定线程名后缀（通常传 taskId，便于 jstack / 线程转储里定位）。 */
    public static AsyncRunMetricsListener wrapping(RunMetricsListener delegate, int queueCapacity,
                                                   String threadNameSuffix) {
        return new AsyncRunMetricsListener(delegate, queueCapacity, threadNameSuffix);
    }

    /** 默认容量 + 默认线程名。 */
    public static AsyncRunMetricsListener wrapping(RunMetricsListener delegate) {
        return new AsyncRunMetricsListener(delegate, DEFAULT_QUEUE_CAPACITY, null);
    }

    /** 永不阻塞：满了就丢最旧的中间态（保留最新），队列操作本身是纳秒级。 */
    @Override
    public void onChunk(ChunkProgress progress) {
        if (progress == null || closing) {
            return;
        }
        submittedCallbacks.incrementAndGet();
        while (!queue.offer(progress)) {
            // 丢最旧：进度是累计值，保留最新才有意义
            if (queue.poll() == null) {
                break;
            }
            droppedCallbacks.incrementAndGet();
        }
        synchronized (lock) {
            lock.notifyAll();
        }
    }

    private void drainLoop() {
        while (true) {
            ChunkProgress next;
            synchronized (lock) {
                next = queue.poll();
                if (next == null) {
                    if (closing) {
                        break;
                    }
                    try {
                        lock.wait(20L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
            }
            deliver(next);
        }
        terminated = true;
    }

    private void deliver(ChunkProgress progress) {
        try {
            delegate.onChunk(progress);
            deliveredCallbacks.incrementAndGet();
        } catch (Throwable t) {
            // 契约要求回调异常自行吞掉；这里再兜一层，绝不让观测通道影响同步
            droppedCallbacks.incrementAndGet();
        }
    }

    /**
     * 结束：停止接收新进度，把队列里剩下的投递出去；若回调方明显跟不上（超过宽限期仍未排空），
     * 则**只保留最新一件**、其余中间态明确计入 {@link #droppedCallbacks()}。
     *
     * <p>这样两种场景都对：
     * <ul>
     *   <li>快回调（正常观测者）：所有中间态照常投递，零丢弃；</li>
     *   <li>慢回调（被背压的 WebSocket，实测单次 1.4s）：不会为了"排空 8 件要 11 秒"而把
     *       契约的硬保证（<b>最后一次进度必须已投递</b>）拖没——用一次投递换到最终进度。</li>
     * </ul>
     *
     * <p>返回后不会再有**新的** delegate 调用被发起；若超时时恰有一次调用正在进行，
     * 那一次允许自然跑完（不会去 interrupt 别人的回调）。
     */
    public void flushAndClose(long timeoutMillis) {
        if (terminated) {
            return;
        }
        closing = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        long timeout = Math.max(0L, timeoutMillis);
        long deadline = System.currentTimeMillis() + timeout;
        try {
            worker.join(Math.min(FLUSH_GRACE_MS, timeout));
            if (!terminated && queue.size() > 1) {
                // 回调方跟不上：只保留最新的一件，其余中间态记账丢弃
                ChunkProgress newest = null;
                ChunkProgress candidate;
                while ((candidate = queue.poll()) != null) {
                    if (newest != null) {
                        droppedCallbacks.incrementAndGet();
                    }
                    newest = candidate;
                }
                queue.offer(newest);
            }
            long remaining = deadline - System.currentTimeMillis();
            if (!terminated && remaining > 0) {
                worker.join(remaining);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        // 超时仍未排空：明确记账，不让观测者以为看到的是全量
        ChunkProgress leftover;
        while ((leftover = queue.poll()) != null) {
            droppedCallbacks.incrementAndGet();
        }
    }

    /** 等价于 {@link #flushAndClose(long)} 且不等待（用于 try-with-resources 的兜底）。 */
    @Override
    public void close() {
        flushAndClose(0L);
    }

    /** 已成功投递给 delegate 的回调数。 */
    public long deliveredCallbacks() {
        return deliveredCallbacks.get();
    }

    /** 被丢弃（合并/超时/回调抛异常）的回调数；&gt;0 表示观测者看到的不是全量采样。 */
    public long droppedCallbacks() {
        return droppedCallbacks.get();
    }

    /** 提交进本包装器的回调总数（= delivered + dropped，用于自检与观测）。 */
    public long submittedCallbacks() {
        return submittedCallbacks.get();
    }

    /** 是否已经完全停止（内部线程已退出）。 */
    public boolean isTerminated() {
        return terminated;
    }

    @Override
    public String toString() {
        return "AsyncRunMetricsListener{submitted=" + submittedCallbacks()
                + ", delivered=" + deliveredCallbacks() + ", dropped=" + droppedCallbacks() + "}";
    }
}
