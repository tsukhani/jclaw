import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import {
  SUBAGENT_CHIP_POLL_MS,
  useChatSubagentChips,
  type SubagentRunStatus,
} from '~/composables/useChatSubagentChips'

const bus = vi.hoisted(() => ({
  handlers: [] as Array<{ type: string, handler: (data: unknown, type: string) => void }>,
  openHandlers: [] as Array<() => void>,
}))

vi.mock('~/composables/useEventBus', () => ({
  useEventBus: () => ({
    on: vi.fn(),
    off: vi.fn(),
    onEvent: (type: string, handler: (data: unknown, type: string) => void) => {
      bus.handlers.push({ type, handler })
    },
    onOpen: (handler: () => void) => {
      bus.openHandlers.push(handler)
    },
  }),
}))

function emitBus(type: string, data: unknown) {
  for (const entry of bus.handlers) {
    if (entry.type === type) entry.handler(data, type)
  }
}

function run(id: number, childConversationId: number | null, status: SubagentRunStatus = 'RUNNING',
  label: string | null = null, parentConversationId = 5) {
  return { id, label, childAgentId: 90 + id, childAgentName: `main-sub-${id}`, parentConversationId,
    childConversationId, mode: 'session', status, startedAt: '2026-09-13T09:44:41Z',
    endedAt: status === 'RUNNING' ? null : '2026-09-13T09:45:10Z', outcome: status === 'RUNNING' ? null : 'done' }
}

function runEvent(runId: number, parentConversationId: number | null, status: SubagentRunStatus = 'RUNNING') {
  return { runId, parentConversationId, childConversationId: 100 + runId, childAgentId: 90 + runId, status, label: null }
}

type RunRow = { id: number, status: SubagentRunStatus }

// The status=RUNNING queries, kept apart so `requests` counts one per refresh.
const runningRequests: URLSearchParams[] = []

/** Serves /api/subagent-runs per parent conversation, filtered, ordered and cut as the server does, and records each query. */
function serve(rowsFor: (parentConversationId: number) => RunRow[] | Promise<RunRow[]>) {
  const requests: URLSearchParams[] = []
  registerEndpoint('/api/subagent-runs', async (event) => {
    const url = new URL(String(event.node?.req?.url ?? event.path ?? ''), 'http://localhost')
    const status = url.searchParams.get('status')
    ;(status ? runningRequests : requests).push(url.searchParams)
    const rows = (await rowsFor(Number(url.searchParams.get('parentConversationId')))).filter(r => !status || r.status === status)
    const desc = url.searchParams.get('dir') === 'desc'
    rows.sort((a, b) => (desc ? b.id - a.id : a.id - b.id))
    event.node.res.setHeader('x-total-count', String(rows.length))
    return rows.slice(0, Number(url.searchParams.get('limit')) || 100)
  })
  return requests
}

async function settle() {
  for (let i = 0; i < 5; i++) await flushPromises()
}

const mounted: Array<{ unmount: () => void }> = []

async function mountChips(convoId: number | null = 5) {
  const selectedConvoId = ref<number | null>(convoId)
  const streaming = ref(false)
  let api!: ReturnType<typeof useChatSubagentChips>
  mounted.push(await mountSuspended(
    defineComponent({
      setup() {
        api = useChatSubagentChips(selectedConvoId, streaming)
        return () => h('div')
      },
    }),
  ))
  await settle()
  const ids = () => api.chips.value.map(c => c.id)
  return { ...api, ids, selectedConvoId, streaming }
}

beforeEach(() => {
  bus.handlers.length = 0
  bus.openHandlers.length = 0
  runningRequests.length = 0
})

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
  vi.useRealTimers()
})

