import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import type { MockInstance } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { nextTick } from 'vue'
import ChatCostSection from '~/components/ChatCostSection.vue'
import type { Agent } from '~/types/api'

/**
 * Flow coverage for ChatCostSection beyond the structural specs in
 * chat-cost-section.test.ts:
 *
 *   - Cost-column tooltip (teleported to body, mouse + keyboard triggers).
 *   - "All" time window: epoch `since` param + windowDays derived from the
 *     earliest row timestamp (pro-rates the subscription fee).
 *   - Agent / channel filter dropdowns narrowing the per-model table.
 *   - Channel filter auto-reset when the selected channel drops out of the
 *     refetched window.
 *   - Per-token provider chip auto-clear when its provider loses paid
 *     activity under a narrower agent filter.
 *   - Turns-column sort.
 *   - Chart view per-agent branch (more agents than models) including the
 *     "agent #id" fallback label for deleted agents.
 *   - CSV export: header, subscription allocation rows, unallocated
 *     subscription rows, per-token rows in sort order, RFC-4180 quoting,
 *     and the filter-tagged filename.
 */

const STUB_AGENTS: Agent[] = [
  { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
    enabled: true, isMain: true, providerConfigured: true } as unknown as Agent,
  { id: 2, name: 'helper', modelProvider: 'openai', modelId: 'gpt-4.1',
    enabled: true, isMain: false, providerConfigured: true } as unknown as Agent,
]

interface UsageSpec {
  prompt?: number
  completion?: number
  reasoning?: number
  cached?: number
  modelId?: string
  modelProvider?: string
  promptPrice?: number
  completionPrice?: number
}

function makeUsage(u: UsageSpec = {}): string {
  return JSON.stringify({
    prompt: u.prompt ?? 100,
    completion: u.completion ?? 50,
    total: (u.prompt ?? 100) + (u.completion ?? 50),
    reasoning: u.reasoning ?? 0,
    cached: u.cached ?? 0,
    durationMs: 100,
    promptPrice: u.promptPrice ?? 0.5,
    completionPrice: u.completionPrice ?? 1.0,
    modelId: u.modelId ?? 'gpt-4.1',
    modelProvider: u.modelProvider ?? 'openai',
  })
}

function costRow(usage: UsageSpec, over: { agentId?: number, channelType?: string, timestamp?: string } = {}) {
  return {
    timestamp: over.timestamp ?? '2026-06-25T12:00:00Z',
    agentId: over.agentId ?? 1,
    channelType: over.channelType ?? 'web',
    usageJson: makeUsage(usage),
  }
}

// registerEndpoint registrations persist for the whole file (latest wins);
// a /api/providers stub from one test would otherwise leak a subscription
// config into later tests that expect the no-providers default. Track the
// disposers and unwind them after each test.
const endpointDisposers: (() => void)[] = []

interface ProviderStub {
  name: string
  paymentModality: 'PER_TOKEN' | 'SUBSCRIPTION'
  subscriptionMonthlyUsd: number
}

function stubProviders(providers: ProviderStub[]) {
  endpointDisposers.push(registerEndpoint('/api/providers', () => providers.map(p => ({
    ...p,
    supportedModalities: [p.paymentModality],
  }))))
}

beforeEach(() => {
  // useFetch caches by URL across mounts, and the component persists its
  // window / view / chip choices to localStorage — clear both so every
  // test starts from the 30d/table defaults.
  clearNuxtData()
  localStorage.clear()
})

afterEach(() => {
  while (endpointDisposers.length) endpointDisposers.pop()!()
  // The cost tooltip teleports to <body>; drop any leftovers so a later
  // test's body queries don't see stale nodes.
  document.body.querySelectorAll('[data-testid="cost-info-tooltip"]').forEach(el => el.remove())
})

