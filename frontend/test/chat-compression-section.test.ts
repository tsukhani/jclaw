import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { getQuery, setResponseStatus, type H3Event } from 'h3'
import { clearNuxtData } from '#app'
import ChatCompressionSection from '~/components/ChatCompressionSection.vue'
import type { CompressionTotals } from '~/types/api'

/**
 * JCLAW-467, JF-139: Chat Compression dashboard section. The server sends the totals for the
 * window, agent and channel; the panel derives the ratios, orders the breakdowns, raises the alerts,
 * and asks again whenever a filter changes.
 */
const AGENTS = [{ id: 1, name: 'main' }, { id: 2, name: 'helper' }]

function totals(fields: Partial<CompressionTotals> = {}): CompressionTotals {
  return {
    since: '2026-01-01T00:00:00Z', agents: [], channels: [], compressions: 0, tokensBefore: 0, tokensAfter: 0,
    inflationGuards: 0, ccrRetrievals: 0, ccrHits: 0, byType: [], byAlgorithm: [], byTool: [], ...fields,
  }
}

// Agent 1 compressed on web and telegram; the two retrievals were saved before they named their agent.
const WINDOW = { agents: ['1'], channels: ['telegram', 'web'] }
const JSON_WEB = { type: { contentType: 'JSON', tokensBefore: 10000, tokensAfter: 600 },
  algorithm: { algorithm: 'json-smartcrush', count: 1, tokensBefore: 10000, tokensAfter: 600 } }
const CODE_WEB = { type: { contentType: 'CODE', tokensBefore: 2000, tokensAfter: 180 },
  algorithm: { algorithm: 'code-structural', count: 1, tokensBefore: 2000, tokensAfter: 180 } }
const TEXT_TELEGRAM = { type: { contentType: 'TEXT', tokensBefore: 1000, tokensAfter: 700 },
  algorithm: { algorithm: 'text-statistical', count: 1, tokensBefore: 1000, tokensAfter: 700 } }
const AGENT_ONE = {
  ...WINDOW, compressions: 3, tokensBefore: 13000, tokensAfter: 1480,
  byType: [CODE_WEB.type, JSON_WEB.type, TEXT_TELEGRAM.type],
  byAlgorithm: [CODE_WEB.algorithm, JSON_WEB.algorithm, TEXT_TELEGRAM.algorithm],
}

function payload() {
  return totals({ ...AGENT_ONE, ccrRetrievals: 2, ccrHits: 1 })
}

/** Answers each request with the totals for its filters, keyed `agentId|channel`, and records what was asked. */
function answers(byFilter: Record<string, CompressionTotals>, seen: Record<string, unknown>[] = []) {
  return (event: H3Event) => {
    const query = getQuery(event)
    seen.push(query)
    const key = `${query.agentId ?? ''}|${query.channel ?? ''}`
    const answer = byFilter[key]
    if (!answer) throw new Error(`no totals stubbed for ${key}`)
    return answer
  }
}

function costUsage(fields: Record<string, number>) {
  return JSON.stringify({
    completion: 20, total: 0, reasoning: 0, durationMs: 100, promptPrice: 0.5, completionPrice: 1,
    modelId: 'gpt-4.1', modelProvider: 'openai', ...fields,
  })
}

/** Each KPI tile as [label, figure, caption]. */
const tiles = (wrapper: VueWrapper) => wrapper.findAll('.grid-cols-2 > div')
  .map(tile => tile.findAll(':scope > div').map(part => part.text()))
const tables = (wrapper: VueWrapper) => wrapper.findAll('table')
  .map(table => table.findAll('tbody tr').map(tr => tr.findAll('td').map(td => td.text())))
const alerts = (wrapper: VueWrapper) => wrapper.findAll('p').map(p => p.text())

async function mountWith(compression: CompressionTotals) {
  clearNuxtData()
  registerEndpoint('/api/metrics/compression', () => compression)
  const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
  await flushPromises()
  return wrapper
}

/** The alerts a fresh panel raises for these totals; unmounted after, so the next mount fetches its own. */
async function alertsFor(compression: CompressionTotals) {
  const wrapper = await mountWith(compression)
  const shown = alerts(wrapper)
  wrapper.unmount()
  return shown
}

