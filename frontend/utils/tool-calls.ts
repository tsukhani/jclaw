import type { ToolCall, ToolCallResultStructured } from '~/types/api'

/**
 * JCLAW-170: merge tool-role message rows into the following assistant
 * response's {@code toolCalls} list.
 *
 * Backend persistence shape (emitted by
 * {@code /api/conversations/:id/messages}):
 *
 *  1. assistant row with {@code content: null, toolCalls: [persisted-tool-call-objects]}
 *  2. tool row per call with {@code content: "result text", toolResults:
 *     "<tool_call_id>", toolResultStructured: {...} | undefined}
 *  3. assistant row with the final response {@code content}.
 *
 * The tool-role rows are filtered out of the transcript by {@code
 * shouldDisplayMessage}; the intermediate assistant-with-toolCalls row has
 * no content so it's also filtered. Before those filters kick in we walk
 * the full list, build one normalized {@link ToolCall} per invocation
 * (arguments + icon from the persisted assistant row, result text and
 * structured payload from the matching tool row), and hang the resulting
 * array on the {@code toolCalls} field of the next assistant row that does
 * render.
 *
 * Mutates its input for simplicity — the caller is expected to have just
 * loaded the list and has no other references to it.
 */

interface PersistedToolCall {
  id: string
  type?: string
  function: { name: string, arguments: string }
  icon?: string
}

interface RawRow {
  role?: string
  content?: string | null
  reasoning?: string | null
  toolCalls?: PersistedToolCall[] | ToolCall[] | null
  toolResults?: string | null
  toolResultStructured?: ToolCallResultStructured | null
  /** JCLAW-228: generate_image persists its produced image on the
   *  intermediate (content-null) assistant row alongside the tool call.
   *  That row gets filtered, so its attachments are carried onto the
   *  rendering row the same way its toolCalls are. */
  attachments?: unknown[] | null
  [k: string]: unknown
}

function looksPersisted(tc: PersistedToolCall | ToolCall): tc is PersistedToolCall {
  return typeof (tc as PersistedToolCall).function === 'object'
    && (tc as PersistedToolCall).function !== null
    && typeof (tc as PersistedToolCall).function.name === 'string'
}

type ResultsByCallId = Map<string, { text: string, structured: ToolCallResultStructured | null }>

function indexToolResults(msgs: RawRow[]): ResultsByCallId {
  // Index tool-role rows by tool_call_id so the assistant pass can splice
  // result text/structured payloads onto each ToolCall without re-walking.
  const resultsByCallId: ResultsByCallId = new Map()
  for (const m of msgs) {
    if (m.role === 'tool' && typeof m.toolResults === 'string') {
      resultsByCallId.set(m.toolResults, {
        text: m.content ?? '',
        structured: m.toolResultStructured ?? null,
      })
    }
  }
  return resultsByCallId
}

function normalizeToolCall(
  raw: PersistedToolCall | ToolCall,
  resultsByCallId: ResultsByCallId,
): ToolCall | null {
  if (!looksPersisted(raw)) {
    // Already normalized — pass through. Lets callers feed hydrate()
    // output back in idempotently, and keeps live-SSE test fixtures
    // working without a parallel hydration path.
    return raw
  }
  if (!raw.id || !raw.function?.name) return null
  const r = resultsByCallId.get(raw.id)
  return {
    id: raw.id,
    name: raw.function.name,
    icon: raw.icon ?? 'wrench',
    arguments: raw.function.arguments ?? '',
    resultText: r?.text ?? null,
    resultStructured: r?.structured ?? null,
  }
}

function collectNormalizedCalls(
  rawCalls: Array<PersistedToolCall | ToolCall>,
  resultsByCallId: ResultsByCallId,
): ToolCall[] {
  const out: ToolCall[] = []
  for (const raw of rawCalls) {
    if (!raw || typeof raw !== 'object') continue
    const normalized = normalizeToolCall(raw, resultsByCallId)
    if (normalized) out.push(normalized)
  }
  return out
}

function attachLeftoverPending(
  msgs: RawRow[],
  pending: ToolCall[],
  pendingAttachments: unknown[],
): void {
  // Edge case: a mid-turn reload where the final assistant-with-content row
  // hasn't landed yet. Attach the leftover calls (and any carried-over
  // attachments) to the last assistant row we saw so they render instead of
  // getting dropped silently.
  for (let i = msgs.length - 1; i >= 0; i--) {
    const row = msgs[i]
    if (row?.role === 'assistant') {
      if (pending.length) row.toolCalls = pending
      if (pendingAttachments.length) {
        row.attachments = [
          ...(Array.isArray(row.attachments) ? row.attachments : []),
          ...pendingAttachments,
        ]
      }
      break
    }
  }
}

/** Calls + attachments accumulated off filtered intermediate rows, awaiting the next content row. */
interface Pending {
  calls: ToolCall[]
  attachments: unknown[]
}

function pendingIsEmpty(p: Pending): boolean {
  return p.calls.length === 0 && p.attachments.length === 0
}

/**
 * Drain an intermediate (content-null) tool-call row into the pending accumulator: its normalized
 * calls, and — JCLAW-228 — any attachments generate_image persisted on it (that row is filtered out
 * of the transcript, so the attachment must ride forward to the row that renders). The raw shapes are
 * nulled off the row so the renderer doesn't re-render them.
 */
function drainIntermediateRow(m: RawRow, resultsByCallId: ResultsByCallId, pending: Pending): void {
  if (!Array.isArray(m.toolCalls)) return
  pending.calls.push(...collectNormalizedCalls(m.toolCalls, resultsByCallId))
  m.toolCalls = null
  if (Array.isArray(m.attachments) && m.attachments.length) {
    pending.attachments.push(...m.attachments)
    m.attachments = null
  }
}

/** Flush the accumulated calls/attachments onto the first assistant row that has actual content. */
function flushIntoContentRow(m: RawRow, pending: Pending): void {
  if (!m.content || pendingIsEmpty(pending)) return
  if (pending.calls.length) {
    m.toolCalls = pending.calls
    pending.calls = []
  }
  if (pending.attachments.length) {
    m.attachments = [
      ...(Array.isArray(m.attachments) ? m.attachments : []),
      ...pending.attachments,
    ]
    pending.attachments = []
  }
}

export function hydrateToolCalls(msgs: RawRow[]): void {
  const resultsByCallId = indexToolResults(msgs)
  const pending: Pending = { calls: [], attachments: [] }
  for (const m of msgs) {
    if (m.role !== 'assistant') continue
    drainIntermediateRow(m, resultsByCallId, pending)
    flushIntoContentRow(m, pending)
  }
  if (!pendingIsEmpty(pending)) {
    attachLeftoverPending(msgs, pending.calls, pending.attachments)
  }
}
