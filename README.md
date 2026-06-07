# DataSync — 异构数据库数据同步平台

[![Java](https://img.shields.io/badge/Java-21-orange)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.4.1-brightgreen)](https://spring.io/projects/spring-boot)
[![Vue](https://img.shields.io/badge/Vue-3.5-4fc08d)](https://vuejs.org/)
[![License](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

**DataSync** 是一个基于 Spring Batch 的异构数据库数据同步平台，支持 MySQL ↔ 达梦 DM8 之间的全量同步、增量同步以及先全量后增量同步。

---

## 目录

- [功能特性](#功能特性)
- [技术栈](#技术栈)
- [快速开始](#快速开始)
- [使用指南](#使用指南)
- [项目结构](#项目结构)
- [API 参考](#api-参考)
- [开发指南](#开发指南)
- [常见问题](#常见问题)

---

## 功能特性

### 🔄 数据同步
- **全量同步 (FULL)** — 读取源表全部数据写入目标表，首次写入前自动清空目标表
- **增量同步 (INCR)** — 基于时间戳列增量读取，只同步新增/变更的数据
- **先全量后增量 (FULL_INCR)** — 首次全量同步，后续转换为增量同步

### 🗄️ 异构数据库支持
- **源数据库**：MySQL 8.0+
- **目标数据库**：MySQL 8.0+ / 达梦 DM8
- 自动处理 MySQL → DM8 的 27 种类型映射（TINYINT→SMALLINT, JSON→CLOB, BIT→INT 等）
- 根据目标库类型自动切换 SQL 方言（MySQL `ON DUPLICATE KEY UPDATE` / DM8 `MERGE INTO`）

### 🎛️ 可视化管理
- **仪表盘** — 任务统计概览、最近失败记录监控（30 秒自动刷新）
- **数据源管理** — 支持连接测试、在线获取表列表和列信息
- **同步任务管理** — 字段映射配置、Cron 调度表达式、手动触发执行
- **执行历史** — 执行记录查看、错误信息追溯、实时日志 WebSocket 推送

### ⚡ 架构特性
- **基于 Spring Batch** — 分页读取、Chunk 批量写入、失败重试、断点续传
- **动态数据源** — 每次同步按配置动态创建连接池，用完自动释放
- **异步执行** — 手动触发后立即返回，任务在后台异步执行
- **实时日志** — 通过 WebSocket 推送同步进度和事件消息
- **容器化部署** — Docker Compose 一键启动

---

## 技术栈

| 层级 | 技术 | 版本 |
|------|------|------|
| **运行时** | Java | 21 |
| **后端框架** | Spring Boot | 3.4.1 |
| **批处理框架** | Spring Batch | 5.1.2 |
| **ORM** | Spring Data JPA (Hibernate) | 6.6 |
| **调度框架** | Quartz Scheduler | 2.3.2 |
| **数据库** | MySQL | 8.0+ |
| **目标库支持** | 达梦 DM8 | — |
| **前端框架** | Vue 3 + TypeScript | 3.5 / 5.7 |
| **UI 组件库** | Element Plus | 2.9 |
| **构建工具（后端）** | Maven | 3.9+ |
| **构建工具（前端）** | Vite | 6.x |
| **容器化** | Docker / Docker Compose | — |

---

## 快速开始

### 方式一：Docker Compose 部署（推荐）

```bash
# 克隆项目
git clone https://github.com/qinYong999/data-sync.git
cd data-sync

# 启动服务（MySQL + Backend）
docker-compose up -d

# 查看日志
docker-compose logs -f backend
```

访问 `http://localhost:8080` 即可进入管理界面。

### 方式二：本地开发运行

#### 前置要求
- JDK 21+
- Maven 3.9+
- Node.js 22+
- MySQL 8.0+

#### 1. 启动 MySQL

```bash
# 使用 Docker 启动 MySQL（或使用现有的 MySQL 实例）
docker run -d \
  --name datasync-mysql \
  -e MYSQL_ROOT_PASSWORD=123456 \
  -e MYSQL_DATABASE=datasync \
  -p 3307:3306 \
  mysql:8.0
```

#### 2. 启动后端

```bash
# 编译
mvn install -DskipTests

# 启动（端口 8080）
mvn spring-boot:run -pl data-sync-server
```

#### 3. 启动前端

```bash
cd data-sync-web
npm install
npm run dev
```

访问 `http://localhost:5173` 进入管理界面（Vite 自动代理 `/api` 和 `/ws` 到后端 8080）。

---

## 使用指南

### 第一步：配置数据源
1. 进入 **数据源管理** → **新增数据源**
2. 填写数据库连接信息（类型、主机、端口、库名、用户名、密码）
3. 点击 **测试连接** 验证配置正确性
4. 点击 **保存** 完成创建

### 第二步：创建同步任务
1. 进入 **同步任务** → **新增任务**
2. 选择 **源数据源** 和 **源表**
3. 选择 **目标数据源** 和 **目标表**
4. 选择 **同步模式**（全量/增量/先全量后增量）
5. 可选：配置 **Cron 表达式** 实现定时调度
6. 点击 **保存**

### 第三步：手动执行
- 在任务列表点击 **手动执行** 立即触发一次同步
- 点击 **历史** 查看执行记录和实时日志

---

## 项目结构

```
data-sync/
├── pom.xml                        # Maven 父工程
├── docker-compose.yml             # Docker Compose 编排
├── Dockerfile                     # 多阶段构建
├── settings.xml                   # Maven 仓库配置
│
├── data-sync-core/                # ⭐ 同步引擎核心
│   └── src/main/java/com/datasync/core/
│       ├── job/
│       │   ├── SyncJobConfig.java           # Spring Batch Job 动态工厂
│       │   ├── SyncJobListener.java         # Job 生命周期监听器
│       │   ├── SyncEventBus.java            # 同步事件总线（WebSocket 推送）
│       │   ├── SqlBuilder.java              # SQL 构建器（多方言）
│       │   ├── reader/
│       │   │   ├── PageReader.java          # 全量分页读取器
│       │   │   └── IncrementalReader.java   # 增量读取器
│       │   ├── processor/
│       │   │   └── ColumnMappingProcessor.java  # 字段映射处理器
│       │   └── writer/
│       │       ├── JdbcBatchWriter.java     # JDBC 批量写入器
│       │       └── Dm8BatchWriter.java      # 达梦专用写入器
│       ├── mapper/
│       │   ├── TypeMapper.java              # 类型映射接口
│       │   └── MySqlToDm8TypeMapper.java    # MySQL→DM8 类型映射
│       └── model/
│           ├── DataSourceConfig.java
│           ├── SyncTaskConfig.java
│           ├── FieldMapping.java
│           └── enums/ (DbType, SyncMode, SyncStatus)
│
├── data-sync-server/              # ⭐ 后端服务
│   └── src/main/java/com/datasync/server/
│       ├── DataSyncApplication.java        # Spring Boot 入口
│       ├── controller/
│       │   ├── DataSourceController.java   # 数据源 CRUD + 测试连接
│       │   ├── TaskController.java         # 任务 CRUD + 调度 + 触发
│       │   ├── RecordController.java       # 执行记录查询
│       │   └── DashboardController.java    # 仪表盘统计
│       ├── service/
│       │   ├── DataSourceService.java      # 数据源业务逻辑
│       │   ├── SyncTaskService.java        # 同步任务业务逻辑
│       │   └── SyncExecutionService.java   # ⭐ 任务执行引擎
│       ├── config/
│       │   ├── WebSocketConfig.java        # WebSocket 配置
│       │   ├── SyncLogWebSocketHandler.java# 日志推送处理器
│       │   └── DatabaseCommentInitializer.java  # 表注释初始化
│       ├── entity/ (DataSourceEntity, SyncTaskEntity, SyncRecordEntity)
│       ├── repository/ (JPA repositories)
│       ├── model/ (DTO, VO)
│       └── exception/ (GlobalExceptionHandler)
│
└── data-sync-web/                 # ⭐ 前端界面
    └── src/
        ├── App.vue                # 主布局（侧边栏 + 顶栏 + 内容区）
        ├── main.ts                # 入口
        ├── types/index.ts         # TypeScript 类型定义
        ├── api/                   # API 请求层
        │   ├── request.ts         # Axios 实例（拦截器统一错误处理）
        │   ├── datasource.ts      # 数据源 API
        │   ├── task.ts            # 任务 API
        │   ├── dashboard.ts       # 仪表盘 API
        │   └── websocket.ts       # WebSocket 客户端
        ├── composables/           # 可复用逻辑
        │   ├── usePagination.ts   # 分页
        │   ├── useForm.ts         # 表单 CRUD
        │   ├── useWebSocket.ts    # WebSocket 管理
        │   └── useConfirm.ts      # 确认对话框
        ├── components/            # 通用组件
        │   ├── PageHeader.vue     # 页面标题栏
        │   ├── StatusTag.vue      # 状态标签
        │   └── EmptyState.vue     # 空状态
        ├── views/                 # 页面
        │   ├── Dashboard.vue      # 仪表盘
        │   ├── DataSourceList.vue # 数据源列表
        │   ├── DataSourceForm.vue # 新增/编辑数据源
        │   ├── TaskList.vue       # 同步任务列表
        │   ├── TaskForm.vue       # 新增/编辑同步任务
        │   ├── TaskRecords.vue    # 执行历史 + 实时日志
        │   └── FieldMappingEditor.vue  # 字段映射编辑器
        ├── router/index.ts        # 路由配置
        └── styles/global.css      # 全局样式
```

---

## API 参考

### 数据源

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/datasources` | 分页列表 |
| `GET` | `/api/datasources/{id}` | 获取单个 |
| `POST` | `/api/datasources` | 新增 |
| `PUT` | `/api/datasources/{id}` | 编辑 |
| `DELETE` | `/api/datasources/{id}` | 删除 |
| `POST` | `/api/datasources/{id}/test` | 测试连接（已保存） |
| `POST` | `/api/datasources/test` | 测试连接（未保存） |
| `GET` | `/api/datasources/{id}/tables` | 获取表列表 |

### 同步任务

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/tasks` | 分页列表 |
| `GET` | `/api/tasks/{id}` | 获取单个 |
| `POST` | `/api/tasks` | 新增 |
| `PUT` | `/api/tasks/{id}` | 编辑 |
| `DELETE` | `/api/tasks/{id}` | 删除 |
| `POST` | `/api/tasks/{id}/enable` | 启用调度 |
| `POST` | `/api/tasks/{id}/disable` | 禁用调度 |
| `POST` | `/api/tasks/{id}/trigger` | 手动触发执行 |
| `PUT` | `/api/tasks/{id}/schedule` | 更新调度表达式 |
| `GET` | `/api/tasks/{id}/columns` | 获取源/目标表列信息 |
| `GET` | `/api/tasks/{id}/records` | 执行记录列表 |

### 仪表盘

| 方法 | 路径 | 说明 |
|------|------|------|
| `GET` | `/api/dashboard/overview` | 统计概览 |
| `GET` | `/api/dashboard/recent-fails` | 最近失败记录 |

### WebSocket

| 路径 | 说明 |
|------|------|
| `/ws/logs` | 实时同步日志推送 |

---

## 开发指南

### 后端扩展

**添加新的数据库类型支持：**

1. 在 `DbType` 枚举中添加新类型
2. 实现 `TypeMapper` 接口（类型映射）
3. 在 `SqlBuilder` 中添加对应 SQL 方言
4. 创建对应的 `Writer` 类（继承 `JdbcBatchWriter` 或实现 `ItemWriter`）
5. 在 `DataSourceService` 中添加连接 URL 构建逻辑

**添加新的同步模式：**

1. 在 `SyncMode` 枚举中添加新模式
2. 在 `SyncJobConfig.buildJob()` 中处理新模式
3. 前端 `TaskForm.vue` 同步模式选择器添加新选项

### 前端开发

```bash
cd data-sync-web
npm run dev      # 开发模式（热更新）
npm run build    # 生产构建
npx vue-tsc --noEmit  # TypeScript 类型检查
```

### 构建完整 JAR

```bash
mvn package -DskipTests
```
构建产物：`data-sync-server/target/data-sync-server-1.0.0-SNAPSHOT.jar`

---

## 常见问题

**Q: 同步任务执行失败，提示主键冲突？**

A: 如果使用 `FULL_INCR` 模式且未配置增量字段，系统会退化为全量同步（先清空目标表再写入）。请确认已正确配置增量字段（`incrColumn`），或在 `FULL` 模式下系统会自动清空目标表。

**Q: 执行历史中没有错误信息？**

A: `SyncExecutionService` 会在任务失败后从 Spring Batch 的 `StepExecution` 中提取异常信息并保存到 `sync_record.error_message` 字段。

**Q: 实时日志显示"未连接"？**

A: 确认后端已启动并且 Vite 代理配置中包含 `/ws` 路径的 WebSocket 转发。前端刷新后会自动重连 WebSocket。

**Q: 数据源下拉列表为空？**

A: 请先在"数据源管理"页面添加至少一个数据源。如果已有数据源但仍不显示，刷新页面重试。

---

## License

[MIT](LICENSE)
