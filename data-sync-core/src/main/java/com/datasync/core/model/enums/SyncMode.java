package com.datasync.core.model.enums;

/**
 * 同步模式枚举
 */
public enum SyncMode {
    FULL,       // 全量同步
    INCR,       // 纯增量同步
    FULL_INCR   // 首次全量后增量
}