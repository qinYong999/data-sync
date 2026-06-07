package com.datasync.server.service;

import com.datasync.core.job.SyncEventBus;
import com.datasync.core.job.SyncJobConfig;
import com.datasync.core.model.FieldMapping;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.model.enums.DbType;
import com.datasync.core.model.enums.SyncMode;
import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.entity.SyncRecordEntity;
import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.repository.DataSourceRepository;
import com.datasync.server.repository.SyncRecordRepository;
import com.datasync.server.repository.SyncTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 同步任务执行服务 — 手动触发时动态构建 Job 并启动
 */
@Service
public class SyncExecutionService {

    private static final Logger log = LoggerFactory.getLogger(SyncExecutionService.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    private final SyncTaskRepository taskRepo;
    private final DataSourceRepository dsRepo;
    private final SyncRecordRepository recordRepo;
    private final JobRepository jobRepository;
    private final JobLauncher jobLauncher;
    private final PlatformTransactionManager transactionManager;

    public SyncExecutionService(SyncTaskRepository taskRepo, DataSourceRepository dsRepo,
                                SyncRecordRepository recordRepo, JobRepository jobRepository,
                                JobLauncher jobLauncher, PlatformTransactionManager transactionManager) {
        this.taskRepo = taskRepo;
        this.dsRepo = dsRepo;
        this.recordRepo = recordRepo;
        this.jobRepository = jobRepository;
        this.jobLauncher = jobLauncher;
        this.transactionManager = transactionManager;
    }

    /**
     * 异步执行同步任务，立即返回
     */
    public CompletableFuture<Long> executeTaskAsync(Long taskId) {
        return CompletableFuture.supplyAsync(() -> executeTask(taskId));
    }

    /**
     * 执行同步任务（同步）
     */
    public Long executeTask(Long taskId) {
        // 加载任务配置
        SyncTaskEntity taskEntity = taskRepo.findById(taskId)
                .orElseThrow(() -> new RuntimeException("任务不存在: " + taskId));
        DataSourceEntity sourceEntity = dsRepo.findById(taskEntity.getSourceDsId())
                .orElseThrow(() -> new RuntimeException("源数据源不存在: " + taskEntity.getSourceDsId()));
        DataSourceEntity targetEntity = dsRepo.findById(taskEntity.getTargetDsId())
                .orElseThrow(() -> new RuntimeException("目标数据源不存在: " + taskEntity.getTargetDsId()));

        // 创建执行记录
        SyncRecordEntity record = new SyncRecordEntity();
        record.setTaskId(taskId);
        record.setStartTime(LocalDateTime.now());
        record.setStatus("RUNNING");
        record.setTriggerType("MANUAL");
        record = recordRepo.save(record);

        HikariDataSource sourceDs = null;
        HikariDataSource targetDs = null;

        try {
            SyncEventBus.publish("任务 [" + taskEntity.getName() + "] 开始执行...");

            // 构建动态数据源
            sourceDs = buildDataSource(sourceEntity);
            targetDs = buildDataSource(targetEntity);

            // 转换配置
            SyncTaskConfig taskConfig = toTaskConfig(taskEntity);

            // 构建 Spring Batch Job
            DbType targetDbType = DbType.valueOf(targetEntity.getDbType().toUpperCase());
            SyncJobConfig jobConfig = new SyncJobConfig(jobRepository, transactionManager);
            Job job = jobConfig.buildJob(taskConfig, sourceDs, targetDs, targetDbType);

            // 启动 Job（带时间参数确保每次参数不同）
            var jobParams = new JobParametersBuilder()
                    .addLong("taskId", taskId)
                    .addLong("timestamp", System.currentTimeMillis())
                    .toJobParameters();

            var execution = jobLauncher.run(job, jobParams);

            // 更新执行记录
            record.setEndTime(LocalDateTime.now());
            String statusName = execution.getStatus().name();
            record.setStatus(statusName);

            // 提取步骤执行统计
            long totalRead = 0, totalWrite = 0, totalError = 0;
            StringBuilder sb = new StringBuilder();
            for (var step : execution.getStepExecutions()) {
                totalRead += step.getReadCount();
                totalWrite += step.getWriteCount();
                totalError += step.getSkipCount();
                if ("FAILED".equals(statusName) && step.getFailureExceptions() != null
                    && !step.getFailureExceptions().isEmpty()) {
                    step.getFailureExceptions().forEach(ex -> {
                        sb.append("[").append(step.getStepName()).append("] ")
                          .append(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName())
                          .append("; ");
                    });
                }
            }
            record.setReadRows(totalRead);
            record.setWriteRows(totalWrite);
            record.setErrorRows(totalError);

            if ("FAILED".equals(statusName)) {
                record.setErrorMessage(sb.length() > 0 ? sb.toString() : "任务执行失败");
            } else {
                record.setErrorMessage(null);
            }
            recordRepo.save(record);

            String statusText = "COMPLETED".equals(statusName) ? "成功" : "失败";
            SyncEventBus.publish("任务 [" + taskEntity.getName() + "] 执行" + statusText
                + "，读取 " + (record.getReadRows() != null ? record.getReadRows() : 0)
                + " 行，写入 " + (record.getWriteRows() != null ? record.getWriteRows() : 0) + " 行");

            log.info("任务 {} 手动触发执行完成，状态: {}", taskId, execution.getStatus());
            return record.getId();

        } catch (Exception e) {
            log.error("任务 {} 执行失败", taskId, e);
            record.setEndTime(LocalDateTime.now());
            record.setStatus("FAILED");
            record.setErrorMessage(e.getMessage());
            recordRepo.save(record);
            SyncEventBus.publish("任务 [" + taskEntity.getName() + "] 执行失败: " + e.getMessage());
            throw new RuntimeException("任务执行失败: " + e.getMessage(), e);

        } finally {
            if (sourceDs != null) sourceDs.close();
            if (targetDs != null) targetDs.close();
        }
    }

    /**
     * 根据数据源实体创建 HikariCP 连接池
     */
    private HikariDataSource buildDataSource(DataSourceEntity entity) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(buildJdbcUrl(entity));
        config.setUsername(entity.getUsername());
        config.setPassword(entity.getPassword());
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        if ("MYSQL".equalsIgnoreCase(entity.getDbType())) {
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        } else if ("DM8".equalsIgnoreCase(entity.getDbType())) {
            config.setDriverClassName("dm.jdbc.driver.DmDriver");
        }
        return new HikariDataSource(config);
    }

