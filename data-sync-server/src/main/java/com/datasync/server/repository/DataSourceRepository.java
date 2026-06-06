package com.datasync.server.repository;
import com.datasync.server.entity.DataSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
public interface DataSourceRepository extends JpaRepository<DataSourceEntity, Long> {}