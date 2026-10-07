# DataSync — 异构数据库数据同步平台

针对 **MySQL → MySQL**（生产主线）与 **MySQL → 达梦 DM8**（结构完整、未验证）的批量数据同步平台，
提供 Web 管理界面、定时调度、断点续传与完整的执行审计。

> **本 README 描述的是当前代码的实际能力，不是设计意图。**
> 哪些能力经过真机验证、哪些只做了单测或静态评审，见
> [`docs/production-readiness/04-acceptance-report.md`](docs/production-readiness/04-acceptance-report.md)。
>
> 改造前的严重问题清单与生产就绪差距分析见
> [`docs/production-readiness/01-assessment.md`](docs/production-readiness/01-assessment.md)；
> 冻结的工程决策与接口契约见
> [`docs/production-readiness/00-frozen-contract.md`](docs/production-readiness/00-frozen-contract.md)。

---

## 1. 它解决什么问题

把 A 库的一张表（或一段自定义 SELECT 的结果）持续、可重复地搬到 B 库，并保证：

| 保证 | 实现方式 |
|---|---|
| **不丢** | 增量水位只在**整个任务成功提交后**推进一次；读范围取「执行锚点 − 安全滞后」为上界，规避未提交事务 |
| **不重** | 写入一律走幂等 upsert（MySQL `INSERT ... ON DUPLICATE KEY UPDATE`），重放、重跑、断点续跑都安全 |
| **不卡** | 全量同步用**键集分页**（`WHERE key > ? ORDER BY key LIMIT n`），不用 `LIMIT/OFFSET`，源库并发写入不会导致重复或漏行 |
| **可查** | 每次执行记录读取/写入/跳过/失败行数、水位起止、阶段耗时；坏行明细落 `sync_error` 表 |
| **可控** | 每任务同一时刻只允许一个实例在跑（应用锁 + 数据库唯一索引双重保障），可随时取消 |
| **可诊断** | 执行前预检：表/列/类型/主键/索引/权限问题在**写入任何数据之前**以稳定错误码暴露，并给出中文修复建议 |

---

## 2. 快速开始

### 2.1 Docker Compose（推荐）

```bash
cp .env.example .env
# 编辑 .env，至少要改这三项：
#   DATASYNC_DB_PASSWORD      元数据库 root 口令
#   DATASYNC_ADMIN_PASSWORD   管理界面登录口令
#   DATASYNC_SECRET_KEY       数据源口令的加密密钥（≥32 字符）
docker compose up -d
```

访问 `http://localhost:8080`，用 `.env` 里的 `DATASYNC_ADMIN_USER` / `DATASYNC_ADMIN_PASSWORD` 登录。

> ⚠️ 应用以 `prod` profile 启动，**配置缺失会直接启动失败**而不是使用默认口令——这是刻意设计。
>
> ⚠️ 本机开发环境没有 Docker，Docker 路径**未经实测**，属"交付但未验证"。

### 2.2 本地开发运行

前置条件：**JDK 21+**（本机为 JDK 25）、Maven 3.9+、Node 22+、**MySQL 8.0**（元数据库，必需）。

```bash
# 1) 建元数据库（Flyway 会自动建表，这里只需要一个空库）
mysql -uroot -p -e "CREATE DATABASE datasync DEFAULT CHARACTER SET utf8mb4;"

# 2) 构建后端
mvn -B -DskipTests package
java -jar data-sync-server/target/data-sync-server-1.0.0-SNAPSHOT.jar \
     --spring.profiles.active=dev

# 3) 前端（开发模式，Vite 会把 /api 与 /ws 代理到 8080）
cd data-sync-web
npm install
npm run dev        # http://localhost:5173
```

`dev` profile 会关闭登录认证，方便本地调接口；生产**必须**用 `prod`。

### 2.3 部署生产

```bash
mvn -B -DskipTests package
SPRING_PROFILES_ACTIVE=prod \
DATASYNC_DB_HOST=... DATASYNC_DB_NAME=... DATASYNC_DB_USER=... DATASYNC_DB_PASSWORD=... \
DATASYNC_ADMIN_PASSWORD=... DATASYNC_SECRET_KEY=... \
java -jar data-sync-server/target/data-sync-server-1.0.0-SNAPSHOT.jar
```

