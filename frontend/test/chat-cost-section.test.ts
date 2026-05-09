import { describe, it, expect } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import ChatCostSection from '~/components/ChatCostSection.vue'
import type { Agent } from '~/types/api'

/**
 * Component tests for the JCLAW-28 Chat Cost section.
 *
 * <p>The component wraps three concerns: the /api/metrics/cost fetch (with
 * since-driven refetch), client-side filter aggregation via
 * {@code computeFleetCost}, and the table/chart/CSV view modes. The
 * computeFleetCost math is exercised in {@code usage-cost.test.ts}; these
 * tests focus on the wire-up: filter changes producing correct DOM,
 * empty/loading states rendering, and CSV affordance gating.
 */

const STUB_AGENTS: Agent[] = [
  { id: 1, name: 'main', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5',
    enabled: true, isMain: true, providerConfigured: true } as unknown as Agent,
  { id: 2, name: 'helper', modelProvider: 'openai', modelId: 'gpt-4.1',
    enabled: true, isMain: false, providerConfigured: true } as unknown as Agent,
]

interface UsagePartial {
  prompt?: number
  completion?: number
  modelId?: string
  modelProvider?: string
  promptPrice?: number
  completionPrice?: number
}

function makeUsage(partial: UsagePartial = {}): string {
  return JSON.stringify({
    prompt: partial.prompt ?? 100,
    completion: partial.completion ?? 50,
    total: (partial.prompt ?? 100) + (partial.completion ?? 50),
    reasoning: 0,
    cached: 0,
    durationMs: 100,
    promptPrice: partial.promptPrice ?? 0.5,
    completionPrice: partial.completionPrice ?? 1.0,
    modelId: partial.modelId ?? 'gpt-4.1',
    modelProvider: partial.modelProvider ?? 'openai',
  })
}

