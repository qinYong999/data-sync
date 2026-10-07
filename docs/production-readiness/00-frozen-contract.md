# DataSync 生产化改造 — 冻结契约（FROZEN）

> 本文件由 Lead 在开工前冻结。任何成员**不得单方面修改**其中的类型签名、模块边界、目录归属；
> 如需变更，必须由 Lead 写入本文件并通知全部成员。
> 冻结时间：阶段一开始前。

---

## 0. 环境与命令（全体必读，不要自行摸索）

| 项 | 值 |
|---|---|
| 工作区 | `D:\AI\DeepSeek Harness\data-sync` |
| JDK | `S:\Program Files\Java\jdk-25`（仅此一个，无 JDK 21） |
| Maven | `S:\installationFree\apache-maven-3.9.15\bin\mvn.cmd` |
| 本地仓库 | `D:\AI\DeepSeek Harness\jc\project\.m2repo`（**可写**，已预置依赖） |
| Maven settings | `D:\AI\DeepSeek Harness\jc\project\.dsh-probe\settings.xml` |
| MySQL | `127.0.0.1:3306`，`root/123456`，服务名 MySQL80，客户端 `D:\Program Files\MySQL\MySQL Server 8.0\bin\mysql.exe` |
| Node | `S:\Program Files\nodejs\node.exe` v24，npm 可用 |
| Shell | Windows PowerShell 5.1（**无 pwsh**） |

**唯一对外构建命令**（在仓库根执行）：

```powershell
& "S:\installationFree\apache-maven-3.9.15\bin\mvn.cmd" -B -s "D:\AI\DeepSeek Harness\jc\project\.dsh-probe\settings.xml" -DskipTests package
```

- 需要跑测试时把 `-DskipTests` 换成 `-Dtest=<类名> -DfailIfNoTests=false`，或跑全局测试。
- **不要**执行 `mvn install`（会把半成品装进共享仓库，污染其他人）。
- **不要**在 PowerShell 里 `mvn ... | Select-String` 判读中文输出（控制台是 GBK，中文会乱码）。要看中文日志请重定向到文件后用 read 工具读。
- 每个成员可独占创建自己的测试库：`datasync_test_<成员名>`，用完可留着。

**编码铁律**：仓库内所有源文件 UTF-8 无 BOM；Java 源码注释用中文（项目既有约定）；标识符用英文。

---

## 1. 冻结的工程决策（权威，不得争辩）

| # | 决策 | 理由 |
|---|---|---|
| D1 | **移除 Spring Batch**（core 与 server 都不再依赖）。同步由自研流式引擎执行。 | 我们需要完全掌控游标、事务边界、断点、幂等与对账；Spring Batch 的 JobRepository 只带来元数据表膨胀与 Job 实例爆炸 |
| D2 | 全量分页一律**键集分页**（keyset/cursor paging），禁止 `LIMIT/OFFSET` 分页。 | OFFSET 在源库并发写入时必然重复/漏行 |
| D3 | 增量同步采用**水位 + 安全滞后(safetyLag) + 回看窗口(lookback)**；读范围上界为「执行时刻锚点」，**游标只在整个任务全部 chunk 成功后推进一次**。 | 修掉"读失败也推进 `incrValue`"导致的永久丢数；重放靠幂等 upsert 保证安全 |
| D4 | 目标写入一律**幂等 upsert**（MySQL `INSERT ... ON DUPLICATE KEY UPDATE` / DM8 `MERGE INTO`），主键从 JDBC 元数据推断。 | 断点续传、重复执行、并发写都安全 |
| D5 | **预检（preflight）强制执行**：源表/目标表存在、列名与类型兼容、增量字段可排序且建议有索引、目标具备主键或唯一键。预检失败不写任何数据。 | 原设计第 4.1 节要求，但实现完全缺失 |
| D6 | 全量同步默认策略 `TRUNCATE`（与现状一致），可选 `DELETE`、`SWAP`（暂存表 + RENAME）。 | 默认可预期、易运维；SWAP 留给要求"同步期间目标可读"的场景 |
| D7 | 元数据库**只支持 MySQL 8.0**，Schema 由 **Flyway** 版本化迁移管理，JPA 设 `ddl-auto=validate`。生产 profile 不允许 H2。 | 元数据是生产的根，不能靠 Hibernate 猜表结构 |
| D8 | 失败行落库到 `sync_error` 表；错误分**可重试(retryable)/致命(fatal)**；重试次数、间隔、跳过阈值可配。 | 原设计第 5 节要求，实现完全缺失 |
| D9 | 数据源口令**AES-GCM 加密落库**，密钥来自环境变量/配置；接口响应一律脱敏；日志与异常消息不得出现明文口令。 | 现状明文存储 + 明文日志 |
| D10 | 服务端启用 **Spring Security 表单登录 + CSRF**，全部 `/api/**` 需认证；`/actuator/health` 匿名可读。 | 现状所有接口裸奔 |
| D11 | 并发控制：**每任务最多 1 个运行中实例**（DB 级唯一约束 + 应用级锁双重保障），全局并发度用**有界线程池**限制。 | 现状用 `ForkJoinPool.commonPool` 无界并发，同一任务可自我重叠 |
| D12 | 每任务使用**持久化的目标连接池**（缓存 + 空闲回收），不再每次执行新建/销毁 Hikari 池。 | 现状每次执行重建连接池，定时任务高频下是连接风暴 |
| D13 | **达梦 DM8 标注为「未验证」**：方言实现结构完整，但无本机实例与驱动，不得声称已验证。生产主线是 **MySQL → MySQL**。 | 虚假的支持声明比不支持更危险 |
| D14 | 全量同步**不清空**目标表除非预检通过且策略已确定；增量/幂等模式下绝不 TRUNCATE。 | 现状 `FULL_INCR` 首次也 TRUNCATE，语义错误 |

