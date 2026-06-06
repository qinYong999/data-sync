package com.datasync.server.repository;
import com.datasync.server.entity.SyncTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SyncTaskRepository extends JpaRepository<SyncTaskEntity, Long> {
    List<SyncTaskEntity> findByStatus(String status);
}