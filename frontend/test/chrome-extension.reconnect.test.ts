import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

// The real background.js, run against a fake chrome and a fake WebSocket under fake timers. close() does not
// call onclose, which is what a socket to a hung server does: the close handshake never finishes.
class FakeSocket {
  static all: FakeSocket[] = []
  sent: Array<Record<string, unknown>> = []
  closed = false
  onopen: (() => void) | null = null
  onmessage: ((ev: { data: string }) => Promise<void> | void) | null = null
  onclose: (() => void) | null = null
  onerror: (() => void) | null = null
  constructor(public url: string) { FakeSocket.all.push(this) }
  send(data: string) { this.sent.push(JSON.parse(data)) }
  close() { this.closed = true }
  async hear(msg: Record<string, unknown>) { await this.onmessage?.({ data: JSON.stringify(msg) }) }
}

let status: string | undefined
let alarmHandler: ((a: { name: string }) => void) | undefined
const alarmsCreate = vi.fn()

async function boot() {
  FakeSocket.all = []
  status = undefined
  alarmHandler = undefined
  alarmsCreate.mockClear()
  vi.resetModules()
  const noEvent = { addListener: vi.fn() }
  vi.stubGlobal('WebSocket', FakeSocket)
  vi.stubGlobal('chrome', {
    storage: {
      local: {
        get: async () => ({ token: 'tok', port: 9000 }),
        set: async (v: { status?: string }) => { if (v.status) status = v.status },
      },
      onChanged: noEvent,
    },
    runtime: { onStartup: noEvent, onInstalled: noEvent },
    alarms: { create: alarmsCreate, onAlarm: { addListener: (h: typeof alarmHandler) => { alarmHandler = h } } },
    tabGroups: { query: async () => [], update: vi.fn() },
    debugger: { onEvent: noEvent, onDetach: noEvent },
    tabs: { onRemoved: noEvent },
  })
  await import('../../chrome-extension/background.js')
  await vi.advanceTimersByTimeAsync(0)
}

async function ready(sock: FakeSocket) {
  sock.onopen?.()
  await sock.hear({ type: 'ready' })
}

describe('the extension keeping its connection to JClaw', () => {
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
    vi.unstubAllGlobals()
  })

  it('says connected once JClaw is ready', async () => {
    await boot()
    expect(FakeSocket.all).toHaveLength(1)
    FakeSocket.all[0]!.onopen?.()
    expect(FakeSocket.all[0]!.sent).toEqual([{ type: 'hello', token: 'tok' }])
    await FakeSocket.all[0]!.hear({ type: 'ready' })
    expect(status).toBe('connected')
  })

  it('lets go of a server that stops answering and connects again', async () => {
    await boot()
    const first = FakeSocket.all[0]!
    await ready(first)

    await vi.advanceTimersByTimeAsync(20_000)
    expect(first.sent.at(-1)).toEqual({ type: 'ping' })
    expect(first.closed).toBe(false)

    await vi.advanceTimersByTimeAsync(10_000)
    expect(first.closed).toBe(true)
    expect(status).toBe('disconnected')

    await vi.advanceTimersByTimeAsync(1_000)
    expect(FakeSocket.all).toHaveLength(2)
  })

  it('keeps a connection that answers its pings', async () => {
    await boot()
    const sock = FakeSocket.all[0]!
    await ready(sock)

    await vi.advanceTimersByTimeAsync(20_000)
    for (let round = 0; round < 6; round++) {
      await vi.advanceTimersByTimeAsync(5_000)
      await sock.hear({ type: 'pong' })
      await vi.advanceTimersByTimeAsync(15_000)
    }

    expect(sock.sent.filter(m => m.type === 'ping').length).toBeGreaterThanOrEqual(5)
    expect(sock.closed).toBe(false)
    expect(FakeSocket.all).toHaveLength(1)
    expect(status).toBe('connected')
  })

  it('gives up on a socket that never gets an answer after opening, and tries again', async () => {
    await boot()
    const stuck = FakeSocket.all[0]!
    stuck.onopen?.()

    await vi.advanceTimersByTimeAsync(9_999)
    expect(stuck.closed).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(stuck.closed).toBe(true)

    await vi.advanceTimersByTimeAsync(1_000)
    expect(FakeSocket.all).toHaveLength(2)
  })

  it('does not let a dropped socket\'s late close clear the new connection', async () => {
    await boot()
    const first = FakeSocket.all[0]!
    await ready(first)
    await vi.advanceTimersByTimeAsync(30_000)
    await vi.advanceTimersByTimeAsync(1_000)
    const second = FakeSocket.all[1]!
    await ready(second)

    first.onclose?.()
    await vi.advanceTimersByTimeAsync(5_000)

    expect(status).toBe('connected')
    expect(FakeSocket.all).toHaveLength(2)
  })

  it('connects from an alarm when a retry timer was lost, and leaves a live connection alone', async () => {
    await boot()
    expect(alarmsCreate).toHaveBeenCalledWith('jclaw-reconnect', { periodInMinutes: 0.5 })
    const first = FakeSocket.all[0]!
    await ready(first)

    alarmHandler?.({ name: 'jclaw-reconnect' })
    await vi.advanceTimersByTimeAsync(0)
    expect(FakeSocket.all).toHaveLength(1)

    first.onclose?.()
    vi.clearAllTimers()
    await vi.advanceTimersByTimeAsync(60_000)
    expect(FakeSocket.all).toHaveLength(1)

    alarmHandler?.({ name: 'jclaw-reconnect' })
    await vi.advanceTimersByTimeAsync(0)
    expect(FakeSocket.all).toHaveLength(2)
  })

  it('ignores an alarm that is not its own', async () => {
    await boot()
    FakeSocket.all[0]!.onclose?.()
    vi.clearAllTimers()
    alarmHandler?.({ name: 'someone-else' })
    await vi.advanceTimersByTimeAsync(0)
    expect(FakeSocket.all).toHaveLength(1)
  })
})
