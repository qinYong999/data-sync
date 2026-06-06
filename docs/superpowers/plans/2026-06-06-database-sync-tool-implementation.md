# 数据库同步工具 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 构建 MySQL → MySQL 和 MySQL → 达梦8 数据库同步工具，提供 Web UI 管理。

**架构：** Maven 多模块，data-sync-core 封装 Spring Batch 同步引擎，data-sync-server 提供 REST API + Quartz 调度，data-sync-web 为 Vue 3 前端。

**Tech Stack:** Java 25, Spring Boot 3.x, Spring Batch 5.x, Quartz, HikariCP, MySQL, DM8, Vue 3, Element Plus

---

## Task 1: 项目骨架搭建

**Files:**
- Create: `pom.xml` (根)
- Create: `data-sync-core/pom.xml`
- Create: `data-sync-server/pom.xml`
- Create: `data-sync-server/src/main/java/com/datasync/server/DataSyncApplication.java`
- Create: `data-sync-server/src/main/resources/application.yml`

- [ ] **Step 1: 根 POM**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <groupId>com.datasync</groupId>
    <artifactId>data-sync</artifactId>
    <version>1.0.0-SNAPSHOT</version>
    <packaging>pom</packaging>
    <modules>
        <module>data-sync-core</module>
        <module>data-sync-server</module>
    </modules>
    <properties>
        <java.version>25</java.version>
        <maven.compiler.source>25</maven.compiler.source>
        <maven.compiler.target>25</maven.compiler.target>
        <spring-boot.version>3.4.4</spring-boot.version>
        <spring-batch.version>5.2.3</spring-batch.version>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>
    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-dependencies</artifactId>
                <version>${spring-boot.version}</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>
    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
                <version>${spring-boot.version}</version>
            </plugin>
        </plugins>
    </build>
</project>
```

- [ ] **Step 2: data-sync-core/pom.xml**

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0" ...>
    <parent><artifactId>data-sync</artifactId><groupId>com.datasync</groupId><version>1.0.0-SNAPSHOT</version></parent>
    <modelVersion>4.0.0</modelVersion>
    <artifactId>data-sync-core</artifactId>
    <dependencies>
        <dependency><groupId>org.springframework.batch</groupId><artifactId>spring-batch-core</artifactId><version>${spring-batch.version}</version></dependency>
        <dependency><groupId>org.springframework</groupId><artifactId>spring-jdbc</artifactId></dependency>
        <dependency><groupId>com.zaxxer</groupId><artifactId>HikariCP</artifactId></dependency>
        <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
        <dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency>
        <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><scope>test</scope></dependency>
        <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>test</scope></dependency>
    </dependencies>
</project>
```
- [ ] **Step 3: data-sync-server/pom.xml**

`xml
<project>
  <parent><artifactId>data-sync</artifactId><groupId>com.datasync</groupId><version>1.0.0-SNAPSHOT</version></parent>
  <modelVersion>4.0.0</modelVersion><artifactId>data-sync-server</artifactId>
  <dependencies>
    <dependency><groupId>com.datasync</groupId><artifactId>data-sync-core</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-quartz</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-batch</artifactId></dependency>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
  </dependencies>
</project>
`

- [ ] **Step 4: Spring Boot 入口**

`java
package com.datasync.server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class DataSyncApplication {
  public static void main(String[] args) {
    SpringApplication.run(DataSyncApplication.class, args);
  }
}
`

- [ ] **Step 5: application.yml**

`yaml
server:
  port: 8080
spring:
  datasource:
    url: jdbc:h2:file:./data/datasync;AUTO_SERVER=TRUE
    driver-class-name: org.h2.Driver
    username: sa
    password:
  h2:
    console:
      enabled: true
      path: /h2-console
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
  batch:
    jdbc:
      initialize-schema: always
    job:
      enabled: false
logging:
  level:
    com.datasync: INFO
`

- [ ] **Step 6: 编译验证** — Run: mvn compile -q

