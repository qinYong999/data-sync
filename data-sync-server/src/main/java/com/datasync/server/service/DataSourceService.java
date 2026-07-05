package com.datasync.server.service;

import com.datasync.core.job.SqlValidator;
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
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class DataSourceService {
    private final DataSourceRepository repository;
    public DataSourceService(DataSourceRepository repository) { this.repository = repository; }

    public Page<DataSourceEntity> findAll(Pageable pageable) { return repository.findAll(pageable); }
    public DataSourceEntity findById(Long id) {
        return repository.findById(id).orElseThrow(() -> new RuntimeException("数据源不存在: " + id));
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
        String url = buildUrl(e);
        List<Map<String, Object>> cols = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, e.getUsername(), e.getPassword())) {
            DatabaseMetaData meta = c.getMetaData();
            try (ResultSet rs = meta.getColumns(e.getDatabaseName(), null, tableName, "%")) {
                while (rs.next()) {
                    Map<String, Object> col = new HashMap<>();
                    col.put("name", rs.getString("COLUMN_NAME"));
                    col.put("type", rs.getString("TYPE_NAME"));
                    col.put("nullable", rs.getInt("NULLABLE") == 1);
                    col.put("primaryKey", false);
                    cols.add(col);
                }
            }
            try (ResultSet pk = meta.getPrimaryKeys(e.getDatabaseName(), null, tableName)) {
                while (pk.next()) {
                    String pkCol = pk.getString("COLUMN_NAME");
                    for (var col : cols) {
                        if (col.get("name").equals(pkCol)) ((Map)col).put("primaryKey", true);
                    }
                }
            }
        } catch (Exception ex) {
            String detail = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
            throw new RuntimeException("获取列信息失败: " + detail);
        }
        return cols;
    }

    /** 获取指定数据源的所有表名 */
    public List<String> getTableNames(Long dsId) {
        DataSourceEntity e = findById(dsId);
        String url = buildUrl(e);
        List<String> tables = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, e.getUsername(), e.getPassword());
             ResultSet rs = c.getMetaData().getTables(e.getDatabaseName(), null, "%", new String[]{"TABLE", "VIEW"})) {
            while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
        } catch (Exception ex) {
            throw new RuntimeException("获取表列表失败: " + ex.getMessage());
        }
        Collections.sort(tables);
        return tables;
    }

    /** 预览自定义 SQL 的前 N 行结果 */
    public List<Map<String, Object>> previewSql(Long dsId, String sql, int limit) {
        SqlValidator.validateSelectSql(sql);
        DataSourceEntity e = findById(dsId);
        String url = buildUrl(e);
        List<Map<String, Object>> rows = new ArrayList<>();
        String cleanSql = sql.trim().replaceAll(";$", "");
        String previewSql = "SELECT * FROM (" + cleanSql + ") _sql_wrapper LIMIT " + limit;
        try (Connection conn = DriverManager.getConnection(url, e.getUsername(), e.getPassword());
             java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(previewSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        } catch (Exception ex) {
            throw new RuntimeException("预览SQL失败: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
        return rows;
    }

    /** 获取自定义 SQL 的查询结果列信息 */
    public List<Map<String, Object>> getSqlColumns(Long dsId, String sql) {
        SqlValidator.validateSelectSql(sql);
        DataSourceEntity e = findById(dsId);
        String url = buildUrl(e);
        List<Map<String, Object>> cols = new ArrayList<>();
        String cleanSql = sql.trim().replaceAll(";$", "");
        String metaSql = "SELECT * FROM (" + cleanSql + ") _sql_wrapper LIMIT 0";
        try (Connection conn = DriverManager.getConnection(url, e.getUsername(), e.getPassword());
             java.sql.Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(metaSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                Map<String, Object> col = new HashMap<>();
                col.put("name", meta.getColumnLabel(i));
                col.put("type", meta.getColumnTypeName(i));
                col.put("nullable", true);
                col.put("primaryKey", false);
                cols.add(col);
            }
        } catch (Exception ex) {
            throw new RuntimeException("获取自定义SQL列信息失败: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
        }
        return cols;
    }

    private String buildUrl(DataSourceEntity e) {
        return "MYSQL".equalsIgnoreCase(e.getDbType())
            ? String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai", e.getHost(), e.getPort(), e.getDatabaseName())
            : String.format("jdbc:dm://%s:%d/%s", e.getHost(), e.getPort(), e.getDatabaseName());
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
