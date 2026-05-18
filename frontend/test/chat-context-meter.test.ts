import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatContextMeter from '~/components/ChatContextMeter.vue'

/**
 * JCLAW-314 — ChatContextMeter coverage.
 *
 * The meter renders a token readout (used / capacity) and a thin progress
 * bar. Its primary responsibility is mapping numeric usage to a stable
 * label + bar width without bumping into divide-by-zero, undefined props,
 * or the 100 percent ceiling. Tests pin the three sample-point contract
 * from the JIRA acceptance: 0%, 50%, and 95%.
 */

describe('ChatContextMeter — percentage rendering at 0 / 50 / 95 percent', () => {
  it('renders 0 percent for zero prompt+completion against a non-zero capacity', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 0,
        completionTokens: 0,
        contextWindow: 100_000,
      },
    })
    const html = component.html()
    // Trigger button shows "used / capacity-kFormatted"; on zero usage the
    // left side is "0" and the bar width style sits at 0%.
    expect(html).toContain('0 / 100.0k')
    // The bar uses an inline style with the computed width — 0% means a
    // collapsed bar.
    expect(html).toContain('width: 0%')
  })

  it('renders 50 percent for half-full context (50k of 100k)', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 30_000,
        completionTokens: 20_000,
        contextWindow: 100_000,
      },
    })
    const html = component.html()
    // 30k + 20k = 50k used. The trigger uses toLocaleString() on the total,
    // so "50,000 / 100.0k" is the canonical readout.
    expect(html).toContain('50,000 / 100.0k')
    // Width style is the computed percent — 50% in this scenario.
    expect(html).toContain('width: 50%')
  })

  it('renders the >=90 percent red coloring at 95 percent fill', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 90_000,
        completionTokens: 5_000,
        contextWindow: 100_000,
      },
    })
    const html = component.html()
    // 95k of 100k -> 95% used. The component's color rule is
    //   p >= 90 -> bg-red-400, p >= 70 -> bg-amber-400, else bg-emerald-400
    expect(html).toContain('95,000 / 100.0k')
    expect(html).toContain('width: 95%')
    expect(html).toContain('bg-red-400')
  })
})

describe('ChatContextMeter — capacity handling edge cases', () => {
  it('renders an em-dash for capacity when no contextWindow is provided', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 100,
        completionTokens: 50,
        contextWindow: null,
      },
    })
    const html = component.html()
    // No capacity -> percent computes to 0 and the right-hand label shows
    // an em-dash. Trigger text reads "150 / —".
    expect(html).toContain('150 / —')
    expect(html).toContain('width: 0%')
  })

  it('clamps to 100 percent when used tokens exceed the context window', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 150_000,
        completionTokens: 0,
        contextWindow: 100_000,
      },
    })
    const html = component.html()
    // The percent computation is Math.min(100, total/capacity * 100) — the
    // bar must not visually overflow even when usage exceeds capacity.
    expect(html).toContain('width: 100%')
    expect(html).toContain('bg-red-400')
  })

  it('treats nullish promptTokens/completionTokens as zero', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: null,
        completionTokens: null,
        contextWindow: 50_000,
      },
    })
    const html = component.html()
    // Both null -> total 0 -> displayed as "0 / 50.0k"; no crash.
    expect(html).toContain('0 / 50.0k')
  })
})
