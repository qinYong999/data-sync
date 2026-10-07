package com.datasync.core.engine;

/**
 * 进度回调。
 *
 * <p><b>语义（冻结契约 §3.3 修订版）：进度是累计绝对值，回调是"尽力而为的采样"。</b>
 * <ul>
 *   <li>{@link ChunkProgress} 的 {@code readRows/writtenRows/skippedRows} 都是**累计值**，
 *       因此实现方可以合并或丢弃中间回调而**不丢信息**。唯一硬保证是：
 *       <b>{@code run()} 返回前，最后一次进度必须已经投递</b>，
 *       且 {@link com.datasync.core.model.SyncRunResult} 里的最终统计永远准确。</li>
 *   <li><b>实现方必须非阻塞</b>：不得在回调里做网络 I/O、锁等待或长事务。
 *       真实事故：某次真机验收里回调同步推送 WebSocket 被背压，单次阻塞约 1.4 秒，
 *       把 100 万行增量从约 1500 行/秒拖到约 565 行/秒
 *       （引擎分段埋点显示：回调 28065ms / 总 28688ms，占 98%）。</li>
 *   <li>拿不准自己的回调会不会阻塞时，用 {@link AsyncRunMetricsListener} 包一层：
 *       它在后台单线程 + 有界队列里投递，队列满时丢弃最旧的中间态并统计丢弃次数。</li>
 *   <li>回调抛异常必须自行吞掉；引擎侧还会再兜一层（只记 warn，绝不影响已提交的数据与任务状态）。</li>
 * </ul>
 */
@FunctionalInterface
public interface RunMetricsListener {

    void onChunk(ChunkProgress progress);
}
