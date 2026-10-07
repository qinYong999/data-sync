package com.datasync.server.repository;

import com.datasync.server.entity.DataSourceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface DataSourceRepository extends JpaRepository<DataSourceEntity, Long> {

    /**
     * 找出仍是历史明文的行（非 {@code ENC(} 开头且非空）。
     * 启动扫描用它把明文一次性升级为密文，不依赖任何业务库连通性
     * （旧库里最常见的情况恰恰是"躺着一条连不上的旧数据源"）。
     */
    @Query("SELECT d FROM DataSourceEntity d WHERE d.password NOT LIKE 'ENC(%' AND d.password <> ''")
    List<DataSourceEntity> findLegacyPlaintextPasswords();
}
