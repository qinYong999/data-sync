package com.datasync.server.config;

import com.datasync.server.service.TaskRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 优雅停机（契约 §5.10）。
 *
 * <p>选择监听 {@link ContextClosedEvent} 而不是 {@code SmartLifecycle}：
 * 该事件在 {@code AbstractApplicationContext.doClose()} 里于
 * {@code lifecycleProcessor.onClose()} 与 {@code destroyBeans()} **之前**发布，
 * 因此"取消运行中任务并等待收尾"一定发生在连接池关闭、线程池销毁之前。</p>
 *
 * <p>停机时：先给每个运行中任务发协作式取消 → 等待 {@code app.sync.shutdown-timeout-seconds}
 * → 仍未结束的强制标记 CANCELLED。<b>水位一律不推进。</b></p>
 */
@Component
public class GracefulShutdownListener {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownListener.class);

    private final TaskRunService taskRunService;
    private final AppProperties properties;

    public GracefulShutdownListener(TaskRunService taskRunService, AppProperties properties) {
        this.taskRunService = taskRunService;
        this.properties = properties;
    }

    @EventListener(ContextClosedEvent.class)
    public void onShutdown() {
        long timeout = properties.getSync().getShutdownTimeoutSeconds();
        log.info("收到停机信号，开始取消运行中的同步任务（最长等待 {} 秒）", timeout);
        try {
            taskRunService.cancelAllAndAwait(timeout);
        } catch (Exception e) {
            log.error("停机取消运行中任务时发生异常", e);
        }
        log.info("停机收尾完成");
    }
}
