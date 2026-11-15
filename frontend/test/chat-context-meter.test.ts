import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatContextMeter from '~/components/ChatContextMeter.vue'

/**
 * JCLAW-314 — ChatContextMeter coverage.
 *
 * The meter renders a token readout (used / what it fills toward) and a thin
 * progress bar, and its hover card says in words what the reading means.
 * Anchors via data-testid attributes on the readout, the bar, the percent and
 * the summary so assertions don't break on adjacent class/style refactors.
 */

type MeterProps = InstanceType<typeof ChatContextMeter>['$props']

async function openCard(props: MeterProps) {
  const component = await mountSuspended(ChatContextMeter, { props })
  const trigger = component.find('button')
  await trigger.trigger('mouseenter')
  return { component, trigger }
}

/** The hover card's rows as label → value, read from the teleported popover. */
function rows(): Record<string, string | undefined> {
  return Object.fromEntries(
    [...document.body.querySelectorAll('dl > div')]
      .map(row => [row.querySelector('dt')?.textContent?.trim(), row.querySelector('dd')?.textContent?.trim()])
      .filter(([label]) => label),
  )
}

function text(testId: string): string | undefined {
  return document.body.querySelector(`[data-testid="${testId}"]`)?.textContent?.trim()
}

describe('ChatContextMeter — percentage rendering at 0 / 50 / 95 percent', () => {
  it('renders 0 percent for zero prompt+completion against a non-zero capacity', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 0, completionTokens: 0, contextWindow: 100_000 },
    })
    expect(component.find('[data-testid="context-readout"]').text()).toBe('0 / 100.0k')
    expect(component.find('[data-testid="context-bar"]').attributes('style')).toContain('width: 0%')
  })

  it('renders 50 percent for half-full context (50k of 100k)', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 30_000, completionTokens: 20_000, contextWindow: 100_000 },
    })
    expect(component.find('[data-testid="context-readout"]').text()).toBe('50,000 / 100.0k')
    expect(component.find('[data-testid="context-bar"]').attributes('style')).toContain('width: 50%')
  })

  it('repeats the bar under the card\'s title, in the same fill and color', async () => {
    const { trigger } = await openCard({ promptTokens: 30_000, completionTokens: 20_000, contextWindow: 100_000 })
    const bar = document.body.querySelector<HTMLElement>('[data-testid="context-card-bar"]')!
    expect(bar.style.width).toBe('50%')
    expect(bar.classList.contains('bg-ok')).toBe(true)
    await trigger.trigger('mouseleave')
  })

  it('draws no card bar when there is nothing to fill toward', async () => {
    const { trigger } = await openCard({ promptTokens: 150, completionTokens: 0 })
    expect(document.body.querySelector('[data-testid="context-card-bar"]')).toBeNull()
    await trigger.trigger('mouseleave')
  })

  it('renders the red coloring at 95 percent of the model window', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 90_000, completionTokens: 5_000, contextWindow: 100_000 },
    })
    expect(component.find('[data-testid="context-readout"]').text()).toBe('95,000 / 100.0k')
    const bar = component.find('[data-testid="context-bar"]')
    expect(bar.attributes('style')).toContain('width: 95%')
    expect(bar.classes()).toContain('bg-danger')
  })
})

