import { onUnmounted, readonly, ref, type Ref } from 'vue'

/** One frame of `GET /api/conversations/{id}/stream`: the JSON the chat stream sends, `type` naming its kind. */
export type TurnFrame = { type?: string } & Record<string, unknown>

export interface ConversationStreamHandlers {
  /** A frame, with the SSE id the server numbered it with; the same frame replayed carries the same id. */
  onFrame: (frame: TurnFrame, eventId: string) => void
  /** The stream opened, or reopened after a drop. */
  onOpen?: () => void
  /** The stream gave up after its last retry: nothing more arrives until it is opened again. */
  onGiveUp?: () => void
}

export interface UseConversationStream {
  /** The conversation the stream follows, or null when it is closed. */
  conversationId: Readonly<Ref<number | null>>
  /** Following a conversation and not given up, so its turns arrive, after any retry under way. */
  up: Readonly<Ref<boolean>>
  /** Follow `conversationId`, from its running turn's start: a new subscription names no last id, so the turn is replayed. */
  open: (conversationId: number) => void
  /** Stop following; the turn on the server keeps running. */
  close: () => void
  /** Retry a stream that gave up, from the last id it got; anything else is left as it is. */
  reopen: () => void
}

// EventSource.CLOSED: the browser gave up rather than reconnecting itself.
const CLOSED = 2
const MAX_ATTEMPTS = 10

/**
 * The open conversation's turn stream (JF-201/202): one EventSource on `GET /api/conversations/{id}/stream`, whose
 * frames every turn of the conversation publishes, the tab's own included. A dropped connection the browser retries
 * itself sends `Last-Event-ID`, so the server answers with what was missed or a `resync`; one it gave up on, after an
 * error status, is reopened with the last id as `lastEventId`, backing off as the event bus does (5 s doubling to
 * 60 s, ten tries). One that gave up waits for {@link UseConversationStream.reopen}. Closed on switch, New Chat and
 * unmount; closing never stops a turn.
 */
export function useConversationStream(handlers: ConversationStreamHandlers): UseConversationStream {
  const conversationId = ref<number | null>(null)
  const up = ref(false)
  let source: EventSource | null = null
  let lastEventId = ''
  let attempts = 0
  let retry: ReturnType<typeof setTimeout> | null = null

  function connect() {
    const id = conversationId.value
    if (id == null) return
    const query = lastEventId ? `?lastEventId=${encodeURIComponent(lastEventId)}` : ''
    const es = new EventSource(`/api/conversations/${id}/stream${query}`)
    source = es
    es.onopen = () => {
      if (source !== es) return
      attempts = 0
      handlers.onOpen?.()
    }
    es.onmessage = (e: MessageEvent) => {
      if (source !== es) return
      attempts = 0
      if (e.lastEventId) lastEventId = e.lastEventId
      let frame: TurnFrame
      try {
        frame = JSON.parse(e.data) as TurnFrame
      }
      catch {
        return
      }
      handlers.onFrame(frame, e.lastEventId ?? '')
    }
    es.onerror = () => {
      if (source !== es || es.readyState !== CLOSED) return
      es.close()
      source = null
      if (attempts >= MAX_ATTEMPTS) {
        up.value = false
        handlers.onGiveUp?.()
        return
      }
      const delay = Math.min(5000 * Math.pow(2, attempts), 60000)
      attempts++
      retry = setTimeout(() => {
        retry = null
        connect()
      }, delay)
    }
  }

  function close() {
    if (retry != null) clearTimeout(retry)
    retry = null
    source?.close()
    source = null
    conversationId.value = null
    up.value = false
  }

  function open(id: number) {
    close()
    conversationId.value = id
    lastEventId = ''
    attempts = 0
    up.value = true
    connect()
  }

  function reopen() {
    if (conversationId.value == null || source != null || retry != null) return
    attempts = 0
    up.value = true
    connect()
  }

  onUnmounted(close)

  return { conversationId: readonly(conversationId), up: readonly(up), open, close, reopen }
}
