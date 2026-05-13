/**
 * Filter predicate deciding whether a message should appear in the chat
 * transcript for rendering.
 *
 * Rules (in order):
 *   - Tool-role messages are always hidden (they're tool call records,
 *     not user-facing).
 *   - During streaming, the frontend-created placeholder (has `_key` but
 *     no DB `id`) is suppressed ONLY until it has something renderable —
 *     content or reasoning text. Pre-first-byte, this avoids a flash of
 *     empty bubble; once reasoning streams in (JCLAW-70), the bubble
 *     renders live so the user sees thinking progress.
 *   - Otherwise, keep any message that has content, reasoning, or usage.
 *
 * Kept as a pure helper so the rule is directly unit-testable without
 * mounting the chat page.
 */

export interface DisplayMessageCandidate {
  role?: string
  _key?: string | null
  id?: number | null
  content?: string | null
  reasoning?: string | null
  usage?: object | null
  /** JCLAW-170: tool invocations on this assistant turn. A streaming
   *  placeholder that has at least one tool call landed — even before the
   *  first content/reasoning token — should render so the user sees
   *  live tool activity instead of a blank gap. */
  toolCalls?: { length?: number } | null
}

function hasToolCalls(m: DisplayMessageCandidate): boolean {
  return !!(m.toolCalls && (m.toolCalls.length ?? 0) > 0)
}

export function shouldDisplayMessage(
  m: DisplayMessageCandidate,
  streaming: boolean,
): boolean {
  if (m.role === 'tool') return false
  if (m._key && !m.id && streaming && !m.content && !m.reasoning && !hasToolCalls(m)) return false
  return !!(m.content || m.reasoning || m.usage || hasToolCalls(m))
}