## Task 2: 数据模型 (data-sync-core model)

**Files:** data-sync-core/src/main/java/com/datasync/core/model/enums/DbType.java, SyncMode.java, SyncStatus.java, DataSourceConfig.java, SyncTaskConfig.java, FieldMapping.java

- [ ] **DbType 枚举:** MYSQL, DM8
- [ ] **SyncMode 枚举:** FULL, INCR, FULL_INCR
- [ ] **SyncStatus 枚举:** ENABLED, DISABLED
- [ ] **DataSourceConfig.java:** id, name, dbType, host, port, databaseName, username, password + buildJdbcUrl()
- [ ] **FieldMapping.java:** sourceColumn, targetColumn, defaultValue, primaryKey
- [ ] **SyncTaskConfig.java:** 完整配置字段, pageSize 默认 1000, batchSize 默认 500
- [ ] **编译验证:** mvn compile -q

## Task 3: 类型映射 (TypeMapper)

- [ ] **TypeMapper 接口:** mapType(sourceType, value), mapTypeName(sourceType)
- [ ] **MySqlToDm8TypeMapper.java:** 实现 TINYINT->SMALLINT, YEAR->INT, BIT->INT, JSON->CLOB 等映射
- [ ] **MySqlToDm8TypeMapperTest.java:** 7 个测试覆盖类型名映射、值转换、null输入
- [ ] **Run:** mvn test -pl data-sync-core -Dtest=MySqlToDm8TypeMapperTest

## Task 4: SQL 构建器 (SqlBuilder)

- [ ] **SqlBuilder.java:** buildSelectPage, buildSelectIncremental, buildCountSql, buildTruncateSql, buildInsertSql, buildUpsertSql(MySQL/DM8)
- [ ] **SqlBuilderTest.java:** 6 个测试覆盖分页、增量、truncate、MySQL upsert、DM8 merge
- [ ] **Run:** mvn test -pl data-sync-core -Dtest=SqlBuilderTest

## Task 5: 读取器 (PageReader & IncrementalReader)

- [ ] **PageReader.java:** 继承 JdbcCursorItemReader, MapRowMapper 将 ResultSet 转 Map
- [ ] **IncrementalReader.java:** 构造时注入时间戳参数
- [ ] **PageReaderTest.java:** 构造验证 + MapRowMapper 实例检查
- [ ] **Run:** mvn test -pl data-sync-core -Dtest=PageReaderTest

## Task 6: 处理器 (ColumnMappingProcessor)

- [ ] **ColumnMappingProcessor.java:** 实现 ItemProcessor, 按 FieldMapping 映射源行到目标行
- [ ] **ColumnMappingProcessorTest.java:** 3 个测试覆盖字段映射、默认值、空映射
- [ ] **Run:** mvn test -pl data-sync-core -Dtest=ColumnMappingProcessorTest

## Task 7: 写入器 (JdbcBatchWriter & Dm8BatchWriter)

- [ ] **JdbcBatchWriter.java:** 实现 ItemWriter, JdbcTemplate.batchUpdate
- [ ] **Dm8BatchWriter.java:** 继承 JdbcBatchWriter, 构造时指定 DbType.DM8
- [ ] **JdbcBatchWriterTest.java:** insert/truncate SQL 构造验证
- [ ] **Run:** mvn test -pl data-sync-core -Dtest=JdbcBatchWriterTest

## Task 8: Spring Batch Job 配置 (SyncJobConfig)

- [ ] **SyncJobListener.java:** JobExecutionListener, 记录开始/结束日志
- [ ] **SyncJobConfig.java:** Job 工厂, 根据 SyncTaskConfig 动态创建 Job (Reader+Processor+Writer)
- [ ] **编译验证:** mvn compile -q

## Task 9: 核心同步集成测试

- [ ] **SyncJobIntegrationTest.java:** H2 内存库模拟源和目标, 构造 PageReader+JdbcBatchWriter, 验证数据一致性
- [ ] **Run:** mvn test -pl data-sync-core -Dtest=SyncJobIntegrationTest

