import api from "./request"
import type { DataSourceVO, DataSourceForm, PageRes, ColumnInfo } from "@/types"

export const datasourceApi = {
  list: (params?: any) => api.get<PageRes<DataSourceVO>>("/datasources", { params }),
  get: (id: number) => api.get<DataSourceVO>("/datasources/" + id),
  create: (data: DataSourceForm) => api.post<DataSourceVO>("/datasources", data),
  update: (id: number, data: Partial<DataSourceForm>) =>
    api.put<DataSourceVO>("/datasources/" + id, data),
  delete: (id: number) => api.delete<void>("/datasources/" + id),
  test: (id: number) => api.post<boolean>("/datasources/" + id + "/test"),
  testDirect: (data: DataSourceForm) => api.post<boolean>("/datasources/test", data),
  getTables: (id: number) => api.get<string[]>("/datasources/" + id + "/tables"),
  getTableColumns: (id: number, table: string) =>
    api.get<ColumnInfo[]>("/datasources/" + id + "/columns?table=" + encodeURIComponent(table)),
}