必需的环境变量见 `application-prod.yml`；缺任何一个都会启动失败并明确指出缺的是哪个。

> **关于"启动失败"的实现**：`application-prod.yml` 里的 `${VAR:?TOKEN}` 语法**不足以保证 fail-fast**
> ——实测本环境下它不会抛异常，而是把提示文本当成值拼进 JDBC URL，最后抛一个看不懂的
> `Malformed database URL, failed to parse the connection string near ':3306/?MISSING_ENV_...'`。
> 因此真正的校验由 `ProductionConfigValidator`（`BeanFactoryPostProcessor`）承担，
> 在**任何 Bean 实例化之前**运行，把缺的变量逐条列出。启动失败时的输出形如：
> ```
> 生产环境配置校验未通过，拒绝启动。共 6 项问题：
>   1) DATASYNC_DB_HOST 未配置（元数据库主机）
>   2) DATASYNC_DB_NAME 未配置（元数据库名）
>   ...
> 请参考项目根目录的 .env.example 补齐这些环境变量后重新启动。
> ```
> 该文件注释里记录了「为什么必须是 `BeanFactoryPostProcessor`」「为什么必须用 `EnvironmentAware`
> 而不是构造器注入」两次实测教训，改动前请先读。

---

## 3. 使用指南

### 第一步：配置数据源
**数据源管理 → 新增数据源**，填类型（MySQL / DM8）、主机、端口、库名、账号口令 → **测试连接** → 保存。

口令以 AES-GCM 密文落库（`ENC(` 前缀），接口返回一律脱敏，日志不打印明文。
编辑时口令框留空表示「不修改」。

### 第二步：配置同步任务
**同步任务 → 新增任务**：

| 配置项 | 说明 |
|---|---|
| 源/目标数据源与表 | 支持 `TABLE` 直连表，或 `CUSTOM_SQL` 用一段 SELECT 当数据源 |
| 同步模式 | `FULL` 全量 / `INCR` 增量 / `FULL_INCR` 首次全量后续增量 |
| 增量字段 | 必须是**单调**列（自增数值或时间戳）。建议建索引 |
| 排序列（可选） | 键集分页的排序键；留空按「增量列 → 主键」自动推断，末尾自动追加主键做 tie-breaker |
| 安全滞后 / 回看窗口 | 增量读的上界滞后与下界回看，用于规避未提交事务与迟到写入 |
| 全量策略 | `TRUNCATE`（默认，最快）/ `DELETE`（可回滚但慢）/ `SWAP`（暂存表 + RENAME，同步期间目标表可读） |
| 错误策略 | 重试次数与退避、是否跳过坏行、坏行数上限 |
| 页大小 / 批大小 | 单次抓取行数与 JDBC 批量提交行数 |
| Cron | Quartz 表达式，留空表示只手动触发 |

### 第三步：预检
点 **预检**。它会检查源/目标表是否存在、列映射是否有效、类型是否兼容、目标是否有主键、
增量字段是否可排序/有索引、目标是否为平台自身的元数据库……**任何 ERROR 都必须在写入数据之前解决**。

有 ERROR 时「执行」按钮会被禁用。

### 第四步：执行与观察
- **手动执行**：后台异步执行，立即返回；同一任务重复点击只会有一个实例在跑，其余被拒绝并提示。
- **取消**：运行中可取消；取消的执行**不会推进水位**，下次从上次成功的位置重来。
- **执行历史**：状态、读取/写入/跳过/失败行数、水位起止、阶段耗时、错误信息。
- **坏行明细**：跳过模式下每条坏行的阶段、主键、原因、原始行数据（截断）。
- **实时日志**：WebSocket 推送，按任务隔离，断线自动重连。

---

## 4. 架构

