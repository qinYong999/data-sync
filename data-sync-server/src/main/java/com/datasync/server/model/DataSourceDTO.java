package com.datasync.server.model;
public class DataSourceDTO {
    private Long id; private String name; private String dbType; private String host;
    private Integer port; private String databaseName; private String username; private String password;
    public Long getId() { return id; } public void setId(Long v) { this.id = v; }
    public String getName() { return name; } public void setName(String v) { this.name = v; }
    public String getDbType() { return dbType; } public void setDbType(String v) { this.dbType = v; }
    public String getHost() { return host; } public void setHost(String v) { this.host = v; }
    public Integer getPort() { return port; } public void setPort(Integer v) { this.port = v; }
    public String getDatabaseName() { return databaseName; } public void setDatabaseName(String v) { this.databaseName = v; }
    public String getUsername() { return username; } public void setUsername(String v) { this.username = v; }
    public String getPassword() { return password; } public void setPassword(String v) { this.password = v; }
}