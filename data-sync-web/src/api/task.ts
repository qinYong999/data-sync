import api from "./request"
import type { TaskVO, TaskForm, RecordVO, PageRes, TriggerRes, ColumnInfo, PreflightRes, CancelRes } from "@/types"
import { normalizePreflight } from "@/utils/preflight"

export const taskApi = {
  list: (params?: unknown) => api.get<PageRes<TaskVO>>("/tasks", { params }),
  get: (id: number) => api.get<TaskVO>("/tasks/" + id),
  create: (data: TaskForm) => api.post<TaskVO>("/tasks", data),
  update: (id: number, data: Partial<TaskForm>) => api.put<TaskVO>("/tasks/" + id, data),
  delete: (id: number) => api.delete<void>("/tasks/" + id),
  enable: (id: number) => api.post<void>("/tasks/" + id + "/enable"),
  disable: (id: number) => api.post<void>("/tasks/" + id + "/disable"),

  trigger: async (taskId: number): Promise<TriggerRes> => {
    const raw = await api.post<TriggerRes | { success?: boolean; message?: string } | null>(
      "/tasks/" + taskId + "/trigger",
      undefined,
      { silent: true },
    )
    if (raw && typeof raw === "object") {
      return {
        success: raw.success !== false,
        message: String(raw.message || (raw.success === false ? "触发失败" : "任务已触发执行")),
      }
    }
    return { success: true, message: "任务已触发执行" }
  },

  /** 取消运行中的任务（§4.3 新增端点） */
  cancel: async (taskId: number): Promise<TriggerRes> => {
    const raw = await api.post<CancelRes | null>("/tasks/" + taskId + "/cancel", undefined, {
      silent: true,
    })
    if (raw && typeof raw === "object") {
      const success = raw.success !== false
      return {
        success,
        message: String(raw.message || (success ? "已请求取消任务" : "取消失败")),
      }
    }
    return { success: true, message: "已请求取消任务" }
  },

  /** 预检（§4.3 新增端点）。返回规整后的 { hasError, issues } */
  preflight: async (taskId: number, silent = true): Promise<PreflightRes> => {
    const raw = await api.get<unknown>("/tasks/" + taskId + "/preflight", { silent })
    return normalizePreflight(raw)
  },

  /** 原始执行记录响应（数组或 Page，交给 normalizePage 规整） */
  recordsRaw: (taskId: number, page = 0, size = 10) =>
    api.get<RecordVO[] | Record<string, unknown>>("/tasks/" + taskId + "/records", {
      params: { page, size },
    }),

  /** 兼容旧调用：一次性取回全部记录 */
  records: async (taskId: number): Promise<RecordVO[]> => {
    const raw = await taskApi.recordsRaw(taskId, 0, 1000)
    if (Array.isArray(raw)) return raw as RecordVO[]
    const content = (raw as Record<string, unknown>)?.content
    return Array.isArray(content) ? (content as RecordVO[]) : []
  },

  getColumns: (taskId: number) =>
    api.get<{ sourceColumns: ColumnInfo[]; targetColumns: ColumnInfo[] }>(
      "/tasks/" + taskId + "/columns",
    ),
}