---

## 2. 模块与目录归属（写作用域，互不越界）

```
data-sync/
├── pom.xml                                  [Lead]
├── Dockerfile / docker-compose.yml          [Lead]
├── .gitignore / README.md                   [Lead]
├── docs/production-readiness/               [架构分析师 + Lead]
├── data-sync-core/                          [内核工程师]  ← 全权
│   ├── pom.xml                              [Lead 审, 内核工程师改]
│   └── src/{main,test}/java/com/datasync/core/**
├── data-sync-server/                        [平台工程师]  ← 全权
│   ├── pom.xml                              [Lead 审, 平台工程师改]
│   └── src/{main,test}/java/com/datasync/server/**
│   └── src/main/resources/**                 [Lead: application*.yml / db/migration]
└── data-sync-web/                           [界面工程师]  ← 全权
```

**越界禁令**：内核工程师不碰 `data-sync-server/**`；平台工程师不碰 `data-sync-core/**`；
界面工程师不碰后端；任何人都不要动 `pom.xml`（根 POM）——需要改依赖时**发消息给 Lead**。

---

## 3. 冻结的核心公共 API（`data-sync-core`）

包根：`com.datasync.core`。以下签名是实现与调用双方共同的合同，**签名一字不改**。

### 3.1 模型与枚举（`com.datasync.core.model`）

