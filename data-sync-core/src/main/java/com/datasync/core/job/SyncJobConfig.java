package com.datasync.core.job;

import com.datasync.core.job.processor.ColumnMappingProcessor;
import com.datasync.core.job.reader.PageReader;
import com.datasync.core.job.reader.IncrementalReader;
import com.datasync.core.job.writer.JdbcBatchWriter;
import com.datasync.core.job.writer.Dm8BatchWriter;
import com.datasync.core.mapper.MySqlToDm8TypeMapper;
import com.datasync.core.mapper.TypeMapper;
import com.datasync.core.model.FieldMapping;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.model.enums.DbType;
import com.datasync.core.model.enums.SyncMode;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.item.ItemReader;
import org.springframework.batch.item.ItemWriter;
import org.springframework.transaction.PlatformTransactionManager;
import javax.sql.DataSource;
import java.util.Map;

/**
 * 同步任务 Job 工厂 — 根据 SyncTaskConfig 动态创建 Spring Batch Job
 */
public class SyncJobConfig {

    private final JobRepository jobRepository;
    private final PlatformTransactionManager transactionManager;

    public SyncJobConfig(JobRepository jobRepository, PlatformTransactionManager transactionManager) {
        this.jobRepository = jobRepository;
        this.transactionManager = transactionManager;
    }

    /**
     * 根据任务配置构建完整的 Spring Batch Job
     */
    public Job buildJob(SyncTaskConfig taskConfig, DataSource sourceDs, DataSource targetDs, DbType targetDbType) {
        String jobName = "syncJob_" + taskConfig.getId();

        // Reader
        ItemReader<Map<String, Object>> reader = buildReader(taskConfig, sourceDs);

        // Processor
        TypeMapper typeMapper = targetDbType == DbType.DM8 ? new MySqlToDm8TypeMapper() : null;
        ColumnMappingProcessor processor = new ColumnMappingProcessor(
            taskConfig.getFieldMappings() != null ? taskConfig.getFieldMappings() : java.util.List.of(),
            typeMapper != null ? typeMapper : new MySqlToDm8TypeMapper()
        );

        // Writer
        String[] columns = extractColumns(taskConfig);
        boolean isFullSync = taskConfig.getSyncMode() == SyncMode.FULL;
        ItemWriter<Map<String, Object>> writer;
        if (targetDbType == DbType.DM8) {
            writer = new Dm8BatchWriter(targetDs, taskConfig.getTargetTable(), columns, isFullSync);
        } else {
            writer = new JdbcBatchWriter(targetDs, taskConfig.getTargetTable(), columns, DbType.MYSQL, isFullSync);
        }

        // Step
        Step step = new StepBuilder("syncStep_" + taskConfig.getId(), jobRepository)
            .<Map<String, Object>, Map<String, Object>>chunk(taskConfig.getPageSize(), transactionManager)
            .reader(reader)
            .processor(processor)
            .writer(writer)
            .build();

        // Job
        return new JobBuilder(jobName, jobRepository)
            .start(step)
            .listener(new SyncJobListener())
            .build();
    }

    private ItemReader<Map<String, Object>> buildReader(SyncTaskConfig config, DataSource sourceDs) {
        if (config.getSyncMode() == SyncMode.INCR || config.getSyncMode() == SyncMode.FULL_INCR) {
            if (config.getIncrColumn() != null && !config.getIncrColumn().isBlank()) {
                return new IncrementalReader(sourceDs, config.getSourceTable(),
                    config.getIncrColumn(), "id", config.getIncrValue());
            }
        }
        return new PageReader(sourceDs, config.getSourceTable(), "id", config.getPageSize());
    }

    private String[] extractColumns(SyncTaskConfig config) {
        if (config.getFieldMappings() == null || config.getFieldMappings().isEmpty()) {
            return new String[]{"*"};
        }
        return config.getFieldMappings().stream()
            .map(FieldMapping::getTargetColumn)
            .toArray(String[]::new);
    }
}