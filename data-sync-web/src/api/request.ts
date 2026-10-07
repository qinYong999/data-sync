import axios, {
  AxiosError,
  type AxiosInstance,
  type AxiosRequestConfig,
  type AxiosResponse,
  type InternalAxiosRequestConfig,
} from 'axios'
import { ElMessage } from 'element-plus'
import { notifyUnauthorized } from '@/utils/authBus'
import {
  applyCsrfHeaders,
  getCsrfToken,
  invalidateCsrfToken,
  peekCsrfToken,
} from '@/utils/csrf'
import type { ApiErrorBody } from '@/types'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** true = 不弹全局错误提示，由调用方自行处理 */
    silent?: boolean
    /** true = 401/未登录时不跳登录页（登录页自身的探测请求用） */
    skipAuthRedirect?: boolean
    /** 内部使用：标记该请求已因 CSRF 失败重试过一次 */
    _csrfRetried?: boolean
  }
}

const UNSAFE_METHODS = ['post', 'put', 'patch', 'delete']

/** 业务错误：携带后端统一错误体的 code / status / details */
export class ApiError extends Error {
  status: number
  code: string
  details: string[]
  raw: unknown

  constructor(message: string, status = 0, code = '', details: string[] = [], raw: unknown = null) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.code = code
    this.details = details
    this.raw = raw
  }
}

/** 把任意异常转成中文可读消息 */
export function toApiError(err: unknown): ApiError {
  if (err instanceof ApiError) return err
  if (axios.isAxiosError(err)) {
    const e = err as AxiosError<ApiErrorBody>
    const body: unknown = e.response?.data
    const status = e.response?.status || 0
    let message = ''
    let code = ''
    let details: string[] = []
    if (body && typeof body === 'object') {
      const b = body as ApiErrorBody
      message = String(b.message || b.error || '')
      code = String(b.code || '')
      if (Array.isArray(b.details)) details = b.details.map((d) => String(d))
    } else if (typeof body === 'string' && body && !body.trimStart().startsWith('<')) {
      message = body
    }
    if (!message) {
      if (e.code === 'ECONNABORTED') message = '请求超时，请稍后重试'
      else if (status === 401) message = '未登录或登录已过期，请重新登录'
      else if (status === 403) message = '没有权限执行该操作（可能是登录状态失效或 CSRF 令牌无效）'
      else if (status === 404) message = '接口不存在或资源已被删除'
      else if (status >= 500) message = '服务器内部错误，请查看后端日志'
      else message = e.message || '请求失败'
    }
    return new ApiError(message, status, code, details, body)
  }
  if (err instanceof Error) return new ApiError(err.message || '请求失败')
  return new ApiError('请求失败')
}

/**
 * 判断响应是否"被重定向到了服务端登录页"。
 *
 * 只依据**响应体本身是不是 HTML 文档**判断 —— 绝不能拿 URL 里有没有 `/login` 做判断，
 * 否则 `/api/auth/login` 这类合法接口会被误判成"登录页"（曾导致登录永远失败）。
 * 后端契约里 `/api/**` 一律返回 JSON，出现 HTML 文档只可能是 Spring Security
 * 把浏览器导航式请求 302 到了 MPA 登录页。
 */
function isHtmlResponse(res: AxiosResponse): boolean {
  const data = res.data
  if (typeof data !== 'string') return false
  const head = data.trimStart().slice(0, 64).toLowerCase()
  return head.startsWith('<!doctype html') || head.startsWith('<html')
}

/**
 * 统一的基础配置。
 * 会话式 CSRF（平台定稿）：令牌走 `X-CSRF-TOKEN` 请求头，由拦截器注入；
 * 不使用 axios 内建的 cookie XSRF，但必须带 cookie 以维持 Session。
 */
const BASE_CONFIG: AxiosRequestConfig = {
  withCredentials: true,
  headers: {
    'X-Requested-With': 'XMLHttpRequest',
    // 显式声明期望 JSON：后端据此对 /api/** 返回 401 JSON 而不是 302 重定向
    Accept: 'application/json, text/plain, */*',
  },
}

export const client: AxiosInstance = axios.create({
  baseURL: '/api',
  timeout: 60000,
  ...BASE_CONFIG,
})

/**
 * 用于访问 `/api` 之外的根路径（登录 / 登出等）的实例。
 * 不触发全局登录跳转，也不会因为探测 404 而弹提示。
 */
export const rootClient: AxiosInstance = axios.create({
  baseURL: '',
  timeout: 60000,
  ...BASE_CONFIG,
})

