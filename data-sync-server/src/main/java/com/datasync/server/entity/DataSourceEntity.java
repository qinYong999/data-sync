package com.datasync.server.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.Comment;
import java.time.LocalDateTime;

@Entity
@Table(name = "datasource")
@org.hibernate.annotations.Comment("数据源配置表")
public class DataSourceEntity {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Comment("数据源ID")
    private Long id;

    @Column(nullable = false, length = 100)
    @Comment("数据源名称")
    private String name;

    @Column(name = "db_type", nullable = false, length = 20)
    @Comment("数据库类型：MYSQL / DM8")
    private String dbType;

    @Column(nullable = false, length = 255)
    @Comment("主机地址")
    private String host;

    @Column(nullable = false)
    @Comment("端口号")
    private Integer port;

    @Column(name = "database_name", nullable = false, length = 100)
    @Comment("数据库名")
    private String databaseName;

    @Column(nullable = false, length = 100)
    @Comment("登录用户名")
    private String username;

    @Column(nullable = false, length = 255)
    @Comment("登录密码（加密存储）")
    private String password;

    @Column(name = "created_at")
    @Comment("创建时间")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    @Comment("更新时间")
    private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getName() { return name; } public void setName(String n) { this.name = n; }
    public String getDbType() { return dbType; } public void setDbType(String v) { this.dbType = v; }
    public String getHost() { return host; } public void setHost(String v) { this.host = v; }
    public Integer getPort() { return port; } public void setPort(Integer v) { this.port = v; }
    public String getDatabaseName() { return databaseName; } public void setDatabaseName(String n) { this.databaseName = n; }
    public String getUsername() { return username; } public void setUsername(String u) { this.username = u; }
    public String getPassword() { return password; } public void setPassword(String p) { this.password = p; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime t) { this.createdAt = t; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
}