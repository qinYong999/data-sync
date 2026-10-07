# DataSync 生产可用性评估报告（当前基线）

> **评估基线 commit：`70474fc`**（`git rev-parse --short HEAD`，2026-10-07 实测）
>
> **重要前提：生产化改造已在进行中。本报告描述的是改造前的基线代码。**
> 评估期间 `data-sync-core` / `data-sync-server` / `data-sync-web` 正被其他成员并行重写，
> 工作区文件随时变化。为保证结论可复现，本报告**全部证据取自 `70474fc` 的不可变快照**，
> 而非实时工作区：
>
> ```powershell
> git archive --format=zip -o .dsh-scratch\architect\baseline.zip 70474fc
> Expand-Archive .dsh-scratch\architect\baseline.zip .dsh-scratch\architect\baseline
> ```
>
> 因此本报告中所有 `文件:行号` 均指向 **`70474fc` 的版本**。若你手上的工作区已改造，
> 行号会漂移，请用 `git show 70474fc:<路径>` 复核。**本报告不改动任何源码，也不代表改造后的状态。**

---

## 0. 评估范围、方法与实测原始结果

### 0.1 评估对象

| 模块 | 文件数(main) | 行数(main) | 文件数(test) | 行数(test) |
|---|---|---|---|---|
| `data-sync-core` | 20 | 740 | 4 | 180 |
| `data-sync-server` | 25 | 1380 | **0** | **0** |
| `data-sync-web`（`src/`） | 26 | 2159 | **0** | **0** |
| 合计（Java） | 49 | — | — | — |

（统计命令：对 `70474fc` 快照按模块 `Get-ChildItem -Recurse -Filter *.java` + `Measure-Object -Line`。）

### 0.2 实测原始结果（在基线快照中执行，非工作区）

| # | 命令 | 结果 | 日志 |
|---|---|---|---|
| 1 | `mvn -B -s <settings> -DskipTests package` | **BUILD SUCCESS**（Reactor：data-sync / core / server 全 SUCCESS，产出 `data-sync-server-1.0.0-SNAPSHOT.jar`） | `.dsh-scratch/architect/baseline-package.log` |
| 2 | `mvn -B -s <settings> test` | **BUILD FAILURE** — `Tests run: 18, Failures: 1, Errors: 0` | `.dsh-scratch/architect/baseline-test.log` |
| 3 | 前端 `npm run build` | **成功**（`✓ built in 7.89s`，仅有 >500 kB chunk 体积告警） | `.dsh-scratch/architect/baseline/web-build.log` |
| 4 | 前端 `vue-tsc --noEmit` | **EXIT=0，零输出**（类型检查干净） | `.dsh-scratch/architect/baseline/web-tsc.log` |

**唯一失败的测试**（其余 17 个全绿）：

```
[ERROR] com.datasync.core.job.processor.ColumnMappingProcessorTest.testProcessEmpty
        -- Time elapsed: 0.008 s <<< FAILURE!
org.opentest4j.AssertionFailedError: expected: <true> but was: <false>
    at ...ColumnMappingProcessorTest.testProcessEmpty(ColumnMappingProcessorTest.java:42)
```

这不是"测试写错了"这么简单：**测试编码的是原设计的意图，实现违反了它**。

- 原设计 §3.1.2 第 3 条：*"对不需要映射的源列直接丢弃"*（`docs/superpowers/specs/2026-06-06-database-sync-tool-design.md:79`）
- 测试期望：空映射 ⇒ 输出为空行（`data-sync-core/src/test/java/com/datasync/core/job/processor/ColumnMappingProcessorTest.java:40-43`）
- 实现实际：空映射 ⇒ **整行透传**（`data-sync-core/src/main/java/com/datasync/core/job/processor/ColumnMappingProcessor.java:26-28`）

即 `mvn test` 在基线上**是红的**。契约验收口径第 12 条（"单元/集成测试全绿：`mvn test` 通过"，`docs/production-readiness/00-frozen-contract.md:409`）在当前基线上**不成立**。

### 0.3 评估方法的局限（先说清楚，避免过度解读）

- 本次**未做真实 MySQL 端到端跑数**（10 万行一致性、断点续传 kill 进程、并发触发 5 次等）。契约 §5 的验收项 2/3/4/5/7 需要改造后由 QA 与 Lead 执行；本报告对这些项只做**代码级判定**，并明确标注。
- 涉及 MySQL 语义的结论（TRUNCATE 隐式提交、`setFetchSize` 是否生效、`initialize-schema` 重放 DDL）依赖官方语义 + 代码路径推断，**已在文中逐条标注证据等级**。
- 但 `data/datasync.trace.db`（**已被 git 跟踪**，见 §3.2-F8）包含一条**真实运行留下的 H2 异常**，属于一等现场证据，见 §3.2-F9。

---

## 1. 原始需求基线（从 README 与 specs/plans 提炼）

依据：`README.md`、`docs/superpowers/specs/2026-06-06-database-sync-tool-design.md`（下称 **原设计**）、
`docs/superpowers/plans/2026-06-06-database-sync-tool-implementation.md`（下称 **实施计划**）。

### 1.1 目标

1. MySQL → MySQL（同构）与 MySQL → 达梦 DM8（异构）的全量 / 增量 / 先全量后增量同步（原设计 §1.1，`design:8-10`）。
2. Web UI 完成"配数据源 → 配任务/字段映射 → 看执行状态与监控"闭环（原设计 §1.1，`design:10`）。
3. 元数据库默认 H2 内嵌、生产可切 MySQL（原设计 §3.3 导语，`design:171`；§6，`design:302`）。

### 1.2 非目标（明确不做）

不做实时 CDC（无 binlog）、不做复杂 ETL transform、不做双写/双向同步、不做 DDL 同步（原设计 §1.2，`design:12-16`）。

### 1.3 元数据模型（原设计 §3.3，`design:175-242`）—— 五张表的明确约定

`datasource`、`sync_task`、**`sync_watermark`（增量水位线）**、`sync_record`、**`sync_error`（错误行）**。

### 1.4 数据流（原设计 §4）

- **全量**：`Step 1 预检`（源表存在 / 目标表存在 / 列兼容性）→ `Step 2 TRUNCATE` → `Step 3 Chunk 循环`（`SELECT * FROM src ORDER BY id LIMIT ? OFFSET ?`）→ `Step 4 更新同步记录`（`design:249-262`）。
- **增量**：`Step 1 水位读取`（从 `sync_watermark` 读 `max_value`，无记录则全量）→ `Step 2 Chunk 循环`（`WHERE updated_at > ? ORDER BY updated_at, id`；写入用 `ON DUPLICATE KEY UPDATE` / `MERGE INTO`）→ `Step 3 水位更新`（写回 `sync_watermark`）（`design:267-278`）。

### 1.5 错误处理约定（原设计 §5，`design:283-291`）

| 场景 | 约定 |
|---|---|
| 连接断开 | `RetryTemplate` 自动重试，**默认 3 次，间隔 5s** |
| 单行转换失败 | **跳过该行，写入 `sync_error` 表**，记录错误原因 |
| 整批写入失败 | 回退 chunk，重试默认 3 次 |
| 任务重叠 | Quartz `@DisallowConcurrentExecution` 防重叠 |
| 目标表不存在 | **预检阶段报错，任务立即失败，不清除已有数据** |
| 字段不兼容 | 预检 warning + 可选 force 跳过 |
| 同步中断重启 | Spring Batch JobRepository 保存上下文，**重启后从断点继续** |

### 1.6 技术栈与测试策略

- 技术栈：**JDK Java 25**、Spring Boot 3.x、Spring Batch 5.x、Quartz、HikariCP、元数据库 H2(默认)/MySQL(生产)、**测试 JUnit 5 + Testcontainers + Mockito**（`design:293-309`；`plan:9`、`plan:38-42`）。
- 测试策略（原设计 §7，`design:311-319`）：单测（TypeMapper/SQLBuilder/FieldMappingProcessor/数据模型）、**集成测试（Testcontainers 起 MySQL）**、**API 测试（MockMvc / WebTestClient）**、前端 Vitest + Vue Test Utils、端到端 Docker Compose。
- API 契约（原设计 §3.2，`design:147-148`）明确包含 `POST /api/tasks/{id}/start` 与 **`POST /api/tasks/{id}/stop`（停止正在运行的任务）**；`GET /api/records/{id}` 需"含失败行详情"（`design:157`）。

---

## 2. 实现偏离矩阵

偏离性质：**缺失**＝设计有代码无；**走样**＝有实现但语义/契约不符；**画蛇添足**＝设计没有却加了。

### 2.1 核心偏离（逐条已核实）

