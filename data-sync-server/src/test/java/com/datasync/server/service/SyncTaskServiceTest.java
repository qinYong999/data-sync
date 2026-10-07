package com.datasync.server.service;

import com.datasync.server.config.AppProperties;
import com.datasync.server.entity.DataSourceEntity;
import com.datasync.server.entity.SyncTaskEntity;
import com.datasync.server.exception.AppException;
import com.datasync.server.model.TaskDTO;
import com.datasync.server.repository.DataSourceRepository;
import com.datasync.server.repository.SyncTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 任务校验与调度：cron 非法给中文 400（而不是 500），配置错误在保存时就被拦住。
 */
class SyncTaskServiceTest {

    private SyncTaskRepository taskRepo;
    private DataSourceRepository dsRepo;
    private SyncSchedulerService scheduler;
    private SyncTaskService service;

    @BeforeEach
    void setUp() {
        taskRepo = mock(SyncTaskRepository.class);
        dsRepo = mock(DataSourceRepository.class);
        scheduler = mock(SyncSchedulerService.class);
        ObjectMapper objectMapper = new ObjectMapper();
        AppProperties properties = new AppProperties();
        service = new SyncTaskService(taskRepo, dsRepo, scheduler,
            new SyncTaskConfigMapper(properties, objectMapper), objectMapper);
        when(taskRepo.save(any(SyncTaskEntity.class))).thenAnswer(invocation -> {
            SyncTaskEntity entity = invocation.getArgument(0);
            if (entity.getId() == null) {
                entity.setId(1L);
            }
            return entity;
        });
        when(dsRepo.findById(anyLong())).thenReturn(Optional.of(new DataSourceEntity()));
    }

    private SyncTaskEntity existingTask() {
        SyncTaskEntity task = new SyncTaskEntity();
        task.setId(1L);
        task.setName("测试任务");
        task.setSourceDsId(1L);
        task.setTargetDsId(2L);
        task.setSourceTable("t_src");
        task.setTargetTable("t_dst");
        task.setSyncMode("FULL");
        // 默认启用：便于验证"改 cron/启用 → 注册调度"
        task.applyEnabled(true);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        return task;
    }

    private TaskDTO validDto() {
        TaskDTO dto = new TaskDTO();
        dto.setName("测试任务");
        dto.setSourceDsId(1L);
        dto.setTargetDsId(2L);
        dto.setSourceTable("t_src");
        dto.setTargetTable("t_dst");
        dto.setSyncMode("FULL");
        return dto;
    }

    @Test
    @DisplayName("Quartz cron 校验：5 段 Spring 表达式非法，报中文 400")
    void fiveFieldCronIsRejectedWithChineseMessage() {
        existingTask();

        assertThatThrownBy(() -> service.updateSchedule(1L, "0 0 3 * *"))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("Cron 表达式非法")
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo("INVALID_CRON");
        verify(scheduler, never()).registerJob(anyLong(), anyString());
    }

    @Test
    @DisplayName("合法 Quartz cron：落库并注册调度")
    void validCronIsSavedAndRegistered() {
        existingTask();

        SyncTaskEntity saved = service.updateSchedule(1L, "0 0/5 * * * ?");

        assertThat(saved.getCronExpression()).isEqualTo("0 0/5 * * * ?");
        verify(scheduler).registerJob(1L, "0 0/5 * * * ?");
    }

    @Test
    @DisplayName("启用且已配置 cron 的任务：创建后立即注册")
    void createWithEnabledRegistersJob() {
        TaskDTO dto = validDto();
        dto.setEnabled(true);
        dto.setCronExpression("0 0/10 * * * ?");

        SyncTaskEntity created = service.create(dto);

        assertThat(created.getId()).isNotNull();
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getStatus()).isEqualTo("ENABLED");
        verify(scheduler).registerJob(created.getId(), "0 0/10 * * * ?");
    }

    @Test
    @DisplayName("新建任务默认停用（与历史行为一致）")
    void createDefaultsToDisabled() {
        SyncTaskEntity created = service.create(validDto());

        assertThat(created.isEnabled()).isFalse();
        assertThat(created.getStatus()).isEqualTo("DISABLED");
        verify(scheduler, never()).registerJob(anyLong(), anyString());
    }

    @Test
    @DisplayName("停用任务会注销调度")
    void disableUnregistersJob() {
        existingTask();

        service.disableTask(1L);

        verify(scheduler).unregisterJob(1L);
    }

    @Test
    @DisplayName("未启用的任务即使有 cron 也不注册（停用即不调度）")
    void disabledTaskIsNotScheduled() {
        SyncTaskEntity task = existingTask();
        task.applyEnabled(false);

        service.updateSchedule(1L, "0 0/5 * * * ?");

        verify(scheduler, never()).registerJob(anyLong(), anyString());
        verify(scheduler).unregisterJob(1L);
    }

    @Test
    @DisplayName("增量模式必须配置增量字段")
    void incrModeRequiresIncrColumn() {
        TaskDTO dto = validDto();
        dto.setSyncMode("INCR");

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("必须配置增量字段")
            .extracting(e -> ((AppException) e).getCode())
            .isEqualTo("INCR_COLUMN_MISSING");
    }

    @Test
    @DisplayName("同步模式非法 → 中文 400")
    void invalidSyncModeIsRejected() {
        TaskDTO dto = validDto();
        dto.setSyncMode("SNAPSHOT");

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("同步模式非法");
    }

    @Test
    @DisplayName("全量策略非法 → 中文 400")
    void invalidFullSyncStrategyIsRejected() {
        TaskDTO dto = validDto();
        dto.setFullSyncStrategy("DROP");

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("全量同步策略非法");
    }

    @Test
    @DisplayName("每批行数不能大于每页行数")
    void batchSizeLargerThanPageSizeIsRejected() {
        TaskDTO dto = validDto();
        dto.setPageSize(100);
        dto.setBatchSize(500);

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("每批行数不能大于每页行数");
    }

    @Test
    @DisplayName("数据源不存在 → 中文 400，而不是外键/500")
    void missingDatasourceIsRejected() {
        when(dsRepo.findById(99L)).thenReturn(Optional.empty());
        TaskDTO dto = validDto();
        dto.setTargetDsId(99L);

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("目标数据源不存在");
    }

    @Test
    @DisplayName("自定义 SQL 非 SELECT → 预检错误码 CUSTOM_SQL_INVALID")
    void dangerousCustomSqlIsRejected() {
        TaskDTO dto = validDto();
        dto.setSourceMode("CUSTOM_SQL");
        dto.setSourceSql("DELETE FROM t_src");

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("SQL");
    }

    @Test
    @DisplayName("错误处理策略含未知字段 → 中文 400")
    void unknownErrorPolicyFieldIsRejected() {
        TaskDTO dto = validDto();
        dto.setErrorPolicy(new ObjectMapper().readTree("{\"maxRetries\":2,\"unknownField\":1}"));

        assertThatThrownBy(() -> service.create(dto))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("未知字段");
    }

    @Test
    @DisplayName("cron 校验工具：合法/非法判定")
    void cronValidator() {
        assertThat(SyncSchedulerService.isValidCron("0 0/5 * * * ?")).isTrue();
        assertThat(SyncSchedulerService.isValidCron("0 0/5 * * *")).isFalse();
        assertThat(SyncSchedulerService.isValidCron("")).isFalse();
        assertThat(SyncSchedulerService.isValidCron(null)).isFalse();
    }
}
