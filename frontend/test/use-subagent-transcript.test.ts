import { describe, it, expect, vi, afterEach } from 'vitest'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, mount } from '@vue/test-utils'
import { defineComponent, h, ref } from 'vue'
import type { Message } from '~/types/api'
import {
  useSubagentTranscript,
  type SubagentRunStatus,
  type UseSubagentTranscript,
} from '~/composables/useSubagentTranscript'

afterEach(() => {
  vi.useRealTimers()
})

function row(id: number, o: Record<string, unknown> = {}) {
  return { id, role: 'assistant', content: `reply ${id}`, createdAt: '2026-09-13T00:00:00Z', ...o }
}

// Each test serves its own conversation id: the transcript cache is module-level by design.
function serve(convoId: number, initial: unknown[]) {
  const server = { rows: [...initial], calls: 0 }
  registerEndpoint(`/api/conversations/${convoId}/messages`, () => {
    server.calls++
    return structuredClone(server.rows)
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

  it('fetches a finished run only when the cache lacks its final messages', async () => {
    const done = serve(108, [row(1)])
    const firstDone = mountTranscript(108, 'COMPLETED')
    await vi.waitFor(() => expect(firstDone.api.loaded.value).toBe(true))
    firstDone.wrapper.unmount()
    const againDone = mountTranscript(108, 'COMPLETED')
    await flushPromises()
    expect(done.calls).toBe(1)
    expect(ids(againDone.api)).toEqual([1])
    againDone.wrapper.unmount()

    // Cached while RUNNING, then the run ended while no panel was mounted to see it.
    const ended = serve(109, [row(1)])
    const running = mountTranscript(109, 'RUNNING')
    await vi.waitFor(() => expect(running.api.loaded.value).toBe(true))
    running.wrapper.unmount()
    ended.rows.push(row(2))
    const failed = mountTranscript(109, 'FAILED')
    await vi.waitFor(() => expect(ids(failed.api)).toEqual([1, 2]))
    expect(ended.calls).toBe(2)
    failed.wrapper.unmount()
  })
})
