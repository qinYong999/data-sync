package com.datasync.core.model;

/**
 * 全量同步的目标清理策略（见冻结契约 §1 D6/D14）。
 *
 * <ul>
 *   <li>{@link #TRUNCATE}：默认策略，清空目标表后重写（需要 DROP 权限）。</li>
 *   <li>{@link #DELETE}：逐行删除后重写（权限要求低，但大表慢、且不释放空间）。</li>
 *   <li>{@link #SWAP}：写入暂存表后 RENAME 交换，同步期间目标表可读；DM8 不支持。</li>
 * </ul>
 *
 * <p>注意：增量（INCR）模式下绝不清理目标表，本策略只在全量阶段生效。
 */
public enum FullSyncStrategy {
    TRUNCATE,
    DELETE,
    SWAP
}
