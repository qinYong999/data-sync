package com.datasync.server.repository;
import com.datasync.server.entity.SyncRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SyncRecordRepository extends JpaRepository<SyncRecordEntity, Long> {
    List<SyncRecordEntity> findByTaskIdOrderByStartTimeDesc(Long taskId);
}