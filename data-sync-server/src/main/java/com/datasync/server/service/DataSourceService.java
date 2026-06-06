package com.datasync.server.service;

import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.model.DataSourceDTO;
import com.datasync.server.repository.DataSourceRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class DataSourceService {
    private final DataSourceRepository repository;
    public DataSourceService(DataSourceRepository repository) { this.repository = repository; }

    public Page<DataSourceEntity> findAll(Pageable pageable) { return repository.findAll(pageable); }
    public DataSourceEntity findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("??????: " + id));
    }
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

    public List<Map<String, Object>> getTableColumns(Long dsId, String tableName) {
        DataSourceEntity e = findById(dsId);
        String url = "MYSQL".equalsIgnoreCase(e.getDbType())
            ? String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", e.getHost(), e.getPort(), e.getDatabaseName())
            : String.format("jdbc:dm://%s:%d/%s", e.getHost(), e.getPort(), e.getDatabaseName());
        List<Map<String, Object>> cols = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, e.getUsername(), e.getPassword())) {
            DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getColumns(null, "%", tableName, "%")) {
                while (rs.next()) {
                    cols.add(Map.of("name", rs.getString("COLUMN_NAME"), "type", rs.getString("TYPE_NAME"),
                        "nullable", rs.getInt("NULLABLE") == 1, "primaryKey", false));
                }
            }
            try (ResultSet pk = meta.getPrimaryKeys(null, null, tableName)) {
                while (pk.next()) {
                    String pkCol = pk.getString("COLUMN_NAME");
                    for (var col : cols) {
                        if (col.get("name").equals(pkCol)) ((Map)col).put("primaryKey", true);
                    }
                }
            }
        } catch (Exception ex) { throw new RuntimeException("???????: " + ex.getMessage()); }
        return cols;
    }

    private String buildJdbcUrl(DataSourceDTO dto) {
        if ("MYSQL".equalsIgnoreCase(dto.getDbType()))
            return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", dto.getHost(), dto.getPort(), dto.getDatabaseName());
        if ("DM8".equalsIgnoreCase(dto.getDbType()))
            return String.format("jdbc:dm://%s:%d/%s", dto.getHost(), dto.getPort(), dto.getDatabaseName());
        throw new IllegalArgumentException("?????????: " + dto.getDbType());
    }
    private DataSourceEntity toEntity(DataSourceDTO dto) {
        DataSourceEntity e = new DataSourceEntity();
        e.setName(dto.getName()); e.setDbType(dto.getDbType()); e.setHost(dto.getHost());
        e.setPort(dto.getPort()); e.setDatabaseName(dto.getDatabaseName());
        e.setUsername(dto.getUsername()); e.setPassword(dto.getPassword());
        return e;
    }
}
