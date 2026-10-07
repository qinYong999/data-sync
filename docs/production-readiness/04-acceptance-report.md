# DataSync 独立验收报告（QA）

> 独立质量工程师出品。判卷标准：`docs/production-readiness/00-frozen-contract.md` §5 的 12 条验收口径。
> 测法见 `03-acceptance-plan.md`。**本文件只写"跑出什么"，不写"应该怎样"。**
> 立场声明：开发者的自测结论不作为证据；每条结论都附可复现命令与原始输出片段。

**被测状态（所有阶段 B 结论都对应这个状态）**

| 项 | 值 |
|---|---|
| 构建产物 | `data-sync-server/target/data-sync-server-1.0.0-SNAPSHOT.jar`，**61.7 MB**，时间戳 **2026-10-07 14:41:30** |
| 运行副本 | `.dsh-scratch/qa/app.jar`（**不在 target 下跑，避免 Windows 锁文件影响其他人打包**） |
| git HEAD | `70474fc703c5b638157a5dce78d324b28e8d4818`（工作区 206 项未提交改动，均为本次改造） |
| 运行参数 | `--spring.profiles.active=dev`（认证关）/ `prod`（认证开）；元数据库 `127.0.0.1:3408/datasync_e2e` |
| 业务库 | 源 `127.0.0.1:3407/datasync`，目标 `127.0.0.1:3408/datasync`（两个独立 MySQL 8.0.46 实例） |
| 单元测试 | core `152/0/0/1 skipped`；server `76/0/0/0`；`BUILD SUCCESS` |

---

## §0 阶段 A：验收基础设施自证（先证明"判卷工具"本身可信）

> 逻辑：如果比对工具连注入的差异都发现不了，那它给出的"一致"毫无价值。所以先做三步自证。

### 0.1 独立环境

```powershell
$qa = "D:\AI\DeepSeek Harness\data-sync\.dsh-scratch\qa"
$mysqld = "D:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe"
foreach($n in @("src","dst")){
  Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
    -Arguments @{ CommandLine = '"' + $mysqld + '" --defaults-file="' + $qa + '\my-' + $n + '.ini"' }
}
```

实测（跨多次会话复验，实例保持存活）：

```
3407 LISTEN  源库      mysqld ~300 MB
3408 LISTEN  目标库    mysqld ~280 MB
3306 LISTEN  MySQL80 服务（既有，全程未触碰其 datasync 库）
```

```
+---------+-----------+--------------------+-------------+
| @@port  | @@version | character_set_srv  | @@time_zone |
+---------+-----------+--------------------+-------------+
|    3407 | 8.0.46    | utf8mb4            | +08:00      |
+---------+-----------+--------------------+-------------+
```

**两个实例的 `sql_mode` 实际取值（夹具可复现性的前提）**

```
ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION
（3407 与 3408 完全相同；my-src.ini/my-dst.ini 未覆盖该项，即 MySQL 8.0.46 默认值）
```

由此直接推出的两条夹具行为（已实测确认，不是推断）：
- `STRICT_TRANS_TABLES` → 往 `bad_row_dst.val VARCHAR(32)` 写 120 字符报
  `ERROR 1406 (22001): Data too long for column 'val' at row 1`（不是静默截断）。
- `NO_ZERO_DATE` → 正常会话写不进 `0000-00-00`，故坏行夹具内部先 `SET SESSION sql_mode=''` 再插入。

**踩坑记录（都已解决）**

| 现象 | 根因 | 解法 |
|---|---|---|
| `Start-Process` 起的 mysqld/桩服务立刻消失 | DSH 会话结束时回收子进程 | `Invoke-CimMethod Win32_Process Create` 脱离父进程；长任务用 `run_in_background` |
| WMI 子进程无法写文件 | 沙箱对非会话内进程的限制 | 同上 |
| `Get-Content … \| mysql` 装载中文/emoji 报 `ERROR 1064` | PowerShell 管道按 GBK 重编码 | `mysql --execute="source <绝对路径>"` 直读文件字节 |
| `.ps1` 里中文乱码、报"意外的标记 }" | PS 5.1 把无 BOM 的 UTF-8 当 ANSI 读 | 加 UTF-8 BOM；`fix-ps1.ps1` 一键补 BOM + 语法检查（**编辑工具会丢 BOM，改完必跑**） |
| `compare.py count --cols id` 报 `Unknown column 'i'` | mysql CLI 把 `--cols` 当列别名 | 改写成子查询内聚合 + `AS x` |
| `java -jar <含空格路径>` 报 `Unable to access jarfile D:\AI\DeepSeek` | `Start-Process -ArgumentList` 不做引号处理 | 参数写成 `` "`"$Jar`"" `` |
| `health` 响应打成一串十进制数字 | PS 5.1 有时把 `Content` 给成 `byte[]` | `RespText` 统一 UTF-8 解码 |
| 中文提示变 `ä»»åŠ¡å·²å—ç†` | 响应头无 charset 时 PS 按 Latin-1 解码 | 走 `RawContentStream` + `UTF8.GetString` |
| `taskkill`（不带 `/F`）无法终止控制台 Java 进程 | Windows 对无窗口消息循环的进程发不出 SIGTERM | 见 §2.11 平台限制 |

### 0.2 夹具清单（`docs/production-readiness/fixtures/`）

| 文件 | 内容 |
|---|---|
| `01-schema-src.sql` | 源库 11 张表：主表 13 列覆盖 `BIGINT`/`DECIMAL(38,10)`/`DOUBLE`/`BIT(1)`/`TINYINT(1)`/`VARCHAR(200)`/`TEXT`/`BLOB`/`JSON`/`DATETIME(6)`/`DATE`/`TIME(6)`；复合主键、无主键、仅唯一键、保留字列名（`order`/`desc`/`group`/`key`/`select`/`from`）、中文表名 `中文表_订单` 与中文列名、坏行表、水位表、无索引增量列、**强制类型不兼容表** |
| `02-schema-dst.sql` | 目标库结构，**故意保留 3 处差异**：`bad_row_dst.val VARCHAR(32)`（源 200）、`type_clash_dst.amount VARCHAR(10)`（源 `DECIMAL(38,10)`，且无主键）、`type_hard_dst` 全数值列（源 日期/大文本/二进制） |

主表数据由 `.dsh-scratch/qa/gen_fixtures.py` 生成，**完全确定性**（不用 `random`），任何时间重跑得到同一份数据：

```
生成完成: 200 个 INSERT 文件, rows=100000   （77.6 MB）
装载完成: 200 个文件, 19.4 秒
行数 = 100000 / 重复主键组数 = 0 / id 范围 = 1..100000
updated_at 范围 = 2026-01-01 00:00:00.100007 .. 2026-01-17 23:16:23.199007
```

边界数据抽样（确认真的落库了）：

```
id  name_cn                        CHAR_LENGTH  updated_at
1   客户-1-🚀                       6            2026-01-01 00:00:00.100007
11  👍🏽                            2
19  xxxx…(200 个 ASCII)            200
31  中中…(200 个中文，utf8mb4 600 字节)  200
37  (空串，与 NULL 区分)            0
53  😀🚀🇨🇳👍🏽👨‍👩‍👧‍👦数据同步𝓕𝓪𝓷𝓬𝔂①⑴⒈   25
47  O'Brien \\ 反斜杠 \t 制表   → HEX=4F27427269656E205C20E58F8DE6969CE69DA0200920E588B6E8A1A8
    （0x09 制表符、0x5C 反斜杠、0x27 单引号全部原样保留）
2   flag=0（BIT(1)）   4   flag=1
23  amount=-99999999999999999999999999.9999999999（DECIMAL(38,10) 下界）
29  amount=+99999999999999999999999999.9999999999（上界）
73  doc=NULL           83  doc={"n": 83, "emoji": "👍🏽", "中文键": "值"}
```

### 0.3 比对工具自证：**能报错**才算可信

| 场景 | 注入方式 | 工具反应 |
|---|---|---|
| A 基线一致 | 两侧装同一份夹具 | `count src=100000 dst=100000 dup=0`；`digest` 两侧 SHA256 相同；`diff` 逐行一致 ✅ |
| B 内容篡改 | 目标 `UPDATE big_table SET amount=amount+1 WHERE id=5` | diff 精确指出第 5 行 `源=5.0000000005 / 目标=6.0000000005` ✅ |
| B 缺行 | 目标 `DELETE FROM big_table WHERE id=7` | diff 报 `差异行 495 处`，第 7 行起全部错位暴露 ✅ |
| C 重复行 | 目标去主键后复制一行 | `count` 报 `FAIL dst=501 dst_dup_groups=1` ✅ |

### 0.4 10 万行基线摘要

```
### big_table [canon]（JDBC 逐行规范化后流式 SHA-256，全部 13 列 × 10 万行）
源:  ROWS=100000  SHA256=3b98a01e1011a8c131f092663eee14cd298fa9bed7e0a6dc55563c7a35775df2
目标:ROWS=100000  SHA256=3b98a01e1011a8c131f092663eee14cd298fa9bed7e0a6dc55563c7a35775df2
### big_table [keydigest]（仅主键集合）
源:  SHA256=6f4c9dc86395e91c3809e79d9abc9da28394fb2559f371f6797e4dfac02c0d72
目标:SHA256=6f4c9dc86395e91c3809e79d9abc9da28394fb2559f371f6797e4dfac02c0d72
### 落盘后按字节比较
源 canon 文件 SHA256 = 591417fc622ba1620ac93f39c8f7ef5ca37794213f3fdbc9ebe4325ac582a457
目标 canon 文件 SHA256 = 591417fc622ba1620ac93f39c8f7ef5ca37794213f3fdbc9ebe4325ac582a457
两侧文件是否逐字节相同 = True
```

### 0.5 驾驶舱自证：`e2e.ps1` 对着**桩服务**跑通

桩服务 `.dsh-scratch/qa/stub_server.py`（health / datasources / tasks / trigger / records / system.info
+ Lead 冻结的完整认证契约）实测：

```
[e2e] health [200] {"status": "UP"}
[e2e] 任务已创建 id=1 name=中文任务-含emoji-🚀 mode=FULL
[e2e] 任务 1 终态=COMPLETED 用时=3.1s read=100000 write=100000
[e2e] 中文表名「中文表_订单」创建成功（JSON 中文往返正确）
--- auth-check（对桩）---
① 未登录访问 /api/tasks         PASS 401（JSON，不是 302）
③ 带 CSRF 头登录                PASS 200
⑤ 带会话访问 /api/tasks          PASS 200
⑥ 带会话、不带 CSRF 的写请求     PASS 403
⑥b curl 交叉验证同一请求         PASS 403
⑦ /actuator/health 匿名          PASS 200
```

> **⑥ 这一步差点变成假阴性。**
> 第一版 `auth-check` 把 `X-CSRF-TOKEN` 挂在登录用的 `WebRequestSession.Headers` 上，
> 而 PowerShell 的 `WebRequestSession` **会让该会话后续所有请求自动带上这个头**，
> 于是"不带 token 的写请求"其实一直带着 token → 桩返回 200 → 我一度以为是桩的守卫写错了。
> 用 `curl` 手工发同一请求（只带 `Cookie`、不带 CSRF 头）得到 **403**，才定位到是自己的测试写错了。
> **教训**：断言"某请求缺少某头"时不能靠"我没显式加"，必须证明"对端确实没收到"。

### 0.6 我自己犯的错（一并记录，避免污染结论）

1. 夹具生成器把 `DATE`/`TIME` 字面量当裸 token 输出 → 全部 `ERROR 1064`。已修正。
2. `type_clash_dst` 最初带主键，会让"无主键"与"类型不兼容"两个预检错误互相掩盖。已去主键。
3. `e2e.ps1` 用 `$id` 作局部变量——`$id`/`$PID`/`$HOME` 在 PowerShell 里是**只读自动变量**。已改名。
4. `e2e.ps1` 用 `ConvertTo-Json` 发请求体，PS 5.1 会把中文转成 `\uXXXX`。已改用 `JavaScriptSerializer`。
5. **我用错了任务创建字段名**：先用 `errorPolicyJson`（那是 **DB 列名**）→ 服务端**静默忽略** →
   `error_policy_json` 落库为 NULL → 我一度以为"skipBadRows 不生效"。改用正确的 `errorPolicy`（对象）后行为完全正确。→ 见缺陷 **D2**。
6. 编辑工具改完 `.ps1` 会丢 UTF-8 BOM（实测 4 次）。已固化为流程：**改完必跑 `fix-ps1.ps1`**。
7. **取证对象选错**：验收 6a 我把"行数快照"打在了 `big_table`（同库**另一张表**）上，而当时它正被
   另一个任务并发写入，打出 `3500 → 82500` 这种吓人的数字。任务 2 的目标表是 `big_table_not_exist`，
   不可能写到 `big_table`。**是我的取证写法瑕疵，不是被测系统缺陷**，已核实更正。

---

## §1 执行台账

| # | 验收项 | 结论 | 证据 |
|---|---|---|---|
| 1 | 构建 + 启动 + Flyway | **通过** | §2.1 |
| 2 | 10 万行全量逐行一致 | **通过** | §2.2 |
| 2b | 内存稳定性 / 真流式 | **通过** | §2.3 |
| 3 | 断点续传（kill 后重跑一致） | **通过** | §2.4 |
| 4 | 幂等（连跑 3 次不变） | **通过** | §2.5 |
| 5 | 并发触发 5 次只 1 个 RUNNING | **通过** | §2.6 |
| 6 | 预检在写数据前失败 | **通过（含一条口径澄清）** | §2.7 |
| 7 | 坏行策略 + 水位不推进 | **通过** | §2.8 |
| 8 | 口令密文 / 脱敏 / 日志无明文 | **通过** | §2.9 |
| 9 | 认证 + CSRF | **通过** | §2.10 |
| 10 | 优雅停机 CANCELLED + 水位不推进 | **部分验证（平台限制）** | §2.11 |
| 11 | 前端 build + vue-tsc | **通过**；全链路交互**未独立复验** | §2.12 |
| 12 | 单元/集成测试全绿 | **通过（数字与 Lead 完全一致）** | §2.13 |
| 补 U6 | 旧库升级路径 | **通过**（本轮补验） | §2.15 |
| 补 U7/R2 | Quartz 重启持久化 | **通过**（本轮补验） | §2.16 |
| 补 — | 明文口令升级触发条件（独立发现） | **行为与契约措辞不符** | §2.17 / D6 |

### 额外找茬项

| # | 检查项 | 结论 |
|---|---|---|
| E1 | 无 OFFSET 分页 | **通过**（静态 `OFFSET\s*\?` 0 命中；运行时内存不随行数增长） |
| E2 | 保留字 / 中文标识符 | **通过** |
| E3 | 复合主键 3000 行 | **通过** |
| E4 | 仅唯一键无主键 | **通过** |
| E5 | 无主键表预检 | **通过**（报 `SORT_KEY_MISSING` ERROR + `PK_MISSING` WARN，比预期更严格） |
| E6 | `endCursor` 语义 | **通过**（复合游标，见 §3.2） |
| E7 | DM8 标注"未验证" | **通过**（`dm8Verified=false`） |
| E8 | 增量模式绝不 TRUNCATE | **通过** |
| E9 | 标识符注入被拒 | **通过**（`IDENTIFIER_INVALID`） |
| E10 | 错误响应不泄漏堆栈/SQL/口令 | **通过** |
| E11 | 元数据库防护 `TARGET_IS_METADATA` | **通过** |

---

## §2 逐条验收证据

### 2.1 验收 1 —— 构建 + 启动 + Flyway ✅

在**全空**的 `datasync_e2e` 上启动（验证从零迁移）：

```powershell
java -jar .dsh-scratch\qa\app.jar --server.port=18080 --spring.profiles.active=dev `
  --spring.datasource.url="jdbc:mysql://127.0.0.1:3408/datasync_e2e?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai&characterEncoding=utf8" `
  --spring.datasource.username=root --spring.datasource.password=qa_root_123 `
  --spring.quartz.properties.org.quartz.scheduler.instanceName=QaScheduler
```

```
INFO org.flywaydb.core.Flyway - All configured schemas are empty; a baseline marker will not be added
INFO JdbcTableSchemaHistory - Creating Schema History table `datasync_e2e`.`flyway_schema_history` ...
INFO DbMigrate - Migrating schema `datasync_e2e` to version "1 - baseline schema"
INFO DbMigrate - Migrating schema `datasync_e2e` to version "2 - production upgrade"
INFO DbMigrate - Migrating schema `datasync_e2e` to version "3 - quartz schema"
INFO DbMigrate - Successfully applied 3 migrations to schema `datasync_e2e`, now at version v3 (execution time 00:00.498s)
INFO o.s.s.quartz.LocalDataSourceJobStore - Using db table-based data access locking (synchronization).
INFO o.s.s.quartz.LocalDataSourceJobStore - JobStoreCMT initialized.
INFO o.s.boot.tomcat.TomcatWebServer - Tomcat started on port 18080 (http)
```

```
flyway_schema_history:  1|baseline schema|1    2|production upgrade|1    3|quartz schema|1   （全部 success=1）
QRTZ 表数量 = 11
表与列数：datasource=10  sync_task=25  sync_record=19  sync_error=9

sync_task  ：…,cursor_value,order_column,…,safety_lag_seconds,lookback_seconds,
             full_sync_strategy,error_policy_json,enabled,…            ← 新增 7 列全部到位，incr_value 保留
sync_record：…,skipped_rows,…,start_cursor,end_cursor,read_millis,write_millis,
             total_millis,…,run_key,preflight_json,…                  ← 新增 8 列全部到位
sync_error ：id,record_id,task_id,phrase→phase,row_key,message,row_data,retryable,created_at  ← 与契约一致
唯一约束：uk_sync_record_run_key(run_key) NON_UNIQUE=0                ← 幂等落库保障存在
```

