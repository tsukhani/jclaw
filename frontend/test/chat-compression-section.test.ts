import { describe, it, expect, beforeEach, vi } from 'vitest'
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
    expect(text).toContain('1 / 2 hits') // CCR hits / total tile
    // JSON ratio 600/10000 = 6% kept (< 10%) AND CCR hit rate 1/2 = 50% (< 90%,
    // retrieval unhealthy) → low-ratio alert fires.
    expect(text).toContain('JSON compression ratio is very low')
  })

  it('suppresses low-ratio alerts when CCR retrieval is healthy', async () => {
    // JSON is still 6% kept, but every ccr_retrieve hit → elided originals are
    // recoverable, so the aggressive ratio is by-design, not data loss.
    registerEndpoint('/api/metrics/compression', () => ({
      since: '2026-01-01T00:00:00Z',
      rows: [
        { timestamp: '2026-06-01T00:00:00Z', agentId: '1', channel: 'web', contentType: 'JSON', algorithm: 'json-smartcrush', tokensBefore: 10000, tokensAfter: 600, kind: 'COMPRESSION', ccrHit: null },
        { timestamp: '2026-06-01T00:00:00Z', agentId: null, channel: null, contentType: null, algorithm: 'ccr', tokensBefore: 0, tokensAfter: 0, kind: 'CCR_RETRIEVAL', ccrHit: true },
        { timestamp: '2026-06-01T00:00:00Z', agentId: null, channel: null, contentType: null, algorithm: 'ccr', tokensBefore: 0, tokensAfter: 0, kind: 'CCR_RETRIEVAL', ccrHit: true },
        { timestamp: '2026-06-01T00:00:00Z', agentId: null, channel: null, contentType: null, algorithm: 'ccr', tokensBefore: 0, tokensAfter: 0, kind: 'CCR_RETRIEVAL', ccrHit: true },
      ],
    }))
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()
    const text = wrapper.text()

    expect(text).toContain('JSON') // still rendered in the breakdown
    expect(text).not.toContain('compression ratio is very low')
  })

  it('lists fetch-backs per tool for the agent in view, whatever the channel', async () => {
    const ccr = (agentId: string, toolName: string | null, ccrHit: boolean) => ({
      timestamp: '2026-06-01T00:00:00Z', agentId, channel: null, contentType: null, algorithm: 'ccr',
      tokensBefore: 0, tokensAfter: 0, kind: 'CCR_RETRIEVAL', ccrHit, toolName,
    })
    registerEndpoint('/api/metrics/compression', () => ({
      since: '2026-01-01T00:00:00Z',
      rows: [
        { timestamp: '2026-06-01T00:00:00Z', agentId: '1', channel: 'web', contentType: 'JSON', algorithm: 'json-smartcrush', tokensBefore: 1000, tokensAfter: 600, kind: 'COMPRESSION', ccrHit: null, toolName: null },
        ccr('1', 'web_fetch', true),
        ccr('1', 'exec', true),
        ccr('1', 'web_fetch', true),
        ccr('2', 'web_fetch', true),
        ccr('1', null, false),
      ],
    }))
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()
    await wrapper.find('button[title="Table view"]').trigger('click')
    const perTool = () => wrapper.findAll('[data-testid="fetch-backs-by-tool"] tbody tr')
      .map(tr => tr.findAll('td').map(td => td.text()))

    expect(perTool()).toEqual([['web_fetch', '3'], ['exec', '1']])
    expect(wrapper.text()).toContain('4 / 5 hits')

    await wrapper.find('select[aria-label="Filter by agent"]').setValue('1')
    expect(perTool()).toEqual([['web_fetch', '2'], ['exec', '1']])
    expect(wrapper.text()).toContain('3 / 4 hits')

    await wrapper.find('select[aria-label="Filter by channel"]').setValue('web')
    expect(perTool()).toEqual([['web_fetch', '2'], ['exec', '1']])

    await wrapper.find('select[aria-label="Filter by agent"]').setValue('2')
    expect(perTool()).toEqual([['web_fetch', '1']])
  })

  it('shows an empty state when there is no activity', async () => {
    registerEndpoint('/api/metrics/compression', () => ({ since: '', rows: [] }))
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: [] } })
    await flushPromises()
    expect(wrapper.text()).toContain('No compression activity')
  })
})

describe('ChatCompressionSection — a failed reset (JCLAW-1221)', () => {
  it('says why the metrics were not cleared', async () => {
    registerEndpoint('/api/metrics/compression', () => payload())
    const off = registerEndpoint('/api/metrics/compression', {
      method: 'DELETE',
      handler: async (event) => {
        const { setResponseStatus } = await import('h3')
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    try {
      const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
      await flushPromises()
      await wrapper.find('button[title="Reset compression metrics"]').trigger('click')
      await vi.waitFor(() => expect(wrapper.find('[data-testid="api-error"]').exists()).toBe(true))
      expect(wrapper.find('[data-testid="api-error"]').text()).toContain('/api/metrics/compression')
    }
    finally {
      off()
    }
  })
})
