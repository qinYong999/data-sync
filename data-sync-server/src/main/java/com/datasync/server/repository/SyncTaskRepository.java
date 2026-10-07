package com.datasync.server.repository;

import com.datasync.server.entity.SyncTaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface SyncTaskRepository extends JpaRepository<SyncTaskEntity, Long> {

    List<SyncTaskEntity> findByStatus(String status);

    @Query("SELECT t FROM SyncTaskEntity t WHERE t.status = ?1 AND t.cronExpression IS NOT NULL AND t.cronExpression <> ''")
    List<SyncTaskEntity> findByStatusAndCronExpressionIsNotNull(String status);

    /** 启动时重新注册用的候选集合：enabled=1 且配置了 cron */
    @Query("SELECT t FROM SyncTaskEntity t WHERE t.enabled = true AND t.cronExpression IS NOT NULL AND t.cronExpression <> ''")
    List<SyncTaskEntity> findEnabledSchedulable();

    long countByEnabledTrue();
}