`/api/system/info`：

```json
{"version":"1.0.0-SNAPSHOT","dbType":"MySQL","dm8Verified":false,"activeTasks":0,"runningTasks":0,
 "totalTasks":0,"totalRecords":0,"cachedPools":0,"poolSummary":[],"runningTaskIds":[],
 "startTime":"2026-10-07T14:43:54.76","javaVersion":"25","securityEnabled":false,"csrfToken":null}
```
→ `dm8Verified:false` 满足 E7。

### 2.2 验收 2 —— 10 万行全量同步逐行一致 ✅

```
[e2e] 触发结果: {"success":true,"code":"ACCEPTED","message":"任务已受理，正在执行","recordId":1}
执行记录：status=COMPLETED readRows=100000 writeRows=100000 skippedRows=0
          readMillis=509  writeMillis=3467  totalMillis=4290  runKey=1-1
```

```
OK   big_table -> big_table: src=100000 dst=100000 dst_dup_groups=0
### big_table [canon] 源:  ROWS=100000 SHA256=3b98a01e1011a8c131f092663eee14cd298fa9bed7e0a6dc55563c7a35775df2
                     目标:ROWS=100000 SHA256=3b98a01e1011a8c131f092663eee14cd298fa9bed7e0a6dc55563c7a35775df2  => 一致
### big_table [keydigest] 两侧 SHA256=6f4c9dc86395e91c3809e79d9abc9da28394fb2559f371f6797e4dfac02c0d72  => 一致
### 逐行差异: 源 100000 行 / 目标 100000 行  => 逐行完全一致
```

**关键点**：目标侧 `SHA256=3b98a01e…` 与 §0.4 的**手工装载**基线**完全相同** ——
被测服务写出的 10 万行与我独立生成的夹具逐字节一致（含 `BIT(1)`、`BLOB`、`DECIMAL(38,10)`、
`DATETIME(6)` 微秒、emoji、中文、NULL 与空串的区分）。

### 2.3 验收 2b —— 内存稳定性 / 真流式 ✅

```powershell
# 同步进行中每 250ms 采样
(Get-Process -Id <srvPid>).WorkingSet64 / PrivateMemorySize64
```

```
同步前基线: workingSet=573MB private=657MB
第 1 次: COMPLETED read=100000 write=100000 耗时=4102ms 峰值workingSet=590MB 峰值private=675MB
第 2 次: COMPLETED read=100000 write=100000 耗时=4014ms 峰值workingSet=583MB 峰值private=667MB
第 3 次: COMPLETED read=100000 write=100000 耗时=3965ms 峰值workingSet=580MB 峰值private=666MB
```
```
run,elapsed_ms,status,readRows,writeRows,workingSetMB,privateMB,threads
1,68,RUNNING,0,0,574,658,69
...
3,3916,RUNNING,0,0,427,475,73        ← chunk 提交后 GC 回落
3,4196,COMPLETED,100000,100000,427,475,73
```

**判定**：内存**不随行数增长**（10 万行峰值仅 +17 MB，随后回落到 427 MB，比基线更低）。
静态佐证：`data-sync-core` 中 `OFFSET\s*\?` 命中 **0**；`MySqlDialect.streamFetchSize()` 返回 `Integer.MIN_VALUE`。

### 2.4 验收 3 —— 断点续传（kill 后重跑逐行一致）✅

**为什么用 100 万行**：6500 行全量同步只要 **179 ms**（`totalMillis=179`），强杀窗口太窄、
结论不可复现；30 万行只要 3.76 s。改用 100 万行专用表 `resume_src`，窗口 ~12 s。

```
SIGKILL 前目标行数（部分数据）= 522000
taskkill: SUCCESS: The process with PID 1200 has been terminated.
进程是否存活: 否(已杀死)
--- 强杀后 sync_record ---
id  task_id  status     read_rows  write_rows  end_time
24  15       RUNNING    0          0           NULL          ← 崩溃留下的孤儿记录
23  15       COMPLETED  300000     300000      2026-10-07 14:51:22
目标 resume_dst 行数（强杀后）= 571000
```

重启同一 JAR（**同时验证启动自愈**）：

```
--- 重启后 sync_record ---
24  15  FAILED  0  0  2026-10-07 14:52:25.619974     ← RUNNING 被启动自愈收尾为 FAILED（没伪装成 SUCCESS）
--- 重启后重新触发同一任务（应用级锁已清理）---
触发响应: {"success":true,"code":"ACCEPTED","message":"任务已受理，正在执行","recordId":25}
```

重跑与逐行校验：

```
重跑终态=COMPLETED read=1000000 write=1000000 耗时=12328ms
目标 resume_dst 行数=1000000      目标重复 id 分组数=0
### resume_src -> resume_dst [canon]
源:  ROWS=1000000 SHA256=47956ab7a7d0ea0cac0c7ef914171b8ec012a27d0655d355892bd887a71d4c64
目标:ROWS=1000000 SHA256=47956ab7a7d0ea0cac0c7ef914171b8ec012a27d0655d355892bd887a71d4c64 => 一致
```

**结论：无重复、无丢失。** 522000 → 571000（强杀瞬间）→ 重启后 1000000，逐行摘要与源完全一致。
崩溃时已写入的 571000 行没有被回滚成不一致状态，重跑靠幂等 upsert 补齐。

### 2.5 验收 4 —— 幂等性 ✅

```
（此前已跑过 4 次：1 次验收 2 + 3 次内存采样）
执行前: 目标行数 = 100000
第 1 次: {"success":true,"code":"ACCEPTED","recordId":5}  终态=COMPLETED read=100000 write=100000 目标行数=100000 用时=3970ms
第 2 次: {"success":true,"code":"ACCEPTED","recordId":6}  终态=COMPLETED read=100000 write=100000 目标行数=100000 用时=3868ms
第 3 次: {"success":true,"code":"ACCEPTED","recordId":7}  终态=COMPLETED read=100000 write=100000 目标行数=100000 用时=3851ms
执行后: 目标行数 = 100000
PASS 行数不变（100000 → 100000）
### big_table [canon] 源/目标 SHA256=3b98a01e1011a8c131f092663eee14cd298fa9bed7e0a6dc55563c7a35775df2 => 一致
OK   big_table: src=100000 dst=100000 dst_dup_groups=0
```

**累计 7 次执行**，行数恒为 100000，内容摘要恒为 `3b98a01e…`。
（FULL+TRUNCATE 的"重跑不变"由 TRUNCATE+重写保证；**真正的幂等 upsert 路径**在 §2.4 的 100 万行重跑里验证。）

### 2.6 验收 5 —— 并发触发同一任务 5 次 ✅

```
第 1 个并发请求: HTTP=200 {"success":true, "code":"ACCEPTED",   "message":"任务已受理，正在执行","recordId":8}
第 2 个并发请求: HTTP=200 {"success":false,"code":"TASK_RUNNING","message":"任务正在执行中，已忽略本次触发","recordId":null}
第 3 个并发请求: HTTP=200 {"success":false,"code":"TASK_RUNNING","message":"任务正在执行中，已忽略本次触发","recordId":null}
第 4 个并发请求: HTTP=200 {"success":false,"code":"TASK_RUNNING","message":"任务正在执行中，已忽略本次触发","recordId":null}
第 5 个并发请求: HTTP=200 {"success":false,"code":"TASK_RUNNING","message":"任务正在执行中，已忽略本次触发","recordId":null}
ACCEPTED=1  被拒=4
=== 立刻查 RUNNING 记录（DB 级证据）===   RUNNING 记录数 = 1
=== 并发批次结束后终态=COMPLETED
sync_record 条数 7 → 8（并发 5 次只新增 1 条）
```

**判定通过**：只有 1 个实例真正运行；其余 4 次返回 HTTP 200 + `success:false` + **明确中文提示**
（不是 500、不是英文堆栈）；DB 里 RUNNING 恰好 1 条；`sync_record` 只新增 1 条。
同时验证了 **Lead 的 R1**（应用级锁不再泄漏）：并发批次结束后任务**仍能被正常触发**（见 §2.5 连续 3 次）。

### 2.7 验收 6 —— 预检在写任何数据之前失败 ✅（含一条口径澄清）

| 场景 | 预检结果 | 执行结果 | 目标表行数（前→后） | 判定 |
|---|---|---|---|---|
| 6a 目标表不存在 | `hasError=true` `ERROR:DST_TABLE_MISSING` | `FAILED`：`预检未通过：DST_TABLE_MISSING: 目标表[big_table_not_exist]不存在或没有任何列` | 该表不存在，无行可写 | **通过** |
| 6b 增量字段不存在 | `hasError=true` `ERROR:INCR_COLUMN_MISSING` | `FAILED`：`预检未通过：INCR_COLUMN_MISSING: 增量字段[no_such_col]在源表（或自定义 SQL 结果集）中不存在` | `watermark_dst` 0 → 0 | **通过** |
| 6c 类型不兼容（DECIMAL→VARCHAR） | `hasError=false` `WARN:PK_MISSING` | `FAILED`：`MAP 阶段处理失败（skipBadRows=false，快速失败）：字符串长度 31 超过目标列 amount(VARCHAR) 的最大长度 10（拒绝静默截断，请扩大目标列或先在源侧处理）` | `type_clash_dst` 0 → 0 | **通过**（口径澄清见下） |
| 6d 目标无主键 | `hasError=true` `WARN:PK_MISSING` + `ERROR:SORT_KEY_MISSING` | `FAILED`：`预检未通过：SORT_KEY_MISSING: 源表[nopk_table]没有主键或唯一键，无法做键集分页（禁止 OFFSET 分页）` | `nopk_table` 0 → 0 | **通过**（比预期更严格） |

**6c 的口径澄清（重要，不是失败）**：契约 §5.6 把"类型不兼容"列为应报 `TYPE_INCOMPATIBLE` 的预检场景，
但 `DECIMAL(38,10) → VARCHAR(10)` 在 core 的 `TypeCompatibility` 里被**有意**判为 `RISKY`（WARN）而非
`INCOMPATIBLE`（ERROR），理由是"目标为字符类型时数值→字符有明确定义，只是可能超长"，
代码注释写明"超长值会成为坏行（不会静默截断）"。实测行为与注释完全一致：预检放行，
运行期在 **MAP 阶段**硬失败、**一个字都没写**。**这是安全的，只是与契约举例的措辞不符** → 见缺陷 D5。

为验证 `TYPE_INCOMPATIBLE` 这条路径**真的能触发**，我补了夹具 `type_hard_src/type_hard_dst`
（源 日期/大文本/二进制 → 目标 全数值，有主键避免干扰）：

```
taskId=6  hasError=True
  [ERROR] TYPE_INCOMPATIBLE :: 源列[dt_col(DATETIME)]无法安全转换到目标列[dt_col(BIGINT)]：日期时间无法安全转换为数值（驱动会静默写成 20260101 这类垃圾值）
  [ERROR] TYPE_INCOMPATIBLE :: 源列[txt_col(TEXT)]无法安全转换到目标列[txt_col(INT)]：文本大字段通常存放自由文本，无法安全转换为数值
  [ERROR] TYPE_INCOMPATIBLE :: 源列[bin_col(BLOB)]无法安全转换到目标列[bin_col(BIGINT)]：二进制列无法安全转换为数值
触发后记录: FAILED|预检未通过：TYPE_INCOMPATIBLE: ...
type_hard_dst 行数: 执行前=0 执行后=0  PASS 未写入
```

**结论**：`TYPE_INCOMPATIBLE` 的 ERROR 判定、三层拒绝（预检→MAP→写前）与"拒绝静默截断"都成立。

### 2.8 验收 7 —— 坏行策略 + 水位 ✅

**先把两条路径分离**（读阶段坏行必须先拿掉，否则它在读就炸、走不到写阶段）：

源 `bad_row_src` 5 行：`id=3` 的 `val` 有 120 字符（目标列 `VARCHAR(32)` → 写阶段炸）；
`id=4` 的 `zero_dt` 是 `0000-00-00 00:00:00`（Connector/J 默认拒绝 → 读阶段炸）。

**读阶段**（保留 id=4）：

```
7a  skipBadRows=false：status=FAILED read=0 write=0 skipped=0
    errorMessage=读取源数据失败（已重试 3 次）：Zero date value prohibited
    cursor_value 执行前=<NULL>  执行后=<NULL>        ← 水位未推进 ✅
    目标行数=0                                       ← 一个字都没写 ✅
7b  skipBadRows=true ：status=FAILED read=0 write=0 skipped=0
    errorMessage=读取源数据失败（已重试 3 次）：Zero date value prohibited
    cursor_value 执行后=<NULL>；目标行数=0
```

**写阶段**（删掉 id=4，只留 id=3）：

```
7c  skipBadRows=false：status=FAILED read=4 write=0 skipped=0
    errorMessage=MAP 阶段处理失败（skipBadRows=false，快速失败）：字符串长度 120 超过目标列 val(VARCHAR) 的最大长度 32（拒绝静默截断，请扩大目标列或先在源侧处理）
    cursor_value=<NULL>（未推进 ✅）  目标行数=0  sync_error 条数=1
7d  skipBadRows=true ：status=COMPLETED read=4 write=3 skipped=1
    目标行数=3   目标 id=1,2,5                      ← 坏行 id=3 被跳过，其余 3 行正常写入 ✅
    sync_error 条数=1
    phase=MAP  row_key=id=3  retryable=0
    msg=字符串长度 120 超过目标列 val(VARCHAR) 的最大长度 32（拒绝静默截断，请扩大目标列或先在源侧处理）
```

**水位推进的判定依据（设计决策，QA 明确写下立场）**

- **跳过 = 已知并记录 → 允许前进**。坏行已显式写入 `sync_error` 并计入 `skippedRows`，
  运维有据可查、可单独补数；若此时仍卡住水位，任务会永久卡在同一行上，反而变成"不可自愈"。
- **失败 = 未知 → 必须停下**。任务整体失败时我们没有"这一行已被处理"的任何记录，
  推进水位就等于把这段数据永久跳过（这正是原设计"读失败也推进 `incrValue`"的老毛病）。
- 实测两种情况都符合上述语义：**失败时水位一律未推进；跳过时任务正常完成**。
- `maxSkipRows` 超限时整体失败（防止坏行面扩大被静默吞掉）——本次未突破上限（100）。

**一条实现事实（不是缺陷，但必须写清楚）**：`skipBadRows` **只对行级（MAP/WRITE）异常生效**，
**不覆盖读阶段（READ）异常**——零日期那次，`skipBadRows=true` 与 `false` 的结果完全一样（都 FAILED）。
好在这条路径仍然安全：**水位不推进、目标一个字不写** → 见缺陷 D4（建议写进文档）。

### 2.9 验收 8 —— 口令密文 / 脱敏 / 日志无明文 ✅

```
--- 库内存的是密文 ---
id  name         pwd_prefix                          pwd_len
1   qa-src-3407  ENC(Vgn0igMbB2aewJ26c7l3BkuWidag     57
2   qa-dst-3408  ENC(NuqzZx7cO5G/aOawUdWZRVMyklxM     57
满足 ENC( 前缀 / 总数 = 2/2
password 列含明文 qa_root_123 的行数 = 0 （必须 0）

--- API 响应脱敏 ---
id=1 name=qa-src-3407 password=[]        ← 空，不是掩码也不是密文
id=2 name=qa-dst-3408 password=[]
PASS 响应体无明文口令

--- 日志 ---
PASS 全部日志文件均无明文口令 qa_root_123
（覆盖 server-e2e / server-e2e2 / server-prod / server-quartz1/2 / prod-neg / ci-probe 等全部日志）

--- 仓库源码 ---
命中 3 处，全部在 docs/production-readiness/03-acceptance-plan.md（我自己写的测试口令示例），非产品代码

--- 配置文件 ---
application-prod.yml: password: ${DATASYNC_DB_PASSWORD:?MISSING_ENV_DATASYNC_DB_PASSWORD}
                      password: ${DATASYNC_ADMIN_PASSWORD:?MISSING_ENV_DATASYNC_ADMIN_PASSWORD}
application.yml     : password: ${DATASYNC_DB_PASSWORD:123456}   （dev 默认值，prod 被 :? 覆盖）
```

`ENC(` 前缀 + 57 字符长度符合 AES-GCM（12 字节 IV + 密文 + 16 字节 tag，Base64）。

### 2.10 验收 9 —— 认证 + CSRF ✅

**prod profile 反例（Lead 的 R3）** —— `SPRING_PROFILES_ACTIVE=prod` 且不提供任何 `DATASYNC_*`：

```
进程已退出=True  退出码=非 0
日志相关片段：
  1) DATASYNC_DB_HOST 未配置（元数据库主机）
  2) DATASYNC_DB_NAME 未配置（元数据库名）
  3) DATASYNC_DB_USER 未配置（元数据库账号）
  4) DATASYNC_DB_PASSWORD 未配置（元数据库口令）
  5) DATASYNC_ADMIN_PASSWORD 未配置（管理界面登录口令）
  6) DATASYNC_SECRET_KEY 未配置 —— 它是数据源口令 AES-GCM 加密的密钥，必须显式提供
```
→ **fail-fast 成立，且把缺失的变量名逐个列清**（不是抛一个看不懂的 `Malformed database URL`）。
**正例** —— 变量齐备时 prod profile 正常启动（`服务就绪=True`）。

**认证流程（用 curl 交叉验证，避免 PowerShell 会话干扰）**：

