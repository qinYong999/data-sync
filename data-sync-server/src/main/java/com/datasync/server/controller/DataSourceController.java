package com.datasync.server.controller;

import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.model.DataSourceDTO;
import com.datasync.server.service.DataSourceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 数据源接口（契约 §4.3）。
 *
 * <p>既有路径与响应结构保持兼容；新增两个 {@code test-detail}（返回可读中文原因，口令已清洗）
 * 与 {@code /{id}/tables/{table}/columns}（REST 风格的表列查询）。</p>
 */
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {

    private final DataSourceService service;

    public DataSourceController(DataSourceService service) {
        this.service = service;
    }

    @GetMapping
    public Page<DataSourceEntity> list(Pageable pageable) {
        return service.findAll(pageable);
    }

    @GetMapping("/{id}")
    public DataSourceEntity get(@PathVariable Long id) {
        return service.findById(id);
    }

    @PostMapping
    public DataSourceEntity create(@RequestBody DataSourceDTO dto) {
        return service.create(dto);
    }

    @PutMapping("/{id}")
    public DataSourceEntity update(@PathVariable Long id, @RequestBody DataSourceDTO dto) {
        return service.update(id, dto);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.ok().build();
    }

    /** 兼容既有接口：裸 boolean */
    @PostMapping("/{id}/test")
    public ResponseEntity<Boolean> test(@PathVariable Long id) {
        return ResponseEntity.ok(service.testStoredConnection(id));
    }

    /** 兼容既有接口：裸 boolean */
    @PostMapping("/test")
    public ResponseEntity<Boolean> testDirect(@RequestBody DataSourceDTO dto) {
        return ResponseEntity.ok(service.testConnection(dto));
    }

    /** 新增：带可读失败原因的连通性测试（口令已清洗，可安全展示） */
    @PostMapping("/test-detail")
    public DataSourceService.ConnectionTestResult testDirectDetail(@RequestBody DataSourceDTO dto) {
        return service.testConnectionDetail(null, dto);
    }

    /** 新增：用已存口令测试并给出可读原因（口令留空即用库里的） */
    @PostMapping("/{id}/test-detail")
    public DataSourceService.ConnectionTestResult testDetail(@PathVariable Long id, @RequestBody(required = false) DataSourceDTO dto) {
        DataSourceDTO effective = dto == null ? new DataSourceDTO() : dto;
        if (effective.getDbType() == null) {
            // 只传了 password（或什么都不传）：其余字段取库里已存的
            DataSourceEntity entity = service.findById(id);
            effective.setId(entity.getId());
            effective.setName(entity.getName());
            effective.setDbType(entity.getDbType());
            effective.setHost(entity.getHost());
            effective.setPort(entity.getPort());
            effective.setDatabaseName(entity.getDatabaseName());
            effective.setUsername(entity.getUsername());
        }
        return service.testConnectionDetail(id, effective);
    }

    @GetMapping("/{id}/tables")
    public List<String> getTableNames(@PathVariable Long id) {
        return service.getTableNames(id);
    }

    /** 兼容既有接口（query param 形式） */
    @GetMapping("/{id}/columns")
    public List<Map<String, Object>> getTableColumns(@PathVariable Long id, @RequestParam String table) {
        return service.getTableColumns(id, table);
    }

    /** 新增：REST 风格路径（契约 §4.3） */
    @GetMapping("/{id}/tables/{table}/columns")
    public List<Map<String, Object>> getTableColumnsRest(@PathVariable Long id, @PathVariable String table) {
        return service.getTableColumns(id, table);
    }

    @PostMapping("/{id}/sql-columns")
    public List<Map<String, Object>> getSqlColumns(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return service.getSqlColumns(id, body.get("sql"));
    }

    @PostMapping("/{id}/sql-preview")
    public List<Map<String, Object>> previewSql(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String sql = body.get("sql");
        int limit = body.containsKey("limit") && body.get("limit") != null
            ? Integer.parseInt(body.get("limit")) : 5;
        return service.previewSql(id, sql, limit);
    }
}