```java
public enum DbType { MYSQL, DM8 }                       // 保留既有包 com.datasync.core.model.enums

public final class FieldMapping {                       // 保留既有类，字段不变
    private String sourceColumn;
    private String targetColumn;
    private String defaultValue;   // 可空
    private boolean primaryKey;    // 是否参与 upsert 匹配键（用户可覆盖自动推断）
    // getter/setter + 无参构造
}

public final class SyncTaskConfig {                     // 保留既有类，新增下列属性（用 getter/setter）
    private Long   id;
    private String name;
    private String sourceTable;
    private String targetTable;
    private SyncMode syncMode;            // FULL / INCR / FULL_INCR
    private String incrColumn;            // 增量字段（单调：自增数值 或 时间戳）
    private String cursorValue;           // 上次成功水位（由平台从 DB 读出后传入，null = 首次）
    private String orderColumn;           // 键集分页排序列；null 时自动推断
    private Integer pageSize   = 1000;    // 单次抓取行数（内存 chunk）
    private Integer batchSize  = 500;     // JDBC 批量提交行数（<= pageSize）
    private List<FieldMapping> fieldMappings;   // 空/ null = 按目标表列名同名映射
    private String  sourceMode;           // "TABLE" | "CUSTOM_SQL"
    private String  sourceSql;            // sourceMode=CUSTOM_SQL 时使用
    private IncrPolicy incrPolicy = new IncrPolicy();
    private FullSyncStrategy fullSyncStrategy = FullSyncStrategy.TRUNCATE;
    private ErrorPolicy errorPolicy = new ErrorPolicy();
    private int  fetchSize = 1000;
    private Long queryTimeoutSeconds = 0L;
    private boolean dryRun = false;
}

public final class IncrPolicy {
    private long safetyLagSeconds = 0L;    // 上界 = 执行锚点 - safetyLag；数值型自增列默认 0
    private long lookbackSeconds = 0L;     // 下界 = 水位 - lookback（时间戳列建议 ≥ 60）
    private boolean timestampColumn = false; // 由预检结果回填
    // getter/setter
}

public enum FullSyncStrategy { TRUNCATE, DELETE, SWAP }

public final class ErrorPolicy {
    private int maxRetries = 3;            // 单个 chunk 的重试次数
    private long retryBackoffMs = 2000L;
    private boolean skipBadRows = false;   // true = 坏行跳过并落 sync_error；false = 快速失败
    private long maxSkipRows = 100L;       // 超过则整体失败
    private int maxErrorsRecorded = 200;   // 落库的坏行明细上限
}

public final class SyncRunResult {
    private boolean success;
    private String  status;        // COMPLETED / FAILED / CANCELLED
    private long    readRows;
    private long    writtenRows;
    private long    skippedRows;
    private long    readMillis;
    private long    writeMillis;
    private long    totalMillis;
    private String  startCursor;   // 本次生效的读下界（字符串形式，可空）
    private String  endCursor;     // 成功时的新水位（可空；!= null 才允许推进 incrValue）
    private String  lastCommittedKey; // 最后提交行的 order key，用于断点续跑（可空）
    private String  errorMessage;
    private List<SyncError> errors; // 上限 maxErrorsRecorded
    private List<PreflightIssue> preflightIssues;
}

public final class SyncError {
    private String phase;        // PREFLIGHT / READ / MAP / WRITE
    private String rowKey;       // 主键值（尽力而为，可空）
    private String message;
    private String rowData;      // 源行 JSON，截断到 4000 字符
    private boolean retryable;
}

public final class PreflightIssue {
    public enum Level { WARN, ERROR }
    private Level level;
    private String code;     // 稳定错误码，见 §3.6
    private String message;  // 中文，面向运维
    private String hint;     // 可执行的修复建议（中文）
}
```

### 3.2 预检（`com.datasync.core.preflight`）

```java
public interface Preflighter {
    List<PreflightIssue> check(SyncTaskConfig cfg, DataSource src, DataSource dst);
}
public final class DefaultPreflighter implements Preflighter {
    public DefaultPreflighter() { ... }                    // 不做元数据库防护检查
    public DefaultPreflighter(MetadataGuard guard) { ... }  // 平台侧注入元数据库指纹后使用
}
/** 便捷入口。签名不变：平台与前端都依赖它。元数据库防护只在带 guard 的实例上生效。 */
public final class Preflight {
    public static List<PreflightIssue> check(SyncTaskConfig cfg, DataSource src, DataSource dst);
    public static boolean hasError(List<PreflightIssue> issues);
}
/**
 * 元数据库防护（防止把平台自身的元数据表当成同步目标，一次 FULL 抹掉全平台配置）。
 * 不依赖 Spring：metadataJdbcUrl 由平台侧（唯一知道自己元数据库在哪的一层）传入，null = 跳过检查。
 */
public final class MetadataGuard {
    public static MetadataGuard of(String metadataJdbcUrl);
    public void check(DataSource target, List<PreflightIssue> issues);
}
/** 稳定错误码常量集中处。前端只依赖 code 字符串，不依赖 Java 常量。 */
public final class ErrorCodes {
    public static final String SRC_TABLE_MISSING = "SRC_TABLE_MISSING";
    // ... 与 §3.6 表格一一对应
}
```

### 3.3 引擎（`com.datasync.core.engine`）

