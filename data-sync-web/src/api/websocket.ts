export function createLogWebSocket(onMessage: (msg: string) => void): WebSocket {
  const protocol = window.location.protocol === "https:" ? "wss:" : "ws:"
  const host = window.location.host
  const ws = new WebSocket(`${protocol}//${host}/ws/logs`)

  ws.onmessage = (event) => {
    if (typeof event.data === "string") onMessage(event.data)
  }

  ws.onerror = () => console.warn("WebSocket ????")
  ws.onclose = () => setTimeout(() => createLogWebSocket(onMessage), 3000) // auto-reconnect

  return ws
}
