package com.datasync.server.controller;
import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.model.DataSourceDTO;
import com.datasync.server.service.DataSourceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController
@RequestMapping("/api/datasources")
public class DataSourceController {
    private final DataSourceService service;
    public DataSourceController(DataSourceService service) { this.service = service; }
    @GetMapping List<DataSourceEntity> list() { return service.findAll(); }
    @GetMapping("/{id}") DataSourceEntity get(@PathVariable Long id) { return service.findById(id); }
    @PostMapping DataSourceEntity create(@RequestBody DataSourceDTO dto) { return service.create(dto); }
    @PutMapping("/{id}") DataSourceEntity update(@PathVariable Long id, @RequestBody DataSourceDTO dto) { return service.update(id, dto); }
    @DeleteMapping("/{id}") ResponseEntity<Void> delete(@PathVariable Long id) { service.delete(id); return ResponseEntity.ok().build(); }
    @PostMapping("/{id}/test") ResponseEntity<Boolean> test(@PathVariable Long id) {
        DataSourceEntity e = service.findById(id);
        DataSourceDTO dto = new DataSourceDTO();
        dto.setDbType(e.getDbType()); dto.setHost(e.getHost()); dto.setPort(e.getPort());
        dto.setDatabaseName(e.getDatabaseName()); dto.setUsername(e.getUsername()); dto.setPassword(e.getPassword());
        return ResponseEntity.ok(service.testConnection(dto));
    }
}