describe('ChatContextMeter — filling toward the summary point', () => {
  it('fills toward where older messages get summarized, not the model window', async () => {
    const { component, trigger } = await openCard({
      promptTokens: 28_025, completionTokens: 179, contextWindow: 1_048_576, compactAt: 150_000,
    })

    expect(component.find('[data-testid="context-readout"]').text()).toBe('28,204 / 150.0k')
    expect(component.find('[data-testid="context-bar"]').attributes('style'))
      .toContain(`width: ${(28_204 / 150_000) * 100}%`)
    expect(text('context-percent')).toBe('19% full')
    expect(text('context-summary')).toBe('The agent rereads this conversation every time it answers: 28,204 tokens right now. '
      + 'At about 150,000, JClaw summarizes the oldest messages to make room.')
    expect(rows()['Model\'s limit']).toBe('1,048,576 tokens')
    await trigger.trigger('mouseleave')
  })

  it('turns amber at 80 percent of the summary point and not one token before', async () => {
    const at = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 120_000, completionTokens: 0, contextWindow: 1_048_576, compactAt: 150_000 },
    })
    const below = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 119_999, completionTokens: 0, contextWindow: 1_048_576, compactAt: 150_000 },
    })

    expect(at.find('[data-testid="context-bar"]').classes()).toContain('bg-warning')
    expect(below.find('[data-testid="context-bar"]').classes()).toContain('bg-ok')
  })

  it('turns red only near the model window, where messages are cut rather than summarized', async () => {
    const at = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 90_000, completionTokens: 0, contextWindow: 100_000 },
    })
    const below = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 89_999, completionTokens: 0, contextWindow: 100_000 },
    })

    expect(at.find('[data-testid="context-bar"]').classes()).toContain('bg-danger')
    expect(below.find('[data-testid="context-bar"]').classes()).toContain('bg-warning')
  })

  it('names the model window as the limit when no summary point is known, and has no separate limit row', async () => {
    const { trigger } = await openCard({ promptTokens: 1_000, completionTokens: 200, contextWindow: 100_000 })

    expect(text('context-summary')).toBe('The agent rereads this conversation every time it answers: 1,200 tokens right now. '
      + 'This model can take 100,000 at most.')
    expect(rows()['Model\'s limit']).toBeUndefined()
    await trigger.trigger('mouseleave')
  })

  it('says only what it holds when neither a summary point nor a window is known', async () => {
    const { component, trigger } = await openCard({ promptTokens: 100, completionTokens: 50, contextWindow: null })

    expect(component.find('[data-testid="context-readout"]').text()).toBe('150 / —')
    expect(text('context-summary')).toBe('The agent rereads this conversation every time it answers: 150 tokens right now.')
    expect(text('context-percent')).toBeUndefined()
    await trigger.trigger('mouseleave')
  })

  it('names only the summary point past 80% and past 100%, with no count of messages a summary needs', async () => {
    const near = await openCard({ promptTokens: 55_589, completionTokens: 0, contextWindow: 131_072, compactAt: 65_536, compactionCount: 1 })
    expect(text('context-summary')).toBe('The agent rereads this conversation every time it answers: 55,589 tokens right now. '
      + 'At about 65,536, JClaw summarizes the oldest messages to make room.')
    await near.trigger.trigger('mouseleave')

    const past = await openCard({ promptTokens: 70_000, completionTokens: 0, contextWindow: 131_072, compactAt: 65_536 })
    expect(text('context-summary')).toBe('The agent rereads this conversation every time it answers: 70,000 tokens right now. '
      + 'At about 65,536, JClaw summarizes the oldest messages to make room.')
    await past.trigger.trigger('mouseleave')
  })

  it('says the last reply\'s reading is out of date once its oldest messages were summarized after it', async () => {
    const { trigger } = await openCard({
      promptTokens: 68_000, completionTokens: 316, contextWindow: 131_072, compactAt: 65_536, summarizedSinceReply: true,
    })

    expect(text('context-summary')).toBe('The agent\'s last reply reread 68,316 tokens of this conversation. Its oldest '
      + 'messages have since been summarized, so the next reply starts from a shorter conversation.')
    await trigger.trigger('mouseleave')
  })

  it('says the oldest messages are left out when the chat comes near the model\'s own limit', async () => {
    const { trigger } = await openCard({ promptTokens: 90_000, completionTokens: 0, contextWindow: 100_000 })

    expect(text('context-summary')).toBe('The agent rereads this conversation every time it answers: 90,000 tokens right now. '
      + 'This model can take 100,000 at most. This close to the model\'s limit, the oldest messages are left out of what it reads.')
    await trigger.trigger('mouseleave')
  })

  it('says there is nothing to measure before the first reply', async () => {
    const { trigger } = await openCard({ promptTokens: 0, completionTokens: 0, contextWindow: 100_000, compactAt: 50_000 })

    expect(text('context-summary')).toBe('Nothing to measure until the agent replies.')
    await trigger.trigger('mouseleave')
  })
})

describe('ChatContextMeter — capacity handling edge cases', () => {
  it('clamps to 100 percent when used tokens exceed the context window', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 150_000, completionTokens: 0, contextWindow: 100_000 },
    })
    const bar = component.find('[data-testid="context-bar"]')
    expect(bar.attributes('style')).toContain('width: 100%')
    expect(bar.classes()).toContain('bg-danger')
  })

  it('treats nullish promptTokens/completionTokens as zero', async () => {
    const component = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: null, completionTokens: null, contextWindow: 50_000 },
    })
    expect(component.find('[data-testid="context-readout"]').text()).toBe('0 / 50.0k')
  })
})

