import { describe, it, expect } from 'vitest'
import {
  computeUsageCostBreakdown,
  computeConversationCost,
  formatConversationCost,
  formatConversationCostTooltip,
  formatUsageCost,
  formatUsageCostTooltip,
  type MessageUsage,
} from '~/utils/usage-cost'

const CLAUDE_OPUS_PRICES = {
  promptPrice: 15, // $/MTok
  completionPrice: 75,
}

const OPENAI_PRICES = {
  promptPrice: 3,
  completionPrice: 15,
}

function usage(partial: Partial<MessageUsage>): MessageUsage {
  return {
    prompt: 0,
    completion: 0,
    total: 0,
    reasoning: 0,
    cached: 0,
    durationMs: 0,
    ...partial,
  }
}

describe('computeUsageCostBreakdown', () => {
  it('returns null when no pricing info is available', () => {
    const b = computeUsageCostBreakdown(usage({ prompt: 1000, completion: 500 }))
    expect(b).toBeNull()
  })

  it('charges full rate on all input tokens when no caching occurs', () => {
    const b = computeUsageCostBreakdown(
      usage({ prompt: 1000, completion: 500, ...CLAUDE_OPUS_PRICES }),
    )
    expect(b).not.toBeNull()
    expect(b!.uncachedInputTokens).toBe(1000)
    expect(b!.cacheReadTokens).toBe(0)
    expect(b!.cacheWriteTokens).toBe(0)
    // 1000 × $15/M = $0.015 input; 500 × $75/M = $0.0375 output; total $0.0525
    expect(b!.uncachedInputCost).toBeCloseTo(0.015, 6)
    expect(b!.outputCost).toBeCloseTo(0.0375, 6)
    expect(b!.total).toBeCloseTo(0.0525, 6)
  })

  it('applies cache-read discount on a 95% cache-hit turn (Anthropic defaults)', () => {
    // The exact scenario from the bug report: Opus 4.6 with 4,891 total prompt tokens,
    // 4,670 served from cache, 112 output, 76 reasoning. No explicit cache prices in
    // config — should fall back to 0.1× prompt for reads.
    const b = computeUsageCostBreakdown(
      usage({
        prompt: 4891,
        completion: 112,
        cached: 4670,
        ...CLAUDE_OPUS_PRICES,
      }),
    )
    expect(b).not.toBeNull()
    expect(b!.uncachedInputTokens).toBe(221) // 4891 - 4670 - 0
    expect(b!.cacheReadTokens).toBe(4670)
    // Uncached: 221 × $15/M = $0.003315
    // Cache read: 4670 × $1.50/M (0.1 × $15) = $0.007005
    // Output: 112 × $75/M = $0.0084
    // Total: ~$0.01872
    expect(b!.uncachedInputCost).toBeCloseTo(0.003315, 6)
    expect(b!.cacheReadCost).toBeCloseTo(0.007005, 6)
    expect(b!.outputCost).toBeCloseTo(0.0084, 6)
    expect(b!.total).toBeCloseTo(0.01872, 5)
  })

  it('old buggy formula would over-report the cache-hit turn by ~4x', () => {
    // Documents the fix magnitude: the pre-fix formula charged prompt * promptPrice on
    // *all* tokens, producing $0.07337 for the same 4,891-token, 95%-cached turn. The
    // corrected total should be nowhere near that.
    const u = usage({
      prompt: 4891,
      completion: 112,
      cached: 4670,
      ...CLAUDE_OPUS_PRICES,
    })
    const oldBuggyCost
      = (u.prompt / 1_000_000) * CLAUDE_OPUS_PRICES.promptPrice
        + (u.completion / 1_000_000) * CLAUDE_OPUS_PRICES.completionPrice
    const b = computeUsageCostBreakdown(u)!
    expect(oldBuggyCost).toBeCloseTo(0.08177, 5) // was wrong
    expect(b.total).toBeLessThan(oldBuggyCost * 0.3) // new total is <30% of the bug
  })

  it('charges cache-write premium on the seeding turn (Anthropic)', () => {
    // First turn of a new conversation: most of the system prompt is being *written*
    // to cache, not read. Anthropic 5-min TTL charges 1.25× base for writes.
    const b = computeUsageCostBreakdown(
      usage({
        prompt: 5000,
        completion: 200,
        cached: 0,
        cacheCreation: 4800,
        ...CLAUDE_OPUS_PRICES,
      }),
    )
    expect(b).not.toBeNull()
    expect(b!.uncachedInputTokens).toBe(200) // 5000 - 0 - 4800
    expect(b!.cacheWriteTokens).toBe(4800)
    // Uncached: 200 × $15/M = $0.003
    // Cache write: 4800 × $18.75/M (1.25 × $15) = $0.09
    // Output: 200 × $75/M = $0.015
    // Total: ~$0.108 (more than uncached-only would've been, as expected)
    expect(b!.cacheWriteCost).toBeCloseTo(0.09, 6)
    expect(b!.total).toBeCloseTo(0.108, 4)
  })

  it('honors explicit cache prices from config when provided', () => {
    // OpenRouter's actual per-model rates: Sonnet 4 input $3, read $0.30 (exact 10%),
    // write $3.75 (exact 25% premium). When these are set explicitly, fallbacks don't
    // fire.
    const b = computeUsageCostBreakdown(
      usage({
        prompt: 10_000,
        completion: 500,
        cached: 7000,
        cacheCreation: 2000,
        promptPrice: 3,
        completionPrice: 15,
        cachedReadPrice: 0.30,
        cacheWritePrice: 3.75,
      }),
    )!
    expect(b.uncachedInputTokens).toBe(1000) // 10000 - 7000 - 2000
    expect(b.effectivePromptPrice).toBe(3)
    expect(b.effectiveCachedReadPrice).toBe(0.30)
    expect(b.effectiveCacheWritePrice).toBe(3.75)
    // Uncached: 1000 × $3/M = $0.003
    // Cache read: 7000 × $0.30/M = $0.0021
    // Cache write: 2000 × $3.75/M = $0.0075
    // Output: 500 × $15/M = $0.0075
    // Total: $0.0201
    expect(b.total).toBeCloseTo(0.0201, 5)
  })

  it('never produces negative uncached counts when cache fields overflow prompt', () => {
    // Defensive: if a provider reports inconsistent numbers (e.g., cached > prompt),
    // the formula must not blow up or produce negative costs.
    const b = computeUsageCostBreakdown(
      usage({
        prompt: 1000,
        completion: 50,
        cached: 1200, // clearly bogus — shouldn't happen in practice
        ...CLAUDE_OPUS_PRICES,
      }),
    )!
    expect(b.uncachedInputTokens).toBe(0)
    expect(b.uncachedInputCost).toBe(0)
    // We still charge for the reported cache reads at the read rate.
    expect(b.cacheReadTokens).toBe(1200)
  })
})

