import { computed, onMounted, onUnmounted, triggerRef, type ComputedRef, type Ref, type ShallowRef } from 'vue'
import { hydrateToolCalls } from '~/utils/tool-calls'
import { initCollapsedState } from '~/utils/thinking'
import { backfillServerIds } from '~/utils/message-reconcile'
import type { Message } from '~/types/api'

/**
 * JCLAW-270 async-spawn announce poller (JCLAW-690 stage 5b; behaviour
 * extracted verbatim from pages/chat.vue). After the parent's streaming turn
 * ends, the {@code subagent_announce} Message that closes an async spawn can
 * arrive seconds (or minutes) later — the SSE stream is already closed by
 * then, so the chat view has no reactive path to learn about it. Without a
 * refresh the announce sits in the DB and the user has to manually reload.
 *
 * Mirror {@code /subagents}'s "auto-refresh while RUNNING" pattern: while
 * the open conversation has at least one tool result reporting
 * {@code status:RUNNING} for a {@code run_id} that no {@code subagent_announce}
 * row has closed yet, poll {@code /api/conversations/{id}/messages} every
 * {@link ANNOUNCE_POLL_INTERVAL_MS}. Merge any newer rows by server id —
 * never clobber in-flight optimistic placeholders. Stops as soon as the
 * announce arrives or the page unmounts.
 *
 * Deliberately silent during streaming — the SSE path already keeps the
 * messages list reactive, so polling on top of it would race and risk
 * stomping the in-flight assistant bubble. The check runs every 5s
 * regardless, so within one cadence of stream end the loop kicks in.
 *
 * This is conversation-refresh, not display — a separate concern from
 * useChatSubagents (whose initSubagentCollapsedState it calls after merging
 * newly-arrived rows). Owns its own poll timer lifecycle.
 */
const ANNOUNCE_POLL_INTERVAL_MS = 5000

/**
 * Keep polling for this long after the most recent subagent_announce row
 * lands, even though {@link hasPendingAsyncAnnounce} flips false. Covers
 * the gap where {@link AgentRunner#runYieldResume} runs the parent's
 * resumed turn on the backend with no SSE stream to the chat tab — any
 * `message` tool calls or sync sub-agent activity inside the resumed
 * turn would otherwise sit in the DB until the user reloads. Three
 * minutes accommodates a typical Phase 4 import (Radarr scan + verify
 * + transmission cleanup, ~30-180s) plus headroom; long enough that
 * the user sees the full workflow inline, short enough that idle
 * conversations don't poll forever.
 */
const POST_ANNOUNCE_GRACE_MS = 180_000

/**
 * Grace window for polling after a {@code task_manager.createTask} call:
 * once a scheduled / recurring task is in the conversation's history, the
 * fire that lands on the server has no SSE channel back to this tab (the
 * task fires on a virtual-thread carrier, not in a chat turn). Poll for
 * this long after the latest createTask call so the auto-delivered fire
 * output (TaskExecutor → DeliveryDispatcher web-send) surfaces inline
 * rather than only on manual reload. Generous because recurring tasks
 * re-fire indefinitely; one createTask call typically intends to receive
 * many deliveries over time.
 */
const TASK_DELIVERY_POLL_GRACE_MS = 30 * 60_000

/**
 * Tool name that schedules background tasks; matches
 * {@code TaskTool.TOOL_NAME} on the backend. Detecting by name (not by
 * tool-result substring) is stable across the two shapes a tool call
 * takes in the local list — the SSE {@code tool_call} frame may not
 * populate {@code resultText} the same way the persisted-row reload
 * does, but the {@code name} field is invariant.
 */
const TASK_MANAGER_TOOL_NAME = 'task_manager'

/**
 * Extract the async-spawn {@code run_id} from a JSON-shaped tool result body.
 * Returns null when the body isn't a {@code status:RUNNING} async-spawn
 * payload — every "not an async-pending result" branch falls through
 * harmlessly. The cheap substring pre-check avoids JSON.parse on every other
 * tool result body (web_search responses, file reads, etc.) on every poll
 * tick.
 */
function pendingAsyncRunIdFromResultText(text: string | null | undefined): string | null {
  if (!text) return null
  if (!text.includes('"status"') || !text.includes('RUNNING')) return null
  try {
    const parsed = JSON.parse(text) as { run_id?: unknown, status?: unknown }
    if (parsed?.status !== 'RUNNING') return null
    const runId = parsed.run_id
    if (typeof runId === 'string') return runId
    if (typeof runId === 'number') return String(runId)
    return null
  }
  catch {
    return null
  }
}

