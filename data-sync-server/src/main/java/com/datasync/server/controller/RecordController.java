package com.datasync.server.controller;

import com.datasync.server.entity.SyncErrorEntity;
import com.datasync.server.entity.SyncRecordEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.service.RunRecordStore;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 执行记录与坏行明细（契约 §4.3）。
 *
 * <p>分页统一用 Spring Data {@code Page} 形状：
 * {@code {content,totalElements,totalPages,number,size,first,last,numberOfElements,empty}}，
 * 参数 {@code page}（0 起）+ {@code size}（上限 200）。</p>
 */
@RestController
@RequestMapping("/api")
public class RecordController {

    private static final int MAX_PAGE_SIZE = 200;

    private final RunRecordStore recordStore;

    public RecordController(RunRecordStore recordStore) {
        this.recordStore = recordStore;
    }

    /** 某任务的执行记录（分页，倒序） */
    @GetMapping("/tasks/{taskId}/records")
    public Page<SyncRecordEntity> records(@PathVariable Long taskId,
                                          @RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "20") int size) {
        return recordStore.findRecords(taskId, page(page, size, Sort.by(Sort.Direction.DESC, "startTime")));
    }

    @GetMapping("/records/{id}")
    public SyncRecordEntity get(@PathVariable Long id) {
        SyncRecordEntity record = recordStore.findRecord(id);
        if (record == null) {
            throw AppException.notFound("执行记录不存在: " + id);
        }
        return record;
    }

    /** 坏行明细分页（D8），可按 phase 过滤：PREFLIGHT / READ / MAP / WRITE */
    @GetMapping("/records/{id}/errors")
    public Page<SyncErrorEntity> errors(@PathVariable Long id,
                                        @RequestParam(required = false) String phase,
                                        @RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "20") int size) {
        if (recordStore.findRecord(id) == null) {
            throw AppException.notFound("执行记录不存在: " + id);
        }
        // 坏行明细表只有 created_at（没有 start_time）：按 id 升序即写入顺序，
        // 语义等价且不依赖可空列。曾用 Sort.by("startTime") 导致该端点 100% 抛
        // PropertyReferenceException → 500（PropertyReferenceException 只在真正调用时才炸）。
        return recordStore.findErrors(id, phase, page(page, size, Sort.by(Sort.Direction.ASC, "id")));
    }

    private static Pageable page(int page, int size, Sort sort) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        return PageRequest.of(safePage, safeSize, sort);
    }
}
