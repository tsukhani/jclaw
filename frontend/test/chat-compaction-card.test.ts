import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatCompactionCard from '~/components/chat/ChatCompactionCard.vue'
import type { CompactionCard } from '~/composables/useChatStream'

async function mountCard(card: CompactionCard) {
  return mountSuspended(ChatCompactionCard, { props: { card } })
}

describe('ChatCompactionCard', () => {
  it('shows the tokens being compressed and the share of rows summarized', async () => {
    const w = await mountCard({ phase: 'start', tokensBefore: 182345, done: 45, total: 90 })
    expect(w.get('[data-testid="compaction-title"]').text()).toBe('Compacting conversation')
    expect(w.get('[data-testid="compaction-summary"]').text()).toBe('Summarizing 90 earlier messages · 182k tokens')
    const bar = w.get('[data-testid="compaction-bar-rows"]')
    expect(bar.attributes('aria-valuenow')).toBe('50')
    expect(bar.get('div').attributes('style')).toContain('width: 50%')
  })

  it('slides an indeterminate bar until the first summary has landed', async () => {
    const w = await mountCard({ phase: 'start', tokensBefore: 182345, done: 0, total: 90 })
    expect(w.find('[data-testid="compaction-bar-indeterminate"]').exists()).toBe(true)
    expect(w.find('[data-testid="compaction-bar-rows"]').exists()).toBe(false)
  })

  it('names no token count for a /compact, which learns it only when it ends', async () => {
    const w = await mountCard({ phase: 'start', tokensBefore: null })
    expect(w.get('[data-testid="compaction-summary"]').text()).toBe('Summarizing earlier messages')
    expect(w.find('[data-testid="compaction-bar-indeterminate"]').exists()).toBe(true)
  })

  it('says when it is merging earlier summaries, with a sliding bar, though every row is summarized', async () => {
    const two = await mountCard({ phase: 'start', tokensBefore: 40000, done: 11, total: 11, stage: 'merging', summaries: 2 })
    expect(two.get('[data-testid="compaction-title"]').text()).toBe('Compacting conversation')
    expect(two.get('[data-testid="compaction-summary"]').text()).toBe('Merging 2 earlier summaries into one')
    expect(two.find('[data-testid="compaction-bar-indeterminate"]').exists()).toBe(true)
    expect(two.find('[data-testid="compaction-bar-rows"]').exists()).toBe(false)

    const unnamed = await mountCard({ phase: 'start', tokensBefore: null, stage: 'merging' })
    expect(unnamed.get('[data-testid="compaction-summary"]').text()).toBe('Merging earlier summaries')
  })

  it('ends with the token count before and after, how much it freed and how long it took', async () => {
    const w = await mountCard({ phase: 'done', tokensBefore: 182345, tokensAfter: 41000, ms: 14200 })
    expect(w.get('[data-testid="compaction-title"]').text()).toBe('Conversation compacted')
    expect(w.get('[data-testid="compaction-result"]').text()).toBe('182k → 41k tokens · 78% freed')
    expect(w.get('[data-testid="compaction-time"]').text()).toBe('14 s')
    expect(w.get('[data-testid="compaction-bar-kept"]').attributes('aria-valuenow')).toBe('22')
  })

  it('says how many messages a compaction after a reply summarized, having no token count to show', async () => {
    const ten = await mountCard({ phase: 'done', tokensBefore: null, ms: 3200, done: 10, total: 10 })
    expect(ten.get('[data-testid="compaction-title"]').text()).toBe('Conversation compacted')
    expect(ten.get('[data-testid="compaction-result"]').text()).toBe('10 earlier messages summarized')
    expect(ten.get('[data-testid="compaction-time"]').text()).toBe('3.2 s')

    const one = await mountCard({ phase: 'done', tokensBefore: null, ms: 900, done: 1, total: 1 })
    expect(one.get('[data-testid="compaction-result"]').text()).toBe('1 earlier message summarized')
  })

  it('says no summary was made, with the reason as the server gave it and no bar', async () => {
    const w = await mountCard({ phase: 'skipped', tokensBefore: 182345, reason: 'The summary model did not answer.' })
    expect(w.get('[data-testid="compaction-title"]').text()).toBe('Summary not made')
    expect(w.get('[data-testid="compaction-skip-reason"]').text()).toBe('The summary model did not answer.')
    expect(w.find('[data-testid="compaction-result"]').exists()).toBe(false)
    expect(w.find('[role="progressbar"]').exists()).toBe(false)
    expect(w.get('[data-testid="compaction-time"]').text()).toBe('')
  })

  it('says only that no summary was made when the server named no reason', async () => {
    const w = await mountCard({ phase: 'skipped', tokensBefore: null })
    expect(w.get('[data-testid="compaction-title"]').text()).toBe('Summary not made')
    expect(w.find('[data-testid="compaction-skip-reason"]').exists()).toBe(false)
    expect(w.find('[data-testid="compaction-result"]').exists()).toBe(false)
  })

  it('shows a short compaction\'s time to a tenth of a second', async () => {
    const w = await mountCard({ phase: 'done', tokensBefore: 900, tokensAfter: 400, ms: 1250 })
    expect(w.get('[data-testid="compaction-time"]').text()).toBe('1.3 s')
    expect(w.get('[data-testid="compaction-result"]').text()).toBe('900 → 400 tokens · 56% freed')
  })
})
