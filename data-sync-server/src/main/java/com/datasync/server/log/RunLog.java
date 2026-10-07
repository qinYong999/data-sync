package com.datasync.server.log;

import com.datasync.core.job.SyncEventBus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 带 run 标签的事件总线出口 —— <b>永不阻塞调用方</b>（D1 修复）。
 *
 * <p>背景：{@link SyncEventBus} 是 core 里的全局静态总线，{@code publish} 会<b>同步遍历所有消费者</b>。
 * 我们的 WebSocket 消费者曾经在<b>引擎线程</b>上直接 {@code sendMessage}，而
 * {@code ConcurrentWebSocketSessionDecorator} 的语义就是"必要时阻塞调用方"（最多 5 秒）。
 * 于是一个挂着的浏览器页面就能让每个 chunk 卡 ~1.4 秒，把同步拖慢几十倍。</p>
 *
 * <p>修复：本类把"投递"变成一次<b>有界队列的 offer</b>（O(1)、绝不等待），
 * 真正的 {@code SyncEventBus.publish} 由一条<b>专用守护线程</b>串行执行；
 * 队列满时丢<b>最旧</b>的消息（进度是累计采样的中间态，保留最新比保留全量更有价值），
 * 并计数 {@link #droppedMessages()} 供观测——即"丢中间态"而不是"背压阻塞引擎"。</p>
 *
 * <p>标签格式刻意保持人类可读：即使原样写进日志也不影响排查。</p>
 *
 * <p><b>队列上限做成系统属性</b>（{@code -Ddatasync.log.queue-capacity=2000}）的两个理由：</p>
 * <ol>
 *   <li>让"丢弃计数器"能被<b>端到端验证</b>——调到很小（如 8）就能在真机上逼出丢弃，
 *       证明这个运维会看的指标真的会动，而不是"看着有、其实是死的"；</li>
 *   <li>给消费者更慢的部署留旋钮（跨广域网日志收集可调大以减少丢弃，反之可调小只保最新）。</li>
 * </ol>
 * <p>非法值（非数字、{@code <= 0}）一律回落默认值并 WARN，绝不在类初始化时抛异常。</p>
 */
public final class RunLog {

    private static final Logger log = LoggerFactory.getLogger(RunLog.class);

    private static final Pattern TAG_PATTERN =
        Pattern.compile("^\\[#run task=(\\d+) record=(\\d+)]\\s?(.*)$", Pattern.DOTALL);

    /** 系统属性名：{@code -Ddatasync.log.queue-capacity=<正整数>} */
    public static final String QUEUE_CAPACITY_PROPERTY = "datasync.log.queue-capacity";
    /** 默认队列容量（生产行为与历史版本一致） */
    public static final int DEFAULT_QUEUE_CAPACITY = 2000;

    /**
     * 有界队列：满了就丢最旧（见类注释）。
     *
     * <p><b>为什么是懒加载而不是 {@code static final} 直接初始化</b>（验收阶段实测踩到）：
     * 静态初始化发生在 **Logback 配置就绪之前**，此时发出的 WARN/INFO 会被日志框架**直接丢弃**——
     * 于是"非法值回落"的告警虽然代码里有、单测也过，**运维在真实启动里一条都看不到**。
     * 改成首次使用时解析，就把"解析时机"和"日志可用时机"解耦了；
     * 另外 {@code StartupLogConfigReporter} 会在上下文就绪后把生效值打进启动日志，
     * 保证**任何配置结果都有一个必然可见的落点**。</p>
     */
    private static volatile BlockingQueue<String> queue;

    private static final AtomicLong DROPPED = new AtomicLong();
    /** 历史最高水位（背压指示：永远有信息量，而"是否丢过"在容量 2000 下几乎不可能发生） */
    private static final AtomicInteger PEAK_DEPTH = new AtomicInteger();
    private static final AtomicBoolean DISPATCHING = new AtomicBoolean(true);
    private static final Thread DISPATCHER;

    static {
        DISPATCHER = new Thread(RunLog::dispatchLoop, "runlog-dispatcher");
        DISPATCHER.setDaemon(true);
        DISPATCHER.start();
    }

    private RunLog() {
    }

    private static BlockingQueue<String> queue() {
        BlockingQueue<String> q = queue;
        if (q == null) {
            synchronized (RunLog.class) {
                q = queue;
                if (q == null) {
                    q = new ArrayBlockingQueue<>(resolveCapacity(QUEUE_CAPACITY_PROPERTY, DEFAULT_QUEUE_CAPACITY));
                    queue = q;
                }
            }
        }
        return q;
    }

    /**
     * 解析容量配置：非法值（非数字、{@code <= 0}、解析失败）回落到默认值并 WARN。
     *
     * <h4>为什么不能直接用 {@link Integer#getInteger(String, Integer)}</h4>
     * 它对"非数字"**返回默认值而不是 null**，于是"值非法 → 告警"这条分支永远不可达：
     * <pre>
     *   -Ddatasync.log.queue-capacity=abc
     *     → Integer.getInteger(...) 直接返回 2000 → 既不告警也不提示，完全静默
     *   -Ddatasync.log.queue-capacity=0
     *     → 返回 0 → 落到 &lt;=0 分支才会告警
     * </pre>
     * 也就是说 {@code abc} 和"没配"在日志上长得一模一样。**运维把属性名或值写错时会以为生效了。**
     * 这是验收阶段实测发现的不对称缺陷（另一个类 {@code SyncLogWebSocketHandler} 当时已用
     * {@code System.getProperty + Integer.decode}，只有这里漏了）。
     *
     * <p>现在改为：先取原始字符串 → 为空则用默认值（未配置，正常）→ 否则显式 {@code decode}
     * 并捕获异常 → 任何解析失败或非正值都走同一条告警路径。</p>
     *
     * <p>包级可见 + 运行时读取，是为了让"非法值回落"这条保护逻辑能被单测直接覆盖
     * （生产路径仍是类初始化时读一次）。类初始化绝不抛异常。</p>
     */
    static int resolveCapacity(String propertyName, int defaultValue) {
        String raw = System.getProperty(propertyName);
        if (raw == null || raw.isBlank()) {
            // 未配置 = 正常情况，不打扰日志
            return defaultValue;
        }
        int parsed;
        try {
            parsed = Integer.decode(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("系统属性 {} 的值非法（\"{}\" 不是合法整数），已回落到默认值 {}",
                    propertyName, raw, defaultValue);
            return defaultValue;
        }
        if (parsed <= 0) {
            log.warn("系统属性 {} 的值非法（{} 必须为正整数），已回落到默认值 {}",
                    propertyName, parsed, defaultValue);
            return defaultValue;
        }
        if (parsed != defaultValue) {
            log.info("系统属性 {} 覆盖为 {}（默认 {}）", propertyName, parsed, defaultValue);
        }
        return parsed;
    }

    /** 当前生效的队列容量（观测/测试用） */
    public static int queueCapacity() {
        return queue().remainingCapacity() + queue().size();
    }

    private static void dispatchLoop() {
        while (DISPATCHING.get() || !queue().isEmpty()) {
            String message;
            try {
                message = queue().poll(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (message == null) {
                continue;
            }
            try {
                // 消费者若有背压，只压住这条调度线程，绝不回压到引擎线程
                SyncEventBus.publish(message);
            } catch (Throwable t) {
                log.debug("事件总线消费者抛异常（已忽略）：{}", t.toString());
            }
        }
    }

    /** 发布一条属于某次执行的消息（带 run 标签，非阻塞） */
    public static void publish(Long taskId, Long recordId, String message) {
        offer(tag(taskId, recordId) + (message == null ? "" : message));
    }

    /** 发布一条系统级消息（无标签，广播给所有 WebSocket 会话，非阻塞） */
    public static void publishSystem(String message) {
        offer(message == null ? "" : message);
    }

    private static void offer(String message) {
        if (queue().offer(message)) {
            trackPeak();
            return;
        }
        // 队列满：丢最旧、保最新，并计数（这样"进度看起来是跳跃的"是可解释的）
        if (queue().poll() != null) {
            DROPPED.incrementAndGet();
        }
        queue().offer(message);
        trackPeak();
    }

    /** 记录历史最高水位（CAS 循环，避免并发下把峰值写小） */
    private static void trackPeak() {
        int depth = queue().size();
        int peak = PEAK_DEPTH.get();
        while (depth > peak && !PEAK_DEPTH.compareAndSet(peak, depth)) {
            peak = PEAK_DEPTH.get();
        }
    }

    /** 因队列满而丢弃的消息条数（观测用：> 0 说明消费端跟不上，但同步主循环没被拖慢） */
    public static long droppedMessages() {
        return DROPPED.get();
    }

    /** 当前积压深度（观测用） */
    public static int queueDepth() {
        return queue().size();
    }

    /**
     * 历史最高积压水位（背压指示，永不清零）。
     *
     * <p><b>为什么它比"丢弃计数"更值得看</b>：投递到本队列的生产者已被上游
     * {@code AsyncRunMetricsListener}（64 容量、丢最旧）节流，因此 {@link #droppedMessages()}
     * 在正常负载下**恒为 0 是预期行为**；而"积压到多深"任何负载下都有信息量
     * （它反映总线到 WebSocket 之间到底有没有背压）。</p>
     */
    public static int peakQueueDepth() {
        return PEAK_DEPTH.get();
    }

    /**
     * 等待队列排空（收尾/停机用）。超时即返回，绝不无限等待。
     *
     * @return true 表示在超时前排空
     */
    public static boolean flush(long timeoutMillis) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMillis);
        while (!queue().isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return queue().isEmpty();
    }

    /** 仅测试/停机使用：停止调度线程（此后 publish 只入队不再投递） */
    public static void shutdownDispatcher() {
        DISPATCHING.set(false);
        DISPATCHER.interrupt();
    }

    /** 解析消息：带标签则给出 taskId/recordId 与剥离后的正文；无标签返回 null */
    public static Tagged decode(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = TAG_PATTERN.matcher(raw);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return new Tagged(Long.valueOf(matcher.group(1)), Long.valueOf(matcher.group(2)), matcher.group(3));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String tag(Long taskId, Long recordId) {
        return "[#run task=" + taskId + " record=" + recordId + "] ";
    }

    /** 一条已解析的事件 */
    public record Tagged(Long taskId, Long recordId, String payload) {
    }
}