```
9.1  未登录 GET /api/tasks   HTTP=401  {"status":401,"code":"UNAUTHENTICATED","message":"未登录或会话已失效，请重新登录"}
9.2  匿名 GET /api/auth/csrf {"securityEnabled":true,"headerName":"X-CSRF-TOKEN","parameterName":"_csrf","token":"274675f0-…"}
9.3  带 CSRF 头登录          HTTP=200  {"success":true,"authenticated":true,"username":"admin","csrfToken":"274675f0-…","message":"登录成功"}
9.4  带会话 GET /api/tasks   HTTP=200  {"content":[{...}]}
9.5  会话但无 CSRF 写请求    HTTP=403  {"status":403,"code":"CSRF_INVALID","message":"CSRF 校验失败：会话令牌缺失或已过期，请重新获取 CSRF 令牌后再提交"}
9.6  会话 + CSRF 写请求      HTTP=200  {"id":3,"name":"csrf-with-token",...,"password":""}   ← 返回值同样脱敏
9.7  匿名 /actuator/health   HTTP=200
9.11 全新会话 + 错误口令     HTTP=401  {"status":401,"code":"BAD_CREDENTIALS","message":"用户名或密码错误"}
9.9  登出                    HTTP=200  {"success":true,"message":"已退出登录"}
9.10 登出后再访问 /api/tasks HTTP=401
```

**判定通过**：`/api/**` 未认证返回 **401 JSON 而不是 302**（与冻结契约一致）；
CSRF 缺失 **403**、带上后 **200**；`health` 匿名可读；错误口令 401 + `BAD_CREDENTIALS`；登出后会话失效。

### 2.11 验收 10 —— 优雅停机 ⚠️ 部分验证（平台限制）

**能验证的部分（水位语义）**：

```
停机前 cursor_value = 2026-09-01 00:00:05.000000
停机后 cursor_value = 2026-09-01 00:00:05.000000
PASS 水位未推进
```

**未能验证的部分（CANCELLED 标记）**：Windows 上无法对这个进程发真正的 SIGTERM。

```
taskkill（不带 /F）: ERROR: The process with PID 43940 (child process of PID 37008) could not be terminated.
                     Reason: This process can only be terminated forcefully (with /F option).
进程是否仍存活=True  等待耗时=75.1s
停机记录: id=30 task_id=17 status=RUNNING read=0 write=0 end_time=NULL
```

**原因（平台事实，不是被测系统缺陷）**：Windows 没有 POSIX 信号；`taskkill` 不带 `/F` 只能给
**有窗口消息循环**的进程发 `WM_CLOSE`，而 `java -jar` 的控制台进程二者皆无，因此优雅停机的入口
（`server.shutdown=graceful` + JVM shutdown hook）在 Windows 上没有可用的外部触发方式。

**我用"次强"手段覆盖了等价的安全属性**（见 §2.4）：

- 强杀后孤儿 `RUNNING` 记录（id=24）**在重启时被启动自愈收尾为 FAILED**，没有伪装成 SUCCESS；
- 崩溃时已写入的 571000 行**没有**产生不一致（重跑补齐到 1000000，逐行摘要一致）。

**结论**：与"优雅停机"直接相关的两条安全属性（**水位不推进**、**不留假 SUCCESS**）都已独立验证；
但"**运行中的同步被标记为 CANCELLED**"这一条**我无法在 Windows 上验证** → §5 U3。

### 2.12 验收 11 —— 前端构建 + 类型检查 ✅（全链路未独立复验）

**跑测时点与状态快照**（证明是对哪个状态跑的）：

```
时间: 2026-10-07 15:24:23
HEAD: 70474fc703c5b638157a5dce78d324b28e8d4818
改动文件数: 206     'A' x 133    'M' x 47    'D' x 19    'AM' x 1    '??' x 6
data-sync-web 改动（前 15）：package.json / App.vue / api/auth.ts(新) / api/datasource.ts /
  api/record.ts(新) / api/request.ts / api/system.ts(新) / api/task.ts / components/CopyText.vue(新) /
  components/PreflightDialog.vue(新) / components/PreflightPanel.vue(新) / components/RecordErrorsDialog.vue(新) /
  composables/useAuth.ts(新) / composables/usePagination.ts / composables/useWebSocket.ts
```

```powershell
cd data-sync-web; npm run type-check; npm run build
```

```
npm run type-check → exit=0（无输出 = 零类型错误）
npm run build      → exit=0
  dist/assets/Login-CjKwazhj.js            4.12 kB
  dist/assets/PreflightPanel-DHDkIrcv.js   8.62 kB
  dist/assets/TaskRecords-razN3crN.js     21.36 kB
  dist/assets/TaskForm-DHUEYkfS.js        24.41 kB
  ✓ built in 6.26s
```

**未复验的部分**：任务书要求的"页面能完成 建数据源 → 预检 → 建任务 → 执行 → 看进度/错误 全链路"
**我没有独立跑**——Lead 转述的 `ui` 自测（无头 Chrome + CDP 67+12 条断言）**不是我的证据**。
我没有浏览器自动化工具（只有 `view_screen` 截 Windows 桌面）→ §5 U4。

### 2.13 验收 12 —— 单元/集成测试全绿 ✅（数字与 Lead 完全一致）

```powershell
cd D:\AI\DeepSeek Harness\data-sync
& "S:\installationFree\apache-maven-3.9.15\bin\mvn.cmd" -B -s "<settings>" test
```

```
core（17 个测试类）：
  ContractSignatureTest               6/0/0/0     ← 契约签名断言
  DefaultSyncEngineMySqlTest         19/0/0/1     ← 唯一 skipped（-Ddatasync.it.bigrows 控制）
  DefaultPreflighterMySqlTest        17/0/0/0
  MetadataGuardTest                  10/0/0/0     ← TARGET_IS_METADATA
  TypeCompatibilityTest              12/0/0/0
  DefaultValueConverterTest          18/0/0/0
  MySqlDialectTest / Dm8DialectTest / CursorCodecTest / IdentifiersTest /
  JdbcMetadataReaderTest / SyncEventBusTest / MySqlPrivilegesTest / CustomSqlGuardTest ...
  [INFO] Tests run: 152, Failures: 0, Errors: 0, Skipped: 1

server（10 个测试类）：
  ServerSecurityIntegrationTest      16/0/0/0     ← 未认证拦截 + CSRF
  SyncTaskServiceTest                14/0/0/0
  CredentialCipherTest               11/0/0/0     ← 加解密与历史明文升级
  GlobalExceptionHandlerTest          9/0/0/0
  ConnectionPoolRegistryTest          8/0/0/0
  TaskRunServiceIntegrationTest       5/0/0/0     ← 每任务互斥
  RunRecordStoreIntegrationTest       4/0/0/0     ← 含 run_key 唯一约束冲突用例
  RunLogTest                          4/0/0/0
  SyncLogWebSocketFilterTest          3/0/0/0
  StartupSelfHealingIntegrationTest   2/0/0/0     ← 崩溃后 RUNNING 收尾
  [INFO] Tests run: 76, Failures: 0, Errors: 0, Skipped: 0

[INFO] data-sync-core ..................................... SUCCESS [ 11.395 s]
[INFO] data-sync-server ................................... SUCCESS [ 21.983 s]
[INFO] BUILD SUCCESS
```

**两次数值完全一致**（Lead 报 core 152/0/0/1、server 76/0/0/0；我独立跑出同样数字）。
契约要求的关键路径都有对应测试类（预检、键集分页、水位、幂等 upsert、脱敏、互斥、自愈）。

### 2.14 E 系列

**E2 保留字与中文标识符** ✅ `reserved_table`（`order`/`desc`/`group`/`key`/`select`/`from`）50 行、
中文表名 `中文表_订单`（列名 主键/订单号/金额/客户名称/创建时间/备注）200 行 → 同步成功、逐行摘要一致。

**E9 标识符注入** ✅ `Identifiers` + `MetadataGuard` 拦截，`MetadataGuardTest` 覆盖
`datasync.sync_task` / `SYNC_TASK` / `qrtz_locks` 等变体。

**E10 错误响应不泄漏** ✅ 所有失败响应体统一为
`{"timestamp","status","code","message","details"}`，只有中文消息与稳定错误码，**无堆栈、无 SQL、无口令**。

**E11 元数据库防护 `TARGET_IS_METADATA`** ✅ 把任务目标数据源**指向元数据库本身**
（`3408/datasync_e2e`，目标表 `datasource`）：预检 `hasError=True` 且含 `[ERROR] TARGET_IS_METADATA`，
执行被阻止，`datasource` 表行数在执行前后完全一致。单测覆盖 `datasource`/`sync_task`/`sync_record`/
`sync_error`/`flyway_schema_history`/`QRTZ_TRIGGERS`/`qrtz_locks`（大小写不敏感）/带 schema 前缀写法。

### 2.15 U6 —— 旧库升级路径 ✅（本轮补验，独立复现）

Lead 用用户真实旧库验过一次。我按要求**用自己的 3408 实例独立复现**。
旧结构不是照抄的：`V1__baseline_schema.sql` 的列顺序就是历史演进顺序，
所以"旧结构"= V1 **去掉 V2 新增的列**（`sync_task` 去 7 列、`sync_record` 去 8 列）**且无 `sync_error` 表**，
由 `.dsh-scratch/qa/gen_legacy_ddl.py` 从 V1 程序化派生（同时把引用被删列的索引一并去掉，
否则 MySQL 会报 `Unknown column`——我第一版就踩了这个坑）。

```powershell
& $QaPython "$qa\gen_legacy_ddl.py" --v1 "<V1>.sql" --out "$qa\fixtures\legacy_schema.sql"
# 建 datasync_legacy 库 → 应用旧结构 → 灌旧数据（含 incr_value 历史水位）→ 启动应用指向它
```

**升级前**（确认确实是旧结构，不是我自己建错的）：

```
sync_task 列数=18 （期望 18）   sync_record 列数=11 （期望 11）
sync_error 存在=0              flyway_schema_history 存在=0
升级前：datasource=2  sync_task=2  sync_record=3
incr_value：任务1=2026-07-15 10:00:00.123456   任务2=50000
```

**Flyway 行为（关键：旧库必须被 baseline 而不是被 V1 重建/清库）**：

```
JdbcTableSchemaHistory - Schema history table `datasync_legacy`.`flyway_schema_history` does not exist yet
JdbcTableSchemaHistory - Creating Schema History table `datasync_legacy`.`flyway_schema_history` with baseline ...
DbBaseline   - Successfully baselined schema with version: 1
DbMigrate    - Migrating schema `datasync_legacy` to version "2 - production upgrade"
DbMigrate    - Migrating schema `datasync_legacy` to version "3 - quartz schema"
DbMigrate    - Successfully applied 2 migrations to schema `datasync_legacy`, now at version v3 (execution time 00:01.392s)
```

**升级后核对**：

```
sync_task   列数 18 → 25 （期望 18 → 25）
sync_record 列数 11 → 19 （期望 11 → 19）
sync_error 建出=1   QRTZ 表数=11
升级后：datasource=2  sync_task=2  sync_record=3        → PASS 零数据损失
任务1 cursor_value=2026-07-15 10:00:00.123456 （= 旧 incr_value）    → PASS 历史水位继承成功
任务2 cursor_value=50000                     （= 旧 incr_value）
中文/emoji 未被破坏：任务1 name=旧任务A-含emoji🚀 CHAR_LENGTH=12
  HEX=E697A7E4BBBBE58AA1412DE590AB656D6F6A69F09F9A80（含 F09F9A80 = 🚀，字节级完整）
```

**结论**：旧库升级路径**通过**——baseline→V2→V3 正确，列数、数据行数、历史水位、中文与 emoji 全部无损。

### 2.16 U7 / R2 —— Quartz JDBC JobStore 重启持久化 ✅（本轮补验）

```
--- 重启前 ---
JOB_NAME      JOB_GROUP   IS_NONCONCURRENT  IS_DURABLE
syncJob_18    syncTasks   1                 0
重启前 JOB 数=1  TRIGGER 数=1

--- 杀掉进程并重启 ---
重启后 pid=20724
--- 重启后 ---
JOB_NAME      JOB_GROUP   IS_NONCONCURRENT  IS_DURABLE
syncJob_18    syncTasks   1                 0
重启后 JOB 数=1  TRIGGER 数=1
PASS Job 在重启后仍然存在（1 → 1）
QRTZ 表数 = 11
重启后任务状态：{"id":18,"name":"qa-quartz-cron","cronExpression":"0 0/5 * * * ?","enabled":true,"status":"ENABLED",...}

--- JobStore 实现类（必须不是 RAMJobStore）---
o.s.s.quartz.LocalDataSourceJobStore - Using db table-based data access locking (synchronization).
o.s.s.quartz.LocalDataSourceJobStore - JobStoreCMT initialized.
Using job-store 'org.springframework.scheduling.quartz.LocalDataSourceJobStore' - which supports persistence.
```

**结论通过**：`IS_NONCONCURRENT=1`（`@DisallowConcurrentExecution` 生效）、Job 与 Trigger 在**重启后仍在**、
JobStore 是 DB 持久化实现（**不是 `RAMJobStore`**）、任务重启后仍为 `ENABLED`。
这就完整验证了 Lead 的 R2（"生产配置下应用起不来 / Job 丢失"两个风险点都不存在）。

### 2.17 D6 复验（明文口令升级）✅ —— 三条路径独立复现

被测产物 `app-d1fix.jar`（15:53:34 / SHA256 `74C487A7317FB0EA`）。**关键设计**：造两条
「明文口令 + **端口连不上**（13306/13307）」的数据源，**全程不做连接测试、不跑同步** ——
"连不上也必须升级"才是 D6 的要害。

**路径 1 · 启动扫描**（应用启动**前**就已植入明文）：

```
启动前：101 状态 = 明文 planted_plain_101 ；102 状态 = 明文 planted_plain_102
启动应用后：
  101 状态 = cipher        102 状态 = cipher
  日志：WARN c.d.s.s.LegacyPasswordUpgrader - 启动扫描：2 条数据源的历史明文口令
        已升级为 AES-GCM 密文（无需数据库连通性）
```
→ 我自己的库内查询（不是采信实现方日志）显示**两条都变成了 `cipher`**，日志与之互证。

**路径 2 · 读取路径**（Lead 指定的最严格路径：先启动完，**之后**才改回明文，再只调一次 GET）：

```
改回明文后：101 = 明文 planted_after_start_101 ；102 = 明文 planted_after_start_102
GET /api/datasources/101 → HTTP=200，响应里 "password":""（不回显）
GET 之后：      101 = cipher     ← 期望达成
再 GET 列表后：  102 = cipher     ← 期望达成
响应体里是否回显明文 = PASS 无回显
```
→ **单次 GET 即完成升级，且不需要能连上**。这条正是我原报告 D6 指出的失效场景，现在修好了。

**路径 3 · 建池兜底**：保留，本次未单独构造（前两条已覆盖"无连通性"这一要害）。

### 2.18 D2 复验（未知字段 400 / `errorPolicyJson` 别名）✅

```
--- D2a 拼错字段 errorPolicyy → 期望 400 UNKNOWN_FIELD ---
HTTP=400
body={"timestamp":"…","status":400,"code":"UNKNOWN_FIELD",
      "message":"请求体包含未知字段：errorPolicyy。请检查字段名拼写（接口字段名与数据库列名不一定相同：错误处理策略请用 errorPolicy，也接受 DB 列名风格的 errorPolicyJson）",
      "details":["errorPolicyy"]}
PASS 400 + UNKNOWN_FIELD，且消息里点名了该字段

--- D2b 合法别名 errorPolicyJson（前端 TaskForm.vue 一直在用）→ 期望 200 且落库非空 ---
HTTP=200  落库 error_policy_json = {"skipBadRows":true,"maxSkipRows":42}
PASS 别名生效并落库   ← 我原报告 D2 的"静默忽略"已不复现

--- D2c 合法对象 errorPolicy → 期望 200 且落库 ---
HTTP=200  落库 = {"skipBadRows":true,"maxSkipRows":7}
```

**判定**：D2 的**两个方向**都验了（拒绝拼错 + 接受别名），且**别名必须继续被接受**这一点特意保留 ——
因为前端 `TaskForm.vue` 整包提交里含 `errorPolicyJson`，若只验拒绝就会把前端弄坏。
错误消息还主动解释了"接口字段名与数据库列名不一定相同"，这正是我当初踩坑的地方，属于**对症的改进**。


契约 §4.2 写的是"**首次读取时**自动升级"。我用干净库 `u6c_probe` 做了判定实验：

```
1) 库里两条数据源都是明文 qa_root_123（口令**正确**，排除"连不上导致升级没跑"的干扰）
   11  明文口令-正确-源库     qa_root_123   is_enc=0
   12  明文口令-正确-目标库   qa_root_123   is_enc=0

3) 只做 GET /api/datasources 与 GET /api/datasources/11 → 回查：
   11   qa_root_123   is_enc=0        ← GET 读取**不触发**升级
   12   qa_root_123   is_enc=0

4) 跑一次同步（成功建立连接池）→ 回查：
   11   ENC(/LGPk3d5c3Hl2w5saIJGenPob5WuKjRZw8ZhvKNEhW89hGLQ9HRz)   is_enc=1
   12   ENC(sUhL9jYpQSBpPo+4yyYwE/wCgpcjbLFl/mubNiODsir9dJgy0mD4)   is_enc=1
   同步结果：status=COMPLETED read=6500 write=6500

5) 升级后再跑一次同步（回归：升级不能把能用的连接弄坏）：
   status=COMPLETED read=6500 write=6500        ← PASS 升级后仍能正常连库
```

