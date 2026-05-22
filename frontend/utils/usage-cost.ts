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
  /** Source of primary token counts: provider usage block, or jtokkit fallback. */
  usageSource?: 'provider' | 'jtokkit'
  /** True when primary token counts came from local tokenizer measurement. */
  estimated?: boolean
  /** Local tokenizer measurement kept for provider-vs-client diagnostics. */
  jtokkitPrompt?: number
  jtokkitCompletion?: number
  jtokkitReasoning?: number
  jtokkitTotal?: number
  jtokkitEncoding?: string
  jtokkitModelMatched?: boolean
  jtokkitPromptDelta?: number
  jtokkitCompletionDelta?: number
  jtokkitTotalDelta?: number
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
function formatPerModelCost(total: number): string {
  if (total === 0) return '$0.00'
  if (total < 0.0001) return '< $0.0001'
  return '$' + total.toFixed(4)
}

export function formatConversationCostTooltip(breakdown: ConversationCostBreakdown): string {
  return breakdown.perModel
    .map((pm) => {
      const label = pm.modelProvider ? `${pm.modelProvider}/${pm.modelId}` : pm.modelId
      const cost = formatPerModelCost(pm.total)
      return `${label}: ${cost} / ${pm.turnCount} turn${pm.turnCount === 1 ? '' : 's'}`
    })
    .join('\n')
}

// ──────────────────────────────────────────────────────────────────────────
// Fleet-level aggregation (JCLAW-28)
// ──────────────────────────────────────────────────────────────────────────

/** Per-agent aggregation row in the fleet cost summary. */
export interface FleetCostPerAgent {
  agentId: number
  turnCount: number
  total: number
  prompt: number
  completion: number
  reasoning: number
  cached: number
}

/** Per-channel aggregation row in the fleet cost summary. */
export interface FleetCostPerChannel {
  channelType: string
  turnCount: number
  total: number
  prompt: number
  completion: number
  reasoning: number
  cached: number
}

/** Per-model aggregation row in the fleet cost summary. */
export interface FleetCostPerModel {
  modelId: string
  modelProvider?: string
  turnCount: number
  total: number
  prompt: number
  completion: number
  reasoning: number
  cached: number
}

/**
 * Whole-fleet cost summary (JCLAW-28). Built by {@link computeFleetCost} from
 * a list of raw per-turn rows returned by GET /api/metrics/cost.
 *
 * <p>{@code total} is the straight sum of each turn's cost at the price regime
 * frozen on the row when it was emitted (matches {@link ConversationCostBreakdown}'s
 * semantic). Free-tier turns contribute 0 cost but still count toward
 * {@code turnCount} and the token subtotals so the operator sees activity for
 * all-free agents.
 */
export interface FleetCostBreakdown {
  total: number
  turnCount: number
  prompt: number
  completion: number
  reasoning: number
  cached: number
  perAgent: FleetCostPerAgent[]
  perChannel: FleetCostPerChannel[]
  perModel: FleetCostPerModel[]
}

/** Raw row shape returned by GET /api/metrics/cost. */
export interface FleetCostRow {
  timestamp: string
  agentId: number
  channelType: string
  /** Original Message.usageJson string. Parse with JSON.parse to MessageUsage. */
  usageJson: string
  /**
   * JCLAW-280: canonical provider name for the conversation that emitted this
   * turn — projected server-side as COALESCE(modelProviderOverride, agent.modelProvider).
   * Used to partition the dashboard by payment modality (per-token vs subscription).
   * Distinct from {@link MessageUsage.modelProvider} embedded in usageJson, which
   * snapshots the agent's provider at turn time and can drift if the agent's
   * provider was swapped after the turn ran.
   */
  modelProvider?: string
}

/** Filter shape for {@link computeFleetCost}. Null fields = no filter on that dimension. */
export interface FleetCostFilter {
  agentId: number | null
  channelType: string | null
}

/**
 * Aggregate raw per-turn rows into total/per-agent/per-channel/per-model
 * breakdowns. Filter narrows the input set client-side; the time window is
 * already applied server-side via the {@code since} param. Each filter
 * dimension is independent — null means "all values."
 */
/**
 * Tokens + cost contribution from one turn, in the shape every fleet
 * per-dimension aggregation row uses.
 */
interface TurnContribution {
  turnTotal: number
  prompt: number
  completion: number
  reasoning: number
  cached: number
}