| # | 原设计要求（证据） | 代码实际实现（证据） | 偏离性质 | 生产风险 |
|---|---|---|---|---|
| M1 | **§4.1 `Step 1: 预检`**：检源表/目标表存在、列兼容性（`design:250-253`） | **完全不存在**。`SyncJobConfig.buildJob()` 只做 Reader/Processor/Writer/Step 组装，无任何元数据校验（`data-sync-core/src/main/java/com/datasync/core/job/SyncJobConfig.java:42-83`）；全仓库 grep `preflight\|Preflight` 在 `70474fc` 的 Java 源码中**零命中** | 缺失 | **阻塞** |
| M2 | **§3.3 `sync_watermark` 水位线表**（`design:210-217`），§4.2 从它读/写水位（`design:269-278`） | **表不存在**（无 DDL、无实体、无 Flyway）；水位退化为 `sync_task.incr_value` 单列（`data-sync-server/src/main/java/com/datasync/server/entity/SyncTaskEntity.java:44-46`） | 缺失/走样 | **阻塞** |
| M3 | **§4.2 `Step 3: 水位更新` = 写回"本次读取的最大值"**（`design:276-277`） | **走样成"执行结束后重新查源表全局 `MAX(col)`"**，与本次读到了什么无关（`data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:185-196`，尤其 188-191 行的 `SELECT MAX(<incrColumn>) FROM <sourceTable>`） | 走样 | **阻塞** |
| M4 | **§3.3 `sync_error` 错误行表**（`design:234-241`），§5 单行失败落该表（`design:286`） | **表与代码完全不存在**；`SyncRecordEntity` 只有 `errorRows` 计数，无明细（`data-sync-server/src/main/java/com/datasync/server/entity/SyncRecordEntity.java:44-46`）；`GET /api/records/{id}` 只返回记录本身（`data-sync-server/src/main/java/com/datasync/server/controller/RecordController.java:12`），**无失败行详情** | 缺失 | **高** |
| M5 | **§5 `RetryTemplate` 重试 3 次/间隔 5s、整批失败重试 3 次**（`design:285,287`） | **零重试配置**：`StepBuilder` 没有 `.faultTolerant()` / `retryLimit` / `skipLimit`（`SyncJobConfig.java:70-76`）。更糟的是 `SyncProgressListener.afterChunkError()` 会向用户播报 *"批次处理出错，正在进行重试..."*（`data-sync-core/src/main/java/com/datasync/core/job/SyncProgressListener.java:42-48`）——**这是一句假话，没有任何重试在进行** | 缺失 + 误导 | **高** |
| M6 | **§3.1.3 写入器**：全量先 TRUNCATE 再写；**增量用 `INSERT ... ON DUPLICATE KEY UPDATE`(MySQL) / `MERGE INTO`(DM8)**（`design:87-89`） | 写入器**永远只发裸 `INSERT INTO`**（`data-sync-core/src/main/java/com/datasync/core/job/writer/JdbcBatchWriter.java:36-41, 61`）。`SqlBuilder.buildUpsertSql()` 实现齐全（`data-sync-core/src/main/java/com/datasync/core/job/SqlBuilder.java:34-77`），但**生产代码零调用**，只被 `SqlBuilderTest.java:23,30` 调用 → **死代码** | 缺失 | **阻塞** |
| M7 | **§3.1.3 批量大小可配置（默认 500）**（`design:86`） | **`batchSize` 完全不生效**。Step 的 chunk size 传的是 `pageSize`：`.chunk(taskConfig.getPageSize(), transactionManager)`（`SyncJobConfig.java:71`）。全仓库 `getBatchSize()` 在 `70474fc` 只出现在：4 处 getter/日志、2 处 setter 管道，**没有一处用于决定 chunk 大小** | 走样 | 高 |
| M8 | **§3.1.1 `PageReader`**："基于 LIMIT ? OFFSET ? 或 WHERE id > ? ORDER BY id LIMIT ? 游标分页"，"支持自定义排序字段"（`design:60-62`） | 走的是 Spring Batch `MySqlPagingQueryProvider` + `setSortKeys({orderBy: ASC})`（`data-sync-core/src/main/java/com/datasync/core/job/reader/PageReader.java:20-27`），MySQL 方言下**生成的就是 `LIMIT ? OFFSET ?`**；且排序键被**硬编码为 `"id"`**（`SyncJobConfig.java:98,100`）。类注释却写 *"实现真正的 keyset 分页"*（`PageReader.java:15`）——**注释与行为相反** | 走样 | **阻塞** |
| M9 | **§3.2 `POST /api/tasks/{id}/stop` 停止运行中任务**（`design:148`） | **端点不存在**。`TaskController` 只有 `/trigger`、`/enable`、`/disable`、`/schedule`、`/columns`（`data-sync-server/src/main/java/com/datasync/server/controller/TaskController.java:30-80`）。无取消机制：`SyncProgressListener` 无 `cancel` 钩子，Step 无 `StepExecution#setTerminateOnly` 调用 | 缺失 | 高 |
| M10 | **§7 测试策略：Testcontainers + MockMvc/WebTestClient**（`design:313-319`） | **`data-sync-server/src/test` 目录不存在**（实测 `Test-Path` = `False`），server 侧 **0 个测试**；core 只有 4 个测试类共 18 个用例，全部用 **H2 内存库**（`SyncJobIntegrationTest.java:22-26`）。全仓库**无 Testcontainers 依赖**（`data-sync-core/pom.xml:32-46`、`data-sync-server/pom.xml:40-44`）；前端 `package.json` **无 vitest / @vue/test-utils，无 test 脚本**（`data-sync-web/package.json:6-22`） | 缺失 | 高 |
| M11 | **技术栈：JDK Java 25**（`design:297`、`plan:9`、`plan:38-40`） | 实际编译目标 **Java 21**（根 `pom.xml:15-18`），README 徽章也改成 21（`README.md:3`）；Docker 基镜像 `eclipse-temurin-21`（`Dockerfile:8,17`）。**属主动降级**（本机只有 JDK 25，编译到 21 是合理的），但**与设计文档不一致且文档未更新** | 走样（低危） | 低 |
| M12 | **元数据库默认 H2 内嵌**（`design:171`、`plan:124-131`：`jdbc:h2:file:./data/datasync;AUTO_SERVER=TRUE` + `h2.console.enabled=true`） | 配置已改为 **MySQL `jdbc:mysql://127.0.0.1:3306/datasync`**（`data-sync-server/src/main/resources/application.yml:6`），**合理**；但 `data/datasync.mv.db`(49 KB) 与 `data/datasync.trace.db`(51 KB) **仍被 git 跟踪**（`git ls-files data` 输出二者），且 `.gitignore` 后来才加的 `data/` 对已跟踪文件无效 | 画蛇添足（遗留物） | 低（但见 F8/F9） |
| M13 | **§3.1.2 Processor 第 2 条**："对需要类型转换的列**调用 `TypeMapper` 转换值**"（`design:78`） | **`TypeMapper` 在同步链路上是死的**。`ColumnMappingProcessor` 持有 `typeMapper` 字段（`ColumnMappingProcessor.java:16,20`）却**从不调用**（`process()` 全文 `ColumnMappingProcessor.java:23-41`）。实测：`70474fc` 的 `src/main` 下 grep `\.mapType` **零命中**。27 项类型映射（`MySqlToDm8TypeMapper.java:10-38`）只在单测里被调用 | 缺失 | **高** |
| M14 | **§6 达梦驱动 `dm.jdbc.driver`**（`design:304`）、**§1.1 支持 MySQL→DM8**（`design:9`） | `pom.xml` 中**没有任何达梦驱动依赖**（`data-sync-core/pom.xml`、`data-sync-server/pom.xml` 全文无 `dm8`/`DmDriver` artifact）；代码里只有字符串 `"dm.jdbc.driver.DmDriver"`（`SyncExecutionService.java:271`）与 URL 模板（`SyncExecutionService.java:283-285`）。**DM8 路径在运行期必然 `ClassNotFoundException`** | 缺失 | **高**（虚假支持声明） |
| M15 | **§3.1.3 `Dm8BatchWriter` 覆盖 DM8 特定行为**：MERGE INTO upsert、批量 ≤1000 行限制（`design:91-94`） | `Dm8BatchWriter` 是**空壳**——只把 `DbType.DM8` 传给父类，**不覆盖任何方法**（`data-sync-core/src/main/java/com/datasync/core/job/writer/Dm8BatchWriter.java:9-14`）；父类根本不看 `dbType` 字段（`JdbcBatchWriter.java:19` 定义后全文未使用） | 走样 | **高** |

### 2.2 README 对外声称 vs 代码事实（这一节直接决定"能不能拿给客户看"）

README 是给客户/运维看的第一份材料，其中 **4 条能力声明与代码事实相反**：

| README 声明（行号） | 代码事实 | 判定 |
|---|---|---|
| `README.md:8` "基于 Spring Batch 的……同步平台" | 属实（确实用 Spring Batch 5.1.2，根 `pom.xml:22`） | ✅ 属实 |
| `README.md:35` "自动处理 MySQL → DM8 的 **27 种类型映射**" | 27 项映射表存在（`MySqlToDm8TypeMapper.java:10-38`），但**在同步链路上从不被调用**（见 M13）；且无 DM8 驱动（M14） | ❌ **虚假** |
| `README.md:36` "根据目标库类型**自动切换 SQL 方言**（MySQL `ON DUPLICATE KEY UPDATE` / DM8 `MERGE INTO`）" | 写入器永远只发裸 `INSERT`（M6），upsert SQL 是死代码 | ❌ **虚假** |
| `README.md:45` "分页读取、Chunk 批量写入、**失败重试、断点续传**" | 无任何 retry/skip 配置（M5）；每次执行带唯一 `timestamp` 参数 ⇒ **每次都是新 JobInstance，Spring Batch 的重启语义永远用不上**（`SyncExecutionService.java:145-148`）⇒ 断点续传**不存在** | ❌ **虚假** |
| `README.md:46` "每次同步按配置**动态创建连接池，用完自动释放**" | 属实（`SyncExecutionService.java:130-131, 257-273`、`finally` 中 `close()` 于 248-251）——但**这是缺陷不是特性**（见 F5） | ⚠️ 属实但有害 |
| `README.md:28-30` 全量/增量/FULL_INCR 三种模式 | 存在，且 `FULL_INCR` 无有效增量配置时退化为全量（`SyncJobConfig.java:56-60`） | ✅ 属实 |
| `README.md:42` "实时日志 WebSocket 推送" | 存在（`SyncLogWebSocketHandler.java`） | ✅ 属实 |
| `README.md:49` "容器化部署 — Docker Compose 一键启动" | 见 F11：Docker 构建命令为**离线模式**，在空 `~/.m2` 的构建镜像里必然失败 | ❌ **大概率不可用（静态判定，未实测）** |
| `README.md:332` "从 Spring Batch 的 `StepExecution` 提取异常信息保存到 `error_message`" | 属实（`SyncExecutionService.java:160-181`） | ✅ 属实 |

**结论：README 目前是一份"愿景文档"而非"交付说明"，其中至少 4 条能力声明属于虚假陈述。** 在改造完成前，这份 README 不可对外发布。

---

## 3. 独立缺陷清单（实现自身的问题，按严重级排序）

每条给「现象 → 触发条件 → 后果 → 证据 → 建议」。**F1–F6 为阻塞级**。

---

### F1（阻塞）全量同步先 TRUNCATE、失败无法回滚 ⇒ 目标表被清空且数据灭失

- **现象**：`fullSync` 时，`TRUNCATE TABLE` 在**第一个 chunk 的写入方法内部**执行，且在**任何源端读取/字段校验之前**——只要 Step 有一个 chunk 被提交，TRUNCATE 就已生效。
- **触发条件**：FULL 或 FULL_INCR（首次/无有效增量配置，`SyncJobConfig.java:56-60`）模式的任一执行，在首个 chunk 之后失败：目标库磁盘满、网络中断、字段映射导致 `INSERT` 列数不匹配、目标表被并发 DDL、进程被 kill。
- **后果**：**目标表原有数据被清空且无法恢复**。MySQL 的 `TRUNCATE` 是 DDL，**触发隐式提交，chunk 事务回滚也回不来**。这是原设计 §5 明确要求防住、而实现完全没有防的场景（`design:289`："目标表不存在 → 预检阶段报错，任务立即失败，**不清除已有数据**"）。**这是本基线最严重的单一缺陷。**
- **证据**：
  - `data-sync-core/src/main/java/com/datasync/core/job/writer/JdbcBatchWriter.java:48-51`（`if (fullSync && !truncated) { jdbcTemplate.execute(truncateSql); ... }`，位于 `write(Chunk)` 方法体内，第 44 行起）
  - `data-sync-core/src/main/java/com/datasync/core/job/writer/JdbcBatchWriter.java:33`（`this.truncateSql = "TRUNCATE TABLE " + table;`）
  - `data-sync-core/src/main/java/com/datasync/core/job/SyncJobConfig.java:56-67`（`isFullSync` 计算与 writer 构造）
  - 无预检：`SyncJobConfig.java:42-83` 全文无校验（见 M1）