**代码依据**：升级只在 `ConnectionPoolRegistry.get()`（第 58 行 `upgradeLegacyPassword`）路径上执行，
即**真正使用该数据源建连接池**时；`DataSourceService` 的 GET 路径（第 144 行）只做 `decrypt`，不回写。

**另一条必须写下来的行为**：如果数据源的口令本身是错的（连不上），
`createEntry` 抛异常 → 升级**也不会发生** → **明文会无限期留在库里**。
实测：旧库 `datasync_legacy` 里 id=2 的口令 `another-plain-pwd` 在多次 GET 之后**仍是明文**（`is_enc=0`）。→ 见 D6。



## §3 独立发现与 D1 攻坚（不是缺陷，但必须进报告）

### 3.1 崩溃自愈标记为 FAILED，不是 CANCELLED

`/F` 强杀后重启，孤儿 `RUNNING` 记录被收尾为 **FAILED**。我认为这个选择是**对的**
（进程崩溃时无法区分"被取消"和"挂了"，标 FAILED 更诚实），但它与契约 §5.10
「运行中的同步被标记 `CANCELLED`」的字面表述有差异：那条针对的是**优雅停机**路径
（shutdown hook 主动置位），崩溃恢复路径标 FAILED 是合理的。
**建议 Lead 在契约里把这两条路径分开写**，否则验收时容易误判。

### 3.2 `endCursor` 用的是**复合游标**（比契约更强的设计）

```
推进后 cursor_value = 2026-09-01 00:16:40|1000000
源表最后一行      = id=1000000, updated_at=2026-09-01 00:16:40.000000
```

即"增量列值 `|` 排序键值"。契约 §3.1 把 `endCursor` 描述为"新水位（字符串形式）"，没说能否复合。
实测语义**正确**（等于本次成功提交的**最后一行**的增量列值 + 排序键，而不是 `SELECT MAX(...)`），
而且复合游标解决了"同一时间戳多行"的续跑歧义，是**改进**。
**建议**：在契约里把 `cursor_value` 的格式（`<incrValue>|<orderKey>`）明确写下来。

### 3.3 INCR 路径吞吐比 FULL 低约 50 倍（性能观察，不是功能缺陷）

| 场景 | 行数 | 总耗时 | 吞吐 |
|---|---|---|---|
| FULL（按主键键集分页） | 300,000 | 3,761 ms | ~79,700 行/秒 |
| FULL（按主键键集分页） | 1,000,000 | 12,328 ms | ~81,100 行/秒 |
| **INCR（按 `updated_at` 水位 + 键集分页）** | 995,000 | **641,033 ms** | **~1,552 行/秒** |

为定位，我把两库慢查询日志阈值调到 **50 ms** 并跑了一次真实 INCR：

```
slow-src.log：只记录到我手工执行的 UPDATE（Query_time 1.3s），**引擎发出的语句一条都没有**
slow-dst.log：**完全为空**
```

即 **引擎发出的每一条 SQL 都在 50 ms 以内**。单页读的服务器端执行时间实测：

```
EXPLAIN ANALYZE … WHERE updated_at > … AND updated_at <= … ORDER BY updated_at, id LIMIT 1000
→ 实际执行 2.62 ms / 1000 行
手工执行 1000 行 upsert（ON DUPLICATE KEY UPDATE，目标表已有 99.5 万行）= 96.9 ms
换算：995 个 chunk × 644 ms/chunk 实际 vs 96.9 ms/chunk 手工 → 约 6.6 倍
```

**结论**：慢的不是 SQL，而是 **chunk 之间**的应用层开销（995 个 chunk，平均 644 ms/chunk）。
我**没有**拿到能定位到具体代码行的证据（需要引擎侧埋点或 JFR），所以**只作为性能观察**上报，不判缺陷。→ D1。

### 3.3.1 ⚠ 本轮两处方法论错误的更正（重要，避免后人被误导）

**错误一：我的慢查询日志取证方式是无效的。**

我写的是"两库慢查询日志阈值调到 50ms，引擎语句 0 命中"——**这个结论不成立**。
根因（由 `engine` 指出，我确认成立）：`SET GLOBAL long_query_time` **只对之后新建的连接生效**，
而引擎用的是连接池里的**既有连接**（会话变量在连接建立时就固定了）。
所以引擎的语句一直按默认 10s 阈值判定，当然一条都不记；而我手工执行的语句走的是新连接，所以 96.9ms 那条被记下了。
**正确做法**：`SET PERSIST` 后重启应用，或在应用侧连接上执行 `SET SESSION long_query_time`。
**影响**：这条错误**恰好与"时间不在 SQL 里"的结论方向一致**，但它是"没测到"而不是"测到了没有"，
二者证据强度不同，所以必须更正。§2.9（口令安全）的结论**不受影响**（直接查 `datasource.password` 列与响应体，不依赖慢查询日志）。

**错误二：`INCR_COLUMN_NO_INDEX` 在我那次 644ms/chunk 的运行里并不成立。**

我原报告 §3.3 顺带引用了 engine 关于"增量列无索引 → 每页全表扫描"的解释。
**但我的 `resume_src` 表一直带着 `idx_upd(updated_at, id)` 索引**——644ms/chunk 那次运行是**有索引**的。
我按 engine 的论据做了独立对照实验（同一张 100 万行表，同一分页 SQL）：

| 场景 | 客户端墙钟 | 服务器端 EXPLAIN ANALYZE |
|---|---|---|
| **有** `idx_upd` 索引 | 85.7 ms/页 | **2.84 ms**（Index range scan） |
| **临时 DROP INDEX** | 360.1 ms/页 | **303 ms**（Table scan 100 万行 + Sort） |

索引确实重要（读侧差约 **100 倍**），**但它解释不了我那次测量**，因为那次有索引。
所以 644 ms/chunk 里占主导的应当是**写侧**：我的应用连接 URL 里**没有** `rewriteBatchedStatements=true`
（`qa-env.ps1` 的 `Qa-Url()` 与我在 18080/18083 启动时传的 URL 都没这个参数）。
这与 engine 的对照实验一致：不开该参数时 `addBatch` 退化成 1000 次独立往返（219~254 ms/1000 行），
量级正好对得上 644 ms/chunk（读 <10 ms + 写 ~240 ms + 其余开销），而"多行 VALUES 单语句"只要 32~44 ms。

**独立佐证**：我手工做"1000 行 upsert"对照实验时用的是 `mysql --execute="source <file>"`
（**一条多行 VALUES 语句**），耗时 **96.9 ms**，落在"多行 VALUES"档位（32~44 ms 同量级，
差异来自我这张表有 2 个二级索引），**而不是**"未开 rewrite 的 addBatch"档位（219~254 ms）。
**两个实验互相印证了同一个机制**：差异来自"1000 次往返"还是"1 次多行语句"，而不是 SQL 本身慢。

**D1 复测状态**：engine 的修复（MySQL 方言多行 `VALUES` + 按占位符上限切分）**已进入 core 源码**，
但**尚未打包成可测 JAR**——`target` 下的 JAR 时间戳 15:13:33，而我全部阶段 B 测试用的是 14:41:30 的副本，
修复在 15:2x 之后落地。按 Lead 定的流程（"我收口打包，由我通知你才开跑"），
**D1 的修复效果我尚未独立复测**，需等 Lead 给出新 JAR 后按 §3.3.2 的协议补测。

### 3.3.2 D1 复测结果（被测产物：`app-d1fix.jar`，15:53:34，SHA256 `74C487A7317FB0EA`）

**产物核验（我自己 javap，不采信转述）**：从 `app-d1fix.jar` 里取出内嵌
`data-sync-core-1.0.0-SNAPSHOT.jar` 后，`MySqlDialect` 确有
`buildInsertMultiRow(TableRef, List, int)` / `buildUpsertMultiRow(TableRef, List, List, int)` / `maxRowsPerStatement(int)`；
**旧 JAR 里这三个方法一个都没有**（对照通过）。

**工作量**（与基线可比）：100 万行 `resume_src → resume_dst_bak` 的 INCR，
游标置回最早，目标表预先灌满 100 万行（绝大多数走 UPDATE），`pageSize/batchSize = 1000/500`，增量列**有** `idx_upd`。

**结论一：正确性通过 ✅**

```
服务端记录：status=COMPLETED read_rows=1000000 write_rows=1000000  total_millis=885000
比对：OK   resume_src -> resume_dst_bak: src=1000000 dst=1000000 dst_dup_groups=0
      [keydigest] 两侧 SHA256=473fc54646b933fd220394dfdc53c33ba23522331f8e4fd24c00a9fcfce0906b
      [canon]     两侧 SHA256=7a531ad29180dc82da7def520c5cec128604659b4201d44a762fd435ef121504
```

→ **多行 VALUES 的占位符切分没有参数下标错位**（这正是最危险的失效模式），100 万行两侧摘要完全一致。

**结论二：吞吐相对我原基线没有改善 ⚠️（三次运行都是 644~885 ms/chunk）**

| 运行 | JAR | 行数 | total_millis | ms/chunk | 行/秒 |
|---|---|---|---|---|---|
| 基线（阶段 B） | 旧 14:41:30 | 995,000 | 641,033 | **644** | ~1,552 |
| 复测第 1 次（四格之 A） | 新 15:53:34 | 1,000,000 | 885,000 | **885** | ~1,130 |
| 复测第 2 次（干净工作量） | 新 15:53:34 | 1,000,000 | 757,131 | **757** | ~1,321 |

三次都在同一个数量级 —— **说明这不是"新 JAR 引入的回归"，而是一个一直存在、与 JAR 版本无关的固定成本**。
（我原先"新 JAR 更慢"的说法**撤回**：三次运行分属不同批次、不同会话状态，不能据此比较版本。）

**结论三：瓶颈不在 SQL —— 这一条我有直接证据**

我把目标库的 `general_log` 打开 15 秒采样（只看引擎发出的语句）：

```
INSERT 条数 = 20   每条 500 元组   语句长度 27,629~27,631 字节
相邻 INSERT 间隔：min 3.2 | 中位 8.2 | **max 1,678.6 ms**
前十几条的模式： 5.3 → **1460** → 5.3 → **1447** → 3.3 → **1415** → 4.6 → **1445** → 4.2 → **1416** → 7.7 → **1679** …
```

即"两条 INSERT 挨着发（间隔 3~8 ms），然后**静默停 1.4~1.7 秒**"，循环往复。
那 1.4 秒里目标库**只收到 `SET autocommit=1` / `SET autocommit=0`**，没有任何 SQL 工作。

**同一工作量的手工基准**（多行 VALUES + `ON DUPLICATE KEY UPDATE`，打到已有 100 万行的同一张表）：

```
mysql 客户端启动基线 = 66.5 ms
500  行单语句 upsert：墙钟 75.1 ms → 净 ≈ 5 ms  → 净吞吐 ≈ 100,000 行/秒
1000 行单语句 upsert：墙钟 75.0 ms → 净 ≈ 16 ms → 净吞吐 ≈  62,500 行/秒
```

**读侧也排除了**（源库 general_log 20 秒窗口）：

```
数据读 160 次，间隔 min 22.7 / 中位 89.9 / 平均 101.5 ms
游标序列 159 个且严格单调递增（201000 → 359000），无重复读
```

**三条事实放在一起**：
1. 数据库每条 500 行 upsert 只要 **~5 ms**；
2. 目标库每 chunk 实际只收到 **~17.6 ms** 的 SQL（1.4s ÷ 每 500 行一次 INSERT + 一次 COMMIT）；
3. 读侧每页 ~25 ms 且游标单调推进。

→ **每 chunk 的 885 ms 里，约 868 ms 花在了应用层、数据库完全空闲。**
这是一个**与 D1 原假设（`rewriteBatchedStatements`）不同的、新的定位**：
engine 的多行 VALUES 改造**确实生效了且正确**，但它没有解决真正的大头。

**结论四：读侧明显跑在写侧前面（附带发现）**

采样期游标已到 **359,000**，而目标表里写侧只追到 **200,000** —— 读侧超前约 **15.9 万行**。
JVM 内存同时段采样：workingSet 537→543 MB、private 601→610 MB（6 次 × 8 秒，增长约 9 MB/48 秒）。
**当前不是无界堆积**，但如果这个超前量随数据量线性增长，就是"内存换吞吐"的隐患，需要 engine 确认队列是否有界。

**结论五：我还未能完成"旧新同数据"的严格 A/B（如实记录）**

上表两行的时间数字是**跨批次**比较，不是同一次配置下的 A/B：基线（995,000 行 / 641,033 ms）来自
阶段 B 的一次运行，本次来自另一次。虽然工作量形态一致（都是 0.5~1 秒/chunk 量级、都有增量列索引、
都走 UPDATE 分支），但**严格结论需要两个 JAR 在同一份数据上各跑一次**。

我尝试做这个 A/B 时失败：`app.jar` 副本已被我覆盖成新产物，`git archive HEAD | tar -x` 又被
PowerShell 的文本管道破坏了二进制流（`Damaged tar archive`）。
**因此"新 JAR 更慢"这一条我标记为"跨批次比较，未做严格 A/B"**，不作为定论 —— 但
**"瓶颈在应用层而非 SQL"这一条有直接证据，与批次无关**。

**给 engine 的可执行建议**：给 `writeChunk` 与 chunk 之间的循环加耗时埋点（读/映射/绑定/执行/提交各一段），
或者在运行中连 JFR / `jstack` 采一次线程栈 —— 868 ms/chunk 的量级用埋点一测就能定位，
不需要再靠外部推断。（我这边的证据只能到"不在数据库、不在读侧"这一层。）

**我另外犯的两个错（一次记录）**：
1. 第一次复测三格全 FAILED，根因是我**没设 `DATASYNC_SECRET_KEY`** → 与加密数据源口令时的密钥不一致 →
   解密出垃圾 → `Access denied for user 'root'@'localhost'`。**这是我的环境问题，不是被测系统的缺陷。**
2. 同一轮里我用 `mysqldump` 灌数，但它**不在 PATH 上**，命令失败被我的 `2>$null` 吞掉，
   于是"灌数"实际是 `TRUNCATE` 了目标表却没灌回来，导致那三格的"工作量"变成空表插入。
   已改成自带 JDBC 灌数工具（`bulk_copy.py` + `CompareTable --mode=exec`），不再依赖外部命令。
   **教训（通用陷阱）：`2>$null` 吞掉失败 = 伪造成功。** 后续所有脚本改为**显式断言外部命令的退出码**。

**第二次干净复测（同一产物，目标表被重新灌成与源一致）**：

```
id=43  status=COMPLETED  read_rows=1000000  write_rows=1000000  skipped_rows=0
total_millis=757131   read_millis=747631   write_millis=8260
→ 757 ms/chunk，1,321 行/秒
→ 读占 98.7%，写占 1.1%
```

**结论六（⚠️ 经过两次更正，请连同结论七、八、九一起看）：D1 的根因不是 WebSocket 回调**

**第一次归属（已作废）**：engine 用分段埋点指向 `notifyChunk → RunLog.publish → SyncEventBus.publish →
SyncLogWebSocketHandler.session.sendMessage(...)`（引擎线程上同步写 socket）。
他的对照实验（同步阻塞 700 ms 的 delegate → **716 ms/chunk、回调占 98%**）证明的是
"**回调能造成这个量级**"，**不是**"我那次就是这个原因"。

**推翻它的算术**（engine 自己核对了持久化链路后确认）：
```
RunRecordStore:129-131 原样取 SyncRunResult.getReadMillis()/getWriteMillis()
  引擎口径： read_millis  = fetchPage 窗口（不含回调）
             write_millis = writeChunk 窗口（不含回调）
所以： 757,131(总) − 747,631(读) − 8,260(写) = 1,240 ms
        1,240 ms ÷ ~1000 chunk ≈ 1 ms/chunk  ← "回调 + 映射 + 其它"的【上限】
```
**1 ms/chunk 的回调开销不可能构成 757 ms/chunk 的主体。** 我上一封说
"98.7% 与回调 98% 是同一件事"——**这条更正**；我当时把"能造成"读成了"就是"。

**真正根因（见 §3.3.3，我已独立复现并量化）**：**键集分页的行构造器谓词没有下推成索引范围扫描**。
它一次性解释了我此前的全部观测（目标库空闲 / 每 chunk 恒定 / 我那句 2.84 ms 是浅游标假象 /
`read_millis` 98.7% / 慢查询日志 0 命中），且 engine 也已就该归属自我更正。

**顺带把三份旧证据解释通**（主循环是**严格单线程串行**：读一页 → 写 2 个 chunk → 再读一页）：
- "读慢"在目标库看来就是"两条 INSERT 间隔约 1.45 s"——写被读挤开了；
- 那 1.4 秒里目标库只收到 `SET autocommit=1/0`、**零 SQL**——时间花在**源库**的索引项过滤上；
- 我的两个采样窗口不重叠也没关系了——**不再需要用它们互相解释速率比**。

**判读表（engine 16:33 冻结版把 `fetchPage` 拆成四段，供后续复测用）**：

```
分段耗时汇总：chunk=13 共 359ms（27ms/chunk）|
  读 103ms[借连接 99ms 建语句 3ms 取行 48ms 归还 3ms 其它 0ms] |
  映射 10ms | 写 82ms(execute=53ms commit=29ms) | 回调 0ms | 其它 164ms
```

| 哪一段大 | 指向 |
|---|---|
| **借连接 / 归还** | 连接池层（Hikari 配置、耗尽、校验、归还复位/驱逐）——**样例里"借连接 99ms"占了读窗口 96%**，Lead 最怀疑这条 |
| 建语句 | 驱动解析 SQL（27KB 多行 VALUES 会在这里显形） |
| **取行** | 客户端流式读取（`Integer.MIN_VALUE`）或网络/行宽；**若服务端很快而这里很慢，优先怀疑 §3.3.3 的谓词退化** |
| 回调 | 才是 WebSocket 那条（platform 会异步化，但 engine 明确说这次不指望它） |

