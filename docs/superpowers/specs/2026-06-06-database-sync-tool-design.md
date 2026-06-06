# 数据库同步工具 - 设计文档

> 基于 Spring Boot + Spring Batch + Quartz + Vue 3 的数据库同步工具，支持 MySQL → MySQL 同构同步和 MySQL → 达梦8 异构同步，提供 Web UI 配置管理。

## 1. 目标与范围

### 1.1 核心目标
- 支持 **MySQL → MySQL** 全量/增量数据同步
- 支持 **MySQL → 达梦8** 全量/增量数据同步
- 提供 Web UI 用于配置数据源、同步任务、字段映射，以及查看执行状态和监控

### 1.2 非目标（明确不做）
- 不支持实时 CDC（基于 binlog）同步，仅定时批量同步
- 不做数据转换引擎（不处理 ETL 中的复杂 transform 逻辑）
- 不做双写或双向同步
- 不处理 DDL 同步（表结构变更不自动同步）

## 2. 架构设计

### 2.1 分层架构

```
┌───────────────────────────────────────────────────────────────┐
│                    Web 管理端 (Vue 3)                          │
│  数据源管理 | 任务配置 | 字段映射 | 监控面板 | 执行日志        │
└──────────────────────┬────────────────────────────────────────┘
                       │ REST API
┌──────────────────────┴────────────────────────────────────────┐
│                   Spring Boot 服务端                           │
│  ┌────────────┐  ┌────────────┐  ┌────────────────────────┐  │
│  │ 数据源管理  │  │ 任务管理    │  │  调度执行层              │  │
│  │ (DataSource│  │ (Task CRUD)│  │  ┌──────┐ ┌─────────┐ │  │
│  │   Pool)    │  │            │  │  │Quartz│ │Spring   │ │  │
│  └────────────┘  └────────────┘  │  │Schedule│Batch Job│ │  │
│                                   │  └──────┘ └─────────┘ │  │
│                                   └────────────────────────┘  │
└──────────────────────┬────────────────────────────────────────┘
                       │ JDBC
┌──────────────────────┴────────────────────────────────────────┐
│  源数据库 (MySQL)                    目标数据库 (MySQL/DM8)    │
│  元数据库 (H2/MySQL — 任务配置、同步状态、水位线)               │
└───────────────────────────────────────────────────────────────┘
```

### 2.2 模块划分

| 模块 | 职责 |
|------|------|
| data-sync-web | Vue 3 + Element Plus 前端，提供配置面板和监控仪表盘 |
| data-sync-server | Spring Boot 3.x 后端，REST API 入口 + Quartz 调度器管理 |
| data-sync-core | 核心同步引擎（含读取器/写入器/类型映射/SQL构建），基于 Spring Batch 5.x 实现 |

## 3. 详细组件设计

### 3.1 data-sync-core 组件

#### 3.1.1 读取器 (Reader)

**PageReader** — 通用分页读取器
- 基于 LIMIT ? OFFSET ? 或 WHERE id > ? ORDER BY id LIMIT ? 游标分页
- 通过 TaskConfig 中的 pageSize 配置每批大小（默认 1000）
- 支持自定义排序字段（主键或指定列），确保分页有序
- 读取完成的标记由 Spring Batch 的 ItemReader 返回 null 控制

**IncrementalReader** — 增量读取器
- 在 PageReader 基础上增加时间戳条件 WHERE updated_at > ?
- 水位值从元数据库 sync_watermark 表读取
- 首次运行时如果无水位记录，执行全量读
- 水位字段名在任务配置中指定（如 updated_at、modify_time）

#### 3.1.2 处理器 (Processor)

**ColumnMappingProcessor**
- 输入：源表一行数据（Map<String, Object>）
- 输出：目标表一行数据（Map<String, Object>，字段名已按映射转换）
- 核心逻辑：
  1. 按 FieldMapping 列表执行字段映射：目标列 → 源列
  2. 对需要类型转换的列调用 TypeMapper 转换值
  3. 对不需要映射的源列直接丢弃
  4. 对目标表有但源表没有的列，使用配置的默认值

#### 3.1.3 写入器 (Writer)

**JdbcBatchWriter** — JDBC 批量写入器
- 使用 PreparedStatement + batchUpdate 批量写入
- 批量大小可配置（默认 500）
- 写入前判断同步模式：
  - 全量模式：先 TRUNCATE（或 DELETE）目标表，再写入
  - 增量模式：直接 INSERT ... ON DUPLICATE KEY UPDATE（MySQL）或 MERGE INTO（DM8）

**Dm8BatchWriter** — 达梦专用写入器
- 继承 JdbcBatchWriter，覆盖 DM8 特定行为
- 使用达梦的 MERGE INTO 语法处理增量 upsert
- 处理 DM8 对批量写入的特殊限制（如批量大小不超过 1000 行）

#### 3.1.4 类型映射 (TypeMapper)

MySQL → DM8 类型映射表（核心映射）：