describe('formatUsageCost', () => {
  it('returns null when pricing is absent', () => {
    expect(formatUsageCost(usage({ prompt: 1000, completion: 500 }))).toBeNull()
  })

  it('renders "< $0.0001" for negligible totals', () => {
    expect(
      formatUsageCost(
        usage({ prompt: 10, completion: 5, promptPrice: 1, completionPrice: 2 }),
      ),
    ).toBe('< $0.0001')
  })

  it('renders a 4-decimal-place dollar value for normal totals', () => {
    const result = formatUsageCost(
      usage({ prompt: 4891, completion: 112, cached: 4670, ...CLAUDE_OPUS_PRICES }),
    )
    expect(result).toMatch(/^\$0\.\d{4}$/)
    // Should not be the old bogus $0.082 value
    expect(result).not.toBe('$0.0818')
  })
})

describe('formatUsageCostTooltip', () => {
  it('surfaces every present billing category', () => {
    const tooltip = formatUsageCostTooltip(
      usage({
        prompt: 6000,
        completion: 150,
        cached: 5000,
        cacheCreation: 500,
        ...CLAUDE_OPUS_PRICES,
      }),
    )
    expect(tooltip).toContain('Input:')
    expect(tooltip).toContain('Cache read:')
    expect(tooltip).toContain('Cache write:')
    expect(tooltip).toContain('Output:')
  })

  it('omits empty categories', () => {
    // No caching → no cache read/write rows in the tooltip.
    const tooltip = formatUsageCostTooltip(
      usage({ prompt: 1000, completion: 500, ...OPENAI_PRICES }),
    )
    expect(tooltip).toContain('Input:')
    expect(tooltip).toContain('Output:')
    expect(tooltip).not.toContain('Cache read')
    expect(tooltip).not.toContain('Cache write')
  })
})

