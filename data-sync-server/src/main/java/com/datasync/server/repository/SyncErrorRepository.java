package com.datasync.server.repository;

import com.datasync.server.entity.SyncErrorEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface SyncErrorRepository extends JpaRepository<SyncErrorEntity, Long> {

    Page<SyncErrorEntity> findByRecordIdOrderByIdAsc(Long recordId, Pageable pageable);

    Page<SyncErrorEntity> findByRecordIdAndPhaseOrderByIdAsc(Long recordId, String phase, Pageable pageable);

    long countByRecordId(Long recordId);

    @Modifying
    @Query("DELETE FROM SyncErrorEntity e WHERE e.createdAt < :before")
    int deleteBefore(@Param("before") LocalDateTime before);
}
