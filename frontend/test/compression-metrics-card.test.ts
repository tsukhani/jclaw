import { describe, it, expect } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import CompressionMetricsCard from '~/components/CompressionMetricsCard.vue'

/**
 * JCLAW-467: the per-agent compression metrics dashboard card. Verifies it
 * renders the summary (savings, by-type + algorithm breakdowns, CCR hit rate,
 * alerts) and shows an empty state when there's been no activity.
 */
describe('CompressionMetricsCard', () => {
  it('renders savings, breakdowns, CCR hit rate and alerts', async () => {
    registerEndpoint('/api/agents/7/compression-metrics', () => ({
      tokensSaved24h: 1200,
      tokensSaved7d: 8000,
      tokensSaved30d: 30000,
      ratioByType: [
        { contentType: 'JSON', tokensBefore: 10000, tokensAfter: 600, ratio: 0.06 },
        { contentType: 'CODE', tokensBefore: 2000, tokensAfter: 1000, ratio: 0.5 },
      ],
      algorithmUsage: [
        { algorithm: 'json-smartcrush', count: 12, tokensSaved: 9400 },
        { algorithm: 'code-structural', count: 3, tokensSaved: 1000 },
      ],
      inflationGuardCount: 2,
      ccrRetrievals: 4,
      ccrHits: 1,
      ccrHitRate: 0.25,
      alerts: ['CCR cache hit rate is 25% — the model may not be retrieving when it should'],
    }))

    const wrapper = await mountSuspended(CompressionMetricsCard, { props: { agentId: 7 } })
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Compression Metrics')
    expect(text).toContain('Saved 30d')
    expect(text).toContain('JSON')
    expect(text).toContain('json-smartcrush')
    expect(text).toContain('25%') // CCR hit rate
    expect(text).toContain('CCR cache hit rate is 25%') // alert surfaced
  })

  it('shows an empty state when the agent has no compression activity', async () => {
    registerEndpoint('/api/agents/8/compression-metrics', () => ({
      tokensSaved24h: 0,
      tokensSaved7d: 0,
      tokensSaved30d: 0,
      ratioByType: [],
      algorithmUsage: [],
      inflationGuardCount: 0,
      ccrRetrievals: 0,
      ccrHits: 0,
      ccrHitRate: 0,
      alerts: [],
    }))

    const wrapper = await mountSuspended(CompressionMetricsCard, { props: { agentId: 8 } })
    await flushPromises()
    expect(wrapper.text()).toContain('No compression activity yet')
  })
})
