export function createLogWebSocket(onMessage: (msg: string) => void, onOpen?: () => void): WebSocket {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:"
  const host = window.location.host
  const ws = new WebSocket(`${protocol}//${host}/ws/logs`)

  ws.onopen = () => { if (onOpen) onOpen() }
  ws.onmessage = (event) => {
    if (typeof event.data === "string") onMessage(event.data)
  }

  ws.onerror = () => console.warn("WebSocket 连接异常")
  ws.onclose = () => setTimeout(() => createLogWebSocket(onMessage), 3000)

  return ws
}