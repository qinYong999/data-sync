import axios from "axios"
import { ElMessage } from "element-plus"

const client = axios.create({ baseURL: "/api", timeout: 30000 })

/** 请求拦截器 — 可选附加全局 loading */
client.interceptors.request.use(
  (config) => config,
  (error) => Promise.reject(error),
)

/** 响应拦截器 — 统一错误提示 */
client.interceptors.response.use(
  (response) => response,
  (error) => {
    const msg =
      error.response?.data?.error ||
      error.response?.data?.message ||
      error.message ||
      "请求失败"
    // 避免重复弹窗（组件内也可能 catch 自己处理）
    if (!error.config?.silent) {
      ElMessage.error(msg)
    }
    return Promise.reject(error)
  },
)

const api = {
  get: <T = any>(url: string, config?: any) =>
    client.get<T>(url, config).then((r) => r.data),
  post: <T = any>(url: string, data?: any, config?: any) =>
    client.post<T>(url, data, config).then((r) => r.data),
  put: <T = any>(url: string, data?: any, config?: any) =>
    client.put<T>(url, data, config).then((r) => r.data),
  delete: <T = any>(url: string, config?: any) =>
    client.delete<T>(url, config).then((r) => r.data),
}

export default api