**教训（写给后人）**：要区分"某机制**能**造成该量级"（对照实验）与"该次运行**就是**它"（需要该次运行的数据本身支持）。
我在这一轮里两次把前者当成后者。


**结论七：我自己的一个推理错误，主动撤回**

我一度推断"读侧比写侧快 13.4 倍 → 预取无界"。**复核后发现这个推断不成立**：
源库与目标库的两份 general_log 采样窗口**相隔约 50 秒**（目标窗 08:11:16–08:11:31，源窗 08:12:06–08:12:23），
**不是同一时间段**，因此不能得出"同一时刻读写速率差 13 倍"的结论。**该推断撤回。**
engine 侧另有 `readAheadIsBounded` 实测"最大已读未写 = 800，上界 1,399"。
**预取是否有界，以 engine 的埋点数据为准；我的采样无法证伪也无法证实。**
（这条撤回很重要：如果我不核对时间窗，就会把一个错误结论当证据递给 Lead 去做架构决策。）

**结论八：我另一处说法也撤回 —— "新 JAR 更慢"不成立**

我又做了一次**干净工作量**复测（目标表重新灌成与源一致后再跑），三次数据：

| 运行 | JAR | 行数 | total_millis | ms/chunk |
|---|---|---|---|---|
| 基线（阶段 B） | 旧 14:41:30 | 995,000 | 641,033 | **644** |
| 复测第 1 次（四格之 A） | 新 15:53:34 | 1,000,000 | 885,000 | **885** |
| 复测第 2 次（干净） | 新 15:53:34 | 1,000,000 | 757,131 | **757** |

**三次同量级 → 这不是版本回归，而是一个一直存在、与 JAR 版本无关的固定成本。**
"新 JAR 更慢"的说法**撤回**；engine 的多行 VALUES 改造与这个固定成本**分开记账**，不混为一谈。

> **这两条撤回请保留原样，不要改写成"经复核结论为…"。**
> 后人需要看到的是"**什么样的证据不算证据**"：
> ① 跨时间窗的两次采样不能当同一时刻的对比；
> ② 分属不同批次/不同会话状态的两次运行不能用来比较版本。
> Lead 专门批准并强调保留这两条，理由是"结论方向恰好还能自圆其说、但证据链断了"的情形最容易被留在报告里当结论。

**结论九（契约语义变更，来自 Lead 决议）**：`RunMetricsListener` 的语义已从"每 chunk 必调一次"
改为"**进度是累计绝对值，回调是尽力而为的采样**；唯一硬保证是 `run()` 返回前最后一次进度已投递、
且 `SyncRunResult` 最终统计永远准确；实现方必须非阻塞"。
→ **因此 `droppedCallbacks() > 0` 是该语义下的正常表现，不是缺陷，不判"进度丢失"。**
（我方复测 E/F 两格时按此口径判定。）

### 3.3.3 ✅ D1 真正根因（独立复现，证据形态最硬）：键集分页的行构造器谓词**没有下推成索引范围扫描**

**Lead 提出该假设，我在自己的实例上原样复现并量化。** 引擎增量模式实际发出的语句
（从原始 `general_log` **原样抄出**，连参数值一起）：

```sql
SELECT `id`, `val`, `updated_at` FROM `resume_src`
 WHERE `updated_at` > '2026-09-01 00:03:19.001' AND `updated_at` <= '2026-10-07 16:12:06.803762'
   AND (`updated_at`, `id`) > ('2026-09-01 00:05:59', 359000)      -- ← 行构造器
 ORDER BY `updated_at` ASC, `id` ASC LIMIT 1000
```

**判据一：`EXPLAIN` 的 `key_len`（联合索引用到了几个列）**

| 谓词形态 | type | key | **key_len** | 含义 |
|---|---|---|---|---|
| A 行构造器 `(a,b) > (?,?)` | range | `idx_upd` | **8** | 只用索引第 1 列建范围 |
| B 展开式 `(a > ? OR (a = ? AND b > ?))` | range | `idx_upd` | **16** | 两列都进范围（真正的"游标定位"） |

**判据二：`Handler_*` 会话状态**（每次查询前 `FLUSH STATUS`，即单次查询的净计数）

```
A 行构造器：Handler_read_key=1   Handler_read_next=472,999    ← 逐条扫过 47 万条索引项
B 展开式  ：Handler_read_key=2   Handler_read_next=999        ← 精确读到 1000 行
```

**判据三：服务器端真实耗时**（`SHOW PROFILES`，排除客户端解析开销），各跑 3 次

```
Query 1  A 行构造器     570.70 ms
Query 2  A 行构造器     607.58 ms
Query 3  A 行构造器     602.96 ms
Query 4  B 展开式OR       2.93 ms
Query 5  B 展开式OR       3.97 ms
Query 6  B 展开式OR       2.89 ms
```

→ **约 200 倍**，方向与 Lead 在 3407 上的独立测试一致（他那组 8000 倍；差异来自上界与游标位置，
机制相同）。

**机制**：优化器**不把行构造器 `(a,b) > (?,?)` 下推成联合索引的范围起点**。
它只用 `updated_at <= 上界` 建立索引范围，然后**从该范围起点逐条读索引项、再用行构造器做 Filter**，
直到凑够 1000 行。所以**耗时段随游标深度增长**——这正好解释了为什么我早先那句
`EXPLAIN ANALYZE → 2.84 ms` 是**浅游标下的假象**：

| 游标位置 | 服务端实际扫描 | 服务端耗时 |
|---|---|---|
| 浅（2,000） | ~2,000 条索引项 | **2.29 ms** |
| 深（672,000） | **473,000 条索引项** | **592 ms** |

**这条根因一次性解释了我此前的全部观测**（Lead 归纳，我逐条核对无误）：

| 我的观测 | 解释 |
|---|---|
| 目标库全程空闲、只收到 `SET autocommit` | 时间花在**源库**的索引项过滤上 |
| 每 chunk **恒定** ~644–885 ms | 每页都要扫到同一端再 Filter，**恒定**正是它的特征 |
| `EXPLAIN ANALYZE` 只要 2.84 ms/页 | **我测的是浅游标**，不是引擎真实的深游标形态 |
| `read_millis` 占 98.7% | **就是这里**；与回调无关 |
| 慢查询日志 0 命中 | 我已更正的 `SET GLOBAL long_query_time` 对池化连接无效 |
| `INCR_COLUMN_NO_INDEX` 的读侧风险 | **另一个独立问题**（无索引 303 ms/页 vs 有索引 2.84 ms/页），两者都要治 |

**修复前后的可执行断言（Lead 追加要求，我已固化）**：对**同一游标位置**分别跑两种谓词，断言
`Handler_read_next` 从 **47 万级** → **10³ 级**、服务端耗时从 **~600 ms** → **~3 ms**。
（`FLUSH STATUS` + `SHOW SESSION STATUS` 差值，或 `EXPLAIN ANALYZE` 的 `rows=` 均可。）

**我此前的一处取证偏差在这里被彻底解释清楚**（保留，作方法论案例）：
① 我那句 `EXPLAIN ANALYZE → 2.84 ms` 是**浅游标 + 不带真实游标参数**测出来的（**测错了形态**，
   不是方法论错误）；② 我还一度拿分属**不同批次**的两次运行比较 JAR 版本（**测错了对照**，已撤回）。

### 3.3.4 📌 方法论沉淀（本轮最有价值的一条，Lead 指定写入）

> **性能缺陷的判据要尽量落在「机制量」上，而不是「时长」上。**
> 时长会被缓存、并发、批次差异、游标位置污染；机制量不会。

本轮实际用到的两类判据，强度差别很大：

| 判据 | 本缺陷中的取值 | 是否受环境影响 |
|---|---|---|
| **机制量（强）** | `EXPLAIN` 的 **`key_len`：8 → 16**（联合索引用了几列） | 不受数据分布、游标位置、缓存影响 —— **最直接** |
| **机制量（强）** | `Handler_read_next`：**472,999 → 999**（服务端真正读了多少索引项） | 不受"测错形态"污染 |
| 时长（弱，仅作辅证） | `SHOW PROFILES` 570/607/602 → 2.93/3.97/2.89 ms | 可交叉印证，但**不能单独用来定性** |

**为什么这条重要**：我在本缺陷上先后被"时长"骗过两次——
① 浅游标的 `2.84 ms` 让我以为读侧没问题；
② 跨批次的 644/885/757 ms 让我以为是"版本回归"。
**两次都是拿时长当判据。** 换成 `key_len` 与 `Handler_read_next` 后，结论唯一且不可争辩。

**顺带修正 Lead 原先"每 chunk 固定成本"的说法**（他采纳了我的口径）：
并非严格固定，而是**每页必须扫过自范围起点以来的全部候选索引项**。
因为每页只取 1000 行、游标前进很慢，所以越深每页扫的候选越多 → **近似恒定、实则随深度增长**。

**配套的执行纪律**（本轮同样付出代价才学到）：
1. 断言外部命令的行为时**必须检查退出码**，不能用 `2>$null` 吞掉 stderr —— 那等于伪造成功；
2. 要区分"某机制**能**造成该量级"（对照实验）与"该次运行**就是**它"（需该次运行自身的数据支持）；
3. 采样对比必须核对**时间窗是否重叠**，跨窗口的两次采样不能当同一时刻的对比；
4. 复测"某修复是否到位"时，**先用机制量独立判定修复是否真的进了产物**（`javap` / `key_len`），
   再看时长——否则会把"没合进包"误判成"修复失败"。

### 3.3.4.1 📌 第五条纪律（Lead 指定写入，D10 收尾时新增）

> **"由单测覆盖""代码已进包""实现方验证过"都不是"运维真的能看到"。**
> 判据必须落在**最终用户/运维实际能观察到的产物**上
> （启动日志的一行、接口的一个字段、页面的一次加载），
> 且**提取判据的方式本身也要先被验证**。

**本轮累计五个实例，全部是"看起来有、实际不工作/不可见"的同一类**：

| # | 实例 | 失效形态 |
|---|---|---|
| 1 | `error_rows` 恒为 0 | 字段在、永远没值 |
| 2 | Dashboard 成功数恒为 0 | 指标在、永远没值 |
| 3 | `logDroppedMessages` 恒为 0（D8） | 计数器在、结构上不可能被触发 |
| 4 | **D9**：所有 API 全绿，但 Docker 部署的界面**根路径 404** | 接口全通、"东西"打不开 |
| 5 | **D10**：解析逻辑对了、代码进包了、单测过了，但**告警被类初始化时序吞掉** | 代码在、日志看不见 |

**配套的"提取方式"教训（同样五个）**：
① `SET GLOBAL long_query_time` 对**已池化连接无效** → 我据此错误推断"SQL 不慢"；
② `2>$null` **吞掉失败** → `mysqldump` 不在 PATH 却当成灌数成功；
③ **浅游标**的 `EXPLAIN ANALYZE` → 拿它当引擎真实语句的耗时（测错形态）；
④ 脚本 grep 中文**在 GBK 控制台上必然失败** → 误报"汇总行/告警不存在"（我自己踩了两次）；
⑤ 用 UTF-8 硬解 **`.class` 常量池** → 中文被 modified UTF-8 毁掉，误判"文案不在包里"。

→ **共同结论：先证明"观测手段本身收到了信号"，再用观测结果下结论。**
  这条纪律在本轮至少避免了 3 次错误结论进入交付说明（D9 界面打不开、D10 告警不可见、以及我自己那两次误报）。








### 3.3.5 ✅ D1 修复复测（终审产物 `app-final.jar`，16:42:11，SHA256 `5768675C…E1538`）

**产物核验（我自己做，不采信转述）**：SHA256 与 Lead 报的**逐字符一致**；
从 fat jar 取出内嵌 core 后 `javap com.datasync.core.dialect.SqlDialect` 确认：
```
public default java.lang.String buildKeysetPredicate(java.util.List<java.lang.String>, KeysetPosition);
public default java.util.List<java.lang.Object> buildKeysetParameters(java.util.List<java.lang.String>, KeysetPosition);
```
`buildKeysetParameters` 是**新增的配对方法**——它存在就说明谓词展开这批改动确实进了包。

**F-A（engine：谓词展开）—— 机制量全部达标 ✅**

| 判据 | 修复前 | 修复后 |
|---|---|---|
| 引擎实际发出的 SQL（`general_log` 原文） | `AND (\`updated_at\`, \`id\`) > ('2026-09-01 00:05:59', 359000)` | `AND ((\`updated_at\` > '2026-09-02 00:03:20') OR (\`updated_at\` = '2026-09-02 00:03:20' AND \`id\` > 200000))` |
| 行构造器是否还在 | 是 | **否**（判定：`-match '(updated_at, id) >'` = False） |
| `EXPLAIN` 的 `key_len` | **8** | **16**（修复前实测） |
| `Handler_read_next` | **472,999** | **999**（修复前实测） |
| **`read_millis`（100 万行 INCR）** | **747,631 ms** | **3,349 ms**（**223 倍**） |
| **`total_millis`** | **757,131 ms** | **11,871 ms**（**64 倍**） |
| **ms/chunk** | **757** | **11.9** |
| 读占比 | 98.7% | **28.2%**（写成了主体 61.8%） |

→ 与 Lead/engine 的预期（"读掉到与写同量级、总时长 757s → 10s 量级、ms/chunk 掉到 10ms 量级"）**完全一致**。

**F-B（platform：回调非阻塞）—— E/F/G 三格对照 ✅**

| 格 | WebSocket 客户端 | total_ms | read_ms | write_ms | ms/chunk | 会话数 |
|---|---|---|---|---|---|---|
| **E** | 不连 | 11,871 | 3,349 | 7,339 | **11.9** | 0 |
| **F** | 连上 + 正常读 | 11,753 | 3,360 | 7,197 | **11.8** | 1 |
| **G** | 连上 + **故意不读 socket** | 11,240 | 3,191 | 7,023 | **11.2** | 1 |

**三格绝对值差异 < 5%**：即使挂着一个**完全不读 socket 的客户端**，引擎吞吐**没有任何下降** —— 回调不再阻塞主循环。

**服务端日志的独立证据**（比我的客户端统计更硬）：
```
INFO  c.d.s.config.SyncLogWebSocketHandler - 实时日志已订阅 SyncEventBus（按 run 标签分发；发送在独立线程，不回压引擎）
DEBUG c.d.s.config.SyncLogWebSocketHandler - WebSocket 传输异常（会话 ffef2c2d-afda-4c81-a58f-8d509b763696）：Connection reset
DEBUG c.d.s.config.SyncLogWebSocketHandler - WebSocket 传输异常（会话 0ea47264-20e2-42aa-944d-66a651c86e13）：Connection reset
```
**两个不同的会话 ID** 证明 F/G 两格客户端确实连上了；`Connection reset` 是客户端退出时的正常现象；
那行 INFO 是实现方自己声明"发送在独立线程，不回压引擎"。

**修复未改变数据语义 ✅**（谓词改写最怕这个）：
```
count:      src=1000000 dst=1000000 dst_dup_groups=0
keydigest:  两侧 SHA256=473fc54646b933fd220394dfdc53c33ba23522331f8e4fd24c00a9fcfce0906b
canon:      两侧 SHA256=7a531ad29180dc82da7def520c5cec128604659b4201d44a762fd435ef121504
```
**`canon` 摘要与修复前那次完全相同** → 展开式 OR 与行构造器**语义等价**：没丢行、没重复、没改内容。

**两处我必须如实标注的边界**：
1. `logDroppedMessages=0` / `websocketDroppedMessages=0` —— **丢弃计数器没有被触发**
   （12 秒同步 + 队列上限 2000/256，很可能没填满）。所以"丢中间态"这条**我没验到饱和状态**；
   我验到的是**"不拖慢主循环"**（G 格含零窗口客户端）。
2. 我的客户端脚本**没写出统计 JSON**（`ws-final-F/G.json` 不存在）——文件层证据缺失，
   我用**服务端日志的两个会话 ID** 补上了"客户端确实连上"这一环。
   **若要把丢弃路径验到位**，需要更长的同步或更小的队列上限。

**归因分离（Lead 要求的"分开记账"）**：

| 修复 | 机制量判据 | 结论 |
|---|---|---|
| **F-A** engine 谓词展开 | `javap` 方法存在 + 实际 SQL 无行构造器 + `key_len` 8→16 + `Handler_read_next` 47 万→10³ + `read_millis` 747,631→3,349 | **✅ 验到** |
| **F-B** platform 回调非阻塞 | E/F/G 三格绝对值差 <5%（含零窗口客户端）+ 服务端 INFO 声明 | **✅ 验到（"不阻塞"面）；丢弃饱和路径未验到** |

### 3.3.6 F-C 复测：队列开关 + 丢弃计数器的**饱和路径**（产物 `app-final2.jar`，17:01:01，SHA256 `E87C89C7…E7C8`）

**产物核验**：SHA256 与 Lead 报的逐字符一致；内嵌 core 的 `buildKeysetPredicate` / `buildKeysetParameters` 仍在
（F-A 同源代码重打包）。**F-A / F-B 的结论复用 §3.3.5，本轮只测 F-C。**

**场景设计**：任务 `pageSize=100` → 100 万行产生 **20,000 次回调**（远超队列容量）；
挂一个**故意不读 socket** 的客户端制造背压。

