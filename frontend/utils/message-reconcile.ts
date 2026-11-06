import { hydrateToolCalls } from '~/utils/tool-calls'
import { initCollapsedState } from '~/utils/thinking'
import type { Message, MessageAttachment, ToolCall } from '~/types/api'

/**
 * Server-id reconciliation for optimistic chat rows (JCLAW-690 stage 4e; pure
 * logic extracted verbatim from pages/chat.vue). Kept as pure functions here so
 * the subtle role-stack pairing — the part most likely to regress — is
 * unit-testable without mounting the page or faking a stream. The impure
 * orchestrator (useChatConversation's reconcileMessageIds) calls
 * {@link backfillServerIds}.
 *
 * Pair id-less local rows with their server-side counterparts by role,
 * mutating local rows to carry the server id. Used both at end-of-stream
 * (reconcileMessageIds) and inside the announce poll loop so subsequent
 * id-membership checks dedupe correctly.
 *
 * Algorithm: build a per-role LIFO stack of unpaired server ids, then walk
 * local rows back to front and pop the latest matching-role server id off
 * the stack. Server rows whose ids are already present locally are excluded
 * from the stacks up front so they can't be re-stolen.
 *
 * Earlier versions walked both lists backwards in lockstep and broke on the
 * first role mismatch — that fell over when the server list contained
 * intermediate {@code tool} / {@code system} rows the local list never
 * tracked (e.g., a JCLAW-270 async subagent that injects a system-role
 * announce between the parent's user turn and the parent's final assistant
 * reply). The role-stack walk skips those intermediates without false
 * pairings: a local row whose role's stack runs dry stays id-less.
 *
 * {@link reconcileWindow} below is the merge every incremental fetch of
 * hydrated rows goes through: the chat page's live merge and the subagent
 * transcript panel alike.
 */

/** Set of message ids already present on local rows. */
export function collectLocalTakenIds(local: Message[]): Set<number> {
  const ids = new Set<number>()
  for (const L of local) {
    if (typeof L.id === 'number') ids.add(L.id)
  }
  return ids
}

/**
 * Build a per-role LIFO stack of unpaired server ids, skipping any id
 * already present locally so it can't be re-stolen.
 */
export function groupUnpairedServerIdsByRole(fresh: Message[], localTakenIds: Set<number>): Map<string, number[]> {
  const idsByRole = new Map<string, number[]>()
  for (const R of fresh) {
    if (typeof R.id !== 'number' || !R.role) continue
    if (localTakenIds.has(R.id)) continue
    const list = idsByRole.get(R.role) ?? []
    list.push(R.id)
    idsByRole.set(R.role, list)
  }
  return idsByRole
}

/** Index server rows by id for fast post-pairing flag-copy lookups. */
export function indexFreshById(fresh: readonly Message[]): Map<number, Message> {
  const freshById = new Map<number, Message>()
  for (const R of fresh) {
    if (typeof R.id === 'number') freshById.set(R.id, R)
  }
  return freshById
}

export function backfillServerIds(local: Message[], fresh: Message[]): boolean {
  const localTakenIds = collectLocalTakenIds(local)
  const idsByRole = groupUnpairedServerIdsByRole(fresh, localTakenIds)
  // Index server rows by id so the post-pairing pass below can also copy
  // server-side flags (currently just JCLAW-291 truncated) onto the local
  // optimistic rows. Without this the in-flight assistant bubble would
  // never gain its truncation marker until a hard reload.
  const freshById = indexFreshById(fresh)
  let mutated = false
  for (let li = local.length - 1; li >= 0; li--) {
    const L = local[li]
    if (!L || L.id || !L.role) continue
    const list = idsByRole.get(L.role)
    if (!list?.length) continue
    L.id = list.pop()!
    const R = freshById.get(L.id)
    if (R?.truncated) L.truncated = true
    // A reply's stored text carries its quoted block (JCLAW-1299); render that rather than the client copy.
    if (L._quote && R) {
      L.content = R.content
      delete L._quote
    }
    mutated = true
  }
  return mutated
}

/** What {@link reconcileWindow} made of a window of fetched rows. */
export interface ReconciledWindow {
  /** The window's rows in server order, each the shown copy where one exists. */
  rows: Message[]
  /** The window's rows not shown before, with the collapse defaults applied. */
  additions: Message[]
  /** Whether a shown row changed. */
  changed: boolean
}

/** A shown call as it stood before the merge, and the row whose block held it. */
interface PriorCall {
  expanded: boolean | undefined
  /** The operator set `expanded` away from the default for the call's place, which only the last call has open. */
  chosen: boolean
  owner: Message
  ownerOpen: boolean
}

interface WindowRow {
  fresh: Message
  shown: Message | undefined
}

function sameToolCalls(a: ToolCall[], b: ToolCall[]): boolean {
  return a.length === b.length && a.every((tc, i) => tc.id === b[i]!.id && tc.resultText === b[i]!.resultText)
}

// A partial window hydrates only its own rows, so a shown row's calls from before it would be dropped: keep them on
// that row, or, once the row has handed its calls on, on the window's newest row with calls (JCLAW-1209). They precede
// every call of the window, which hydration has already put where a full fetch puts it.
function keepEarlierCalls(window: WindowRow[]): void {
  const placed = new Set<string>()
  for (const { fresh } of window) for (const tc of fresh.toolCalls ?? []) placed.add(tc.id)
  const carried: ToolCall[] = []
  for (const { fresh, shown } of window) {
    const earlier = (shown?.toolCalls ?? []).filter(tc => !placed.has(tc.id))
    if (!earlier.length) continue
    if (fresh.toolCalls?.length) fresh.toolCalls = [...earlier, ...fresh.toolCalls]
    else carried.push(...earlier)
  }
  if (!carried.length) return
  for (let i = window.length - 1; i >= 0; i--) {
    const fresh = window[i]!.fresh
    if (!fresh.toolCalls?.length) continue
    fresh.toolCalls = [...carried, ...fresh.toolCalls]
    return
  }
}

