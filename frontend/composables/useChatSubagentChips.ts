import { computed, onUnmounted, ref, watch, type ComputedRef, type Ref } from 'vue'
import { useEventBus } from '~/composables/useEventBus'

export type SubagentRunStatus = 'RUNNING' | 'COMPLETED' | 'FAILED' | 'KILLED' | 'TIMEOUT'

/** One chip; also the `run` prop of ChatSubagentStack's `expanded` slot. */
export interface SubagentChip {
  id: number
  label: string | null
  childAgentName: string | null
  childAgentId: number | null
  childConversationId: number
  status: SubagentRunStatus
  /** ISO instants from the run row; a RUNNING run has no endedAt. */
  startedAt: string | null
  endedAt: string | null
}

interface SubagentRunRow {
  id: number
  label?: string | null
  childAgentName: string | null
  childAgentId: number | null
  childConversationId: number | null
  status: SubagentRunStatus
  startedAt?: string | null
  endedAt?: string | null
}

export const SUBAGENT_CHIP_POLL_MS = 5000
export const SUBAGENT_CHIP_RUN_LIMIT = 100

/**
 * Every subagent run the open conversation spawned, plus the client-only
 * expanded chip state. Spawns and endings arrive on the event bus; the poll
 * is a fallback that runs only while a chip is still RUNNING.
 */
export function useChatSubagentChips(
  selectedConvoId: Ref<number | null>,
  streaming: Ref<boolean>,
): {
  chips: ComputedRef<SubagentChip[]>
  runsTotal: Ref<number>
  expandedId: Ref<number | null>
  toggleExpanded: (id: number) => void
} {
  const runs = ref<SubagentChip[]>([])
  // Counts the inline runs that get no chip, so it matches the Subagents page the header links to.
  const runsTotal = ref(0)
  // One transcript at a time: each panel can take half the viewport.
  const expandedId = ref<number | null>(null)
  let latestRequest = 0
  let timer: ReturnType<typeof setInterval> | undefined

  const chips = computed(() => runs.value)
  const anyRunning = computed(() => chips.value.some(r => r.status === 'RUNNING'))

  async function refresh() {
    const request = ++latestRequest
    const convoId = selectedConvoId.value
    if (!convoId) {
      runs.value = []
      runsTotal.value = 0
      return
    }
    let newest: SubagentRunRow[]
    let running: SubagentRunRow[]
    let total: number
    try {
      // The newest window drops old runs past the limit, still-RUNNING ones included, so those are fetched on their own.
      const query = { parentConversationId: convoId, sort: 'id', dir: 'desc', limit: SUBAGENT_CHIP_RUN_LIMIT }
      const [res, runningRes] = await Promise.all([
        $fetch.raw<SubagentRunRow[]>('/api/subagent-runs', { query }),
        $fetch<SubagentRunRow[]>('/api/subagent-runs', { query: { ...query, status: 'RUNNING' } }),
      ])
      newest = res._data ?? []
      running = runningRes ?? []
      total = Number.parseInt(res.headers.get('x-total-count') ?? '', 10)
    }
    catch (e) {
      console.error('Failed to load subagent runs:', e)
      return
    }
    // An event and a poll can overlap, and the operator can switch conversations mid-request.
    if (request !== latestRequest || selectedConvoId.value !== convoId) return
    const rows = [...new Map([...running, ...newest].map(r => [r.id, r])).values()].sort((a, b) => a.id - b.id)
    runsTotal.value = Number.isFinite(total) ? total : rows.length
    // Inline runs write into this same conversation and already render in its transcript.
    runs.value = rows.flatMap(r =>
      r.childConversationId != null && r.childConversationId !== convoId
        ? [{
            id: r.id,
            label: r.label ?? null,
            childAgentName: r.childAgentName,
            childAgentId: r.childAgentId,
            childConversationId: r.childConversationId,
            status: r.status,
            startedAt: r.startedAt ?? null,
            endedAt: r.endedAt ?? null,
          }]
        : [],
    )
  }

  function toggleExpanded(id: number) {
    expandedId.value = expandedId.value === id ? null : id
  }

  watch(selectedConvoId, () => {
    runs.value = []
    runsTotal.value = 0
    expandedId.value = null
    void refresh()
  }, { immediate: true })

  watch(streaming, (now, was) => {
    if (was && !now) void refresh()
  })

  function onRunEvent(data: unknown) {
    const parentId = (data as { parentConversationId?: number | null } | null)?.parentConversationId
    if (parentId != null && parentId === selectedConvoId.value) void refresh()
  }
  const { onEvent, onOpen } = useEventBus()
  onEvent('subagentrun.started', onRunEvent)
  onEvent('subagentrun.ended', onRunEvent)
  // The bus has no replay, so a spawn published while the stream was down or the tab slept is refetched here.
  onOpen(() => void refresh())
  function onVisibilityChange() {
    if (!document.hidden) void refresh()
  }
  document.addEventListener('visibilitychange', onVisibilityChange)

  watch(anyRunning, (running) => {
    clearInterval(timer)
    timer = running
      ? setInterval(() => {
          if (!document.hidden) void refresh()
        }, SUBAGENT_CHIP_POLL_MS)
      : undefined
  }, { immediate: true })

  onUnmounted(() => {
    clearInterval(timer)
    document.removeEventListener('visibilitychange', onVisibilityChange)
  })

  return { chips, runsTotal, expandedId, toggleExpanded }
}
