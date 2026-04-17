/**
 * Helpers for the per-message thinking-bubble collapse UX.
 *
 * The chat page renders one collapsible reasoning bubble per assistant
 * message. In-flight turns capture timing on the fly (first reasoning event
 * → first token event); persisted turns read timing from usageJson.
 * These pure functions isolate the header-label and default-collapse
 * decisions so they can be unit-tested without mounting the page.
 */

export interface ThinkingMessage {
  reasoning?: string | null
  /** Persisted per-turn usage read from /api/conversations/{id}/messages. */
  usage?: { reasoningDurationMs?: number } | null
  /** In-flight capture: assigned by the SSE handler at reasoning→content transition. */
  _thinkingDurationMs?: number | null
  /** Per-bubble collapse state. undefined = uninitialised, true = collapsed. */
  thinkingCollapsed?: boolean
}

/**
 * Resolve the duration to display for a message's thinking bubble.
 * Prefers persisted backend timing; falls back to in-flight client capture.
 * Returns null when no reasoning was timed (pre-feature history, or a
 * message that hasn't started streaming reasoning yet).
 */
export function thinkingDurationMs(msg: ThinkingMessage): number | null {
  const persisted = msg?.usage?.reasoningDurationMs
  if (typeof persisted === 'number' && persisted > 0) return persisted
  const live = msg?._thinkingDurationMs
  return typeof live === 'number' && live > 0 ? live : null
}

/**
 * Header text for the thinking bubble. Matches LM Studio's
 * "Thought for 16.78 seconds" pattern when a duration is known; degrades
 * to the generic "Thinking" label for pre-feature historical messages.
 */
export function thinkingHeaderLabel(msg: ThinkingMessage): string {
  const ms = thinkingDurationMs(msg)
  return ms != null ? `Thought for ${(ms / 1000).toFixed(2)} seconds` : 'Thinking'
}

/**
 * Collapse every thinking bubble in a freshly-loaded message list. Called
 * after /api/conversations/{id}/messages lands so the user sees a dense
 * transcript on open; individual bubbles remain click-to-expand.
 */
export function initCollapsedState(msgs: ThinkingMessage[]): void {
  for (const m of msgs) {
    if (m.reasoning || m.usage?.reasoningDurationMs) m.thinkingCollapsed = true
  }
}
