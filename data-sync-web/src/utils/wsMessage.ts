/**
 * 实时日志消息解析。
 *
 * 后端可能按 run/task 打标签（§任务要求 7），但具体格式未冻结，因此这里做**宽松解析**：
 *  - JSON 体：{taskId, recordId, runKey, level, timestamp, message}
 *  - 纯文本前缀：[task:12] / [run:abc] / 任务#12
 * 解析不出来的消息不做过滤（宁可多显示，不可漏显示）。
 */

export interface LogMessage {
  /** 原始文本，始终保留用于展示 */
  raw: string
  /** 解析出的任务 ID，undefined = 无法判定 */
  taskId?: number
  /** 解析出的执行记录 ID */
  recordId?: number
  /** 解析出的 run 标识（sync_record.run_key） */
  runKey?: string
  level?: string
  timestamp?: string
  /** 去掉标签后的正文（解析失败时等于 raw） */
  text: string
}

function toNumber(v: unknown): number | undefined {
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string' && /^\d+$/.test(v.trim())) return Number(v.trim())
  return undefined
}

function pickString(o: Record<string, unknown>, keys: string[]): string | undefined {
  for (const k of keys) {
    const v = o[k]
    if (typeof v === 'string' && v) return v
  }
  return undefined
}

export function parseLogMessage(raw: string): LogMessage {
  const text = raw ?? ''
  const msg: LogMessage = { raw: text, text }

  const trimmed = text.trim()
  if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
    try {
      const o = JSON.parse(trimmed) as Record<string, unknown>
      if (o && typeof o === 'object') {
        msg.taskId = toNumber(o.taskId ?? o.task_id ?? o.taskID ?? o.task)
        msg.recordId = toNumber(o.recordId ?? o.record_id ?? o.recordID ?? o.runId)
        msg.runKey = pickString(o, ['runKey', 'run_key', 'run'])
        msg.level = pickString(o, ['level', 'logLevel', 'severity'])
        msg.timestamp = pickString(o, ['timestamp', 'time', 'ts', 'createdAt'])
        const body = pickString(o, ['message', 'msg', 'text', 'log', 'content'])
        if (body) msg.text = body
        if (msg.taskId !== undefined || msg.recordId !== undefined || msg.runKey) return msg
      }
    } catch {
      /* 不是合法 JSON，继续按文本解析 */
    }
  }

  const taskMatch = trimmed.match(/\[(?:task|任务)\s*[:=#]?\s*(\d+)\]/i)
  if (taskMatch) msg.taskId = Number(taskMatch[1])

  if (msg.taskId === undefined) {
    const cnMatch = trimmed.match(/任务\s*#?\s*(\d+)/)
    if (cnMatch) msg.taskId = Number(cnMatch[1])
  }

  const recMatch = trimmed.match(/\[(?:record|记录|run)\s*[:=#]\s*(\d+)\]/i)
  if (recMatch) msg.recordId = Number(recMatch[1])

  const runKeyMatch = trimmed.match(/\[(?:runkey|run_key)\s*[:=#]\s*([\w.-]+)\]/i)
  if (runKeyMatch) msg.runKey = runKeyMatch[1]

  return msg
}

/** 判断该消息是否属于当前任务（无法判定的消息一律保留） */
export function belongsToTask(msg: LogMessage, taskId: number | null | undefined): boolean {
  if (taskId === null || taskId === undefined || Number.isNaN(taskId)) return true
  if (msg.taskId === undefined) return true
  return msg.taskId === Number(taskId)
}
