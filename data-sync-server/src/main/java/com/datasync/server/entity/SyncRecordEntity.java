package com.datasync.server.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

@Entity
@Table(name = "sync_record")
@org.hibernate.annotations.Comment("同步执行记录表")
public class SyncRecordEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("记录ID")
    private Long id;

    @Column(name = "task_id", nullable = false)
    @Comment("关联的任务ID")
    private Long taskId;

    @Column(name = "start_time", nullable = false)
    @Comment("开始执行时间")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    @Comment("结束时间")
    private LocalDateTime endTime;

    @Column(length = 20)
    @Comment("执行状态：RUNNING / SUCCESS / FAILED / STOPPED")
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

    @Column(name = "error_rows")
    @Comment("失败行数")
    private Long errorRows = 0L;

    @Column(name = "error_message", columnDefinition = "TEXT")
    @Comment("错误信息")
    private String errorMessage;

    @Column(name = "trigger_type", length = 20)
    @Comment("触发方式：SCHEDULED / MANUAL")
    private String triggerType;

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; } public void setTaskId(Long v) { this.taskId = v; }
    public LocalDateTime getStartTime() { return startTime; } public void setStartTime(LocalDateTime v) { this.startTime = v; }
    public LocalDateTime getEndTime() { return endTime; } public void setEndTime(LocalDateTime v) { this.endTime = v; }
    public String getStatus() { return status; } public void setStatus(String v) { this.status = v; }
    public Long getTotalRows() { return totalRows; } public void setTotalRows(Long v) { this.totalRows = v; }
    public Long getReadRows() { return readRows; } public void setReadRows(Long v) { this.readRows = v; }
    public Long getWriteRows() { return writeRows; } public void setWriteRows(Long v) { this.writeRows = v; }
    public Long getErrorRows() { return errorRows; } public void setErrorRows(Long v) { this.errorRows = v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { this.errorMessage = v; }
    public String getTriggerType() { return triggerType; } public void setTriggerType(String v) { this.triggerType = v; }
}