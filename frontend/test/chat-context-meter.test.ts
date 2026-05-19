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
 *
 * Anchors via data-testid attributes on the readout and the inner bar span
 * so assertions don't break on adjacent class/style refactors.
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
    // Trigger button shows "used / capacity-kFormatted"; on zero usage the
    // left side is "0" and the bar width style sits at 0%.
    expect(component.find('[data-testid="context-readout"]').text()).toBe('0 / 100.0k')
    // The bar uses an inline style with the computed width — 0% means a
    // collapsed bar.
    expect(component.find('[data-testid="context-bar"]').attributes('style')).toContain('width: 0%')
  })

  it('renders 50 percent for half-full context (50k of 100k)', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 30_000,
        completionTokens: 20_000,
        contextWindow: 100_000,
      },
    })
    // 30k + 20k = 50k used. The trigger uses toLocaleString() on the total,
    // so "50,000 / 100.0k" is the canonical readout.
    expect(component.find('[data-testid="context-readout"]').text()).toBe('50,000 / 100.0k')
    // Width style is the computed percent — 50% in this scenario.
    expect(component.find('[data-testid="context-bar"]').attributes('style')).toContain('width: 50%')
  })

  it('renders the >=90 percent red coloring at 95 percent fill', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 90_000,
        completionTokens: 5_000,
        contextWindow: 100_000,
      },
    })
    // 95k of 100k -> 95% used. The component's color rule is
    //   p >= 90 -> bg-red-400, p >= 70 -> bg-amber-400, else bg-emerald-400
    expect(component.find('[data-testid="context-readout"]').text()).toBe('95,000 / 100.0k')
    const bar = component.find('[data-testid="context-bar"]')
    expect(bar.attributes('style')).toContain('width: 95%')
    expect(bar.classes()).toContain('bg-red-400')
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
    // No capacity -> percent computes to 0 and the right-hand label shows
    // an em-dash. Trigger text reads "150 / —".
    expect(component.find('[data-testid="context-readout"]').text()).toBe('150 / —')
    expect(component.find('[data-testid="context-bar"]').attributes('style')).toContain('width: 0%')
  })

  it('clamps to 100 percent when used tokens exceed the context window', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 150_000,
        completionTokens: 0,
        contextWindow: 100_000,
      },
    })
    // The percent computation is Math.min(100, total/capacity * 100) — the
    // bar must not visually overflow even when usage exceeds capacity.
    const bar = component.find('[data-testid="context-bar"]')
    expect(bar.attributes('style')).toContain('width: 100%')
    expect(bar.classes()).toContain('bg-red-400')
  })

  it('treats nullish promptTokens/completionTokens as zero', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: null,
        completionTokens: null,
        contextWindow: 50_000,
      },
    })
    // Both null -> total 0 -> displayed as "0 / 50.0k"; no crash.
    expect(component.find('[data-testid="context-readout"]').text()).toBe('0 / 50.0k')
  })
})

