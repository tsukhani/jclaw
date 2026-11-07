import { onMounted, onUnmounted, triggerRef, type Ref, type ShallowRef } from 'vue'
import { fetchMessagesAfter, newRowsCursor } from '~/utils/conversation-messages'
import { mergeNewRows } from '~/utils/message-reconcile'
import { latestTurnWindowStart } from '~/utils/tool-calls'
import { useEventBus } from '~/composables/useEventBus'
import { useLatestRequest } from '~/composables/useLatestRequest'
import type { Message } from '~/types/api'

/**
 * JCLAW-270 async-spawn announce poller (JCLAW-690 stage 5b; extracted from
 * pages/chat.vue). After the parent's streaming turn
 * ends, the {@code subagent_announce} Message that closes an async spawn can
 * arrive seconds (or minutes) later, after the chat stream has closed. The
 * event bus's conversation.updated and conversation.busy bring it in; this
 * poll is the fallback for while the bus is down.
 *
 * Mirror {@code /subagents}'s "auto-refresh while RUNNING" pattern: while
 * the open conversation has at least one tool result reporting
 * {@code status:RUNNING} for a {@code run_id} that no {@code subagent_announce}
 * row has closed yet, poll {@code /api/conversations/{id}/messages} every
 * {@link ANNOUNCE_POLL_INTERVAL_MS}. Merge any newer rows by server id —
 * never clobber in-flight optimistic placeholders. Stops as soon as the
 * announce arrives or the page unmounts.
 *
 * Deliberately silent while the conversation stream reports a turn — the
 * stream renders it and fetches its rows itself ({@code mergeSavedRows}), so
 * polling on top of it would race the in-flight assistant bubble. The check
 * runs every 5s regardless, so within one cadence of the turn's end the loop
 * kicks in.
 *
 * This is conversation-refresh, not display — a separate concern from
 * useChatSubagents (whose initSubagentCollapsedState it calls on the rows of
 * each run a merge adds). Owns its own poll timer lifecycle.
 */
const ANNOUNCE_POLL_INTERVAL_MS = 5000

/**
 * Keep polling for this long after the most recent subagent_announce row
 * lands, even though {@link hasPendingAsyncAnnounce} flips false. Covers
 * the gap where {@link AgentRunner#runYieldResume} runs the parent's
 * resumed turn on the backend with no SSE stream to the chat tab. The
 * event bus's conversation.updated and conversation.busy bring that turn's
 * rows in; this window is the fallback for while the bus is down. Three
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

/** How long to keep polling after a {@code conversation.updated} event that arrived mid-stream. */
const CONVERSATION_UPDATED_GRACE_MS = 60_000

/**
 * Tool name that schedules background tasks; matches
 * {@code TaskTool.name()} on the backend. Detecting by name (not by
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
  /** From useChatSubagents — run on the rows of each subagent run a merge adds. */
  initSubagentCollapsedState: (msgs: Message[]) => void
  /** Runs each merge that changes the rows; the chat page's follows them down (useChatScroll.appendFollowing). */
  appendRows?: (append: () => void) => void
}

export interface MergeSavedRowsOptions {
  /** Which id-less rows may take a saved row's id; the running turn's bubble may not. */
  pairable?: (m: Message) => boolean
  /** A resync's reload: fetch from the window that holds the newest turn, rather than from the newest settled row. */
  full?: boolean
}

export interface UseChatAnnouncePoller {
  hasPendingAsyncAnnounce: () => boolean
  hasRecentTaskCreate: () => boolean
  pollForAnnounce: () => Promise<void>
  /** {@link pollForAnnounce} while the conversation stream reports a turn, whose rows it fetches on init and after its end. */
  mergeSavedRows: (opts?: MergeSavedRowsOptions) => Promise<void>
  /**
   * Poll for {@code ms} from now whatever else holds. A background scrape job's card calls it when the
   * job ends (JCLAW-1273): the completion message and the agent's reply to it land with no SSE
   * channel to this tab.
   */
  keepPollingFor: (ms: number) => void
}

/** The cursor a resync's reload fetches after: just before the window that holds the newest turn, else every shown row. */
function turnWindowCursor(shown: Message[]): number | null {
  const saved = shown.filter(m => typeof m.id === 'number')
  const start = latestTurnWindowStart(saved)
  if (start > 0) return saved[start - 1]!.id!
  const oldest = saved[0]?.id
  return oldest == null ? null : oldest - 1
}

