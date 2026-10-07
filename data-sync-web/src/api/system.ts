import api, { rootClient } from './request'
import { adoptTokenFromBody } from '@/utils/csrf'
import type { SystemInfoVO } from '@/types'

/** 系统信息（契约 §4.3）。该接口在 MPA 模式下还可能夹带 CSRF 令牌，故顺带采纳。 */
export const systemApi = {
  info: async (silent = true): Promise<SystemInfoVO> => {
    const res = await api.get<SystemInfoVO>('/system/info', { silent })
    adoptTokenFromBody(res)
    return res || {}
  },
  /** /actuator/health 匿名可读，注意它不在 /api 前缀下 */
  health: async (): Promise<Record<string, unknown> | null> => {
    try {
      const res = await rootClient.get<Record<string, unknown>>('/actuator/health', {
        silent: true,
        skipAuthRedirect: true,
        validateStatus: (s) => s >= 200 && s < 300,
      })
      return res.data || null
    } catch {
      return null
    }
  },
}

/** 把 poolSummary 渲染成可读文本（结构未冻结，做成容错展示） */
export function describePoolSummary(summary: unknown): string {
  if (summary === null || summary === undefined) return ''
  if (typeof summary === 'number') return String(summary)
  if (typeof summary === 'string') return summary
  if (Array.isArray(summary)) {
    return summary
      .map((it) => {
        if (it && typeof it === 'object') {
          const o = it as Record<string, unknown>
          const name = o.name ?? o.dsName ?? o.datasourceId ?? o.id ?? ''
          const active = o.active ?? o.activeConnections ?? o.inUse ?? ''
          const idle = o.idle ?? o.idleConnections ?? ''
          const max = o.max ?? o.maxSize ?? o.maximumPoolSize ?? ''
          const parts = [String(name)].filter(Boolean)
          if (active !== '') parts.push('活动 ' + String(active))
          if (idle !== '') parts.push('空闲 ' + String(idle))
          if (max !== '') parts.push('上限 ' + String(max))
          return parts.join(' / ')
        }
        return String(it)
      })
      .join('；')
  }
  if (typeof summary === 'object') {
    const o = summary as Record<string, unknown>
    const keys = Object.keys(o)
    if (keys.length === 0) return ''
    return keys
      .slice(0, 12)
      .map((k) => k + ': ' + String(o[k]))
      .join('；')
  }
  return String(summary)
}
