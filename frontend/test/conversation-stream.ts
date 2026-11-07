import { vi } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'

/**
 * `GET /api/conversations/{id}/stream` as a test drives it: each EventSource the page opens is kept, and the test opens
 * it, sends it frames (numbered as the server numbers them, unless the test names the id) and fails it.
 */
export class FakeEventSource {
  static all: FakeEventSource[] = []
  readyState = 0
  onopen: ((e: Event) => void) | null = null
  onmessage: ((e: MessageEvent) => void) | null = null
  onerror: ((e: Event) => void) | null = null
  closed = false
  private nextId = 1

  constructor(readonly url: string) {
    FakeEventSource.all.push(this)
  }

  close() {
    this.closed = true
    this.readyState = 2
  }

  /** The response headers arrived. */
  open() {
    this.readyState = 1
    this.onopen?.(new Event('open'))
  }

  send(frame: object, id = String(this.nextId++)) {
    this.onmessage?.({ data: JSON.stringify(frame), lastEventId: id } as MessageEvent)
  }

  /** An error status, which the browser does not retry. */
  fail() {
    this.readyState = 2
    this.onerror?.(new Event('error'))
  }
}

/** Every EventSource the code under test opens from now on is a {@link FakeEventSource}. */
export function fakeConversationStreams() {
  FakeEventSource.all = []
  vi.stubGlobal('EventSource', FakeEventSource)
}

/** The conversation stream open on `conversationId`; fails the test when there is none or more than one. */
export function streamOn(conversationId: number): FakeEventSource {
  const open = openStreamsOn(conversationId)
  if (open.length !== 1) throw new Error(`expected one open stream on conversation ${conversationId}, found ${open.length}`)
  return open[0]!
}

export function openStreamsOn(conversationId: number): FakeEventSource[] {
  return FakeEventSource.all.filter(s => !s.closed && s.url.startsWith(`/api/conversations/${conversationId}/stream`))
}

/** Answers `POST /api/chat/turn` as `answer` says, recording each body it got. */
export function serveTurnStart(answer: (body: Record<string, unknown>) => object | Promise<object>) {
  const bodies: Array<Record<string, unknown>> = []
  registerEndpoint('/api/chat/turn', {
    method: 'POST',
    handler: async (event) => {
      const { readBody } = await import('h3')
      const body = await readBody<Record<string, unknown>>(event)
      bodies.push(body)
      return answer(body)
    },
  })
  return bodies
}