```java
public interface SyncEngine {
    SyncRunResult run(SyncTaskConfig cfg, DataSource src, DataSource dst, RunMetricsListener listener);
    /** 协作式取消：置位标志，正在运行的 run() 尽快返回 CANCELLED */
    void cancel(long taskId);
}

public final class DefaultSyncEngine implements SyncEngine {
    public DefaultSyncEngine();
}

@FunctionalInterface
public interface RunMetricsListener {
    /**
     * 进度回调。**签名不变；语义在 2026-10 修订**（见下方决策 A3）。
     */
    void onChunk(ChunkProgress progress);
}

/** 把任意 listener 包装成"单线程 + 有界队列、丢弃中间态、结束时可 flush"的线程安全实现。
 *  用途：调用方拿不准自己的回调会不会阻塞（WebSocket、外部埋点、未来的 Kafka）时套一层。 */
public final class AsyncRunMetricsListener implements RunMetricsListener, AutoCloseable {
    public static AsyncRunMetricsListener wrapping(RunMetricsListener delegate, int queueCapacity);
    @Override public void onChunk(ChunkProgress progress);   // 永不阻塞调用方
    public void flushAndClose(long timeoutMillis);           // run() 返回前由调用方调用
    public long droppedCallbacks();                          // 让观测者知道自己看到的不是全量
    @Override public void close();
}

public final class ChunkProgress {
    // getter only
    public long   readRows();     // 累计绝对值（不是增量）
    public long   writtenRows();  // 累计绝对值
    public long   skippedRows();  // 累计绝对值
    public String lastKey();      // 最近提交行的排序键（**诊断用，不参与恢复**，见下方决策 A2）
    public String cursor();       // 最近提交行的增量字段值（可空，**诊断用，不参与恢复**）
    public long   elapsedMillis();
}
```

**决策 A3（回调语义，2026-10 冻结补充，权威）**

`onChunk` 的语义**从"每个 chunk 必被调用一次"修订为**：

> **进度是累计绝对值，回调是尽力而为的采样。**
> 实现方**可以合并或丢弃中间回调**；只有两条硬保证：
> 1. `run()` 返回前，**最后一次进度必须已经投递**；
> 2. `SyncRunResult` 里的最终统计**永远准确**（不依赖回调）。
>
> 回调方的实现**必须非阻塞**：不得在回调里做网络 I/O、锁等待或长事务。

**为什么改**：实测发现被同步调用的 `onChunk`（→ `SyncEventBus` → WebSocket `sendMessage`，
外层还套着 `ConcurrentWebSocketSessionDecorator`，其语义恰恰是"必要时阻塞调用方"）会让
**一个挂着的慢客户端把数据同步拖慢 30 倍**（26 ms/chunk → 716 ms/chunk）。
既然 `ChunkProgress` 的三个计数本来就是累计值，丢掉中间态不丢信息；
而"每 chunk 必调一次"这条承诺恰恰会诱使实现方把重活放进回调。

**责任划分（两边都要做，缺一不可）**：
- **调用方**（`data-sync-server`）：回调必须真正非阻塞——有界队列丢弃中间态，WebSocket 发送移出引擎线程；
- **引擎**（`data-sync-core`）：提供 `AsyncRunMetricsListener` 作纵深防御，让**任何**慢回调都拖不住主循环。
  默认路径保持同步调用：**契约靠 javadoc 界定语义，代码靠这个包装器兜底**。

**决策 A2（水位恢复语义，2026-10 冻结补充，权威）**

| 字段 | 是否推进水位 | 用途 |
|---|---|---|
| `SyncRunResult.endCursor` | **是**（唯一） | 本次成功提交的最后一行的增量值；整个任务成功后平台才写回 `sync_task.cursor_value`。**绝不允许**用 `SELECT MAX(...)` |
| `ChunkProgress.cursor()` / `lastKey()`、`SyncRunResult.lastCommittedKey` | 否 | 纯进度可观测：平台可写入 `sync_record` 作为诊断，让运维看到 5 小时大表任务跑到哪；**下次执行绝不从它恢复** |

- 任何失败 / 取消 / 进程被杀：**下次一律从上次成功的水位重跑**，靠幂等 upsert 保证重放安全。
- **不实现** chunk 级水位持久化，**不引入** `sync_watermark` 表（该方案会让吞吐塌掉并把水位正确性分散到 N 个事务里）。
- 全量同步（FULL）没有断点续传语义，中断即重跑。

**决策 A1（排序键唯一性，2026-10 冻结补充，权威）**

键集分页的 `keyColumns` 必须能**唯一确定一行**，否则同值行会被整批跳过（与 OFFSET 漏行同量级的静默丢数）。解析规则按序：