## Task 10: JPA 实体 & Repository (data-sync-server)

- [ ] **DataSourceEntity.java:** @Entity @Table(name=datasource), 含 id/name/dbType/host/port/databaseName/username/password/createdAt/updatedAt
- [ ] **SyncTaskEntity.java:** @Entity @Table(name=sync_task), 含配置字段 + createdAt/updatedAt
- [ ] **SyncRecordEntity.java:** @Entity @Table(name=sync_record), 含执行统计字段
- [ ] **DataSourceRepository, SyncTaskRepository, SyncRecordRepository:** JpaRepository 继承
- [ ] **编译验证:** mvn compile -q

## Task 11: REST API — 数据源管理

- [ ] **DataSourceDTO.java:** id/name/dbType/host/port/databaseName/username/password
- [ ] **DataSourceService.java:** CRUD + testConnection (JDBC 连接测试)
- [ ] **DataSourceController.java:** @RestController /api/datasources, list/get/create/update/delete/test 端点
- [ ] **编译验证:** mvn compile -q

## Task 12: REST API — 任务管理 & 执行记录

- [ ] **TaskDTO.java:** 含 FieldMappingItem 内部类, mappingJson 用 Jackson 序列化
- [ ] **SyncTaskService.java:** CRUD + enableTask/disableTask/updateSchedule
- [ ] **TaskController.java:** list/get/create/update/delete/enable/disable/updateSchedule
- [ ] **RecordController.java:** 按任务查执行历史 + 单条详情
- [ ] **编译验证:** mvn compile -q

## Task 13: 仪表盘 & 错误处理

- [ ] **DashboardVO.java:** totalTasks/runningTasks/failedTasks/successTasks/totalRecords/totalReadRows
- [ ] **DashboardController.java:** /api/dashboard/overview + /api/dashboard/recent-fails
- [ ] **GlobalExceptionHandler.java:** @RestControllerAdvice, RuntimeException->400, Exception->500
- [ ] **编译验证:** mvn compile -q

## Task 14: 前端脚手架 (Vue 3 + Vite + Element Plus)

- [ ] 创建 data-sync-web/package.json, vite.config.ts, tsconfig.json, index.html
- [ ] 创建 src/main.ts, src/App.vue (含侧边栏导航), src/router/index.ts (路由配置)
- [ ] 创建 src/api/request.ts (Axios 封装), src/env.d.ts
- [ ] **Run:** cd data-sync-web && npm install

## Task 15: 前端 — 数据源管理页面

- [ ] **src/api/datasource.ts:** list/get/create/update/delete/test API 封装
- [ ] **DataSourceList.vue:** el-table 展示, 测试连接/编辑/删除操作
- [ ] **DataSourceForm.vue:** el-form 创建/编辑, 支持测试连接按钮

## Task 16: 前端 — 同步任务管理页面

- [ ] **src/api/task.ts:** REST API 封装
- [ ] **TaskList.vue:** 任务列表, 启用/禁用/编辑/历史/删除
- [ ] **TaskForm.vue:** 创建/编辑任务, 选择数据源/同步模式/Cron
- [ ] **TaskRecords.vue:** 执行历史, 状态标签/行数/错误信息

## Task 17: 前端 — 仪表盘

- [ ] **src/api/dashboard.ts:** overview + recentFails API
- [ ] **Dashboard.vue:** 统计卡片 (总任务/运行中/失败) + 失败记录表

## 自审

**Spec 覆盖率检查:**
- MySQL-MySQL 同步: Task 8
- MySQL-DM8 同步: Task 7 + Task 3
- Web UI 配置: Task 14-17
- 定时调度: Task 1 (Quartz starter)
- 全量+增量: Task 5
- 执行记录: Task 10 + Task 12
- 错误处理: Task 13 (GlobalExceptionHandler)
- 仪表盘: Task 13 + Task 17
**占位符:** 无
**类型一致性:** 前后一致