```
┌───────────────────────────────────────────────────────┐
│  data-sync-web    Vue 3 + Element Plus + Vite          │
│  数据源 / 任务 / 字段映射 / 预检 / 执行历史 / 实时日志   │
└───────────────────────┬───────────────────────────────┘
                        │ REST + WebSocket（表单登录 + CSRF）
┌───────────────────────┴───────────────────────────────┐
│  data-sync-server   Spring Boot 4                      │
│  ┌──────────────┐ ┌──────────────┐ ┌────────────────┐ │
│  │ 数据源管理    │ │ 任务与调度    │ │ 执行编排        │ │
│  │ 口令加密      │ │ Quartz(JDBC) │ │ 每任务互斥      │ │
│  │ 连接池注册表  │ │ 启动重注册    │ │ 有界线程池/取消 │ │
│  └──────────────┘ └──────────────┘ └────────────────┘ │
│  元数据持久化：JPA + Flyway（Schema 唯一事实源）        │
└───────────────────────┬───────────────────────────────┘
                        │ SyncEngine.run(cfg, src, dst, listener)
┌───────────────────────┴───────────────────────────────┐
│  data-sync-core    纯 JDBC 同步引擎（无 Spring 依赖）    │
│  预检 Preflight → 键集分页读取 → 值/类型转换 → 幂等 upsert │
│  SqlDialect: MySqlDialect / Dm8Dialect（方言隔离）       │
└───────────────────────┬───────────────────────────────┘
                        │ JDBC
              ┌─────────┴──────────┐
              │ 源库 MySQL          │
              │ 目标库 MySQL / DM8  │
              └────────────────────┘
```

### 为什么不用 Spring Batch
改造前用 Spring Batch 承载同步，代价是：11 张 `BATCH_*` 元数据表、Job 实例随每次执行膨胀、
真正需要的「裁剪式分页 / 断点续传 / 幂等 upsert / 对账」全都要绕开框架自己写，
而框架提供的「chunk 事务 / 重试 / 跳过」又必须在关键路径上被替换。
现在的引擎是**面向这个问题的直写实现**：一个 chunk 一个事务，提交成功才回调进度，
重试只在 chunk 内做，水位只在整任务成功后推进。

---

## 5. 项目结构

```
data-sync/
├── pom.xml                        # 聚合 POM（JDK 21 字节码 / Spring Boot 4.1.1）
├── Dockerfile / docker-compose.yml / .env.example
├── docs/production-readiness/     # 生产化改造的契约、评估、验收文档
│
├── data-sync-core/                # 同步引擎（不依赖 Spring）
│   └── src/main/java/com/datasync/core/
│       ├── engine/                # SyncEngine / DefaultSyncEngine / 进度回调
│       ├── preflight/             # 预检、错误码、元数据库防护
│       ├── dialect/               # SqlDialect / MySqlDialect / Dm8Dialect
│       ├── jdbc/                  # 表元数据读取、键集位置
│       ├── mapper/                # 类型映射与值转换
│       ├── model/                 # 任务配置、字段映射、执行结果、错误策略
│       └── job/SyncEventBus       # 轻量事件总线
│
├── data-sync-server/              # 后端服务
│   └── src/main/
│       ├── java/com/datasync/server/
│       │   ├── controller/        # REST API
│       │   ├── service/           # 数据源/任务/执行编排/记录落库
│       │   ├── repository/ entity/# JPA 持久化
│       │   ├── config/            # 安全、WebSocket、Quartz、引擎装配
│       │   └── job/               # Quartz Job
│       └── resources/
│           ├── application*.yml   # dev / prod 配置
│           └── db/migration/      # Flyway V1 基线 / V2 升级 / V3 Quartz
│
└── data-sync-web/                 # 前端
    └── src/{api,views,components,composables,router,types,styles}
```

---

## 6. REST API

所有 `/api/**` 需要登录（会话 Cookie + CSRF Token）；`/actuator/health` 匿名可读。

### 认证
| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/auth/csrf` | 取 CSRF Token |
| `GET` | `/api/auth/session` | 当前会话状态 |
| `POST` | `/api/auth/login` | 登录 |
| `POST` | `/api/auth/logout` | 退出 |

### 数据源
| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/datasources` | 分页列表（口令脱敏） |
| `GET/POST/PUT/DELETE` | `/api/datasources[/{id}]` | 增删改查 |
| `POST` | `/api/datasources/{id}/test` | 测试连接 |
| `GET` | `/api/datasources/{id}/tables` | 表列表 |
| `GET` | `/api/datasources/{id}/tables/{table}/columns` | 列信息 |