describe('computeConversationCost (JCLAW-108)', () => {
  it('sums to zero across an all-Kimi conversation with zero pricing', () => {
    // Ollama-Cloud/Kimi is GPU-subscription: prices are 0 per-token. Every
    // turn contributes nothing, but turnCount and per-model breakdown still
    // track the conversation's shape for the UI.
    const kimi: MessageUsage = {
      prompt: 5000, completion: 1000, total: 6000, reasoning: 0, cached: 0, durationMs: 1000,
      promptPrice: 0, completionPrice: 0,
      modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
    }
    const breakdown = computeConversationCost([kimi, kimi, kimi])

    expect(breakdown.total).toBe(0)
    expect(breakdown.turnCount).toBe(3)
    expect(breakdown.perModel).toHaveLength(1)
    expect(breakdown.perModel[0]!.modelId).toBe('kimi-k2.5')
    expect(breakdown.perModel[0]!.turnCount).toBe(3)
    expect(breakdown.perModel[0]!.total).toBe(0)
  })

  it('sums all-Flash turns using per-turn embedded prices', () => {
    // 1000 prompt + 500 completion at 0.30 / 2.50 → (1000/1M * 0.30) + (500/1M * 2.50)
    //  = 0.0003 + 0.00125 = 0.00155 per turn; 3 turns = 0.00465
    const flash: MessageUsage = {
      prompt: 1000, completion: 500, total: 1500, reasoning: 0, cached: 0, durationMs: 1000,
      promptPrice: 0.30, completionPrice: 2.50,
      modelProvider: 'openrouter', modelId: 'google-flash',
    }
    const breakdown = computeConversationCost([flash, flash, flash])

    expect(breakdown.total).toBeCloseTo(0.00465, 6)
    expect(breakdown.turnCount).toBe(3)
    expect(breakdown.perModel).toHaveLength(1)
    expect(breakdown.perModel[0]!.turnCount).toBe(3)
  })

  it('honors each turn\'s own prices across a mid-conversation model switch', () => {
    // Exactly the motivating scenario: 3 Kimi turns (free) then 3 Flash turns
    // (paid). Grand total must equal ONLY the Flash contribution.
    const kimi: MessageUsage = {
      prompt: 5000, completion: 1000, total: 6000, reasoning: 0, cached: 0, durationMs: 1000,
      promptPrice: 0, completionPrice: 0,
      modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
    }
    const flash: MessageUsage = {
      prompt: 1000, completion: 500, total: 1500, reasoning: 0, cached: 0, durationMs: 1000,
      promptPrice: 0.30, completionPrice: 2.50,
      modelProvider: 'openrouter', modelId: 'google-flash',
    }
    const breakdown = computeConversationCost([kimi, kimi, kimi, flash, flash, flash])

    expect(breakdown.turnCount).toBe(6)
    expect(breakdown.total).toBeCloseTo(0.00465, 6)
    expect(breakdown.perModel).toHaveLength(2)
    const kimiEntry = breakdown.perModel.find(p => p.modelId === 'kimi-k2.5')!
    const flashEntry = breakdown.perModel.find(p => p.modelId === 'google-flash')!
    expect(kimiEntry.turnCount).toBe(3)
    expect(kimiEntry.total).toBe(0)
    expect(flashEntry.turnCount).toBe(3)
    expect(flashEntry.total).toBeCloseTo(0.00465, 6)
  })

  it('splits three distinct pricing regimes correctly', () => {
    // 3 regimes: Kimi (free), Flash (cheap), Sonnet (pricier). Each entry
    // must self-attribute its contribution even when interleaved.
    const kimi: MessageUsage = {
      prompt: 5000, completion: 1000, total: 6000, reasoning: 0, cached: 0, durationMs: 1000,
      promptPrice: 0, completionPrice: 0,
      modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
    }
    const flash: MessageUsage = {
      prompt: 1000, completion: 500, total: 1500, reasoning: 0, cached: 0, durationMs: 1000,
      promptPrice: 0.30, completionPrice: 2.50,
      modelProvider: 'openrouter', modelId: 'google-flash',
    }
    const sonnet: MessageUsage = {
      prompt: 1000, completion: 500, total: 1500, reasoning: 0, cached: 0, durationMs: 1000,
      promptPrice: 3.0, completionPrice: 15.0,
      modelProvider: 'anthropic', modelId: 'claude-sonnet-4-6',
    }
    const breakdown = computeConversationCost([kimi, flash, sonnet])

    expect(breakdown.perModel).toHaveLength(3)
    const sonnetEntry = breakdown.perModel.find(p => p.modelId === 'claude-sonnet-4-6')!
    //   (1000/1M * 3.0) + (500/1M * 15.0) = 0.003 + 0.0075 = 0.0105
    expect(sonnetEntry.total).toBeCloseTo(0.0105, 6)
  })

  it('counts turns but contributes zero when pricing is absent', () => {
    // Fresh agent with no pricing configured — the turn still happened, so
    // turnCount increments, but cost stays zero.
    const noPriceTurn: MessageUsage = {
      prompt: 1000, completion: 500, total: 1500, reasoning: 0, cached: 0, durationMs: 1000,
      modelProvider: 'mystery', modelId: 'priceless',
    }
    const breakdown = computeConversationCost([noPriceTurn])

    expect(breakdown.turnCount).toBe(1)
    expect(breakdown.total).toBe(0)
    expect(breakdown.perModel[0]!.turnCount).toBe(1)
  })

  it('groups turns with missing modelId under an unknown bucket', () => {
    // Legacy rows predating JCLAW-107's usageJson extension may lack the
    // modelId field. Aggregator must not crash and must not inflate a real
    // model's turn count.
    const legacy: MessageUsage = {
      prompt: 1000, completion: 500, total: 1500, reasoning: 0, cached: 0, durationMs: 1000,
      promptPrice: 3.0, completionPrice: 15.0,
    }
    const breakdown = computeConversationCost([legacy])

    expect(breakdown.perModel).toHaveLength(1)
    expect(breakdown.perModel[0]!.modelId).toBe('(unknown)')
  })

  it('formats compact conversation cost with the right thresholds', () => {
    expect(formatConversationCost({ total: 0, turnCount: 0, perModel: [] })).toBeNull()
    expect(formatConversationCost({ total: 0, turnCount: 3, perModel: [] })).toBe('$0.00')
    expect(formatConversationCost({ total: 0.00005, turnCount: 1, perModel: [] })).toBe('< $0.0001')
    expect(formatConversationCost({ total: 0.0149, turnCount: 1, perModel: [] })).toBe('$0.0149')
  })

  it('renders per-model tooltip one line per model', () => {
    const tooltip = formatConversationCostTooltip({
      total: 0.0149,
      turnCount: 6,
      perModel: [
        { modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud', turnCount: 3, total: 0 },
        { modelId: 'google-flash', modelProvider: 'openrouter', turnCount: 3, total: 0.0149 },
      ],
    })

    expect(tooltip).toContain('ollama-cloud/kimi-k2.5: $0.00 / 3 turns')
    expect(tooltip).toContain('openrouter/google-flash: $0.0149 / 3 turns')
  })
})
