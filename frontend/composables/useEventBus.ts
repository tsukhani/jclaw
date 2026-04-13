/**
 * Global SSE event bus composable. Connects once to /api/events and
 * dispatches events to registered listeners. Survives page navigation.
 *
 * Usage:
 *   const { on, off } = useEventBus()
 *   on('skill.promoted', (data) => { ... })
 */

type EventHandler = (data: unknown) => void

const handlers = new Map<string, Set<EventHandler>>()
let eventSource: EventSource | null = null
let connected = false
let reconnectAttempts = 0

function connect() {
  if (connected || typeof window === 'undefined') return

  // Guard: don't open SSE connection if not authenticated (prevents
  // infinite reconnect loop when the backend returns 401/403).
  const auth = useState<boolean>('auth:authenticated')
  if (!auth.value) return

  connected = true

  eventSource = new EventSource('/api/events')

  eventSource.onmessage = (e) => {
    // Successful message — reset backoff counter
    reconnectAttempts = 0
    try {
      const event = JSON.parse(e.data)
      const typeHandlers = handlers.get(event.type)
      if (typeHandlers) {
        for (const handler of typeHandlers) {
          try { handler(event.data) } catch { /* ignore handler errors */ }
        }
      }
    } catch { /* ignore parse errors */ }
  }

  eventSource.onerror = () => {
    connected = false
    eventSource?.close()
    eventSource = null

    // Stop retrying after 10 consecutive failures
    if (reconnectAttempts >= 10) return

    // Exponential backoff: 5s, 10s, 20s, 40s, 60s (capped)
    const delay = Math.min(5000 * Math.pow(2, reconnectAttempts), 60000)
    reconnectAttempts++
    setTimeout(connect, delay)
  }
}

export function useEventBus() {
  // Connect on first use
  connect()

  function on(type: string, handler: EventHandler) {
    if (!handlers.has(type)) handlers.set(type, new Set())
    handlers.get(type)!.add(handler)
  }

  function off(type: string, handler: EventHandler) {
    handlers.get(type)?.delete(handler)
  }

  /**
   * Register a handler that is automatically removed when the calling
   * component unmounts. Prevents handler accumulation on page navigation
   * (e.g., visiting Skills 3 times no longer fires the handler 3 times).
   */
  function onEvent(type: string, handler: EventHandler) {
    on(type, handler)
    if (getCurrentInstance()) {
      onUnmounted(() => off(type, handler))
    }
  }

  return { on, off, onEvent }
}
