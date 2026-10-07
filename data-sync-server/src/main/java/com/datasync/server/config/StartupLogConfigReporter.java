package com.datasync.server.config;

import com.datasync.server.log.RunLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 把"可配置项最终生效成了什么"打进启动日志。
 *
 * <h2>为什么必须存在这个类（验收阶段实测的教训）</h2>
 * {@link RunLog} 的队列上限支持系统属性覆盖，非法值会回落默认值并"打一条 WARN"。
 * 但实测发现：**那条 WARN 运维永远看不到** —— 因为容量解析发生在**静态初始化块**里，
 * 而静态初始化早于 Logback 配置就绪，此时发出的日志会被日志框架**直接丢弃**。
 *
 * <p>于是出现了一个很坏的组合：代码里有告警、单测也过、字节码里确实有那两条文案，
 * 但真实启动的日志里**一行都没有**。这与"界面 404 而所有 API 都绿"（D9）、
 * "计数器永远不会触发"（D8）属于同一类缺陷：**看起来有、实际不工作**。</p>
 *
 * <h2>本类的作用</h2>
 * 在 {@link ApplicationReadyEvent}（**上下文完全就绪、日志系统必然可用**）时，
 * 把两个可配置容量的**最终生效值**打进启动日志。这样：
 * <ul>
 *   <li>无论用户配了什么（合法 / 非法 / 没配），**都有一个必然可见的落点**；</li>
 *   <li>运维排查"我的参数到底生效没有"时，只需看启动日志里这一行，不用去猜；</li>
 *   <li>它也是验收该参数的判据 —— 判据必须落在"运维真能看到的东西"上。</li>
 * </ul>
 *
 * <p>注意这里**只读取值**，不触发 WebSocket 订阅副作用：{@code SyncLogWebSocketHandler}
 * 是 Spring bean，它的订阅发生在 bean 初始化阶段（早于本事件），因此读它的静态访问器是安全的。</p>
 */
@Component
public class StartupLogConfigReporter {

    private static final Logger log = LoggerFactory.getLogger(StartupLogConfigReporter.class);

    @EventListener(ApplicationReadyEvent.class)
    public void report() {
        // 读取即触发懒加载解析；此时 Logback 已就绪，任何 WARN/INFO 都能落地
        int logQueueCapacity = RunLog.queueCapacity();
        int wsQueueCapacity = SyncLogWebSocketHandler.sessionQueueCapacity();

        log.info("可配置项生效值：实时日志总线队列上限={}（默认 {}，属性 {}）；"
                        + "WebSocket 会话队列上限={}（默认 {}，属性 {}）",
                logQueueCapacity, RunLog.DEFAULT_QUEUE_CAPACITY, RunLog.QUEUE_CAPACITY_PROPERTY,
                wsQueueCapacity, SyncLogWebSocketHandler.DEFAULT_SESSION_QUEUE_CAPACITY,
                SyncLogWebSocketHandler.SESSION_QUEUE_CAPACITY_PROPERTY);
    }
}
