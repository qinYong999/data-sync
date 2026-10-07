package com.datasync.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datasync.core.dialect.Dialects;
import com.datasync.core.dialect.SqlDialect;
import com.datasync.core.engine.ChunkProgress;
import com.datasync.core.engine.DefaultSyncEngine;
import com.datasync.core.engine.RunMetricsListener;
import com.datasync.core.engine.SyncEngine;
import com.datasync.core.jdbc.ColumnMeta;
import com.datasync.core.jdbc.DefaultJdbcMetadataReader;
import com.datasync.core.jdbc.JdbcMetadata;
import com.datasync.core.jdbc.JdbcMetadataReader;
import com.datasync.core.jdbc.KeysetPosition;
import com.datasync.core.jdbc.TableMeta;
import com.datasync.core.jdbc.TableRef;
import com.datasync.core.job.SyncEventBus;
import com.datasync.core.mapper.DefaultValueConverter;
import com.datasync.core.mapper.MySqlToDm8TypeMapper;
import com.datasync.core.mapper.MySqlToMySqlTypeMapper;
import com.datasync.core.mapper.TypeMapper;
import com.datasync.core.mapper.ValueConverter;
import com.datasync.core.model.ErrorPolicy;
import com.datasync.core.model.FieldMapping;
import com.datasync.core.model.FullSyncStrategy;
import com.datasync.core.model.IncrPolicy;
import com.datasync.core.model.PreflightIssue;
import com.datasync.core.model.SyncError;
import com.datasync.core.model.SyncRunResult;
import com.datasync.core.model.SyncTaskConfig;
import com.datasync.core.model.enums.DbType;
import com.datasync.core.model.enums.SyncMode;
import com.datasync.core.preflight.DefaultPreflighter;
import com.datasync.core.preflight.Preflight;
import com.datasync.core.preflight.Preflighter;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 契约一致性自检（冻结契约 §3.1–§3.7）。
 *
 * <p>用反射把契约里的每一个公开类型与签名钉死：任何人不小心改签名，这个测试立刻红。
 * 平台工程师依赖的正是这些符号。
 */
class ContractSignatureTest {

