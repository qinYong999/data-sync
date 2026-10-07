package com.datasync.server.service;

import com.datasync.server.log.RunLog;
import com.datasync.server.repository.SyncRecordRepository;
import com.datasync.server.repository.SyncTaskRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code GET /api/system/info} 的数据来源（契约 §4.3）。
 *
 * <p>{@code dm8Verified} 恒为 false：达梦 DM8 方言实现结构完整，但本机无实例与驱动，
 * 未在真实达梦上验证过（D13）——虚假的支持声明比不支持更危险。</p>
 */
@Service
public class SystemInfoService {

    private static final Logger log = LoggerFactory.getLogger(SystemInfoService.class);

    private static final String DEFAULT_VERSION = "1.0.0-SNAPSHOT";

    private final DataSource metadataDataSource;
    private final SyncTaskRepository taskRepo;
    private final SyncRecordRepository recordRepo;
    private final TaskRunService taskRunService;
    private final ConnectionPoolRegistry poolRegistry;

    private final LocalDateTime startTime = LocalDateTime.now();
    private volatile String cachedDbType;

    public SystemInfoService(DataSource metadataDataSource, SyncTaskRepository taskRepo,
                             SyncRecordRepository recordRepo, TaskRunService taskRunService,
                             ConnectionPoolRegistry poolRegistry) {
        this.metadataDataSource = metadataDataSource;
        this.taskRepo = taskRepo;
        this.recordRepo = recordRepo;
        this.taskRunService = taskRunService;
        this.poolRegistry = poolRegistry;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("version", version());
        info.put("dbType", metadataDbType());
        info.put("dm8Verified", false);
        info.put("activeTasks", safeCount(taskRepo::countByEnabledTrue));
        info.put("runningTasks", taskRunService.runningCount());
        info.put("totalTasks", safeCount(taskRepo::count));
        info.put("totalRecords", safeCount(recordRepo::count));
        info.put("cachedPools", poolRegistry.cachedPoolCount());
        info.put("poolSummary", poolRegistry.snapshot());
        info.put("runningTaskIds", List.copyOf(taskRunService.runningTaskIds()));
        // ------------------------------------------------------------------
        // D1/D8 观测口径（先读这段再解读数字）：
        //  · websocketDroppedMessages —— 判断"实时日志是否在丢"看这一个。它由真实外部条件触发
        //    （浏览器页面挂着不读 socket），QA 实测可达 1.2 万条。
        //  · logPeakQueueDepth / logQueueDepth —— 总线到 WebSocket 之间的背压指示，永远有信息量。
        //  · logDroppedMessages —— 正常负载下**恒为 0 是预期行为**：投递到该队列的生产者已被上游
        //    AsyncRunMetricsListener（64 容量、丢最旧）节流，队列几乎不可能被填满；
        //    因此它不用于判断"是否在丢"，只在极端背压时给出信号。
        // ------------------------------------------------------------------
        info.put("logDroppedMessages", RunLog.droppedMessages());
        info.put("logQueueDepth", RunLog.queueDepth());
        info.put("logPeakQueueDepth", RunLog.peakQueueDepth());
        info.put("startTime", startTime.toString());
        info.put("javaVersion", System.getProperty("java.version"));
        return info;
    }

    private String version() {
        String implVersion = getClass().getPackage() == null ? null : getClass().getPackage().getImplementationVersion();
        return implVersion == null || implVersion.isBlank() ? DEFAULT_VERSION : implVersion;
    }

    /** 元数据库类型（不是被同步的业务库类型） */
    private String metadataDbType() {
        if (cachedDbType != null) {
            return cachedDbType;
        }
        try (Connection connection = metadataDataSource.getConnection()) {
            cachedDbType = connection.getMetaData().getDatabaseProductName();
        } catch (Exception e) {
            log.debug("读取元数据库类型失败：{}", e.getMessage());
            cachedDbType = "UNKNOWN";
        }
        return cachedDbType;
    }

    private long safeCount(java.util.function.LongSupplier supplier) {
        try {
            return supplier.getAsLong();
        } catch (Exception e) {
            log.debug("统计失败：{}", e.getMessage());
            return 0L;
        }
    }
}