/**
 * Returns the {@code run_id} string carried in a tool-role message's JSON
 * content when the result reported {@code status:RUNNING}. This is the
 * post-reload shape — the standalone tool-role row only lands in
 * {@code messages.value} after a transcript refetch.
 */
function pendingAsyncRunId(m: Message): string | null {
  if (m.role !== 'tool') return null
  return pendingAsyncRunIdFromResultText(m.content)
}

/** Collect the set of run-ids that have already produced an announce row. */
function collectAnnouncedRunIds(msgs: Message[]): Set<string> {
  const announcedRunIds = new Set<string>()
  for (const m of msgs) {
    if (m.messageKind !== 'subagent_announce') continue
    const meta = (m as Message & { metadata?: { runId?: number | string } }).metadata
    const rid = meta?.runId
    if (rid != null) announcedRunIds.add(String(rid))
  }
  return announcedRunIds
}

/**
 * Check whether {@code m}'s assistant toolCalls contain a pending async
 * spawn whose run-id has not yet been announced.
 */
function hasUnannouncedToolCallPending(m: Message, announcedRunIds: Set<string>): boolean {
  if (m.role !== 'assistant' || !m.toolCalls?.length) return false
  for (const tc of m.toolCalls) {
    const tcPending = pendingAsyncRunIdFromResultText(tc.resultText)
    if (tcPending && !announcedRunIds.has(tcPending)) return true
  }
  return false
}

export interface UseChatAnnouncePollerDeps {
  messages: ShallowRef<Message[]>
  selectedConvoId: Ref<number | null>
  streaming: Ref<boolean>
  /** From useChatSubagents — re-run after merging newly-arrived rows. */
  initSubagentCollapsedState: (msgs: Message[]) => void
}

export interface UseChatAnnouncePoller {
  announcedSubagentCount: ComputedRef<number>
  hasPendingAsyncAnnounce: () => boolean
  hasRecentTaskCreate: () => boolean
  pollForAnnounce: () => Promise<void>
}

