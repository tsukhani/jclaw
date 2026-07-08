/**
 * Global SSE event bus composable. Connects once to /api/events and
 * dispatches events to registered listeners. Survives page navigation.
 *
 * Usage:
 *   const { on, off } = useEventBus()
 *   on('skill.promoted', (data) => { ... })
 */

// Handlers receive the event payload plus the concrete event type. The type
// arg lets a single wildcard subscriber (e.g. `codingrun.*`) branch on which
// member fired; exact-match subscribers can ignore it.
type EventHandler = (data: unknown, type: string) => void

const handlers = new Map<string, Set<EventHandler>>()
let eventSource: EventSource | null = null
let connected = false
let reconnectAttempts = 0

function connect() {
  if (connected || globalThis.window === undefined) return

  // Guard: don't open SSE connection if not authenticated (prevents
  // infinite reconnect loop when the backend returns 401).
  const auth = useState<boolean>('auth:authenticated')
  if (!auth.value) return

  connected = true

  eventSource = new EventSource('/api/events')

  eventSource.onmessage = (e) => {
    // Successful message — reset backoff counter
    reconnectAttempts = 0
    try {
      const event = JSON.parse(e.data)
      dispatch(event.type, event.data)
    }
    catch { /* ignore parse errors */ }
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

function callHandlers(set: Set<EventHandler> | undefined, data: unknown, type: string) {
  if (!set) return
  for (const handler of set) {
    try {
      handler(data, type)
    }
    catch { /* ignore handler errors */ }
  }
}

/**
 * Deliver one event to its exact-match subscribers plus any single-level
 * wildcard subscribers. A handler registered under `"<ns>.*"` receives every
 * event whose type is `"<ns>.<member>"` — the coding-run monitor (JCLAW-663)
 * relies on this to take both `codingrun.step` and `codingrun.done` through a
 * single subscription, branching on the type passed as the handler's 2nd arg.
 */
function dispatch(type: string, data: unknown) {
  if (typeof type !== 'string') return
  callHandlers(handlers.get(type), data, type)
  const dot = type.indexOf('.')
  if (dot > 0) callHandlers(handlers.get(type.slice(0, dot) + '.*'), data, type)
}

function on(type: string, handler: EventHandler) {
  if (!handlers.has(type)) handlers.set(type, new Set())
  handlers.get(type)!.add(handler)
}

function off(type: string, handler: EventHandler) {
  handlers.get(type)?.delete(handler)
}

export function useEventBus() {
  // Connect on first use
  connect()

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