- **建议**：预检全部通过后才允许清理（契约 D5/D14）；把清理做成**独立的、可回滚的、且失败可恢复**的步骤：优先 `SWAP`（临时表 + RENAME，秒级原子切换，失败不影响原表），`TRUNCATE` 仅在明确选择且预检通过时执行；执行前记录目标表行数/校验和到 `sync_record`，失败时给出可核对的损失范围。

---

### F2（阻塞）增量游标语义错误：用「执行结束后的源表全局 MAX」当水位，且只以 `readRows>0` 为门槛 ⇒ 永久丢数

- **现象**：游标推进块位于 Job 结束之后（`SyncExecutionService.java:184-206`），取值方式是**重新对源表跑一次 `SELECT MAX(<incrColumn>) FROM <sourceTable>`**，然后把结果覆盖写入 `sync_task.incr_value`。
- **触发条件**：任何 INCR / FULL_INCR 任务，只要源表在"Reader 读到最后一行"与"MAX 查询"之间**有新行提交**（哪怕只隔几毫秒），这段区间的数据就被跳过。
- **后果**：**永久丢数，且平台自己完全不知情。** 因为下次执行的条件是 `WHERE <incrColumn> > <水位>`，被跳过区间的行永远不会再被读到；`sync_record` 显示 `COMPLETED`，日志显示 *"✓ 增量游标已推进"*。
  进一步，判据只有三个：`status==COMPLETED`、有增量列、`totalRead > 0`（第 185 行）。**不检查 `writeRows == readRows`、不检查本次实际覆盖的区间上界**。因此 `readRows>0 && writeRows==0`（例如字段映射把整行映射空、目标表列全部不匹配导致静默行为异常）时，**游标照样推进**。
  再进一步，游标推进失败被**吞掉并且反着说**：`log.warn("推进增量游标失败(不影响同步结果)")`（第 203 行）——**它恰恰影响同步结果**，失败后下次会从旧水位重放，而重放路径又只有裸 `INSERT`（F3），于是变成主键冲突。
- **证据**：
  - `data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:185`（推进条件）
  - `data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:188-191`（`SELECT MAX(...) FROM <表>`，无 WHERE、无上界锚点、无 safetyLag）
  - `data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:193-194`（`taskEntity.setIncrValue(maxValue); taskRepo.save(taskEntity);`）
  - `data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:202-204`（catch + 误导性 warn）
  - 无 safetyLag / lookback 概念：`70474fc` 的 Java 源码中 grep `safetyLag\|lookback` 零命中
  - 读取侧无上界：`WHERE <col> > ?` 单边条件（`data-sync-core/src/main/java/com/datasync/core/job/SqlBuilder.java:17`），`IncrementalReader` 也未加上界（`data-sync-core/src/main/java/com/datasync/core/job/reader/IncrementalReader.java:16`）
- **建议**：按契约 D3 重写：**执行开始即锚定上界** `upper = now - safetyLag`，读区间 `(lower, upper]`；水位推进值 = **本次实际读到并成功提交的最大游标值**（由引擎在 chunk 提交时回报，对应契约 §3.3 `ChunkProgress.cursor()`），**不是**重新查源表 MAX；`endCursor == null` 或本次有未提交 chunk 时**禁止推进**；把"推进失败"从 `warn` 升级为**执行失败**。同时修掉 F2 与 F3 的组合问题（重放安全必须靠幂等 upsert）。

---

### F3（阻塞）写入无幂等：`SqlBuilder.buildUpsertSql` 是死代码，实际只发裸 INSERT

- **现象**：目标写入路径只有 `INSERT INTO ... VALUES (...)`（`JdbcBatchWriter.java:36-41`），没有 `ON DUPLICATE KEY UPDATE`、没有 `MERGE INTO`。`buildUpsertSql`（`SqlBuilder.java:34-77`，MySQL 与 DM8 两套实现都写好了）在 `src/main` 中**零调用点**。
- **触发条件**：任何重放场景——增量重跑、断点续跑、同一任务并发两个实例、因 `timestamp` 参数导致的历史 Job 重跑。
- **后果**：
  1. 增量模式下只要重放已同步过的行 ⇒ **`Duplicate entry` 主键冲突 ⇒ 整个 chunk 失败 ⇒ 任务 FAILED**（无法自我修复，需要人工介入，人工介入又会再次冲突）。
  2. 若目标表**没有主键/唯一键**，`INSERT` 全部成功 ⇒ **重复数据静默堆积**，行数持续增长，且没有任何机制会发现。
  3. 契约 D4（幂等 upsert）与验收口径第 4 条（连续执行 3 次行数不增长）**必然失败**。
- **证据**：
  - `data-sync-core/src/main/java/com/datasync/core/job/writer/JdbcBatchWriter.java:36-41, 61`
  - `data-sync-core/src/main/java/com/datasync/core/job/SqlBuilder.java:34-77`（实现存在）
  - 实测调用点扫描（`70474fc` 快照，全仓库 `*.java` grep `buildUpsertSql`）：只有 `SqlBuilder.java:34`（定义）与 `SqlBuilderTest.java:23,30`（测试）
  - `DbType dbType` 字段在 writer 中定义后未被使用：`JdbcBatchWriter.java:19`
- **建议**：按契约 D4 让 writer 走 `buildUpsertSql`，主键从 JDBC 元数据推断（`DatabaseMetaData.getPrimaryKeys`，现成代码可参考 `data-sync-server/src/main/java/com/datasync/server/service/DataSourceService.java:62-69`）；主键缺失时由预检报 `PK_MISSING` 并**拒绝写入**。

---

### F4（阻塞）分页用 `LIMIT/OFFSET` + 硬编码 `ORDER BY id` ⇒ 并发写入下重复/漏行，且非 `id` 主键表直接报错

- **现象**：两个独立问题叠加。
  1. **OFFSET 分页**：`PageReader` 用 Spring Batch `MySqlPagingQueryProvider`（`PageReader.java:20-27`），MySQL 方言下生成 `LIMIT ? OFFSET ?`。OFFSET 分页在源表并发写入时**必然重复或漏行**：后面插入的行会把结果集整体后移，导致"跳页→漏行"；删除则导致"回退→重复行"。
  2. **硬编码 `"id"`**：排序列被写死成字符串 `"id"`（`SyncJobConfig.java:98` 与 `:100` 两处），既不是从 `orderColumn` 配置来的，也不是从元数据推断的。
- **触发条件**：源表在同步期间有 INSERT/DELETE；或源表根本没有名为 `id` 的列（例如主键叫 `user_id`、`uuid`）。
- **后果**：
  - 有并发写入的源表 ⇒ **静默漏行/重复行**，且校验时 `COUNT` 可能凑巧相等，极难发现。
  - 源表无 `id` 列 ⇒ `SELECT * FROM <表> ORDER BY id LIMIT ...` 直接 **SQL 语法/未知列错误 ⇒ 任务启动即失败**。这在真实客户库上非常常见。
  - 契约 D2（禁止 `LIMIT/OFFSET`、一律键集分页）与验收口径第 2 条（10 万行逐行一致）在并发场景下不成立。
- **证据**：
  - `data-sync-core/src/main/java/com/datasync/core/job/reader/PageReader.java:15`（注释声称 keyset，行为是 OFFSET）
  - `data-sync-core/src/main/java/com/datasync/core/job/reader/PageReader.java:20-27`
  - `data-sync-core/src/main/java/com/datasync/core/job/SyncJobConfig.java:98`（`new IncrementalReader(..., "id", ...)`）
  - `data-sync-core/src/main/java/com/datasync/core/job/SyncJobConfig.java:100`（`new PageReader(sourceDs, config.getSourceTable(), "id", config.getPageSize())`）
  - `data-sync-core/src/main/java/com/datasync/core/job/SqlBuilder.java:12-13`（`buildSelectPage` = `ORDER BY %s LIMIT %d OFFSET %d`，同样只在 `SqlBuilderTest.java:11` 被调用）
  - `data-sync-core/src/main/java/com/datasync/core/model/SyncTaskConfig.java` 全文**没有 `orderColumn` 字段**（契约 §3.1 要求有）
- **建议**：按契约 D2 实现键集分页 `WHERE (k1,k2,...) > (?,?,...)`；排序列优先取 `orderColumn`，为空则从主键元数据推断，推断失败直接预检报错；把唯一性/tie-breaker 纳入预检（详见 §6 异议 A）。

---

### F5（阻塞）同任务可自我重叠 + 无界公共线程池 ⇒ 并发双写、游标互踩

- **现象**：手动触发走 `CompletableFuture.supplyAsync(...)`（`SyncExecutionService.java:65`）——**没有传 Executor，用的是 `ForkJoinPool.commonPool()`**（JDK 默认并行度 = CPU-1，且是 JVM 全局共享）。同时全仓库**没有任何任务级互斥**：没有 DB 唯一约束、没有 `ReentrantLock`、没有运行中标记检查。
- **触发条件**：同一任务被连点两次"手动执行"；或"手动执行"与 Quartz 定时触发撞车（`SyncQuartzJob.java:39` 走的是同步调用，与手动触发可并行）；或 5 个任务同时触发（契约验收口径第 5 条场景）。
- **后果**：
  1. **同一任务两个实例同时跑**，对同一张目标表并发写；FULL 模式下后启动的实例会再 TRUNCATE 一次，把先启动实例已写入的数据删掉 ⇒ 最终数据不可预测。
  2. 两个实例都执行 F2 的 `MAX()` 推进，**游标互相覆盖**，中间区间永久丢失。
  3. `commonPool` 被长时间阻塞的同步任务占满 ⇒ **拖垮 JVM 中所有其他使用公共池的并行流**（JPA/Hibernate 若无显式线程池亦受影响）；且无并发上限，10 个任务 = 10 个同步 + 20 个 Hikari 池。
- **证据**：
  - `data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:64-66`（`return CompletableFuture.supplyAsync(() -> executeTask(taskId, "MANUAL"));`）
  - `data-sync-server/src/main/java/com/datasync/server/controller/TaskController.java:62`（触发入口，返回值被丢弃）
  - 互斥缺失：`70474fc` 全仓库 grep 无 `max-concurrent` / `worker-threads` / `ReentrantLock` / 运行中唯一约束；`SyncTaskEntity` 无 `@Version`（实测 `@Version` 在 server 模块零命中）
  - Quartz 侧**也没有** `@DisallowConcurrentExecution`（原设计 §5 明确要求，`design:288`）：`data-sync-server/src/main/java/com/datasync/server/job/SyncQuartzJob.java:14-44`（实测 `DisallowConcurrentExecution` 在 `70474fc` 全仓库零命中）
