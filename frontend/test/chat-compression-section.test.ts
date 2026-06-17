import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import ChatCompressionSection from '~/components/ChatCompressionSection.vue'

/**
 * JCLAW-467: Chat Compression dashboard section. Verifies client-side
 * aggregation of the raw metric rows (savings, by-type/algorithm breakdowns,
 * CCR hit rate, alerts) and the empty state.
 */
const AGENTS = [{ id: 1, name: 'main' }, { id: 2, name: 'helper' }]

function payload() {
  return {
    since: '2026-01-01T00:00:00Z',
    rows: [
      { timestamp: '2026-06-01T00:00:00Z', agentId: '1', channel: 'web', contentType: 'JSON', algorithm: 'json-smartcrush', tokensBefore: 10000, tokensAfter: 600, kind: 'COMPRESSION', ccrHit: null },
      { timestamp: '2026-06-01T00:00:00Z', agentId: '1', channel: 'web', contentType: 'CODE', algorithm: 'code-structural', tokensBefore: 2000, tokensAfter: 180, kind: 'COMPRESSION', ccrHit: null },
      { timestamp: '2026-06-01T00:00:00Z', agentId: '1', channel: 'telegram', contentType: 'TEXT', algorithm: 'text-statistical', tokensBefore: 1000, tokensAfter: 700, kind: 'COMPRESSION', ccrHit: null },
      { timestamp: '2026-06-01T00:00:00Z', agentId: null, channel: null, contentType: null, algorithm: 'ccr', tokensBefore: 0, tokensAfter: 0, kind: 'CCR_RETRIEVAL', ccrHit: true },
      { timestamp: '2026-06-01T00:00:00Z', agentId: null, channel: null, contentType: null, algorithm: 'ccr', tokensBefore: 0, tokensAfter: 0, kind: 'CCR_RETRIEVAL', ccrHit: false },
    ],
  }
}

describe('ChatCompressionSection', () => {
  beforeEach(() => clearNuxtData())

  it('aggregates rows into breakdowns, CCR rate and alerts', async () => {
    registerEndpoint('/api/metrics/compression', () => payload())
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('Chat Compression')
    expect(text).toContain('JSON')
    expect(text).toContain('json-smartcrush')
    expect(text).toContain('(1/2)') // CCR hits / total
    // JSON ratio 600/10000 = 6% kept (< 10%) → low-ratio alert
    expect(text).toContain('JSON compression ratio is very low')
  })

  it('shows an empty state when there is no activity', async () => {
    registerEndpoint('/api/metrics/compression', () => ({ since: '', rows: [] }))
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: [] } })
    await flushPromises()
    expect(wrapper.text()).toContain('No compression activity')
  })
})