1. 起点：`cfg.orderColumn`（若配置且列存在）→ 否则 `incrColumn` → 否则源表主键列。
2. 末位追加 **tie-breaker**：候选项不唯一或非主键时，把主键列中尚未出现的列按元数据顺序追加到末尾。
3. 源表无任何主键/唯一键、且候选项本身不唯一 → `ORDER_KEY_NOT_UNIQUE`（ERROR），**禁止**退化为 `LIMIT/OFFSET`。
4. 当 `[incrColumn]` 不唯一时，水位比较必须**同时覆盖多列**，且 `cursorValue`/`endCursor`/`startCursor` 三个字符串字段要能承载复合游标（序列化格式由内核自定，但必须能被 `SqlDialect` 正确还原成比较谓词）。

   > ⚠️ **谓词形态（2026-10 决策 A4 修正，此前的"元组比较"写法已废弃）**
   >
   > 早期写的是 SQL 标准的**行构造器** `` (incr, pk) > (?, ?) ``。实测（MySQL 8.0.46，`resume_src` 100 万行、`idx_upd(updated_at,id)`）：
   > **优化器不会把行构造器比较下推成索引范围访问**，只按 `updated_at <= 上界` 建范围，再逐条扫索引项过滤——
   > `key_len=8`（只用第 1 列）、`Handler_read_next=472,999`、服务端 **~600 ms/页**；
   > 展开式则是 `key_len=16`（两列都进范围）、`Handler_read_next=999`、**~3 ms/页**（约 200 倍；
   > 更紧的上界下实测 170–210 ms vs 0.02 ms，约 8000 倍）。
   >
   > **必须写成展开式 OR**：
   > ```sql
   > (k1 > ?) OR (k1 = ? AND k2 > ?) OR (k1 = ? AND k2 = ? AND k3 > ?)
   > ```
   > 注意占位符是 **`n(n+1)/2` 个**（游标只有 n 个值），参数按**前缀重复**展开绑定：
   > `[v1] + [v1,v2] + [v1,v2,v3]`。谓词生成与参数展开必须是**同一对方法**（`buildKeysetPredicate` +
   > `buildKeysetParameters`），否则会像多行 VALUES 那样出现"参数错位却不报错"的静默错误。
   >
   > 该形态由 `SqlDialect` 的默认实现统一提供，**MySQL 与 DM8 共用一份**（达梦的可下推性更没有保证）。
   > 回归测试：`DefaultSyncEngineMySqlTest#keysetDeepCursorDoesNotScanWholeTable`
   > —— 深浅游标每页扫描行数都必须是 `≈pageSize` 量级，**改回行构造器立刻变红**。

### 3.4 方言（`com.datasync.core.dialect`）

```java
public interface SqlDialect {
    DbType dbType();
    String quote(String identifier);
    String qualifySchema(String schema, String table);   // schema 可空
    String userName();
    /** 键集分页查询：WHERE 条件由 keyset 生成。 */
    String buildKeysetSelect(TableRef table, List<String> selectColumns,
                             List<String> keyColumns, KeysetPosition after, int pageSize, List<String> extraPredicates);
    /** 水位区间查询（增量）：cursor > lower AND cursor <= upper */
    String buildWatermarkSelect(TableRef table, List<String> selectColumns, String cursorColumn,
                                String lowerExclusive, String upperInclusive, List<String> keyColumns,
                                KeysetPosition after, int pageSize);
    String buildCount(TableRef table, List<String> extraPredicates);
    String buildTruncate(TableRef table);
    String buildDeleteAll(TableRef table);
    String buildInsert(TableRef table, List<String> columns);
    String buildUpsert(TableRef table, List<String> columns, List<String> keyColumns);
    String buildCreateTableLike(TableRef source, TableRef newTable);  // SWAP 策略用，DM8 可抛 UnsupportedOperationException
    String buildRenameTable(TableRef from, TableRef to);
    String buildDropTable(TableRef table);
    String buildMaxValue(TableRef table, String column);
    /** 分页/限量语法差异：MySQL LIMIT n，DM8 由实现自行处理 */
    String limitClause(int pageSize);
}
public final class Dialects {
    public static SqlDialect of(DbType type);       // 未注册类型抛 IllegalArgumentException，消息中文
}
```

> `TableRef` 与 `KeysetPosition` 均定义在 `com.datasync.core.jdbc`（见 §3.5），`dialect` 包依赖 `jdbc` 包，
> 反过来 `jdbc` 包**不得**引用 `dialect` 包（避免循环依赖）。

### 3.5 JDBC 元数据与值转换（`com.datasync.core.jdbc`, `com.datasync.core.mapper`）