/** 请求拦截：写请求自动补 CSRF 令牌（拿不到则降级为不带令牌，不阻塞） */
async function attachCsrf(config: InternalAxiosRequestConfig): Promise<InternalAxiosRequestConfig> {
  const method = String(config.method || 'get').toLowerCase()
  if (!UNSAFE_METHODS.includes(method)) return config

  let info = peekCsrfToken()
  if (!info) {
    // 首次或不带令牌 → 解析一次（失败会进入冷却，不阻塞业务）
    info = await getCsrfToken()
  }
  if (info) {
    const headers = (config.headers || {}) as unknown as Record<string, unknown>
    applyCsrfHeaders(headers, info)
    config.headers = headers as InternalAxiosRequestConfig['headers']
  }
  return config
}

client.interceptors.request.use(
  (config) => attachCsrf(config),
  (error) => Promise.reject(error),
)

rootClient.interceptors.request.use(
  (config) => attachCsrf(config),
  (error) => Promise.reject(error),
)

/** 403 且 code=CSRF_INVALID（或形似 CSRF 失效）时，刷新令牌并原样重试一次 */
async function retryAfterCsrfRefresh(error: AxiosError): Promise<AxiosResponse | null> {
  const config = error.config as InternalAxiosRequestConfig | undefined
  if (!config) return null
  const status = error.response?.status
  if (status !== 403 || config._csrfRetried) return null

  const body = error.response?.data as ApiErrorBody | undefined
  const code = String(body?.code || '').toUpperCase()
  const text = String(body?.message || body?.error || '').toLowerCase()
  // 平台定稿：403 + code=CSRF_INVALID 表示令牌失效，需重新取令牌后重试一次
  const looksLikeCsrf =
    code === 'CSRF_INVALID' ||
    code === 'INVALID_CSRF_TOKEN' ||
    text.includes('csrf') ||
    text.includes('令牌') ||
    text.includes('跨站')

  if (!looksLikeCsrf) return null

  config._csrfRetried = true
  invalidateCsrfToken()
  const info = await getCsrfToken(true)
  if (!info) return null
  const headers = (config.headers || {}) as unknown as Record<string, unknown>
  applyCsrfHeaders(headers, info)
  config.headers = headers as InternalAxiosRequestConfig['headers']
  return client.request(config)
}

function handleResponseError(error: AxiosError): Promise<never> {
  const config = error.config as InternalAxiosRequestConfig | undefined
  const status = error.response?.status
  const silent = !!config?.silent
  const skipRedirect = !!config?.skipAuthRedirect

  if (status === 401 && !skipRedirect) {
    notifyUnauthorized('未登录或登录已过期，请重新登录')
  }

  const apiError = toApiError(error)
  if (!silent && !(status === 401 && !skipRedirect)) {
    ElMessage.error(apiError.message)
  }
  return Promise.reject(apiError)
}

for (const inst of [client, rootClient]) {
  inst.interceptors.response.use(
    (response) => {
      // 302 → /login 时浏览器会自动跟随，XHR 最终拿到的是 HTML 登录页。
      // 这里按"响应内容类型"识别，转成未认证处理，避免页面里弹一堆 JSON 解析错误。
      if (isHtmlResponse(response)) {
        const skipRedirect = !!(response.config as AxiosRequestConfig).skipAuthRedirect
        if (!skipRedirect) {
          notifyUnauthorized('登录状态已失效，请重新登录')
        }
        return Promise.reject(
          new ApiError('登录状态已失效，请重新登录', 302, 'UNAUTHENTICATED', [], response.data),
        )
      }
      return response
    },
    async (error: AxiosError) => {
      const retried = await retryAfterCsrfRefresh(error)
      if (retried) return retried
      return handleResponseError(error)
    },
  )
}

const api = {
  get: <T = unknown>(url: string, config?: AxiosRequestConfig) =>
    client.get<T>(url, config).then((r) => r.data),
  post: <T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    client.post<T>(url, data, config).then((r) => r.data),
  put: <T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    client.put<T>(url, data, config).then((r) => r.data),
  patch: <T = unknown>(url: string, data?: unknown, config?: AxiosRequestConfig) =>
    client.patch<T>(url, data, config).then((r) => r.data),
  delete: <T = unknown>(url: string, config?: AxiosRequestConfig) =>
    client.delete<T>(url, config).then((r) => r.data),
  /** 探测型请求：静默 + 不跳转 + 不因 4xx 抛错，返回 null 表示不可用 */
  probe: async <T = unknown>(url: string, config?: AxiosRequestConfig): Promise<T | null> => {
    try {
      const res = await client.request<T>({
        url,
        method: 'get',
        silent: true,
        skipAuthRedirect: true,
        validateStatus: (s) => s >= 200 && s < 300,
        ...config,
      })
      if (isHtmlResponse(res as AxiosResponse)) return null
      return res.data
    } catch {
      return null
    }
  },
}

export default api
