import { describe, it, expect, vi, afterEach } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import type { Message } from '~/types/api'
import type { SubagentRunStatus } from '~/composables/useChatSubagentChips'
import { useSubagentTranscript, type UseSubagentTranscript } from '~/composables/useSubagentTranscript'

afterEach(() => {
  vi.useRealTimers()
})

function row(id: number, o: Record<string, unknown> = {}) {
  return { id, role: 'assistant', content: `reply ${id}`, createdAt: '2026-09-13T00:00:00Z', ...o }
}

// Each test serves its own conversation id: the transcript cache is module-level by design.
function serve(convoId: number, initial: unknown[]) {
  const server = { rows: [...initial], calls: 0, offsets: [] as number[], gate: null as Promise<void> | null }
  registerEndpoint(`/api/conversations/${convoId}/messages`, async (event) => {
    if (server.gate) await server.gate
    server.calls++
    const { getQuery } = await import('h3')
    const query = getQuery(event)
    // The endpoint's paging: 200 rows unless asked, 500 at most, oldest first.
    const limit = Math.min(Number(query.limit) || 200, 500)
    const offset = Number(query.offset) || 0
    server.offsets.push(offset)
    return structuredClone(server.rows.slice(offset, offset + limit))
  })
  return server
}

function mountTranscript(convoId: number, initial: SubagentRunStatus) {
  const status = ref<SubagentRunStatus>(initial)
  let api!: UseSubagentTranscript
  const wrapper = mount(defineComponent({
    setup() {
      api = useSubagentTranscript(convoId, status)
      return () => h('div')
    },
  }))
  return { wrapper, status, api }
}

function ids(api: UseSubagentTranscript) {
  return api.messages.value.map(m => m.id)
}

// Only the interval is faked, so the endpoint round trip still resolves on real timers.
function fakePollTimer() {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
}

