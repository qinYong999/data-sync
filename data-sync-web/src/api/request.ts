import axios from "axios"
const client = axios.create({ baseURL: "/api", timeout: 30000 })
const api = {
  get: (url: string, config?: any) => client.get(url, config).then(r => r.data),
  post: (url: string, data?: any, config?: any) => client.post(url, data, config).then(r => r.data),
  put: (url: string, data?: any, config?: any) => client.put(url, data, config).then(r => r.data),
  delete: (url: string, config?: any) => client.delete(url, config).then(r => r.data),
}
export default api