- **建议**：按契约 D11 双保险——`sync_record(run_key)` 唯一约束 + 应用级 `ConcurrentHashMap<taskId, Lock>` / `tryLock` 立即拒绝并返回明确中文提示；改用**有界线程池**（`app.sync.worker-threads`）并配 `TaskDecorator` 传递上下文；给 Quartz Job 加 `@DisallowConcurrentExecution`。

---

### F6（阻塞）无认证、口令明文落库、API 明文回显、WebSocket 全量裸奔

这一条拆成 4 个子问题，但它们是同一个"平台没有任何安全边界"的事实。

| 子项 | 现象 | 证据 |
|---|---|---|
| F6-a **无认证** | 全部 `/api/**` 匿名可访问。`pom.xml` 里**没有 `spring-boot-starter-security`**，代码里没有 `SecurityFilterChain` / `@PreAuthorize`（实测二者在 `70474fc` 的 server 模块零命中） | `data-sync-server/pom.xml:13-54`（依赖全表，无 security）；`data-sync-server/src/main/java/com/datasync/server/controller/*.java` 全部无鉴权注解 |
| F6-b **口令明文落库** | 数据源密码以明文写进 MySQL `datasource.password` 列。实体注释却写着"登录密码（**加密存储**）" | 写入：`data-sync-server/src/main/java/com/datasync/server/service/DataSourceService.java:160`（`e.setPassword(dto.getPassword())`）；实体：`data-sync-server/src/main/java/com/datasync/server/entity/DataSourceEntity.java:40-42`；虚假注释：`DataSourceEntity.java:41` 与 `data-sync-server/src/main/java/com/datasync/server/config/DatabaseCommentInitializer.java:74` |
| F6-c **API 响应回显明文口令** | `GET /api/datasources` 直接把 JPA 实体序列化成 JSON，`password` 字段**无 `@JsonIgnore`、无 DTO 转换、无脱敏**（实测 `JsonIgnore` 在 server 模块零命中）⇒ 任何能访问 8080 的人**一次列表请求就拿到所有库的 root 口令** | `data-sync-server/src/main/java/com/datasync/server/controller/DataSourceController.java:19-20`（`public Page<DataSourceEntity> list(...)`）；`data-sync-server/src/main/java/com/datasync/server/entity/DataSourceEntity.java:62`（`getPassword()` 无注解）；前端类型里干脆没有 password 字段（`data-sync-web/src/types/index.ts:15-25`），说明**前端并不需要它，接口却在发** |
| F6-d **WebSocket 无鉴权 + 通配 Origin + 全量广播 + 同步阻塞发送** | `/ws/logs` 允许任意 Origin、无握手鉴权；服务端把**所有任务**的实时日志推给**所有**连接；推送是**在同步线程上同步发送**（`for (session : sessions) s.sendMessage(...)`） | 通配 Origin：`data-sync-server/src/main/java/com/datasync/server/config/WebSocketConfig.java:15`（`.setAllowedOrigins("*")`）；订阅与广播：`data-sync-server/src/main/java/com/datasync/server/config/SyncLogWebSocketHandler.java:19-23`；同步发送在调用线程：`data-sync-core/src/main/java/com/datasync/core/job/SyncEventBus.java:13`（`listeners.forEach(l -> l.accept(message))`） |

- **后果**：未授权者可以读取全部数据源口令、创建任务（把任意库的数据写到任意库）、触发 TRUNCATE。**任何"生产可用"的结论都不可能在这一条之上成立**（契约 D9、D10 与验收口径第 8、9 条）。
- **补充**：`GlobalExceptionHandler` 把原始异常消息直接回给前端（`data-sync-server/src/main/java/com/datasync/server/exception/GlobalExceptionHandler.java:11,14`），JDBC 异常消息里通常带 **URL、用户名、主机名**，构成额外信息泄漏；且 `Map.of` 不接受 null value，`e.getMessage()` 为 null 时 handler 自身会再抛 NPE，把 400 变成 500。
- **建议**：按契约 D9/D10 全部落地：AES-GCM 加密落库、响应脱敏、`spring-boot-starter-security` 表单登录 + CSRF、`/actuator/health` 白名单、WS 绑定已认证 Session 并按 taskId 过滤订阅、异常消息消毒（保留 traceId 供关联，不回显原始 SQL 报错）。

---

### F7（高）每次执行新建并销毁 2 个 Hikari 连接池 ⇒ 连接风暴

- **现象**：`executeTask` 每次执行都 `buildDataSource(sourceEntity)` + `buildDataSource(targetEntity)`（各建一个 `maximumPoolSize=10, minimumIdle=2` 的 `HikariDataSource`），`finally` 里全部 `close()`。
- **触发条件**：任意次数的执行，尤其是高频定时（每 5 分钟一次的 10 个任务）。
- **后果**：每次执行 ≈ 建立 2 个池 + 4 个常驻连接 + 关闭时的连接抖动；每次 `close()` 后下一次执行冷启动要重新 TCP+TLS+认证握手（MySQL 认证 + `allowPublicKeyRetrieval` 的 RSA 交换在公网 RTT 下可达数百毫秒）。库侧 `max_connections` 会被频繁的池创建/销毁拉扯；`DataSourceService` 的连接测试、列信息查询**另走 `DriverManager.getConnection` 每次新建裸连接**（`DataSourceService.java:41,50,82,100,125`），与池并行存在 ⇒ 同一数据源的连接数完全不可控。
- **证据**：`data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:130-131`（两次构建）、`:257-273`（池参数）、`:248-251`（finally 关闭）；`DataSourceService.java:41,50,82,100,125`（裸连接）。
- **建议**：按契约 D12 实现 `ConnectionPoolRegistry`（按数据源 ID 缓存 + 空闲回收 + 配置变更失效 + 关闭时统一销毁），所有读元数据的路径也走同一个池。

---

### F8（高）无优雅停机 ⇒ kill 后连接中断、`sync_record` 永久卡在 RUNNING、目标表处于半完成状态

- **现象**：基线 `application.yml` 没有 `server.shutdown` 配置（实测 `70474fc` 的 `application.yml:1-29` 中无 `shutdown` 关键字；`server:` 段只有 `port: 8080`）。
- **触发条件**：`docker-compose down`、`kubectl delete pod`、按 Ctrl+C、SIGTERM。
- **后果**：
  1. Spring Boot 默认**立即停机**，正在跑的同步线程被硬中断，JDBC 连接被暴力关闭，MySQL 侧事务由服务端超时回滚（可能长达数分钟）。
  2. `sync_record.status` 停在 `"RUNNING"`（`SyncExecutionService.java:91`），**没有任何启动对账逻辑去纠正它**（实测无 `@Scheduled`、无启动扫描）⇒ 执行历史里永久留存"僵尸 RUNNING"记录，运维无法判断真实状态。
  3. FULL 模式下目标表已被 TRUNCATE 但只写入了一部分 ⇒ **对外呈现为"数据少了一半"且没有记录说明**。
  4. 验收口径第 10 条（SIGTERM 后运行中同步标记 `CANCELLED` 且水位不推进）不成立。
- **证据**：`data-sync-server/src/main/resources/application.yml:1-29` 无 `server.shutdown`；`SyncExecutionService.java:91`（`RUNNING` 写入后无超时/对账）；全仓库 grep `@Scheduled|cleanup|Cleanup|retention` **零命中**。
- **建议**：`server.shutdown: graceful` + `spring.lifecycle.timeout-per-shutdown-phase`；引擎支持协作式取消（契约 §3.3 `SyncEngine.cancel(long)`）；启动时把 `start_time` 早于本次启动、状态仍为 `RUNNING` 的记录标为 `CANCELLED` 并写清原因。

---

### F9（高）Spring Batch 元数据表无人管理：每次启动重放 DDL + 无限膨胀

- **现象**：`spring.batch.jdbc.initialize-schema: always`（`application.yml:19`）⇒ 每次启动都尝试执行 Batch 建表脚本；`SyncExecutionService` 每次执行都塞入唯一 `timestamp` 参数（`SyncExecutionService.java:147`）⇒ **每次执行生成一个全新 `BATCH_JOB_INSTANCE`**，永不合并、永不清理。
- **触发条件**：每次应用启动；每次任务执行（无论手动还是定时）。
- **后果**：
  1. 启动时批量报 `Table ... already exists`。**这不是推测——`data/datasync.trace.db` 里有真实运行留下的痕迹**（该文件**已被 git 跟踪**）：
     ```
     2026-06-06 12:45:59.414930+08:00 jdbc[3]: exception
     org.h2.jdbc.JdbcSQLSyntaxErrorException: Table "BATCH_JOB_INSTANCE" already exists; SQL statement:
     CREATE TABLE BATCH_JOB_INSTANCE ( JOB_INSTANCE_ID BIGINT ... )
     ```
     在 MySQL 上同样会发生（Batch 官方 MySQL 脚本用裸 `CREATE TABLE`，无 `IF NOT EXISTS`），日志噪音 + 启动时长不可控；配合 F8 的半完成状态，Batch 表里的 `STARTED` 记录会永久残留。
  2. `BATCH_JOB_INSTANCE` / `BATCH_JOB_EXECUTION` / `BATCH_STEP_EXECUTION*` / `BATCH_JOB_EXECUTION_PARAMS` / `BATCH_JOB_EXECUTION_CONTEXT` 六张表随执行次数线性增长。每个定时任务每天 288 次执行 ⇒ 单任务一年 10 万行，且 `BATCH_STEP_EXECUTION_CONTEXT` 里存的是**序列化的 Reader/Writer 状态**，体积不小。`sync_record` 同理，**没有任何清理任务**。
  3. 契约 D1 移除 Spring Batch 的核心理由之一就是"JobRepository 只带来元数据表膨胀与 Job 实例爆炸"——基线正好完整命中了这个反模式。
- **证据**：`application.yml:17-21`；`SyncExecutionService.java:145-150`；`data/datasync.trace.db`（git 跟踪）；grep 无清理任务。
- **建议**：改造后由自研引擎彻底去掉 JobRepository；过渡期若仍保留 Batch，则 `initialize-schema: never` + Flyway 管 schema，并加保留策略（契约 `app.retention.record-days/error-days`）。

---

### F10（高）配置与部署缺陷：明文口令入库、GBK 控制台编码、`ddl-auto=update`、Docker 离线构建

