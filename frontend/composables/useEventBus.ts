/**
 * Global SSE event bus composable. Connects once to /api/events and
 * dispatches events to registered listeners. Survives page navigation.
 *
 * Usage:
 *   const { on, off } = useEventBus()
 *   on('skill.promoted', (data) => { ... })
 */

type EventHandler = (data: any) => void

const handlers = new Map<string, Set<EventHandler>>()
let eventSource: EventSource | null = null
let connected = false

function connect() {
  if (connected || typeof window === 'undefined') return
  connected = true

  eventSource = new EventSource('/api/events')

  eventSource.onmessage = (e) => {
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
    // Reconnect after a delay
    connected = false
    eventSource?.close()
    eventSource = null
    setTimeout(connect, 5000)
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

  return { on, off }
}
