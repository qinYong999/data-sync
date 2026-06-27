package com.datasync.server.repository;
import com.datasync.server.entity.SyncTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import java.util.List;
public interface SyncTaskRepository extends JpaRepository<SyncTaskEntity, Long> {
    List<SyncTaskEntity> findByStatus(String status);

    @Query("SELECT t FROM SyncTaskEntity t WHERE t.status = ?1 AND t.cronExpression IS NOT NULL AND t.cronExpression <> ''")
    List<SyncTaskEntity> findByStatusAndCronExpressionIsNotNull(String status);
}