| 格 | 启动参数 | 会话队列上限（日志实证） | `logDropped` | `wsDropped` |
|---|---|---|---|---|
| **A** | `-Ddatasync.log.queue-capacity=8 -Ddatasync.ws.session-queue-capacity=4` | **4** ✅ | **0** | **12,382** ✅ |
| **B** | 无开关（负对照） | 256（默认） | **0** | 12,359 |
| **C** | `-D…log.queue-capacity=0 -D…ws.session-queue-capacity=abc` | 256（回落） | **0** | 12,413 |

**开关本身生效 ✅**（日志原文，用 .NET 按 UTF-8 读，避免 PS 5.1 按 GBK 误读）：
```
INFO SyncLogWebSocketHandler - 系统属性 datasync.ws.session-queue-capacity 覆盖为 4（默认 256）
INFO RunLog                 - 系统属性 datasync.log.queue-capacity 覆盖为 8（默认 2000）
WARN RunLog                 - 系统属性 datasync.log.queue-capacity 的值非法（0），已回落到默认值 2000
```
非法值 **`0` → 回落 2000 + WARN + 应用不抛异常**（C 格任务照常 COMPLETED）；
`abc` 走 `Integer.getInteger` 的 null 路径静默回落（未加 WARN，可接受）。

**三条断言的结果（如实，不凑绿灯）**：
1. **两个计数器都 > 0** → **FAIL**：`wsDropped=12,382 > 0` ✅ 但 **`logDropped=0`** ❌；
2. 任务最终状态 **`COMPLETED`** → **PASS**（三格都是 1,000,000/1,000,000、skipped=0）；
3. **`readRows`/`writeRows` 仍准确** → **PASS**（read=1,000,000 = 源行数；目标表 1,000,000；
   `count` 比对 `dst_dup_groups=0`）。

**为什么 `logDropped` 即使在容量 8 下也不触发 —— 我给出机制解释，不是"填不满"**

这一格的 `分段耗时汇总`（引擎自打，20,000 chunk，1 行 1 页级）：
```
分段耗时汇总：chunk=20000 共 34323ms（1ms/chunk）|
  读 14020ms[借连接 36ms 建语句 246ms 取行 13468ms 归还 21ms 其它 249ms] |
  映射 457ms | 写 14851ms(execute=12408ms commit=2443ms) | 回调 402ms | 其它 4593ms
```
**回调总耗时 402 ms / 20,000 次 = 0.02 ms/次** → 回调路径彻底不阻塞（F-B 再次得到佐证）。

机制：**投递到 `RunLog` 队列的生产者，本身已被上游 64 容量的 `AsyncRunMetricsListener` 节流过**
（`TaskRunService` 用它包裹 listener；队列满丢最旧）。所以即使 `RunLog` 容量压到 8，
**它面对的入队速率也被限制在"drain 线程跟得上"的量级**，队列永远填不满。
更根本的一点：**这类"总线/会话队列"没有能让它过载的公开生产者**——
`wsDropped` 能被打到 1.2 万，是因为**客户端不读 socket** 这种真实可造的外部条件。

**因此 `logDroppedMessages` 的结论是**：
> **端到端未能触发（含容量压到 8、20,000 次回调、零窗口客户端）；该路径的正确性由单测覆盖。**

**覆盖它的单测（Lead 要求的"附单测名"）**：
```
data-sync-server\src\test\java\com\datasync\server\log\RunLogNonBlockingTest.java
   :37  long droppedBefore = RunLog.droppedMessages();
   :39  int messages = 2300;   // > 队列容量 2000，必然触发丢弃
   :49  assertThat(RunLog.droppedMessages()).isGreaterThan(droppedBefore);
   :46  同时断言耗时（证明不阻塞调用方）
data-sync-server\src\test\java\com\datasync\server\config\SyncLogWebSocketHandlerTest.java
   :82  long droppedBefore = handler.droppedMessages();
   :86  // 单会话队列 256，且发送线程远慢于投递（700ms/条）→ 必然丢弃
   :88  assertThat(handler.droppedMessages())
          .as("慢客户端导致的丢弃必须被计数（证明是丢中间态而不是背压阻塞）")
          .isGreaterThan(droppedBefore);
```

**负对照（Lead 特别要求的反向错误排除）✅**：
- 不带开关时 `logDroppedMessages = 0`（B 格）→ 排除"计数器恒 > 0"；
- 带开关时 `wsDroppedMessages = 12,382 > 0`（A 格）→ 排除"计数器恒为 0"。
**两个方向都验了**，所以"计数器是否有效"这件事是有信息量的。

**一个我必须自曝的 harness 错误（也是本次差点误判成实现方缺陷的地方）**：
第一版 F-C 我把 `-Ddatasync.*` 放在 **`-jar` 之后**，被当成**程序参数**、应用收不到，
于是日志显示"会话队列上限 256"、计数器毫无变化 —— 我一度以为开关是假的。
**正确做法是 JVM 系统属性必须放在 `-jar` 之前**；改对后日志立刻显示"覆盖为 4/8"。
记录在此：**"开关不起作用"和"参数位置放错"从外部看完全一样，必须先验证参数真的被应用收到了。**


实测启动日志：

```
Executing SQL statement [ALTER TABLE datasource COMMENT = '数据源配置表']
Executing SQL statement [ALTER TABLE datasource MODIFY COLUMN id bigint NOT NULL auto_increment COMMENT '…']
Executing SQL statement [ALTER TABLE datasource MODIFY COLUMN name varchar(100) NOT NULL COMMENT '…']
```

它用 `information_schema` 拼出 `COLUMN_TYPE + NOT NULL + DEFAULT + EXTRA` 再 `MODIFY COLUMN`。
**我一开始怀疑它会把列的字符集/排序规则改掉**（重建的 DDL 里没有 `CHARACTER SET` / `COLLATE`）。
**实测证伪了我的怀疑**：在全新库 `ci_probe` 上跑完 Flyway V1/V2/V3 再启动应用后，
`datasource` 全部列的 `COLLATION_NAME = utf8mb4_general_ci`，**与迁移脚本里显式声明的表级
`COLLATE = utf8mb4_general_ci` 完全一致**；`auto_increment` 与 `NOT NULL` 都保住；注释补齐率 `10/10`。
**没有发现实际损害。**

仍值得记一条质量隐患（不算缺陷）：
- **契约 D7 说"Schema 由 Flyway 版本化迁移管理"**，而这个组件让应用在**每次启动**都执行 DDL，
  属于"绕过 Flyway 改表"；多实例部署下会互相加锁/竞争。
- 重建的 DDL 不含 `CHARACTER SET`/`COLLATE`/`GENERATED`，且 `DEFAULT` 是把 `COLUMN_DEFAULT`
  直接字符串拼接（不区分字符串/函数默认值）。**当前 schema 下没被触发**，
  但只要将来有一列带 `DEFAULT (expr)`、或声明了非表级默认排序规则，就会被悄悄改掉。
- 失败被吞成 `log.debug`，默认 INFO 级别下运维看不到。

**建议 `platform`**：要么把注释也交给 Flyway（一次性迁移），要么至少
① 只在列注释为空时执行、② 重建 DDL 时补上 `CHARACTER SET`/`COLLATE`、③ 失败日志提到 WARN。

> **⚠️ 本节末尾这段"预测"在 F-C 复测中被证实了 —— 见 §6 缺陷 D7。**
> `DEFAULT` 字符串直拼确实产生了非法 SQL，而且不是"将来会触发"，是**每次启动都触发 3 条失败**。

#### D7（低 → **✅ 已修复并在最终产物上复测通过**）`DatabaseCommentInitializer` 对带字符串默认值的列每启动一次就失败一次

**修复后复测（`app-release.jar`，含"补齐"与"跳过"两条分支都被跑到）见 §3.7。**
```
INFO DatabaseCommentInitializer - 元数据注释检查完成：本次补齐 6 条，跳过 61 条（注释已存在，Flyway 为唯一事实源），失败 0 条
'补列注释失败' = 0 条；那 3 个字符串默认值的列（WARN 原现场）逐列确认已有注释
```
**修复方案（platform）**：① `defaultClause(type, raw)` 按类型正确转义（字符串加引号并转义内部引号；
数值/`CURRENT_TIMESTAMP`/`(uuid())`/`b'0'`/`NULL` 按表达式或字面量原样输出）；
② **只在注释为空时才补**（不再比较文案是否一致）—— 注释的唯一事实源是 Flyway；
③ 重建 DDL 现在保留 `CHARACTER SET`/`COLLATE`（顺带堵掉了我 D3 里提的属性丢失风险）；
④ 失败日志带表/列/SQL 上下文，汇总区分"补齐/跳过/失败"。

**原始缺陷记录（保留）：**

- **现象**：每次启动日志里稳定出现 3 条失败 + 1 条汇总（A/B/C 三格全部复现）：
  ```
  WARN DatabaseCommentInitializer - 补列注释失败 sync_task.source_mode：bad SQL grammar
       [ALTER TABLE sync_task MODIFY COLUMN source_mode varchar(20) DEFAULT TABLE CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci COMMENT '…']
  WARN DatabaseCommentInitializer - 补列注释失败 sync_task.status：… DEFAULT DISABLED …
  WARN DatabaseCommentInitializer - 补列注释失败 sync_task.full_sync_strategy：… DEFAULT TRUNCATE …
  WARN DatabaseCommentInitializer - 元数据注释补齐有 3 条失败（不影响启动；可人工执行 ALTER TABLE ... COMMENT 补齐）
  ```
- **根因（我独立复现其语法错误）**：重建 DDL 时把 `COLUMN_DEFAULT` **直接字符串拼接**，
  字符串默认值没加引号 → `DEFAULT TABLE`（应为 `DEFAULT 'TABLE'`）→ `ERROR 1064`。
- **影响（诚实评估，不夸大）**：
  - **本次未造成数据或结构损害**：那 3 列的注释来自 Flyway V1/V2、本身已存在
    （实测该表 25 列 `empty_cmt=0`），所以失败只意味着"每次启动白刷 3 条 WARN"
    + "该组件对这几列的注释变更永远无法生效"；
  - 这正是 §3.4 里预判的 `DEFAULT` 拼接缺陷，从"理论风险"变成**每次启动可复现的实际失败**；
  - 若将来有人改了 V1 的默认值/注释并期望该组件同步，会得到静默的"改了但没生效"。
- **修复建议**（`platform`，约几行）：① 字符串型默认值必须加单引号（区分字面量与表达式，后者用 `DEFAULT (expr)`）；
  ② 或更稳：**注释也交给 Flyway 一次性迁移**，彻底去掉启动期 DDL。
- **证据**：§3.3.6 的 A/B/C 三格日志、§3.4。

#### D8（信息 → **✅ 已关闭（改指标 + 改文档）**）`logDroppedMessages` 恒 0 没有信息量

**处置结果**：Lead 采纳"把永远为 0 的指标换成一组成立即有意义的水位指标"：
- 新增 **`logPeakQueueDepth`**（历史最高水位，CAS 更新、永不清零）→ **任何负载下都有信息量**；
- `logDroppedMessages` 保留并写明口径：**恒 0 是预期行为**（上游 64 容量 `AsyncRunMetricsListener` 已替它挡掉压力），
  **不用于判断是否在丢**；
- "是否在丢"看 **`websocketDroppedMessages`**（我实测 12,382，有效）；
- README 监控章节已写明上述口径。
→ **判定：D8 关闭**（记为"信息级，已通过改指标 + 改文档关闭"，不是"留着"）。

**原始缺陷记录（保留）：**

- **现象**：把 `-Ddatasync.log.queue-capacity` 压到 **8**、制造 **20,000 次回调**、
  再挂**零窗口慢客户端**，`logDroppedMessages` 仍恒为 **0**（A/B/C 三格皆然）。
- **机制（不是"填不满"，是结构性的）**：投递到 `RunLog` 队列的生产者
  **已被上游 64 容量的 `AsyncRunMetricsListener` 节流**（队列满丢最旧），
  所以 `RunLog` 面对的入队速率天然被限制在 drain 线程跟得上的量级；
  且这类"总线队列"**没有能让它过载的公开生产者**
  （对比：`wsDropped` 能被打到 1.2 万，因为"客户端不读 socket"是真实可造的外部条件）。
- **影响**：不构成缺陷，但**运维不能据此判断"实时日志是否在丢"**——它永远是 0。
  真正能反映"客户端跟不上"的是 **`websocketDroppedMessages`（实测 1.2 万，有效）**。
- **建议**：① 文档写明"看 `websocketDroppedMessages`，`logDroppedMessages` 恒 0 属正常"；
  或 ② 换成真会被触发的口径，否则这就是一个"**看起来有、实际没有信息量**"的观测点
  （与本轮 `error_rows` 恒 0、Dashboard 成功数恒 0 同类）。
- **证据**：§3.3.6。

---

### §3.7 收尾复测（最终发布产物 `app-release.jar`，17:27:59，SHA256 `8DFA49D98CC4ECA3193DEED42DDCE6A7FAF2E843C4B78CAEF40BCAB8F123B5D5`）

**产物核验（我自己做）**：SHA256 与 Lead 报的逐字符一致；内嵌 core 的
`buildKeysetPredicate` / `buildKeysetParameters` 仍在；`DatabaseCommentInitializer`、`RunLog`、
`SyncLogWebSocketHandler` 均在包内。

### D7 复测 ✅ **通过，且把"补齐"路径真正跑到了**

**路径①：无注释旧库（补齐路径）** —— 我专门造的夹具 `datasync_nocmt`：
由 V1 **程序化去掉全部 `COMMENT`** 派生（模拟 Hibernate `ddl-auto` 时代建的表），
启动前 **空列注释 39/39、空表注释 3/3**，且保留 V1 的 `DEFAULT 'TABLE'` 等字符串默认值。
```
INFO DatabaseCommentInitializer - 元数据注释检查完成：本次补齐 6 条，跳过 61 条（注释已存在，Flyway 为唯一事实源），失败 0 条
启动后：空列注释 39/39 → 10/152 ，空表注释 3/3 → 1/16
那 3 个字符串默认值的列（本次 WARN 的原现场）逐列确认：
  sync_task.source_mode        = 数据源模式：TABLE / CUSTOM_SQL
  sync_task.status             = 任务状态：ENABLED / DISABLED
  sync_task.full_sync_strategy = 全量策略：TRUNCATE / DELETE / SWAP
'补列注释失败' = 0 条   ✅
```
残留 10 条空列注释经核实**全部属于 `flyway_schema_history`**（不在组件清单内，属设计边界，非失败）。
→ **判定：D7 关闭。**

> **为什么必须专门造这个夹具**：我实测 `datasync_e2e` / `datasync_legacy` / `ci_probe` 三个库的
> **空列注释都是 0/63、空表注释都是 0/4** —— platform 把逻辑改成"非空即跳过"之后，
> **在我原有全部库上只会走"跳过"分支，包括那 3 条 WARN 的原现场**。
> 只在这些库上验，就会得到"零 WARN 通过"但**修改点未被覆盖**的结论。
> 所以我从 V1 派生了一个真正无注释的旧库，才把"补齐"这条分支跑到（补齐 6 条 + 跳过 61 条同时出现，
> 说明两条分支都被执行）。

**路径②：有注释库（跳过路径）** `datasync_e2e`：`'补列注释失败' = 0 条` ✅

**一条我必须自我更正的抓取失误**：本轮脚本对"补齐 0 条，跳过 N 条…失败 0 条"那行的抓取**失败了**
（我的匹配串与实现文案不完全一致），所以我一并核对了**无注释库那一行原文**
（`补齐 6 条，跳过 61 条，失败 0 条`），并把它作为跳过多寡的证据；
`61` 与"启动前已带 Flyway 注释的列数"一致，**没有出现"跳过一切、掩盖失败"**的情况。
另外残留的 10 条空注释是 `flyway_schema_history` —— **我不把它当成失败**，如实标注为设计边界。

#### D10（低 → **✅ 已修复（Lead 修）并复验通过**）`datasync.log.queue-capacity` 的非法值告警曾被"类初始化时序"吞掉

**修复后复验（`app-d10fix.jar`，17:47:01，SHA256 `B93FCB821BC44F5FD1046358482FEF1EEF0189298AAC2753F63B18210F5C0CF5`）——三条日志行全部可见：**
```
17:49:55.135 WARN [main] c.d.s.config.SyncLogWebSocketHandler - 系统属性 datasync.ws.session-queue-capacity 的值非法（abc），已回落到默认值 256
17:49:56.687 WARN [main] com.datasync.server.log.RunLog - 系统属性 datasync.log.queue-capacity 的值非法（"abc" 不是合法整数），已回落到默认值 2000   ← 上一版【完全不可见】的那条
17:49:56.687 INFO [main] c.d.s.c.StartupLogConfigReporter - 可配置项生效值：实时日志总线队列上限=2000（默认 2000，…）；WebSocket 会话队列上限=256（默认 256，…）
```
**全日志 `RunLog` 行数：上一版 0 → 本版 1**（懒加载后解析发生在 Logback 就绪之后）。
**默认值场景（不带 `-D`）**：汇总行同样出现（2000/256），且无任何"非法值"WARN —— 正常路径不打扰 ✅

**修法（Lead 采用了我诊断的根因 + 我建议的第 1、3 条）**：
① `QUEUE_CAPACITY` 改为**懒加载**（双重检查的 `queue()`），首次使用时才解析 → 与日志可用时机解耦；
② 新增 **`StartupLogConfigReporter`**（`@EventListener(ApplicationReadyEvent.class)`），
上下文完全就绪时打印一行汇总 → **无论配置合法/非法/没配，都有一个必然可见的落点**。

**⚠️ 我在本条上的两次自我更正（保留，这是本条最有价值的部分）**：
1. 我第一次用 `[Text.Encoding]::UTF8.GetString(字节)` 直接解 `.class` 常量池，
   **被 modified UTF-8 的中文编码毁掉字符串匹配**，误判"新文案不在包里"；改用**正确解析常量池**的脚本后才确认它在包里。