describe('useSubagentTranscript', () => {
  it('hydrates tool calls and applies the chat page\'s collapse defaults on first load', async () => {
    serve(101, [
      row(1, { role: 'user', content: 'go' }),
      row(2, { content: null, toolCalls: [{ id: 'c1', function: { name: 'web_search', arguments: '{}' } }] }),
      row(3, { role: 'tool', content: 'found it', toolResults: 'c1' }),
      row(4, { content: 'done', reasoning: 'thinking hard' }),
    ])
    const { api, wrapper } = mountTranscript(101, 'COMPLETED')
    await vi.waitFor(() => expect(api.loaded.value).toBe(true))

    const last = api.messages.value[3] as Message
    expect(last.toolCalls).toMatchObject([{ id: 'c1', name: 'web_search', resultText: 'found it', _expanded: true }])
    expect(last.toolCallsCollapsed).toBe(true)
    expect(last.thinkingCollapsed).toBe(true)
    wrapper.unmount()
  })

  it('appends rows with a new server id every 5 s while RUNNING, keeping the rows already shown', async () => {
    fakePollTimer()
    const server = serve(102, [row(1)])
    const { api, wrapper } = mountTranscript(102, 'RUNNING')
    await vi.waitFor(() => expect(ids(api)).toEqual([1]))
    const first = api.messages.value[0]!
    first.thinkingCollapsed = false

    server.rows.push(row(2))
    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2]))
    expect(api.messages.value[0]).toBe(first)
    expect(first.thinkingCollapsed).toBe(false)

    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(server.calls).toBe(3))
    await flushPromises()
    expect(ids(api)).toEqual([1, 2])
    wrapper.unmount()
  })

  it('moves a turn\'s tool calls onto its later row instead of showing them twice', async () => {
    fakePollTimer()
    const server = serve(103, [
      row(1, { role: 'user', content: 'go' }),
      row(2, { content: null, toolCalls: [{ id: 'c1', function: { name: 'web_search', arguments: '{}' } }] }),
    ])
    const { api, wrapper } = mountTranscript(103, 'RUNNING')
    await vi.waitFor(() => expect(api.messages.value[1]?.toolCalls).toHaveLength(1))
    expect(api.messages.value[1]!.toolCalls![0]!.resultText).toBeNull()

    server.rows.push(row(3, { role: 'tool', content: 'found it', toolResults: 'c1' }))
    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(api.messages.value[1]!.toolCalls![0]!.resultText).toBe('found it'))

    server.rows.push(row(4, { content: 'done' }))
    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2, 3, 4]))
    const carrying = api.messages.value.filter(m => m.toolCalls?.some(tc => tc.id === 'c1'))
    expect(carrying.map(m => m.id)).toEqual([4])
    wrapper.unmount()
  })

  it('skips a poll while the document is hidden', async () => {
    fakePollTimer()
    const server = serve(104, [row(1)])
    const { api, wrapper } = mountTranscript(104, 'RUNNING')
    await vi.waitFor(() => expect(api.loaded.value).toBe(true))

    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true })
    try {
      vi.advanceTimersByTime(15_000)
      await flushPromises()
      expect(server.calls).toBe(1)
    }
    finally {
      Reflect.deleteProperty(document, 'hidden')
    }
    wrapper.unmount()
  })

  it('fetches once more when the run ends, then stops polling', async () => {
    fakePollTimer()
    const server = serve(105, [row(1)])
    const { api, status, wrapper } = mountTranscript(105, 'RUNNING')
    await vi.waitFor(() => expect(api.loaded.value).toBe(true))

    server.rows.push(row(2))
    status.value = 'COMPLETED'
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2]))
    expect(server.calls).toBe(2)

    vi.advanceTimersByTime(20_000)
    await flushPromises()
    expect(server.calls).toBe(2)
    wrapper.unmount()
  })

  it('stops polling on unmount', async () => {
    fakePollTimer()
    const server = serve(106, [row(1)])
    const { api, wrapper } = mountTranscript(106, 'RUNNING')
    await vi.waitFor(() => expect(api.loaded.value).toBe(true))

    wrapper.unmount()
    vi.advanceTimersByTime(20_000)
    await flushPromises()
    expect(server.calls).toBe(1)
  })

  it('shows the cached transcript at once on remount, then catches up while RUNNING', async () => {
    const server = serve(107, [row(1)])
    const first = mountTranscript(107, 'RUNNING')
    await vi.waitFor(() => expect(first.api.loaded.value).toBe(true))
    first.wrapper.unmount()

    server.rows.push(row(2))
    const second = mountTranscript(107, 'RUNNING')
    expect(second.api.loaded.value).toBe(true)
    expect(ids(second.api)).toEqual([1])
    await vi.waitFor(() => expect(ids(second.api)).toEqual([1, 2]))
    second.wrapper.unmount()
  })

  it('refetches a finished run on every mount, so a reply a killed child persisted late still lands', async () => {
    const server = serve(108, [row(1)])
    const first = mountTranscript(108, 'KILLED')
    await vi.waitFor(() => expect(first.api.loaded.value).toBe(true))
    first.wrapper.unmount()

    server.rows.push(row(2))
    const again = mountTranscript(108, 'KILLED')
    expect(ids(again.api)).toEqual([1])
    await vi.waitFor(() => expect(ids(again.api)).toEqual([1, 2]))
    expect(server.calls).toBe(2)
    again.wrapper.unmount()
  })

  it('reads every page of a long transcript and hydrates a turn that spans two pages', async () => {
    const rows = Array.from({ length: 1100 }, (_, i) => row(i + 1))
    rows[499] = row(500, { content: null, toolCalls: [{ id: 'c9', function: { name: 'web_search', arguments: '{}' } }] })
    rows[500] = row(501, { role: 'tool', content: 'paged result', toolResults: 'c9' })
    const server = serve(110, rows)
    const { api, wrapper } = mountTranscript(110, 'COMPLETED')

    await vi.waitFor(() => expect(api.messages.value).toHaveLength(1100))
    expect(server.calls).toBe(3)
    expect(api.messages.value.at(-1)!.id).toBe(1100)
    const carrying = api.messages.value.filter(m => m.toolCalls?.some(tc => tc.id === 'c9'))
    expect(carrying.map(m => m.id)).toEqual([502])
    expect(carrying[0]!.toolCalls![0]!.resultText).toBe('paged result')
    wrapper.unmount()
  })

  it('sends no request queued behind an in-flight poll once the panel has unmounted', async () => {
    const server = serve(111, [row(1)])
    let release!: () => void
    server.gate = new Promise((resolve) => {
      release = resolve
    })
    const { api, status, wrapper } = mountTranscript(111, 'RUNNING')
    await flushPromises()

    status.value = 'COMPLETED'
    await nextTick()
    wrapper.unmount()
    release()
    await vi.waitFor(() => expect(api.loaded.value).toBe(true))
    await new Promise(resolve => setTimeout(resolve, 50))
    expect(server.calls).toBe(1)
  })

  it('requests no further page once the panel unmounts part way through a long transcript, and merges none of it', async () => {
    const server = serve(112, Array.from({ length: 700 }, (_, i) => row(i + 1)))
    let release!: () => void
    server.gate = new Promise((resolve) => {
      release = resolve
    })
    const { api, wrapper } = mountTranscript(112, 'COMPLETED')
    await flushPromises()

    wrapper.unmount()
    release()
    await new Promise(resolve => setTimeout(resolve, 50))
    expect(server.calls).toBe(1)
    expect(api.messages.value).toHaveLength(0)
    expect(api.loaded.value).toBe(false)
  })

  it('polls from a recent offset rather than from the start once the transcript has loaded', async () => {
    fakePollTimer()
    const server = serve(113, [row(1, { role: 'user', content: 'go' }), row(2), row(3)])
    const { api, wrapper } = mountTranscript(113, 'RUNNING')
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2, 3]))

    server.rows.push(row(4))
    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2, 3, 4]))
    expect(server.offsets).toEqual([0, 2])
    wrapper.unmount()
  })

  it('starts a poll early enough to hydrate a result that arrives for a call shown earlier', async () => {
    fakePollTimer()
    const server = serve(114, [
      row(1, { role: 'user', content: 'go' }),
      row(2, { content: 'first I will look around' }),
      row(3, { content: 'searching now', toolCalls: [{ id: 'c1', function: { name: 'web_search', arguments: '{}' } }] }),
    ])
    const { api, wrapper } = mountTranscript(114, 'RUNNING')
    await vi.waitFor(() => expect(api.messages.value[2]?.toolCalls).toHaveLength(1))
    expect(api.messages.value[2]!.toolCalls![0]!.resultText).toBeNull()

    server.rows.push(row(4, { role: 'tool', content: 'found it', toolResults: 'c1' }), row(5, { content: 'done' }))
    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2, 3, 4, 5]))
    expect(server.offsets).toEqual([0, 1])
    expect(api.messages.value[2]!.toolCalls![0]!.resultText).toBe('found it')
    wrapper.unmount()
  })

  it('falls back to a full fetch when the rows at the offset are not the ones cached', async () => {
    fakePollTimer()
    const server = serve(115, [row(1), row(2), row(3)])
    const { api, wrapper } = mountTranscript(115, 'RUNNING')
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2, 3]))

    server.rows = [row(1), row(3), row(4)]
    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 3, 4]))
    expect(server.offsets).toEqual([0, 2, 0])
    wrapper.unmount()
  })

  // Two completed rounds before the poll: the calls aggregate onto the newest assistant row, and a
  // window may not start before it, so a shorter transcript can only ever be fetched whole.
  function toolOnlyRun() {
    return [
      row(1, { role: 'user', content: 'go' }),
      row(2, { content: null, toolCalls: [{ id: 'c1', function: { name: 'web_search', arguments: '{}' } }] }),
      row(3, { role: 'tool', content: 'first result', toolResults: 'c1' }),
      row(4, { content: null, toolCalls: [{ id: 'c2', function: { name: 'web_fetch', arguments: '{}' } }] }),
      row(5, { role: 'tool', content: 'second result', toolResults: 'c2' }),
    ]
  }

  it('polls a run made only of tool calls from a non-zero offset, merging as one full fetch would (JCLAW-1209)', async () => {
    fakePollTimer()
    const server = serve(116, toolOnlyRun())
    const { api, wrapper } = mountTranscript(116, 'RUNNING')
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2, 3, 4, 5]))

    server.rows.push(
      row(6, { content: null, toolCalls: [{ id: 'c3', function: { name: 'web_search', arguments: '{}' } }] }),
      row(7, { role: 'tool', content: 'third result', toolResults: 'c3' }),
    )
    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2, 3, 4, 5, 6, 7]))
    expect(server.offsets.slice(1).every(o => o > 0), `offsets were ${server.offsets}`).toBe(true)

    // Across every row, so a call left on the row it was handed off from shows up as a duplicate.
    const calls = api.messages.value.flatMap(m => m.toolCalls ?? [])
    expect(calls.map(tc => tc.id)).toEqual(['c1', 'c2', 'c3'])
    expect(calls.map(tc => tc.resultText)).toEqual(['first result', 'second result', 'third result'])
    wrapper.unmount()
  })

  it('hands a window\'s calls and the ones earlier polls aggregated to the content row that lands (JCLAW-1209)', async () => {
    fakePollTimer()
    const server = serve(117, toolOnlyRun())
    const { api, wrapper } = mountTranscript(117, 'RUNNING')
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2, 3, 4, 5]))

    server.rows.push(
      row(6, { content: null, toolCalls: [{ id: 'c3', function: { name: 'web_search', arguments: '{}' } }] }),
      row(7, { role: 'tool', content: 'third result', toolResults: 'c3' }),
      row(8, { content: 'done' }),
    )
    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(ids(api)).toEqual([1, 2, 3, 4, 5, 6, 7, 8]))
    expect(server.offsets.slice(1).every(o => o > 0), `offsets were ${server.offsets}`).toBe(true)

    const carrying = api.messages.value.filter(m => m.toolCalls?.length)
    expect(carrying).toHaveLength(1)
    expect(carrying[0]!.content).toBe('done')
    expect(carrying[0]!.toolCalls!.map(tc => tc.id)).toEqual(['c1', 'c2', 'c3'])
    wrapper.unmount()
  })
})
