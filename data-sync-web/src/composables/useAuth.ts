import { reactive, computed } from 'vue'
import { fetchCurrentUser, login as apiLogin, logout as apiLogout, detectAuthMode } from '@/api/auth'
import { resetUnauthorizedThrottle } from '@/utils/authBus'
import { invalidateCsrfToken, isSecurityDisabled } from '@/utils/csrf'

export interface AuthState {
  /** 是否已完成一次会话判定 */
  ready: boolean
  authenticated: boolean
  username: string
  /** null = 尚未探测出后端是否启用安全 */
  securityEnabled: boolean | null
}

/** 模块级单例状态：所有组件共享同一份登录态 */
const state = reactive<AuthState>({
  ready: false,
  authenticated: false,
  username: '',
  securityEnabled: null,
})

let sessionPromise: Promise<AuthState> | null = null

const SESSION_TIMEOUT_MS = 6000

function withTimeout<T>(p: Promise<T>, ms: number, fallback: T): Promise<T> {
  return new Promise<T>((resolve) => {
    let settled = false
    const timer = setTimeout(() => {
      if (!settled) {
        settled = true
        resolve(fallback)
      }
    }, ms)
    p.then((v) => {
      if (!settled) {
        settled = true
        clearTimeout(timer)
        resolve(v)
      }
    }).catch(() => {
      if (!settled) {
        settled = true
        clearTimeout(timer)
        resolve(fallback)
      }
    })
  })
}

/**
 * 判定当前会话。结果缓存；force=true 强制重新判定。
 *
 * 三种结果：
 *  - authenticated=true  → 会话有效（或后端未启用安全）
 *  - authenticated=false → 明确未登录，路由层应跳登录页
 *  - 无法判定（探测超时/接口不存在）→ 不放行也不拦截，交给 axios 拦截器兜底
 */
export async function ensureSession(force = false): Promise<AuthState> {
  if (state.ready && !force) return state
  if (sessionPromise && !force) return sessionPromise

  sessionPromise = (async () => {
    const user = await withTimeout(fetchCurrentUser(), SESSION_TIMEOUT_MS, null)
    if (user === null) {
      // 无法判定：不拦截（避免把"接口不存在"误判成"未登录"而卡死页面）
      state.authenticated = true
      state.securityEnabled = null
    } else {
      state.authenticated = !!user.authenticated
      state.username = user.username || ''
      state.securityEnabled = true
      if (!state.authenticated) state.username = ''
    }
    state.ready = true
    return state
  })()

  try {
    return await sessionPromise
  } finally {
    sessionPromise = null
  }
}

export interface LoginOutcome {
  success: boolean
  message: string
}

export async function doLogin(username: string, password: string): Promise<LoginOutcome> {
  const res = await apiLogin(username, password)
  if (res.success) {
    resetUnauthorizedThrottle()
    state.authenticated = true
    state.username = username
    state.securityEnabled = isSecurityDisabled() ? false : true
    state.ready = true
  }
  return { success: res.success, message: res.message }
}

export async function doLogout(): Promise<void> {
  try {
    await apiLogout()
  } finally {
    invalidateCsrfToken()
    state.authenticated = false
    state.username = ''
    state.ready = true
  }
}

/** 由 axios 拦截器 / 路由守卫调用：标记会话已失效 */
export function markUnauthenticated(): void {
  state.authenticated = false
  state.username = ''
  state.ready = true
}

export function useAuth() {
  return {
    state,
    ready: computed(() => state.ready),
    authenticated: computed(() => state.authenticated),
    username: computed(() => state.username),
    securityEnabled: computed(() => state.securityEnabled),
    ensureSession,
    login: doLogin,
    logout: doLogout,
    markUnauthenticated,
    /** 探测后端安全模式（登录页挂载时调用，用于展示提示） */
    detect: detectAuthMode,
  }
}

/** 供路由守卫使用：是否应拦截 */
export function shouldBlockNavigation(): boolean {
  if (!state.ready) return false
  if (state.securityEnabled === false) return false
  return !state.authenticated
}
