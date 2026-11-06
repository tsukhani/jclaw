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
import { fetchConversationMessages } from '~/utils/conversation-messages'
import { reconcileWindow } from '~/utils/message-reconcile'
import type { Message } from '~/types/api'
import type { SubagentRunStatus } from '~/composables/useChatSubagentChips'

/**
 * A subagent run's child transcript for the panel an expanded chip shows (JCLAW-1205): loaded in
 * full on every mount, refetched from a recent offset every 5 s while the run is RUNNING and the
 * tab is visible, fetched in full once more when the run ends so its last messages land, and
 * merged by server id.
 */
export interface UseSubagentTranscript {
  messages: ShallowRef<Message[]>
  loaded: Ref<boolean>
  failed: Ref<boolean>
  /** Bumped when a fetch changes the transcript; a collapse toggle does not bump it. */
  revision: Ref<number>
  retry: () => Promise<void>
}

interface CachedTranscript {
  messages: ShallowRef<Message[]>
  loaded: Ref<boolean>
  revision: Ref<number>
}

const POLL_INTERVAL_MS = 5000
// Module-level because collapsing a chip unmounts its panel, and a re-expand must show what was loaded.
const cache = new Map<number, CachedTranscript>()

function cachedTranscript(id: number): CachedTranscript {
  let entry = cache.get(id)
  if (!entry) {
    entry = { messages: shallowRef<Message[]>([]), loaded: ref(false), revision: ref(0) }
    cache.set(id, entry)
  }
  return entry
}

// Hydration carries calls forward to the next assistant row with content, so a window can only begin
// just after such a row, and no later than the first row still waiting on a call's result. A run made
// only of tool calls has no content row until it ends (JCLAW-1209), so a completed result anchors a
// window too; either way the window starts no later than the row the calls aggregate onto, which must
// be re-hydrated or the calls a later row takes over would render twice.
function incrementalStart(shown: Message[]): number {
  let limit = shown.findIndex(m => m.toolCalls?.some(tc => tc.resultText == null))
  if (limit < 0) limit = shown.length
  for (let i = shown.length - 1; i >= 0; i--) {
    if (shown[i]!.toolCalls?.length) {
      limit = Math.min(limit, i)
      break
    }
  }
  for (let i = limit; i > 0; i--) {
    const before = shown[i - 1]!
    if (before.role === 'assistant' && before.content) return i
  }
  for (let i = limit; i > 0; i--) {
    if (shown[i - 1]!.role === 'tool') return i
  }
  return 0
}

// The endpoint's offset counts every row, tool rows included, in the order the cache holds them;
// a deleted row or two rows sharing a timestamp breaks that, which the ids reveal.
function linesUp(shown: Message[], rows: Message[], offset: number): boolean {
  return rows.length > 0 && rows.every((m, i) => offset + i >= shown.length || m.id === shown[offset + i]!.id)
}

// `fresh` holds the server's rows from cache index `from` on; from 0 it replaces the list in server order.
function merge(entry: CachedTranscript, fresh: Message[], from: number): void {
  const prev = entry.messages.value
  const { rows, changed } = reconcileWindow(prev, fresh, from > 0)
  const next = [...prev.slice(0, from), ...rows]
  if (next.length !== prev.length || next.some((m, i) => m !== prev[i])) entry.messages.value = next
  else if (changed) triggerRef(entry.messages)
  else return
  entry.revision.value++
}

export function useSubagentTranscript(
  childConversationId: number,
  status: MaybeRefOrGetter<SubagentRunStatus>,
): UseSubagentTranscript {
  const entry = cachedTranscript(childConversationId)
  const failed = ref(false)
  let timer: ReturnType<typeof setInterval> | undefined
  let inFlight: Promise<void> | null = null
  let active = true
  const isActive = () => active

  async function fetchOnce(full: boolean): Promise<void> {
    try {
      const start = full ? 0 : incrementalStart(entry.messages.value)
      if (start > 0) {
        // One row before the window anchors the offset to a row the cache already holds.
        const rows = await fetchConversationMessages(childConversationId, start - 1, isActive)
        if (!rows) return
        if (linesUp(entry.messages.value, rows, start - 1)) {
          merge(entry, rows.slice(1), start)
          failed.value = false
          return
        }
        if (!active) return
      }
      const rows = await fetchConversationMessages(childConversationId, 0, isActive)
      if (!rows) return
      merge(entry, rows, 0)
      entry.loaded.value = true
      failed.value = false
    }
    catch (e) {
      console.error('Failed to load subagent transcript:', e)
      failed.value = true
    }
  }

  // Waits out a fetch already under way: the final fetch must start after the run ended, not join one that began before.
  async function load(full: boolean): Promise<void> {
    while (inFlight) await inFlight
    if (!active) return
    inFlight = fetchOnce(full).finally(() => {
      inFlight = null
    })
    await inFlight
  }

  function startPolling() {
    timer ??= setInterval(() => {
      if (inFlight || document.hidden) return
      void load(false)
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
    if (was === 'RUNNING') void load(true)
  })

  // Always fetch: a killed or timed-out child can still persist the reply that was in flight.
  onMounted(() => {
    if (toValue(status) === 'RUNNING') startPolling()
    void load(true)
  })

  onUnmounted(() => {
    active = false
    stopPolling()
  })

  // `failed` stays set until a fetch succeeds, so a Retry control stays mounted while it runs.
  function retry(): Promise<void> {
    return load(true)
  }

  return { messages: entry.messages, loaded: entry.loaded, failed, revision: entry.revision, retry }
}
