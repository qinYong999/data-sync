/**
 * CSRF Token 管理器
 *
 * 与平台工程师确认的定稿方案（会话式 CSRF，`HttpSessionCsrfTokenRepository`）：
 *   - 取令牌：`GET /api/auth/csrf`（匿名可访问）
 *     → `{"token":"...","headerName":"X-CSRF-TOKEN","parameterName":"_csrf","securityEnabled":true}`
 *   - 回传：所有非 GET 请求带请求头 `X-CSRF-TOKEN: <token>`
 *   - 令牌与 Session 绑定：登录/登出后必须用响应里的 `csrfToken` 覆盖本地值
 *   - 403 `code=CSRF_INVALID` → 重新 GET `/api/auth/csrf` 后重试一次
 *   - `app.security.enabled=false` 时 `securityEnabled:false`、`csrfToken:null`，无需任何头
 *
 * 另外作为容错，仍支持从登录页 meta 标签 / 全局注入对象读取令牌（后端如改为页面内嵌可直接生效），
 * 但**不读 cookie**（平台明确说明不是 XSRF-TOKEN cookie 方案）。
 */

import axios from 'axios'

export interface CsrfTokenInfo {
  token: string
  headerName: string
  parameterName: string
}

/** 平台确认的端点（首选） */
const PRIMARY_ENDPOINT = '/api/auth/csrf'
/** 容错候选：若主端点变更仍可自动适配 */
const FALLBACK_ENDPOINTS = ['/api/csrf', '/csrf']

const DEFAULT_HEADER = 'X-CSRF-TOKEN'
/** 会话式方案下同时写这两个头（服务端只认其一，多余头被忽略） */
const COMPAT_HEADERS = ['X-CSRF-TOKEN']

/** 全部分辨率失败后的冷却时间，避免请求风暴 */
const UNAVAILABLE_COOLDOWN_MS = 60_000

let cached: CsrfTokenInfo | null = null
let inflight: Promise<CsrfTokenInfo | null> | null = null
let unavailableUntil = 0
let discoveredEndpoint: string | null = null
/** 后端显式声明安全已关闭 */
let securityDisabled = false

declare global {
  interface Window {
    __CSRF__?: string | { token?: string; headerName?: string; parameterName?: string }
    __CSRF_TOKEN__?: string
  }
}

export function isSecurityDisabled(): boolean {
  return securityDisabled
}

function readMeta(): CsrfTokenInfo | null {
  try {
    const names = ['csrf-token', 'csrfToken', '_csrf', 'xsrf-token']
    for (const n of names) {
      const el = document.querySelector('meta[name="' + n + '"]')
      const content = el ? el.getAttribute('content') : ''
      if (content) return { token: content, headerName: DEFAULT_HEADER, parameterName: '_csrf' }
    }
  } catch {
    /* ignore */
  }
  return null
}

function readGlobal(): CsrfTokenInfo | null {
  try {
    const g = window.__CSRF__
    if (typeof g === 'string' && g) {
      return { token: g, headerName: DEFAULT_HEADER, parameterName: '_csrf' }
    }
    if (g && typeof g === 'object' && g.token) {
      return {
        token: String(g.token),
        headerName: g.headerName ? String(g.headerName) : DEFAULT_HEADER,
        parameterName: g.parameterName ? String(g.parameterName) : '_csrf',
      }
    }
    if (typeof window.__CSRF_TOKEN__ === 'string' && window.__CSRF_TOKEN__) {
      return { token: window.__CSRF_TOKEN__, headerName: DEFAULT_HEADER, parameterName: '_csrf' }
    }
  } catch {
    /* ignore */
  }
  return null
}

/** 从任意形状的响应体里抽取令牌（兼容 {token} / {csrfToken} 等命名） */
export function extractTokenFromBody(body: unknown): CsrfTokenInfo | null {
  if (!body || typeof body !== 'object') return null
  const o = body as Record<string, unknown>
  const tokenKeys = ['token', 'csrfToken', 'csrf', '_csrf', 'xsrfToken']
  let token = ''
  for (const k of tokenKeys) {
    const v = o[k]
    if (typeof v === 'string' && v) {
      token = v
      break
    }
  }
  if (!token) return null
  const headerName = typeof o.headerName === 'string' && o.headerName ? o.headerName : DEFAULT_HEADER
  const parameterName =
    typeof o.parameterName === 'string' && o.parameterName ? o.parameterName : '_csrf'
  return { token, headerName, parameterName }
}

/** 读取响应体里的 securityEnabled（true/false/缺失） */
function readSecurityFlag(body: unknown): boolean | null {
  if (!body || typeof body !== 'object') return null
  const v = (body as Record<string, unknown>).securityEnabled
  return typeof v === 'boolean' ? v : null
}

