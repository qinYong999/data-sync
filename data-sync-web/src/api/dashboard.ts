import api from "./request"
export const dashboardApi = {
  overview: () => api.get("/dashboard/overview"),
  recentFails: () => api.get("/dashboard/recent-fails"),
}