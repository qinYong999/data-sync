-- DataSync QA 夹具：类型边缘 + 保留字 + 中文表名/列名 + 复合主键 + 无主键/仅唯一键
-- 源库(3407) 与 目标库(3408) 都执行本脚本，保证两侧结构一致
-- 由 QA 独占，仅用于独立验收

-- ============ 1. 主表：10 万行，覆盖极值类型 ============
DROP TABLE IF EXISTS `big_table`;
CREATE TABLE `big_table` (
  `id`         BIGINT       NOT NULL AUTO_INCREMENT,
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
  PRIMARY KEY (`id`),
  KEY `idx_updated_at` (`updated_at`, `id`),
  KEY `idx_biz` (`biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 2. 复合主键 ============
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

-- ============ 3. 无主键表（预检必须报 PK_MISSING 并拒绝写入） ============
DROP TABLE IF EXISTS `nopk_table`;
CREATE TABLE `nopk_table` (
  `a` INT          NULL,
  `b` VARCHAR(64)  NULL,
  `c` DATETIME(6)  NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 4. 仅唯一键（无 PK）—— 唯一键是否可作为 upsert 匹配键 ============
DROP TABLE IF EXISTS `uk_table`;
CREATE TABLE `uk_table` (
  `code`       VARCHAR(64)  NOT NULL,
  `val`        VARCHAR(200) NULL,
  `updated_at` DATETIME(6)  NULL,
  UNIQUE KEY `uk_code` (`code`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 5. 保留字列名（须正确加反引号） ============
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

-- ============ 6. 中文表名 + 中文列名 ============
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

-- ============ 7. 坏行数据集：目标列窄于源列，用于触发运行期写入失败 ============
-- 源：val 为 VARCHAR(200)；目标：val 为 VARCHAR(32)
-- 其中第 3 行 val 长度 120 > 32，在 STRICT_TRANS_TABLES 下必然报 Data too long
DROP TABLE IF EXISTS `bad_row_src`;
CREATE TABLE `bad_row_src` (
  `id`         BIGINT       NOT NULL,
  `val`        VARCHAR(200) NULL,
  `zero_dt`    DATETIME     NULL,
  `updated_at` DATETIME(6)  NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 8. 类型不兼容（预检用）：源 DECIMAL(38,10) -> 目标 VARCHAR(10) ============
DROP TABLE IF EXISTS `type_clash_src`;
CREATE TABLE `type_clash_src` (
  `id`         BIGINT         NOT NULL,
  `amount`     DECIMAL(38,10)  NULL,
  `updated_at` DATETIME(6)     NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 9. 水位推进验证用表（独立，避免与 big_table 相互干扰） ============
DROP TABLE IF EXISTS `watermark_src`;
CREATE TABLE `watermark_src` (
  `id`         BIGINT       NOT NULL,
  `val`        VARCHAR(100) NULL,
  `updated_at` DATETIME(6)  NOT NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 10. 无索引增量列（预检应 WARN: INCR_COLUMN_NO_INDEX） ============
DROP TABLE IF EXISTS `noindex_incr_src`;
CREATE TABLE `noindex_incr_src` (
  `id`         BIGINT       NOT NULL,
  `updated_at` DATETIME(6)  NOT NULL,
  `val`        VARCHAR(50)  NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ============ 11. 真正「不可能安全转换」的类型对（预检必须报 TYPE_INCOMPATIBLE ERROR） ============
-- 依据 core 的 TypeCompatibility（预检与运行时的唯一事实源）：
--   目标为数值列时，源 = 日期时间 / 大文本 / 二进制 → INCOMPATIBLE（ERROR）
--   ⚠ 反例（不要误当 ERROR）：DECIMAL(38,10) → VARCHAR(10) 属于 **RISKY（WARN）**：
--     在 TypeCompatibility 里 "目标为字符类型" 一律放行（数值→字符有明确定义），
--     超长会在运行期变成坏行。这正是验收 6c 的实测结论。
DROP TABLE IF EXISTS `type_hard_src`;
CREATE TABLE `type_hard_src` (
  `id`      BIGINT      NOT NULL,
  `dt_col`  DATETIME(6) NOT NULL,
  `txt_col` TEXT        NULL,
  `bin_col` BLOB        NULL,
  PRIMARY KEY (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `type_hard_src` VALUES (1, '2026-09-01 12:00:00.000001', '自由文本', X'DEADBEEF');
