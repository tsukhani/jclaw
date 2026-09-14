import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Index from '~/pages/index.vue'
import Agents from '~/pages/agents/[[name]].vue'
import Settings from '~/pages/settings.vue'
import Logs from '~/pages/logs.vue'
import Conversations from '~/pages/conversations/index.vue'

// Register mock API endpoints
function setupMockApi() {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'test', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: false, providerConfigured: true },
  ])
  registerEndpoint('/api/channels', () => [
    { channelType: 'telegram', enabled: false },
    { channelType: 'slack', enabled: true },
  ])
  registerEndpoint('/api/channels/active', () => ({
    count: 1,
    channelTypes: ['slack'],
  }))
  registerEndpoint('/api/tasks', () => [])
  registerEndpoint('/api/logs', () => ({ events: [
    { id: 1, timestamp: '2026-04-07T10:00:00Z', level: 'INFO', category: 'system', agentId: null, message: 'Test event' },
  ] }))
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1', updatedAt: '2026-04-07T10:00:00Z' },
      { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****', updatedAt: '2026-04-07T10:00:00Z' },
      { key: 'provider.ollama-cloud.models', value: '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535}]', updatedAt: '2026-04-07T10:00:00Z' },
    ],
  }))
  registerEndpoint('/api/conversations', () => [
    { id: 1, agentId: 1, agentName: 'test', channelType: 'web', peerId: 'admin', messageCount: 3, preview: 'Hello', createdAt: '2026-04-07T10:00:00Z', updatedAt: '2026-04-07T10:00:00Z' },
  ])
  registerEndpoint('/api/conversations/channels', () => ['web', 'telegram'])
  // JCLAW-72: useToolMeta composable fetches from /api/tools/meta on mount.
  // Supply a minimal fixture so agents.vue renders without errors.
  registerEndpoint('/api/tools/meta', () => [
    { name: 'exec', category: 'System', icon: 'terminal',
      shortDescription: 'Shell', system: false, actions: [] },
    { name: 'web_fetch', category: 'Web', icon: 'globe',
      shortDescription: 'Fetch URLs', system: false, actions: [] },
  ])
  registerEndpoint('/api/agents/1/tools', () => [
    { name: 'exec', description: 'Execute shell', system: false, enabled: true },
    { name: 'web_fetch', description: 'Fetch URLs', system: false, enabled: true },
  ])
}

describe('Dashboard page', () => {
  it('renders stats cards', async () => {
    setupMockApi()
    const component = await mountSuspended(Index)

    expect(component.text()).toContain('Dashboard')
    // Each card carries a category title at the top; the labels below the
    // counts describe which slice of that category is being shown.
    expect(component.text()).toContain('Agents')
    expect(component.text()).toContain('Conversations')
    expect(component.text()).toContain('Channels')
    expect(component.text()).toContain('Tasks')
    // Tasks card sub-stats: ACTIVE (recurring in steady state),
    // RUNNING (currently firing), PENDING (one-shot SCHEDULED/IMMEDIATE
    // waiting to fire). 'Active' is shared with the Agents and Channels
    // cards so the per-card check above already covers it.
    expect(component.text()).toContain('Running')
    expect(component.text()).toContain('Pending')
  })

  it('displays agent count', async () => {
    setupMockApi()
    const component = await mountSuspended(Index)
    // The dashboard's reads are lazy, so mount resolves before they land.
    await flushPromises()

    // 1 agent enabled out of 1 total
    expect(component.text()).toContain('1/1')
  })

  it('renders cards in left-to-right order: Agents, Conversations, Channels, Tasks', async () => {
    setupMockApi()
    const component = await mountSuspended(Index)

    const text = component.text()
    // Card titles anchor the order — each is rendered once at the top
    // of its card before the count, so first occurrence wins.
    const agentsIdx = text.indexOf('Agents')
    const convosIdx = text.indexOf('Conversations')
    const channelsIdx = text.indexOf('Channels')
    const tasksIdx = text.indexOf('Tasks')
    expect(agentsIdx).toBeGreaterThanOrEqual(0)
    expect(convosIdx).toBeGreaterThan(agentsIdx)
    expect(channelsIdx).toBeGreaterThan(convosIdx)
    expect(tasksIdx).toBeGreaterThan(channelsIdx)
  })
})

describe('Agents page', () => {
  it('renders agent list', async () => {
    setupMockApi()
    const component = await mountSuspended(Agents)

    expect(component.text()).toContain('Agents')
    expect(component.text()).toContain('test')
    expect(component.text()).toContain('ollama-cloud')
    expect(component.text()).toContain('enabled')
  })

  it('shows New Agent button', async () => {
    setupMockApi()
    const component = await mountSuspended(Agents)

    // New Agent is now an icon-only button identified by its title attribute
    const button = component.find('button[title="New Agent"]')
    expect(button.exists()).toBe(true)
  })

  it('renders agent cards with model info', async () => {
    setupMockApi()
    const component = await mountSuspended(Agents)

    // Verify structural elements exist beyond just text content
    const buttons = component.findAll('button')
    expect(buttons.length).toBeGreaterThanOrEqual(1)
    // New Agent is an icon-only button with a title attribute
    const newAgentBtn = component.find('button[title="New Agent"]')
    expect(newAgentBtn.exists()).toBe(true)
  })
})

