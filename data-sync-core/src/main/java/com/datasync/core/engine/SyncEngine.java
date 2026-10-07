package com.datasync.core.engine;

import com.datasync.core.model.SyncRunResult;
import com.datasync.core.model.SyncTaskConfig;
import javax.sql.DataSource;

/**
 * 同步引擎：执行单次同步。
 *
 * <p>实现必须满足：
 * <ul>
 *   <li><b>不丢</b>：水位只在整个任务成功后推进一次，且推进值来自"本次成功提交的最后一行"。</li>
 *   <li><b>不重</b>：目标写入是幂等 upsert（键来自目标表元数据或字段映射）。</li>
 *   <li><b>可续</b>：全量分页一律键集分页，禁止 LIMIT/OFFSET。</li>
 *   <li><b>可查</b>：预检不通过时一行数据都不写，问题以稳定错误码返回。</li>
 * </ul>
 *
 * <p>{@link #run} <b>不抛异常</b>：任何失败都以 {@code status=FAILED} 的结果返回。
 */
public interface SyncEngine {

    /**
     * 执行一次同步。
     *
     * @param listener 进度回调，可为 null
     */
    SyncRunResult run(SyncTaskConfig cfg, DataSource src, DataSource dst, RunMetricsListener listener);

    /** 协作式取消：置位标志，正在运行的 run() 尽快返回 CANCELLED。 */
    void cancel(long taskId);
}
