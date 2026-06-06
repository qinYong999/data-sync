package com.datasync.server.entity;
import jakarta.persistence.*;
import java.time.LocalDateTime;
@Entity
@Table(name = "datasource")
public class DataSourceEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, length = 100) private String name;
    @Column(name = "db_type", nullable = false, length = 20) private String dbType;
    @Column(nullable = false, length = 255) private String host;
    @Column(nullable = false) private Integer port;
    @Column(name = "database_name", nullable = false, length = 100) private String databaseName;
    @Column(nullable = false, length = 100) private String username;
    @Column(nullable = false, length = 255) private String password;
    @Column(name = "created_at") private LocalDateTime createdAt;
    @Column(name = "updated_at") private LocalDateTime updatedAt;
    @PrePersist protected void onCreate() { createdAt = LocalDateTime.now(); updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }
    public Long getId() { return id; } public void setId(Long id) { this.id = id; }
    public String getName() { return name; } public void setName(String name) { this.name = name; }
    public String getDbType() { return dbType; } public void setDbType(String dbType) { this.dbType = dbType; }
    public String getHost() { return host; } public void setHost(String host) { this.host = host; }
    public Integer getPort() { return port; } public void setPort(Integer port) { this.port = port; }
    public String getDatabaseName() { return databaseName; } public void setDatabaseName(String n) { this.databaseName = n; }
    public String getUsername() { return username; } public void setUsername(String u) { this.username = u; }
    public String getPassword() { return password; } public void setPassword(String p) { this.password = p; }
    public LocalDateTime getCreatedAt() { return createdAt; } public void setCreatedAt(LocalDateTime t) { this.createdAt = t; }
    public LocalDateTime getUpdatedAt() { return updatedAt; } public void setUpdatedAt(LocalDateTime t) { this.updatedAt = t; }
}