### 同步任务
| 方法 | 路径 | 说明 |
|---|---|---|
| `GET/POST/PUT/DELETE` | `/api/tasks[/{id}]` | 增删改查 |
| `GET` | `/api/tasks/{id}/preflight` | **预检**，返回 `{hasError, issues[]}` |
| `POST` | `/api/tasks/{id}/trigger` | 手动触发 |
| `POST` | `/api/tasks/{id}/cancel` | 取消运行中的任务 |
| `POST` | `/api/tasks/{id}/enable` \| `/disable` | 启停调度 |
| `PUT` | `/api/tasks/{id}/schedule` | 更新 Cron |
| `GET` | `/api/tasks/{id}/columns` | 源/目标列信息 |
| `GET` | `/api/tasks/{id}/records` | 执行历史（分页） |

### 执行记录与系统
| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/api/records/{id}` | 单次执行详情 |
| `GET` | `/api/records/{id}/errors` | 坏行明细分页 |
| `GET` | `/api/dashboard/overview` | 统计概览 |
| `GET` | `/api/dashboard/recent-fails` | 最近失败 |
| `GET` | `/api/system/info` | 版本、DM8 验证状态、线程池与连接池快照 |
| `GET` | `/actuator/health` | 健康检查（匿名） |

统一错误响应体：

```json
{ "timestamp": "...", "status": 400, "code": "INCR_COLUMN_MISSING",
  "message": "中文消息", "details": ["..."] }
```

### WebSocket
`/ws/logs` —— 实时同步日志，按任务/执行实例打标签，需登录。

---

## 7. 运维要点

### 升级与回滚
- 数据库结构由 **Flyway** 管理：启动时自动执行 `db/migration` 下未应用的迁移，**只前滚不回滚**。
- 升级路径已收敛验证：全新库执行 V1/V2/V3，旧库（历史上由 `ddl-auto=update` 建表）
  会被基线化为版本 1 后执行 V2/V3，两条路径的最终结构**列序、类型、可空性、默认值、注释完全一致**。
- 回滚 = 部署旧 JAR。因为迁移**只增列不删列**，旧版本代码可以继续在新结构上运行。
- **不要**把 `ddl-auto` 改回 `update`：那会让 Hibernate 在生产库上悄悄改表结构。

### 备份
元数据库里有全部任务配置与执行审计，其中 `datasource.password` 是密文，
**`DATASYNC_SECRET_KEY` 必须与数据库一起备份**——丢了密钥，所有数据源口令都无法解密。

### 容量与清理
- `app.retention.record-days` / `error-days` 控制执行记录与坏行明细的保留天数，默认 30 / 7 天。
- 执行记录表增长最快的是高频定时任务；建议按 `record-days` 观察后再调整。
- `app.sync.worker-threads` 决定并发同步数；每个数据源连接池默认 8 连接，按目标库承载能力调整。

### 监控
- `/actuator/health` 已接入容器健康检查（`HEALTHCHECK`）。
- 关注指标：执行失败率、`skipped_rows` 突增、单次执行耗时趋势、`sync_task` 落后（`cursor_value` 长时间不前进）。
- **实时日志的观测口径**（`GET /api/system/info`）：
  - 判断"前端是否跟不上"看 **`websocketDroppedMessages`**（> 0 说明有慢客户端被丢弃中间态，**同步主循环不受影响**）；
  - **`logPeakQueueDepth` / `logQueueDepth`** 反映"事件总线 → WebSocket"之间的背压水位，任何负载下都有意义；
  - **`logDroppedMessages` 在正常负载下恒为 0 是预期行为**，不是指标坏了——上游的
    `AsyncRunMetricsListener`（64 容量、丢最旧）已经替它挡掉了压力，它只在极端背压下才非 0。
  - 两个队列上限可用 `-Ddatasync.log.queue-capacity=2000` / `-Ddatasync.ws.session-queue-capacity=256`
    调整（非法值回落默认值并打 WARN）。**系统属性必须写在 `-jar` 之前**，否则会被当成程序参数、静默失效
    ——"开关不起作用"和"参数位置放错"从外部看完全一样。

### 性能与容量

引擎的写入**不依赖 JDBC URL 参数**：MySQL 方言直接生成多行 `VALUES (?,?),(?,?),…`（按占位符上限
自动切分，且**切分仍在同一事务内**，chunk 原子性不变）。这一点是刻意设计——如果把性能押在连接串的
`rewriteBatchedStatements=true` 上，任何一处漏配都会让 `addBatch` 退化成逐行往返。

实测（本机 MySQL 8.0.46，1000 行/chunk）：

| 写法 | 1000 行耗时 |
|---|---|
| `executeBatch`，连接**不带** `rewriteBatchedStatements` | 219–254 ms |
| `executeBatch`，连接带该参数 | 28–31 ms |
| **多行 `VALUES` 单语句（本项目采用）** | **32–44 ms** |

端到端参考值（目标表已有数据）：增量同步约 **13,300 行/秒**，全量 10 万行约 **3 秒**。

**增量列没有索引会显著变慢**（这是真实风险，不只是"建议"）：

| 增量列索引 | 客户端每页 | 服务端 `EXPLAIN ANALYZE` |
|---|---|---|
| 有索引 | 85.7 ms | 2.84 ms |
| 无索引（100 万行全表扫描 + Sort） | 360.1 ms | 303 ms |

预检会对无索引的增量列给 `INCR_COLUMN_NO_INDEX` 告警，引擎在运行开始时也会再 WARN 一次。

**键集谓词必须用"展开式 OR"，不能用行构造器**（这是 D1 的根因，值得单独记一笔）：

```sql
-- ❌ 错误：行构造器不会下推成索引范围访问
WHERE updated_at <= ? AND (updated_at, id) > (?, ?)
-- ✅ 正确：展开式 OR，优化器能精确定位到游标
WHERE updated_at <= ? AND ((updated_at > ?) OR (updated_at = ? AND id > ?))
```

MySQL 8.0.46 实测（100 万行表，索引 `idx_upd(updated_at, id)`）：

| 形态 | 执行计划关键行 | 每页耗时（3 次） |
|---|---|---|
| 行构造器 | `Filter (rows=498146)` ← `covering index range scan (rows=800000)` | 175 / 210 / 170 ms |
| 展开式 OR | `covering index range scan (rows=2)` | 0.0272 / 0.0216 / 0.0225 ms |

**约 8000 倍差距**：1000 页就是 190 秒 vs 0.02 秒——这正是"增量同步只有 1500 行/秒、
而数据库看起来全程空闲"的原因。代价是占位符个数变成 `n(n+1)/2`（3 列键 6 个），
参数必须按前缀重复展开；`SqlDialect` 把 `buildKeysetPredicate` 与 `buildKeysetParameters`
做成一对默认方法，两者不可能各写一套而错位。回归测试
`DefaultSyncEngineMySqlTest#keysetDeepCursorDoesNotScanWholeTable` 用 `Handler_read_next`
断言**每页扫描行数不随游标深度增长**（实测浅游标 999 行、深游标 999 行）——
任何人改回行构造器都会立刻变红。

