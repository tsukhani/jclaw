import { describe, it, expect } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import Index from '~/pages/index.vue'
import Agents from '~/pages/agents.vue'
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
    expect(component.text()).toContain('Agents enabled')
    expect(component.text()).toContain('Conversations had')
    expect(component.text()).toContain('Channels active')
    expect(component.text()).toContain('Tasks pending')
  })

  it('displays agent count', async () => {
    setupMockApi()
    const component = await mountSuspended(Index)

    // 1 agent enabled out of 1 total
    expect(component.text()).toContain('1/1')
  })

  it('renders cards in left-to-right order: Agents, Conversations, Channels, Tasks', async () => {
    setupMockApi()
    const component = await mountSuspended(Index)

    const text = component.text()
    const agentsIdx = text.indexOf('Agents enabled')
    const convosIdx = text.indexOf('Conversations had')
    const channelsIdx = text.indexOf('Channels active')
    const tasksIdx = text.indexOf('Tasks pending')
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
    setupMockApi()
    const component = await mountSuspended(Settings)

    expect(component.text()).toContain('Settings')
    expect(component.text()).toContain('LLM Providers')
    // JCLAW-182: provider cards now render the friendly display label.
    expect(component.text()).toContain('Ollama Cloud')
  })

  it('does not expose an add-entry form for ad-hoc config', async () => {
    // The generic config list is a read-only diagnostic for stale/unmanaged keys,
    // not a place to create new rows — arbitrary keys aren't read by anything.
    setupMockApi()
    const component = await mountSuspended(Settings)

    expect(component.text()).not.toContain('Add Entry')
  })
})

describe('Logs page', () => {
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
