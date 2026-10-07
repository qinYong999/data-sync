import api from './request'
import { normalizePage, type NormalizedPage } from '@/utils/page'
import type { RecordVO, SyncErrorVO } from '@/types'

/**
 * 执行记录与坏行明细。
 *
 * 契约 §4.3 标注 `GET /api/tasks/{id}/records` 为分页、`GET /api/records/{id}/errors` 为分页，
 * 但既有实现返回的是裸数组。这里统一用 normalizePage 兼容两种形态：
 *  - 裸数组 → 前端分页
 *  - Spring Page → 服务端分页
 */
export const recordApi = {
  /** 拉取原始响应（数组或 Page），交由 normalizePage 规整 */
  listRaw: (taskId: number, page = 0, size = 10) =>
    api.get<RecordVO[] | Record<string, unknown>>('/tasks/' + taskId + '/records', {
      params: { page, size },
    }),

  list: async (taskId: number, page = 0, size = 10): Promise<NormalizedPage<RecordVO>> => {
    const raw = await recordApi.listRaw(taskId, page, size)
    return normalizePage<RecordVO>(raw, page, size)
  },

  get: (id: number) => api.get<RecordVO>('/records/' + id),

  /** 坏行明细分页（可传 phase 过滤：PREFLIGHT / READ / MAP / WRITE） */
  errorsRaw: (recordId: number, page = 0, size = 10, phase?: string) =>
    api.get<SyncErrorVO[] | Record<string, unknown>>('/records/' + recordId + '/errors', {
      params: phase ? { page, size, phase } : { page, size },
    }),

  errors: async (
    recordId: number,
    page = 0,
    size = 10,
    phase?: string,
  ): Promise<NormalizedPage<SyncErrorVO>> => {
    const raw = await recordApi.errorsRaw(recordId, page, size, phase)
    return normalizePage<SyncErrorVO>(raw, page, size)
  },
}
