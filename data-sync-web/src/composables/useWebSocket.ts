import { ref, onUnmounted, type Ref } from "vue"

interface UseWebSocketOptions {
  url?: string
  onMessage?: (msg: string) => void
  reconnectInterval?: number
}

/**
 * WebSocket 连接管理 composable
 * - 建立/断开连接
 * - 自动重连
 * - 组件卸载时自动清理
 */
export function useWebSocket(opts: UseWebSocketOptions = {}) {
  const {
    url = `${window.location.protocol === "https:" ? "wss:" : "ws:"}//${window.location.host}/ws/logs`,
    onMessage,
    reconnectInterval = 3000,
  } = opts

  const connected = ref(false) as Ref<boolean>
  const messages = ref<string[]>([]) as Ref<string[]>

  let ws: WebSocket | null = null
  let reconnectTimer: ReturnType<typeof setTimeout> | null = null
  let manualClose = false

  function connect() {
    if (ws && (ws.readyState === WebSocket.OPEN || ws.readyState === WebSocket.CONNECTING)) return
    manualClose = false

    ws = new WebSocket(url)

    ws.onopen = () => {
      connected.value = true
    }

    ws.onmessage = (event) => {
      if (typeof event.data === "string") {
        messages.value.push(event.data)
        onMessage?.(event.data)
      }
    }

    ws.onerror = () => {
      console.warn("WebSocket 连接异常")
    }

    ws.onclose = () => {
      connected.value = false
      if (!manualClose) {
        reconnectTimer = setTimeout(() => connect(), reconnectInterval)
      }
    }
  }

  function disconnect() {
    manualClose = true
    if (reconnectTimer) clearTimeout(reconnectTimer)
    reconnectTimer = null
    if (ws) {
      ws.close()
      ws = null
    }
    connected.value = false
  }

  function clearMessages() {
    messages.value = []
  }

  onUnmounted(() => {
    disconnect()
  })

  return { connected, messages, connect, disconnect, clearMessages }
}
