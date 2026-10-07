import api, { rootClient, ApiError } from './request'
import {
  adoptTokenFromBody,
  getCsrfToken,
  invalidateCsrfToken,
  markCsrfUnavailable,
  csrfDebugState,
  isSecurityDisabled,
} from '@/utils/csrf'
import type { AuthUser, LoginVO, SessionVO } from '@/types'

/**
 * 认证 API —— 按平台工程师 2026-05 定稿实现：
 *
 *   会话探测：GET  /api/auth/session （匿名）→ {authenticated, username, csrfToken}
 *   取 CSRF ：GET  /api/auth/csrf   （匿名）→ {token, headerName, parameterName, securityEnabled}
 *   登录    ：POST /api/auth/login   body {username,password} + X-CSRF-TOKEN
 *              → 200 {success:true, username, csrfToken} ／ 401 {code:"BAD_CREDENTIALS"}
 *   登出    ：POST /api/auth/logout  (+CSRF) → 200 {success:true, message}
 *
 * 未认证访问 `/api/**` → 401 JSON（code=UNAUTHENTICATED），不会 302。
 * `app.security.enabled=false` 时全部放行、无令牌，前端必须能正常降级。
 *
 * 为降低联调风险，`/api/auth/session` 不可用时仍回退到 `/api/auth/me`、`/api/me`，
 * 再回退到探测 `/api/system/info`（该端点需认证）。
 */

/**
 * 会话端点候选（**相对于 axios baseURL `/api`**，即最终请求 `/api/auth/session`）。
 * 注意：这里不能写全路径 `/api/auth/session`，否则会拼成 `/api/api/auth/session` → 404，
 * 进而退化成"探测 /api/system/info"的兜底分支，导致登录后顶栏拿不到用户名（曾出现该缺陷）。
 */
const ME_CANDIDATES = ['/auth/session', '/auth/me', '/me']

export interface LoginResult {
  success: boolean
  message: string
}

export interface AuthDiagnostics {
  securityEnabled: boolean | null
  sessionEndpoint: string | null
  loginEndpoint: string | null
  csrf: ReturnType<typeof csrfDebugState>
}

let sessionEndpoint: string | null = null
let loginEndpoint: string | null = null
let securityEnabled: boolean | null = null

function isNotFound(e: unknown): boolean {
  return e instanceof ApiError && (e.status === 404 || e.status === 405)
}

function unwrapAuthUser(raw: unknown): AuthUser | null {
  if (!raw || typeof raw !== 'object') return null
  const o = raw as Record<string, unknown>
  const hasShape =
    o.authenticated !== undefined ||
    o.username !== undefined ||
    o.name !== undefined ||
    o.principal !== undefined
  if (!hasShape) return null
  const authenticated =
    typeof o.authenticated === 'boolean' ? o.authenticated : !!(o.username || o.name)
  const username = o.username ?? o.name ?? o.principal
  return {
    authenticated,
    username: typeof username === 'string' && username && username !== 'anonymousUser' ? username : undefined,
  }
}

/**
 * 查询当前登录态。永不抛异常。
 * 返回 null 表示"无法判定"（端点不存在 / 网络异常），调用方不应据此拦截导航。
 */
export async function fetchCurrentUser(): Promise<AuthUser | null> {
  const order = sessionEndpoint ? [sessionEndpoint] : ME_CANDIDATES

  for (const path of order) {
    try {
      const raw = await api.get<SessionVO>(path, { silent: true, skipAuthRedirect: true })
      // 会话端点同时下发新的 CSRF 令牌（令牌与 Session 绑定）
      adoptTokenFromBody(raw)
      const flag = (raw as Record<string, unknown>)?.securityEnabled
      if (typeof flag === 'boolean') securityEnabled = flag
      else securityEnabled = true

      sessionEndpoint = path
      const user = unwrapAuthUser(raw)
      if (user) return user
      return { authenticated: true }
    } catch (e) {
      if (isNotFound(e)) continue
      if (e instanceof ApiError && (e.status === 401 || e.status === 403)) {
        securityEnabled = true
        return { authenticated: false }
      }
      // 其它异常（超时/500）→ 试下一个候选
    }
  }

  // 兜底：/api/** 全部需要认证（契约 §4.3），能拿到 system/info 说明会话有效
  const info = await api.probe<Record<string, unknown>>('/system/info', { silent: true })
  if (info) {
    adoptTokenFromBody(info)
    securityEnabled = true
    return { authenticated: true }
  }
  return null
}

