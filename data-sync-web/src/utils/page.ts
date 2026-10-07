import type { PageRes } from '@/types'

export interface NormalizedPage<T> extends PageRes<T> {
  /** true = 后端返回的是数组，分页在前端完成 */
  clientPaged: boolean
  /** 未分页时的完整数据集（仅 clientPaged 时有意义） */
  all?: T[]
}

/** 统一分页响应包装 */
export function normalizePage<T>(raw: unknown, page = 0, size = 10): NormalizedPage<T> {
  // 形态一：裸数组（既有后端 GET /tasks/{id}/records 就是这样）
  if (Array.isArray(raw)) {
    const all = raw as T[]
    const start = page * size
    return {
      content: all.slice(start, start + size),
      totalElements: all.length,
      totalPages: Math.max(1, Math.ceil(all.length / size)),
      size,
      number: page,
      clientPaged: true,
      all,
    }
  }

  // 形态二：Spring Data Page
  if (raw && typeof raw === 'object') {
    const o = raw as Record<string, unknown>
    const content = Array.isArray(o.content) ? (o.content as T[]) : []
    const totalElements =
      typeof o.totalElements === 'number' ? o.totalElements : content.length
    const sizeVal = typeof o.size === 'number' && o.size > 0 ? o.size : size
    const number = typeof o.number === 'number' ? o.number : page
    return {
      content,
      totalElements,
      totalPages:
        typeof o.totalPages === 'number'
          ? o.totalPages
          : Math.max(1, Math.ceil(totalElements / sizeVal)),
      size: sizeVal,
      number,
      clientPaged: false,
    }
  }

  return { content: [], totalElements: 0, totalPages: 0, size, number: page, clientPaged: false }
}
