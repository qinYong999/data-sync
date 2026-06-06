import axios from "axios"
const api = axios.create({ baseURL: "/api", timeout: 30000 })
api.interceptors.response.use(
  response => response.data,
  error => {
    const msg = error.response?.data?.error || error.message || "请求失败"
    console.error("API Error:", msg)
    return Promise.reject(new Error(msg))
  }
)
export default api