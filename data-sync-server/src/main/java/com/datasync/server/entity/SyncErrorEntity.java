package com.datasync.server.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/**
 * 同步失败行明细表（D8，契约 §4.2 新建表）。
 *
 * <p>由 {@code RunRecordStore} 在 run 结束时批量写入，
 * 上限由 core 的 {@code ErrorPolicy.maxErrorsRecorded} 控制。</p>
 */
@Entity
@Table(name = "sync_error")
@org.hibernate.annotations.Comment("同步失败行明细表")
public class SyncErrorEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("错误行ID")
    private Long id;

    @Column(name = "record_id", nullable = false)
    @Comment("关联的执行记录ID")
    private Long recordId;

    @Column(name = "task_id", nullable = false)
    @Comment("关联的任务ID")
    private Long taskId;

    @Column(nullable = false, length = 16)
    @Comment("失败阶段：PREFLIGHT / READ / MAP / WRITE")
    private String phase;

    @Column(name = "row_key", length = 255)
    @Comment("失败行的主键值（尽力而为，可空）")
    private String rowKey;

    @Column(length = 1000)
    @Comment("错误原因")
    private String message;

    @Column(name = "row_data", columnDefinition = "TEXT")
    @Comment("源行数据（JSON，超长会截断）")
    private String rowData;

    @Column(nullable = false, columnDefinition = "TINYINT(1)")
    @Comment("是否可重试：1 可重试 0 致命")
    private boolean retryable;

    @Column(name = "created_at", nullable = false)
    @Comment("记录时间")
    private LocalDateTime createdAt;

    @PrePersist protected void onCreate() { if (createdAt == null) createdAt = LocalDateTime.now(); }

    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public Long getRecordId() { return recordId; } public void setRecordId(Long v) { this.recordId = v; }
    public Long getTaskId() { return taskId; } public void setTaskId(Long v) { this.taskId = v; }
    public String getPhase() { return phase; } public void setPhase(String v) { this.phase = v; }
    public String getRowKey() { return rowKey; } public void setRowKey(String v) { this.rowKey = v; }
    public String getMessage() { return message; } public void setMessage(String v) { this.message = v; }
    public String getRowData() { return rowData; } public void setRowData(String v) { this.rowData = v; }
    public boolean isRetryable() { return retryable; } public void setRetryable(boolean v) { this.retryable = v; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime v) { this.createdAt = v; }
}
