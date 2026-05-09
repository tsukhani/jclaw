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
