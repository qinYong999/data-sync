package com.datasync.server;

import com.datasync.core.model.SyncError;
import com.datasync.server.entity.SyncRecordEntity;
import com.datasync.server.service.RunRecordStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D11 的数据库级保障 + 启动自愈（水位不得推进）。
 *
 * <p>本类使用任务 ID 9101（见基类的隔离约定），与其它测试类不共享任何任务/记录。</p>
 */
class RunRecordStoreIntegrationTest extends AbstractServerIntegrationTest {

    private static final long TASK_ID = 9101L;

    @Autowired
    private RunRecordStore recordStore;

    @BeforeEach
    void prepareData() {
        createCommonDatasources();
        ensureTable("t_src_9101");
        ensureTable("t_dst_9101");
        createTask(TASK_ID, "记录测试任务", "t_src_9101", "t_dst_9101", "FULL", null, null);
    }

    @Test
    @DisplayName("run_key 唯一索引挡住同一任务的第二个运行实例（多实例部署也安全）")
    void runKeyUniqueIndexBlocksSecondRun() {
        SyncRecordEntity first = recordStore.tryStartRun(TASK_ID, "MANUAL");
        SyncRecordEntity second = recordStore.tryStartRun(TASK_ID, "MANUAL");

        assertThat(first).isNotNull();
        assertThat(first.getRunKey()).isEqualTo("RUNNING-" + TASK_ID);
        assertThat(second).as("唯一索引冲突时必须返回 null（触发方据此给出中文提示）").isNull();
        Long count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM sync_record WHERE task_id = ? AND status = 'RUNNING'", Long.class, TASK_ID);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName("结束执行后 run_key 改写为 {taskId}-{recordId}，令牌释放、可再次触发")
    void finishRunReleasesToken() {
        SyncRecordEntity first = recordStore.tryStartRun(TASK_ID, "MANUAL");
        recordStore.finishRun(first.getId(), SyncRecordEntity.STATUS_COMPLETED, null, null);

        assertThat(runKeyOf(first.getId())).isEqualTo(TASK_ID + "-" + first.getId());
        assertThat(recordStore.tryStartRun(TASK_ID, "MANUAL")).as("令牌释放后应能再次开始").isNotNull();
    }

    @Test
    @DisplayName("残留 RUNNING → FAILED + 释放 run_key，且任务水位未被推进")
    void orphanRunningRecordIsRecoveredWithoutAdvancingCursor() {
        createTask(TASK_ID, "自愈测试任务", "t_src_9101", "t_dst_9101", "INCR", "id", "500");
        jdbcTemplate.update(
            "INSERT INTO sync_record (task_id, run_key, start_time, status, read_rows, write_rows, skipped_rows, "
                + "error_rows, read_millis, write_millis, total_millis, trigger_type) "
                + "VALUES (?, ?, NOW(), 'RUNNING', 10, 10, 0, 0, 0, 0, 0, 'MANUAL')",
            TASK_ID, "RUNNING-" + TASK_ID);
        Long recordId = jdbcTemplate.queryForObject(
            "SELECT id FROM sync_record WHERE task_id = ? AND status = 'RUNNING'", Long.class, TASK_ID);

        int recovered = recordStore.recoverOrphanRuns();

        assertThat(recovered).isGreaterThanOrEqualTo(1);
        assertThat(recordStatus(recordId)).isEqualTo(SyncRecordEntity.STATUS_FAILED);
        List<String> messages = jdbcTemplate.queryForList(
            "SELECT error_message FROM sync_record WHERE id = ?", String.class, recordId);
        assertThat(messages.get(0)).contains("进程异常退出");
        assertThat(runKeyOf(recordId)).as("必须释放互斥令牌，否则该任务永久无法触发")
            .isEqualTo(TASK_ID + "-" + recordId);
        assertThat(cursorOf(TASK_ID)).as("自愈绝不能推进水位").isEqualTo("500");
        assertThat(recordStore.tryStartRun(TASK_ID, "MANUAL")).isNotNull();
    }

    @Test
    @DisplayName("保存坏行明细：超长字段被截断到列长度，不会因超长写入失败")
    void saveErrorsTruncatesLongValues() {
        SyncRecordEntity record = recordStore.tryStartRun(TASK_ID, "MANUAL");

        SyncError error = new SyncError();
        error.setPhase("WRITE");
        error.setRowKey("1".repeat(400));
        error.setMessage("错".repeat(2000));
        error.setRowData("x".repeat(6000));
        error.setRetryable(true);

        int saved = recordStore.saveErrors(record.getId(), TASK_ID, List.of(error));

        assertThat(saved).isEqualTo(1);
        String rowKey = jdbcTemplate.queryForObject(
            "SELECT row_key FROM sync_error WHERE record_id = ?", String.class, record.getId());
        assertThat(rowKey).hasSize(255);
    }
}
