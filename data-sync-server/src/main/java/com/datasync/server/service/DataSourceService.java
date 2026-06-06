package com.datasync.server.service;
import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.model.DataSourceDTO;
import com.datasync.server.repository.DataSourceRepository;
import org.springframework.stereotype.Service;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
@Service
public class DataSourceService {
    private final DataSourceRepository repository;
    public DataSourceService(DataSourceRepository repository) { this.repository = repository; }
    public List<DataSourceEntity> findAll() { return repository.findAll(); }
    public DataSourceEntity findById(Long id) { return repository.findById(id).orElseThrow(() -> new RuntimeException("数据源不存在: " + id)); }
    public DataSourceEntity create(DataSourceDTO dto) { return repository.save(toEntity(dto)); }
    public DataSourceEntity update(Long id, DataSourceDTO dto) {
        DataSourceEntity e = findById(id);
        e.setName(dto.getName()); e.setDbType(dto.getDbType()); e.setHost(dto.getHost());
        e.setPort(dto.getPort()); e.setDatabaseName(dto.getDatabaseName()); e.setUsername(dto.getUsername());
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) e.setPassword(dto.getPassword());
        return repository.save(e);
    }
    public void delete(Long id) { repository.deleteById(id); }
    public boolean testConnection(DataSourceDTO dto) {
        try (Connection c = DriverManager.getConnection(buildJdbcUrl(dto), dto.getUsername(), dto.getPassword())) {
            return c.isValid(5);
        } catch (Exception e) { return false; }
    }
    private String buildJdbcUrl(DataSourceDTO dto) {
        if ("MYSQL".equalsIgnoreCase(dto.getDbType()))
            return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", dto.getHost(), dto.getPort(), dto.getDatabaseName());
        if ("DM8".equalsIgnoreCase(dto.getDbType()))
            return String.format("jdbc:dm://%s:%d/%s", dto.getHost(), dto.getPort(), dto.getDatabaseName());
        throw new IllegalArgumentException("不支持的数据库类型: " + dto.getDbType());
    }
    private DataSourceEntity toEntity(DataSourceDTO dto) {
        DataSourceEntity e = new DataSourceEntity();
        e.setName(dto.getName()); e.setDbType(dto.getDbType()); e.setHost(dto.getHost());
        e.setPort(dto.getPort()); e.setDatabaseName(dto.getDatabaseName());
        e.setUsername(dto.getUsername()); e.setPassword(dto.getPassword());
        return e;
    }
}