export function useChatAnnouncePoller(deps: UseChatAnnouncePollerDeps): UseChatAnnouncePoller {
  const { messages, selectedConvoId, streaming, initSubagentCollapsedState } = deps

  let announcePollTimer: ReturnType<typeof setInterval> | undefined

  /**
   * JCLAW-326: count of subagent runs that have produced an announce row in
   * the current conversation. Drives the "View N subagents in this
   * conversation" banner; 0 hides the banner. Counts unique run-ids rather
   * than raw announce messages so a hypothetical duplicate (re-render race)
   * doesn't inflate the figure.
   */
  const announcedSubagentCount = computed(() => collectAnnouncedRunIds(messages.value).size)

  /**
   * True when the open conversation has at least one async-spawn tool result
   * whose announce hasn't landed. Drives the poll-tick decision: if false we
   * skip the network call entirely and let the interval idle until either a
   * new spawn fires or the page unmounts.
   */
  function hasPendingAsyncAnnounce(): boolean {
    const msgs = messages.value
    if (!msgs.length) return false
    const announcedRunIds = collectAnnouncedRunIds(msgs)
    for (const m of msgs) {
      const pending = pendingAsyncRunId(m)
      if (pending && !announcedRunIds.has(pending)) return true
      if (hasUnannouncedToolCallPending(m, announcedRunIds)) return true
    }
    return false
  }

  /**
   * True when the most recent {@code subagent_announce} row arrived within
   * {@link POST_ANNOUNCE_GRACE_MS}. Drives a grace-window poll after an
   * announce lands so messages persisted by the subsequent yield-resumed
   * turn (which has no SSE channel to this tab) surface inline rather
   * than only on manual reload.
   */
  function isWithinPostAnnounceGrace(): boolean {
    const msgs = messages.value
    if (!msgs.length) return false
    let latest = 0
    for (const m of msgs) {
      if (m.messageKind !== 'subagent_announce') continue
      if (!m.createdAt) continue
      const t = Date.parse(m.createdAt)
      if (!Number.isFinite(t)) continue
      if (t > latest) latest = t
    }
    if (latest === 0) return false
    return Date.now() - latest < POST_ANNOUNCE_GRACE_MS
  }

  /**
   * True when the open conversation has any {@code task_manager} tool call
   * within {@link TASK_DELIVERY_POLL_GRACE_MS}. While true,
   * {@link announcePollTick} polls so a task fire's auto-delivered message
   * (Bug 1 fix) surfaces inline. Scans both shapes the tool call takes in
   * the local list (mirrors {@link hasPendingAsyncAnnounce}): standalone
   * tool-role rows post-reload (where the assistant row above carried the
   * call's name), and live SSE-pushed entries on the streaming assistant's
   * {@link Message.toolCalls} array pre-reload.
   *
   * <p>Triggers on every {@code task_manager} action, not just
   * {@code createTask} — only createTask actually schedules a new fire,
   * but updateTask / pause / resume / runNow / cancelTask all leave the
   * conversation in a state where another fire may land soon, and the cost
   * of one extra GET per 5s while the grace window is open is trivial.
   */
  function hasRecentTaskCreate(): boolean {
    const msgs = messages.value
    if (!msgs.length) return false
    const cutoff = Date.now() - TASK_DELIVERY_POLL_GRACE_MS
    for (const m of msgs) {
      const messageAge = m.createdAt ? Date.parse(m.createdAt) : Number.NaN
      // When the message has no createdAt (optimistic), treat as "now" so
      // we don't skip a just-issued create that races the timestamp write.
      if (Number.isFinite(messageAge) && messageAge < cutoff) continue
      if (m.toolCalls?.length) {
        for (const tc of m.toolCalls) {
          if (tc.name === TASK_MANAGER_TOOL_NAME) return true
        }
      }
    }
    return false
  }

  /**
   * Refetch the open conversation's messages and append any rows whose server
   * id isn't already present locally. Preserves client-only state on existing
   * rows (toolCallsCollapsed, _key on optimistic placeholders, etc.) by
   * never mutating or replacing them.
   *
   * Backfills server ids onto id-less optimistic rows BEFORE computing the
   * additions set: without that step the server-side copies of those rows
   * (which carry ids the local set doesn't yet have) sail past the
   * id-membership filter and get appended as duplicates, producing the
   * "user prompt rendered twice" symptom on async-spawn turns.
   */
  async function pollForAnnounce() {
    const convoId = selectedConvoId.value
    if (!convoId) return
    let fresh: Message[]
    try {
      fresh = await $fetch<Message[]>(`/api/conversations/${convoId}/messages`) ?? []
    }
    catch (e) {
      console.error('Failed to poll for announce:', e)
      return
    }
    if (!fresh.length) return
    backfillServerIds(messages.value, fresh)
    const knownIds = new Set<number>()
    for (const m of messages.value) {
      if (typeof m.id === 'number') knownIds.add(m.id)
    }
    const additions = fresh.filter(m => typeof m.id === 'number' && !knownIds.has(m.id))
    if (!additions.length) return
    // Hydrate tool-role rows into the preceding assistant message's toolCalls
    // array on the additions before pushing (mirrors loadConversation). Pass
    // the full fresh list so hydrateToolCalls can match tool rows against
    // their owning assistant turn, then drop everything but additions.
    hydrateToolCalls(fresh as unknown as Array<Record<string, unknown>>)
    for (const m of additions) {
      if (!m.toolCalls?.length) continue
      (m as Message & { toolCallsCollapsed?: boolean }).toolCallsCollapsed = true
      for (let i = 0; i < m.toolCalls.length; i++) {
        m.toolCalls[i]!._expanded = i === m.toolCalls.length - 1
      }
    }
    messages.value = [...messages.value, ...additions]
    initCollapsedState(messages.value)
    initSubagentCollapsedState(messages.value)
    triggerRef(messages)
  }

  function announcePollTick() {
    // Don't fight the streaming path — SSE already keeps the messages list
    // reactive during the parent's own turn. Resume on the next tick after
    // streaming ends.
    if (streaming.value) return
    // Three reasons to poll:
    //   1. An async-spawn is in flight, awaiting its announce row.
    //   2. An announce row arrived within POST_ANNOUNCE_GRACE_MS — the
    //      backend's yield-resume may still be writing follow-up messages
    //      (Phase 4 "import starting", Phase 5 "ready to watch", etc.).
    //   3. A task_manager.createTask call landed within
    //      TASK_DELIVERY_POLL_GRACE_MS — its scheduled fire will auto-deliver
    //      back into this conversation on a virtual-thread carrier with no
    //      SSE channel to this tab (TaskExecutor → DeliveryDispatcher
    //      web-send). Without polling, the delivered Message row sits in the
    //      DB until the user reloads.
    if (!hasPendingAsyncAnnounce()
      && !isWithinPostAnnounceGrace()
      && !hasRecentTaskCreate()) return
    void pollForAnnounce()
  }

  onMounted(() => {
    announcePollTimer = setInterval(announcePollTick, ANNOUNCE_POLL_INTERVAL_MS)
  })

  onUnmounted(() => {
    if (announcePollTimer != null) clearInterval(announcePollTimer)
  })

  return {
    announcedSubagentCount,
    hasPendingAsyncAnnounce,
    hasRecentTaskCreate,
    pollForAnnounce,
  }
}