describe('ChatCompressionSection', () => {
  beforeEach(() => {
    clearNuxtData()
    registerEndpoint('/api/metrics/cost', () => ({ since: '', rows: [] }))
  })

  it('shows the first-call cache share over the all-calls share, leaving out rows saved without it (JF-151)', async () => {
    registerEndpoint('/api/metrics/compression', answers({
      '|': payload(),
      '2|': totals(WINDOW),
      '1|': totals(AGENT_ONE),
      '1|telegram': totals({ ...WINDOW, compressions: 1, tokensBefore: 1000, tokensAfter: 700,
        byType: [TEXT_TELEGRAM.type], byAlgorithm: [TEXT_TELEGRAM.algorithm] }),
    }))
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-01-01T00:00:00Z',
      rows: [
        // Three calls; the first read 250 of its 1,000 prompt tokens from the cache.
        { timestamp: '2026-06-01T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: costUsage({ prompt: 4000, cached: 3000, firstPrompt: 1000, firstCached: 250 }) },
        // One call, so its first call is the whole turn.
        { timestamp: '2026-06-01T13:00:00Z', agentId: 1, channelType: 'web',
          usageJson: costUsage({ prompt: 1000, cached: 50, firstPrompt: 1000, firstCached: 50 }) },
        // Saved before turns recorded their first call; agent 2 never compressed anything.
        { timestamp: '2026-06-01T14:00:00Z', agentId: 2, channelType: 'telegram',
          usageJson: costUsage({ prompt: 2000, cached: 1800 }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()
    const tile = () => [
      wrapper.find('[data-testid="cache-share-first-call"]').text(),
      wrapper.find('[data-testid="cache-share-all"]').text(),
    ]

    // All calls: 4,850 of 7,000. First call: 300 of 2,000, with the older row left out.
    expect(tile()).toEqual(['15%', '69% over all calls'])

    await wrapper.find('select[aria-label="Filter by agent"]').setValue('2')
    await vi.waitFor(() => expect(tile()).toEqual(['—', '90% over all calls']))

    await wrapper.find('select[aria-label="Filter by agent"]').setValue('1')
    await vi.waitFor(() => expect(tile()).toEqual(['15%', '61% over all calls']))

    // Agent 1 compressed on telegram but ran no turn there.
    await wrapper.find('select[aria-label="Filter by channel"]').setValue('telegram')
    await vi.waitFor(() => expect(tile()).toEqual(['—', 'no turns']))
  })

  it('says it is loading, not zero, while the compression totals are still on their way', async () => {
    let release!: () => void
    const held = new Promise<void>((resolve) => {
      release = resolve
    })
    registerEndpoint('/api/metrics/compression', async () => {
      await held
      return payload()
    })
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-01-01T00:00:00Z',
      rows: [{ timestamp: '2026-06-01T12:00:00Z', agentId: 1, channelType: 'web',
        usageJson: costUsage({ prompt: 1000, cached: 400, firstPrompt: 1000, firstCached: 400 }) }],
    }))
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()

    expect(wrapper.text()).toContain('Loading compression data…')
    expect(wrapper.text()).not.toContain('Tokens saved')

    release()
    await vi.waitFor(() => expect(wrapper.text()).toContain('1 / 2 hits'))
    expect(wrapper.text()).not.toContain('Loading compression data…')
  })

  it('asks again when a filter changes and shows the loading state, not the old totals, until the answer lands', async () => {
    const seen: Record<string, unknown>[] = []
    const answer = answers({
      '|': payload(),
      '1|': totals(AGENT_ONE),
      '1|web': totals({ ...WINDOW, compressions: 2, tokensBefore: 12000, tokensAfter: 780,
        byType: [CODE_WEB.type, JSON_WEB.type], byAlgorithm: [CODE_WEB.algorithm, JSON_WEB.algorithm] }),
    }, seen)
    let hold = Promise.resolve()
    registerEndpoint('/api/metrics/compression', async (event) => {
      const reply = answer(event)
      await hold
      return reply
    })
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()
    expect(tiles(wrapper).slice(0, 3)).toEqual([
      ['Tokens saved', '11,520', 'over 30d'],
      ['Reduction', '89%', '13,000 → 1,480'],
      ['CCR hit rate', '50%', '1 / 2 hits'],
    ])

    for (const [select, value, expected] of [
      ['agent', '1', [['Tokens saved', '11,520', 'over 30d'], ['Reduction', '89%', '13,000 → 1,480'],
        ['CCR hit rate', '—', 'no retrievals']]],
      ['channel', 'web', [['Tokens saved', '11,220', 'over 30d'], ['Reduction', '94%', '12,000 → 780'],
        ['CCR hit rate', '—', 'no retrievals']]],
    ] as const) {
      let release!: () => void
      hold = new Promise<void>((resolve) => {
        release = resolve
      })
      await wrapper.find(`select[aria-label="Filter by ${select}"]`).setValue(value)
      await flushPromises()
      expect(wrapper.text()).toContain('Loading compression data…')
      expect(wrapper.text()).not.toContain('Tokens saved')

      release()
      await vi.waitFor(() => expect(tiles(wrapper).slice(0, 3)).toEqual(expected))
      expect(wrapper.text()).not.toContain('Loading compression data…')
    }

    expect(seen.map(q => [q.agentId, q.channel])).toEqual([[undefined, undefined], ['1', undefined], ['1', 'web']])
    expect(new Set(seen.map(q => q.since)).size).toBe(1)
  })

  it('holds the body at its height while a filter change reloads, and lets go once the answer lands', async () => {
    let hold = Promise.resolve()
    const answer = answers({ '|': payload(), '1|': totals(AGENT_ONE) })
    registerEndpoint('/api/metrics/compression', async (event) => {
      const reply = answer(event)
      await hold
      return reply
    })
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()
    const body = wrapper.find('[aria-busy]').element as HTMLElement
    // jsdom lays nothing out, so every rect is zero; give the body the height a browser would.
    body.getBoundingClientRect = () => ({
      left: 0, right: 600, top: 0, bottom: 412, width: 600, height: 412, x: 0, y: 0, toJSON: () => ({}),
    })
    expect(body.style.minHeight).toBe('')

    let release!: () => void
    hold = new Promise<void>((resolve) => {
      release = resolve
    })
    await wrapper.find('select[aria-label="Filter by agent"]').setValue('1')
    await flushPromises()
    expect(wrapper.text()).toContain('Loading compression data…')
    expect(body.style.minHeight).toBe('412px')
    expect(body.getAttribute('aria-busy')).toBe('true')

    release()
    await vi.waitFor(() => expect(wrapper.text()).not.toContain('Loading compression data…'))
    expect(body.style.minHeight).toBe('')
    expect(body.getAttribute('aria-busy')).toBe('false')
  })

  it('shows a failed filtered read and keeps the filter, sending no unfiltered read', async () => {
    const seen: Record<string, unknown>[] = []
    let failures = 1
    registerEndpoint('/api/metrics/compression', (event: H3Event) => {
      const query = getQuery(event)
      seen.push(query)
      if (query.agentId === undefined) return payload()
      if (failures-- > 0) {
        setResponseStatus(event, 400)
        return { type: 'error', code: 'invalid_request', message: 'The totals could not be read' }
      }
      return totals(AGENT_ONE)
    })
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()
    expect(wrapper.find('[data-testid="api-error"]').exists()).toBe(false)

    await wrapper.find('select[aria-label="Filter by agent"]').setValue('1')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="api-error"]').exists()).toBe(true))
    await flushPromises()
    expect(wrapper.find('[data-testid="api-error"]').text()).toContain('The totals could not be read')
    expect(seen.map(q => [q.agentId, q.channel])).toEqual([[undefined, undefined], ['1', undefined]])

    // The next read still asks for agent 1, and its answer puts agent 1 back in the select.
    await (wrapper.vm as unknown as { refresh: () => Promise<unknown> }).refresh()
    await flushPromises()
    expect(seen.map(q => [q.agentId, q.channel])).toEqual([[undefined, undefined], ['1', undefined], ['1', undefined]])
    expect(wrapper.find('[data-testid="api-error"]').exists()).toBe(false)
    expect((wrapper.find('select[aria-label="Filter by agent"]').element as HTMLSelectElement).value).toBe('1')
  })

  it('lists every agent and channel the window holds in the selects, whatever the filter', async () => {
    const window = { agents: ['1', '3'], channels: ['slack', 'web'] }
    registerEndpoint('/api/metrics/compression', answers({ '|': totals(window), '3|': totals(window) }))
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-01-01T00:00:00Z',
      rows: [{ timestamp: '2026-06-01T12:00:00Z', agentId: 2, channelType: 'telegram',
        usageJson: costUsage({ prompt: 1000, cached: 400, firstPrompt: 1000, firstCached: 400 }) }],
    }))
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()
    const options = (select: string) => wrapper.findAll(`select[aria-label="Filter by ${select}"] option`)
      .map(o => o.text())

    expect(options('agent')).toEqual(['All agents', 'agent #3', 'helper', 'main'])
    expect(options('channel')).toEqual(['All channels', 'slack', 'telegram', 'web'])

    await wrapper.find('select[aria-label="Filter by agent"]').setValue('3')
    await vi.waitFor(() => expect(wrapper.text()).not.toContain('Loading compression data…'))
    expect(options('agent')).toEqual(['All agents', 'agent #3', 'helper', 'main'])
    expect(options('channel')).toEqual(['All channels', 'slack', 'telegram', 'web'])
  })

  it('shows the tiles for a window with turns but no compression', async () => {
    registerEndpoint('/api/metrics/compression', () => totals())
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-01-01T00:00:00Z',
      rows: [{ timestamp: '2026-06-01T12:00:00Z', agentId: 1, channelType: 'web',
        usageJson: costUsage({ prompt: 1000, cached: 400, firstPrompt: 1000, firstCached: 400 }) }],
    }))
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()

    expect(wrapper.text()).not.toContain('No compression activity')
    expect(wrapper.find('[data-testid="cache-share-first-call"]').text()).toBe('40%')
    expect(wrapper.find('[data-testid="cache-share-all"]').text()).toBe('40% over all calls')
  })

  it('renders the totals into tiles, ordered breakdowns, the CCR rate and alerts', async () => {
    const wrapper = await mountWith(payload())

    expect(wrapper.text()).toContain('Chat Compression')
    expect(tiles(wrapper)).toEqual([
      ['Tokens saved', '11,520', 'over 30d'],
      ['Reduction', '89%', '13,000 → 1,480'],
      ['CCR hit rate', '50%', '1 / 2 hits'],
      ['Inflation guards', '0', '3 compressions'],
      ['First-call cache', '—', 'no turns'],
    ])
    // JSON keeps 6% and CODE 9% (< 10%) while the CCR hit rate is 50% (< 90%, retrieval unhealthy).
    expect(alerts(wrapper)).toEqual([
      'JSON compression ratio is very low (6% kept) — verify it isn\'t dropping needed data',
      'CODE compression ratio is very low (9% kept) — verify it isn\'t dropping needed data',
    ])

    await wrapper.find('button[title="Table view"]').trigger('click')
    expect(tables(wrapper)).toEqual([
      [['JSON', '9,400', '94%'], ['CODE', '1,820', '91%'], ['TEXT', '300', '30%']],
      [['code-structural', '1', '1,820'], ['json-smartcrush', '1', '9,400'], ['text-statistical', '1', '300']],
    ])
  })

  it('labels a missing content type or algorithm with a dash', async () => {
    const wrapper = await mountWith(totals({
      ...WINDOW, compressions: 1, tokensBefore: 800, tokensAfter: 200,
      byType: [{ contentType: null, tokensBefore: 800, tokensAfter: 200 }],
      byAlgorithm: [{ algorithm: null, count: 1, tokensBefore: 800, tokensAfter: 200 }],
    }))
    await wrapper.find('button[title="Table view"]').trigger('click')

    expect(tables(wrapper)).toEqual([[['—', '600', '75%']], [['—', '1', '600']]])
  })

  it('suppresses low-ratio alerts when CCR retrieval is healthy', async () => {
    // JSON is still 6% kept, but every ccr_retrieve hit → elided originals are
    // recoverable, so the aggressive ratio is by-design, not data loss.
    const wrapper = await mountWith(totals({
      ...WINDOW, compressions: 1, tokensBefore: 10000, tokensAfter: 600, ccrRetrievals: 3, ccrHits: 3,
      byType: [JSON_WEB.type], byAlgorithm: [JSON_WEB.algorithm],
    }))

    expect(wrapper.text()).toContain('JSON') // still rendered in the breakdown
    expect(alerts(wrapper)).toEqual([])
  })

  it('warns when inflation guards pass 5% of attempts, and not at 5%', async () => {
    const guarded = (compressions: number) => totals({
      ...WINDOW, compressions, tokensBefore: 1000 * compressions, tokensAfter: 500 * compressions, inflationGuards: 1,
    })

    expect(await alertsFor(guarded(19))).toEqual([])
    expect(await alertsFor(guarded(18))).toEqual([
      'Inflation-guard rate is 5% — the compressor is frequently inflating',
    ])
  })

  it('warns when the CCR hit rate falls below 50%, and not at 50%', async () => {
    const retrieved = (ccrRetrievals: number) => totals({ ...WINDOW, ccrRetrievals, ccrHits: 1 })

    expect(await alertsFor(retrieved(2))).toEqual([])
    expect(await alertsFor(retrieved(3))).toEqual([
      'CCR cache hit rate is 33% — the model may not be retrieving when it should',
    ])
  })

  it('ranks each tool by the share of its compressed results fetched back, and asks again for a filter', async () => {
    // As the server counts them: each distinct result once, the fetch-backs matched to the filter's compressions.
    registerEndpoint('/api/metrics/compression', answers({
      '|': totals({ ...WINDOW, agents: ['1', '2'], compressions: 9, tokensBefore: 9000, tokensAfter: 900,
        byTool: [
          { tool: 'exec', compressed: 2, fetched: 1 },
          { tool: 'jclaw_api', compressed: 1, fetched: 0 },
          { tool: 'web_fetch', compressed: 3, fetched: 1 },
        ] }),
      '2|': totals({ ...WINDOW, agents: ['1', '2'], compressions: 1, tokensBefore: 1000, tokensAfter: 100,
        byTool: [{ tool: 'jclaw_api', compressed: 1, fetched: 0 }] }),
      '1|': totals({ ...WINDOW, agents: ['1', '2'], compressions: 8, tokensBefore: 8000, tokensAfter: 800,
        byTool: [{ tool: 'exec', compressed: 2, fetched: 1 }, { tool: 'web_fetch', compressed: 3, fetched: 1 }] }),
      '1|telegram': totals({ ...WINDOW, agents: ['1', '2'], compressions: 1, tokensBefore: 1000, tokensAfter: 100,
        byTool: [{ tool: 'web_fetch', compressed: 1, fetched: 1 }] }),
    }))
    const wrapper = await mountSuspended(ChatCompressionSection, { props: { agents: AGENTS as never } })
    await flushPromises()
    const bars = () => wrapper.findAll('[data-testid="fetch-backs-by-tool"] .flex.items-center')
      .map(row => [row.find('span').text(), row.find('[style]').attributes('style'), row.findAll('span').at(-1)!.text()])

    expect(bars()).toEqual([
      ['exec', 'width: 50%;', '1 of 2 · 50%'],
      ['web_fetch', 'width: 33.33333333333333%;', '1 of 3 · 33%'],
      ['jclaw_api', 'width: 0%;', '0 of 1 · 0%'],
    ])

    await wrapper.find('button[title="Table view"]').trigger('click')
    const table = () => wrapper.findAll('[data-testid="fetch-backs-by-tool"] tbody tr')
      .map(tr => tr.findAll('td').map(td => td.text()))
    expect(table()).toEqual([
      ['exec', '2', '1', '50%'],
      ['web_fetch', '3', '1', '33%'],
      ['jclaw_api', '1', '0', '0%'],
    ])

    await wrapper.find('select[aria-label="Filter by agent"]').setValue('2')
    await vi.waitFor(() => expect(table()).toEqual([['jclaw_api', '1', '0', '0%']]))

    await wrapper.find('select[aria-label="Filter by agent"]').setValue('1')
    await vi.waitFor(() => expect(table()).toEqual([['exec', '2', '1', '50%'], ['web_fetch', '3', '1', '33%']]))
    await wrapper.find('select[aria-label="Filter by channel"]').setValue('telegram')
    await vi.waitFor(() => expect(table()).toEqual([['web_fetch', '1', '1', '100%']]))
  })

  it('leaves the fetch-back list out when no compression named its tool and result', async () => {
    const wrapper = await mountWith(totals({
      ...WINDOW, compressions: 1, tokensBefore: 1000, tokensAfter: 100, ccrRetrievals: 2, ccrHits: 2,
      byType: [{ contentType: 'JSON', tokensBefore: 1000, tokensAfter: 100 }],
      byAlgorithm: [{ algorithm: 'json-smartcrush', count: 1, tokensBefore: 1000, tokensAfter: 100 }],
    }))

    expect(wrapper.text()).toContain('2 / 2 hits')
    expect(wrapper.find('[data-testid="fetch-backs-by-tool"]').exists()).toBe(false)
  })

  it('shows an empty state when there is no activity', async () => {
    registerEndpoint('/api/metrics/compression', () => totals())
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