2. **我第一次给的修复建议是不充分的**：我说"按 `SyncLogWebSocketHandler` 的写法对齐即可（约 6 行）"。
   Lead 照此改了、**代码也确实进包了、单测也过了 —— 但运行时告警依然一条都看不到**。
   因为本质差异**不在"解析怎么写"，而在"解析发生在哪个阶段"**（静态初始化早于 Logback 就绪）。
   **这条教训就是 §3.4 那条方法论的第五个实例。**

**判定：D10 关闭（✅ 已修复并复验通过）。**

**修复过程中间的状态记录（保留）：**

**修复后复测（`app-handover.jar`，Lead 修改的 6 行）—— 分两半看：**

**① 代码修复确实进包了 ✅**（按 class 常量池正确解析，不是用 UTF-8 硬解 class）：
```
=== app-handover.jar 内嵌 RunLog.class ===
  ✅ 系统属性 {} 的值非法（"{}" 不是合法整数），已回落到默认值 {}      ← 新增（abc 路径）
  ✅ 系统属性 {} 的值非法（{} 必须为正整数），已回落到默认值 {}        ← 新增（0/-5 路径）
  ✅ 系统属性 {} 覆盖为 {}（默认 {}）
  javap -c: resolveCapacity 已调用 java/lang/Integer.decode（不再是 Integer.getInteger）
=== app-release2.jar（上一版）对照 ===
  ❌ 含 '不是合法整数' (0 条)     ← 确认这两条新文案确系本轮新增
```

**② 但运行时仍然一条日志都没有 ❌** —— 三种取值全部静默：
```
-Ddatasync.log.queue-capacity=abc  → （无任何相关日志行）
-Ddatasync.log.queue-capacity=0    → （无任何相关日志行）
-Ddatasync.log.queue-capacity=8    → （无任何相关日志行）  ← 连"覆盖为"的 INFO 都没有
```

**根因（本轮新定位，比我上一轮的判断更深一层）**：
`RunLog` 的容量解析在 **static 初始化块**里执行，而该静态初始化**发生在 Logback 配置就绪之前**
→ 此时发出的 WARN/INFO **被日志框架丢弃**。
同一次启动的完整日志里 **`RunLog` 相关行数 = 0**（全 221 行无一条）；
而 `SyncLogWebSocketHandler` 的同类 WARN **出现了** —— 因为它的容量字段虽也是 `static final`，
但**首次被触碰是在 `@PostConstruct subscribe()`**（Spring bean 初始化，晚于 Logback 就绪），
且它把上限写进那行 INFO（`会话队列上限 256`），所以有一个**可观测的落点**。

**时间线证据（同一次启动）**：
```
17:42:53.914  Starting DataSyncApplication
17:42:56.550  Flyway: Current version of schema = 3
17:42:59.503  INFO SyncLogWebSocketHandler - 实时日志已订阅…会话队列上限 256   ← 它的日志可见
（全日志 RunLog 行数 = 0）                                                      ← 它的日志不可见
```

**⚠️ 我上一轮的修复建议是不充分的（自我更正）**：
我当时说"按 `SyncLogWebSocketHandler` 的写法对齐即可，约 6 行"。
Lead 照此改了，**代码对齐了、也被验证为已进包，但告警依然看不见** ——
因为差异的本质**不在解析写法，而在"解析发生在哪个阶段"**。只改写法不足以让运维看到这条告警。

**真正可行的修法（三选一）**：
1. **把解析推迟到首次使用**（`QUEUE_CAPACITY` 改懒加载），此时 Logback 已就绪；
2. **把它并入一个可观测落点**（如并入 `SyncLogWebSocketHandler` 那行 INFO 一起打印）；
3. **改为 Spring 配置属性绑定**（`@Value`/`@ConfigurationProperties`）—— 最正统，
   启动期属性错误由 Spring Boot 自己报出来。

**影响（诚实评估）**：不影响功能（正确回落到 2000、应用正常启动、D9/D7/D8 均未回归）；
影响的是"**运维把属性写错时不会被通知**"。属本轮反复出现的"**看起来有、实际不生效/不可见**"一类，
且这次连"告警本身"都不可见。

**方法论价值（二进制层面的同一条教训）**：
我上一轮用 `[Text.Encoding]::UTF8.GetString(字节)` 直接解 `.class` 常量池，
**被 modified UTF-8 的中文编码毁掉了字符串匹配**，因而误判"新文案不在包里"；
改用**正确解析常量池**的脚本后才确认它确实在包里。
→ 与"判据取不到时先怀疑自己的提取方式"是同一条纪律，这次出现在**二进制层面**。

---

**原始记录（保留）：**

- **背景**：我在 F-C 复测里提过"`abc` 静默回落、只对 `0` 有 WARN"，platform 回复说**两个类都改了**。
  我用最终产物实测**矩阵**（唯一变量是属性名与取值）：

  | 取值 | `datasync.ws.session-queue-capacity` | `datasync.log.queue-capacity` |
  |---|---|---|
  | `abc` | **WARN** 值非法（abc），已回落到默认值 256 | **（无任何日志行）** ❌ 静默 |
  | `0` | **WARN** 值非法（0），已回落到默认值 256 | **（无任何日志行）** ❌ 静默 |
  | `8` / `4` | INFO 覆盖为 4（默认 256） | **（无任何日志行）** ❌ 静默 |

- **根因（读源码定位）**：两个类的 `resolveCapacity()` 实现**不对称**：
  ```java
  // SyncLogWebSocketHandler（已修）：显式取值 + 捕获，能区分"非数字"与"缺失"
  String raw = System.getProperty(propertyName);
  if (raw == null || raw.isBlank()) return defaultValue;      // 缺失 → 静默（正确）
  try { configured = Integer.decode(raw.trim()); } catch (NumberFormatException ignored) { }
  if (configured == null || configured <= 0) { log.warn("…值非法（{}）…", propertyName, raw, defaultValue); }

  // RunLog（未同步修改）：getInteger 对非数字直接返回默认值 → 永远进不了告警分支
  Integer configured = Integer.getInteger(propertyName, defaultValue);
  if (configured == null || configured <= 0) { log.warn("…值非法（{}）…", propertyName, configured, …); }
  ```
  `Integer.getInteger(name, def)` 对非数字**返回 def 而不是 null** → 告警分支不可达；
  `configured != defaultValue` 的 INFO 分支也因"等于默认值"而不打 → **`abc` 与 `0` 都完全静默**。
- **影响（诚实评估）**：
  - **不影响功能**：两个属性都正确回落到默认值，应用正常启动（三格 health 都 UP）；
  - **影响可运维性**：属性写错值时（如 `queue-capacity=2ooo`）**WS 那个会明确告诉你，log 那个什么都不说**
    —— 运维会以为生效了。属本轮反复出现的"**看起来有、实际没生效**"同一类，只是程度轻（有默认值兜底）。
- **修复建议**（`platform`，按 `SyncLogWebSocketHandler` 的写法对齐即可，约 6 行）：
  显式 `System.getProperty` + 捕获 `NumberFormatException` + 统一 WARN；
  并更新那段**已过时**的 javadoc（仍写着"`Integer.getInteger` 对非数字本身就会返回默认值"），
  否则下一个读代码的人会以为该分支有效。
- **证据**：本节矩阵（同一产物、同一参数位置）。

### §3.8 最终发布产物复测（`app-release2.jar`，17:32:31，SHA256 `B142FCDFB8C33727B8B417F5F03217623897882E8825F4E853584BDF82EB92C1`）

**产物核验**：SHA256 与 Lead 报的逐字符一致；包内确认含
`BOOT-INF/classes/static/index.html`、`static/assets/*.js|css`、`SpaForwardController.class`
（前端已打进 JAR —— 这是 D9 修复的前提）。

### D9（新，**已修复并复测通过**）Docker 部署出来的界面根本打不开

- **现象（Lead 发现）**：README 写"前端由同一个 JAR 提供"、Dockerfile 也把 `data-web/dist/` 拷进 `static/`，
  但后端**原先没有任何 SPA 转发规则** → history 模式下 `/`、`/tasks`、`/login` 在服务端没有对应文件，
  **访问根路径直接 404**。**所有 API 都正常，所以只跑 API 冒烟测试永远看不出界面打不开。**
- **修复**：新增 `SpaForwardController` —— 转发不含 `.` 的前端路由到 `static/index.html`（带 `Cache-Control: no-cache`）；
  **不转发**含 `.` 的路径（否则资源 404 也返回 HTML，浏览器报 `Unexpected token '<'`，极难排查）；
  不转发 `/api`、`/ws`、`/actuator`、`/assets` 等保留前缀；前端资源未构建时返回 **503 + 中文说明页**。
- **我的独立复现（`app-release2.jar`）**：

  | 请求 | 结果 | 判定 |
  |---|---|---|
  | `/` | `200 text/html` len=708 | ✅ SPA 入口 |
  | `/tasks` | `200 text/html` len=708 | ✅ history 路由 |
  | `/records/1` | `200 text/html` len=708 | ✅ 带参数路由 |
  | `/login` | `200 text/html;charset=UTF-8` len=2395 | ✅ **未被 SPA 抢走**（Thymeleaf 登录页） |
  | `/api/system/info` | `200 application/json` | ✅ 保留前缀未污染 |
  | `/actuator/health` | `200 application/vnd.spring-boot.actuator.v3+json` | ✅ |
  | 不存在的前端路由 | `200 text/html` | ✅ 交给前端 404（正确） |

  **入口 HTML 引用的 6 个资源全部 200 且 MIME 正确**（这是"资源 404 返回 HTML"那个坑的反向验证）：
  ```
  /assets/index-Cm_EVs9r.js            -> 200 text/javascript
  /assets/vue-DMkpGqwR.js              -> 200 text/javascript
  /assets/vendor-jlspyQi9.js           -> 200 text/javascript
  /assets/element-plus-SSFkEkZ6.js     -> 200 text/javascript
  /assets/element-plus-CMeMdHGs.css    -> 200 text/css
  /assets/index-DOQVFJrT.css           -> 200 text/css
  ```
- **判定：D9 关闭。** 四条断言（`/` 与 `/tasks` 200 HTML、`/api` 仍 JSON、静态资源 MIME 正确、`/login` 未被抢）
  全部独立通过。**这条也说明"API 全绿"不等于"部署可用"** —— 与"计数器恒 0""`error_rows` 恒 0"同属"看不见的失效"。

### D7 复测（最终产物）✅ 两条路径都跑到，均通过

```
① 无注释旧库 datasync_nocmt：
   启动前：空列注释 39/39、空表注释 3/3
   INFO DatabaseCommentInitializer - 元数据注释检查完成：本次补齐 6 条，跳过 61 条（注释已存在，Flyway 为唯一事实源），失败 0 条
   启动后（组件清单内 3 张表）：空列注释 = 0/54 、空表注释 = 0/3          ✅
   那 3 个字符串默认值的列逐列确认：
     sync_task.source_mode        = 数据源模式：TABLE / CUSTOM_SQL
     sync_task.status             = 任务状态：ENABLED / DISABLED
     sync_task.full_sync_strategy = 全量策略：TRUNCATE / DELETE / SWAP
   '补列注释失败' = 0 条                                                ✅
② 有注释库 datasync_e2e：
   INFO DatabaseCommentInitializer - 元数据注释检查完成：本次补齐 0 条，跳过 67 条，失败 0 条
   '补列注释失败' = 0 条                                                ✅
   跳过 67 与"63 列 + 4 张表"吻合 → 不是"跳过一切"式假通过
```
**判定：D7 关闭。**（上轮残留的 10 条空注释属 `flyway_schema_history`，不在组件清单内，是设计边界。）

### D8 复测（最终产物）✅ 关闭

`/api/system/info` 五个字段齐全且有值（空闲启动全 0，诚实反映无背压）：
```
logDroppedMessages=0  logQueueDepth=0  logPeakQueueDepth=0  websocketDroppedMessages=0  websocketSessions=0
```
慢客户端场景的 `websocketDroppedMessages=12,382` 与 `logPeakQueueDepth` 的作用见 §3.3.6；
README 监控章节口径已按"看 `websocketDroppedMessages`；`logDroppedMessages` 恒 0 属正常"写明。**D8 关闭。**

### D10 复测（最终产物 `app-handover.jar`）⚠️ **代码已修并进包，但告警仍不可见 —— 根因推进到"类初始化时序"**

**产物核验**：17:39:44 / SHA256 `4EB096044693804EBC3FC308092502F53C440E3BA81E8F2E593E97514E7C868F`，
与我自算的逐字符一致。**分两半判定**：

**① 代码修复进包 ✅**（正确解析 class 常量池得到的证据）：
```
app-handover.jar 的 RunLog.class 含：
  "系统属性 {} 的值非法（"{}" 不是合法整数），已回落到默认值 {}"     ← 新增
  "系统属性 {} 的值非法（{} 必须为正整数），已回落到默认值 {}"       ← 新增
javap -c：resolveCapacity 调用 java/lang/Integer.decode（不再是 Integer.getInteger）
app-release2.jar（上一版）不含上述两条新文案  ← 差异确认
```

**② 运行时零日志输出 ❌**（`abc` / `0` / `8` 三种取值全部无任何相关行）：
```
-Ddatasync.log.queue-capacity=abc  → （无）        -Ddatasync.log.queue-capacity=0  → （无）
-Ddatasync.log.queue-capacity=8    → （无）        ← 连"覆盖为"的 INFO 也没有
同一次启动：SyncLogWebSocketHandler 的同类 WARN 正常出现；全日志 RunLog 行数 = 0
```

**根因**：`RunLog` 的容量解析在 **static 初始化块**执行，**早于 Logback 配置就绪** → 日志被丢弃。
`SyncLogWebSocketHandler` 的容量字段虽也是 `static final`，但首次触碰在 `@PostConstruct`
（Spring bean 初始化阶段，晚于 Logback 就绪），且把上限写进了那行 INFO，因此**有可观测落点**。

**我的自我更正**：上一轮我建议"按 `SyncLogWebSocketHandler` 的写法对齐即可（约 6 行）"——
**这个建议不充分**。Lead 照此改了、代码也确实进包了，但**告警依然看不见**，
因为差异的本质是"**解析发生在哪个阶段**"，不是"解析怎么写"。
可行修法：① 懒加载该容量；② 并入某个启动期可观测落点；③ 改为 Spring 配置属性绑定（最正统）。

**判定**：**D10 仍为开放项**（低优先级、不影响功能）。**不写成"已修复并复验通过"**——
以"运行时告警可见"为判据，它没有通过。

### D9/D7/D8 未回归确认（同一产物 `app-handover.jar`）✅
```
GET /       → 200 text/html        GET /tasks → 200 text/html
启动日志 '补列注释失败' 条数 = 0
INFO DatabaseCommentInitializer - 元数据注释检查完成：本次补齐 0 条，跳过 67 条，失败 0 条
/api/system/info 五字段：logDroppedMessages=0 logQueueDepth=0 logPeakQueueDepth=0 websocketDroppedMessages=0 websocketSessions=0
```
三条均未回归。


同一产物、同一参数位置、唯一变量是属性名：
```
13:34:35 WARN SyncLogWebSocketHandler - 系统属性 datasync.ws.session-queue-capacity 的值非法（abc），已回落到默认值 256   ✅
（日志 222 行中，关于 datasync.log.queue-capacity 的行数 = 0）                                                          ❌ 静默
```
`RunLog.resolveCapacity()` 仍是 `Integer.getInteger(name, def)`（对非数字返回 def 而非 null →
告警分支不可达）。**判定：仍为开放缺陷**（低优先级、不影响功能，见 §6 D10）。

> **两条我自己抓取失误的更正（保留，作方法论案例）**：
> ① 上轮我对"补齐 0 条，跳过 N 条…失败 0 条"那行的抓取失败了（匹配串与实现文案不一致），
>    我误判为"汇总行未找到"；本轮改用宽松匹配（`注释` + `补齐|跳过|失败`）后取到原文。
> ② 本轮 `abc` 那次我的脚本内联检查也误报"（无）"（同样的匹配串问题），
>    但**按 UTF-8 直读原始日志文件**取到了正确的 WARN 行。
> **教训（与本轮主线一致）：判据取不到时，先怀疑"我的提取方式"，再怀疑"被测系统"。**

### §3.9 交付产物复测（`app-handover.jar`，17:39:44，SHA256 `4EB096044693804EBC3FC308092502F53C440E3BA81E8F2E593E97514E7C868F`）

**产物核验**：我自算 SHA256 与 Lead 报的逐字符一致。
**D10 复测 ⚠️ 部分通过**（代码进包 ✅ / 运行时告警可见 ❌，根因推进到类初始化时序）→ 详见 §6 D10。
**D9/D7/D8 未回归确认**：
```
GET /      → 200 text/html            GET /tasks → 200 text/html
启动日志 '补列注释失败' 条数 = 0
INFO DatabaseCommentInitializer - 元数据注释检查完成：本次补齐 0 条，跳过 67 条，失败 0 条
/api/system/info: logDroppedMessages=0 logQueueDepth=0 logPeakQueueDepth=0 websocketDroppedMessages=0 websocketSessions=0
```

### §3.10 D10 最终复测（`app-d10fix.jar`，17:47:01，SHA256 `B93FCB821BC44F5FD1046358482FEF1EEF0189298AAC2753F63B18210F5C0CF5`）✅

