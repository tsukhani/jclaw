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
  /**
   * Explicit stream-state flag. True while reasoning chunks are actively
   * arriving; flipped to false at the reasoning→content transition (or on
   * stream completion if the turn was reasoning-only). Drives header-label
   * and default-collapse decisions unambiguously, independent of whether
   * `_thinkingDurationMs` or `usage.reasoningDurationMs` may have leaked
   * in via earlier SSE frames. Undefined on historical/reloaded messages.
   */
  _thinkingInProgress?: boolean
}

/**
 * Resolve the duration to display for a message's thinking bubble.
 *
 * Prefers in-flight client capture over persisted backend timing. The two
 * measurements differ by ~network-latency + event-loop-jitter (backend timing
 * is purely JVM-side: first reasoning chunk → last reasoning chunk; client
 * timing spans first reasoning SSE event → first content SSE event, so it
 * includes transit). Once the user has seen a duration stamp mid-stream, we
 * don't want the final `usage` frame to retroactively revise the number they
 * saw (observed: 1.84s briefly dropping to 1.71s when the persisted number
 * landed). For *historical/reloaded* messages no live value exists, so the
 * persisted fallback takes over — which is exactly right for that case.
 *
 * Returns null when no reasoning was timed (pre-feature history, or a
 * message that hasn't started streaming reasoning yet).
 */
export function thinkingDurationMs(msg: ThinkingMessage): number | null {
  const live = msg?._thinkingDurationMs
  if (typeof live === 'number' && live > 0) return live
  const persisted = msg?.usage?.reasoningDurationMs
  return typeof persisted === 'number' && persisted > 0 ? persisted : null
}

/**
 * Header text for the thinking bubble. Matches LM Studio's
 * "Thought for 16.78 seconds" pattern when a duration is known; degrades
 * to the generic "Thinking" label for pre-feature historical messages.
 *
 * While `_thinkingInProgress` is true, ALWAYS renders "Thinking" regardless
 * of whether a duration exists in the message. This prevents the
 * reasoning→content transition from being anticipated by an early-arriving
 * `usage.reasoningDurationMs` (which can happen when the backend emits its
 * status+usage frame ahead of or between content chunks).
 */
export function thinkingHeaderLabel(msg: ThinkingMessage): string {
  if (msg?._thinkingInProgress) return 'Thinking'
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
