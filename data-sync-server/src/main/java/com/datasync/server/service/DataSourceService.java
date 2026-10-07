package com.datasync.server.service;

import com.datasync.core.dialect.Dialects;
import com.datasync.core.dialect.SqlDialect;
import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.enums.DbType;
import com.datasync.core.preflight.CustomSqlGuard;
import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.model.DataSourceDTO;
import com.datasync.server.repository.DataSourceRepository;
import com.datasync.server.security.CredentialCipher;
import com.datasync.server.security.LegacyPasswordUpgrader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据源 CRUD + 元数据浏览（契约 §4.1）。
 *
 * <p>相比改造前：</p>
 * <ul>
 *   <li>口令 AES-GCM 加密落库（D9），出参由实体注解强制为空（{@code DataSourceEntity#getPasswordForJson}）；</li>
 *   <li>编辑时口令为空 = 不修改；</li>
 *   <li>连接统一走 {@link ConnectionPoolRegistry} 缓存池（D12），不再每次新建/销毁；</li>
 *   <li>连接测试的异常消息经口令清洗后才返回（不泄漏明文）；</li>
 *   <li>自定义 SQL 校验改用 core 的 {@link CustomSqlGuard}（旧的 SqlValidator 已删除）。</li>
 * </ul>
 */
@Service
public class DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceService.class);

    private final DataSourceRepository repository;
    private final ConnectionPoolRegistry poolRegistry;
    private final CredentialCipher cipher;
    private final LegacyPasswordUpgrader legacyPasswordUpgrader;

    public DataSourceService(DataSourceRepository repository, ConnectionPoolRegistry poolRegistry,
                             CredentialCipher cipher, LegacyPasswordUpgrader legacyPasswordUpgrader) {
        this.repository = repository;
        this.poolRegistry = poolRegistry;
        this.cipher = cipher;
        this.legacyPasswordUpgrader = legacyPasswordUpgrader;
    }

    // ------------------------------------------------------------------ CRUD

    /**
     * 列表查询。顺带完成契约 §4.2 的"首次读取即升级"：
     * 读到历史明文口令就地加密回写（{@link LegacyPasswordUpgrader} 自带写库事务，
     * 常态是"零写库"——绝大多数行本来就已经是密文）。
     *
     * <p>刻意<b>不</b>在外层加事务：本方法以读为主，只有真的遇到明文行才会走一次单行写事务。</p>
     */
    public Page<DataSourceEntity> findAll(Pageable pageable) {
        // 口令由实体上的 @JsonIgnore + @JsonProperty("password") 保证不外泄
        Page<DataSourceEntity> page = repository.findAll(pageable);
        page.forEach(legacyPasswordUpgrader::upgradeIfLegacy);
        return page;
    }

    /** 单条查询，同样顺带升级历史明文口令 */
    public DataSourceEntity findById(Long id) {
        DataSourceEntity entity = findEntity(id);
        legacyPasswordUpgrader.upgradeIfLegacy(entity);
        return entity;
    }

    @Transactional
    public DataSourceEntity create(DataSourceDTO dto) {
        validate(dto);
        DataSourceEntity entity = new DataSourceEntity();
        apply(entity, dto);
        entity.passwordCipher(cipher.encrypt(dto.getPassword() == null ? "" : dto.getPassword()));
        DataSourceEntity saved = repository.save(entity);
        log.info("已创建数据源 {}（{}:{}/{}）", saved.getId(), saved.getHost(), saved.getPort(), saved.getDatabaseName());
        return saved;
    }

    @Transactional
    public DataSourceEntity update(Long id, DataSourceDTO dto) {
        DataSourceEntity entity = findEntity(id);
        validate(dto);
        apply(entity, dto);
        // 前端留空 = 不修改口令
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            entity.passwordCipher(cipher.encrypt(dto.getPassword()));
        }
        DataSourceEntity saved = repository.save(entity);
        poolRegistry.invalidate(id);
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        findEntity(id);
        repository.deleteById(id);
        poolRegistry.invalidate(id);
    }

    // ------------------------------------------------------------------ 连接测试

    public record ConnectionTestResult(boolean success, String message) { }

    /** 用请求里给出的参数测试（新建数据源场景）；不落库、不建池 */
    public boolean testConnection(DataSourceDTO dto) {
        return testConnectionDetail(null, dto).success();
    }

    public ConnectionTestResult testConnectionDetail(Long id, DataSourceDTO dto) {
        String password;
        if (dto.getPassword() != null && !dto.getPassword().isBlank()) {
            password = dto.getPassword();
        } else if (id != null) {
            password = cipher.decrypt(findEntity(id).passwordCipher());
        } else {
            password = "";
        }
        String url;
        try {
            url = ConnectionPoolRegistry.jdbcUrl(dto.getDbType(), dto.getHost(), dto.getPort(), dto.getDatabaseName());
        } catch (IllegalArgumentException e) {
            return new ConnectionTestResult(false, "连接失败：" + e.getMessage());
        }
        if (url.startsWith("jdbc:mysql:")) {
            url = url + "&connectTimeout=5000&socketTimeout=15000";
        }
        try (Connection connection = DriverManager.getConnection(url, dto.getUsername(), password)) {
            boolean valid = connection.isValid(5);
            return new ConnectionTestResult(valid, valid ? "连接成功" : "连接失败：连接不可用");
        } catch (Exception e) {
            String reason = CredentialCipher.scrub(messageOf(e), password);
            log.warn("数据源连通性测试失败（{}:{}/{}）：{}", dto.getHost(), dto.getPort(), dto.getDatabaseName(), reason);
            return new ConnectionTestResult(false, "连接失败：" + reason);
        }
    }

    /** 用库里已存的口令测试（老接口 {@code POST /api/datasources/{id}/test} 用） */
    public boolean testStoredConnection(Long id) {
        DataSourceEntity entity = findEntity(id);
        DataSourceDTO dto = toDto(entity);
        dto.setPassword(cipher.decrypt(entity.passwordCipher()));
        return testConnectionDetail(id, dto).success();
    }

    // ------------------------------------------------------------------ 元数据浏览

    public List<String> getTableNames(Long dsId) {
        DataSourceEntity entity = findEntity(dsId);
        List<String> tables = new ArrayList<>();
        try (Connection connection = poolRegistry.get(dsId).getConnection();
             ResultSet rs = connection.getMetaData().getTables(entity.getDatabaseName(), null, "%",
                 new String[] { "TABLE", "VIEW" })) {
            while (rs.next()) {
                tables.add(rs.getString("TABLE_NAME"));
            }
        } catch (Exception e) {
            throw AppException.badRequest("METADATA_READ_FAILED",
                "获取表列表失败：" + CredentialCipher.scrub(messageOf(e)));
        }
        Collections.sort(tables);
        return tables;
    }

    public List<Map<String, Object>> getTableColumns(Long dsId, String tableName) {
        DataSourceEntity entity = findEntity(dsId);
        List<Map<String, Object>> columns = new ArrayList<>();
        try (Connection connection = poolRegistry.get(dsId).getConnection()) {
            DatabaseMetaData meta = connection.getMetaData();
            try (ResultSet rs = meta.getColumns(entity.getDatabaseName(), null, tableName, "%")) {
                while (rs.next()) {
                    Map<String, Object> column = new HashMap<>();
                    column.put("name", rs.getString("COLUMN_NAME"));
                    column.put("type", rs.getString("TYPE_NAME"));
                    column.put("nullable", rs.getInt("NULLABLE") == 1);
                    column.put("primaryKey", false);
                    columns.add(column);
                }
            }
            try (ResultSet keys = meta.getPrimaryKeys(entity.getDatabaseName(), null, tableName)) {
                while (keys.next()) {
                    String keyColumn = keys.getString("COLUMN_NAME");
                    for (Map<String, Object> column : columns) {
                        if (keyColumn.equals(column.get("name"))) {
                            column.put("primaryKey", true);
                        }
                    }
                }
            }
        } catch (Exception e) {
            throw AppException.badRequest("METADATA_READ_FAILED",
                "获取列信息失败：" + CredentialCipher.scrub(messageOf(e)));
        }
        return columns;
    }

    /** 自定义 SQL 预览：只读校验 + 键集分页无关的限量语法 */
    public List<Map<String, Object>> previewSql(Long dsId, String sql, int limit) {
        DataSourceEntity entity = findEntity(dsId);
        String cleanSql = validatedSql(sql);
        SqlDialect dialect = dialectOf(entity);
        String preview = CustomSqlGuard.wrap(cleanSql);
        String limitClause = dialect.limitClause(Math.max(1, Math.min(limit, 1000)));
        if (limitClause != null && !limitClause.isBlank()) {
            preview = preview + " " + limitClause.trim();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        try (Connection conn = poolRegistry.get(dsId).getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(preview)) {
            ResultSetMetaData meta = rs.getMetaData();
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                rows.add(row);
            }
        } catch (Exception e) {
            throw AppException.badRequest("CUSTOM_SQL_INVALID",
                "预览 SQL 失败：" + CredentialCipher.scrub(messageOf(e)));
        }
        return rows;
    }

    /** 自定义 SQL 的结果列信息（不取数据，只探元数据） */
    public List<Map<String, Object>> getSqlColumns(Long dsId, String sql) {
        findEntity(dsId);
        String cleanSql = validatedSql(sql);
        String metaSql = CustomSqlGuard.wrap(cleanSql) + " WHERE 1=0";
        List<Map<String, Object>> columns = new ArrayList<>();
        try (Connection conn = poolRegistry.get(dsId).getConnection();
             Statement statement = conn.createStatement();
             ResultSet rs = statement.executeQuery(metaSql)) {
            ResultSetMetaData meta = rs.getMetaData();
            for (int i = 1; i <= meta.getColumnCount(); i++) {
                Map<String, Object> column = new HashMap<>();
                column.put("name", meta.getColumnLabel(i));
                column.put("type", meta.getColumnTypeName(i));
                column.put("nullable", true);
                column.put("primaryKey", false);
                columns.add(column);
            }
        } catch (Exception e) {
            throw AppException.badRequest("CUSTOM_SQL_INVALID",
                "获取自定义SQL列信息失败：" + CredentialCipher.scrub(messageOf(e)));
        }
        return columns;
    }

    // ------------------------------------------------------------------ 内部

    private DataSourceEntity findEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> AppException.notFound("数据源不存在: " + id));
    }

    private String validatedSql(String sql) {
        PreflightIssue issue = CustomSqlGuard.validate(sql);
        if (issue != null) {
            throw AppException.badRequest(issue.getCode() == null ? "CUSTOM_SQL_INVALID" : issue.getCode(),
                issue.getMessage());
        }
        return sql.trim().replaceAll(";$", "");
    }

    private SqlDialect dialectOf(DataSourceEntity entity) {
        try {
            return Dialects.of(DbType.valueOf(String.valueOf(entity.getDbType()).toUpperCase()));
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("UNSUPPORTED_DB_TYPE", "不支持的数据库类型: " + entity.getDbType());
        }
    }

    private void validate(DataSourceDTO dto) {
        if (dto == null) {
            throw AppException.badRequest("INVALID_DATASOURCE", "数据源参数不能为空");
        }
        requireText(dto.getName(), "数据源名称");
        requireText(dto.getDbType(), "数据库类型");
        requireText(dto.getHost(), "主机地址");
        requireText(dto.getDatabaseName(), "数据库名");
        requireText(dto.getUsername(), "登录用户名");
        if (dto.getPort() == null || dto.getPort() <= 0 || dto.getPort() > 65535) {
            throw AppException.badRequest("INVALID_DATASOURCE", "端口号必须在 1-65535 之间");
        }
        String type = dto.getDbType().trim().toUpperCase();
        if (!"MYSQL".equals(type) && !"DM8".equals(type)) {
            throw AppException.badRequest("UNSUPPORTED_DB_TYPE", "不支持的数据库类型：" + dto.getDbType() + "，可选值：MYSQL / DM8");
        }
        dto.setDbType(type);
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw AppException.badRequest("INVALID_DATASOURCE", field + "不能为空");
        }
    }

    private void apply(DataSourceEntity entity, DataSourceDTO dto) {
        entity.setName(dto.getName());
        entity.setDbType(dto.getDbType());
        entity.setHost(dto.getHost());
        entity.setPort(dto.getPort());
        entity.setDatabaseName(dto.getDatabaseName());
        entity.setUsername(dto.getUsername());
    }

    private DataSourceDTO toDto(DataSourceEntity entity) {
        DataSourceDTO dto = new DataSourceDTO();
        dto.setId(entity.getId());
        dto.setName(entity.getName());
        dto.setDbType(entity.getDbType());
        dto.setHost(entity.getHost());
        dto.setPort(entity.getPort());
        dto.setDatabaseName(entity.getDatabaseName());
        dto.setUsername(entity.getUsername());
        return dto;
    }

    private static String messageOf(Throwable e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