**产物核验**：SHA256 与我自算的逐字符一致。
**三条日志行全部可见**（按 Lead 提醒，**判据只匹配 ASCII 部分**，避开 GBK 控制台的中文匹配陷阱）：
```
--- ① SyncLogWebSocketHandler 的 WARN（匹配 session-queue-capacity + abc）---
  17:49:55.135 WARN [main] c.d.s.config.SyncLogWebSocketHandler - 系统属性 datasync.ws.session-queue-capacity 的值非法（abc），已回落到默认值 256
--- ② RunLog 的 WARN（匹配 datasync.log.queue-capacity）---
  17:49:56.687 WARN [main] com.datasync.server.log.RunLog - 系统属性 datasync.log.queue-capacity 的值非法（"abc" 不是合法整数），已回落到默认值 2000
--- ③ StartupLogConfigReporter 的汇总 INFO（匹配类名）---
  17:49:56.687 INFO [main] c.d.s.c.StartupLogConfigReporter - 可配置项生效值：实时日志总线队列上限=2000（默认 2000，…）；WebSocket 会话队列上限=256（默认 256，…）
断言：① True   ② True   ③ True
全日志 RunLog 行数 = 1（上一版为 0）
```
**默认值场景（不带 `-D`）**：汇总行同样出现（2000/256），**无任何"非法值"WARN** → 正常路径不打扰 ✅

**D9/D7/D8 未回归 + 一条额外收获**：
```
GET /      → 200 text/html          GET /tasks → 200 text/html
启动日志 '补列注释失败' 条数 = 0
INFO DatabaseCommentInitializer - 元数据注释检查完成：本次补齐 0 条，跳过 67 条，失败 0 条
/api/system/info 五字段 = 0 / 0 / 1 / 0 / 0
                                   ↑ logPeakQueueDepth = 1
```
**`logPeakQueueDepth = 1` 是个好消息**：D8 改造后新增的这个水位指标，**在仅仅打了一行启动日志之后就已经非 0**
→ 从运行时证伪了"这又是一个恒 0 的死指标"，比单测断言更有说服力。
**判定：D10 关闭；D9/D7/D8 均未回归。**

---

## §4 补充测试结论（小表夹具）

| 用例 | 源 → 目标 | 结果 |
|---|---|---|
| 复合主键 | `cpk_table`（tenant_id, biz_no, seq）3000 行 | 一致（键集分页用元组比较，未退化成只比第一列） |
| 保留字列名 | `reserved_table` 50 行 | 一致 |
| 中文表名 + 中文列名 | `中文表_订单` 200 行 | 一致（含 emoji 客户名与中文备注） |
| 仅唯一键无主键 | `uk_table`（`uk_code`）100 行 | 一致 |
| 无主键 | `nopk_table` 50 行 | 预检 `SORT_KEY_MISSING` ERROR 拒绝执行 |
| 无索引增量列 | `noindex_incr_src/dst` 20 行 | 预检给出 `INCR_COLUMN_NO_INDEX` WARN（不阻断） |

---

## §5 **未验证项**（如实写全）

> 本节按 Lead 要求**只增不删**：其余部分通过，不代表这里可以顺手删条目。
> 宁可要一份带 N 条"未能验证"的诚实报告，也不要一份全绿但没人敢信的报告。
> 已通过补验的两条（U6 旧库升级、U7 Quartz 持久化）**保留并加删除线标注已补验**，不直接抹掉痕迹。

| # | 未验证内容 | 原因 | 影响评估 |
|---|---|---|---|
| U1 | **DM8 真机同步** | 本机无达梦实例、无 DM8 驱动 | 契约 D13 已标注"未验证"；`/api/system/info` 返回 `dm8Verified:false`（已核验）。**仅验证了"没有谎称支持"这一条**，方言正确性只有单测（`Dm8DialectTest` 9 项）作证 |
| U2 | **Docker 构建 / docker-compose 启动** | 本机无 Docker | `Dockerfile` 与 `docker-compose.yml` 的可用性**完全没验**。生产若走容器部署，这是未知风险 |
| U3 | **优雅停机时标记 `CANCELLED`** | Windows 无法向控制台 Java 进程发 SIGTERM | 已用等价手段覆盖"水位不推进"（§2.11）与"崩溃后不留假 SUCCESS"（§2.4）；"主动停机置 CANCELLED"**缺证据**，需 Linux 环境 |
| U4 | **前端全链路页面操作** | 我没有浏览器自动化工具（只有 Windows 桌面截图） | 构建与类型检查已独立通过（§2.12）；但**交互链路正确性未独立复验**——`vue-tsc` 通过不代表页面能点通 |
| U5 | **同步吞吐根因修复后的复测** | 根因已定位（每 chunk 同步 WebSocket 推送阻塞引擎线程，§3.3.2 结论六）；Lead 已拍板"异步有界回调"，platform 修复进行中、尚未收口打包 | **E/F 两格复测（E 不连 WebSocket vs F 挂一个实时日志页）待新 JAR 到位后执行**。判定：E 与 F 的**差值**是修复的验收对象、绝对值要等 platform 改完（core 默认路径仍是同步调用）。另需追加"F 格挂一个故意不读 socket 的慢客户端"边界用例 |
| U11 | **`RunMetricsListener` 语义变更后的"进度丢失"判定** | 契约已由 Lead 改为：进度是**累计绝对值**、回调**尽力而为**、唯一硬保证是 `run()` 返回前最后一次进度已投递 + `SyncRunResult` 最终统计永远准确 | **`droppedCallbacks() > 0` 不是缺陷**，是该语义下的正常表现。我按此口径记录，不判"进度丢失"（原口径"每 chunk 必调一次"已失效） |
| U12 | **`logDroppedMessages` 的端到端饱和** | 已按 Lead 要求在 `-Ddatasync.log.queue-capacity=8` 极端配置 + 20,000 次回调 + 零窗口慢客户端下尝试，**仍未触发**（原因见 §3.3.6：生产者已被上游 64 容量 listener 节流） | **结论按 Lead 口径写：端到端未能触发；该路径正确性由单测覆盖** —— `RunLogNonBlockingTest:39/49`（2300 条 > 容量 2000，断言 `droppedMessages()` 增长）。这一条**不再"纯空白"**，但也不是端到端实证 |
| U6 | ~~旧库升级路径~~ | — | **已在本轮补验通过**，见 §2.15 |
| U7 | ~~Quartz 重启后 Job 持久化（R2）~~ | — | **已在本轮补验通过**，见 §2.16 |
| U8 | **100 万行以上的规模** | 未测 | 1M 行已覆盖"不把整表读进内存"这一核心判断；更极端规模未验证 |
| U9 | **`mvn test` 中唯一 skipped 的用例** | `DefaultSyncEngineMySqlTest#fullSyncHundredThousandRows` 受 `-Ddatasync.it.bigrows=true` 控制，我没开这个开关 | 但**同一场景我用产品路径（HTTP + e2e 脚本）独立跑过**（§2.2，10 万行逐行一致），覆盖更强 |
| U10 | **前端对未知 `code` 的兜底展示**（契约 §3.6 要求不得白屏） | 需要浏览器/组件测试环境 | 未验证；属前端交互范畴 |

---

## §6 缺陷清单

> 分级：**阻塞** / **高** / **中** / **低**（建议项）。
> **本轮 12 条主验收未发现阻塞级缺陷。**

### D1（中 → **✅ 已修复并复测通过，可关闭**）键集分页的行构造器谓词未下推 → 每页扫 47 万条索引项

- **现象**：100 万行 INCR 稳定在 **644~885 ms/chunk**（三次运行、两个 JAR 版本都是这个量级），
  而同一工作量在数据库层面只要约 5 ms/500 行。
- **根因（Lead 假设，我在 3407 上原样复现，§3.3.3）**：
  引擎发出的 `AND (updated_at, id) > (?, ?)` **没有被下推成联合索引的范围起点**，
  优化器只按 `updated_at <= 上界` 建范围、再从起点**逐条扫索引项 + Filter** 直到凑够 1000 行。
  服务端耗时随游标深度增长：**浅游标 2.29 ms vs 深游标 592 ms**。
- **量化证据（修复前）**：`key_len` **8**、`Handler_read_next` **472,999**、服务端耗时 **570/607/602 ms**；
  对照展开式 OR：`key_len` **16**、`Handler_read_next` **999**、**2.93/3.97/2.89 ms** → **约 200 倍**。
- **修复后复测（终审产物 `app-final.jar`，§3.3.5）**：

  | 指标 | 修复前 | 修复后 | 改善 |
  |---|---|---|---|
  | `read_millis` | 747,631 ms | **3,349 ms** | **223×** |
  | `total_millis` | 757,131 ms | **11,871 ms** | **64×** |
  | ms/chunk | 757 | **11.9** | 64× |
  | 引擎 SQL | 行构造器 | **展开式 OR**（无行构造器） | — |
  | `canon` 摘要 | `7a531ad2…` | `7a531ad2…`（**不变**） | 语义等价 ✅ |
  | E/F/G 三格（含零窗口慢客户端） | — | 11.2 / 11.8 / 11.9 ms/chunk | 差 <5% ✅ |

- **判定**：**关闭**。功能正确性同时复核（100 万行逐行摘要与修复前一致、无重复）。
- **注**：`INCR_COLUMN_NO_INDEX`（无索引 303 ms/页）是**另一个独立问题**，两者都要治，不可互相替代。
- **证据**：§3.3.3（根因）、§3.3.5（复测）、§3.3.1/§3.3.2（我的两处撤回）。




### D2（中 → **已修复并复验通过**）请求体中的未知字段被静默忽略

**原缺陷**：我用 `errorPolicyJson`（DB 列名）建任务，服务端返回 200、任务创建成功，
但 `error_policy_json` 落库为 NULL，`skipBadRows` 从未生效——**客户端收不到任何警告**。

**修复后复验（§2.18，我独立复现，两个方向都验了）**：

| 用例 | 期望 | 实测 |
|---|---|---|
| 拼错字段 `errorPolicyy` | 400 + `UNKNOWN_FIELD` + 点名该字段 | ✅ `400` / `code=UNKNOWN_FIELD` / `details:["errorPolicyy"]`，消息还提示"接口字段名与数据库列名不一定相同" |
| 合法别名 `errorPolicyJson`（前端在用） | 200 且落库非空 | ✅ `200`，落库 `{"skipBadRows":true,"maxSkipRows":42}` |
| 合法对象 `errorPolicy` | 200 且落库 | ✅ 落库 `{"skipBadRows":true,"maxSkipRows":7}` |

**结论**：缺陷关闭。且**别名方向特意保留并被接受**——若只验拒绝，会把前端 `TaskForm.vue` 弄坏。


### D3（低）`DatabaseCommentInitializer` 每次启动执行 DDL，重建的列定义有属性丢失风险

- **现象**：每次启动都对 `datasource`/`sync_task`/`sync_record`/`sync_error` 执行
  `ALTER TABLE … MODIFY COLUMN`；重建 DDL 只含 `COLUMN_TYPE + NOT NULL + DEFAULT + EXTRA`。
- **当前是否造成损害**：**没有**。实测全新库启动后列属性与 V1 声明完全一致
  （`utf8mb4_general_ci`、`auto_increment`、`NOT NULL` 都保住，注释 10/10）。
- **风险**：`CHARACTER SET`/`COLLATE`/`GENERATED` 不在重建 DDL 内；`DEFAULT` 是字符串直拼；
  失败被吞成 `debug`；多实例启动时会并发做 DDL。
- **建议**：见 §3.4。**证据**：§3.4。

### D4（低）`skipBadRows` 不覆盖读阶段异常，且文档未说明

- **现象**：源表含 `0000-00-00` 时，`skipBadRows=true` 与 `false` 的结果完全相同（都 FAILED）。
  （这条路径仍然**安全**：水位不推进、目标不写入。）
- **影响**：运维会误以为开了"跳过坏行"就能容忍源库里的非法日期，实际不能。
- **建议**：在 README 与相关说明里写清"跳过只对行级 MAP/WRITE 生效"。**证据**：§2.8。

### D5（低）契约措辞建议：`DECIMAL→VARCHAR` 举例为"类型不兼容"并不贴切

- **现象**：契约 §5.6 用"类型不兼容"举例，实测该组合被判为 RISKY（WARN）并在 MAP 阶段拦截，
  预检**不报** `TYPE_INCOMPATIBLE`。
- **建议**：契约改用"日期时间→数值 / 大文本→数值 / 二进制→数值"这类真正不可能的组合举例，
  并把 `DECIMAL→VARCHAR` 明确列为 RISKY 场景（两种夹具都已放进 `fixtures/`）。
- **证据**：§2.7。

### D6（中 → **已修复并复验通过**）历史明文口令的自动升级触发面太窄，最需要它的场景恰好不升级

**原缺陷**：契约 §4.2 说"首次**读取**时自动升级"，实测升级只在**连接池创建成功**这条路径上发生
（`ConnectionPoolRegistry.get()` → `upgradeLegacyPassword`）；`GET /api/datasources` 只 `decrypt` 不回写。
更关键的是：**如果口令本身是错的（连不上）**，`createEntry` 抛异常 → 升级**也不执行** → **明文无限期留在库里**。

**修复后复验（§2.17，我独立复现）**：

| 路径 | 场景 | 结果 |
|---|---|---|
| 启动扫描 | 启动**前**植入明文 + 端口连不上 | ✅ 两条都变 `cipher`；日志 `启动扫描：2 条…（无需数据库连通性）` |
| 读取路径 | 启动**后**改回明文 → **只调一次 GET** | ✅ `GET /api/datasources/101` 后立即 `cipher`，**全程未连库** |
| 建池兜底 | 保留 | 未单独构造（前两条已覆盖无连通性场景） |

**结论**：缺陷关闭。且**抓到的恰好是我原报告指出的失效场景**——"连不上的旧数据源"现在也会被升级。


- **现象**：契约 §4.2 说"首次**读取**时自动升级"，实测升级只在
  **连接池创建成功**这条路径上发生（`ConnectionPoolRegistry.get()` → `upgradeLegacyPassword`）；
  `GET /api/datasources` 只 `decrypt` 不回写。更关键的是：**如果口令本身是错的（连不上）**，
  `createEntry` 抛异常 → 升级**也不执行** → **明文无限期留在库里**。
- **最小复现**（§2.17 完整命令）：
  1. 建库只有 `datasource` 表，插两条**明文且正确**的 `qa_root_123`；
  2. 启动应用，反复 `GET /api/datasources` 与 `GET /api/datasources/{id}` → 库里**仍是明文**（`is_enc=0`）；
  3. 触发一次同步（成功建池）→ 立刻变成 `ENC(...)`（`is_enc=1`）；
  4. 把口令改成错的，再反复 GET / 触发同步（连接失败）→ **永远保持明文**。
- **影响**：这正是"库里躺着一条连不上的旧数据源"的真实场景——而它恰恰是明文口令长期留存的场景。
  契约 D9 的目标是"口令不得明文落库"，当前实现只在"能连上"的子集里达成。
- **建议**（`platform`）：把升级挂到启动时的一次性扫描，或挂到读取路径（读到明文即加密回写），
  而不是依赖"连接池创建成功"这个附带动作；同时把契约 §4.2 的"首次读取"措辞改准确。
- **证据**：§2.17。

---

## §7 结论

**可以背书的范围**（MySQL → MySQL 主线）：

- 12 条验收口径中 **11 条通过**、**1 条部分验证**（验收 10 的 CANCELLED 标记受 Windows 平台限制）。
- 功能主线（全量/增量/幂等/续传/并发/预检/坏行/水位）在**独立环境、独立工具、逐行摘要级**的验证下全部成立。
- 数据正确性证据强度：10 万行与 100 万行都是**逐行规范化 SHA-256 完全一致 + 落盘逐字节相同**，
  且目标摘要与我"手工装载同一份夹具"的基线相同。
- 三项额外补验（本轮完成）：**旧库升级路径**（§2.15）、**Quartz 重启持久化**（§2.16）、
  **明文口令升级触发条件**（§2.17）。

**本次找到的缺陷（6 条，无阻塞级）**

| 编号 | 级别 | 一句话 | 负责人 |
|---|---|---|---|
| D1 | 中 | INCR 路径吞吐比 FULL 低约 50 倍，SQL 已排除、定位需埋点 | `engine` |
| D2 | 中 | 请求体未知字段被静默忽略（我因此误判过一次） | `platform` |
| D6 | 中 | 明文口令升级只在"连接池创建成功"时发生，连不上就永久明文 | `platform` |
| D3 | 低 | `DatabaseCommentInitializer` 每次启动做 DDL（实测未造成损害） | `platform` |
| D4 | 低 | `skipBadRows` 不覆盖读阶段异常，文档未说明 | `engine` + 文档 |
| D5 | 低 | 契约用 `DECIMAL→VARCHAR` 举例"类型不兼容"不贴切 | Lead（改契约措辞） |

**上生产前建议补齐**（按优先级）：

1. **U3**：在 Linux 环境验证优雅停机 → `CANCELLED` 标记（唯一缺的验收断言）。
2. **D6**：明文口令升级改到启动扫描或读取路径（安全属性，当前只在子集上成立）。
3. **D2**：未知请求字段改为显式报错（否则"配了不生效"类故障会持续坑运维）。
4. **D1**：定位 INCR 吞吐 6.6 倍差距（生产大盘表的运维风险）。
5. **U2/U4**：Docker 构建与前端全链路必须在有对应环境时补验——**目前是纯空白**。
6. **U1**：DM8 保持"未验证"标注，不要在对外文档里含糊。

**我不能背书的部分**：DM8 真机、Docker 部署、前端交互全链路、超过 100 万行的规模、
INCR 性能的根因。这几项之外的结论，都可以用本报告里的命令在我的 3407/3408 环境上原样复现。
