import {
  onMounted,
  onUnmounted,
  ref,
  shallowRef,
  toValue,
  triggerRef,
  watch,
  type MaybeRefOrGetter,
  type Ref,
  type ShallowRef,
} from 'vue'
import { hydrateToolCalls } from '~/utils/tool-calls'
import { initCollapsedState } from '~/utils/thinking'
import type { Message, MessageAttachment, ToolCall } from '~/types/api'
import type { SubagentRunStatus } from '~/composables/useChatSubagentChips'

/**
 * A subagent run's child transcript for the panel an expanded chip shows (JCLAW-1205): loaded
 * once, refetched every 5 s while the run is RUNNING and the tab is visible, fetched once more
 * when the run ends so its last messages land, and merged by server id.
 */
export interface UseSubagentTranscript {
  messages: ShallowRef<Message[]>
  loaded: Ref<boolean>
  failed: Ref<boolean>
}

interface CachedTranscript {
  messages: ShallowRef<Message[]>
  loaded: Ref<boolean>
  /** A fetch began after the run ended, so the cache already holds its last messages. */
  settled: boolean
}

const POLL_INTERVAL_MS = 5000

// Module-level because collapsing a chip unmounts its panel, and a re-expand must show what was loaded.
const cache = new Map<number, CachedTranscript>()

function cachedTranscript(id: number): CachedTranscript {
  let entry = cache.get(id)
  if (!entry) {
    entry = { messages: shallowRef<Message[]>([]), loaded: ref(false), settled: false }
    cache.set(id, entry)
  }
  return entry
}

function applyToolCallDefaults(m: Message): void {
  if (!m.toolCalls?.length) return
  m.toolCallsCollapsed = true
  for (let i = 0; i < m.toolCalls.length; i++) {
    m.toolCalls[i]!._expanded = i === m.toolCalls.length - 1
  }
}

function sameToolCalls(a: ToolCall[], b: ToolCall[]): boolean {
  return a.length === b.length && a.every((tc, i) => tc.id === b[i]!.id && tc.resultText === b[i]!.resultText)
}

function sameAttachments(a: MessageAttachment[], b: MessageAttachment[]): boolean {
  return a.length === b.length && a.every((att, i) => att.uuid === b[i]!.uuid)
}

// Hydration hangs a turn's calls on its last row so far, so between two polls a row already shown
// can gain a call's result or hand its calls on to a later row; left stale, they render twice.
function syncHydratedFields(shown: Message, fresh: Message): boolean {
  let changed = false
  const prevCalls = shown.toolCalls ?? []
  const nextCalls = fresh.toolCalls ?? []
  if (!sameToolCalls(prevCalls, nextCalls)) {
    const expanded = new Map(prevCalls.map(tc => [tc.id, tc._expanded]))
    nextCalls.forEach((tc, i) => {
      tc._expanded = expanded.get(tc.id) ?? i === nextCalls.length - 1
    })
    if (!prevCalls.length) shown.toolCallsCollapsed = true
    shown.toolCalls = nextCalls
    changed = true
  }
  const prevAttachments = shown.attachments ?? []
  const nextAttachments = fresh.attachments ?? []
  if (!sameAttachments(prevAttachments, nextAttachments)) {
    shown.attachments = nextAttachments
    changed = true
  }
  return changed
}

function merge(entry: CachedTranscript, fresh: Message[]): void {
  hydrateToolCalls(fresh as unknown as Array<Record<string, unknown>>)
  const freshById = new Map<number, Message>()
  for (const m of fresh) {
    if (typeof m.id === 'number') freshById.set(m.id, m)
  }
  const shown = entry.messages.value
  let changed = false
  for (const m of shown) {
    const match = typeof m.id === 'number' ? freshById.get(m.id) : undefined
    if (match && syncHydratedFields(m, match)) changed = true
  }
  const shownIds = new Set(shown.map(m => m.id))
  const additions = fresh.filter(m => typeof m.id === 'number' && !shownIds.has(m.id))
  additions.forEach(applyToolCallDefaults)
  // Additions only: re-collapsing every row would fold a thinking card the reader just opened.
  initCollapsedState(additions)
  if (additions.length) entry.messages.value = [...shown, ...additions]
  else if (changed) triggerRef(entry.messages)
}

export function useSubagentTranscript(
  childConversationId: number,
  status: MaybeRefOrGetter<SubagentRunStatus>,
): UseSubagentTranscript {
  const entry = cachedTranscript(childConversationId)
  const failed = ref(false)
  let timer: ReturnType<typeof setInterval> | undefined
  let inFlight: Promise<void> | null = null

  async function fetchOnce(): Promise<void> {
    const runEnded = toValue(status) !== 'RUNNING'
    try {
      const fresh = await $fetch<Message[]>(`/api/conversations/${childConversationId}/messages`) ?? []
      merge(entry, fresh)
      entry.loaded.value = true
      failed.value = false
      if (runEnded) entry.settled = true
    }
    catch (e) {
      console.error('Failed to load subagent transcript:', e)
      failed.value = true
    }
  }

  // Waits out a fetch already under way: the final fetch must start after the run ended, not join one that began before.
  async function load(): Promise<void> {
    while (inFlight) await inFlight
    inFlight = fetchOnce().finally(() => {
      inFlight = null
    })
    await inFlight
  }

  function startPolling() {
    timer ??= setInterval(() => {
      if (inFlight || document.hidden) return
      void load()
    }, POLL_INTERVAL_MS)
  }

  function stopPolling() {
    clearInterval(timer)
    timer = undefined
  }

  watch(() => toValue(status), (now, was) => {
    if (now === 'RUNNING') {
      startPolling()
      return
    }
    stopPolling()
    if (was === 'RUNNING') void load()
  })

  onMounted(() => {
    const running = toValue(status) === 'RUNNING'
    if (running) startPolling()
    if (running || !entry.settled) void load()
  })

  onUnmounted(stopPolling)

  return { messages: entry.messages, loaded: entry.loaded, failed }
}
