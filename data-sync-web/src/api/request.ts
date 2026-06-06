import axios from "axios";

const client = axios.create({ baseURL: "/api", timeout: 30000 });

const api = {
  get: (url) => client.get(url).then(r => r.data),
  post: (url, data) => client.post(url, data).then(r => r.data),
  put: (url, data) => client.put(url, data).then(r => r.data),
  delete: (url) => client.delete(url).then(r => r.data),
};
export default api;
