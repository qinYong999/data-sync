package com.datasync.server.repository;

import com.datasync.server.entity.SyncRecordEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface SyncRecordRepository extends JpaRepository<SyncRecordEntity, Long> {

    List<SyncRecordEntity> findByTaskIdOrderByStartTimeDesc(Long taskId);

    Page<SyncRecordEntity> findByTaskIdOrderByStartTimeDesc(Long taskId, Pageable pageable);

    List<SyncRecordEntity> findByStatus(String status);

    List<SyncRecordEntity> findByStatusOrderByStartTimeDesc(String status, Pageable pageable);

    long countByStatus(String status);

    long countByTaskId(Long taskId);

    @Query("SELECT COALESCE(SUM(r.readRows), 0) FROM SyncRecordEntity r")
    long sumReadRows();

    /** 保留策略清理：只删已结束的记录，绝不删 RUNNING */
    @Modifying
    @Query("DELETE FROM SyncRecordEntity r WHERE r.startTime < :before AND (r.status IS NULL OR r.status <> 'RUNNING')")
    int deleteFinishedBefore(@Param("before") LocalDateTime before);

    /** 进度节流落库：只更新统计列，避免把整行读出来再写回 */
    @Modifying
    @Query("UPDATE SyncRecordEntity r SET r.readRows = :read, r.writeRows = :written, r.skippedRows = :skipped WHERE r.id = :id")
    int updateProgress(@Param("id") Long id, @Param("read") long read, @Param("written") long written, @Param("skipped") long skipped);
}