| MySQL 类型 | 达梦 8 类型 | 备注 |
|------------|-------------|------|
| TINYINT | SMALLINT | MySQL TINYINT(1) 常作为 boolean |
| SMALLINT | SMALLINT | |
| MEDIUMINT | INT | |
| INT / INTEGER | INT | |
| BIGINT | BIGINT | |
| FLOAT | FLOAT | |
| DOUBLE | DOUBLE | |
| DECIMAL(p,s) | DECIMAL(p,s) | 精度一致 |
| CHAR(n) | CHAR(n) | |
| VARCHAR(n) | VARCHAR(n) | |
| TINYTEXT | VARCHAR(255) | 达梦无 TINYTEXT |
| TEXT | TEXT | |
| MEDIUMTEXT | TEXT | |
| LONGTEXT | CLOB | |
| BLOB | BLOB | |
| DATE | DATE | |
| DATETIME | TIMESTAMP | |
| TIMESTAMP | TIMESTAMP | 注意时区处理 |
| TIME | TIME | |
| YEAR | INT | 达梦无 YEAR 类型 |
| BINARY / VARBINARY | BLOB | |
| BIT | INT | 达梦无 BIT 类型 |
| JSON | VARCHAR(4000) 或 CLOB | 达梦 JSON 支持有限 |
| SET | TEXT | 达梦无 SET 类型 |
| ENUM | VARCHAR(255) | 达梦无 ENUM 类型 |

### 3.2 data-sync-server 组件

**REST API 接口设计：**

```
数据源管理:
  POST   /api/datasources              — 创建数据源
  GET    /api/datasources              — 数据源列表
  GET    /api/datasources/{id}         — 数据源详情
  PUT    /api/datasources/{id}         — 更新数据源
  DELETE /api/datasources/{id}         — 删除数据源
  POST   /api/datasources/{id}/test    — 测试连接

任务管理:
  POST   /api/tasks                    — 创建同步任务
  GET    /api/tasks                    — 任务列表（分页+状态过滤）
  GET    /api/tasks/{id}               — 任务详情
  PUT    /api/tasks/{id}               — 更新任务配置
  DELETE /api/tasks/{id}               — 删除任务
  POST   /api/tasks/{id}/start         — 手动启动一次同步
  POST   /api/tasks/{id}/stop          — 停止正在运行的任务
  POST   /api/tasks/{id}/enable        — 启用定时调度
  POST   /api/tasks/{id}/disable       — 禁用定时调度

调度配置:
  PUT    /api/tasks/{id}/schedule      — 更新 cron 表达式

执行记录:
  GET    /api/tasks/{id}/records       — 执行历史列表
  GET    /api/records/{id}             — 单次执行详情（含失败行详情）

字段映射:
  POST   /api/tasks/{id}/fields        — 配置字段映射
  GET    /api/tasks/{id}/fields        — 获取字段映射
  POST   /api/tasks/{id}/columns       — 获取源表/目标表列信息（自动映射预览）

监控:
  GET    /api/dashboard/overview       — 概览统计（总任务数、运行中、失败数）
  GET    /api/dashboard/recent-fails   — 最近失败记录
```

### 3.3 元数据库设计

由于不引入外部数据库依赖，默认使用 H2 内嵌数据库。生产部署可切换为 MySQL。

**表结构：**

```sql
-- 数据源配置
CREATE TABLE datasource (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    db_type     VARCHAR(20) NOT NULL,          -- MYSQL / DM8
    host        VARCHAR(255) NOT NULL,
    port        INT NOT NULL,
    database_name VARCHAR(100) NOT NULL,
    username    VARCHAR(100) NOT NULL,
    password    VARCHAR(255) NOT NULL,          -- 加密存储
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 同步任务配置
CREATE TABLE sync_task (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    name            VARCHAR(200) NOT NULL,
    source_ds_id    BIGINT NOT NULL REFERENCES datasource(id),
    target_ds_id    BIGINT NOT NULL REFERENCES datasource(id),
    source_table    VARCHAR(200) NOT NULL,
    target_table    VARCHAR(200) NOT NULL,
    sync_mode       VARCHAR(20) NOT NULL DEFAULT 'FULL_INCR',  -- FULL / INCR / FULL_INCR
    incr_column     VARCHAR(100),                -- 增量字段名
    incr_value      VARCHAR(255),                -- 增量初始值
    cron_expression VARCHAR(100),                -- Quartz cron
    page_size       INT DEFAULT 1000,
    batch_size      INT DEFAULT 500,
    mapping_json    TEXT,                        -- 字段映射 JSON
    status          VARCHAR(20) DEFAULT 'DISABLED', -- ENABLED / DISABLED
    created_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 同步水位线（增量同步用）
CREATE TABLE sync_watermark (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id     BIGINT NOT NULL REFERENCES sync_task(id),
    column_name VARCHAR(100) NOT NULL,
    max_value   VARCHAR(255),
    updated_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 同步执行记录
CREATE TABLE sync_record (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id         BIGINT NOT NULL REFERENCES sync_task(id),
    start_time      TIMESTAMP NOT NULL,
    end_time        TIMESTAMP,
    status          VARCHAR(20),                -- RUNNING / SUCCESS / FAILED / STOPPED
    total_rows      BIGINT DEFAULT 0,
    read_rows       BIGINT DEFAULT 0,
    write_rows      BIGINT DEFAULT 0,
    error_rows      BIGINT DEFAULT 0,
    error_message   TEXT,
    trigger_type    VARCHAR(20)                 -- SCHEDULED / MANUAL
);

-- 错误行记录
CREATE TABLE sync_error (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    record_id   BIGINT NOT NULL REFERENCES sync_record(id),
    row_data    TEXT,                           -- 源行数据（JSON）
    error_msg   VARCHAR(1000),
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

## 4. 数据流

### 4.1 全量同步流程

```
1. Quartz 触发 → Spring Batch Job 启动
2. Step 1: 预检
   ├─ 连接源数据库，检测源表是否存在
   ├─ 连接目标数据库，检测目标表是否存在
   └─ 对比列兼容性（名称、类型），给出警告或报错
