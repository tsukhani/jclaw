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

  it('ends with the token count before and after, how much it freed and how long it took', async () => {
    const w = await mountCard({ phase: 'done', tokensBefore: 182345, tokensAfter: 41000, ms: 14200 })
    expect(w.get('[data-testid="compaction-title"]').text()).toBe('Conversation compacted')
    expect(w.get('[data-testid="compaction-result"]').text()).toBe('182k → 41k tokens · 78% freed')
    expect(w.get('[data-testid="compaction-time"]').text()).toBe('14 s')
    expect(w.get('[data-testid="compaction-bar-kept"]').attributes('aria-valuenow')).toBe('22')
  })

  it('shows a short compaction\'s time to a tenth of a second', async () => {
    const w = await mountCard({ phase: 'done', tokensBefore: 900, tokensAfter: 400, ms: 1250 })
    expect(w.get('[data-testid="compaction-time"]').text()).toBe('1.3 s')
    expect(w.get('[data-testid="compaction-result"]').text()).toBe('900 → 400 tokens · 56% freed')
  })
})