/**
 * 采纳服务端下发的令牌。
 * 适用于：`/api/auth/csrf`、`/api/auth/session`、`/api/system/info`、登录响应。
 * 若响应声明 securityEnabled=false，则进入"无需令牌"模式。
 */
export function adoptTokenFromBody(body: unknown): boolean {
  const flag = readSecurityFlag(body)
  if (flag === false) {
    securityDisabled = true
    cached = null
    unavailableUntil = Date.now() + UNAVAILABLE_COOLDOWN_MS
    return false
  }
  const info = extractTokenFromBody(body)
  if (info) {
    cached = info
    securityDisabled = false
    unavailableUntil = 0
    return true
  }
  if (flag === true) securityDisabled = false
  return false
}

/** 本地（meta / 全局对象）解析，不发网络请求 */
export function readLocalToken(): CsrfTokenInfo | null {
  return readMeta() || readGlobal()
}

async function probeEndpoint(path: string): Promise<CsrfTokenInfo | null> {
  const res = await axios.request({
    url: path,
    method: 'get',
    withCredentials: true,
    validateStatus: (s) => s >= 200 && s < 300,
    headers: { Accept: 'application/json, text/plain, */*' },
  })
  const flag = readSecurityFlag(res.data)
  if (flag === false) {
    securityDisabled = true
    return null
  }
  return extractTokenFromBody(res.data)
}

/**
 * 取得 CSRF 令牌（带缓存与并发去重）。
 * 永不抛异常：拿不到返回 null，调用方应降级为不带令牌。
 */
export async function getCsrfToken(force = false): Promise<CsrfTokenInfo | null> {
  if (securityDisabled && !force) return null
  if (!force && cached) return cached
  if (!force && Date.now() < unavailableUntil) return null
  if (inflight) return inflight

  inflight = (async () => {
    try {
      const local = readLocalToken()
      if (local && !force) {
        cached = local
        unavailableUntil = 0
        return local
      }

      const order = discoveredEndpoint
        ? [discoveredEndpoint, PRIMARY_ENDPOINT, ...FALLBACK_ENDPOINTS].filter(
            (p, i, arr) => arr.indexOf(p) === i,
          )
        : [PRIMARY_ENDPOINT, ...FALLBACK_ENDPOINTS]

      for (const path of order) {
        try {
          const info = await probeEndpoint(path)
          if (info) {
            cached = info
            discoveredEndpoint = path
            securityDisabled = false
            unavailableUntil = 0
            return info
          }
          if (securityDisabled) {
            // 端点存在但声明安全关闭 → 无需令牌，不要继续探测
            cached = null
            unavailableUntil = Date.now() + UNAVAILABLE_COOLDOWN_MS
            return null
          }
        } catch {
          // 404 / 网络异常 → 试下一个候选
        }
      }

      const local2 = readLocalToken()
      if (local2) {
        cached = local2
        unavailableUntil = 0
        return local2
      }

      unavailableUntil = Date.now() + UNAVAILABLE_COOLDOWN_MS
      return null
    } finally {
      inflight = null
    }
  })()

  return inflight
}

/** 同步读取已缓存的令牌（不发请求），供请求拦截器快速路径使用 */
export function peekCsrfToken(): CsrfTokenInfo | null {
  if (securityDisabled) return null
  return cached || readLocalToken()
}

/** 登录 / 登出 / 403 重试时调用，强制下次重新解析 */
export function invalidateCsrfToken(): void {
  cached = null
  inflight = null
  unavailableUntil = 0
  securityDisabled = false
}

/** 标记"当前无需令牌"（后端未启用安全时调用），避免无谓探测 */
export function markCsrfUnavailable(cooldownMs = UNAVAILABLE_COOLDOWN_MS): void {
  unavailableUntil = Date.now() + cooldownMs
}

/** 把令牌写入请求头（同时写兼容头，服务端只认其一） */
export function applyCsrfHeaders(headers: Record<string, unknown>, info: CsrfTokenInfo): void {
  const names = new Set<string>([info.headerName || DEFAULT_HEADER, ...COMPAT_HEADERS])
  names.forEach((n) => {
    if (n) headers[n] = info.token
  })
}

/** 诊断用：当前令牌状态摘要（不泄露令牌内容） */
export function csrfDebugState(): {
  hasToken: boolean
  headerName: string
  endpoint: string | null
  securityDisabled: boolean
} {
  return {
    hasToken: !!cached,
    headerName: cached ? cached.headerName : DEFAULT_HEADER,
    endpoint: discoveredEndpoint,
    securityDisabled,
  }
}
