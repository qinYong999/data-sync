package com.datasync.server.controller;

import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.model.DataSourceDTO;
import com.datasync.server.service.DataSourceService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {
    private final DataSourceService service;
    public DataSourceController(DataSourceService service) { this.service = service; }

    @GetMapping
    public Page<DataSourceEntity> list(Pageable pageable) { return service.findAll(pageable); }

    @GetMapping("/{id}")
    public DataSourceEntity get(@PathVariable Long id) { return service.findById(id); }

    @PostMapping
    public DataSourceEntity create(@RequestBody DataSourceDTO dto) { return service.create(dto); }

    @PutMapping("/{id}")
    public DataSourceEntity update(@PathVariable Long id, @RequestBody DataSourceDTO dto) { return service.update(id, dto); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) { service.delete(id); return ResponseEntity.ok().build(); }

    @PostMapping("/{id}/test")
    public ResponseEntity<Boolean> test(@PathVariable Long id) {
        DataSourceEntity e = service.findById(id);
        DataSourceDTO dto = new DataSourceDTO();
        dto.setDbType(e.getDbType()); dto.setHost(e.getHost()); dto.setPort(e.getPort());
        dto.setDatabaseName(e.getDatabaseName()); dto.setUsername(e.getUsername()); dto.setPassword(e.getPassword());
        return ResponseEntity.ok(service.testConnection(dto));
    }
}
