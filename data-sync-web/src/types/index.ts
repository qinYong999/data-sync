/** Spring Boot Page 响应格式 */
export interface PageRes<T> {
  content: T[]
  totalElements: number
  totalPages: number
  size: number
  number: number
  sort?: { sorted: boolean; unsorted: boolean; empty: boolean }
  first?: boolean
  last?: boolean
  empty?: boolean
}

/** 数据源视图（返回给前端） */
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
}

/** 数据源表单（提交到后端） */
export interface DataSourceForm {
  name: string
  dbType: string
  host: string
  port: number
  databaseName: string
  username: string
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
  cronExpression?: string
  pageSize: number
  batchSize: number
  mappingJson?: string
  status: string
  createdAt?: string
  updatedAt?: string
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
  incrValue: string
  cronExpression: string
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
}

/** 手动触发响应 */
export interface TriggerRes {
  success: boolean
  message: string
}

/** 状态映射 */
export const STATUS_MAP: Record<string, { label: string; type: 'success' | 'danger' | 'warning' | 'info' | '' }> = {
  ENABLED: { label: '已启用', type: 'success' },
  DISABLED: { label: '已禁用', type: 'info' },
  COMPLETED: { label: '成功', type: 'success' },
  SUCCESS: { label: '成功', type: 'success' },
  FAILED: { label: '失败', type: 'danger' },
  RUNNING: { label: '运行中', type: 'warning' },
  STOPPED: { label: '已停止', type: 'info' },
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
