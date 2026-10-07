/**
 * 认证事件总线：把"未认证"信号从 axios 拦截器传给路由层，
 * 避免 request.ts ↔ router/index.ts 的循环依赖。
 */

type UnauthorizedHandler = (reason: string) => void

let handler: UnauthorizedHandler | null = null

/** 路由层注册真正的跳转实现 */
export function setUnauthorizedHandler(fn: UnauthorizedHandler | null): void {
  handler = fn
}

/** 记录当前是否已经在登录页，避免重复提示/重复跳转 */
let onLoginPage = false
export function setOnLoginPage(v: boolean): void {
  onLoginPage = v
}

let lastNotifyAt = 0
const NOTIFY_THROTTLE_MS = 1500

/**
 * 通知"需要重新登录"。
 * 1.5s 内重复触发只执行一次，避免并发请求打出一堆错误提示。
 */
export function notifyUnauthorized(reason = '未登录或登录已过期，请重新登录'): void {
  const now = Date.now()
  if (now - lastNotifyAt < NOTIFY_THROTTLE_MS) return
  lastNotifyAt = now

  if (handler) {
    handler(reason)
    return
  }
  // 路由尚未就绪（极端情况）→ 直接整页跳转
  if (!onLoginPage) {
    const redirect = encodeURIComponent(window.location.pathname + window.location.search)
    window.location.href = '/login?redirect=' + redirect
  }
}

/** 登录成功后调用，立即解除提示节流 */
export function resetUnauthorizedThrottle(): void {
  lastNotifyAt = 0
}