| 子项 | 证据 | 后果 |
|---|---|---|
| **元数据库明文口令写死在版本库** | `application.yml:8-9`（`username: root` / `password: '123456'`）；`docker-compose.yml:9,30-31`（`MYSQL_ROOT_PASSWORD: 123456`、`SPRING_DATASOURCE_PASSWORD: 123456`） | 生产环境必须改；但配置里**没有 `${ENV:}` 占位符**，运维只能改文件 ⇒ 改文件就意味着配置漂移出仓（或把生产口令提交回来）。 |
| **控制台编码强制 GBK** | `application.yml:23-26`（`console: GBK` / `file: UTF-8`） | 容器基镜像是 Linux（`Dockerfile:17` `eclipse-temurin:21-jre`），`docker logs` 按 UTF-8 解读 ⇒ **所有中文日志在容器里全是乱码**。中文注释、中文事件消息（`SyncEventBus.publish("▶ 任务 ...")`）全部不可读，直接摧毁可运维性。 |
| **`ddl-auto: update`** | `application.yml:10-13`；`docker-compose.yml:32` | Hibernate 在启动时**自动改表**。生产上一次误配（如把 URL 指向正式库）就会静默改结构。契约 D7 要求 `validate` + Flyway。 |
| **Docker 构建走离线模式且本地仓库为空** | `Dockerfile:15`（`RUN mvn package -DskipTests -pl data-sync-server -am -o`） | `maven:3.9-eclipse-temurin-21` 镜像内 `~/.m2` 为空，`-o`（offline）⇒ **依赖无法解析，镜像构建必然失败**。README 却把 `docker-compose up -d` 列为"推荐"部署方式（`README.md:74-88`）。**判定等级：静态分析（本机未验证 Docker），但依赖链是确定的。** 另：`Dockerfile:10` 的 `COPY pom.xml settings.xml* ./` 中 `settings.xml` 被 `.dockerignore:9` 排除，glob 只剩 `pom.xml`。 |
| **仓库内 `settings.xml` 把所有仓库指向本地文件系统** | `settings.xml:10-13`（`<mirrorOf>*</mirrorOf>` → `file://${user.home}/.m2/repository`） | 这是一个**机器本地文件被提交进仓库**。任何干净克隆若无预置 `.m2` 都无法构建；而 README 的快速开始（`README.md:114`）未提 `-s settings.xml`。 |

---

### F11（中）标识符未做白名单，表名/列名直接拼进 SQL

- **现象**：`sourceTable`、`targetTable`、`incrColumn` 都是用户可填的自由文本（`TaskDTO` → `SyncTaskEntity` 无任何校验），随后被字符串拼接进 SQL。
- **触发条件**：恶意或误填的任务配置。例如 `sourceTable = "user WHERE 1=1"` 或 `incrColumn = "id) FROM t; --"`。
- **后果**：MySQL Connector/J 默认不允许 `allowMultiQueries`，所以经典堆叠注入受限；但**子查询/条件注入完全可行**，且 `SyncExecutionService.java:189` 的 `SELECT MAX(<incrColumn>) FROM <sourceTable>` 与 `JdbcBatchWriter.java:33` 的 `TRUNCATE TABLE <table>` 是**破坏性语句**——一次配置错误就可能清空非预期表。`SqlValidator` 只保护 `CUSTOM_SQL` 路径（`data-sync-core/src/main/java/com/datasync/core/job/SqlValidator.java:17-41`），表名/列名路径**完全没有校验**。
- **证据**：`data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:188-191`；`data-sync-core/src/main/java/com/datasync/core/job/writer/JdbcBatchWriter.java:33`；`data-sync-core/src/main/java/com/datasync/core/job/reader/PageReader.java:22`（`"FROM " + table`）；`SqlValidator.java:11-15`（仅对自定义 SQL 生效）。
- **建议**：标识符白名单校验（`^[A-Za-z_][A-Za-z0-9_$]{0,63}$`，可含 `.` 分隔 schema）+ 与元数据比对（表必须真实存在）；所有拼接后的表名在预检阶段用 `DatabaseMetaData` 反查确认。

---

### F12（中）多个端点用"静默降级"掩盖真实失败，运维拿不到可诊断信息

| 位置 | 行为 | 后果 |
|---|---|---|
| `DataSourceController.java:34-38` | `delete` 的 catch **返回 `ResponseEntity.ok()`** | 删除失败也显示"删除成功"（前端 `DataSourceList.vue:70` 直接 `ElMessage.success("删除成功")`） |
| `DataSourceController.java:57-61` | `GET /{id}/tables` 出错返回 `List.of()` | 连接失败/权限不足 → 前端显示"该库没有表" |
| `DataSourceController.java:63-68` | `GET /{id}/columns` 出错返回 `List.of()` | 表名拼错/无权限 → 字段映射编辑器空列表，用户无从判断 |
| `DataSourceService.java:40-44` | `testConnection` 吞掉所有异常返回 `false` | 只告诉用户"连接失败"，不告诉是网络、认证、库不存在还是驱动缺失 |
| `DataSourceController.java:22-26` | `get` 的 catch 返回 404 | 数据库故障被当成"记录不存在" |
| `TaskController.java:59-71` + `SyncExecutionService.java:65` | 触发接口忽略 `CompletableFuture` 返回值，**永远返回 `{"success":true,"message":"任务已触发执行"}`** | 用户点了触发，看到"已触发"；任务在 200 ms 后失败，用户必须自己翻执行历史。异步异常被 `CompletableFuture` 吞掉，**日志里连堆栈都没有**（`executeTask` 的 `catch` 会 `throw new RuntimeException`，但没人 `join`） |

- **证据**：见上表逐条行号。
- **建议**：区分"业务性空结果"与"技术性失败"——技术失败一律返 4xx/5xx + 统一错误码；触发接口返回 `recordId`，前端轮询该记录状态。

---

### F13（中）大表读取存在 OOM 风险：MySQL 下 `setFetchSize(n)` 默认不生效

- **现象**：`IncrementalReader` 与 `CustomSqlReader` 都是 `JdbcCursorItemReader` 且只调用 `setFetchSize(1000)`（正数），但连接 URL 未设置 `useCursorFetch=true`。
- **触发条件**：`INCR`/`FULL_INCR` 任务读大表；或 `CUSTOM_SQL` 模式读复杂 JOIN。
- **后果**：MySQL Connector/J 在未启用服务端游标时，**正数 `fetchSize` 只是提示，驱动会把整个结果集拉进客户端内存**。源表 5000 万行 ⇒ 应用 OOM `OutOfMemoryError`，且是在同步中途，正是 F1（目标表已被清空）最危险的时刻。`JdbcCursorItemReader` 的"流式"承诺在此失效。
- **证据**：`data-sync-core/src/main/java/com/datasync/core/job/reader/IncrementalReader.java:17`（`setFetchSize(1000)`）；`data-sync-core/src/main/java/com/datasync/core/job/reader/CustomSqlReader.java:16`（`setFetchSize(fetchSize)`，值来自 `pageSize`）；连接 URL 无 `useCursorFetch`：`data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:281`、`DataSourceService.java:145,151`。
- **建议**：URL 加 `useCursorFetch=true`（配合 `defaultFetchSize`），或显式 `setFetchSize(Integer.MIN_VALUE)` 启用流式；并在预检里给出"源表行数 vs 允许的最大同步行数"的容量提示。

---

### F14（中）`SyncEventBus` 静态订阅者模型：只增不减、跨任务泄漏、同步阻塞

- **现象**：`SyncEventBus.listeners` 是 `static` 的 `CopyOnWriteArrayList`；`SyncLogWebSocketHandler` 在 `@PostConstruct` 注册一个 lambda 后**永不反注册**；`publish` 在**调用者线程**上遍历并逐个 `sendMessage`。
- **触发条件**：应用重启（同 JVM 内多次 Spring 上下文，如测试）、慢速 WS 客户端、大量并发日志。
- **后果**：
  1. 静态列表在多次上下文加载后累积订阅者 ⇒ 每个事件被重复处理 N 次，且**持有已关闭上下文的引用，ClassLoader 泄漏**。
  2. 消息**不含 taskId**，所有连接看到所有任务的日志 ⇒ **跨任务/跨租户信息泄漏**（配合 F6-d 更严重）。
  3. 同步发送在同步工作线程上：一个卡住的浏览器客户端会让**整个同步任务卡住**（无超时、无异步队列、`catch (Exception ignored)` 把失败也吞了，`SyncLogWebSocketHandler.java:21`）。
- **证据**：`data-sync-core/src/main/java/com/datasync/core/job/SyncEventBus.java:9-13`；`data-sync-server/src/main/java/com/datasync/server/config/SyncLogWebSocketHandler.java:15-23`（无 `@PreDestroy`）；消息体构造处也无 taskId（`SyncExecutionService.java:124-127, 230`）。
- **建议**：契约 §3.7 已把事件总线"弱化为可选"——改造后应由平台侧实现**带 taskId 的、异步、有界队列**的推送，并按订阅过滤；同时清理静态状态。

---

### F15（中）数据一致性陷阱：`FULL_INCR` 无有效增量配置时静默退化为"清空重写"

- **现象**：`isFullSync = (syncMode == FULL) || (syncMode == FULL_INCR && !(hasIncrColumn && hasIncrValue))`（`SyncJobConfig.java:56-60`）。
- **触发条件**：用户建了 `FULL_INCR` 任务但**没配增量列**，或配置了增量列但 `incrValue` 为空且任务再次执行前没有推进成功。
- **后果**：本该"先全量后增量"的任务，**每次都把目标表清空重写**。对 1 亿行表，这是每次执行数小时的全量 + 目标表在整个过程中不可读。用户从 UI 上看不出这个退化——`SyncExecutionService.java:114-121` 只是把描述文字拼成 `"全量同步(未配置增量字段)"` 打进日志，**不做告警、不阻止保存、不要求确认**。契约 D14 明确禁止"增量/幂等模式下 TRUNCATE"。
- **证据**：`data-sync-core/src/main/java/com/datasync/core/job/SyncJobConfig.java:56-60`；`data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:114-121`；前端 `TaskForm.vue:213` 默认 `syncMode: "FULL_INCR"` 且 `incrColumn: ""` ⇒ **默认新建的任务就是这种危险配置**。
- **建议**：保存时校验 `FULL_INCR` 必须有 `incrColumn`，否则拒绝并给出中文提示；确需退化时必须由用户显式勾选"允许清空重写"，并写入 `sync_record` 备注。

---

### F16（中）整实体 `save()` 覆盖并发编辑 + 无乐观锁