describe('ChatCostSection — cost-column tooltip', () => {
  function stubSubscriptionWithUsage() {
    stubProviders([
      { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
    ])
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-06-01T00:00:00Z',
      rows: [costRow({ modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud', promptPrice: 0, completionPrice: 0 })],
    }))
  }

  it('shows the teleported tooltip on mouseenter and hides it on mouseleave', async () => {
    stubSubscriptionWithUsage()
    const wrapper = await mountSuspended(ChatCostSection, { props: { agents: STUB_AGENTS } })
    await flushPromises()

    const infoBtn = wrapper.find('button[aria-label="Cost column information"]')
    expect(infoBtn.exists()).toBe(true)
    expect(document.body.querySelector('[data-testid="cost-info-tooltip"]')).toBeNull()

    await infoBtn.trigger('mouseenter')
    await nextTick()
    const tooltip = document.body.querySelector('[data-testid="cost-info-tooltip"]')
    expect(tooltip).not.toBeNull()
    expect(tooltip!.textContent).toContain('allocated across models by total tokens')

    await infoBtn.trigger('mouseleave')
    await nextTick()
    expect(document.body.querySelector('[data-testid="cost-info-tooltip"]')).toBeNull()
  })

  it('shows the tooltip on keyboard focus and hides it on blur', async () => {
    stubSubscriptionWithUsage()
    const wrapper = await mountSuspended(ChatCostSection, { props: { agents: STUB_AGENTS } })
    await flushPromises()

    const infoBtn = wrapper.find('button[aria-label="Cost column information"]')
    await infoBtn.trigger('focus')
    await nextTick()
    expect(document.body.querySelector('[data-testid="cost-info-tooltip"]')).not.toBeNull()

    await infoBtn.trigger('blur')
    await nextTick()
    expect(document.body.querySelector('[data-testid="cost-info-tooltip"]')).toBeNull()
  })
})

describe('ChatCostSection — All-time window', () => {
  it('sends an epoch since param and pro-rates the subscription fee to the row span', async () => {
    // Earliest row is 15 days old → windowDays ≈ 15 → the $100/month fee
    // pro-rates to $50 on the All window (vs the full $100 at 30d).
    const capturedSince: string[] = []
    stubProviders([
      { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
    ])
    registerEndpoint('/api/metrics/cost', async (event) => {
      const { getQuery } = await import('h3')
      capturedSince.push(String(getQuery(event).since))
      return {
        since: '1970-01-01T00:00:00Z',
        rows: [
          costRow(
            { modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud', promptPrice: 0, completionPrice: 0 },
            { timestamp: new Date(Date.now() - 15 * 24 * 60 * 60 * 1000).toISOString() },
          ),
          costRow(
            { modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud', promptPrice: 0, completionPrice: 0 },
            { timestamp: new Date(Date.now() - 1 * 24 * 60 * 60 * 1000).toISOString() },
          ),
        ],
      }
    })
    const wrapper = await mountSuspended(ChatCostSection, { props: { agents: STUB_AGENTS } })
    // This stub handler is async (dynamic h3 import), so the first response
    // can land after mountSuspended + one flushPromises — wait for the
    // loaded view instead of counting ticks.
    await vi.waitFor(() => expect(wrapper.text()).not.toContain('Loading cost data'))

    // Default 30d window: full monthly fee on the provider chip.
    expect(wrapper.text()).toContain('$100')

    await wrapper.findAll('button').find(b => b.text() === 'All')!.trigger('click')
    // The window switch refetches through the same async stub — wait for the
    // second request to be captured before inspecting it.
    await vi.waitFor(() => expect(capturedSince.length).toBeGreaterThanOrEqual(2))

    // The refetch carried a far-past since (epoch), not a rolling cutoff.
    const lastSince = capturedSince.at(-1)!
    expect(new Date(lastSince).getUTCFullYear()).toBe(1970)

    // Fee pro-rated to the 15-day data span: $100 × 15/30 = $50.
    await vi.waitFor(() => expect(wrapper.text()).toContain('$50'))
    expect(wrapper.text()).not.toContain('$100')
  })
})

describe('ChatCostSection — filter dropdowns', () => {
  it('narrows the per-model table to the selected agent', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-06-01T00:00:00Z',
      rows: [
        costRow({ modelId: 'model-of-main', modelProvider: 'openai' }, { agentId: 1 }),
        costRow({ modelId: 'model-of-helper', modelProvider: 'openai' }, { agentId: 2 }),
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, { props: { agents: STUB_AGENTS } })
    await flushPromises()

    expect(wrapper.text()).toContain('model-of-main')
    expect(wrapper.text()).toContain('model-of-helper')

    await wrapper.find('select[aria-label="Filter by agent"]').setValue('2')
    await flushPromises()

    expect(wrapper.text()).toContain('model-of-helper')
    expect(wrapper.text()).not.toContain('model-of-main')
  })

  it('narrows to the selected channel, then resets the filter when the channel drops out of the window', async () => {
    // 30d window carries web + telegram rows; the 7d window only web.
    // Selecting telegram then switching to 7d must auto-clear the channel
    // filter rather than pin the view to a channel with no data.
    registerEndpoint('/api/metrics/cost', async (event) => {
      const { getQuery } = await import('h3')
      const sinceMs = Date.parse(String(getQuery(event).since))
      const isSevenDay = Date.now() - sinceMs < 8 * 24 * 60 * 60 * 1000
      const webRow = costRow({ modelId: 'web-model', modelProvider: 'openai' }, { channelType: 'web' })
      const tgRow = costRow({ modelId: 'tg-model', modelProvider: 'openai' }, { channelType: 'telegram' })
      return {
        since: String(getQuery(event).since),
        rows: isSevenDay ? [webRow] : [webRow, tgRow],
      }
    })
    const wrapper = await mountSuspended(ChatCostSection, { props: { agents: STUB_AGENTS } })
    // Async stub: wait for the 30d rows (and the channel <option>s they
    // feed) to render before touching the select — a setValue before the
    // telegram option exists silently selects nothing.
    await vi.waitFor(() => expect(wrapper.text()).toContain('web-model'))

    const channelSelect = wrapper.find('select[aria-label="Filter by channel"]')
    await channelSelect.setValue('telegram')
    await flushPromises()
    expect(wrapper.text()).toContain('tg-model')
    expect(wrapper.text()).not.toContain('web-model')

    await wrapper.findAll('button').find(b => b.text() === '7d')!.trigger('click')

    // telegram vanished from the refetched rows → filter auto-reset to All,
    // so web-model is back and tg-model is gone.
    await vi.waitFor(() => expect(wrapper.text()).toContain('web-model'))
    expect(wrapper.text()).not.toContain('tg-model')
    // The "All channels" option binds :value="null", so its DOM value falls
    // back to the option text — assert the reset via selectedIndex instead.
    expect((channelSelect.element as HTMLSelectElement).selectedIndex).toBe(0)
  })
})

describe('ChatCostSection — per-token chip auto-clear', () => {
  it('clears the selected per-token chip when its provider loses paid activity under an agent filter', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-06-01T00:00:00Z',
      rows: [
        costRow({ modelId: 'kimi-k2.6', modelProvider: 'openrouter', promptPrice: 1.0, prompt: 1_000_000, completion: 0 }, { agentId: 1 }),
        costRow({ modelId: 'gpt-4.1', modelProvider: 'openai', promptPrice: 2.0, prompt: 1_000_000, completion: 0 }, { agentId: 2 }),
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, { props: { agents: STUB_AGENTS } })
    await flushPromises()

    const orChip = wrapper.findAll('button[aria-pressed]').find(c => c.text().includes('OpenRouter'))!
    expect(orChip).toBeDefined()
    await orChip.trigger('click')
    await flushPromises()
    expect(orChip.attributes('aria-pressed')).toBe('true')
    expect(wrapper.text()).not.toContain('gpt-4.1')

    // Narrow to agent 2 — OpenRouter has no paid rows there, so its chip
    // (and the stale selection) must both go away instead of trapping the
    // table in an empty provider scope.
    await wrapper.find('select[aria-label="Filter by agent"]').setValue('2')
    await flushPromises()
    await nextTick()

    expect(wrapper.text()).toContain('gpt-4.1')
    expect(wrapper.text()).not.toContain('OpenRouter')
    const pressed = wrapper.findAll('button[aria-pressed="true"]')
    expect(pressed.length).toBe(0)
  })
})

describe('ChatCostSection — Turns sort + per-agent chart', () => {
  it('sorts by turn count descending when the Turns header is clicked', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-06-01T00:00:00Z',
      rows: [
        // solo-model: 1 turn, big cost → leads the default Cost sort.
        costRow({ modelId: 'solo-model', modelProvider: 'p', prompt: 1000, completion: 0, promptPrice: 100, completionPrice: 0 }),
        // busy-model: 3 turns, tiny cost.
        ...Array.from({ length: 3 }, () =>
          costRow({ modelId: 'busy-model', modelProvider: 'p', prompt: 10, completion: 0, promptPrice: 1, completionPrice: 0 })),
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, { props: { agents: STUB_AGENTS } })
    await flushPromises()

    let rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('solo-model')

    await wrapper.findAll('thead button').find(b => b.text().includes('Turns'))!.trigger('click')
    await flushPromises()

    rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('busy-model') // 3 turns
    expect(rows[1]!.text()).toContain('solo-model') // 1 turn
  })

  it('charts per-agent bars when agents outnumber models, labeling deleted agents by id', async () => {
    // Three agents share one model → the chart picks the agent dimension.
    // Agent 99 is not in the agents prop → "agent #99" fallback label.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-06-01T00:00:00Z',
      rows: [
        costRow({ modelId: 'shared-model', modelProvider: 'openai' }, { agentId: 1 }),
        costRow({ modelId: 'shared-model', modelProvider: 'openai' }, { agentId: 2 }),
        costRow({ modelId: 'shared-model', modelProvider: 'openai' }, { agentId: 99 }),
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, { props: { agents: STUB_AGENTS } })
    await flushPromises()

    await wrapper.findAll('button').find(b => b.attributes('title') === 'Bar chart view')!.trigger('click')
    await flushPromises()

    const text = wrapper.text()
    expect(text).toContain('main')
    expect(text).toContain('helper')
    expect(text).toContain('agent #99')

    // Clicking the table tab restores the per-model table.
    await wrapper.findAll('button').find(b => b.attributes('title') === 'Table view')!.trigger('click')
    await flushPromises()
    expect(wrapper.find('thead').exists()).toBe(true)
    expect(wrapper.text()).toContain('shared-model')
  })
})

describe('ChatCostSection — CSV export', () => {
  let capturedBlob: Blob | null
  let downloadName: string
  let clickSpy: MockInstance<() => void>

  beforeEach(() => {
    capturedBlob = null
    downloadName = ''
    // jsdom has no createObjectURL; provide capture stubs.
    URL.createObjectURL = vi.fn((b: Blob) => {
      capturedBlob = b
      return 'blob:jclaw-test'
    }) as typeof URL.createObjectURL
    URL.revokeObjectURL = vi.fn() as typeof URL.revokeObjectURL
    // click() is defined on HTMLElement.prototype (anchors inherit it).
    // Nothing else in this describe block calls element.click() — VTU's
    // trigger() dispatches events directly — so the broad spy is safe.
    clickSpy = vi.spyOn(HTMLElement.prototype, 'click')
      .mockImplementation(function (this: HTMLElement) {
        downloadName = (this as HTMLAnchorElement).download ?? ''
      })
  })

  afterEach(() => {
    clickSpy.mockRestore()
    delete (URL as Partial<typeof URL>).createObjectURL
    delete (URL as Partial<typeof URL>).revokeObjectURL
  })

  function blobText(b: Blob): Promise<string> {
    return new Promise((resolve, reject) => {
      const r = new FileReader()
      r.onload = () => resolve(String(r.result))
      r.onerror = () => reject(r.error ?? new Error('read failed'))
      r.readAsText(b)
    })
  }

  it('exports subscription allocations, unallocated bills, and quoted per-token rows in sort order', async () => {
    stubProviders([
      { name: 'ollama-cloud', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 100 },
      { name: 'openai', paymentModality: 'SUBSCRIPTION', subscriptionMonthlyUsd: 20 },
      { name: 'openrouter', paymentModality: 'PER_TOKEN', subscriptionMonthlyUsd: 0 },
    ])
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-06-01T00:00:00Z',
      rows: [
        // Subscription usage: sole model → gets the full $100 allocation.
        costRow({ modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud', prompt: 500, completion: 250, promptPrice: 0, completionPrice: 0 }),
        // Per-token: $2 row sorts above the $1 row under the default Cost-desc sort.
        costRow({ modelId: 'plain-model', modelProvider: 'openrouter', prompt: 2_000_000, completion: 0, promptPrice: 1.0, completionPrice: 0 }),
        // Model id with an embedded comma must be RFC-4180 quoted.
        costRow({ modelId: 'weird,model', modelProvider: 'openrouter', prompt: 1_000_000, completion: 0, promptPrice: 1.0, completionPrice: 0 }),
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, { props: { agents: STUB_AGENTS } })
    await flushPromises()

    const csvBtn = wrapper.findAll('button').find(b => b.attributes('title')?.includes('CSV'))!
    expect(csvBtn.attributes('disabled')).toBeUndefined()
    await csvBtn.trigger('click')

    expect(capturedBlob).not.toBeNull()
    const csv = await blobText(capturedBlob!)
    const lines = csv.split('\n')

    expect(lines[0]).toBe('modality,provider,modelId,turnCount,cost,promptTokens,completionTokens,reasoningTokens,cachedTokens')
    // Subscription allocation: the only used model carries the full $100.
    expect(lines).toContain('subscription,ollama-cloud,kimi-k2.5,1,100.000000,500,250,0,0')
    // openai's $20 subscription had no usage — exported as unallocated so
    // spreadsheet sums reconcile against the bill.
    expect(lines).toContain('subscription-unallocated,openai,,,20.000000,,,,')
    // Per-token rows honor the on-screen sort (cost desc) and quote the
    // comma-bearing model id.
    const plainIdx = lines.indexOf('per-token,openrouter,plain-model,1,2.000000,2000000,0,0,0')
    const weirdIdx = lines.indexOf('per-token,openrouter,"weird,model",1,1.000000,1000000,0,0,0')
    expect(plainIdx).toBeGreaterThan(0)
    expect(weirdIdx).toBeGreaterThan(plainIdx)

    // Filename encodes the active filters + window + date.
    expect(downloadName).toMatch(/^chat-cost_all-agents_all-channels_30d_\d{4}-\d{2}-\d{2}\.csv$/)
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:jclaw-test')
  })
})