#### 性能埋点（判断"慢在库还是慢在应用"）

每次执行结束，引擎都会打一行 **INFO** 分段汇总（生产默认开启，不需要改日志级别）：

```
分段耗时汇总：chunk=40 共 28688ms（717ms/chunk）| 读 356ms(其中取行 294ms) | 映射 9ms |
写 122ms(execute=113ms commit=9ms) | 回调 28065ms | 借连接等待 149ms | 其它 136ms
```

判读方法：

| 字段 | 含义 | 偏大说明什么 |
|---|---|---|
| 读 | 分页查询 + 结果集遍历 | 增量列缺索引（看"其中取行"）、源库压力大、`page_size` 过大 |
| 映射 | 行映射与值转换 | 列数极多或类型转换复杂；正常应为毫秒级 |
| 写 | 目标写入（execute + commit） | 目标库写入能力不足、二级索引过多、锁等待 |
| **回调** | **调用方 `RunMetricsListener.onChunk` 的耗时** | **瓶颈在观测通道（如 WebSocket 同步推送被背压），不在同步引擎** |
| 借连接等待 | 从连接池借连接的等待 | 池太小或连接被泄漏 |
| 其它 | 以上之外的时间 | 计划/游标/日志等杂项 |

需要更细的每 chunk 明细时，加系统属性 `-Ddatasync.engine.chunk.trace=true`：

```
chunk=NNN rows=500 read=XXms map=XXms write=XXms(execute=XXms/commit=XXms) notify=XXms between=XXms total=XXXms
```

`between` 是"上一个 chunk 结束到这一个 chunk 开始"的间隙，用来抓"两条 SQL 之间什么都没发"的空档。

