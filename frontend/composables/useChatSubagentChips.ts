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
}

interface SubagentRunRow {
  id: number
  label?: string | null
  childAgentName: string | null
  childAgentId: number | null
  childConversationId: number | null
  status: SubagentRunStatus
}

export const SUBAGENT_CHIP_POLL_MS = 5000
export const SUBAGENT_CHIP_RUN_LIMIT = 100

/**
 * Every subagent run the open conversation spawned, plus the client-only closed
 * and expanded chip state. Spawns and endings arrive on the event bus; the poll
 * is a fallback that runs only while a visible chip is still RUNNING.
 */
export function useChatSubagentChips(
  selectedConvoId: Ref<number | null>,
  streaming: Ref<boolean>,
): {
  chips: ComputedRef<SubagentChip[]>
  allRunsTotal: Ref<number | null>
  expandedIds: Ref<Set<number>>
  toggleExpanded: (id: number) => void
  closeChip: (id: number) => void
} {
  const runs = ref<SubagentChip[]>([])
  const allRunsTotal = ref<number | null>(null)
  const closedIds = ref(new Set<number>())
  const expandedIds = ref(new Set<number>())
  let latestRequest = 0
  let timer: ReturnType<typeof setInterval> | undefined

  const chips = computed(() => runs.value.filter(r => !closedIds.value.has(r.id)))
  const anyRunning = computed(() => chips.value.some(r => r.status === 'RUNNING'))

  async function refresh() {
    const request = ++latestRequest
    const convoId = selectedConvoId.value
    if (!convoId) {
      runs.value = []
      allRunsTotal.value = null
      return
    }
    let rows: SubagentRunRow[]
    let total: number
    try {
      // Newest first, so a conversation past the limit loses its oldest runs, never the ones still going.
      const res = await $fetch.raw<SubagentRunRow[]>('/api/subagent-runs', {
        query: { parentConversationId: convoId, sort: 'id', dir: 'desc', limit: SUBAGENT_CHIP_RUN_LIMIT },
      })
      rows = res._data ?? []
      total = Number.parseInt(res.headers.get('x-total-count') ?? '', 10)
    }
    catch (e) {
      console.error('Failed to load subagent runs:', e)
      return
    }
    // An event and a poll can overlap, and the operator can switch conversations mid-request.
    if (request !== latestRequest || selectedConvoId.value !== convoId) return
    allRunsTotal.value = total > rows.length ? total : null
    // Inline runs write into this same conversation and already render in its transcript.
    runs.value = [...rows].reverse().flatMap(r =>
      r.childConversationId != null && r.childConversationId !== convoId
        ? [{
            id: r.id,
            label: r.label ?? null,
            childAgentName: r.childAgentName,
            childAgentId: r.childAgentId,
            childConversationId: r.childConversationId,
            status: r.status,
          }]
        : [],
    )
  }

  function toggleExpanded(id: number) {
    const next = new Set(expandedIds.value)
    if (!next.delete(id)) next.add(id)
    expandedIds.value = next
  }

  function closeChip(id: number) {
    closedIds.value = new Set(closedIds.value).add(id)
    if (expandedIds.value.has(id)) toggleExpanded(id)
  }

  watch(selectedConvoId, () => {
    runs.value = []
    allRunsTotal.value = null
    closedIds.value = new Set()
    expandedIds.value = new Set()
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

  return { chips, allRunsTotal, expandedIds, toggleExpanded, closeChip }
}