```java
package com.datasync.core.jdbc;
public final class TableRef { /* 见 3.4 —— 若两处冲突，以本处为准：TableRef 归 jdbc 包，dialect 依赖它 */ }
public final class ColumnMeta {
    public String name(); public String typeName(); public int jdbcType();
    public boolean nullable(); public boolean primaryKey(); public int ordinal();
}
public final class TableMeta {
    public TableRef table(); public List<ColumnMeta> columns();
    public List<ColumnMeta> primaryKeys(); public ColumnMeta find(String nameIgnoringCase);
}
public interface JdbcMetadataReader { TableMeta read(Connection c, TableRef table); }
public final class DefaultJdbcMetadataReader implements JdbcMetadataReader { ... }
public final class JdbcMetadata {
    public static boolean tableExists(Connection c, TableRef t);
    public static List<TableRef> listTables(Connection c);
    public static TableMeta read(Connection c, TableRef t);
}

package com.datasync.core.mapper;
public interface ValueConverter { Object convert(Object source, ColumnMeta sourceMeta, ColumnMeta targetMeta); }
public final class DefaultValueConverter implements ValueConverter { ... }   // 处理 JSR-310、byte[]、BitSet、BigDecimal 等
public interface TypeMapper {                                                 // 保留既有接口名，新增方法
    String mapTypeName(String sourceTypeName);
    Object mapValue(Object sourceValue, String targetTypeName);
    String describe(String sourceTypeName);
}
public final class MySqlToDm8TypeMapper implements TypeMapper { ... }         // 保留既有类
public final class MySqlToMySqlTypeMapper implements TypeMapper { ... }       // 新增
```

### 3.6 稳定错误码（写进 `PreflightIssue.code`，前端按码出修复建议）

**基础码（11 个）**

| code | 级别 | 含义 |
|---|---|---|
| `SRC_TABLE_MISSING` | ERROR | 源表不存在 |
| `DST_TABLE_MISSING` | ERROR | 目标表不存在 |
| `NO_TARGET_COLUMNS` | ERROR | 目标表无可用列 / 字段映射为空 |
| `MAPPING_COLUMN_MISSING` | ERROR | 字段映射引用了源或目标不存在的列 |
| `TYPE_INCOMPATIBLE` | ERROR | 源列类型无法安全转换到目标列类型 |
| `PK_MISSING` | ERROR | 目标表无主键/唯一键，无法保证幂等 upsert（FULL+TRUNCATE 策略下可降级为 WARN） |
| `INCR_COLUMN_MISSING` | ERROR | 配置的增量字段在源表不存在 |
| `INCR_COLUMN_NOT_SORTABLE` | ERROR | 增量字段类型不可用于有序水位（非数值/时间） |
| `INCR_COLUMN_NO_INDEX` | WARN | 增量字段无索引 |
| `CUSTOM_SQL_INVALID` | ERROR | 自定义 SQL 非法（非 SELECT / 危险关键字 / 语法错误） |
| `PERMISSION_DENIED` | ERROR | 目标库缺少 TRUNCATE/CREATE/DROP 等所需权限 |

**扩展码（5 个，2026-10 冻结补充）**

| code | 级别 | 含义 |
|---|---|---|
| `IDENTIFIER_INVALID` | ERROR | 表名/列名含非法字符（SQL 注入防护；只允许 `[A-Za-z0-9_$\u4e00-\u9fa5.]`） |
| `SORT_KEY_MISSING` | ERROR | 源表无任何可用排序键，无法做键集分页 |
| `ORDER_KEY_NOT_UNIQUE` | ERROR | 排序键无法唯一确定一行（见决策 A1），**禁止**退化分页 |
| `STRATEGY_UNSUPPORTED` | ERROR | 目标库不支持所选全量策略（如 DM8 不支持 SWAP 暂存表） |
| `TARGET_IS_METADATA` | ERROR | 目标指向平台自身的元数据库/元数据表，一次 FULL 会抹掉平台配置（见 `MetadataGuard`） |

前端必须对**未知 code 做兜底展示**（直接显示 `message` + `hint`），不得因为出现新 code 而白屏。

### 3.7 事件总线（保留既有类，弱化为可选）

