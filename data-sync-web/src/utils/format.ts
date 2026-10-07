/**
 * 通用格式化工具（纯函数，无副作用）
 */

/** ISO / 时间戳 → "YYYY-MM-DD HH:mm:ss"；无法解析时原样返回 */
export function formatTime(value?: string | number | null): string {
  if (value === null || value === undefined || value === '') return ''
  const d = new Date(value)
  if (Number.isNaN(d.getTime())) return String(value)
  const pad = (n: number) => String(n).padStart(2, '0')
  return (
    d.getFullYear() +
    '-' +
    pad(d.getMonth() + 1) +
    '-' +
    pad(d.getDate()) +
    ' ' +
    pad(d.getHours()) +
    ':' +
    pad(d.getMinutes()) +
    ':' +
    pad(d.getSeconds())
  )
}

/** 毫秒 → 人类可读耗时；空值返回 "—" */
export function formatMillis(ms?: number | null): string {
  if (ms === null || ms === undefined || Number.isNaN(Number(ms))) return '—'
  const v = Number(ms)
  if (v < 0) return '—'
  if (v < 1000) return v + ' ms'
  const sec = v / 1000
  if (sec < 60) return sec.toFixed(sec < 10 ? 2 : 1) + ' s'
  const min = Math.floor(sec / 60)
  const rest = Math.round(sec % 60)
  if (min < 60) return min + ' min ' + rest + ' s'
  const hour = Math.floor(min / 60)
  return hour + ' h ' + (min % 60) + ' min'
}

/** 大数字千分位；空值返回 "0" */
export function formatCount(n?: number | null): string {
  if (n === null || n === undefined || Number.isNaN(Number(n))) return '0'
  return Number(n).toLocaleString('zh-CN')
}

/** 两个时间点之间的耗时（毫秒字符串）；任一缺失返回 '' */
export function durationBetween(start?: string | null, end?: string | null): string {
  if (!start || !end) return ''
  const s = new Date(start).getTime()
  const e = new Date(end).getTime()
  if (Number.isNaN(s) || Number.isNaN(e)) return ''
  return formatMillis(e - s)
}

/**
 * 复制文本到剪贴板（带降级方案）
 * 返回是否成功；不抛异常
 */
export async function copyText(text: string): Promise<boolean> {
  const value = text ?? ''
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(value)
      return true
    }
  } catch {
    /* 继续走降级方案 */
  }
  try {
    const ta = document.createElement('textarea')
    ta.value = value
    ta.setAttribute('readonly', 'readonly')
    ta.style.position = 'fixed'
    ta.style.top = '-1000px'
    ta.style.opacity = '0'
    document.body.appendChild(ta)
    ta.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(ta)
    return ok
  } catch {
    return false
  }
}

/** 空值占位 */
export function orDash(value?: string | number | null): string {
  if (value === null || value === undefined || value === '') return '—'
  return String(value)
}

/**
 * 把任意值安全地序列化为可读文本（用于错误详情展示）。
 * 字符串原样返回；对象美化 JSON；循环引用不抛异常。
 */
export function toDisplayText(value: unknown): string {
  if (value === null || value === undefined) return ''
  if (typeof value === 'string') return value
  if (typeof value === 'number' || typeof value === 'boolean') return String(value)
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}
