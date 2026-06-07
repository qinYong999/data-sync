import api from "./request"
import type { TaskVO, TaskForm, RecordVO, PageRes, TriggerRes, ColumnInfo } from "@/types"

export const taskApi = {
  list: (params?: any) => api.get<PageRes<TaskVO>>("/tasks", { params }),
  get: (id: number) => api.get<TaskVO>("/tasks/" + id),
  create: (data: TaskForm) => api.post<TaskVO>("/tasks", data),
  update: (id: number, data: Partial<TaskForm>) => api.put<TaskVO>("/tasks/" + id, data),
  delete: (id: number) => api.delete<void>("/tasks/" + id),
  enable: (id: number) => api.post<void>("/tasks/" + id + "/enable"),
  disable: (id: number) => api.post<void>("/tasks/" + id + "/disable"),
  trigger: (taskId: number) => api.post<TriggerRes>("/tasks/" + taskId + "/trigger"),
  records: (taskId: number) => api.get<RecordVO[]>("/tasks/" + taskId + "/records"),
  getColumns: (taskId: number) =>
    api.get<{ sourceColumns: ColumnInfo[]; targetColumns: ColumnInfo[] }>(
      "/tasks/" + taskId + "/columns",
    ),
}