```java
package com.datasync.core.job;
public final class SyncEventBus {          // 保留，供实时日志复用；server 负责注册消费者
    public static void publish(String message);
    public static void subscribe(java.util.function.Consumer<String> consumer);
    public static void unsubscribe(java.util.function.Consumer<String> consumer);
}
```

---

## 4. 冻结的服务端契约（`data-sync-server`）

### 4.1 关键 Bean 与职责

| Bean | 职责 | 归属 |
|---|---|---|
| `SyncEngine`（来自 core） | 执行单次同步 | core |
| `DataSourceService` | 数据源 CRUD、口令加解密、连接池注册表 | 平台 |
| `ConnectionPoolRegistry` | 按数据源 ID 缓存 Hikari 池，配置变更时失效重建，应用关闭时统一销毁 | 平台 |
| `SyncTaskService` | 任务 CRUD、调度表达式校验与落库 | 平台 |
| `TaskRunService` | 触发执行（手动/定时）、**每任务互斥**、有界线程池、记录落库、事件推送 | 平台 |
| `RunRecordStore` | `sync_record` / `sync_error` 持久化 | 平台 |
| `SyncSchedulerService` | Quartz Job/Trigger 同步（`@DisallowConcurrentExecution`） | 平台 |
| `CredentialCipher` | AES-GCM 加解密，密钥来自 `app.security.secret-key` | 平台 |

### 4.2 元数据表（Flyway 迁移为唯一事实源）

**保留既有表名**，仅增列（避免破坏既有数据）：

- `datasource`（既有）+ 不改结构（`password` 列改为存密文，前缀 `ENC(` 区分历史明文，首次读取时自动升级）
- `sync_task`（既有）+ 新增列：
  `cursor_value VARCHAR(255)`（原 `incr_value` 保留用于兼容，新逻辑读写 `cursor_value`）、
  `order_column VARCHAR(128)`、`safety_lag_seconds BIGINT DEFAULT 0`、`lookback_seconds BIGINT DEFAULT 0`、
  `full_sync_strategy VARCHAR(16) DEFAULT 'TRUNCATE'`、`error_policy_json TEXT`、`enabled TINYINT(1) DEFAULT 1`
- `sync_record`（既有）+ 新增列：
  `skipped_rows BIGINT DEFAULT 0`、`start_cursor VARCHAR(255)`、`end_cursor VARCHAR(255)`、
  `read_millis BIGINT`、`write_millis BIGINT`、`total_millis BIGINT`、`run_key VARCHAR(64)`、
  `preflight_json TEXT`
- `sync_error`（**新建**）：`id, record_id, task_id, phase, row_key, message, row_data, retryable, created_at` + 索引 `(record_id)`
- 唯一约束：`sync_record(run_key)` —— 保证同一 run 不会重复落库

### 4.3 REST API 契约（前端按此调用；新增端点以 `*` 标注）

既有端点路径与响应结构**保持向后兼容**。新增：

```
GET  /api/datasources/{id}/tables
GET  /api/datasources/{id}/tables/{table}/columns
*GET /api/tasks/{id}/preflight           → { hasError, issues:[{level,code,message,hint}] }
*POST /api/tasks/{id}/cancel             → 取消运行中的任务
GET  /api/tasks/{id}/records             → 分页；每条含 skippedRows/startCursor/endCursor/totalMillis
*GET /api/records/{id}/errors            → 坏行明细分页
*GET /api/system/info                    → { version, dbType, dm8Verified:false, activeTasks, runningTasks, poolSummary }
GET  /actuator/health                    → 匿名可读
```

统一错误响应体（`GlobalExceptionHandler` 保证）：

```json
{ "timestamp":"...", "status":400, "code":"INCR_COLUMN_MISSING", "message":"中文消息", "details":["..."] }
```

### 4.4 配置键（冻结；`application.yml` 由 Lead 维护）

```yaml
app:
  security:
    enabled: true
    username: admin
    password: ${DATASYNC_ADMIN_PASSWORD:}      # prod 必填，缺失即启动失败
    secret-key: ${DATASYNC_SECRET_KEY:}        # prod 必填，AES-GCM 密钥（≥32 字符）
  sync:
    worker-threads: 4
    max-concurrent-per-task: 1
    default-page-size: 1000
    default-batch-size: 500
    default-safety-lag-seconds: 0
    default-lookback-seconds: 0
    query-timeout-seconds: 300
    shutdown-timeout-seconds: 60
  pool:
    max-size-per-datasource: 8
    idle-timeout-seconds: 600
    max-lifetime-seconds: 1800
  retention:
    record-days: 30
    error-days: 7
```