/** Shared aggregate counters across per-agent/per-channel/per-model rows. */
interface AggregateCounters {
  turnCount: number
  total: number
  prompt: number
  completion: number
  reasoning: number
  cached: number
}

function addContribution(row: AggregateCounters, t: TurnContribution): void {
  row.turnCount++
  row.total += t.turnTotal
  row.prompt += t.prompt
  row.completion += t.completion
  row.reasoning += t.reasoning
  row.cached += t.cached
}

function newCounters(t: TurnContribution): AggregateCounters {
  return {
    turnCount: 1,
    total: t.turnTotal,
    prompt: t.prompt,
    completion: t.completion,
    reasoning: t.reasoning,
    cached: t.cached,
  }
}

/**
 * Upsert into a per-dimension aggregation map: bump counters on an existing
 * row, otherwise seed a fresh one via {@code seed}. Centralises the three
 * near-identical blocks the original computeFleetCost had inline.
 */
function upsertAggregate<K, R extends AggregateCounters>(
  map: Map<K, R>,
  key: K,
  t: TurnContribution,
  seed: () => R,
): void {
  const existing = map.get(key)
  if (existing) addContribution(existing, t)
  else map.set(key, seed())
}

function turnContribution(usage: MessageUsage): TurnContribution {
  const turn = computeUsageCostBreakdown(usage)
  return {
    turnTotal: turn?.total ?? 0,
    prompt: usage.prompt ?? 0,
    completion: usage.completion ?? 0,
    reasoning: usage.reasoning ?? 0,
    cached: usage.cached ?? 0,
  }
}

function parseUsage(usageJson: string): MessageUsage | null {
  try {
    return JSON.parse(usageJson) as MessageUsage
  }
  catch {
    // Malformed payload — skip rather than crash the whole aggregation.
    // The conversation-detail view does the same defensive handling.
    return null
  }
}

export function computeFleetCost(
  rows: FleetCostRow[],
  filter: FleetCostFilter,
): FleetCostBreakdown {
  const perAgent = new Map<number, FleetCostPerAgent>()
  const perChannel = new Map<string, FleetCostPerChannel>()
  const perModel = new Map<string, FleetCostPerModel>()
  const totals: TurnContribution = { turnTotal: 0, prompt: 0, completion: 0, reasoning: 0, cached: 0 }
  let turnCount = 0

  for (const row of rows) {
    if (filter.agentId !== null && row.agentId !== filter.agentId) continue
    if (filter.channelType !== null && row.channelType !== filter.channelType) continue

    const usage = parseUsage(row.usageJson)
    if (!usage) continue

    const t = turnContribution(usage)
    turnCount++
    totals.turnTotal += t.turnTotal
    totals.prompt += t.prompt
    totals.completion += t.completion
    totals.reasoning += t.reasoning
    totals.cached += t.cached

    upsertAggregate(perAgent, row.agentId, t, () => ({ agentId: row.agentId, ...newCounters(t) }))
    upsertAggregate(perChannel, row.channelType, t, () => ({ channelType: row.channelType, ...newCounters(t) }))
    // Per-model. Use modelId as key, falling back to '(unknown)' for rows
    // that pre-date JCLAW-107's model-identity capture.
    const modelKey = usage.modelId ?? '(unknown)'
    upsertAggregate(perModel, modelKey, t, () => ({ modelId: modelKey, modelProvider: usage.modelProvider, ...newCounters(t) }))
  }

  return {
    total: totals.turnTotal,
    turnCount,
    prompt: totals.prompt,
    completion: totals.completion,
    reasoning: totals.reasoning,
    cached: totals.cached,
    // Sort all three breakdowns by cost descending so the highest-spend rows
    // surface first in the table view.
    perAgent: Array.from(perAgent.values()).sort((a, b) => b.total - a.total),
    perChannel: Array.from(perChannel.values()).sort((a, b) => b.total - a.total),
    perModel: Array.from(perModel.values()).sort((a, b) => b.total - a.total),
  }
}

/** Distinct channel types present in the row set, sorted alphabetically. */
export function listChannelsInRows(rows: FleetCostRow[]): string[] {
  const set = new Set<string>()
  for (const r of rows) set.add(r.channelType)
  return Array.from(set).sort((a, b) => a.localeCompare(b))
}

/** Format dollar amount with the same convention as formatUsageCost. */
export function formatCostUsd(cost: number): string {
  if (cost === 0) return '$0.00'
  if (cost < 0.0001) return '< $0.0001'
  return '$' + cost.toFixed(4)
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
