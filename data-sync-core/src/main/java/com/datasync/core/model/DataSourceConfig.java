package com.datasync.core.model;

import com.datasync.core.model.enums.DbType;

/**
 * 数据源配置
 */
public class DataSourceConfig {
    private Long id;
    private String name;
    private DbType dbType;
    private String host;
    private int port;
    private String databaseName;
    private String username;
    private String password;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public DbType getDbType() { return dbType; }
    public void setDbType(DbType dbType) { this.dbType = dbType; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getDatabaseName() { return databaseName; }
    public void setDatabaseName(String databaseName) { this.databaseName = databaseName; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    /** 根据数据库类型构建 JDBC URL */
    public String buildJdbcUrl() {
        return switch (dbType) {
            case MYSQL -> String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8",
                host, port, databaseName);
            case DM8 -> String.format("jdbc:dm://%s:%d/%s", host, port, databaseName);
        };
    }
}