# DataSync 独立验收方案（QA）

> 本文件由**独立质量工程师**维护，判卷标准是 `docs/production-readiness/00-frozen-contract.md` §5 的 12 条验收口径。
> 原则：**开发者的"跑通了"不作为证据**。每一条结论必须能由下面的命令在独立环境上复现。
> 阶段 B 的实测结论与证据写进 `04-acceptance-report.md`（本文件只描述"怎么测、期望什么"）。

---

## 0. 独立验收环境（不共享、不复用开发者的库）

| 项 | 值 | 说明 |
|---|---|---|
| 源库实例 | `127.0.0.1:3407` | 独立 mysqld，数据目录 `.dsh-scratch/qa/mysql-src` |
| 目标库实例 | `127.0.0.1:3408` | 独立 mysqld，数据目录 `.dsh-scratch/qa/mysql-dst` |
| 账号 | `root / qa_root_123` | 两个实例都是空库 + 全新 root，与 3306 的 `MySQL80` 服务**完全隔离** |
| 业务库名 | `datasync` | 源/目标各一个，同名 |
| 元数据库 | `datasync_e2e`（在 3408 实例上） | 与被测服务隔离于 3306 的 `datasync` |
| 被测服务端口 | `18080` | 避免与 8080 上的任何手工验证冲突 |
| 服务元数据库环境变量 | `DATASYNC_DB_HOST/PORT/NAME/USER/PASSWORD` | 见 `application.yml` 第 19–22 行 |
| 安全开关 | `DATASYNC_AUTH_ENABLED=false`（功能验收）/ `true`（第 9 项认证专项） |
| 管理员口令 | `admin / qa_admin_12345`、`DATASYNC_SECRET_KEY` ≥32 字符 |
| 工作目录 | `.dsh-scratch/qa/`（已 gitignore） |
| 夹具脚本 | `docs/production-readiness/fixtures/*.sql`（长期复用，入仓） |

**环境启动（一次）**

