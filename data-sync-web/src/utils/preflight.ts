import type { PreflightIssue, PreflightRes } from '@/types'

/**
 * §3.6 稳定错误码 → 中文可执行修复建议。
 *
 * 契约规定后端 `hint` 字段本身就是"可执行的修复建议（中文）"，
 * 因此展示优先级为：后端 hint > 本表建议 > 通用兜底。
 * 本表的作用是：后端未给 hint（或版本较旧）时，前端仍能给出可操作指引。
 */
export const PREFLIGHT_SUGGESTIONS: Record<string, string> = {
  SRC_TABLE_MISSING:
    '检查源表名拼写与大小写；确认所选源数据源下的库/schema 正确；若源表刚建好，点"刷新表列表"重新拉取。',
  DST_TABLE_MISSING:
    '先在目标库创建同名目标表（可用源表 DDL 改主键），或改用"仅全量 + SWAP"策略让平台建临时表；确认目标数据源连接的库/schema 正确。',
  NO_TARGET_COLUMNS:
    '目标表没有可用列，或字段映射为空。请在"字段映射"面板点"获取列信息"，至少保留一条映射；也可清空映射改用同名自动映射。',
  MAPPING_COLUMN_MISSING:
    '字段映射引用了源或目标不存在的列。请重新获取列信息并删除失效的映射行，或在两端改成同名同义列。',
  TYPE_INCOMPATIBLE:
    '源列类型无法安全转换到目标列类型。请把目标列改成兼容类型（如 VARCHAR 加宽、DECIMAL 精度对齐、时间列统一为 DATETIME/TIMESTAMP），或为该字段配置默认值后不映射它。',
  PK_MISSING:
    '目标表缺少主键/唯一键，无法保证幂等 upsert。请为业务唯一列添加 PRIMARY KEY 或 UNIQUE KEY，并在字段映射里勾选对应的主键列。',
  INCR_COLUMN_MISSING:
    '配置的增量字段在源表不存在。请在"增量字段"下拉中重新选择（需为自增数值列或时间戳列）。',
  INCR_COLUMN_NOT_SORTABLE:
    '增量字段类型不能用于有序水位（需为数值型或时间/日期型）。请改用自增主键或 created_at/updated_at 之类的时间列。',
  INCR_COLUMN_NO_INDEX:
    '增量字段没有索引，增量拉取会全表扫描。建议在源表该列上建立索引（ALTER TABLE ... ADD INDEX）。',
  CUSTOM_SQL_INVALID:
    '自定义 SQL 非法：必须是以 SELECT 开头的单条查询，且不得包含 INSERT/UPDATE/DELETE/DROP/TRUNCATE 等危险关键字。请回到 SQL 编辑器修正后重试。',
  PERMISSION_DENIED:
    '目标库账号权限不足。请授予所需权限（TRUNCATE / CREATE / DROP / INSERT / UPDATE），或把全量策略改为 DELETE 以避开 TRUNCATE 权限。',
}

/** 兜底建议（未知错误码） */
export const PREFLIGHT_FALLBACK_SUGGESTION =
  '请根据上面的问题描述核对源表、目标表与字段映射配置；修改后重新预检。若问题反复出现，请把错误码与任务 ID 提供给运维排查。'

/** 取某条问题的可执行建议 */
export function suggestionFor(issue: PreflightIssue | null | undefined): string {
  if (!issue) return PREFLIGHT_FALLBACK_SUGGESTION
  const hint = (issue.hint || '').trim()
  if (hint) return hint
  const mapped = issue.code ? PREFLIGHT_SUGGESTIONS[issue.code] : ''
  return mapped || PREFLIGHT_FALLBACK_SUGGESTION
}

/** 错误码的简短中文名（用于标签展示） */
export const PREFLIGHT_CODE_LABELS: Record<string, string> = {
  SRC_TABLE_MISSING: '源表不存在',
  DST_TABLE_MISSING: '目标表不存在',
  NO_TARGET_COLUMNS: '目标无可用列',
  MAPPING_COLUMN_MISSING: '映射列缺失',
  TYPE_INCOMPATIBLE: '类型不兼容',
  PK_MISSING: '缺少主键/唯一键',
  INCR_COLUMN_MISSING: '增量字段不存在',
  INCR_COLUMN_NOT_SORTABLE: '增量字段不可排序',
  INCR_COLUMN_NO_INDEX: '增量字段无索引',
  CUSTOM_SQL_INVALID: '自定义 SQL 非法',
  PERMISSION_DENIED: '目标库权限不足',
}

export function codeLabel(code?: string): string {
  if (!code) return '未知问题'
  return PREFLIGHT_CODE_LABELS[code] || code
}

export function isErrorLevel(issue: PreflightIssue | null | undefined): boolean {
  return String(issue?.level || '').toUpperCase() === 'ERROR'
}

/** 判断预检结果是否含 ERROR（兼容后端只给 issues 不给 hasError 的情况） */
export function preflightHasError(res: PreflightRes | null | undefined): boolean {
  if (!res) return false
  if (typeof res.hasError === 'boolean') return res.hasError
  return (res.issues || []).some(isErrorLevel)
}

/** 规整后端可能的松散响应（issues 缺失 / level 小写 / 非数组） */
export function normalizePreflight(raw: unknown): PreflightRes {
  const obj = (raw || {}) as Record<string, unknown>
  const rawIssues = Array.isArray(obj.issues) ? (obj.issues as unknown[]) : []
  const issues: PreflightIssue[] = rawIssues.map((it) => {
    const r = (it || {}) as Record<string, unknown>
    return {
      level: String(r.level ?? 'ERROR').toUpperCase(),
      code: String(r.code ?? ''),
      message: String(r.message ?? ''),
      hint: r.hint === null || r.hint === undefined ? '' : String(r.hint),
    }
  })
  const hasError =
    typeof obj.hasError === 'boolean' ? obj.hasError : issues.some((i) => i.level === 'ERROR')
  return { hasError, issues }
}

/** 一条错误的中文摘要，用于"禁止执行"的提示语 */
export function blockReason(res: PreflightRes | null | undefined): string {
  const errors = ((res?.issues || []) as PreflightIssue[]).filter(isErrorLevel)
  if (errors.length === 0) return ''
  const first = errors[0]
  return `预检未通过（${errors.length} 项错误）：${codeLabel(first.code)} — ${first.message || ''}`.trim()
}