    @Test
    @DisplayName("§3.3 引擎：SyncEngine / DefaultSyncEngine / RunMetricsListener / ChunkProgress")
    void engineApi() throws Exception {
        assertMethod(SyncEngine.class, "run", SyncRunResult.class, SyncTaskConfig.class, DataSource.class,
                DataSource.class, RunMetricsListener.class);
        assertMethod(SyncEngine.class, "cancel", void.class, long.class);
        assertNotNull(DefaultSyncEngine.class.getConstructor());
        assertTrue(SyncEngine.class.isAssignableFrom(DefaultSyncEngine.class));

        assertMethod(RunMetricsListener.class, "onChunk", void.class, ChunkProgress.class);
        assertTrue(RunMetricsListener.class.isAnnotationPresent(FunctionalInterface.class),
                "RunMetricsListener 必须是 @FunctionalInterface");
        long abstractCount = 0;
        for (Method m : RunMetricsListener.class.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers()) && m.getDeclaringClass() == RunMetricsListener.class) {
                abstractCount++;
            }
        }
        assertEquals(1, abstractCount, "只能有一个抽象方法（契约要求）");

        assertMethod(ChunkProgress.class, "readRows", long.class);
        assertMethod(ChunkProgress.class, "writtenRows", long.class);
        assertMethod(ChunkProgress.class, "skippedRows", long.class);
        assertMethod(ChunkProgress.class, "lastKey", String.class);
        assertMethod(ChunkProgress.class, "cursor", String.class);
        assertMethod(ChunkProgress.class, "elapsedMillis", long.class);
    }

    @Test
    @DisplayName("§3.2 预检：Preflighter / DefaultPreflighter / Preflight")
    void preflightApi() throws Exception {
        assertMethod(Preflighter.class, "check", List.class, SyncTaskConfig.class, DataSource.class,
                DataSource.class);
        assertNotNull(DefaultPreflighter.class.getConstructor());
        assertTrue(Preflighter.class.isAssignableFrom(DefaultPreflighter.class));
        assertMethod(Preflight.class, "check", List.class, SyncTaskConfig.class, DataSource.class, DataSource.class);
        assertMethod(Preflight.class, "hasError", boolean.class, List.class);
    }

    @Test
    @DisplayName("§3.4 方言：SqlDialect 全部方法 + Dialects.of")
    void dialectApi() throws Exception {
        assertMethod(SqlDialect.class, "dbType", DbType.class);
        assertMethod(SqlDialect.class, "quote", String.class, String.class);
        assertMethod(SqlDialect.class, "qualifySchema", String.class, String.class, String.class);
        assertMethod(SqlDialect.class, "userName", String.class);
        assertMethod(SqlDialect.class, "buildKeysetSelect", String.class, TableRef.class, List.class,
                List.class, KeysetPosition.class, int.class, List.class);
        assertMethod(SqlDialect.class, "buildWatermarkSelect", String.class, TableRef.class, List.class,
                String.class, String.class, String.class, List.class, KeysetPosition.class, int.class);
        assertMethod(SqlDialect.class, "buildCount", String.class, TableRef.class, List.class);
        assertMethod(SqlDialect.class, "buildTruncate", String.class, TableRef.class);
        assertMethod(SqlDialect.class, "buildDeleteAll", String.class, TableRef.class);
        assertMethod(SqlDialect.class, "buildInsert", String.class, TableRef.class, List.class);
        assertMethod(SqlDialect.class, "buildUpsert", String.class, TableRef.class, List.class, List.class);
        assertMethod(SqlDialect.class, "buildCreateTableLike", String.class, TableRef.class, TableRef.class);
        assertMethod(SqlDialect.class, "buildRenameTable", String.class, TableRef.class, TableRef.class);
        assertMethod(SqlDialect.class, "buildDropTable", String.class, TableRef.class);
        assertMethod(SqlDialect.class, "buildMaxValue", String.class, TableRef.class, String.class);
        assertMethod(SqlDialect.class, "limitClause", String.class, int.class);
        assertMethod(Dialects.class, "of", SqlDialect.class, DbType.class);
    }

    @Test
    @DisplayName("§3.5 JDBC 元数据与值转换")
    void jdbcAndMapperApi() throws Exception {
        assertNotNull(TableRef.class.getMethod("schema"));
        assertNotNull(TableRef.class.getMethod("table"));
        assertNotNull(ColumnMeta.class.getMethod("name"));
        assertNotNull(ColumnMeta.class.getMethod("typeName"));
        assertNotNull(ColumnMeta.class.getMethod("jdbcType"));
        assertNotNull(ColumnMeta.class.getMethod("nullable"));
        assertNotNull(ColumnMeta.class.getMethod("primaryKey"));
        assertNotNull(ColumnMeta.class.getMethod("ordinal"));
        assertNotNull(TableMeta.class.getMethod("table"));
        assertNotNull(TableMeta.class.getMethod("columns"));
        assertNotNull(TableMeta.class.getMethod("primaryKeys"));
        assertNotNull(TableMeta.class.getMethod("find", String.class));
        assertMethod(JdbcMetadataReader.class, "read", TableMeta.class, java.sql.Connection.class, TableRef.class);
        assertNotNull(DefaultJdbcMetadataReader.class.getConstructor());
        assertTrue(JdbcMetadataReader.class.isAssignableFrom(DefaultJdbcMetadataReader.class));
        assertMethod(JdbcMetadata.class, "tableExists", boolean.class, java.sql.Connection.class, TableRef.class);
        assertMethod(JdbcMetadata.class, "listTables", List.class, java.sql.Connection.class);
        assertMethod(JdbcMetadata.class, "read", TableMeta.class, java.sql.Connection.class, TableRef.class);

        assertMethod(ValueConverter.class, "convert", Object.class, Object.class, ColumnMeta.class, ColumnMeta.class);
        assertNotNull(DefaultValueConverter.class.getConstructor());
        assertMethod(TypeMapper.class, "mapTypeName", String.class, String.class);
        assertMethod(TypeMapper.class, "mapValue", Object.class, Object.class, String.class);
        assertMethod(TypeMapper.class, "describe", String.class, String.class);
        assertNotNull(MySqlToDm8TypeMapper.class.getConstructor());
        assertNotNull(MySqlToMySqlTypeMapper.class.getConstructor());
        assertTrue(TypeMapper.class.isAssignableFrom(MySqlToDm8TypeMapper.class));
        assertTrue(TypeMapper.class.isAssignableFrom(MySqlToMySqlTypeMapper.class));
    }

    @Test
    @DisplayName("§3.7 事件总线静态方法")
    void eventBusApi() throws Exception {
        assertMethod(SyncEventBus.class, "publish", void.class, String.class);
        assertMethod(SyncEventBus.class, "subscribe", void.class, java.util.function.Consumer.class);
        assertMethod(SyncEventBus.class, "unsubscribe", void.class, java.util.function.Consumer.class);
        assertTrue(Modifier.isFinal(SyncEventBus.class.getModifiers()));
    }

    @Test
    @DisplayName("§3.1 模型：字段（读写方法）齐全")
    void modelApi() throws Exception {
        // SyncTaskConfig 契约字段
        for (String prop : new String[]{"id", "name", "sourceTable", "targetTable", "syncMode", "incrColumn",
                "cursorValue", "orderColumn", "pageSize", "batchSize", "fieldMappings", "sourceMode",
                "sourceSql", "incrPolicy", "fullSyncStrategy", "errorPolicy", "fetchSize",
                "queryTimeoutSeconds"}) {
            assertAccessors(SyncTaskConfig.class, prop);
        }
        assertAccessors(SyncTaskConfig.class, "dryRun");
        assertEquals(boolean.class, SyncTaskConfig.class.getMethod("isDryRun").getReturnType());
        assertEquals(1000, new SyncTaskConfig().getPageSize());
        assertEquals(500, new SyncTaskConfig().getBatchSize());
        assertEquals(FullSyncStrategy.TRUNCATE, new SyncTaskConfig().getFullSyncStrategy());

        assertAccessors(FieldMapping.class, "sourceColumn");
        assertAccessors(FieldMapping.class, "targetColumn");
        assertAccessors(FieldMapping.class, "defaultValue");
        assertAccessors(FieldMapping.class, "primaryKey");
        assertNotNull(FieldMapping.class.getConstructor());

        assertAccessors(IncrPolicy.class, "safetyLagSeconds");
        assertAccessors(IncrPolicy.class, "lookbackSeconds");
        assertAccessors(IncrPolicy.class, "timestampColumn");
        assertEquals(3, FullSyncStrategy.values().length);

        assertAccessors(ErrorPolicy.class, "maxRetries");
        assertAccessors(ErrorPolicy.class, "retryBackoffMs");
        assertAccessors(ErrorPolicy.class, "skipBadRows");
        assertAccessors(ErrorPolicy.class, "maxSkipRows");
        assertAccessors(ErrorPolicy.class, "maxErrorsRecorded");
        assertEquals(3, new ErrorPolicy().getMaxRetries());
        assertEquals(2000L, new ErrorPolicy().getRetryBackoffMs());
        assertEquals(100L, new ErrorPolicy().getMaxSkipRows());
        assertEquals(200, new ErrorPolicy().getMaxErrorsRecorded());

        for (String prop : new String[]{"success", "status", "readRows", "writtenRows", "skippedRows",
                "readMillis", "writeMillis", "totalMillis", "startCursor", "endCursor", "lastCommittedKey",
                "errorMessage", "errors", "preflightIssues"}) {
            assertAccessors(SyncRunResult.class, prop);
        }
        for (String prop : new String[]{"phase", "rowKey", "message", "rowData", "retryable"}) {
            assertAccessors(SyncError.class, prop);
        }
        assertEquals(4000, SyncError.MAX_ROW_DATA_LENGTH);
        for (String prop : new String[]{"level", "code", "message", "hint"}) {
            assertAccessors(PreflightIssue.class, prop);
        }
        assertEquals(2, PreflightIssue.Level.values().length);
        assertEquals(2, DbType.values().length);
        assertEquals(3, SyncMode.values().length);
        assertEquals(DbType.MYSQL, DbType.valueOf("MYSQL"));
        assertEquals(DbType.DM8, DbType.valueOf("DM8"));
        assertEquals(SyncMode.FULL_INCR, SyncMode.valueOf("FULL_INCR"));
    }

    // ------------------------------------------------------------------

    private static void assertMethod(Class<?> owner, String name, Class<?> returnType, Class<?>... params) {
        try {
            Method m = owner.getMethod(name, params);
            assertEquals(returnType, m.getReturnType(), owner.getSimpleName() + "#" + name + " 返回类型不一致");
        } catch (NoSuchMethodException e) {
            throw new AssertionError("契约要求的方法不存在: " + owner.getName() + "#" + name, e);
        }
    }

    private static void assertAccessors(Class<?> owner, String property) {
        String cap = Character.toUpperCase(property.charAt(0)) + property.substring(1);
        Method getter = null;
        for (String name : new String[]{"get" + cap, "is" + cap}) {
            try {
                getter = owner.getMethod(name);
                break;
            } catch (NoSuchMethodException ignore) {
                // 试下一个前缀
            }
        }
        assertNotNull(getter, owner.getSimpleName() + " 缺少 " + property + " 的读取方法");
        try {
            owner.getMethod("set" + cap, getter.getReturnType());
        } catch (NoSuchMethodException e) {
            throw new AssertionError(owner.getSimpleName() + " 缺少 " + property + " 的写入方法 set" + cap, e);
        }
    }
}
