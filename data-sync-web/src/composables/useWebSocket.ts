import { ref, computed, onUnmounted, getCurrentInstance, type Ref } from 'vue'

export type WsStatus = 'idle' | 'connecting' | 'open' | 'reconnecting' | 'closed'

export interface UseWebSocketOptions {
  /** 显式 URL；默认按当前页面协议/主机拼 /ws/logs */
  url?: string
  /** 只接收该任务的日志（服务端 `?taskId=` 过滤） */
  taskId?: number | null
  /** 只接收该次执行记录的日志（服务端 `?recordId=` 过滤） */
  recordId?: number | null
  onMessage?: (msg: string, index: number) => void
  /** 首帧重连延迟（毫秒） */
  baseDelay?: number
  /** 最大重连延迟（毫秒） */
  maxDelay?: number
  /** 消息缓冲区上限，超过后丢弃最旧的（防止长时间运行内存膨胀） */
  maxMessages?: number
  /** 是否在建立连接后自动重连（默认 true） */
  autoReconnect?: boolean
  /** 客户端二次过滤（服务端已按 taskId 过滤时的兜底） */
  filter?: (msg: string) => boolean
}

function buildDefaultUrl(taskId?: number | null, recordId?: number | null): string {
  const proto = window.location.protocol === 'https:' ? 'wss:' : 'ws:'
  let url = `${proto}//${window.location.host}/ws/logs`
  const params: string[] = []
  if (taskId !== null && taskId !== undefined && !Number.isNaN(Number(taskId))) {
    params.push('taskId=' + encodeURIComponent(String(taskId)))
  }
  if (recordId !== null && recordId !== undefined && !Number.isNaN(Number(recordId))) {
    params.push('recordId=' + encodeURIComponent(String(recordId)))
  }
  if (params.length) url += '?' + params.join('&')
  return url
}

/**
 * WebSocket 连接管理 composable
 * - 指数退避 + 抖动重连（1s → 2s → 4s → … → 30s 上限）
 * - 断线状态提示（`status === 'reconnecting'` 时界面应显示"已断开，重连中"）
 * - 组件卸载自动清理，切换页面/任务不串台
 * - 支持 `?taskId=` / `?recordId=` 让服务端只推该任务的日志
 */
export function useWebSocket(opts: UseWebSocketOptions = {}) {
  const {
    onMessage,
    baseDelay = 1000,
    maxDelay = 30000,
    maxMessages = 2000,
    autoReconnect = true,
    filter,
  } = opts

  const connected = ref(false)
  const status = ref<WsStatus>('idle') as Ref<WsStatus>
  const attempt = ref(0)
  const messages = ref<string[]>([]) as Ref<string[]>
  const lastCloseReason = ref('')

  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let manualClose = false
  let disposed = false
  let messageSeq = 0

  const url = computed(() => opts.url || buildDefaultUrl(opts.taskId, opts.recordId))

  /** 已断开且正在等待下次重连 */
  const reconnecting = computed(() => status.value === 'reconnecting' && !manualClose)
  const statusText = computed(() => {
    switch (status.value) {
      case 'open':
        return '已连接'
      case 'connecting':
        return '连接中…'
      case 'reconnecting':
        return `已断开，重连中（第 ${attempt.value} 次）`
      case 'closed':
        return '已断开'
      default:
        return '未连接'
    }
  })

  function nextDelay(): number {
    const exp = Math.min(maxDelay, baseDelay * Math.pow(2, Math.max(0, attempt.value - 1)))
    // 抖动 ±20%，避免多客户端同时重连打爆服务端
    const jitter = exp * 0.2 * (Math.random() * 2 - 1)
    return Math.max(baseDelay, Math.round(exp + jitter))
  }

  function scheduleReconnect() {
    if (disposed || manualClose || !autoReconnect) {
      status.value = 'closed'
      return
    }
    if (reconnectTimer) clearTimeout(reconnectTimer)
    status.value = 'reconnecting'
    const delay = nextDelay()
    reconnectTimer = setTimeout(() => {
      reconnectTimer = null
      connect()
    }, delay)
  }

  function pushMessage(msg: string) {
    if (filter && !filter(msg)) return
    const idx = messageSeq++
    messages.value.push(msg)
    if (messages.value.length > maxMessages) {
      messages.value.splice(0, messages.value.length - maxMessages)
    }
    onMessage?.(msg, idx)
  }

  function connect() {
    if (disposed) return
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
    manualClose = false
    status.value = attempt.value === 0 ? 'connecting' : 'reconnecting'

    let socket: WebSocket
    try {
      socket = new WebSocket(url.value)
    } catch {
      attempt.value += 1
      lastCloseReason.value = '无法建立连接（URL 或网络异常）'
      scheduleReconnect()
      return
    }
    ws = socket

    socket.onopen = () => {
      if (disposed || ws !== socket) return
      connected.value = true
      status.value = 'open'
      attempt.value = 0
      lastCloseReason.value = ''
      // 重连成功后可能需要补拉一次历史，由调用方通过 onMessage 之外的方式处理
    }

    socket.onmessage = (event) => {
      if (disposed || ws !== socket) return
      if (typeof event.data === 'string') pushMessage(event.data)
    }

    socket.onerror = () => {
      lastCloseReason.value = '连接异常'
    }

    socket.onclose = (ev) => {
      if (ws === socket) ws = null
      if (disposed) return
      connected.value = false
      if (manualClose) {
        status.value = 'closed'
        return
      }
      attempt.value += 1
      if (ev && ev.reason) lastCloseReason.value = ev.reason
      scheduleReconnect()
    }
  }

  function disconnect() {
    manualClose = true
    disposed = false
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    const socket = ws
    ws = null
    if (socket) {
      socket.onopen = null
      socket.onmessage = null
      socket.onerror = null
      socket.onclose = null
      try {
        socket.close()
      } catch {
        /* ignore */
      }
    }
    connected.value = false
    status.value = 'closed'
    attempt.value = 0
  }

  function clearMessages() {
    messages.value = []
  }

  /** 立即重试（用于用户点"重新连接"或页面重新可见时） */
  function retryNow() {
    if (disposed) return
    if (reconnectTimer) {
      clearTimeout(reconnectTimer)
      reconnectTimer = null
    }
    manualClose = false
    attempt.value = 0
    connect()
  }

  function handleOnline() {
    if (!connected.value && !disposed) retryNow()
  }

  if (typeof window !== 'undefined') {
    window.addEventListener('online', handleOnline)
  }

  if (getCurrentInstance()) {
    onUnmounted(() => {
      disposed = true
      if (typeof window !== 'undefined') {
        window.removeEventListener('online', handleOnline)
      }
      disconnect()
    })
  }

  return {
    connected,
    status,
    statusText,
    reconnecting,
    attempt,
    lastCloseReason,
    messages,
    url,
    connect,
    disconnect,
    retryNow,
    clearMessages,
  }
}
