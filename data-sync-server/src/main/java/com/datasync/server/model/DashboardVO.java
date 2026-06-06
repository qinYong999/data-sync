package com.datasync.server.model;
public class DashboardVO {
    private long totalTasks; private long runningTasks; private long failedTasks;
    private long successTasks; private long totalRecords; private long totalReadRows;
    public long getTotalTasks() { return totalTasks; } public void setTotalTasks(long v) { this.totalTasks = v; }
    public long getRunningTasks() { return runningTasks; } public void setRunningTasks(long v) { this.runningTasks = v; }
    public long getFailedTasks() { return failedTasks; } public void setFailedTasks(long v) { this.failedTasks = v; }
    public long getSuccessTasks() { return successTasks; } public void setSuccessTasks(long v) { this.successTasks = v; }
    public long getTotalRecords() { return totalRecords; } public void setTotalRecords(long v) { this.totalRecords = v; }
    public long getTotalReadRows() { return totalReadRows; } public void setTotalReadRows(long v) { this.totalReadRows = v; }
}