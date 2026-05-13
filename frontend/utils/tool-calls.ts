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
  [k: string]: unknown
}

function looksPersisted(tc: PersistedToolCall | ToolCall): tc is PersistedToolCall {
  return typeof (tc as PersistedToolCall).function === 'object'
    && (tc as PersistedToolCall).function !== null
    && typeof (tc as PersistedToolCall).function.name === 'string'
}

export function hydrateToolCalls(msgs: RawRow[]): void {
  // Index tool-role rows by tool_call_id so the assistant pass can splice
  // result text/structured payloads onto each ToolCall without re-walking.
  const resultsByCallId = new Map<string, { text: string, structured: ToolCallResultStructured | null }>()
  for (const m of msgs) {
    if (m.role === 'tool' && typeof m.toolResults === 'string') {
      resultsByCallId.set(m.toolResults, {
        text: m.content ?? '',
        structured: m.toolResultStructured ?? null,
      })
    }
  }

  let pending: ToolCall[] = []
  for (const m of msgs) {
    if (m.role !== 'assistant') continue
    if (Array.isArray(m.toolCalls)) {
      for (const raw of m.toolCalls) {
        if (!raw || typeof raw !== 'object') continue
        if (looksPersisted(raw)) {
          if (!raw.id || !raw.function?.name) continue
          const r = resultsByCallId.get(raw.id)
          pending.push({
            id: raw.id,
            name: raw.function.name,
            icon: raw.icon ?? 'wrench',
            arguments: raw.function.arguments ?? '',
            resultText: r?.text ?? null,
            resultStructured: r?.structured ?? null,
          })
        }
        else {
          // Already normalized — pass through. Lets callers feed hydrate()
          // output back in idempotently, and keeps live-SSE test fixtures
          // working without a parallel hydration path.
          pending.push(raw)
        }
      }
      // Drop the raw array — the intermediate row no longer needs it and
      // leaving the persisted shape around would confuse the renderer,
      // which expects the normalized ToolCall[] shape per Message type.
      m.toolCalls = null
    }
    if (m.content && pending.length) {
      m.toolCalls = pending
      pending = []
    }
  }
  // Edge case: a mid-turn reload where the final assistant-with-content row
  // hasn't landed yet. Attach the leftover calls to the last assistant row
  // we saw so they render instead of getting dropped silently.
  if (pending.length) {
    for (let i = msgs.length - 1; i >= 0; i--) {
      const row = msgs[i]
      if (row?.role === 'assistant') {
        row.toolCalls = pending
        break
      }
    }
  }
}
