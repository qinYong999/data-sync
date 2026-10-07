/** Spring Data Page 响应格式 */
export interface PageRes<T> {
  content: T[]
  totalElements: number
  totalPages?: number
  size?: number
  number?: number
  sort?: { sorted: boolean; unsorted: boolean; empty: boolean }
  first?: boolean
  last?: boolean
  empty?: boolean
}

/** 统一错误响应体（后端 GlobalExceptionHandler 保证） */
export interface ApiErrorBody {
  timestamp?: string
  status?: number
  /** 稳定错误码，例如 INCR_COLUMN_MISSING */
  code?: string
  /** 中文消息 */
  message?: string
  /** 兼容旧版 {error: "..."} 响应体 */
  error?: string
  details?: string[]
}

/** 数据源视图（返回给前端；后端必须脱敏，password 一律为空/掩码） */
export interface DataSourceVO {
  id: number
  name: string
  dbType: string
  host: string
  port: number
  databaseName: string
  username: string
  createdAt?: string
  updatedAt?: string
  /** 后端脱敏字段：可能为 null、空串或掩码；前端**绝不**回填到表单 */
  password?: string | null
  /** 可选：后端如提供"是否已配置口令"，用于展示提示 */
  passwordConfigured?: boolean
  [key: string]: unknown
}

/** 数据源表单（提交到后端） */
export interface DataSourceForm {
  name: string
  dbType: string
  host: string
  port: number
  databaseName: string
  username: string
  /** 编辑态留空表示不修改；留空时提交前会删除该字段 */
  password: string
}

/** 字段映射 */
export interface FieldMapping {
  sourceColumn: string
  targetColumn: string
  defaultValue?: string
  primaryKey?: boolean
}

/** 同步任务视图（返回给前端） */
export interface TaskVO {
  id: number
  name: string
  sourceDsId: number
  targetDsId: number
  sourceTable: string
  targetTable: string
  syncMode: string
  incrColumn?: string
  incrValue?: string
  /** 新契约字段（§4.2）：新逻辑读写 cursor_value */
  cursorValue?: string
  orderColumn?: string
  safetyLagSeconds?: number
  lookbackSeconds?: number
  fullSyncStrategy?: string
  errorPolicyJson?: string
  enabled?: boolean
  cronExpression?: string
  pageSize: number
  batchSize: number
  mappingJson?: string
  sourceMode?: string
  sourceSql?: string
  /** 启用状态：ENABLED / DISABLED */
  status: string
  /** 可选：后端如下发运行态，用于"取消/执行"按钮切换 */
  running?: boolean
  runStatus?: string
  createdAt?: string
  updatedAt?: string
  [key: string]: unknown
}

/** 同步任务表单（提交到后端） */
export interface TaskForm {
  name: string
  sourceDsId: number | null
  targetDsId: number | null
  sourceTable: string
  targetTable: string
  syncMode: string
  incrColumn: string
  /** 增量起始值（兼容字段；新逻辑对应 cursor_value） */
  incrValue: string
  /** §4.2 新增：新逻辑读写的游标列 */
  cursorValue?: string
  orderColumn?: string
  safetyLagSeconds?: number
  lookbackSeconds?: number
  fullSyncStrategy?: string
  errorPolicyJson?: string
  cronExpression: string
  sourceMode: string
  sourceSql: string
  pageSize: number
  batchSize: number
  fieldMappings?: FieldMapping[]
}

/** 同步执行记录 */
export interface RecordVO {
  id: number
  taskId: number
  startTime: string
  endTime?: string
  status: string
  totalRows: number
  readRows: number
  writeRows: number
  errorRows: number
  errorMessage?: string
  triggerType: string
  /** §4.2 新增统计字段 */
  skippedRows?: number
  startCursor?: string | null
  endCursor?: string | null
  readMillis?: number
  writeMillis?: number
  totalMillis?: number
  runKey?: string
  preflightJson?: string
  [key: string]: unknown
}

/** 坏行明细（sync_error） */
export interface SyncErrorVO {
  id?: number
  recordId?: number
  taskId?: number
  /** PREFLIGHT / READ / MAP / WRITE */
  phase?: string
  rowKey?: string
  message?: string
  rowData?: string
  retryable?: boolean
  createdAt?: string
  [key: string]: unknown
}

/** 预检问题（core PreflightIssue，§3.6 稳定错误码） */
export interface PreflightIssue {
  level: 'ERROR' | 'WARN' | string
  code: string
  message: string
  hint?: string
}

/** 预检响应 */
export interface PreflightRes {
  hasError: boolean
  issues: PreflightIssue[]
}

/** 取消任务响应（尽量兼容 204/空体/{success,message}） */
export interface CancelRes {
  success?: boolean
  message?: string
  status?: string
}