describe('ChatContextMeter — the conversation so far', () => {
  it('shows replies and cost when the cost summary has them', async () => {
    const { trigger } = await openCard({
      promptTokens: 100, completionTokens: 50, contextWindow: 50_000, costLabel: '$0.0042', turnCount: 3,
    })

    expect(rows().Replies).toBe('3')
    expect(rows().Cost).toBe('$0.0042')
    await trigger.trigger('mouseleave')
  })

  it('shows the tokens processed, with what that number counts, only when there are any', async () => {
    const some = await openCard({ promptTokens: 100, completionTokens: 50, contextWindow: 50_000, cumulativeTokens: 9_876 })
    expect(rows()['Tokens processed']).toBe('9,876')
    expect(document.body.textContent).toContain('Tokens processed counts every reread, as providers bill it.')
    await some.trigger.trigger('mouseleave')

    const none = await openCard({ promptTokens: 100, completionTokens: 50, contextWindow: 50_000, cumulativeTokens: 0 })
    expect(rows()['Tokens processed']).toBeUndefined()
    expect(document.body.textContent).not.toContain('counts every reread')
    await none.trigger.trigger('mouseleave')
  })

  it('shows messages, your messages and summaries, zero included', async () => {
    const { trigger } = await openCard({
      promptTokens: 100, completionTokens: 50, contextWindow: 50_000, messageCount: 1234, userPromptCount: 56, compactionCount: 0,
    })

    expect(rows()['All saved messages']).toBe('1,234')
    expect(rows()['Your messages']).toBe('56')
    expect(rows()['Summarized so far']).toBe('Not yet')
    await trigger.trigger('mouseleave')
  })

  it('counts summaries in words: once, then a number of times', async () => {
    const once = await openCard({ promptTokens: 100, completionTokens: 50, contextWindow: 50_000, compactionCount: 1 })
    expect(rows()['Summarized so far']).toBe('Once')
    await once.trigger.trigger('mouseleave')

    const twice = await openCard({ promptTokens: 100, completionTokens: 50, contextWindow: 50_000, compactionCount: 2 })
    expect(rows()['Summarized so far']).toBe('2 times')
    await twice.trigger.trigger('mouseleave')
  })

  it('shows no count it has no value for, rather than a zero', async () => {
    const { trigger } = await openCard({
      promptTokens: 100, completionTokens: 50, contextWindow: 50_000, messageCount: null, userPromptCount: null, compactionCount: null,
    })

    expect(rows()['All saved messages']).toBeUndefined()
    expect(rows()['Your messages']).toBeUndefined()
    expect(rows()['Summarized so far']).toBeUndefined()
    expect(document.body.textContent).not.toContain('So far in this conversation')
    await trigger.trigger('mouseleave')
  })
})

describe('ChatContextMeter — trigger button interactions', () => {
  it('opens the popover on mouseenter and closes on mouseleave', async () => {
    const { component, trigger } = await openCard({ promptTokens: 100, completionTokens: 50, contextWindow: 50_000 })
    expect(component.html() + document.body.innerHTML).toContain('Conversation memory')
    await trigger.trigger('mouseleave')
    // We don't assert focus because Popover's Teleport + jsdom focus handling is unreliable.
    await trigger.trigger('focus')
    await trigger.trigger('blur')
  })

  it('closes when the pointer leaves and leaves focus in the message box it was in', async () => {
    const composer = document.createElement('textarea')
    document.body.appendChild(composer)
    composer.focus()
    const component = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 100, completionTokens: 50, contextWindow: 50_000 },
      attachTo: document.body,
    })
    const trigger = component.find('button')
    try {
      await trigger.trigger('mouseenter')
      expect(text('context-summary')).toBeDefined()

      await trigger.trigger('mouseleave')
      await new Promise(resolve => setTimeout(resolve, 50))

      expect(document.activeElement).toBe(composer)
      expect(text('context-summary')).toBeUndefined()
    }
    finally {
      component.unmount()
      composer.remove()
    }
  })

  it('opens on keyboard focus and lets focus move on, closing behind it', async () => {
    const next = document.createElement('button')
    const component = await mountSuspended(ChatContextMeter, {
      props: { promptTokens: 100, completionTokens: 50, contextWindow: 50_000 },
      attachTo: document.body,
    })
    document.body.appendChild(next)
    const trigger = component.find('button')
    try {
      ;(trigger.element as HTMLButtonElement).focus()
      await new Promise(resolve => setTimeout(resolve, 0))
      expect(text('context-summary')).toBeDefined()

      next.focus()
      await new Promise(resolve => setTimeout(resolve, 50))

      expect(document.activeElement).toBe(next)
      expect(text('context-summary')).toBeUndefined()
    }
    finally {
      component.unmount()
      next.remove()
    }
  })

  it('renders the trigger right-side raw when small and kFormatted from 1000', async () => {
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
    const { trigger } = await openCard({ promptTokens: 200, completionTokens: 0, contextWindow: 100_000 })
    expect(text('context-percent')).toBe('0.2% full')
    await trigger.trigger('mouseleave')
  })
})