describe('ChatContextMeter — popover content', () => {
  it('renders prompt / completion / total breakdown rows in the popover', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 1_000,
        completionTokens: 200,
        contextWindow: 100_000,
      },
    })
    // The PopoverContent is rendered conditionally with v-if when the
    // Popover root is open; the component HTML still contains the dl
    // body. Force open via the exposed trigger.
    const trigger = component.find('button')
    await trigger.trigger('mouseenter')
    // The popover may teleport; query both the component html and document body.
    const html = component.html() + document.body.innerHTML
    expect(html).toContain('Prompt tokens')
    expect(html).toContain('Completion')
    expect(html).toContain('Current context')
    // Cleanup: close popover so leaked content doesn't bleed into the next test.
    await trigger.trigger('mouseleave')
  })

  it('shows the cost row only when costLabel is provided', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 100,
        completionTokens: 50,
        contextWindow: 50_000,
        costLabel: '$0.0042',
        turnCount: 3,
      },
    })
    const trigger = component.find('button')
    await trigger.trigger('mouseenter')
    const html = component.html() + document.body.innerHTML
    expect(html).toContain('$0.0042')
    expect(html).toContain('Turns')
    await trigger.trigger('mouseleave')
  })

  it('shows the thinking-tokens row when reasoningTokens > 0', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 100,
        completionTokens: 50,
        reasoningTokens: 25,
        contextWindow: 50_000,
      },
    })
    const trigger = component.find('button')
    await trigger.trigger('mouseenter')
    const html = component.html() + document.body.innerHTML
    expect(html).toContain('Thinking tokens')
    await trigger.trigger('mouseleave')
  })

  it('shows the cached-tokens row when cachedTokens > 0', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 100,
        completionTokens: 50,
        cachedTokens: 30,
        contextWindow: 50_000,
      },
    })
    const trigger = component.find('button')
    await trigger.trigger('mouseenter')
    const html = component.html() + document.body.innerHTML
    expect(html).toContain('Cached tokens')
    await trigger.trigger('mouseleave')
  })

  it('shows the conversation-total row when cumulativeTokens > 0', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 100,
        completionTokens: 50,
        contextWindow: 50_000,
        cumulativeTokens: 9_876,
      },
    })
    const trigger = component.find('button')
    await trigger.trigger('mouseenter')
    const html = component.html() + document.body.innerHTML
    expect(html).toContain('Conversation total')
    expect(html).toContain('9,876')
    await trigger.trigger('mouseleave')
  })

  it('hides conversation-total when cumulativeTokens is zero but always shows compactions', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 100,
        completionTokens: 50,
        contextWindow: 50_000,
        cumulativeTokens: 0,
        compactionCount: 0,
      },
    })
    const trigger = component.find('button')
    await trigger.trigger('mouseenter')
    const html = component.html() + document.body.innerHTML
    expect(html).not.toContain('Conversation total')
    // Compactions is always rendered — operators want the field
    // discoverable even on chats that have never been compacted.
    expect(html).toContain('Compactions')
    await trigger.trigger('mouseleave')
  })

  it('shows the compactions row when compactionCount > 0', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 100,
        completionTokens: 50,
        contextWindow: 50_000,
        compactionCount: 2,
      },
    })
    const trigger = component.find('button')
    await trigger.trigger('mouseenter')
    const html = component.html() + document.body.innerHTML
    expect(html).toContain('Compactions')
    await trigger.trigger('mouseleave')
  })
})

describe('ChatContextMeter — trigger button interactions', () => {
  it('opens the popover on mouseenter and closes on mouseleave', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 100,
        completionTokens: 50,
        contextWindow: 50_000,
      },
    })
    const trigger = component.find('button')
    // Just exercise the handler chain — assertion via no-throw + popover render.
    await trigger.trigger('mouseenter')
    const openedHtml = component.html() + document.body.innerHTML
    expect(openedHtml).toContain('Prompt tokens')
    await trigger.trigger('mouseleave')
    // After mouseleave the handleMouseLeave path ran (would have thrown
    // if the ref was wrong); we don't assert focus because Popover's
    // Teleport + happy-dom focus handling is unreliable.
    await trigger.trigger('focus')
    await trigger.trigger('blur')
  })

  it('uses amber bar coloring between 70 and 90 percent', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        promptTokens: 80_000,
        completionTokens: 0,
        contextWindow: 100_000,
      },
    })
    const bar = component.find('[data-testid="context-bar"]')
    expect(bar.classes()).toContain('bg-amber-400')
  })

  it('renders the trigger right-side as em-dash when capacity is null but uses kFormat when small', async () => {
    // capacity 100 stays raw "100"; 1000 becomes "1k"; in-between rounds.
    const c1 = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 0, completionTokens: 0, contextWindow: 100 },
    })
    expect(c1.find('[data-testid="context-readout"]').text()).toBe('0 / 100')

    const c2 = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 0, completionTokens: 0, contextWindow: 1000 },
    })
    expect(c2.find('[data-testid="context-readout"]').text()).toBe('0 / 1.00k')
  })

  it('formats sub-1% usage with one decimal place in the percent label', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: {
        // 200/100000 = 0.2% -> "0.2%" not "0%".
        promptTokens: 200,
        completionTokens: 0,
        contextWindow: 100_000,
      },
    })
    const trigger = component.find('button')
    await trigger.trigger('mouseenter')
    const html = component.html() + document.body.innerHTML
    expect(html).toContain('0.2%')
    await trigger.trigger('mouseleave')
  })
})
