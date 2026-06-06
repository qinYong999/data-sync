import api from "./request"
export interface DataSource { id: number; name: string; dbType: string; host: string; port: number; databaseName: string; username: string; password?: string }
export const datasourceApi = {
  list: (params?: any) => api.get<any, any>("/datasources", { params }),
  get: (id: number) => api.get<any, DataSource>("/datasources/" + id),
  create: (data: Partial<DataSource>) => api.post<any, DataSource>("/datasources", data),
  update: (id: number, data: Partial<DataSource>) => api.put<any, DataSource>("/datasources/" + id, data),
  delete: (id: number) => api.delete("/datasources/" + id),
  test: (id: number) => api.post<any, boolean>("/datasources/" + id + "/test")
}