> **两个真实案例（都是这套埋点定位的）**
> 1. 验收报告里 100 万行增量只有 1562 行/秒，数据库 `general_log` 全程空闲 → 埋点显示
>    `回调 28065ms / 总 28688ms`：调用方把 WebSocket 推送同步做在引擎线程上，
>    一个慢客户端就能把同步拖慢 30 倍。修法：回调必须非阻塞，或用 core 提供的
>    `AsyncRunMetricsListener` 包一层（后台单线程 + 有界队列 + 丢弃中间态 + 收尾投递最终进度）。
> 2. 同一个 100 万行场景里 `write` 从 ~240ms/chunk 降到 ~40ms/chunk，靠的就是上面那条
>    "多行 VALUES 不依赖连接串参数"。

> 3. **键集分页的谓词形态**（这才是上面第 1 个案例 1562 行/秒的**真正根因**，
>    回调阻塞是另一个被同时发现、但当时被误判为主因的问题）。引擎曾用 SQL 标准的**行构造器**写法
>    `` WHERE updated_at <= ? AND (updated_at, id) > (?, ?) ``，实测在 MySQL 8.0.46 上
>    **优化器不会把它下推成索引范围访问**：
>    ```
>    行构造器:  Limit 1000 (actual time=195..195 ms)  →  Filter  → Covering index range scan (rows=800000)
>    展开式 OR: Limit 1000 (actual time=0.03..0.03 ms) → Covering index range scan (rows=2)
>    ```
>    也就是先扫出 80 万条索引项再逐条过滤，**每页 170–210 ms**；展开式只要 **0.02 ms**——约 8000 倍。
>    1000 页 ≈ 190 秒，这正好解释"目标库全程空闲却每 chunk 恒定 ~700ms"。
>    现在引擎发出的是展开式：
>    ```sql
>    (k1 > ?) OR (k1 = ? AND k2 > ?) OR (k1 = ? AND k2 = ? AND k3 > ?)
>    ```
>    注意它的占位符是 `n(n+1)/2` 个（而游标只有 n 个值），参数按**前缀重复**展开绑定。
>    **不要把它"简化"回行构造器写法**；服务端 `EXPLAIN ANALYZE` 若出现
>    `Filter` + 大 `rows` 而不是范围扫描，就是又退化了。
>    另外：服务端 `EXPLAIN ANALYZE` **不带游标**测不出这个问题（不带游标时那半段范围扫描本来就快，
>    实测 2.84ms/页），必须带真实游标值测。

> 排查性能问题时注意一个坑：`SET GLOBAL long_query_time` **只对新建连接生效**。
> 引擎用的是连接池里的既有连接，改全局阈值对它们无效，会得到"慢查询日志 0 命中"的假阴性。
> 要么用 `SET SESSION long_query_time`（同一连接），要么重启连接池后再测。

### 故障排查
| 现象 | 排查方向 |
|---|---|
| 任务触发被拒绝 | 该任务上一个实例还在跑；看执行历史里是否有长期 `RUNNING`（进程被杀的残留记录会在下次启动时自动标记失败） |
| 水位不推进 | 执行未成功（失败/取消/坏行超限）。这是**保护行为**，不是 bug |
| 增量同步反复读同一批数据 | 增量字段不是单调的，或写入方回填了历史时间戳；调大 `lookback_seconds` 并确认字段语义 |
| 预检报 `PK_MISSING` | 目标表没有主键/唯一键，无法保证幂等；加主键，或明确使用 `FULL` + `TRUNCATE` 策略 |
| 预检报 `ORDER_KEY_NOT_UNIQUE` | 源表没有主键/唯一键，且配置的排序键不唯一 → 键集分页无法保证不重不漏；请指定一个唯一排序列 |
| 同步很慢 | **先看运行结束那行"分段耗时汇总"**：回调占大头 → 观测通道阻塞（用 `AsyncRunMetricsListener`）；读占大头 → 增量列索引/`page_size`；写占大头 → 目标库写入能力与索引；再看 `read_millis`/`write_millis` |

---

## 8. 开发指南

### 构建与测试

```bash
mvn -B -DskipTests package            # 跳过测试构建
mvn -B test                           # 全量测试
mvn -B -pl data-sync-core test        # 只测引擎
mvn -B -pl data-sync-server -am test  # 只测服务端（需先构建 core）

cd data-sync-web
npm run type-check                    # vue-tsc 类型检查
npm run build
```