---

## 5. 验收口径（QA 与 Lead 共同执行）

**必须全部通过才可宣布"生产可用"：**

1. `mvn -B -s <settings> -DskipTests package` 通过；`java -jar data-sync-server/target/data-sync-server-1.0.0-SNAPSHOT.jar` 能在 MySQL 元数据库上启动，Flyway 迁移全部 success。
2. 真实 MySQL 端到端：源库 10 万行 → 目标库，全量同步后逐行校验（`CHECKSUM`/`COUNT`+抽样比对）一致。
3. 断点续传：增量同步中途 kill 进程，重启后重跑，最终数据与源库一致（无重复、无丢失）。
4. 幂等性：同一任务连续执行 3 次，目标表行数不增长、内容不变。
5. 并发安全：同一任务并发触发 5 次，只有 1 个实例处于 RUNNING，其余被拒绝且返回明确中文提示。
6. 预检生效：错误配置在**写入任何数据前**失败，`sync_record` 记录 `PREFLIGHT` 阶段问题，错误码正确。四类必测：
   目标表不存在（`DST_TABLE_MISSING`）、增量字段不存在（`INCR_COLUMN_MISSING`）、
   增量字段不可排序（`INCR_COLUMN_NOT_SORTABLE`）、**类型不兼容（`TYPE_INCOMPATIBLE`）**。
   > 关于 `TYPE_INCOMPATIBLE` 的举例口径（验收阶段修正）：不要用 `DECIMAL(38,10) → VARCHAR(10)` 当例子。
   > 那属于"有损但可表达"，实现有意判为 `WARN:PK_MISSING` 级别的 **RISKY**，由运行期在 MAP 阶段拦截（拒绝静默截断）。
   > 真正触发 `TYPE_INCOMPATIBLE`（ERROR）的是**根本没有合理映射**的组合：
   > 日期时间 / 大文本 / 二进制 → 目标数值列，或目标列长度/精度小于源列且无法安全容纳。
7. 坏行策略：分**读阶段**与**写阶段**两条路径分别验，判定依据是设计决策而非实现细节——
   **跳过 = 该行已知且已落 `sync_error`，允许水位推进；失败 = 存在未知问题，必须停下且水位不推进**：
   - 写阶段（目标列超长）：`skipBadRows=true` → 任务完成、`skippedRows>0`、`sync_error` 有明细、水位**正常推进**；`false` → 任务失败、水位**不推进**。
   - 读阶段（源库存在 `0000-00-00` 零日期，Connector/J 默认拒绝）：两种配置都应失败、`sync_error` 归类到 `READ`、水位**不推进**。
     （**不**给 JDBC URL 加 `zeroDateTimeBehavior=convertToNull`：让非法数据在读阶段暴露，比悄悄转 NULL 再写坏目标库诚实。）
8. 数据源口令：库内存密文（`ENC(` 前缀），API 响应 `password` 字段为空或掩码，日志全文搜索无明文口令。
9. 认证：未登录访问 `/api/tasks` 返回 401/302 到登录页；登录后可正常调用；CSRF 生效。
10. 优雅停机：SIGTERM 后 ≤ `shutdown-timeout-seconds` 退出，运行中的同步被标记 `CANCELLED` 且水位不推进。
11. 前端 `npm run build` 通过，`vue-tsc --noEmit` 无错误；页面能完成"建数据源 → 预检 → 建任务 → 执行 → 看进度/错误"全链路。
12. 单元/集成测试全绿：`mvn test` 通过（core 覆盖率覆盖预检、键集分页、水位推进、幂等 upsert、脱敏）。

**DM8 相关**：只做代码评审与单测（方言 SQL 文本断言），在 README 与 `/api/system/info` 明确标注"未在真实达梦实例验证"。

---

## 6. 协作纪律

- 只 `git add` 自己新建/修改的源文件；**任何人都不得 `git commit`**（用户测试确认前不提交）。
- 临时脚本、测试库、日志一律放到 `data-sync/.dsh-scratch/<成员名>/`（已在 .gitignore 中忽略），不要污染仓库根。
- 发现契约有问题 → 发消息给 Lead，不要自行改签名。
- 每个人完成自己的任务后，把「改了什么、怎么验证的、遗留什么」用中文回报给 Lead。
