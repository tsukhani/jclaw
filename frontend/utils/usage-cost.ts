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
  /** JCLAW-107: the agent's modelProvider at the time this turn ran. */
  modelProvider?: string
  /** JCLAW-107: the agent's modelId at the time this turn ran. */
  modelId?: string
  /** JCLAW-107: the model's contextWindow at the time this turn ran (tokens). */
  contextWindow?: number
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
  const effectiveCachedReadPrice
    = usage.cachedReadPrice ?? effectivePromptPrice * 0.1
  // Fallback 1.25× for cache writes matches Anthropic 5-min TTL. Irrelevant on OpenAI
  // routes where cacheCreation is always 0.
  const effectiveCacheWritePrice
    = usage.cacheWritePrice ?? effectivePromptPrice * 1.25
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

/**
 * Per-model attribution of a conversation's cumulative cost and turn counts
 * (JCLAW-108). Keys are the `usage.modelId` strings captured per-turn — so
 * switching models mid-conversation produces one entry per model that ran.
 */
export interface ConversationCostPerModel {
  modelId: string
  modelProvider?: string
  turnCount: number
  total: number
}

/**
 * Whole-conversation cost summary (JCLAW-108). `total` is the straight sum of
 * each turn's cost at the price regime in effect when that turn ran — this is
 * what the user actually paid, even when the conversation spans multiple
 * models with different prices (e.g. Kimi → Flash → Sonnet).
 */
export interface ConversationCostBreakdown {
  total: number
  turnCount: number
  perModel: ConversationCostPerModel[]
}

/**
 * Sum per-turn costs across the conversation, honoring each turn's own
 * embedded pricing. Pricing is frozen at emission time (see
 * `AgentRunner.buildUsageJson`), so this function does not need to re-resolve
 * ModelInfo or consult the provider registry — it just calls
 * `computeUsageCostBreakdown` per turn and sums the results. Turns whose
 * usage lacks pricing (returned null from the per-turn computation) contribute
 * zero to the totals but still count toward the per-model turn count so a
 * free-tier regime like Ollama shows "3 turns, $0.00" in the breakdown.
 *
 * Input is a list of `MessageUsage` objects — one per assistant message in
 * the conversation. Caller is responsible for filtering out non-assistant
 * messages and unparsed usage.
 */
export function computeConversationCost(usages: MessageUsage[]): ConversationCostBreakdown {
  const byModel = new Map<string, ConversationCostPerModel>()
  let grandTotal = 0
  let turnCount = 0
  for (const u of usages) {
    turnCount++
    const turn = computeUsageCostBreakdown(u)
    const turnTotal = turn?.total ?? 0
    grandTotal += turnTotal
    const key = u.modelId ?? '(unknown)'
    const existing = byModel.get(key)
    if (existing) {
      existing.turnCount++
      existing.total += turnTotal
    }
    else {
      byModel.set(key, {
        modelId: key,
        modelProvider: u.modelProvider,
        turnCount: 1,
        total: turnTotal,
      })
    }
  }
  return {
    total: grandTotal,
    turnCount,
    perModel: Array.from(byModel.values()),
  }
}

/**
 * Compact conversation-level cost label: "$0.0149" across all turns, or
 * "< $0.0001" for sub-tenth-cent totals. Returns null when the conversation
 * has zero turns (fresh/empty) or no paid turns (all free-tier).
 */
export function formatConversationCost(breakdown: ConversationCostBreakdown): string | null {
  if (breakdown.turnCount === 0) return null
  if (breakdown.total === 0) return '$0.00'
  if (breakdown.total < 0.0001) return '< $0.0001'
  return '$' + breakdown.total.toFixed(4)
}

/**
 * Human-readable per-model breakdown, one line per model, in the order the
 * models first appeared. Example:
 *   ollama-cloud/kimi-k2: $0.00 / 3 turns
 *   openrouter/google-flash-preview: $0.0149 / 4 turns
 */
export function formatConversationCostTooltip(breakdown: ConversationCostBreakdown): string {
  return breakdown.perModel
    .map((pm) => {
      const label = pm.modelProvider ? `${pm.modelProvider}/${pm.modelId}` : pm.modelId
      const cost = pm.total === 0
        ? '$0.00'
        : pm.total < 0.0001
          ? '< $0.0001'
          : '$' + pm.total.toFixed(4)
      return `${label}: ${cost} / ${pm.turnCount} turn${pm.turnCount === 1 ? '' : 's'}`
    })
    .join('\n')
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