```powershell
$qa = "D:\AI\DeepSeek Harness\data-sync\.dsh-scratch\qa"
$mysqld = "D:\Program Files\MySQL\MySQL Server 8.0\bin\mysqld.exe"
foreach($n in @("src","dst")){
  # 注意：Start-Process 启动的子进程会随 DSH 会话结束被回收，必须用 WMI 脱离父进程
  Invoke-CimMethod -ClassName Win32_Process -MethodName Create `
    -Arguments @{ CommandLine = '"' + $mysqld + '" --defaults-file="' + $qa + '\my-' + $n + '.ini"' }
}
```

**两个实例的 `sql_mode` 实际取值（夹具可复现性的前提，Lead 要求写明）**

```
ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION
```

该取值是 MySQL 8.0.46 在未显式覆盖 `sql_mode` 时的默认值（`my-src.ini` / `my-dst.ini` 里没有改它）。
其中两条直接决定夹具行为：

- `STRICT_TRANS_TABLES` → 坏行夹具的"写阶段失败"路径成立：往 `bad_row_dst.val VARCHAR(32)` 写 120 字符
  实测报 `ERROR 1406 (22001): Data too long for column 'val' at row 1`（不是截断后静默成功）。
- `NO_ZERO_DATE` → 正常情况下**写不进** `0000-00-00`，所以坏行夹具的 `load_small_tables.sql` 内部
  先 `SET SESSION sql_mode=''` 再插入（只影响该会话，不改变实例默认值）。

**脚本编码铁律（血泪教训）**：`.dsh-scratch/qa/*.ps1` 必须是 **UTF-8 带 BOM**。
PowerShell 5.1 读无 BOM 的 UTF-8 会按 GBK 解码，中文字符串被破坏后引号配对失效，
报出来的是"意外的标记 }"这种完全误导的错误。改完脚本一律跑：

```powershell
& powershell -NoProfile -File "$qa\fix-ps1.ps1"    # 补 BOM + 逐个语法检查
```

**环境现状核对**

```powershell
. "$qa\qa-env.ps1"
Qa-Sql "SELECT @@port, @@version, @@character_set_server, @@time_zone;" 3407
Qa-Sql "SELECT @@port, @@version FROM DUAL;" 3408
```

**已知的环境注意事项（踩过的坑，写下来省时间）**

1. **`Start-Process` 起的 mysqld 会随会话被回收**（PID 立刻就没了，错误日志里连启动记录都没有）。
   解法：用 `Invoke-CimMethod Win32_Process Create` 脱离父进程启动。同理，WMI 子进程**无法写文件**（沙箱限制），
   所以需要落盘日志的长任务要用 `run_in_background` 的普通 shell 跑，不要用 WMI。
2. **`Get-Content ... | mysql` 会破坏 UTF-8**：PowerShell 管道按控制台编码（GBK）重新编码，emoji / 中文会变成 `?`，
   进而引发 `ERROR 1064`。**装载 SQL 一律用 `mysql --execute="source <绝对路径>"`**（让 mysql 自己读文件字节）。
3. **mysql CLI 会把 `--foo` 当列别名**：`compare.py count --cols id` 曾经被解析成别名为 `i`、
   列名为 `d`。所有多行 SQL 都改成"子查询内聚合 + `AS x`"写法。
4. **`NO_ZERO_DATE` / `STRICT_TRANS_TABLES` 是默认开着的**：造非法日期夹具时脚本内必须 `SET SESSION sql_mode=''`。
5. Maven 中文输出乱码：一律重定向到文件再用 read 工具看。

---

## 1. 夹具清单（`docs/production-readiness/fixtures/`）

| 文件 | 内容 | 覆盖的"找茬点" |
|---|---|---|
| `01-schema-src.sql` | 源库 10 张表结构 | 主表 13 列覆盖 `BIGINT`/`DECIMAL(38,10)`/`DOUBLE`/`BIT(1)`/`TINYINT(1)`/`VARCHAR(200)`/`TEXT`/`BLOB`/`JSON`/`DATETIME(6)`/`DATE`/`TIME(6)`；复合主键、无主键、仅唯一键、保留字列名（`order`/`desc`/`group`/`key`/`select`/`from`）、中文表名 `中文表_订单` 与中文列名 |
| `02-schema-dst.sql` | 目标库结构，**故意保留 3 处差异** | `bad_row_dst.val VARCHAR(32)`（源 200）→ 运行期坏行；`type_clash_dst.amount VARCHAR(10)`（源 `DECIMAL(38,10)`）→ 预检类型不兼容；`noindex_incr_dst` 增量列无索引 → `INCR_COLUMN_NO_INDEX` WARN |
| 数据由 `.dsh-scratch/qa/gen_fixtures.py` 生成 | 主表 10 万行，每 100 行一个循环覆盖一类边界 | 第 7/13/17 行是 `BIGINT` 上下界与 0；第 23/29 行是 `DECIMAL(38,10)` 上下界；第 19/31 行是 200 个 ASCII / 200 个中文（utf8mb4 下 600 字节）；第 37 行空串 vs NULL；第 47 行含单引号 + 反斜杠 + 制表符；第 53 行组合 emoji（含 ZWJ）；第 61 行 2 万字 TEXT；第 71 行 NULL BLOB；第 73/79/83 行 JSON NULL / 空结构 / 中文键；第 89/91 行 NULL 日期/时间；微秒位非零（`*.100007` 起） |

**主表数据是确定性的**（不用 `random`），任何人在任何时间重跑都得到同一份数据，因此校验和可以直接相互引用。

**生成 + 装载**

```powershell
& $QaPython "$qa\gen_fixtures.py" --out "$qa\fixtures" --rows 100000 --batch 500   # 约 77 MB / 200 个文件
(Get-Content -Raw -Encoding UTF8 "$fx\01-schema-src.sql") | & $mysql ... datasync   # 结构（纯 DDL 无中文数据，可用管道）
foreach($f in (Get-ChildItem "$qa\fixtures\load_big_table_*.sql" | Sort-Object Name)){ Qa-Source $f.FullName 3407 }
Qa-Source "$qa\fixtures\load_small_tables.sql" 3407
```

---

## 2. 比对工具（`.dsh-scratch/qa/compare.py` + `jdbc/CompareTable.java`）

**为什么不用纯 Python 驱动**：本机 Python 3.11 没有 `pymysql`/`mysql-connector`，但本地 Maven 仓库里有
`mysql-connector-j-9.7.0.jar`。用 JDBC 做比对的好处是**类型保真**：`BIT(1)`、`BLOB`、`DECIMAL(38,10)`、
`DATETIME(6)` 都由驱动给出确定的 Java 类型，比字符拼接可靠得多。

**规范化规则（两侧完全一致才判等）**

| 类型 | 规范化结果 |
|---|---|
| `NULL` | `\N`（与空串 `""` **严格区分**） |
| `BIT(n)` | `0x…` 位串（不与 `TINYINT` 混淆） |
| `BLOB`/`BINARY` | `0x…` 十六进制 |
| `DECIMAL` | `toPlainString()`，保留标度（`1.10 ≠ 1.1`） |
| `DATETIME(6)` | `yyyy-MM-dd HH:mm:ss.SSSSSS`（微秒必须一致） |
| `DATE`/`TIME(6)` | 同上风格 |
| 字符串 | UTF-8 原文，只转义 `\`、制表符、CR、LF |

**五种判等方式**

```powershell
& $QaPython "$qa\compare.py" schema    --table big_table              # 列名/类型/可空/主键/顺序 逐列比对
& $QaPython "$qa\compare.py" count     --table big_table --cols id    # 行数 + 目标侧重复分组数
& $QaPython "$qa\compare.py" digest    --table big_table --order id   # 全列流式 SHA-256 + 行数（最强）
& $QaPython "$qa\compare.py" keydigest --table big_table --cols id    # 仅主键集合摘要（检测多/少/重复）
& $QaPython "$qa\compare.py" diff      --table big_table --order id --max-show 5   # 差异行逐个定位
```

**工具自证（先证明工具能报错，再相信它的"通过"）**

```powershell
# A 基线：两侧都装同一份夹具 → digest/diff 必须一致
# B 注入差异：目标 UPDATE 一行金额 +1、DELETE 一行 → count/digest/diff 必须全部报 FAIL 并定位到具体行
# C 注入重复：目标去掉主键后复制一行 → count 的 dst_dup_groups 必须 = 1
```

阶段 A 已完成 A/B/C 三步自证（见 `04-acceptance-report.md` §0），工具确实能抓到"内容篡改 / 多行 / 少行 / 重复行"。

---

## 3. 端到端驱动（`.dsh-scratch/qa/e2e.ps1`）

```powershell
$E = "$qa\e2e.ps1"
& $E meta-reset                 # 重建独立元数据库（Flyway 重新迁移）
& $E start                      # 18080 启动被测 JAR，日志落 .dsh-scratch/qa/logs/
& $E health                     # /actuator/health
& $E run -TaskName t1 -SrcTable big_table -DstTable big_table -Mode FULL
& $E poll  -TaskId <id>         # 轮询执行记录到终态
& $E preflight -TaskId <id>     # GET /api/tasks/{id}/preflight
& $E concurrent -TaskId <id> -Count 5
& $E sys-info                   # GET /api/system/info
& $E auth-check                 # 未登录 401/302 + 登录后可访问
& $E stop
```

驱动脚本的关键设计：
* 元数据库走环境变量（`DATASYNC_DB_*`），**不修改仓库里的 `application.yml`**；
* 每个命令返回结构化 JSON，方便把原始输出粘进报告；
* `concurrent` 用 5 个独立 Job 同时 POST `/trigger`，收集 HTTP 状态码与响应体原文（这就是第 5 项的证据）；
* `stop` 用 `taskkill /T /F`；**优雅停机（第 10 项）另有专门步骤**（见 §4.10）；
* 请求体用 `System.Web.Script.Serialization.JavaScriptSerializer` 编码
  —— PS 5.1 的 `ConvertTo-Json` 会把中文转成 `\uXXXX`，中文表名/任务名会因服务端按 ASCII 解析而失败；
* JAR 路径含空格（`DeepSeek Harness`），`Start-Process -ArgumentList` 不做 shell 引号处理，
  必须写成 `` "`"$Jar`"" ``，否则 java 收到 `-jar D:\AI\DeepSeek` 报 `Unable to access jarfile`。

**驾驶舱自证（在被测服务可用之前就先证明"驾驶舱"是对的）**

`.dsh-scratch/qa/stub_server.py` 是一个最小桩服务（实现 `/actuator/health`、`/api/datasources`、
`/api/tasks`、`/api/tasks/{id}/trigger`、`/api/tasks/{id}/records`、`/api/system/info`，
并把触发的记录在 3 秒后置为 COMPLETED）。用它验证 `e2e.ps1` 本身：

```powershell
# 桩必须用后台作业跑（Start-Process 起的子进程会随会话被回收）
& $QaPython "$qa\stub_server.py" --port 18080        # run_in_background
& "$qa\e2e.ps1" health
& "$qa\e2e.ps1" run -TaskName "中文任务-含emoji-🚀" -SrcTable big_table -DstTable big_table -Mode FULL
& "$qa\e2e.ps1" poll -TaskId 1
& "$qa\e2e.ps1" concurrent -TaskId 1 -Count 5
& "$qa\e2e.ps1" sys-info
```

实测（`04-acceptance-report.md` §0.5 有完整输出）：中文任务名/中文表名往返正确、轮询拿到终态
`COMPLETED` 与 `read/write/skipped` 字段、并发 5 次全部收到真实 HTTP 结果并正确汇总。
**这一步的意义**：阶段 B 里如果某条验收失败，我能确定是"被测服务的问题"而不是"我的脚本读错了字段"。

---

## 4. 12 条验收口径的测法

> 每条给出：**测法（可复现命令）→ 期望结果 → 失败判定**。取证时把命令原始输出贴进 `04-acceptance-report.md`。

### 验收 1 —— 构建 + 启动 + Flyway
```powershell
& "S:\installationFree\apache-maven-3.9.15\bin\mvn.cmd" -B -s "D:\AI\DeepSeek Harness\jc\project\.dsh-probe\settings.xml" -DskipTests package *> "$qa\logs\build.log"
& $E meta-reset; & $E start
Qa-Sql "SELECT installed_rank, version, description, success FROM flyway_schema_history ORDER BY installed_rank;" 3408 datasync_e2e
```
**期望**：构建 exit 0；JAR 存在；`/actuator/health` 200；`flyway_schema_history` 每一行 `success=1`；
`QRTZ_*` 表存在；`sync_error` 表存在。
**失败判定**：任一迁移 `success=0`；`ddl-auto=validate` 抛 `SchemaManagementException`；JAR 里仍含 `spring-batch-*`。

### 验收 2 —— 10 万行全量同步逐行一致
```powershell
& $E run -TaskName full100k -SrcTable big_table -DstTable big_table -Mode FULL
& $E poll -TaskId <id>                       # 记录 readRows/writtenRows
& $QaPython "$qa\compare.py" count     --table big_table --cols id
& $QaPython "$qa\compare.py" digest    --table big_table --order id
& $QaPython "$qa\compare.py" keydigest --table big_table --cols id
& $QaPython "$qa\compare.py" diff      --table big_table --order id
```
**期望**：行数 100000 = 100000；`digest` 两侧 SHA-256 完全相同；`dst_dup_groups=0`；diff 逐行一致；
记录里 `readRows=writtenRows=100000`。
**失败判定**：任一摘要不等；行数不等；有重复分组。

### 验收 2b —— 内存是否稳定（是否真流式）★重点找茬
```powershell
# 在同步进行中每 2 秒采样一次 JVM 堆占用
1..60 | ForEach-Object {
  $p = Get-Process -Id (Get-Content "$qa\server-e2e.pid") -ErrorAction SilentlyContinue
  if($p){ "{0} WorkingSet={1}MB" -f (Get-Date -f HH:mm:ss), [math]::Round($p.WorkingSet64/1MB,0) }
  Start-Sleep -Seconds 2
} | Tee-Object "$qa\logs\heap-samples.txt"
```
另外**静态核验**（不接受口头承诺）：
```powershell
Select-String -Path "data-sync-core\src\main\java\com\datasync\core\**\*.java" -Pattern "setFetchSize|Integer.MIN_VALUE|LIMIT|OFFSET" 
```
**期望**：堆占用随分页推进呈锯齿状（chunk 提交后回落），峰值不随总行数线性增长（10 万行 ≪ `-Xmx` 默认值上限）；
源码中**不存在** `LIMIT ? OFFSET ?`；MySQL 路径设置了 `setFetchSize(Integer.MIN_VALUE)` 或等价的流式游标；
不存在"把整表读进 `List`"的写法。
**失败判定**：峰值内存 ≈ 全表大小；出现 OOM；源码含 OFFSET 分页。
**为什么必须测**：10 万行只是开胃菜，生产表可能是上亿行——把整表读进内存的实现在这里就会露馅。

### 验收 3 —— 断点续传（kill 后重跑最终一致）★重点找茬
```powershell
# 前置：水位表 500 行已同步，然后向源插入 6000 行增量数据
Qa-Sql "INSERT INTO watermark_src SELECT id+500, CONCAT('增量',id), '2026-08-01 10:00:00.000001' FROM watermark_src;" 3407
& $E run -TaskName incr-resume -SrcTable watermark_src -DstTable watermark_dst -Mode INCR -IncrColumn updated_at
# 触发后立刻在同步中途强杀
taskkill /PID (Get-Content "$qa\server-e2e.pid") /T /F
& $E start ; & $E trigger -TaskId <id> ; & $E poll -TaskId <id>
& $QaPython "$qa\compare.py" digest --table watermark_dst --order id   # 注意：源表名/目标表名不同，用 diff 比对
```
**期望**：中途被杀后重启重跑，最终目标表与源表**逐行一致（无重复、无丢失）**；
`sync_record` 里能看到前一次是 `FAILED`/`CANCELLED`（而不是伪装成 SUCCESS）。
**失败判定**：目标表行数 > 源表（重复写导致）或 < 源表（丢数）；`cursor_value` 在被杀的那次就推进了。
**为什么必须测**：断点续传是最容易"看起来对、实际丢数"的地方——重放必须靠幂等 upsert 兜底。

### 验收 4 —— 幂等性（连跑 3 次）
```powershell
& $E trigger -TaskId <id>; & $E poll -TaskId <id>   # 第 1 次
$before = Qa-Scalar "SELECT COUNT(*) FROM big_table" 3408
& $QaPython "$qa\compare.py" digest --table big_table --order id > "$qa\out\idem-before.txt"
& $E trigger -TaskId <id>; & $E poll -TaskId <id>   # 第 2 次
& $E trigger -TaskId <id>; & $E poll -TaskId <id>   # 第 3 次
```
**期望**：3 次执行后 `COUNT(*)` 完全不变，`digest` 与第 1 次后完全相同；
**且**增量/幂等模式下**没有** TRUNCATE（`FULL_INCR` 首次除外）。
**失败判定**：行数增长（重复插入）；内容变化；增量模式清了表。

### 验收 5 —— 并发触发同一任务 5 次
```powershell
& $E concurrent -TaskId <id> -Count 5
Qa-Sql "SELECT COUNT(*) FROM sync_record WHERE task_id=<id> AND status='RUNNING';" 3408 datasync_e2e
```
**期望**：5 次 POST 中只有 1 次真正开始执行；其余返回**明确中文提示**（如"任务正在执行中"）而不是 500；
`sync_record` 里 RUNNING 记录数 = 1；`sync_task`/`sync_record` 上有 DB 级唯一约束兜底。
**失败判定**：出现 2 条并发 RUNNING；返回 500 或英文堆栈。

### 验收 6 —— 预检在写任何数据之前失败 ★重点找茬
```powershell
# 6a 目标表不存在
& $E run -TaskName pf-nodst -SrcTable big_table -DstTable big_table_not_exist -Mode FULL
# 6b 增量字段不存在
& $E run -TaskName pf-noincr -SrcTable watermark_src -DstTable watermark_dst -Mode INCR -IncrColumn no_such_col
# 6c 类型不兼容（源 DECIMAL(38,10) → 目标 VARCHAR(10)）
& $E run -TaskName pf-type -SrcTable type_clash_src -DstTable type_clash_dst -Mode FULL
# 6d 目标无主键
& $E run -TaskName pf-nopk -SrcTable nopk_table -DstTable nopk_table -Mode FULL
```
每种情况都在**执行前后各查一次目标表行数**：
```powershell
$b = Qa-Scalar "SELECT COUNT(*) FROM <目标表>" 3408
... 触发并轮询到终态 ...
$a = Qa-Scalar "SELECT COUNT(*) FROM <目标表>" 3408
"before=$b after=$a"   # 必须完全相等
```
**期望**：任务失败（`status=FAILED`），`sync_record` 里能看到 `PREFLIGHT` 阶段问题，
错误码分别是 `DST_TABLE_MISSING` / `INCR_COLUMN_MISSING` / `TYPE_INCOMPATIBLE` / `PK_MISSING`；
目标表行数在执行前后**完全没变**（这是"写之前就失败"的唯一硬证据）。
**失败判定**：行数变了（哪怕 TRUNCATE 成 0 也算失败）；错误码不对；任务"成功"了。

### 验收 7 —— 坏行策略 ★重点找茬（读阶段 + 写阶段两条路径都要有证据）

Lead 裁定：**保持 Connector/J 默认（不给 JDBC URL 加 `zeroDateTimeBehavior=convertToNull`）**，
理由是默认值把"源库里存在非法日期"暴露在读阶段，比悄悄转成 NULL 再写坏目标库诚实。
预验证实：读含 `0000-00-00 00:00:00` 的行会直接抛
`java.sql.SQLException: Zero date value prohibited`（证据见报告 §0.5 R3）。

```powershell
# ---- 7a 读阶段失败：源表水位列无零日期（保证能读进去），另设一条零日期行 ----
#   bad_row_src 含：id=3 超长值(120>32)、id=4 零日期
#   a1) 只保留 id=3 的"写阶段坏行"：临时删掉 id=4
Qa-Sql "DELETE FROM bad_row_src WHERE id=4;" 3407
& $E run -TaskName bad-write-fail -SrcTable bad_row_src -DstTable bad_row_dst -Mode FULL
& $E poll -TaskId <id>
Qa-Sql "SELECT id, cursor_value FROM sync_task WHERE id=<id>;" 3408 datasync_e2e   # 水位必须没变
#   a2) 恢复 id=4（零日期）→ 读阶段失败
Qa-Source "$qa\fixtures\load_small_tables.sql" 3407
& $E trigger -TaskId <id>; & $E poll -TaskId <id>
Qa-Sql "SELECT id, cursor_value FROM sync_task WHERE id=<id>;" 3408 datasync_e2e   # 水位仍必须没变
Qa-Sql "SELECT phase, row_key, LEFT(message,80) FROM sync_error WHERE record_id=<record>;" 3408 datasync_e2e

# ---- 7b 跳过坏行：skipBadRows=true ----
Qa-Sql "UPDATE sync_task SET error_policy_json='{\"skipBadRows\":true,\"maxSkipRows\":100}' WHERE id=<id>;" 3408 datasync_e2e
& $E trigger -TaskId <id>; & $E poll -TaskId <id>
& $E records -TaskId <id>            # skippedRows 必须 > 0
```

**期望**：

| 子场景 | 期望结果 |
|---|---|
| 7a-1 写阶段坏行、`skipBadRows=false` | 任务 `FAILED`；`sync_error` 里 `phase=WRITE`（或 `MAP`）；**`cursor_value` 不推进** |
| 7a-2 读阶段坏行（零日期） | 任务 `FAILED`；`sync_error` 里 `phase=READ`；**`cursor_value` 不推进** |
| 7b-1 写阶段坏行、`skipBadRows=true` | 任务 `COMPLETED`；`skipped_rows>0`；`sync_error` 有明细；**水位正常推进** |
| 7b-2 读阶段坏行、`skipBadRows=true` | 任务 `COMPLETED`；`skipped_rows>0`；`sync_error` 有明细；**水位正常推进** |

**水位推进的判定依据（这是设计决策，不是实现细节，QA 明确写下自己的立场）**：
- **跳过 = 已知并记录 → 允许前进**。坏行已被显式写入 `sync_error` 并计入 `skippedRows`，
  运维有据可查、可单独补数；若此时仍卡住水位，任务会永久卡在同一行上，反而变成"不可自愈"。
- **失败 = 未知 → 必须停下**。任务整体失败时我们没有"这一行已被处理"的任何记录，
  推进水位就等于把这段数据永久跳过（这正是原设计"读失败也推进 `incrValue`"的老毛病）。
- 两种情况都必须满足：`maxSkipRows` 超限时整体失败（防止坏行面扩大被静默吞掉）。

**失败判定**：任一路径的水位被推进（失败时）或未推进（成功跳过时）；`sync_error` 没有明细；
`skippedRows` 不变化；坏行被静默丢弃且无记录。

### 验收 8 —— 口令密文 + 脱敏 + 日志无明文 ★重点找茬
```powershell
Qa-Sql "SELECT id, name, password FROM datasource;" 3408 datasync_e2e
(Req GET "/api/datasources?size=200").content | Select-Object id,name,password
# 全仓库 + 日志搜明文
Select-String -Path ".dsh-scratch\qa\logs\*.log" -Pattern "qa_root_123|123456" -SimpleMatch
Get-ChildItem -Recurse -File -Include *.java,*.yml,*.ts,*.vue,*.md -Path . |
  Where-Object { $_.FullName -notmatch '\\target\\|\\.dsh-scratch\\|\\node_modules\\' } |
  Select-String -Pattern "123456" -SimpleMatch
```
**期望**：`datasource.password` 全部以 `ENC(` 开头（AES-GCM）；API 响应的 password 为空或掩码；
服务日志里**搜不到**任何明文口令（连接测试失败时的异常消息也必须清洗过）。
**失败判定**：库里出现明文；API 回显明文；日志出现明文。

### 验收 9 —— 认证与 CSRF（端点形态由 Lead 冻结，以此为准）
```powershell
& $E start -Secure
& $E auth-check
```
冻结的认证契约（若 platform 实现与此有出入，以他的实现为准，但必须同步更新 README 与契约 §4.3）：

| 方法 | 路径 | 匿名? | 语义 |
|---|---|---|---|
| GET | `/api/auth/csrf` | 是 | `200 {"token":"<csrf>","headerName":"X-CSRF-TOKEN"}` |
| GET | `/api/auth/session` | 是 | `200 {"authenticated":true|false,"username":"..."}` |
| POST | `/api/auth/login` | 是 | JSON 请求体 `{"username","password"}` **+ 请求头 `X-CSRF-TOKEN`**；成功 200，失败 401 |
| POST | `/api/auth/logout` | 否 | 需认证 + CSRF → 200 |

**期望的 auth-check 断言序列**：
1. 不带会话访问 `/api/tasks` → **401**（JSON 体，`code=UNAUTHENTICATED`）。**注意不是 302**
   （浏览器导航到非 `/api` 路径才 302 → `/login`，`/api/**` 一律 401）。
2. `GET /api/auth/csrf` 拿 token（匿名可访问）。
3. 带 `X-CSRF-TOKEN` 头 POST `/api/auth/login` → 200 `{"authenticated":true}`，响应带 `Set-Cookie: JSESSIONID`。
4. 用该会话访问 `/api/tasks` → 200。
5. **不带 CSRF token** 的写请求（如 POST `/api/tasks`）→ **403**。
6. `GET /actuator/health` 匿名 → 200。
7. 登出后再访问 `/api/tasks` → 401。

**失败判定**：未登录能读数据；`/api/**` 返回 302 而不是 401（与冻结契约不符）；
写请求不需要 CSRF；health 需要登录。

### 验收 10 —— 优雅停机
```powershell
# 触发一次大表同步，运行中用 CloseMainWindow（等价 SIGTERM / Ctrl+C）请求停机
& $E run -TaskName shutdown-test -SrcTable big_table -DstTable big_table -Mode FULL
$p = Get-Process -Id (Get-Content "$qa\server-e2e.pid")
$sw = [Diagnostics.Stopwatch]::StartNew(); $p.CloseMainWindow() | Out-Null
while((Get-Process -Id $p.Id -ErrorAction SilentlyContinue) -and $sw.Elapsed.TotalSeconds -lt 120){ Start-Sleep 1 }
"退出耗时 = $([math]::Round($sw.Elapsed.TotalSeconds,1))s"
Qa-Sql "SELECT id, status FROM sync_record ORDER BY id DESC LIMIT 3;" 3408 datasync_e2e
```
**期望**：进程在 `shutdown-timeout-seconds`（默认 60s）+ 余量内退出；
运行中的同步被标记 `CANCELLED`；**水位不推进**。
**失败判定**：超过窗口仍不退出；记录停在 RUNNING；水位被推进。
> 注：Windows 控制台 Java 进程没有窗口消息循环，`CloseMainWindow` 可能返回 false——
> 若如此，用 `taskkill /PID <pid>`（不带 `/F`）发送 `WM_CLOSE`，仍不行则记录为"Windows 上无法验证 SIGTERM"，
> 并改用应用自带的 `/actuator/shutdown`（若启用）或在 Linux 容器里验证，结论要如实标注平台限制。

### 验收 11 —— 前端构建 + 类型检查 + 全链路
```powershell
cd data-sync-web
npm run build *> "$qa\logs\web-build.log"
npx vue-tsc --noEmit *> "$qa\logs\web-tsc.log"
```
**期望**：两条命令都 exit 0，`vue-tsc` 零错误；页面能完成"建数据源 → 预检 → 建任务 → 执行 → 看进度/错误"全链路。
**失败判定**：`vue-tsc` 有 error；构建失败；任一页面步骤走不通（截图/HTTP 证据）。

### 验收 12 —— 单元/集成测试全绿
```powershell
& "S:\installationFree\apache-maven-3.9.15\bin\mvn.cmd" -B -s "...settings.xml" test *> "$qa\logs\mvn-test.log"
Select-String -Path "$qa\logs\mvn-test.log" -Pattern "Tests run:|BUILD SUCCESS|BUILD FAILURE"
```
**期望**：`BUILD SUCCESS`，`Failures: 0, Errors: 0`；core 测试覆盖预检、键集分页、水位推进、幂等 upsert、脱敏。
**失败判定**：任何失败/错误；关键路径无测试（要有测试类名清单为证）。

---

## 5. 额外"找茬"清单（契约 §5 之外，但生产上线必须过）

| # | 检查项 | 命令 / 方法 | 期望 |
|---|---|---|---|
| E1 | **无 OFFSET 分页** | `Select-String -Path data-sync-core\**\*.java -Pattern 'OFFSET'` | 0 命中（`LIMIT ? OFFSET ?` 在并发写入下必然重复/漏行） |
| E2 | **保留字与中文标识符** | `& $E run -TaskName rsrv -SrcTable reserved_table -DstTable reserved_table -Mode FULL`；中文表 `中文表_订单` 同理 | 同步成功、逐行一致（标识符必须经 quote） |
| E3 | **复合主键** | `cpk_table`（3 列主键）3000 行 | 键集分页用元组比较，不重复不漏行 |
| E4 | **仅唯一键无主键** | `uk_table` | 要么按唯一键 upsert 成功，要么预检明确报 `PK_MISSING`——但不允许"悄悄写坏" |
| E5 | **无主键表** | `nopk_table` | 预检 `PK_MISSING`；FULL+TRUNCATE 下若降级为纯 INSERT 必须给 WARN 且文档写明 |
| E6 | **`endCursor` 语义** | `SELECT cursor_value FROM sync_task`；对照 `sync_record.end_cursor` | 只能等于"本次成功提交的最后一行的增量列值"，不能是 `SELECT MAX(...)` |
| E7 | **DM8 不得声称已验证** | `README.md` 与 `GET /api/system/info` | 必须出现"未在真实达梦实例验证"字样，`dm8Verified=false` |
| E8 | **破坏性操作边界** | 增量任务的预检失败路径 | 增量/幂等模式下**绝不 TRUNCATE** |
| E9 | **标识符注入** | 任务名/表名传 `big_table; DROP TABLE x` 等 | 预检拒绝（`IDENTIFIER_INVALID`），不拼接进 SQL |
| E10 | **错误响应不泄漏** | 制造一次 SQL 异常 | 响应体无堆栈、无 SQL、无口令，只有错误码 + 中文消息 |
| E11 | **元数据库防护**（Lead 追加要求） | 把任务的**目标数据源指向 3408 上的元数据库 `datasync_e2e`**，调预检 | 返回 `TARGET_IS_METADATA`（ERROR）；执行被阻止；元数据库 `datasource` 表**行数未变化** |

### E11 具体测法（防止"一次误配抹掉全平台配置"的最后一道闸）

```powershell
# 用 e2e 驱动建一个"目标 = 元数据库"的数据源（指向 3408/datasync_e2e）
$metaDs = Req POST "/api/datasources" @{ name="qa-meta-db"; dbType="MYSQL"; host="127.0.0.1";
    port=3408; databaseName="datasync_e2e"; username="root"; password="qa_root_123" }
# 建一个目标表 = datasource 的任务，源随便指向一张同构表
$t = Req POST "/api/tasks" @{ name="e11-meta-target"; sourceDsId=$srcId; targetDsId=$metaDs.id;
    sourceTable="big_table"; targetTable="datasource"; syncMode="FULL" }
# 1) 预检必须报 TARGET_IS_METADATA
& $E preflight -TaskId $t.id
# 2) 硬证据：执行前后元数据库 datasource 表行数必须完全一致
$before = Qa-Scalar "SELECT COUNT(*) FROM datasource;" 3408 datasync_e2e
& $E trigger -TaskId $t.id; & $E poll -TaskId $t.id
$after  = Qa-Scalar "SELECT COUNT(*) FROM datasource;" 3408 datasync_e2e
"before=$before after=$after"     # 必须相等
```

**期望**：`hasError=true`、issues 里含 `code=TARGET_IS_METADATA`（ERROR 级）；任务被拒绝执行
（或被引擎在写之前拦截）；`datasource` 行数不变，`sync_task`/`sync_record` 仍在（没被 TRUNCATE 抹掉）。
**失败判定**：预检没报该码；任务"成功"跑完；元数据库任何表行数发生变化。
**顺带覆盖**：目标表名命中元数据表名前缀（`sync_*` / `datasource` / `QRTZ_*` / `flyway_schema_history`）
也必须被拒 —— 见 core `MetadataGuard.isMetadataTable` 的单测断言（含大小写与 `schema.table` 形式）。

---

## 6. 阶段 B 执行顺序（依赖最少阻塞）

1. 验收 1（构建/启动/Flyway）→ 不过就停，先报 Lead，后面全部无意义。
2. 验收 12（`mvn test`）→ 可以并行跑，不占服务端口。
3. 验收 8 / 9 / 10（安全与停机，静态 + 短用例）。
4. 验收 6 / 7（预检、坏行）→ 依赖独立表的行数快照，互不干扰。
5. 验收 2（10 万行）+ 2b（内存采样）→ 耗时最长，单独跑。
6. 验收 3 / 4 / 5（续传、幂等、并发）→ 都作用在同一批任务上，串行执行。
7. 验收 11（前端）→ 可与后端串行阶段并行，交给同一条命令。

**报告规则**：每条结论必须附"命令 + 原始输出片段"；失败项写**最小复现步骤**并直接发 `send_message`
给对应负责人（core → `engine`，server → `platform`，前端 → `ui`），同时抄送 `lead`。
**绝不修改产品代码**——QA 只负责证明问题存在。
