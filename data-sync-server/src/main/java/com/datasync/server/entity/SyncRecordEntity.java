package com.datasync.server.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

/**
 * 同步执行记录表（契约 §4.2）。
 *
 * <p>状态取值按 core 引擎原样落库：{@code RUNNING / COMPLETED / FAILED / CANCELLED}
 * （历史数据里还存在 Spring Batch 时代的 STARTED/STOPPED）。</p>
 *
 * <p>{@code run_key} 既是幂等键也是互斥令牌：运行中为 {@code RUNNING-{taskId}}，
 * 由唯一索引保证同一任务同时只有一条 RUNNING 记录；结束时改写为 {@code {taskId}-{recordId}}。</p>
 */
@Entity
@Table(name = "sync_record")
@org.hibernate.annotations.Comment("同步执行记录表")
public class SyncRecordEntity {

    /** 运行中记录的 run_key 前缀（配合唯一索引做 DB 级互斥） */
    public static final String RUNNING_KEY_PREFIX = "RUNNING-";

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_CANCELLED = "CANCELLED";

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("记录ID")
    private Long id;

    @Column(name = "task_id", nullable = false)
    @Comment("关联的任务ID")
    private Long taskId;

    @Column(name = "run_key", length = 64)
    @Comment("本次执行的幂等键（唯一，运行中兼作互斥令牌）")
    private String runKey;

    @Column(name = "start_time", nullable = false)
    @Comment("开始执行时间")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    @Comment("结束时间")
    private LocalDateTime endTime;

    @Column(length = 20)
    @Comment("执行状态：RUNNING / COMPLETED / FAILED / CANCELLED")
    private String status;

    @Column(name = "total_rows")
    @Comment("总行数")
    private Long totalRows = 0L;

    @Column(name = "read_rows")
    @Comment("已读取行数")
    private Long readRows = 0L;

    @Column(name = "write_rows")
    @Comment("已写入行数")
    private Long writeRows = 0L;

    @Column(name = "skipped_rows", nullable = false)
    @Comment("被跳过的坏行数")
    private long skippedRows = 0L;

    @Column(name = "error_rows")
    @Comment("失败行数")
    private Long errorRows = 0L;

    @Column(name = "start_cursor", length = 255)
    @Comment("本次生效的增量读下界")
    private String startCursor;

    @Column(name = "end_cursor", length = 255)
    @Comment("本次成功提交的最后一行增量值（仅成功时非空）")
    private String endCursor;

    @Column(name = "read_millis", nullable = false)
    @Comment("读取阶段耗时（毫秒）")
    private long readMillis = 0L;

    @Column(name = "write_millis", nullable = false)
    @Comment("写入阶段耗时（毫秒）")
    private long writeMillis = 0L;

    @Column(name = "total_millis", nullable = false)
    @Comment("总耗时（毫秒）")
    private long totalMillis = 0L;

    @Column(name = "error_message", columnDefinition = "TEXT")
    @Comment("错误信息（已清洗，不含明文口令）")
    private String errorMessage;

    @Column(name = "preflight_json", columnDefinition = "TEXT")
    @Comment("预检结果（JSON 数组）")
    private String preflightJson;

    @Column(name = "trigger_type", length = 20)
    @Comment("触发方式：SCHEDULED / MANUAL")
    private String triggerType;

    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public Long getTaskId() { return taskId; } public void setTaskId(Long v) { this.taskId = v; }
    public String getRunKey() { return runKey; } public void setRunKey(String v) { this.runKey = v; }
    public LocalDateTime getStartTime() { return startTime; } public void setStartTime(LocalDateTime v) { this.startTime = v; }
    public LocalDateTime getEndTime() { return endTime; } public void setEndTime(LocalDateTime v) { this.endTime = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public Long getTotalRows() { return totalRows; } public void setTotalRows(Long v) { this.totalRows = v; }
    public Long getReadRows() { return readRows; } public void setReadRows(Long v) { this.readRows = v; }
    public Long getWriteRows() { return writeRows; } public void setWriteRows(Long v) { this.writeRows = v; }
    public long getSkippedRows() { return skippedRows; } public void setSkippedRows(long v) { this.skippedRows = v; }
    public Long getErrorRows() { return errorRows; } public void setErrorRows(Long v) { this.errorRows = v; }
    public String getStartCursor() { return startCursor; } public void setStartCursor(String v) { this.startCursor = v; }
    public String getEndCursor() { return endCursor; } public void setEndCursor(String v) { this.endCursor = v; }
    public long getReadMillis() { return readMillis; } public void setReadMillis(long v) { this.readMillis = v; }
    public long getWriteMillis() { return writeMillis; } public void setWriteMillis(long v) { this.writeMillis = v; }
    public long getTotalMillis() { return totalMillis; } public void setTotalMillis(long v) { this.totalMillis = v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { this.errorMessage = v; }
    public String getPreflightJson() { return preflightJson; } public void setPreflightJson(String v) { this.preflightJson = v; }
    public String getTriggerType() { return triggerType; } public void setTriggerType(String v) { this.triggerType = v; }
}