- **现象**：`executeTask` 在第 80 行加载 `SyncTaskEntity`，在**第 193-194 行**把整个实体（含执行开始时的所有字段快照）`save()` 回去。
- **触发条件**：任务执行期间（可能数小时），运维在 UI 上修改了该任务（改名、改 cron、改字段映射、禁用）。`SyncTaskService.update`（`SyncTaskService.java:45-70`）没有版本检查。
- **后果**：执行结束时的 `save()` 把用户的全部改动**静默回滚**到执行开始前的状态——包括"禁用任务"这个安全操作。运维会看到"我明明禁用了，它还在跑"。
- **证据**：`data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:80, 193-194`；`data-sync-server/src/main/java/com/datasync/server/service/SyncTaskService.java:45-70`；`data-sync-server/src/main/java/com/datasync/server/entity/SyncTaskEntity.java`（无 `@Version`，实测 `@Version` 在 server 模块零命中）。
- **建议**：加 `@Version`；游标推进改为定向 UPDATE（`UPDATE sync_task SET cursor_value=? WHERE id=? AND cursor_value=?`），不要 `save(整实体)`。

---

### F17（中）仪表盘"成功次数"恒为 0（状态码两套命名体系）

- **现象**：`sync_record.status` 写入的是 **Spring Batch 的状态名**（`execution.getStatus().name()` ⇒ `COMPLETED`/`FAILED`/`STARTED`/`STOPPED`），而仪表盘统计按 **`"SUCCESS"`** 过滤。
- **触发条件**：任意执行。
- **后果**：`successTasks` **永远是 0**。前端仪表盘卡片"成功次数"（`data-sync-web/src/views/Dashboard.vue:54`）恒显示 0，`failedTasks` 正常。运维看到"0 成功 / N 失败"会得出完全相反的结论。同时实体注释、`DatabaseCommentInitializer` 里都写着 `RUNNING/SUCCESS/FAILED/STOPPED`（`SyncRecordEntity.java:29`、`DatabaseCommentInitializer.java:97`），与写入值不符。前端 `STATUS_MAP` 同时收录 `COMPLETED` 和 `SUCCESS`（`data-sync-web/src/types/index.ts:129-130`）——**用兼容映射把后端的不一致糊过去了**，属于掩盖而非修复。
- **证据**：写入 `data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java:154-155`；统计 `data-sync-server/src/main/java/com/datasync/server/controller/DashboardController.java:24`；前端 `data-sync-web/src/views/Dashboard.vue:54`；`data-sync-web/src/types/index.ts:126-134`。
- **建议**：统一为契约 §3.1 `SyncRunResult.status` 的 `COMPLETED/FAILED/CANCELLED`，去掉前端兼容分支；数据库注释同步更新。

---

### F18（低—中）其他确认项

> F18-a ~ F18-g 为低；**F18-h、F18-i 实际影响为「中」**，但因归属"其他确认项"而未单列编号，改造时请勿因分组而漏掉。

| 编号 | 现象 | 证据 | 影响 |
|---|---|---|---|
| F18-a | `data/datasync.mv.db`(49 KB) 与 `data/datasync.trace.db`(51 KB) 被 git 跟踪（H2 MVStore 头 `48 3A 32 2C 62 6C 6F 63 6B ...`），是早期 H2 配置的运行残留 | `git ls-files data` 输出两文件；`.gitignore:27` 的 `data/` 对已跟踪文件无效 | 仓库含二进制运行态文件；trace 里含内部 SQL 与时间戳，**本次评估恰好靠它拿到了 F9 的现场证据** |
| F18-b | `data-sync-web/test.txt` 被跟踪 | `git ls-files data-sync-web` 输出 `data-sync-web/test.txt` | 垃圾文件 |
| F18-c | README/实施计划写 `vite.config.ts`（`plan:236`），实际是 `vite.config.cjs`（CommonJS），且 `package.json` 用 `--config vite.config.cjs` 显式指定 | `data-sync-web/vite.config.cjs:1-17`；`data-sync-web/package.json:7-8` | 文档与实现不符；对 ESM 化的 Vite 6 属于不一致做法 |
| F18-d | `JdbcBatchWriter.BuildInsertSql` 方法名首字母大写（Java 命名规范违背），且 `Dm8BatchWriter` 与 `JdbcBatchWriter` 的唯一差异是构造参数 | `JdbcBatchWriter.java:36`；`Dm8BatchWriter.java:9-14` | 代码规范；可读性 |
| F18-e | `ColumnMappingProcessor` 在目标是 MySQL 时仍被塞入 `MySqlToDm8TypeMapper` 实例 | `SyncJobConfig.java:49-53`（`typeMapper != null ? typeMapper : new MySqlToDm8TypeMapper()` ⇒ 两个分支都得到 DM8 mapper） | 逻辑废话；配合 M13 说明类型转换整体未设计 |
| F18-f | `DatabaseCommentInitializer` 用 `ALTER TABLE ... MODIFY COLUMN` 在启动时给每列重写注释，字符串拼接且带中文注释；仅 MySQL 方言可用 | `DatabaseCommentInitializer.java:18-52`（`:46` 拼接 DDL） | 启动副作用；一旦切到非 MySQL 元数据库会静默报错（catch 成 `debug`） |
| F18-g | `README.md:6` 声明 MIT License 并有徽章，但仓库**没有 LICENSE 文件** | `70474fc` 根目录文件列表与 `git ls-files` 均无 LICENSE | 法务/交付风险 |
| F18-h | `mappingJson` 解析失败直接抛 `RuntimeException("解析字段映射配置失败")`，且抛出点在 `toTaskConfig()` 内、位于 `executeTask` 的 try 块中 ⇒ 被外层 catch 转成"任务执行失败"后**再抛一次**；而手动触发路径是 `supplyAsync`，异常只进 `CompletableFuture`，**调用方不 `join`，日志里连堆栈都没有**（详见 F12） | 抛出：`SyncExecutionService.java:308-316`（尤其 `:314`）；吞掉：`SyncExecutionService.java:64-66`、`TaskController.java:59-71` | 一份写坏的映射 JSON 会让任务**永久静默失败**：UI 显示"已触发"，执行记录短暂 FAILED 后无人知晓根因 |
| F18-i | 执行统计**口径混乱且结构性失准**：① `errorRows` 取自 `step.getSkipCount()`，而 Step **没有配 skip 策略** ⇒ `skipCount` 恒为 0 ⇒ **`error_rows` 永远是 0，即使任务 FAILED**；② `SyncJobListener` 把 `totalWrite==0` 推断成 *"源数据无变更，跳过写入"*、把 `totalWrite==totalRead` 推断成 *"(全量重写)"*，这两个结论**没有任何依据**（增量读 0 行、写入全被丢弃、字段映射错误都会命中前者），却被当成事实播报给用户；③ `sync_record.total_rows` 列在基线下**从未被写入**（全仓库 `setTotalRows` 只有实体与 DTO 的 getter/setter，无调用点），前端类型却声明了 `totalRows` | ① `SyncExecutionService.java:163`（`totalError += step.getSkipCount()`）、`:175`（`record.setErrorRows(totalError)`）、`SyncJobConfig.java:70-76`（无 `.faultTolerant()`/`skipLimit`）；② `SyncJobListener.java:54-60`；③ `SyncRecordEntity.java:34,61`、`SyncRecordDTO.java:12`、`data-sync-web/src/types/index.ts:93` | 执行报告不可信：失败行数恒 0、成功结论靠猜。运维据此判断"数据同步正常"，而实际可能一行都没写 |

---

## 4. 生产就绪差距分析（业界生产级验收维度打分）

打分区间 0–5。**5 = 可直接上生产并具备故障自愈能力；0 = 完全不具备**。基线总分 **8 / 45 ≈ 18%**。

| # | 维度 | 得分 | 扣分依据（证据见第 2、3 节） |
|---|---|---|---|
| 1 | **正确性** | **1** / 5 | 全量先 TRUNCATE 后失败（F1）；游标语义错误导致永久丢数（F2/§2.1 M3）；无幂等写入（F3）；`LIMIT/OFFSET` + 硬编码 `ORDER BY id` ⇒ 并发下重复/漏行、非 `id` 表直接报错（F4）；类型映射在链路上是死代码（M13）；`batchSize` 不生效（M7）。**唯一加分项**：core 有几个真实跑通的单测，前端 `vue-tsc` 干净。 |
| 2 | **幂等 / 可重入** | **1** / 5 | upsert 实现存在但零调用（F3）；重放 ⇒ 主键冲突或重复堆积；"连续执行 3 次行数不变"（验收 4）不成立；`FULL_INCR` 默认配置退化为清空重写（F15）。 |
| 3 | **断点续传** | **0** / 5 | 每次执行带唯一 `timestamp` ⇒ 永远是新 JobInstance，Spring Batch 重启语义**永不可达**（`SyncExecutionService.java:145-148`）；无 checkpoint；游标是"源表全局 MAX"而非"已提交位置"；进程被杀后 `RUNNING` 无人纠正（F8）。契约 §3.3 的 `lastCommittedKey` 没有任何持久化/消费方。 |
| 4 | **可观测性** | **2** / 5 | **有**：WebSocket 实时日志、`sync_record` 三计数、Job/Step 级统计、每 10 chunk 的进度播报、`sync_record.error_message` 提取 Batch 异常。**无**：`/actuator/health`（无 actuator 依赖）、任何指标（Micrometer/Prometheus）、游标当前值在 UI 可见、失败行明细（M4）、结构化日志、traceId；**且**：日志强制 GBK 在容器里乱码（F10）、"成功次数"恒为 0（F17）、"正在重试"是假话（M5）、WS 全量广播无 taskId 过滤（F14）。 |
| 5 | **安全** | **0** / 5 | 无认证（F6-a）；口令明文落库（F6-b）；`GET /api/datasources` 回显明文口令（F6-c）；WS 通配 Origin + 无鉴权 + 全量广播（F6-d）；异常消息直出前端（F6 补充）；标识符注入面（F11）；元数据库口令硬编码在版本库（F10）。**这是一条都不能放过的维度，得 0 分意味着这个系统当前只能存在于隔离内网。** |
| 6 | **并发与资源** | **1** / 5 | `ForkJoinPool.commonPool()` 无界（F5）；同任务零互斥（F5）；Quartz 无 `@DisallowConcurrentExecution`（F5）；每次执行建/销 2 个 Hikari 池 + 元数据路径裸连接（F7）；MySQL 客户端全量缓冲导致 OOM 风险（F13）；**加分项**：Spring Batch chunk 事务边界本身是对的，`connectionTimeout/maxLifetime` 参数设置合理（`SyncExecutionService.java:264-266`）。 |
| 7 | **配置与部署** | **1** / 5 | 无 profile 划分（单份 `application.yml`）；口令硬编码无 `${ENV}` 占位（F10）；`ddl-auto=update` + `initialize-schema=always`（F9/F10）；GBK 控制台编码（F10）；Docker 离线构建大概率失败（F10）；仓库内 `settings.xml` 指向 `file://` 本地仓库（F10）；`pom.xml` 无 web 模块（前端靠 Dockerfile 手工串起来）。**加分项**：多阶段 Dockerfile 结构本身合理。 |
| 8 | **可运维性（升级/回滚/容量）** | **1** / 5 | 无优雅停机（F8）；无健康检查端点；无执行记录/错误记录/Batch 元数据保留策略（F9）；无容量/并发上限配置；无任务取消（M9）；无升级/回滚路径（无 Flyway、无版本化 schema）；启动副作用（`DatabaseCommentInitializer` 启动即 `ALTER TABLE`，F18-f）；无告警/通知集成。**加分项**：`sync_record` + WS 日志让"出事后能翻记录"勉强成立。 |
| 9 | **文档与测试** | **1** / 5 | 文档**体量**充足（README 346 行 + 336 行设计 + 271 行计划，结构清晰），**但** README 至少 4 条能力声明与代码相反（§2.2），设计文档描述的预检/水位表/错误表/重试**一条都没实现**。测试：server 模块 **0 测试**（`src/test` 目录不存在）；core 18 个用例中 1 个失败（`mvn test` 红）；无 Testcontainers、无 MockMvc、无前端测试框架；前端 build 与 `vue-tsc` 通过是**唯一亮点**。 |