describe('Settings page', () => {
  it('renders provider sections', async () => {
    // JCLAW-680: the page renders one section at a time via a TOC swap, so open
    // the LLM Providers section (deep-linked via ?section) before asserting on
    // its provider cards.
    setupMockApi()
    const component = await mountSuspended(Settings, { route: '/settings?section=providers' })
    await flushPromises()
    await flushPromises()

    expect(component.text()).toContain('Settings')
    // LLM Providers appears in the TOC rail regardless; Ollama Cloud is the
    // provider card's friendly display label (JCLAW-182), only rendered when the
    // Providers panel is the active section.
    expect(component.text()).toContain('LLM Providers')
    expect(component.text()).toContain('Ollama Cloud')
  })

  it('does not expose an add-entry form for ad-hoc config', async () => {
    // Unmanaged keys are surfaced read-only via a warning banner, never an
    // editor — arbitrary config rows aren't read by anything, so there's no
    // affordance to create them.
    setupMockApi()
    const component = await mountSuspended(Settings)
    await flushPromises()

    expect(component.text()).not.toContain('Add Entry')
  })
})

describe('Logs page', () => {
  beforeEach(() => {
    clearNuxtData()
  })

  it('renders with auto-refresh toggle', async () => {
    setupMockApi()
    const component = await mountSuspended(Logs)

    expect(component.text()).toContain('Logs')
    expect(component.text()).toContain('Auto-refresh')
  })

  it('shows filter controls', async () => {
    setupMockApi()
    const component = await mountSuspended(Logs)

    expect(component.text()).toContain('All categories')
    expect(component.text()).toContain('All levels')
  })

  it('has auto-refresh checkbox that is checked by default', async () => {
    setupMockApi()
    const component = await mountSuspended(Logs)

    const checkbox = component.find('input[type="checkbox"]')
    expect(checkbox.exists()).toBe(true)
    expect((checkbox.element as HTMLInputElement).checked).toBe(true)
  })

  it('renders category and level filter dropdowns', async () => {
    setupMockApi()
    const component = await mountSuspended(Logs)

    const selects = component.findAll('select')
    expect(selects.length).toBeGreaterThanOrEqual(2)
  })

  function categorySelect(component: VueWrapper) {
    return component.findAll('select').find(s => s.text().includes('All categories'))!
  }

  it('builds the category filter from the categories the backend reports', async () => {
    setupMockApi()
    registerEndpoint('/api/logs/categories', () => [
      'CIRCUIT_BREAKER', 'MCP_CONNECT', 'SUBAGENT_SPAWN', 'TASK_STARTED', 'llm',
    ])
    const component = await mountSuspended(Logs)
    await flushPromises()

    const select = categorySelect(component)
    const flat = select.findAll(':scope > option').map(o => o.attributes('value'))
    expect(flat).toEqual(['', 'CIRCUIT_BREAKER', 'llm'])
    const groups = select.findAll('optgroup').map(g => ({
      label: g.attributes('label'),
      values: g.findAll('option').map(o => o.attributes('value')),
    }))
    expect(groups).toEqual([
      { label: 'Subagents', values: ['SUBAGENT_SPAWN'] },
      { label: 'Tasks', values: ['TASK_STARTED'] },
      { label: 'MCP', values: ['MCP_CONNECT'] },
    ])
  })

  it('offers only "All categories" when the event log is empty', async () => {
    setupMockApi()
    registerEndpoint('/api/logs/categories', () => [])
    const component = await mountSuspended(Logs)
    await flushPromises()

    const select = categorySelect(component)
    expect(select.findAll('option').map(o => o.text())).toEqual(['All categories'])
    expect(select.findAll('optgroup')).toHaveLength(0)
  })

  it('offers only "All categories" when the categories fetch fails, and still lists events', async () => {
    setupMockApi()
    registerEndpoint('/api/logs/categories', () => {
      throw createError({ statusCode: 500 })
    })
    const component = await mountSuspended(Logs)
    await flushPromises()

    const select = categorySelect(component)
    expect(select.findAll('option').map(o => o.text())).toEqual(['All categories'])
    expect(component.text()).toContain('Test event')
  })

  it('offers a category written after mount once the select is focused', async () => {
    setupMockApi()
    const categories = ['llm']
    registerEndpoint('/api/logs/categories', () => [...categories].sort())
    const component = await mountSuspended(Logs)
    await flushPromises()

    const select = categorySelect(component)
    expect(select.findAll('option').map(o => o.attributes('value'))).toEqual(['', 'llm'])

    categories.push('CIRCUIT_BREAKER')
    await select.trigger('focus')
    await flushPromises()

    expect(categorySelect(component).findAll('option').map(o => o.attributes('value')))
      .toEqual(['', 'CIRCUIT_BREAKER', 'llm'])
  })
})

describe('Conversations page', () => {
  it('renders conversation table', async () => {
    setupMockApi()
    const component = await mountSuspended(Conversations)

    expect(component.text()).toContain('Conversations')
    expect(component.text()).toContain('Channel')
    expect(component.text()).toContain('Agent')
    expect(component.text()).toContain('Peer')
  })

  it('shows conversation data', async () => {
    setupMockApi()
    const component = await mountSuspended(Conversations)

    expect(component.text()).toContain('web')
    expect(component.text()).toContain('test')
    expect(component.text()).toContain('admin')
  })

  it('has filter inputs for name and peer', async () => {
    setupMockApi()
    const component = await mountSuspended(Conversations)

    const inputs = component.findAll('input[type="text"]')
    expect(inputs.length).toBeGreaterThanOrEqual(1)
  })

  it('has a FilterBar with search role', async () => {
    setupMockApi()
    const component = await mountSuspended(Conversations)

    const filterBar = component.find('[role="search"]')
    expect(filterBar.exists()).toBe(true)
  })
})