/** 仪表盘概览统计 */
export interface DashboardVO {
  totalTasks: number
  runningTasks: number
  failedTasks: number
  successTasks: number
  totalRecords: number
  totalReadRows: number
}

/** 数据库列信息 */
export interface ColumnInfo {
  name: string
  type: string
  nullable: boolean
  primaryKey: boolean
  [key: string]: unknown
}

/** 手动触发响应 */
export interface TriggerRes {
  success: boolean
  message: string
}

/** 当前登录用户 */
export interface AuthUser {
  authenticated: boolean
  username?: string
}

/** GET /api/system/info 响应（§4.3 + 平台定稿字段） */
export interface SystemInfoVO {
  version?: string
  dbType?: string
  /** true 仅当在真实达梦实例验证过；DM8 当前恒为 false */
  dm8Verified?: boolean
  activeTasks?: number
  runningTasks?: number
  totalTasks?: number
  totalRecords?: number
  /** 数组：只有被实际使用过（已缓存连接池）的数据源会出现 */
  poolSummary?: PoolSummaryItem[] | unknown
  /** 服务端下发的 CSRF 令牌（可能为 null，securityEnabled=false 时） */
  csrfToken?: string | null
  securityEnabled?: boolean
  startTime?: string
  javaVersion?: string
  [key: string]: unknown
}

/** 连接池摘要项 */
export interface PoolSummaryItem {
  id?: number
  name?: string
  dbType?: string
  host?: string
  port?: number
  databaseName?: string
  maximumPoolSize?: number
  activeConnections?: number
  idleConnections?: number
  totalConnections?: number
  threadsAwaitingConnection?: number
  cached?: boolean
  [key: string]: unknown
}

/** GET /api/auth/session 响应（匿名可访问） */
export interface SessionVO {
  authenticated?: boolean
  username?: string | null
  csrfToken?: string | null
  securityEnabled?: boolean
  [key: string]: unknown
}

/** POST /api/auth/login 响应 */
export interface LoginVO {
  success?: boolean
  username?: string
  csrfToken?: string | null
  message?: string
  code?: string
  [key: string]: unknown
}

/** 连接测试详情响应（平台新增 /test-detail） */
export interface TestDetailVO {
  success?: boolean
  message?: string
  [key: string]: unknown
}

/**
 * 状态映射（平台定稿：sync_record.status ∈ RUNNING / COMPLETED / FAILED / CANCELLED）
 * 同时防御性兼容历史脏数据：SUCCESS→成功、STARTED→运行中、STOPPED→已终止。
 * 未知取值原样灰显，不崩。
 */
export const STATUS_MAP: Record<string, { label: string; type: 'success' | 'danger' | 'warning' | 'info' | '' }> = {
  // 任务启用开关
  ENABLED: { label: '已启用', type: 'success' },
  DISABLED: { label: '已禁用', type: 'info' },
  // 执行记录主字典
  RUNNING: { label: '运行中', type: 'warning' },
  COMPLETED: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  CANCELLED: { label: '已取消', type: 'info' },
  // 历史 / 兼容取值
  SUCCESS: { label: '成功', type: 'success' },
  STARTED: { label: '运行中', type: 'warning' },
  CANCELED: { label: '已取消', type: 'info' },
  STOPPED: { label: '已终止', type: 'info' },
  PENDING: { label: '等待中', type: 'info' },
}

/** 视为"运行中"的状态集合（含历史脏数据 STARTED） */
export const RUNNING_STATUSES = ['RUNNING', 'STARTED', 'PENDING']

/** 视为"成功完成"的状态集合 */
export const SUCCESS_STATUSES = ['COMPLETED', 'SUCCESS']

/** 是否为运行中状态 */
export function isRunningStatus(status?: string | null): boolean {
  if (!status) return false
  return RUNNING_STATUSES.includes(String(status).toUpperCase())
}

/** 是否为成功状态 */
export function isSuccessStatus(status?: string | null): boolean {
  if (!status) return false
  return SUCCESS_STATUSES.includes(String(status).toUpperCase())
}

export const SYNC_MODE_MAP: Record<string, string> = {
  FULL: '全量同步',
  INCR: '增量同步',
  FULL_INCR: '先全量后增量',
}

export const TRIGGER_MAP: Record<string, string> = {
  MANUAL: '手动',
  SCHEDULED: '调度',
}

/** 全量同步策略（D6） */
export const FULL_SYNC_STRATEGY_MAP: Record<string, string> = {
  TRUNCATE: 'TRUNCATE（清空目标表）',
  DELETE: 'DELETE（逐行删除）',
  SWAP: 'SWAP（暂存表 + RENAME）',
}

/** 坏行阶段中文名 */
export const ERROR_PHASE_MAP: Record<string, string> = {
  PREFLIGHT: '预检',
  READ: '读取',
  MAP: '映射',
  WRITE: '写入',
}