### 4.1 一句话结论

**当前基线不具备生产可用性。** 危险之处不在于功能少，而在于**它在"看起来成功"的状态下丢数据**：
`sync_record` 会写 `COMPLETED`、日志会打印 `✓ 增量游标已推进`、README 会告诉你它支持断点续传和幂等 upsert——
而实际上 F1 会清空目标表且无法回滚，F2 会永久跳过一段数据，F3 会让重放直接失败，F17 会让仪表盘把成功显示成 0。

---

## 5. 改造优先级矩阵

**负责人代号**：内核 = 内核工程师（`data-sync-core`）；平台 = 平台工程师（`data-sync-server`）；界面 = 界面工程师（`data-sync-web`）；Lead = 根 POM / 配置文件 / Docker / README。

### P0 — 阻塞生产（不修不能宣布任何可用性）

| ID | 事项 | 对应缺陷 | 负责人 | 契约依据 | 验收方式 |
|---|---|---|---|---|---|
| P0-1 | 预检强制执行（表存在/列兼容/增量字段可排序/目标有主键/排序键唯一性），**预检失败不写任何数据、不清空任何表** | F1, M1, F4 | 内核 + 平台 | D5、D14 | 验收 6；故意配错目标表，确认目标表行数不变 |
| P0-2 | 全量清理改为"预检通过后才执行"，优先 `SWAP`；改用 `DELETE`/`TRUNCATE` 时必须可审计（记录清理前行数与校验和） | F1 | 内核 | D6、D14 | 验收 2；在清理后注入失败，确认原表可用（SWAP）或损失可核对 |
| P0-3 | 游标重写：执行锚定上界、`(lower, upper]` 区间、**水位 = 本次已提交的最大游标值**、`endCursor==null` 或存在未提交 chunk 时禁止推进、推进失败即任务失败 | F2, M3 | 内核 + 平台 | D3 | 验收 3；kill 进程后重跑，逐行比对无丢失 |
| P0-4 | 写入全面改幂等 upsert（MySQL `ON DUPLICATE KEY UPDATE` / DM8 `MERGE INTO`），主键从 JDBC 元数据推断 | F3, M6 | 内核 | D4 | 验收 4；同任务连跑 3 次行数不变 |
| P0-5 | 键集分页替换 `LIMIT/OFFSET`；排序列取 `orderColumn` → 主键推断，**禁止硬编码 `id`** | F4, M8 | 内核 | D2 | 并发写入源表时同步，比对结果集无重复无缺失；在无 `id` 列的表上跑通 |
| P0-6 | 每任务最多 1 个运行中实例（DB 唯一约束 + 应用锁）；有界线程池替换 `commonPool`；Quartz 加 `@DisallowConcurrentExecution` | F5 | 平台 | D11 | 验收 5；并发触发 5 次只有 1 个 RUNNING |
| P0-7 | 认证与凭据：AES-GCM 加密落库、响应脱敏、Spring Security 表单登录 + CSRF、`/actuator/health` 白名单 | F6-a/b/c, F10 | 平台 + Lead | D9、D10 | 验收 8、9；库内 `ENC(` 前缀、日志全文无明文、未登录 401/302 |
| P0-8 | WebSocket 鉴权 + 按 taskId 过滤订阅 + 异步有界推送 | F6-d, F14 | 平台 | D10 | 未登录不能建立连接；A 任务日志不出现在 B 的订阅里 |
| P0-9 | 标识符白名单 + 元数据反查（表名/列名不得自由拼接） | F11 | 内核 + 平台 | D5 | 注入样例被拒并给出中文错误码 |

### P1 — 上线前必须完成

| ID | 事项 | 对应缺陷 | 负责人 | 契约依据 |
|---|---|---|---|---|
| P1-1 | `batchSize` 真正决定 chunk 提交行数，且约束 `batchSize <= pageSize` | F-M7 | 内核 | §3.1 |
| P1-2 | 持久化目标连接池（`ConnectionPoolRegistry`），配置变更失效、关闭统一销毁 | F7 | 平台 | D12 |
| P1-3 | 失败行落 `sync_error` + 可配置重试/跳过策略；**删除"正在重试"的假日志** | M4, M5 | 内核 + 平台 | D8 |
| P1-4 | 手动触发返回 `recordId` 并落可查状态；异步异常必须进日志（不能只塞进没人 `join` 的 Future） | F12 | 平台 | §4.3 |
| P1-5 | 取消端点 `POST /api/tasks/{id}/cancel` + 引擎协作式取消 | M9 | 平台 + 内核 | §3.3、§4.3 |
| P1-6 | 优雅停机 + 启动对账（僵尸 `RUNNING` → `CANCELLED`） | F8 | 平台 + Lead | 验收 10 |
| P1-7 | 保留策略：执行记录 / 错误记录 / Batch 元数据定期清理 | F9 | 平台 | `app.retention.*` |
| P1-8 | 元数据库 schema 交 Flyway，`ddl-auto=validate`，`initialize-schema=never` | F9, F10 | 平台 + Lead | D7 |
| P1-9 | 增量读补上界 + `safetyLag` + `lookback` | F2 | 内核 | D3 |
| P1-10 | 大表读取内存安全：`useCursorFetch=true` 或显式流式 fetch | F13 | 内核 + 平台 | — |
| P1-11 | 日志编码按环境配置（容器 UTF-8），profile 化配置分离 | F10 | Lead | — |
| P1-12 | 异常消息消毒（去 JDBC URL/用户名）+ 统一错误响应体 `{timestamp,status,code,message,details}` | F6 补充、F12 | 平台 | §4.3 |
| P1-13 | 修 `FULL_INCR` 静默退化：保存时校验 + 显式确认 + 记录备注 | F15 | 平台 + 界面 | D14 |
| P1-14 | 加 `@Version`，游标改定向 UPDATE | F16 | 平台 | — |
| P1-15 | 状态码统一为 `COMPLETED/FAILED/CANCELLED`，去掉前端兼容分支 | F17 | 平台 + 界面 | §3.1 |
| P1-16 | 测试补齐：server `src/test` + MockMvc、core 修红、前端 Vitest；`mvn test` 必须全绿 | M10、§0.2 | 全员 | 验收 12 |
| P1-17 | `/api/tasks/{id}/preflight`、`/api/records/{id}/errors`、`/api/system/info` 三个新端点 | M1, M4 | 平台 | §4.3 |
| P1-18 | 统计口径修正：`errorRows` 改由 `SyncRunResult.skippedRows`/`errors` 驱动（不再读 `skipCount`）；删除 `SyncJobListener` 的两处无依据结论；`total_rows` 要么写入要么删列 | F18-i | 内核 + 平台 + 界面 | §3.1、§3.3 |
| P1-19 | 配置/映射类校验前置到**保存时**（映射 JSON 解析、`FULL_INCR` 必填增量列、表/列存在性），错误码回给 UI，不再让坏配置在运行期炸 | F18-h, F15 | 平台 | D5 |

### P2 — 可迭代（不阻塞上线，但要在第一个小版本内清掉）

| ID | 事项 | 对应缺陷 | 负责人 |
|---|---|---|---|
| P2-1 | `SyncEventBus` 静态状态清理（`@PreDestroy` 反注册、去掉静态列表） | F14 | 内核 |
| P2-2 | Dockerfile 去掉 `-o` 离线构建；`settings.xml` 从仓库移除或改为可选模板 | F10 | Lead |
| P2-3 | 移除被跟踪的 `data/*.db` 遗留 H2 文件 | F18-a | Lead |
| P2-4 | 删除 `data-sync-web/test.txt` | F18-b | 界面 |
| P2-5 | `vite.config.cjs` → 统一为文档描述的 `vite.config.ts` | F18-c | 界面 |
| P2-6 | 方法命名 `BuildInsertSql` → `buildInsertSql`；`Dm8BatchWriter` 要么实现 DM8 特化要么删除 | F18-d, M15 | 内核 |
| P2-7 | `DatabaseCommentInitializer` 改为 Flyway 迁移里的 `COMMENT`，取消启动副作用 | F18-f | 平台 + Lead |
| P2-8 | 重写 README 能力声明（删除/修正 4 条虚假能力），补 LICENSE 文件 | §2.2, F18-g | Lead |
| P2-9 | 前端补测试脚本与 `typecheck` 脚本，纳入 CI | M10 | 界面 |
| P2-10 | 引入 `/actuator/health` + Micrometer 指标（同步耗时、读写行数、游标滞后秒数） | §4 维度 4 | 平台 |

### 5.1 建议的执行顺序（依赖关系）

```
P0-1 预检 ──┬─→ P0-2 安全清理
            ├─→ P0-5 键集分页
            └─→ P0-9 标识符校验
P0-3 游标 ──┴─→ P0-4 幂等 upsert（二者必须同时上线，否则重放必冲突）
P0-6 互斥/线程池 ──→ P1-2 连接池注册表
P0-7 安全 ──→ P1-12 异常消毒 ──→ P1-16 测试补齐
```

⚠️ **P0-3 与 P0-4 必须同批上线**：只修游标不修幂等，重放会立刻撞主键冲突；只修幂等不修游标，丢数依旧。

---

## 6. 对冻结决策的异议

以下 5 条是对 `docs/production-readiness/00-frozen-contract.md` 的**实质性质疑**，均针对契约自身的缺口或不闭合之处，不针对任何具体实现。
**本报告不修改契约文件**，提交 Lead 裁决。

