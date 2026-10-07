package com.datasync.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 有界同步线程池（D11）。
 *
 * <p>旧实现用 {@code CompletableFuture.supplyAsync} 走 {@code ForkJoinPool.commonPool}：
 * 无界并发、与 JVM 里其它并行流抢线程、任务自我重叠都拦不住。这里改为</p>
 * <ul>
 *   <li>并发度由 {@code app.sync.worker-threads} 固定（corePool=maxPool）；</li>
 *   <li><b>有界队列</b>（worker-threads × 2）；</li>
 *   <li><b>明确拒绝策略</b>：抛出带中文原因的 {@link RejectedExecutionException}，
 *       由 {@code TaskRunService} 转成 503 {@code SYSTEM_BUSY}（定时触发则记日志 + 落一条失败记录），
 *       绝不静默丢任务、也不用 CallerRuns 把 HTTP/Quartz 线程拖住。</li>
 * </ul>
 */
@Configuration
public class SyncExecutorConfig {

    public static final String EXECUTOR_BEAN_NAME = "syncTaskExecutor";

    @Bean(name = EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor syncTaskExecutor(AppProperties properties) {
        int workers = Math.max(1, properties.getSync().getWorkerThreads());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(workers);
        executor.setMaxPoolSize(workers);
        executor.setQueueCapacity(workers * 2);
        executor.setThreadNamePrefix("sync-worker-");
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(false);
        executor.setRejectedExecutionHandler((runnable, pool) -> {
            throw new RejectedExecutionException(rejectMessage(pool));
        });
        // 停机等任务自己收尾；真正的中断由 GracefulShutdownListener 先发取消请求
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds((int) Math.max(1L, properties.getSync().getShutdownTimeoutSeconds()));
        executor.initialize();
        return executor;
    }

    private static String rejectMessage(ThreadPoolExecutor pool) {
        return "同步任务线程池已满（并发上限 " + pool.getMaximumPoolSize()
            + "，排队 " + pool.getQueue().size() + "），本次触发被拒绝";
    }
}
