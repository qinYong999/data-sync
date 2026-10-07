package com.datasync.core.engine;

/**
 * 单个 chunk 提交后的进度快照（不可变）。
 *
 * <p>访问器按契约 §3.3 定义，<b>不带 get 前缀</b>。
 *
 * <p><b>计数口径</b>：{@link #readRows()}、{@link #writtenRows()}、{@link #skippedRows()} 都是
 * **本次执行累计的绝对值**（不是增量），因此调用方可以安全地丢弃中间快照——
 * 下一个快照本身携带全量进度，最终值另见 {@link com.datasync.core.model.SyncRunResult}。
 * 这正是"回调尽力而为、允许合并"这条语义成立的基础。
 *
 * <p>{@link #cursor()} 与 {@link #lastKey()} 只用于**进度可观测**（运维看大表跑到哪了），
 * 不参与断点恢复：任何失败/取消，下次执行一律从上次成功的水位重新开始。
 */
public final class ChunkProgress {

    private final long readRows;
    private final long writtenRows;
    private final long skippedRows;
    private final String lastKey;
    private final String cursor;
    private final long elapsedMillis;

    public ChunkProgress(long readRows, long writtenRows, long skippedRows,
                         String lastKey, String cursor, long elapsedMillis) {
        this.readRows = readRows;
        this.writtenRows = writtenRows;
        this.skippedRows = skippedRows;
        this.lastKey = lastKey;
        this.cursor = cursor;
        this.elapsedMillis = elapsedMillis;
    }

    public long readRows() {
        return readRows;
    }

    public long writtenRows() {
        return writtenRows;
    }

    public long skippedRows() {
        return skippedRows;
    }

    /** 最近提交行的排序键（可空）。 */
    public String lastKey() {
        return lastKey;
    }

    /** 最近提交行的增量字段值（可空）。 */
    public String cursor() {
        return cursor;
    }

    public long elapsedMillis() {
        return elapsedMillis;
    }

    @Override
    public String toString() {
        return "ChunkProgress{read=" + readRows + ", written=" + writtenRows + ", skipped=" + skippedRows
                + ", lastKey=" + lastKey + ", cursor=" + cursor + ", elapsed=" + elapsedMillis + "ms}";
    }
}