/** 登录：走 JSON 端点（平台推荐） */
export async function login(username: string, password: string): Promise<LoginResult> {
  if (!username || !password) return { success: false, message: '请输入用户名和密码' }

  // 登录请求本身也需要 CSRF 令牌
  await getCsrfToken(true).catch(() => null)

  let result: LoginResult | null = null
  try {
    // 只放行 2xx / 404 / 405：401(密码错) 必须走异常分支，否则会把"登录失败"当成功
    const raw = await api.post<LoginVO>(
      '/auth/login',
      { username, password },
      {
        silent: true,
        skipAuthRedirect: true,
        validateStatus: (s) => (s >= 200 && s < 300) || s === 404 || s === 405,
      },
    )
    if (raw && typeof raw === 'object' && raw.success === false) {
      result = { success: false, message: String(raw.message || '登录失败') }
    } else {
      // 登录响应带新令牌 → 直接采纳，避免多打一次 /api/auth/csrf
      const adopted = adoptTokenFromBody(raw)
      loginEndpoint = '/api/auth/login'
      securityEnabled = true
      if (!adopted) {
        invalidateCsrfToken()
        await getCsrfToken(true).catch(() => null)
      }
      result = { success: true, message: String(raw?.message || '登录成功') }
    }
  } catch (e) {
    if (isNotFound(e)) {
      result = await fallbackFormLogin(username, password)
    } else {
      const ae = e as ApiError
      if (ae.status === 401 || ae.status === 400 || ae.status === 403) {
        result = {
          success: false,
          message:
            ae.code === 'BAD_CREDENTIALS'
              ? '用户名或密码错误'
              : ae.status === 403
                ? '登录被拒绝（可能是 CSRF 令牌失效），请刷新页面后重试'
                : ae.message || '用户名或密码错误',
        }
      } else {
        result = { success: false, message: ae.message || '登录失败' }
      }
    }
  }

  if (!result) return { success: false, message: '登录失败' }
  if (!result.success) return result

  // 用会话端点确认一次（拿到的同时也是最新令牌）
  const user = await fetchCurrentUser()
  if (user && user.authenticated === false) {
    return { success: false, message: '登录未被服务端确认，请检查账号密码' }
  }
  return result
}

/** 兜底：Spring Security 自带的表单登录（`app.security.enabled=true` 且未提供 JSON 端点时） */
async function fallbackFormLogin(username: string, password: string): Promise<LoginResult | null> {
  const params = new URLSearchParams()
  params.set('username', username)
  params.set('password', password)
  const tokenInfo = await getCsrfToken()
  if (tokenInfo) params.set(tokenInfo.parameterName || '_csrf', tokenInfo.token)

  try {
    const res = await rootClient.request<unknown>({
      url: '/login',
      method: 'post',
      data: params.toString(),
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      silent: true,
      skipAuthRedirect: true,
      validateStatus: (s) => s < 500,
    })
    if (res.status === 404) return null
    const finalUrl = String((res.request as XMLHttpRequest)?.responseURL || '')
    if (finalUrl.includes('error')) return { success: false, message: '用户名或密码错误' }
    if (res.status >= 400) return { success: false, message: '登录失败（HTTP ' + res.status + '）' }
    loginEndpoint = '/login'
    invalidateCsrfToken()
    await getCsrfToken(true).catch(() => null)
    return { success: true, message: '登录成功' }
  } catch (e) {
    if (isNotFound(e)) return null
    const ae = e as ApiError
    if (ae.status === 302 || ae.code === 'UNAUTHENTICATED') {
      return { success: false, message: '用户名或密码错误' }
    }
    return null
  }
}

/** 退出登录 */
export async function logout(): Promise<LoginResult> {
  try {
    const raw = await api.post<{ success?: boolean; message?: string }>(
      '/auth/logout',
      undefined,
      { silent: true, skipAuthRedirect: true, validateStatus: (s) => s < 500 },
    )
    invalidateCsrfToken()
    if (raw && typeof raw === 'object' && raw.success === false) {
      return { success: false, message: String(raw.message || '退出登录失败') }
    }
    return { success: true, message: String(raw?.message || '已退出登录') }
  } catch (e) {
    const ae = e as ApiError
    if (isNotFound(e)) {
      // 没有该端点 → 至少清理本地状态；同时尝试 Spring Security 默认登出
      try {
        await rootClient.post('/logout', undefined, {
          silent: true,
          skipAuthRedirect: true,
          validateStatus: (s) => s < 500,
        })
      } catch {
        /* ignore */
      }
      invalidateCsrfToken()
      return { success: true, message: '本地会话已清理' }
    }
    if (ae.status === 401 || ae.status === 403 || ae.code === 'UNAUTHENTICATED') {
      invalidateCsrfToken()
      return { success: true, message: '已退出登录' }
    }
    return { success: false, message: ae.message || '退出登录失败' }
  }
}

/** 诊断信息：登录页展示，便于联调定位 */
export function authDiagnostics(): AuthDiagnostics {
  return {
    securityEnabled: securityEnabled ?? (isSecurityDisabled() ? false : null),
    sessionEndpoint,
    loginEndpoint,
    csrf: csrfDebugState(),
  }
}

/**
 * 探测后端认证模式；后端显式声明 `securityEnabled=false` 时降级为"无需登录"。
 */
export async function detectAuthMode(): Promise<{
  securityEnabled: boolean | null
  user: AuthUser | null
}> {
  // 先打一次匿名端点，既拿令牌也拿 securityEnabled
  await getCsrfToken(true).catch(() => null)
  const user = await fetchCurrentUser()
  if (isSecurityDisabled()) {
    securityEnabled = false
  } else if (user === null && securityEnabled === null) {
    // 完全探测不到 → 可能后端未启用安全；不要阻塞用户
    markCsrfUnavailable()
  }
  return { securityEnabled: isSecurityDisabled() ? false : securityEnabled, user }
}
