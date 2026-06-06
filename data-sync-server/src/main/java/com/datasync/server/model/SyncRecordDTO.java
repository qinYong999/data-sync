package com.datasync.server.model;
import java.time.LocalDateTime;
public class SyncRecordDTO {
    private Long id; private Long taskId; private LocalDateTime startTime; private LocalDateTime endTime;
    private String status; private Long totalRows; private Long readRows; private Long writeRows;
    private Long errorRows; private String errorMessage; private String triggerType;
    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
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