-- DataSync QA 夹具：目标库(3408) 结构
-- 与 01-schema-src.sql 大体一致，但**故意**保留三处差异，用于验收项 6/7：
--   1) bad_row_dst.val 为 VARCHAR(32)  <-- 源为 VARCHAR(200)，运行期写入必然 Data too long
--   2) 缺 `type_clash_dst.amount` 的数值类型，改为 VARCHAR(10) <-- 预检应报 TYPE_INCOMPATIBLE
--   3) `noindex_incr_dst` 增量列无索引（WARN 场景）

-- ============ 1. 主表（与源同构） ============
DROP TABLE IF EXISTS `big_table`;
CREATE TABLE `big_table` (
  `id`         BIGINT       NOT NULL,
  `biz_id`     BIGINT       NULL,
  `amount`     DECIMAL(38,10) NULL,
  `ratio`      DOUBLE       NULL,
  `flag`       BIT(1)       NULL,
  `tiny_flag`  TINYINT(1)   NULL,
  `name_cn`    VARCHAR(200) NULL,
  `remark`     TEXT         NULL,
  `payload`    BLOB         NULL,
  `doc`        JSON         NULL,
  `updated_at` DATETIME(6)  NOT NULL,
  `d`          DATE         NULL,
  `t`          TIME(6)      NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 2. 复合主键（同构） ============
DROP TABLE IF EXISTS `cpk_table`;
CREATE TABLE `cpk_table` (
  `tenant_id`  INT          NOT NULL,
  `biz_no`     VARCHAR(64)  NOT NULL,
  `seq`        INT          NOT NULL,
  `val`        VARCHAR(200) NULL,
  `qty`        DECIMAL(18,4) NULL,
  `updated_at` DATETIME(6)  NOT NULL,
  PRIMARY KEY (`tenant_id`, `biz_no`, `seq`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 3. 无主键（同构） ============
DROP TABLE IF EXISTS `nopk_table`;
CREATE TABLE `nopk_table` (
  `a` INT          NULL,
  `b` VARCHAR(64)  NULL,
  `c` DATETIME(6)  NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 4. 仅唯一键（同构） ============
DROP TABLE IF EXISTS `uk_table`;
CREATE TABLE `uk_table` (
  `code`       VARCHAR(64)  NOT NULL,
  `val`        VARCHAR(200) NULL,
  `updated_at` DATETIME(6)  NULL,
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 5. 保留字列名（同构） ============
DROP TABLE IF EXISTS `reserved_table`;
CREATE TABLE `reserved_table` (
  `id`      BIGINT       NOT NULL,
  `order`   VARCHAR(64)  NULL,
  `desc`    VARCHAR(200) NULL,
  `group`   INT          NULL,
  `key`     VARCHAR(64)  NULL,
  `select`  VARCHAR(64)  NULL,
  `from`    DATETIME(6)  NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 6. 中文表名 + 中文列名（同构） ============
DROP TABLE IF EXISTS `中文表_订单`;
CREATE TABLE `中文表_订单` (
  `主键`     BIGINT       NOT NULL,
  `订单号`   VARCHAR(64)  NULL,
  `金额`     DECIMAL(18,2) NULL,
  `客户名称` VARCHAR(200) NULL,
  `创建时间` DATETIME(6)  NULL,
  `备注`     TEXT         NULL,
  PRIMARY KEY (`主键`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 7. 坏行目标：val 故意窄 ============
DROP TABLE IF EXISTS `bad_row_dst`;
CREATE TABLE `bad_row_dst` (
  `id`         BIGINT      NOT NULL,
  `val`        VARCHAR(32) NULL,      -- <-- 源为 VARCHAR(200)
  `zero_dt`    DATETIME    NULL,
  `updated_at` DATETIME(6) NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 8. 类型不兼容目标（**故意不给主键**，否则 upsert 语义会掩盖预检的类型错误） ============
DROP TABLE IF EXISTS `type_clash_dst`;
CREATE TABLE `type_clash_dst` (
  `id`         BIGINT      NOT NULL,
  `amount`     VARCHAR(10) NULL,      -- <-- 源为 DECIMAL(38,10)
  `updated_at` DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 9. 水位表（同构） ============
DROP TABLE IF EXISTS `watermark_dst`;
CREATE TABLE `watermark_dst` (
  `id`         BIGINT       NOT NULL,
  `val`        VARCHAR(100) NULL,
  `updated_at` DATETIME(6)  NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 10. 无索引增量列表 ============
DROP TABLE IF EXISTS `noindex_incr_dst`;
CREATE TABLE `noindex_incr_dst` (
  `id`         BIGINT       NOT NULL,
  `updated_at` DATETIME(6)  NOT NULL,
  `val`        VARCHAR(50)  NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 11. 强制 TYPE_INCOMPATIBLE（源 日期/大文本/二进制 → 目标 数值） ============
-- 有主键，避免 PK_MISSING WARN 干扰判决；只看 TYPE_INCOMPATIBLE
DROP TABLE IF EXISTS `type_hard_dst`;
CREATE TABLE `type_hard_dst` (
  `id`      BIGINT  NOT NULL,
  `dt_col`  BIGINT  NULL,      -- <-- 源 DATETIME(6)：日期时间→数值 = INCOMPATIBLE
  `txt_col` INT     NULL,      -- <-- 源 TEXT：        大文本→数值   = INCOMPATIBLE
  `bin_col` BIGINT  NULL,      -- <-- 源 BLOB：        二进制→数值   = INCOMPATIBLE
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
