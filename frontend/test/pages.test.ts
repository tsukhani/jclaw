import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { registerEndpoint } from '@nuxt/test-utils/runtime'
import Index from '~/pages/index.vue'
import Agents from '~/pages/agents.vue'
import Settings from '~/pages/settings.vue'
import Logs from '~/pages/logs.vue'
import Conversations from '~/pages/conversations.vue'

// Register mock API endpoints
function setupMockApi() {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'test', modelProvider: 'ollama-cloud', modelId: 'kimi-k2.5', enabled: true, isMain: false, providerConfigured: true }
  ])
  registerEndpoint('/api/channels', () => [
    { channelType: 'telegram', enabled: false },
    { channelType: 'slack', enabled: true }
  ])
  registerEndpoint('/api/tasks', () => [])
  registerEndpoint('/api/logs', () => ({ events: [
    { id: 1, timestamp: '2026-04-07T10:00:00Z', level: 'INFO', category: 'system', agentId: null, message: 'Test event' }
  ]}))
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1', updatedAt: '2026-04-07T10:00:00Z' },
      { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****', updatedAt: '2026-04-07T10:00:00Z' },
      { key: 'provider.ollama-cloud.models', value: '[{"id":"kimi-k2.5","name":"Kimi K2.5","contextWindow":262144,"maxTokens":65535}]', updatedAt: '2026-04-07T10:00:00Z' }
    ]
  }))
  registerEndpoint('/api/conversations', () => [
    { id: 1, agentId: 1, agentName: 'test', channelType: 'web', peerId: 'admin', messageCount: 3, preview: 'Hello', createdAt: '2026-04-07T10:00:00Z', updatedAt: '2026-04-07T10:00:00Z' }
  ])
}

describe('Dashboard page', () => {
  it('renders stats cards', async () => {
    setupMockApi()
    const component = await mountSuspended(Index)

    expect(component.text()).toContain('Dashboard')
    expect(component.text()).toContain('Agents enabled')
    expect(component.text()).toContain('Channels active')
    expect(component.text()).toContain('Tasks pending')
    expect(component.text()).toContain('Recent events')
  })

  it('displays agent count', async () => {
    setupMockApi()
    const component = await mountSuspended(Index)

    // 1 agent enabled out of 1 total
    expect(component.text()).toContain('1/1')
  })

  it('shows recent events', async () => {
    setupMockApi()
    const component = await mountSuspended(Index)

    expect(component.text()).toContain('Test event')
    expect(component.text()).toContain('system')
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

    const button = component.find('button')
    expect(button.text()).toContain('New Agent')
  })
})

describe('Settings page', () => {
  it('renders provider sections', async () => {
    setupMockApi()
    const component = await mountSuspended(Settings)

    expect(component.text()).toContain('Settings')
    expect(component.text()).toContain('LLM Providers')
    expect(component.text()).toContain('ollama-cloud')
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
})