/**
 * The tool-calls block a row shows on load: collapsed, which keeps the transcript dense, with only its last call
 * open, so opening the block shows that call's result at once.
 */
export function applyToolCallDefaults(m: Message): void {
  const calls = m.toolCalls ?? []
  if (!calls.length) return
  m.toolCallsCollapsed = true
  calls.forEach((tc, i) => {
    tc._expanded = i === calls.length - 1
  })
}

// A call the operator opened or closed keeps that state on whichever row now holds it, the rest take the defaults,
// and a row that takes calls from a block the operator left open opens; a row that already showed calls keeps its block.
function settleCallStates(row: Message, had: ToolCall[], prior: Map<string, PriorCall>): void {
  const calls = row.toolCalls ?? []
  const takesOpen = calls.some((tc) => {
    const p = prior.get(tc.id)
    return p != null && p.owner !== row && p.ownerOpen
  })
  const collapsed = row.toolCallsCollapsed
  applyToolCallDefaults(row)
  if (takesOpen) row.toolCallsCollapsed = false
  else if (had.length) row.toolCallsCollapsed = collapsed
  for (const tc of calls) {
    const p = prior.get(tc.id)
    if (p?.chosen) tc._expanded = p.expanded
  }
}

function sameAttachments(a: MessageAttachment[], b: MessageAttachment[]): boolean {
  return a.length === b.length && a.every((att, i) => att.uuid === b[i]!.uuid)
}

// Hydration hangs a turn's calls on its last row so far, so between two fetches a row already shown can gain a call's
// result or hand its calls on to a later row; left stale, they render twice.
function syncHydratedFields(shown: Message, fresh: Message, prior: Map<string, PriorCall>): boolean {
  let changed = false
  const prevCalls = shown.toolCalls ?? []
  const nextCalls = fresh.toolCalls ?? []
  if (!sameToolCalls(prevCalls, nextCalls)) {
    shown.toolCalls = nextCalls
    settleCallStates(shown, prevCalls, prior)
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

/**
 * Merges `fresh`, the server's rows from some point of a conversation on, into the rows `shown` holds, matched by
 * server id. Hydrates `fresh`, then brings each shown row in the window to what a reload would show on it, its tool
 * calls, results and attachments, in place, keeping its other client-only state; rows not shown come back as
 * additions with the collapse defaults applied. `partial` marks a window that may begin after some of a turn's
 * rounds, whose calls are kept rather than dropped. Rows with no server id are left alone.
 */
export function reconcileWindow(shown: readonly Message[], fresh: Message[], partial: boolean): ReconciledWindow {
  hydrateToolCalls(fresh as unknown as Array<Record<string, unknown>>)
  const shownById = indexFreshById(shown)
  const window: WindowRow[] = []
  const seen = new Set<number>()
  for (const m of fresh) {
    if (typeof m.id !== 'number' || seen.has(m.id)) continue
    seen.add(m.id)
    window.push({ fresh: m, shown: shownById.get(m.id) })
  }
  // Read before any row changes, so a call that moves to another row keeps its state.
  const prior = new Map<string, PriorCall>()
  for (const { shown: owner } of window) {
    if (!owner) continue
    const calls = owner.toolCalls ?? []
    calls.forEach((tc, i) => {
      const chosen = tc._expanded != null && tc._expanded !== (i === calls.length - 1)
      prior.set(tc.id, { expanded: tc._expanded, chosen, owner, ownerOpen: owner.toolCallsCollapsed === false })
    })
  }
  if (partial) keepEarlierCalls(window)
  const rows: Message[] = []
  const additions: Message[] = []
  let changed = false
  for (const { fresh: m, shown: s } of window) {
    if (!s) {
      additions.push(m)
      if (m.toolCalls?.length) settleCallStates(m, [], prior)
    }
    else if (syncHydratedFields(s, m, prior)) changed = true
    rows.push(s ?? m)
  }
  // Additions only: re-collapsing every row would fold a thinking card the reader just opened.
  initCollapsedState(additions)
  return { rows, additions, changed }
}

/**
 * The render digest a chat row's renderer takes as `renderToken` (JCLAW-690). Rows live in a shallowRef and are
 * mutated in place, so a change to a field below carries no reference change on the row and would not re-render it:
 * a collapse toggle, a call's open state, and what {@link reconcileWindow} brings to a row already shown, its calls,
 * their results and its attachments. Not a `:key`; the row identity stays `msg.id ?? msg._key`.
 */
export function messageRenderToken(msg: Message): string {
  const calls = (msg.toolCalls ?? [])
    .map(tc => `${tc.id}:${tc._expanded ? 1 : 0}:${tc.resultText == null ? '-' : tc.resultText.length}`)
    .join(',')
  const attachments = (msg.attachments ?? []).map(a => `${a.uuid}${a.deleted ? '!' : ''}`).join(',')
  return `${!!msg.thinkingCollapsed}|${!!msg.toolCallsCollapsed}|${calls}|${attachments}|${msg._route?.model ?? ''}${msg._route?.failover ? '!' : ''}`
}