describe('useChatSubagentChips', () => {
  it('asks for the conversation\'s newest runs, shows them in spawn order and keeps only those with their own transcript', async () => {
    const requests = serve(() => [run(1, 6), run(2, 5), run(3, null), run(4, 7, 'COMPLETED', 'Summarise the logs')])
    const { chips, runsTotal } = await mountChips()

    expect(requests[0]!.get('parentConversationId')).toBe('5')
    expect(requests[0]!.get('sort')).toBe('id')
    expect(requests[0]!.get('dir')).toBe('desc')
    expect(requests[0]!.get('limit')).toBe('100')
    expect(requests[0]!.has('status')).toBe(false)
    expect(chips.value).toEqual([
      { id: 1, label: null, childAgentName: 'main-sub-1', childAgentId: 91, childConversationId: 6, status: 'RUNNING',
        startedAt: '2026-09-13T09:44:41Z', endedAt: null, outcome: null },
      { id: 4, label: 'Summarise the logs', childAgentName: 'main-sub-4', childAgentId: 94, childConversationId: 7, status: 'COMPLETED',
        startedAt: '2026-09-13T09:44:41Z', endedAt: '2026-09-13T09:45:10Z', outcome: 'done' },
    ])
    // The two runs without a chip of their own still count.
    expect(runsTotal.value).toBe(4)
  })

  it('keeps the newest 100 runs of a longer conversation, including one just spawned, and reports the full count', async () => {
    let rows = Array.from({ length: 100 }, (_, i) => run(i + 1, 1000 + i, 'COMPLETED'))
    serve(() => rows)
    const { ids, runsTotal } = await mountChips()
    expect(runsTotal.value).toBe(100)

    rows = [...rows, run(101, 1100)]
    emitBus('subagentrun.started', runEvent(101, 5))
    await vi.waitFor(() => expect(ids().at(-1)).toBe(101))
    expect(ids()).toHaveLength(100)
    expect(ids()[0]).toBe(2)
    expect(runsTotal.value).toBe(101)
  })

  it('keeps a chip for an old run still RUNNING once 100 newer runs push it out of the newest window', async () => {
    serve(() => [run(1, 1000), ...Array.from({ length: 100 }, (_, i) => run(i + 2, 1001 + i, 'COMPLETED'))])
    const { ids, runsTotal } = await mountChips()

    expect(runningRequests).toHaveLength(1)
    expect(runningRequests[0]!.get('parentConversationId')).toBe('5')
    expect(runningRequests[0]!.get('status')).toBe('RUNNING')
    expect(ids()).toHaveLength(101)
    expect(ids()[0]).toBe(1)
    expect(ids().at(-1)).toBe(101)
    expect(runsTotal.value).toBe(101)
  })

  it('refetches when the event stream reconnects or the tab becomes visible, recovering a spawn whose event was missed', async () => {
    let rows = [run(1, 6, 'COMPLETED')]
    const requests = serve(() => rows)
    const { ids } = await mountChips()

    rows = [run(1, 6, 'COMPLETED'), run(2, 7)]
    for (const handler of bus.openHandlers) handler()
    await vi.waitFor(() => expect(ids()).toEqual([1, 2]))

    rows = [...rows, run(3, 8)]
    document.dispatchEvent(new Event('visibilitychange'))
    await vi.waitFor(() => expect(ids()).toEqual([1, 2, 3]))
    expect(requests).toHaveLength(3)
  })

  it('picks up a spawn from a run event for the open conversation while no turn streams, ignoring other conversations', async () => {
    let rows = [run(1, 6)]
    const requests = serve(() => rows)
    const { ids, streaming } = await mountChips()
    rows = [run(1, 6), run(2, 7)]

    emitBus('subagentrun.started', runEvent(2, 9))
    emitBus('subagentrun.started', runEvent(2, null))
    await settle()
    expect(requests).toHaveLength(1)
    expect(ids()).toEqual([1])

    emitBus('subagentrun.started', runEvent(2, 5))
    await vi.waitFor(() => expect(ids()).toEqual([1, 2]))
    expect(streaming.value).toBe(false)
  })

  it('turns a Running chip to its terminal status on the ended event and keeps it expanded', async () => {
    let status: SubagentRunStatus = 'RUNNING'
    serve(() => [run(1, 6, status)])
    const { chips, expandedId, toggleExpanded } = await mountChips()
    toggleExpanded(1)

    status = 'COMPLETED'
    emitBus('subagentrun.ended', runEvent(1, 5, 'COMPLETED'))
    await vi.waitFor(() => expect(chips.value[0]!.status).toBe('COMPLETED'))
    expect(expandedId.value).toBe(1)
  })

  it('keeps one chip expanded at a time', async () => {
    serve(() => [run(1, 6), run(2, 7)])
    const { expandedId, toggleExpanded } = await mountChips()

    toggleExpanded(1)
    expect(expandedId.value).toBe(1)
    toggleExpanded(2)
    expect(expandedId.value).toBe(2)
    toggleExpanded(2)
    expect(expandedId.value).toBeNull()
  })

  it('collapses the expanded chip when the conversation is loaded again', async () => {
    serve(convoId => (convoId === 5 ? [run(1, 6), run(2, 7)] : []))
    const { ids, expandedId, toggleExpanded, selectedConvoId } = await mountChips()
    toggleExpanded(2)

    selectedConvoId.value = 11
    await vi.waitFor(() => expect(ids()).toEqual([]))
    selectedConvoId.value = 5
    await vi.waitFor(() => expect(ids()).toEqual([1, 2]))
    expect(expandedId.value).toBeNull()
  })

  it('ignores a response that lands after the conversation changed', async () => {
    let release!: () => void
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    let staleServed = false
    serve(async (convoId) => {
      if (convoId !== 5) return [run(2, 12, 'RUNNING', null, 11)]
      await gate
      staleServed = true
      return [run(1, 6)]
    })
    const { ids, selectedConvoId } = await mountChips()

    selectedConvoId.value = 11
    await vi.waitFor(() => expect(ids()).toEqual([2]))
    release()
    await vi.waitFor(() => expect(staleServed).toBe(true))
    await settle()
    expect(ids()).toEqual([2])
  })

  it('refetches when a turn ends', async () => {
    let rows = [run(1, 6)]
    serve(() => rows)
    const { chips, streaming } = await mountChips()

    rows = [run(1, 6, 'COMPLETED')]
    streaming.value = true
    await nextTick()
    streaming.value = false
    // The 5s poll can't fire inside waitFor's 1s budget, so only the turn-end refetch passes this.
    await vi.waitFor(() => expect(chips.value[0]!.status).toBe('COMPLETED'))
  })

  it('polls while a chip is RUNNING and stops once every run has finished', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    let status: SubagentRunStatus = 'RUNNING'
    const requests = serve(() => [run(1, 6, status)])
    const { chips } = await mountChips()
    expect(requests).toHaveLength(1)

    vi.advanceTimersByTime(SUBAGENT_CHIP_POLL_MS)
    await settle()
    expect(requests).toHaveLength(2)

    status = 'COMPLETED'
    vi.advanceTimersByTime(SUBAGENT_CHIP_POLL_MS)
    await settle()
    expect(chips.value[0]!.status).toBe('COMPLETED')

    vi.advanceTimersByTime(SUBAGENT_CHIP_POLL_MS * 3)
    await settle()
    expect(requests).toHaveLength(3)
  })

  it('never polls a conversation whose runs have all finished', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const requests = serve(() => [run(1, 6, 'COMPLETED'), run(2, 7, 'KILLED')])
    await mountChips()

    vi.advanceTimersByTime(SUBAGENT_CHIP_POLL_MS * 3)
    await settle()
    expect(requests).toHaveLength(1)
  })

  it('skips a poll while the tab is hidden', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const requests = serve(() => [run(1, 6)])
    await mountChips()

    Object.defineProperty(document, 'hidden', { configurable: true, get: () => true })
    try {
      vi.advanceTimersByTime(SUBAGENT_CHIP_POLL_MS)
      await settle()
      expect(requests).toHaveLength(1)
    }
    finally {
      delete (document as { hidden?: boolean }).hidden
    }

    vi.advanceTimersByTime(SUBAGENT_CHIP_POLL_MS)
    await settle()
    expect(requests).toHaveLength(2)
  })

  it('clears the chips when the conversation closes', async () => {
    serve(() => [run(1, 6)])
    const { ids, runsTotal, selectedConvoId } = await mountChips()
    expect(ids()).toEqual([1])
    expect(runsTotal.value).toBe(1)

    selectedConvoId.value = null
    await settle()
    expect(ids()).toEqual([])
    expect(runsTotal.value).toBe(0)
  })
})
