import api from "./request"
export const taskApi = {
  list: () => api.get("/tasks"),
  get: (id: number) => api.get("/tasks/" + id),
  create: (data: any) => api.post("/tasks", data),
  update: (id: number, data: any) => api.put("/tasks/" + id, data),
  delete: (id: number) => api.delete("/tasks/" + id),
  enable: (id: number) => api.post("/tasks/" + id + "/enable"),
  disable: (id: number) => api.post("/tasks/" + id + "/disable"),
  records: (taskId: number) => api.get("/tasks/" + taskId + "/records"),
}