3. Step 2: 目标表清理
   └─ TRUNCATE TABLE target_table
4. Step 3: Chunk 循环
   ├─ Reader: SELECT * FROM source_table ORDER BY id LIMIT ? OFFSET ?
   ├─ Processor: 字段映射 + 类型转换
   └─ Writer: INSERT INTO target_table VALUES (?, ?, ...)
5. Step 4: 更新同步记录
   ├─ 记录读取行数、写入行数、错误行数
   └─ 写入 sync_record 表
```

### 4.2 增量同步流程

```
1. Quartz 触发 → Spring Batch Job 启动
2. Step 1: 水位读取
   ├─ 从 sync_watermark 读取 max_value
   └─ 若没有水位记录，则全量同步
3. Step 2: Chunk 循环
   ├─ Reader: SELECT * FROM source_table WHERE updated_at > ? ORDER BY updated_at, id
   ├─ Processor: 字段映射 + 类型转换
   └─ Writer: INSERT ... ON DUPLICATE KEY UPDATE (MySQL) / MERGE INTO (DM8)
4. Step 3: 水位更新
   ├─ 更新 sync_watermark 的 max_value 为本次读取的最大值
   └─ 更新 sync_record
```

## 5. 错误处理

| 场景 | 处理方式 |
|------|----------|
| 数据库连接断开 | Spring Batch RetryTemplate 自动重试，默认 3 次，间隔 5s |
| 单行数据转换失败 | 跳过该行，写入 sync_error 表，记录错误原因 |
| 整批写入失败 | Spring Batch 回退 chunk，重试 (默认 3 次) |
| 任务超时 | Quartz @DisallowConcurrentExecution 防止重叠执行 |
| 目标表不存在 | 预检阶段报错，任务立即失败，不清除已有数据 |
| 字段不兼容 | 预检阶段 warning 日志 + 可选 force 模式跳过检查 |
| 同步中断重启 | Spring Batch JobRepository 保存执行上下文，重启后从断点继续 |

## 6. 技术栈

| 层级 | 技术选型 |
|------|----------|
| JDK | Java 25 |
| 应用框架 | Spring Boot 3.x |
| 批处理框架 | Spring Batch 5.x |
| 任务调度 | Quartz Scheduler |
| 数据库连接池 | HikariCP |
| 元数据库 | H2 (内嵌, 默认) / MySQL (生产) |
| MySQL 驱动 | mysql-connector-j |
| 达梦驱动 | dm.jdbc.driver (DM8 官方驱动) |
| 前端框架 | Vue 3 + Vite |
| UI 库 | Element Plus |
| HTTP 客户端 (前端) | Axios |
| 构建工具 | Maven 3.9.x |
| 测试 | JUnit 5 + Testcontainers + Mockito |

## 7. 测试策略

| 测试类型 | 覆盖内容 |
|----------|----------|
| 单元测试 | TypeMapper、SQLBuilder、FieldMappingProcessor、数据模型 |
| 集成测试 | Testcontainers 启动 MySQL，H2 模拟 DM8，验证完整同步流程 |
| API 测试 | Spring MockMvc / WebTestClient 验证 REST API |
| 前端测试 | Vitest + Vue Test Utils（组件测试） |
| 端到端测试 | Docker Compose 启动 MySQL + DM8，创建真实同步任务验证 |

## 8. 项目约定

- 代码语言：英语（类名、方法名），注释使用中文
- REST API 路径：kebab-case，如 /api/datasources
- 数据库对象名：snake_case
- 配置文件格式：YAML
- Git 提交信息：中文，简明描述改动

## 9. 未来可扩展方向

- 支持更多目标数据库（PostgreSQL、Oracle、ClickHouse）
- 支持 WebSocket 实时推送执行日志到前端
- 复杂 transform 脚本（Groovy/JavaScript 嵌入）
- 任务依赖与 DAG 编排
- 通知告警（邮件、Webhook）
- Docker 部署支持