describe('ChatCostSection (JCLAW-28)', () => {
  it('renders empty state when no rows are returned', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('No conversations match the current filter')
  })

  it('renders the section title and filter dropdowns', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('Chat Cost')
    expect(wrapper.find('#chat-cost-agent').exists()).toBe(true)
    expect(wrapper.find('#chat-cost-channel').exists()).toBe(true)
    expect(wrapper.find('#chat-cost-window').exists()).toBe(true)
  })

  it('renders aggregated totals when rows are present', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 100, completion: 50 }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 200, completion: 100 }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const text = wrapper.text()
    // Total turns and tokens visible in the summary row.
    expect(text).toContain('Turns')
    expect(text).toContain('300') // 100 + 200 prompt tokens
    expect(text).toContain('150') // 50 + 100 completion tokens
  })

  it('lists only channels present in the loaded data as filter options', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage() },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'telegram',
          usageJson: makeUsage() },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const channelSelect = wrapper.find<HTMLSelectElement>('#chat-cost-channel')
    const optionValues = channelSelect.findAll('option').map(o => o.attributes('value'))
    // Vue serializes null v-model values to empty string; the rest are channel kinds.
    expect(optionValues).toContain('web')
    expect(optionValues).toContain('telegram')
  })

  it('renders per-model breakdown table by default', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ modelId: 'gpt-4.1', modelProvider: 'openai' }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 2, channelType: 'web',
          usageJson: makeUsage({ modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud' }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    expect(wrapper.find('table').exists()).toBe(true)
    const text = wrapper.text()
    expect(text).toContain('gpt-4.1')
    expect(text).toContain('kimi-k2.5')
  })

  it('does not render a reset button (cost is durable)', async () => {
    // Regression guard for the AC: Chat Performance has a reset; Chat Cost
    // explicitly does not, since durable persisted data shouldn't carry the
    // ephemeral-reset affordance.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [{ timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
        usageJson: makeUsage() }],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const buttons = wrapper.findAll('button')
    for (const btn of buttons) {
      const title = btn.attributes('title') ?? ''
      expect(title.toLowerCase()).not.toContain('reset')
    }
  })

  it('disables the CSV button when there is no data', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const csvBtn = wrapper.findAll('button').find(b => b.attributes('title')?.includes('CSV'))
    expect(csvBtn).toBeDefined()
    expect(csvBtn!.attributes('disabled')).toBeDefined()
  })

  it('sorts table by Cost descending by default', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 50, completion: 25,
            modelId: 'cheap-model', modelProvider: 'p',
            promptPrice: 0.1, completionPrice: 0.2 }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 1000, completion: 500,
            modelId: 'expensive-model', modelProvider: 'p',
            promptPrice: 5.0, completionPrice: 10.0 }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const rows = wrapper.findAll('tbody tr')
    // expensive-model has the higher cost — should be first row by default.
    expect(rows[0]!.text()).toContain('expensive-model')
    expect(rows[1]!.text()).toContain('cheap-model')
  })

  it('flips direction when the same column header is clicked twice', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 100, modelId: 'a-model' }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 200, modelId: 'b-model' }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const promptHeader = wrapper.findAll('thead button').find(b => b.text().includes('Prompt'))!
    // First click: sort by Prompt descending (numeric default).
    await promptHeader.trigger('click')
    await flushPromises()
    let rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('b-model') // 200 prompt tokens
    expect(rows[1]!.text()).toContain('a-model') // 100 prompt tokens
    // Second click: flip to ascending.
    await promptHeader.trigger('click')
    await flushPromises()
    rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('a-model')
    expect(rows[1]!.text()).toContain('b-model')
  })

  it('sorts the Model column alphabetically ascending by default', async () => {
    // Strings default to ascending — alphabetical order reads naturally
    // for the operator scanning model names.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ modelId: 'zeta-model' }) },
        { timestamp: '2026-05-09T13:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ modelId: 'alpha-model' }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const modelHeader = wrapper.findAll('thead button').find(b => b.text().includes('Model'))!
    await modelHeader.trigger('click')
    await flushPromises()
    const rows = wrapper.findAll('tbody tr')
    expect(rows[0]!.text()).toContain('alpha-model')
    expect(rows[1]!.text()).toContain('zeta-model')
  })

  it('shows a chevron indicator on the active sort column', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage() },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    // Default active column is Cost (descending). Cost button should carry
    // a chevron-down svg; Model button should not.
    const costHeader = wrapper.findAll('thead button').find(b => b.text().includes('Cost'))!
    const modelHeader = wrapper.findAll('thead button').find(b => b.text().includes('Model'))!
    expect(costHeader.find('svg').exists()).toBe(true)
    expect(modelHeader.find('svg').exists()).toBe(false)
  })

  it('excludes zero-cost models from the per-model table', async () => {
    // Operator's question is "which models cost what" — free-tier rows
    // contribute nothing to that answer and are visual noise.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        // Paid: should appear
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ modelId: 'gpt-4.1', modelProvider: 'openai',
            promptPrice: 3, completionPrice: 15 }) },
        // Free-tier: should be filtered from the table
        { timestamp: '2026-05-09T13:00:00Z', agentId: 2, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 100, completion: 50, total: 150, reasoning: 0,
            cached: 0, durationMs: 100,
            modelId: 'kimi-k2.5', modelProvider: 'ollama-cloud',
            // No promptPrice / completionPrice — computeUsageCostBreakdown
            // returns null, so total cost contribution is 0.
          }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const text = wrapper.text()
    expect(text).toContain('gpt-4.1')
    expect(text).not.toContain('kimi-k2.5')
  })

  it('keeps free-tier turn count and tokens in the summary row', async () => {
    // Filter is display-layer only — the summary row above the breakdown
    // must answer "what happened in this window" comprehensively, including
    // free-tier activity. Operator looking at "Turns: 6" with only 1
    // billed model should see 6, not 1.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: makeUsage({ prompt: 100, completion: 50,
            promptPrice: 3, completionPrice: 15 }) },
        // Five free-tier turns
        ...Array.from({ length: 5 }, (_, i) => ({
          timestamp: `2026-05-09T1${i}:00:00Z`,
          agentId: 2,
          channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 200, completion: 100, total: 300, reasoning: 0,
            cached: 0, durationMs: 100,
            modelId: 'kimi-k2.5',
          }),
        })),
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const text = wrapper.text()
    // 6 total turns (1 paid + 5 free-tier) — summary row honors all.
    expect(text).toContain('Turns')
    expect(text).toMatch(/Turns[\s\S]*?6/)
    // 100 + 5×200 = 1100 prompt tokens — token totals span free-tier too.
    expect(text).toContain('1,100')
  })

  it('shows the all-free-tier empty state when no models contributed cost', async () => {
    // Distinguished from "no data at all" — summary row is still
    // populated, but the breakdown table is replaced with a clear message
    // so the operator isn't confused by an empty table.
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 100, completion: 50, total: 150, reasoning: 0,
            cached: 0, durationMs: 100, modelId: 'kimi-k2.5',
          }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    expect(wrapper.text()).toContain('All turns in this window were on free-tier models')
    // The summary row is still present (it's "what happened" not "what
    // cost") so the activity count is still visible.
    expect(wrapper.text()).toContain('Turns')
    // No table rendered when there are no paid models.
    expect(wrapper.find('tbody tr').exists()).toBe(false)
  })

  it('disables CSV export when all data is free-tier', async () => {
    // Same gating as the no-data state — there's nothing meaningful to
    // export when no models contributed cost. Different from the
    // already-tested no-data case (which has zero rows entirely).
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [
        { timestamp: '2026-05-09T12:00:00Z', agentId: 1, channelType: 'web',
          usageJson: JSON.stringify({
            prompt: 100, completion: 50, total: 150, reasoning: 0,
            cached: 0, durationMs: 100, modelId: 'kimi-k2.5',
          }) },
      ],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    const csvBtn = wrapper.findAll('button').find(b => b.attributes('title')?.includes('CSV'))
    expect(csvBtn).toBeDefined()
    expect(csvBtn!.attributes('disabled')).toBeDefined()
  })

  it('exposes refresh via defineExpose', async () => {
    registerEndpoint('/api/metrics/cost', () => ({
      since: '2026-04-10T00:00:00Z',
      rows: [],
    }))
    const wrapper = await mountSuspended(ChatCostSection, {
      props: { agents: STUB_AGENTS },
    })
    await flushPromises()
    // The component exposes refresh so a parent could trigger reload after
    // a config change. Just assert the API surface exists.
    expect(typeof (wrapper.vm as { refresh?: () => void }).refresh).toBe('function')
  })
})