### 异议 A（最重要）：D2「一律键集分页」缺少配套的**排序键唯一性**约束，会引入一类新的静默漏行

- **契约现状**：D2 禁止 `LIMIT/OFFSET`，§3.1 允许 `orderColumn` 为 `null` 时"自动推断"，§3.4 的 `buildKeysetSelect(..., List<String> keyColumns, KeysetPosition after, ...)` 只接收一组排序列；§3.6 的 11 个稳定错误码里**没有"排序键不唯一"这一项**。
- **问题**：键集分页的正确性前提是**排序列组合唯一**（或至少单调且无重复）。若推断出的是 `updated_at` 这类**非唯一**时间戳列，纯 `WHERE (k) > (?)` 会**把同值的其余行整批跳过**——这与 D2 要解决的 OFFSET 重复/漏行是**同一量级的静默丢数**，只是从"跳页"变成"跳同值组"。现状 `IncrementalReader` 的 SQL 已经暴露了这个模式：增量读只按 `(incrColumn, orderBy)` 排序而**没有把主键拼成 tie-breaker**（`SqlBuilder.java:17`）。
- **建议**：把「排序键唯一性」提升为强制预检项，新增错误码（如 `ORDER_KEY_NOT_UNIQUE`）；`buildKeysetSelect`/`buildWatermarkSelect` 的 `keyColumns` **必须包含目标表主键或源表主键**作为末位 tie-breaker，并由预检强制保证。请 Lead 裁决是否写入契约 §3.5/§3.6。

### 异议 B：D3「游标只在整个任务全部 chunk 成功后推进一次」在大表场景下会把失败代价放大到不可接受，且契约自身的 `lastCommittedKey` 无人消费

- **契约现状**：D3 规定"游标只在整个任务全部 chunk 成功后推进一次"；同时在 §3.1 的 `SyncRunResult` 里定义了 `lastCommittedKey`（"最后提交行的 order key，用于**断点续跑**"，`00-frozen-contract.md:148`），在 §3.3 的 `ChunkProgress` 里定义了 `cursor()`（`00-frozen-contract.md:211`）。
- **问题**：契约**既要求"整体成功才推进"，又定义了"用于断点续跑的 chunk 级检查点"，但没有一条决策说这个检查点由谁持久化、谁消费、重启时如何恢复。契约在这一处不闭合。**同时**，整表级推进在大表上代价极高：1 亿行表跑 5 小时后失败，全部重来；而契约自己说"重放靠幂等 upsert 保证安全"——既然重放安全，就没有理由拒绝 chunk 级水位。
- **建议**：二选一并写入契约：**(a)** 保留"整体推进"，则删除 `lastCommittedKey`/`cursor()` 或明确标注为"仅用于日志"，避免实现者误解；**(b)** 改为"chunk 级水位持久化 + `lookback` 重放"，则需明确水位表（是否新建 `sync_watermark`）与 `lastCommittedKey` 的持久化位置。我倾向 (b)，并建议同时恢复原设计 §3.3 的 `sync_watermark` 表以支持"水位历史/回溯"。请 Lead 裁决。

### 异议 C：D12「每任务持久化目标连接池」缺少**失效触发点**的定义，在 D9 落地后必然踩坑

- **契约现状**：§4.1 只说 `ConnectionPoolRegistry` "按数据源 ID 缓存 Hikari 池，**配置变更时失效重建**，应用关闭时统一销毁"。
- **问题**："配置变更"的**触发机制没有定义**：是 `DataSourceService.update()` 主动调用 `registry.evict(id)`，还是靠 TTL 探测？两种实现在测试里都"看起来对"，但后者在**口令轮换**场景下会让同步任务持续用旧凭据连库直到 TTL 到期（可能 30 分钟），表现为"改了密码还是认证失败"。配合 D9（口令 `ENC(` 加密 + 首次读取自动升级历史明文），失效点变得更加关键：**读路径本身会写库**（把明文升级成密文），如果池化对象缓存了旧的 DTO，升级逻辑可能被反复触发或用错值。
- **建议**：把「`DataSourceService.create/update/delete` 必须同步调用 `registry.evict(id)` 并纳入验收」明确写入契约 §4.1；同时在 §5 增加一条验收项："修改数据源口令后，下一次执行必须使用新口令（不等待任何 TTL）"。

### 异议 D：契约缺少「目标表不得位于元数据库」的硬约束，D6 的 `TRUNCATE` 可以抹掉平台自身元数据

- **契约现状**：D6 允许全量同步默认 `TRUNCATE`；D7 只说元数据库用 MySQL + Flyway。**没有任何一条决策禁止把 `datasync` 库里的 `datasource` / `sync_task` / `sync_record` 表配置成同步目标表。**
- **问题**：这不是理论风险。基线配置里元数据库就是 `127.0.0.1:3306/datasync`（`application.yml:6`），而客户环境里"源库=元数据库所在实例"极其常见；如果运维在 UI 上把目标数据源选成平台自己的库、目标表填成 `datasource`，一次 FULL 同步就会**清空全部数据源配置（含口令）**，平台随即不可用。§3.6 的 11 个错误码里没有这一项。
- **建议**：新增预检 ERROR 码（如 `TARGET_IS_METADATA`）：目标数据源 → 库 → 表组合命中元数据库时**直接拒绝**；同时禁止目标表名匹配 `flyway_schema_history` / `BATCH_*` / `sync_*` / `datasource` / `QRTZ_*` 前缀。

### 异议 E：D13 只声明了 DM8「未验证」，但没有堵住 MySQL→MySQL 路径上的**值转换责任真空**

- **契约现状**：§3.5 定义了 `ValueConverter`/`DefaultValueConverter`（"处理 JSR-310、byte[]、BitSet、BigDecimal 等"）与 `MySqlToMySqlTypeMapper`，但**没有一条决策说值转换发生在哪个组件**（Processor 还是 Writer），也没有说 Writer 是否允许直接 `setObject`。
- **问题**：基线正好落在真空里——`ColumnMappingProcessor` 持有 `TypeMapper` 却从不调用（M13），`JdbcBatchWriter` 直接 `ps.setObject(j+1, row.get(col))`（`JdbcBatchWriter.java:66`）。**结果连"MySQL→MySQL"这条生产主线也没有值转换保障**：MySQL `TINYINT(1)` 的 `Boolean`、`BIT(n)` 的 `byte[]`/`BitSet`、`JSON` 列的 `String`、`TIMESTAMP` 的 `LocalDateTime` 在跨驱动版本、跨 `serverTimezone` 配置时都可能写出错误值或直接抛 `SQLException`。D13 把注意力引向 DM8，反而掩盖了主线上的同类风险。
- **建议**：在契约 §3.3/§3.5 明确「**值转换在 Processor 内完成，Writer 只负责 `setObject`**」，并要求 `DefaultValueConverter` 覆盖 MySQL→MySQL 的 `TINYINT(1)`/`BIT`/`JSON`/`TIMESTAMP` 四类；在 §5 增加验收项："MySQL→MySQL 同步 `TINYINT(1)`/`BIT(8)`/`JSON`/`DATETIME(6)` 四种列类型，逐值比对一致"。

---

## 7. 附录：证据索引与复核方式

### 7.1 复现本报告的全部证据

```powershell
# 1. 取基线快照（不触碰工作区）
cd "D:\AI\DeepSeek Harness\data-sync"
git archive --format=zip -o .dsh-scratch\architect\baseline.zip 70474fc
Expand-Archive .dsh-scratch\architect\baseline.zip .dsh-scratch\architect\baseline -Force

# 2. 构建（期望 BUILD SUCCESS）
cd .dsh-scratch\architect\baseline
& "S:\installationFree\apache-maven-3.9.15\bin\mvn.cmd" -B `
  -s "D:\AI\DeepSeek Harness\jc\project\.dsh-probe\settings.xml" -DskipTests package

# 3. 测试（期望 BUILD FAILURE，Tests run: 18, Failures: 1）
& "S:\installationFree\apache-maven-3.9.15\bin\mvn.cmd" -B `
  -s "D:\AI\DeepSeek Harness\jc\project\.dsh-probe\settings.xml" test

# 4. 前端（期望 build 成功、vue-tsc 退出码 0）
cd data-sync-web
cmd /c mklink /J node_modules "D:\AI\DeepSeek Harness\data-sync\data-sync-web\node_modules"
& "S:\Program Files\nodejs\npm.cmd" run build
& "S:\Program Files\nodejs\node.exe" ..\..\..\..\data-sync-web\node_modules\vue-tsc\bin\vue-tsc.js --noEmit; $LASTEXITCODE
```

### 7.2 关键证据文件清单（正文引用密度最高）

| 文件 | 承载的结论 |
|---|---|
| `data-sync-server/src/main/java/com/datasync/server/service/SyncExecutionService.java` | F1/F2/F5/F7/F8/F16、M3、F17 的写入侧 |
| `data-sync-core/src/main/java/com/datasync/core/job/SyncJobConfig.java` | M1/M7/M8、F1/F4/F13/F15 |
| `data-sync-core/src/main/java/com/datasync/core/job/writer/JdbcBatchWriter.java` | F1/F3、M6 |
| `data-sync-core/src/main/java/com/datasync/core/job/SqlBuilder.java` | F3/F4、M6/M8 |
| `data-sync-core/src/main/java/com/datasync/core/job/processor/ColumnMappingProcessor.java` | M13、§0.2 红测试 |
| `data-sync-server/src/main/java/com/datasync/server/controller/*.java` | F6-c、F10 子项、F12、F17 |
| `data-sync-server/src/main/resources/application.yml` | F8/F9/F10 |
| `data/datasync.trace.db` | F9 的**现场证据**（真实运行留下的 H2 异常） |
| `README.md` / `docs/superpowers/specs/2026-06-06-database-sync-tool-design.md` | §1 需求基线、§2.2 虚假声明 |

### 7.3 评估者声明

- 本报告产出于 `docs/production-readiness/01-assessment.md`，**未修改任何源码、未修改冻结契约、未执行 `git commit`**。
- 评估期间工作区被其他成员并行修改（实测：`application-dev.yml` / `application-prod.yml` / `db/migration/V1,V2` / `ErrorPolicy.java` / `SyncRunResult.java` / `preflight.ts` / `csrf.ts` 等已出现，而这些文件在 `70474fc` 中并不存在）。**本报告的全部结论仅对 `70474fc` 成立**，不代表改造后的状态。
- 未执行真实 MySQL 端到端验证（契约 §5 验收项 2/3/4/5/7/10），相关结论均为代码级判定，已在正文逐条标注证据等级。Docker 构建相关结论为静态分析。