    /**
     * 构建 JDBC URL
     */
    private String buildJdbcUrl(DataSourceEntity entity) {
        if ("MYSQL".equalsIgnoreCase(entity.getDbType())) {
            return String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8&rewriteBatchedStatements=true",
                    entity.getHost(), entity.getPort(), entity.getDatabaseName());
        } else if ("DM8".equalsIgnoreCase(entity.getDbType())) {
            return String.format("jdbc:dm://%s:%d/%s", entity.getHost(), entity.getPort(), entity.getDatabaseName());
        }
        throw new IllegalArgumentException("不支持的数据库类型: " + entity.getDbType());
    }

    /**
     * 将 SyncTaskEntity 转为 SyncTaskConfig
     */
    private SyncTaskConfig toTaskConfig(SyncTaskEntity entity) {
        SyncTaskConfig config = new SyncTaskConfig();
        config.setId(entity.getId());
        config.setName(entity.getName());
        config.setSourceDsId(entity.getSourceDsId());
        config.setTargetDsId(entity.getTargetDsId());
        config.setSourceTable(entity.getSourceTable());
        config.setTargetTable(entity.getTargetTable());
        config.setSyncMode(SyncMode.valueOf(entity.getSyncMode()));
        config.setIncrColumn(entity.getIncrColumn());
        config.setIncrValue(entity.getIncrValue());
        config.setCronExpression(entity.getCronExpression());
        config.setPageSize(entity.getPageSize() != null ? entity.getPageSize() : 1000);
        config.setBatchSize(entity.getBatchSize() != null ? entity.getBatchSize() : 500);

        // 解析字段映射 JSON
        if (entity.getMappingJson() != null && !entity.getMappingJson().isBlank()) {
            try {
                List<FieldMapping> mappings = mapper.readValue(entity.getMappingJson(),
                        new TypeReference<List<FieldMapping>>() {});
                config.setFieldMappings(mappings);
            } catch (Exception e) {
                throw new RuntimeException("解析字段映射配置失败", e);
            }
        }
        return config;
    }
}
