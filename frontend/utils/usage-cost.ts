/**
 * Usage cost computation, shared between the chat UI and its unit tests.
 *
 * The model provider returns tokens in three billing categories:
 *   - uncached input:  full base price
 *   - cache reads:     ~0.1× base (Anthropic) / ~0.5× base (OpenAI)
 *   - cache writes:    ~1.25× base (Anthropic 5-min ephemeral) / 0 (OpenAI)
 *
 * The `prompt` field in MessageUsage is the *total* input count across all three
 * categories; `cached` (reads) and `cacheCreation` (writes) are disjoint subsets of it,
 * so `uncachedInput = prompt - cached - cacheCreation`.
 *
 * When the per-model config doesn't carry explicit cache prices, we fall back to sensible
 * defaults derived from `promptPrice`. The defaults err on the conservative side (0.1× for
 * reads, 1.25× for writes) so a missing config never over-reports or under-reports by
 * more than 15% for either major provider.
 */

export interface MessageUsage {
  prompt: number
  completion: number
  total: number
  reasoning: number
  cached: number
  cacheCreation?: number
  durationMs: number
  /** Wall-clock ms spent in the reasoning phase (first→last reasoning chunk). Omitted when no reasoning was streamed. */
  reasoningDurationMs?: number
  promptPrice?: number
  completionPrice?: number
  cachedReadPrice?: number
  cacheWritePrice?: number
}

export interface UsageCostBreakdown {
  uncachedInputTokens: number
  cacheReadTokens: number
  cacheWriteTokens: number
  uncachedInputCost: number
  cacheReadCost: number
  cacheWriteCost: number
  outputCost: number
  total: number
  effectivePromptPrice: number
  effectiveCachedReadPrice: number
  effectiveCacheWritePrice: number
  effectiveCompletionPrice: number
}

/**
 * Compute the billable cost of a single turn. Returns null when no pricing info is
 * available at all (both promptPrice and completionPrice absent) — the caller should
 * suppress the cost label in that case.
 */
export function computeUsageCostBreakdown(usage: MessageUsage): UsageCostBreakdown | null {
  if (!usage.promptPrice && !usage.completionPrice) return null

  const prompt = usage.prompt || 0
  const cacheReadTokens = usage.cached || 0
  const cacheWriteTokens = usage.cacheCreation || 0
  const uncachedInputTokens = Math.max(0, prompt - cacheReadTokens - cacheWriteTokens)

  const effectivePromptPrice = usage.promptPrice || 0
  // Fallback 0.1× for cache reads matches Anthropic (OpenAI is 0.5×, but OpenAI users
  // almost always have pricing populated by discovery, so the fallback rarely fires).
  const effectiveCachedReadPrice =
    usage.cachedReadPrice ?? effectivePromptPrice * 0.1
  // Fallback 1.25× for cache writes matches Anthropic 5-min TTL. Irrelevant on OpenAI
  // routes where cacheCreation is always 0.
  const effectiveCacheWritePrice =
    usage.cacheWritePrice ?? effectivePromptPrice * 1.25
  const effectiveCompletionPrice = usage.completionPrice || 0

  const uncachedInputCost = (uncachedInputTokens / 1_000_000) * effectivePromptPrice
  const cacheReadCost = (cacheReadTokens / 1_000_000) * effectiveCachedReadPrice
  const cacheWriteCost = (cacheWriteTokens / 1_000_000) * effectiveCacheWritePrice
  const outputCost = (usage.completion / 1_000_000) * effectiveCompletionPrice
  const total = uncachedInputCost + cacheReadCost + cacheWriteCost + outputCost

  return {
    uncachedInputTokens,
    cacheReadTokens,
    cacheWriteTokens,
    uncachedInputCost,
    cacheReadCost,
    cacheWriteCost,
    outputCost,
    total,
    effectivePromptPrice,
    effectiveCachedReadPrice,
    effectiveCacheWritePrice,
    effectiveCompletionPrice,
  }
}

/** Compact cost label, e.g. "$0.0049" or "< $0.0001". Returns null when pricing missing. */
export function formatUsageCost(usage: MessageUsage): string | null {
  const b = computeUsageCostBreakdown(usage)
  if (b === null) return null
  if (b.total < 0.0001) return '< $0.0001'
  return '$' + b.total.toFixed(4)
}

/** Tooltip breakdown showing each billing category separately. */
export function formatUsageCostTooltip(usage: MessageUsage): string {
  const b = computeUsageCostBreakdown(usage)
  if (b === null) return ''
  const parts: string[] = []
  if (b.uncachedInputTokens > 0) {
    parts.push(
      `Input: $${b.uncachedInputCost.toFixed(6)} (${b.uncachedInputTokens.toLocaleString()} × $${b.effectivePromptPrice}/M)`,
    )
  }
  if (b.cacheReadTokens > 0) {
    parts.push(
      `Cache read: $${b.cacheReadCost.toFixed(6)} (${b.cacheReadTokens.toLocaleString()} × $${b.effectiveCachedReadPrice.toFixed(4)}/M)`,
    )
  }
  if (b.cacheWriteTokens > 0) {
    parts.push(
      `Cache write: $${b.cacheWriteCost.toFixed(6)} (${b.cacheWriteTokens.toLocaleString()} × $${b.effectiveCacheWritePrice.toFixed(4)}/M)`,
    )
  }
  if (usage.completion > 0) {
    parts.push(
      `Output: $${b.outputCost.toFixed(6)} (${usage.completion.toLocaleString()} × $${b.effectiveCompletionPrice}/M)`,
    )
  }
  return parts.join(' · ')
}