export function useChatAnnouncePoller(deps: UseChatAnnouncePollerDeps): UseChatAnnouncePoller {
  const { messages, selectedConvoId, streaming, initSubagentCollapsedState } = deps
  const appendRows = deps.appendRows ?? ((append: () => void) => append())

  let announcePollTimer: ReturnType<typeof setInterval> | undefined
  let pollUntil = 0
  // The tick and conversation.updated can overlap; an older window landing last would hang moved calls back on a shown row.
  const polls = useLatestRequest()

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
   * (Bug 1 fix) surfaces inline. Reads each row's {@link Message.toolCalls},
   * where hydration also puts reloaded calls.
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
      if (m.toolCalls?.some(tc => tc.name === TASK_MANAGER_TOOL_NAME)) return true
    }
    return false
  }

  /**
   * Refetch the open conversation's newer rows and merge them through
   * {@link mergeNewRows}: rows whose server id isn't present locally go in
   * server order, as a reload shows them, and a row already shown is brought
   * to what the server holds for it (its tool calls, their results, its
   * attachments) in place, keeping its other client-only state
   * (thinkingCollapsed, _key on optimistic placeholders, etc.). Collapse
   * defaults apply to the added rows alone.
   *
   * Id-less optimistic rows take their saved copies' ids first: without that
   * step the server-side copies of those rows (which carry ids the local set
   * doesn't yet have) sail past the id match and get added as duplicates,
   * producing the "user prompt rendered twice" symptom on async-spawn turns.
   */
  async function fetchAndMerge(duringTurn: boolean, opts: MergeSavedRowsOptions = {}) {
    const convoId = selectedConvoId.value
    if (!convoId) return
    const poll = polls.begin()
    let fresh: Message[]
    try {
      fresh = await fetchMessagesAfter(convoId, opts.full ? turnWindowCursor(messages.value) : newRowsCursor(messages.value))
    }
    catch (e) {
      console.error('Failed to poll for announce:', e)
      return
    }
    // A tick can land after the operator switched conversations, whose rows belong to the old one, or after a turn
    // began, whose rows the stream's own fetches bring in.
    if (!polls.isCurrent(poll) || selectedConvoId.value !== convoId || (!duringTurn && streaming.value)) return
    if (!fresh.length) return
    const shownRuns = new Set<number>()
    for (const m of messages.value) {
      if (m.subagentRunId != null) shownRuns.add(m.subagentRunId)
    }
    // The window holds a running turn's earlier rounds too, and hydrating it hangs all their calls on its newest row:
    // the shown row that held them after the last merge must give them up, or each merge leaves one more block behind.
    // Not partial: newRowsCursor starts the window after a settled row or before every shown one, so it hydrates whole.
    const { rows, additions, changed } = mergeNewRows(messages.value, fresh, opts.pairable)
    if (!changed) return
    const addedRuns = additions.filter(m => m.subagentRunId != null && !shownRuns.has(m.subagentRunId))
    appendRows(() => {
      messages.value = rows
      if (addedRuns.length) initSubagentCollapsedState(addedRuns)
      triggerRef(messages)
    })
  }

  function pollForAnnounce() {
    return fetchAndMerge(false)
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
      && !hasRecentTaskCreate()
      && Date.now() >= pollUntil) return
    void pollForAnnounce()
  }

  function keepPollingFor(ms: number) {
    pollUntil = Math.max(pollUntil, Date.now() + ms)
  }

  // A row the server added outside this tab's stream, such as a task run's report; mid-stream, the tick
  // picks it up once the stream ends.
  const { onEvent } = useEventBus()
  onEvent('conversation.updated', (data) => {
    const id = (data as { conversationId?: unknown } | null)?.conversationId
    if (id == null || id !== selectedConvoId.value) return
    if (streaming.value) keepPollingFor(CONVERSATION_UPDATED_GRACE_MS)
    else void pollForAnnounce()
  })

  onMounted(() => {
    announcePollTimer = setInterval(announcePollTick, ANNOUNCE_POLL_INTERVAL_MS)
  })

  onUnmounted(() => {
    if (announcePollTimer != null) clearInterval(announcePollTimer)
  })

  return {
    hasPendingAsyncAnnounce,
    hasRecentTaskCreate,
    pollForAnnounce,
    mergeSavedRows: opts => fetchAndMerge(true, opts),
    keepPollingFor,
  }
}
