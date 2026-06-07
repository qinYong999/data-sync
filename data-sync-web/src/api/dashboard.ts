import api from "./request"
import type { DashboardVO, RecordVO } from "@/types"

export const dashboardApi = {
  overview: () => api.get<DashboardVO>("/dashboard/overview"),
  recentFails: () => api.get<RecordVO[]>("/dashboard/recent-fails"),
}