### 添加一种数据库
1. `DbType` 加枚举值；
2. 实现 `SqlDialect`（标识符引用、键集分页、水位区间、upsert、全量策略语句）并在 `Dialects` 注册；
3. 实现 `TypeMapper` 描述到目标类型的映射；
4. 数据源连接 URL 构建与驱动依赖；
5. 补方言 SQL 文本断言测试 + 真实实例的端到端验收。

### 添加一种同步模式
1. `SyncMode` 加枚举值；
2. `DefaultSyncEngine` 里实现读范围与写策略的决策分支；
3. 前端同步模式选择器与预检提示同步更新。

### 代码约定
- 标识符英文，**注释与界面文案全部中文**；源文件 UTF-8 无 BOM。
- 元数据库表结构改动一律走 Flyway 新增迁移文件，**不要**改历史迁移文件。
- 构造或修改 Java 接口签名前，先看 `docs/production-readiness/00-frozen-contract.md`。

### ⚠️ Spring Boot 4 模块化清单（踩过的坑，务必先看）

Boot 4 把大量自动配置从主 starter 拆成了独立模块，**拆出去之后旧配置键会变成"哑配置"**——
不报错、不生效，然后在下游以完全不相干的错误爆发。本项目已经踩过两个：

| 你可能想写 | Boot 4 必须写 | 不加会怎样 |
|---|---|---|
| `spring-boot-starter-web`（已废弃） | `spring-boot-starter-webmvc` | 无 Web 容器 |
| —（Jackson 原随 web 传递） | `spring-boot-starter-jackson` | `com.fasterxml.jackson` / `tools.jackson` 找不到符号 |
| 只有 `org.flywaydb:flyway-core` | **`spring-boot-starter-flyway`** | `spring.flyway.*` **被静默忽略**，迁移一条不跑，然后 `ddl-auto=validate` 报 `missing table [datasource]` ——极容易误判成迁移脚本写错 |
| `spring-boot-starter-test`（无 MockMvc） | 另加 `spring-boot-webmvc-test` | `@WebMvcTest` / `MockMvc` 用不了 |

另外 **Jackson 3 的包名变了**：`com.fasterxml.jackson.databind.*` → **`tools.jackson.*`**。
本项目统一用 Jackson 3（`tools.jackson.*`），不要再引入 `com.fasterxml.jackson.*` 的 import。
判断某个 `spring.*` 配置是否生效的最快方法：搜 `spring-boot-autoconfigure-<version>.jar` 里有没有对应的类。

### 构建产物被占用
Windows 下运行中的 `java -jar target/xxx.jar` 会独占该文件，导致 `mvn package` 在最后一步
`repackage` 失败（`Unable to rename ... .jar.original`）或 `clean` 失败。
要边跑边改，请把 JAR 复制到别处再运行：
```powershell
Copy-Item data-sync-server\target\data-sync-server-1.0.0-SNAPSHOT.jar .dsh-scratch\app.jar
java -jar .dsh-scratch\app.jar --server.port=18080
```

---

## 9. 已知限制（如实声明）

| 限制 | 说明 |
|---|---|
| **达梦 DM8 未验证** | 方言实现结构完整并有 SQL 文本单测，但**没有在真实达梦实例上跑过**（开发环境无 DM8 与官方驱动）。`/api/system/info` 会返回 `dm8Verified: false`。**上线 DM8 前必须自行做端到端验收。** |
| 不支持实时 CDC | 基于 binlog 的实时同步不在范围内，只有定时批量同步。 |
| 不同步 DDL | 目标表结构需预先存在且与源表兼容；本工具只搬数据，不改表结构（`SWAP` 策略的暂存表除外）。 |
| 不做双向同步 | 只支持单向（含 `FULL` 覆盖写）。 |
| 单实例调度 | Quartz 用 JDBC JobStore 但 `isClustered=false`。多实例部署需要额外的调度互斥方案，当前仅保证单实例。 |
| 不做复杂转换 | 只支持列改名、默认值、类型映射；不支持自定义脚本转换。 |
| Docker 路径未实测 | 开发环境无 Docker，`Dockerfile` / `docker-compose.yml` 为交付未验证。 |

---

## 10. 许可

MIT
