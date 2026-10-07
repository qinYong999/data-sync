package com.datasync.core.preflight;

/**
 * 稳定错误码（契约 §3.6 的 11 个基础码 + 5 个扩展码）。
 *
 * <p>前端按 code 出修复建议，因此<b>已发布的码值永不复用、永不改语义</b>；
 * 新增能力一律新增码值，前端对未知码走兜底展示。
 */
public final class ErrorCodes {

    private ErrorCodes() {
    }

    // ---------- 契约 §3.6 基础码 ----------

    /** 源表不存在。 */
    public static final String SRC_TABLE_MISSING = "SRC_TABLE_MISSING";
    /** 目标表不存在。 */
    public static final String DST_TABLE_MISSING = "DST_TABLE_MISSING";
    /** 目标表无可用列 / 字段映射为空。 */
    public static final String NO_TARGET_COLUMNS = "NO_TARGET_COLUMNS";
    /** 字段映射引用了源或目标不存在的列。 */
    public static final String MAPPING_COLUMN_MISSING = "MAPPING_COLUMN_MISSING";
    /** 源列类型无法安全转换到目标列类型。 */
    public static final String TYPE_INCOMPATIBLE = "TYPE_INCOMPATIBLE";
    /** 目标表无主键/唯一键，无法保证幂等 upsert。 */
    public static final String PK_MISSING = "PK_MISSING";
    /** 配置的增量字段在源表不存在。 */
    public static final String INCR_COLUMN_MISSING = "INCR_COLUMN_MISSING";
    /** 增量字段类型不可用于有序水位（非数值/时间）。 */
    public static final String INCR_COLUMN_NOT_SORTABLE = "INCR_COLUMN_NOT_SORTABLE";
    /** 增量字段无索引（WARN）。 */
    public static final String INCR_COLUMN_NO_INDEX = "INCR_COLUMN_NO_INDEX";
    /** 自定义 SQL 非法（非 SELECT / 危险关键字 / 语法错误）。 */
    public static final String CUSTOM_SQL_INVALID = "CUSTOM_SQL_INVALID";
    /** 目标库缺少 TRUNCATE/CREATE/DROP 等所需权限。 */
    public static final String PERMISSION_DENIED = "PERMISSION_DENIED";

    // ---------- 扩展码（Lead 已批准；前端按未知码兜底即可） ----------

    /** 表名/库名/列名含非法字符（防 SQL 注入）。 */
    public static final String IDENTIFIER_INVALID = "IDENTIFIER_INVALID";
    /** 找不到任何可用的键集分页排序键（禁止退化为 OFFSET 分页）。 */
    public static final String SORT_KEY_MISSING = "SORT_KEY_MISSING";
    /** 排序键无法唯一确定一行，且没有任何主键/唯一键可做 tie-breaker（Lead 决策 1）。 */
    public static final String ORDER_KEY_NOT_UNIQUE = "ORDER_KEY_NOT_UNIQUE";
    /** 目标库就是平台自身的元数据库（Lead 决策 3）。 */
    public static final String TARGET_IS_METADATA = "TARGET_IS_METADATA";
    /** 该数据库/策略组合不受支持（如 DM8 不支持 SWAP 暂存表）。 */
    public static final String STRATEGY_UNSUPPORTED = "STRATEGY_UNSUPPORTED";
    /** 预检本身无法完成（源/目标库连不上、元数据读不到）。 */
    public static final String PREFLIGHT_FAILED = "PREFLIGHT_FAILED";
    /** 配置项本身不自洽（如 batchSize &gt; pageSize），多为 WARN。 */
    public static final String CONFIG_INVALID = "CONFIG_INVALID";
}
