import { describe, it, expect } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'
import Conversations from '~/pages/conversations/index.vue'

/**
 * JCLAW-267 frontend coverage:
 *
 *   - Chat view: messages sharing a {@code subagentRunId} render as a
 *     collapsible nested-turn block with a header pill carrying the run's
 *     label + terminal status. Collapsed-by-default for COMPLETED runs.
 *   - Conversations sidebar: rows with a {@code parentConversationId} carry
 *     a "subagent" badge so operators can distinguish delegated runs from
 *     user-initiated chats.
 *
 * Both are pinned with backend-fixture mocks; the rendering contract is
 * what the operator sees on screen, so the assertions look for the
 * visible label / badge text.
 */

function setupBaseAgents() {
  registerEndpoint('/api/agents', () => [
    {
      id: 1,
      name: 'main-agent',
      modelProvider: 'ollama-cloud',
      modelId: 'kimi-k2.5',
      enabled: true,
      isMain: true,
      thinkingMode: null,
      providerConfigured: true,
    },
  ])
  registerEndpoint('/api/config', () => ({
    entries: [
      { key: 'provider.ollama-cloud.baseUrl', value: 'https://ollama.com/v1' },
      { key: 'provider.ollama-cloud.apiKey', value: 'xxxx****' },
      { key: 'provider.ollama-cloud.models',
        value: '[{"id":"kimi-k2.5","name":"Kimi K2.5","supportsThinking":false}]' },
    ],
  }))
}

describe('Chat page — inline-subagent rendering (JCLAW-267)', () => {
  it('renders the subagent block header for messages with subagentRunId', async () => {
    setupBaseAgents()
    registerEndpoint('/api/conversations', () => [
      { id: 42, agentId: 1, agentName: 'main-agent', channelType: 'web',
        peerId: 'admin', messageCount: 5, preview: 'inline subagent demo',
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
    ])
    // Fixture: parent user prompt, inline-subagent block (start marker +
    // child user task + child assistant reply + end marker), parent final
    // reply. All three middle rows share subagentRunId=7 so they fold
    // into one collapsible block.
    registerEndpoint('/api/conversations/42/messages', () => [
      { id: 1, role: 'user', content: 'Please delegate this',
        createdAt: '2026-05-14T10:00:00Z' },
      { id: 2, role: 'assistant', content: 'Spawning subagent: investigate-x — investigate X',
        subagentRunId: 7,
        createdAt: '2026-05-14T10:00:01Z' },
      { id: 3, role: 'user', content: 'investigate X',
        subagentRunId: 7,
        createdAt: '2026-05-14T10:00:02Z' },
      { id: 4, role: 'assistant', content: 'Investigation done.',
        subagentRunId: 7,
        createdAt: '2026-05-14T10:00:03Z' },
      { id: 5, role: 'assistant', content: 'Subagent completed: Investigation done.',
        subagentRunId: 7,
        createdAt: '2026-05-14T10:00:04Z' },
      { id: 6, role: 'assistant', content: 'Here is the result.',
        createdAt: '2026-05-14T10:00:05Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()
    await flushPromises()

    // Force-load the conversation through the page's loadConversation entry
    // point so the subagent-block hydration runs (mirrors the chat.page test
    // pattern for the tool-calls fixture).
    const vm = component.vm as unknown as { loadConversation: (id: number) => Promise<void> }
    await vm.loadConversation(42)
    await flushPromises()

    // The block header pill renders with the run's label (derived from the
    // boundary-start marker's content). "investigate-x — investigate X" is
    // what SpawnSubagentTool stamps for label+task.
    const text = component.text()
    expect(text).toContain('Subagent:')
    expect(text).toContain('investigate-x')
    // Terminal-status pill reflects the COMPLETED end-marker.
    expect(text).toContain('Completed')
    // The parent's outer messages are still visible — collapse only hides
    // the block's body, not the surrounding turns.
    expect(text).toContain('Please delegate this')
    expect(text).toContain('Here is the result.')
  })

  it('shows Failed status pill when the boundary-end marker indicates failure', async () => {
    setupBaseAgents()
    registerEndpoint('/api/conversations', () => [
      { id: 43, agentId: 1, agentName: 'main-agent', channelType: 'web',
        peerId: 'admin', messageCount: 3, preview: 'failed run',
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
    ])
    registerEndpoint('/api/conversations/43/messages', () => [
      { id: 10, role: 'user', content: 'try this',
        createdAt: '2026-05-14T10:00:00Z' },
      { id: 11, role: 'assistant', content: 'Spawning subagent: try',
        subagentRunId: 9,
        createdAt: '2026-05-14T10:00:01Z' },
      { id: 12, role: 'assistant', content: 'Subagent failed: provider error',
        subagentRunId: 9,
        createdAt: '2026-05-14T10:00:02Z' },
    ])

    const component = await mountSuspended(Chat)
    await flushPromises()

    const vm = component.vm as unknown as { loadConversation: (id: number) => Promise<void> }
    await vm.loadConversation(43)
    await flushPromises()

    expect(component.text()).toContain('Failed')
  })
})

describe('Conversations sidebar — subagent badge (JCLAW-267)', () => {
  it('renders the subagent badge for rows with a parentConversationId', async () => {
    registerEndpoint('/api/agents', () => [
      { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud',
        modelId: 'kimi-k2.5', enabled: true, isMain: true,
        thinkingMode: null, providerConfigured: true },
    ])
    registerEndpoint('/api/conversations', () => [
      // Session-mode subagent child: parentConversationId is set.
      { id: 100, agentId: 1, agentName: 'main-agent-sub-1', channelType: 'subagent',
        peerId: null, messageCount: 3, preview: 'investigate X',
        parentConversationId: 99,
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
      // Top-level user-initiated chat: no parent.
      { id: 99, agentId: 1, agentName: 'main-agent', channelType: 'web',
        peerId: 'admin', messageCount: 2, preview: 'Top-level chat',
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
    ])

    const component = await mountSuspended(Conversations)
    await flushPromises()

    // The badge text is "subagent" (lowercase, in a small uppercase font
    // via Tailwind). The chip's title attribute references the parent id
    // so screen readers and hover-tooltips can disambiguate.
    expect(component.text()).toContain('subagent')
    const badge = component.findAll('span').find(s => s.text().trim() === 'subagent')
    expect(badge?.exists() ?? false).toBe(true)
    expect(badge?.attributes('title')).toContain('#99')
  })

  it('omits the badge for top-level conversations', async () => {
    registerEndpoint('/api/agents', () => [
      { id: 1, name: 'main-agent', modelProvider: 'ollama-cloud',
        modelId: 'kimi-k2.5', enabled: true, isMain: true,
        thinkingMode: null, providerConfigured: true },
    ])
    registerEndpoint('/api/conversations', () => [
      { id: 1, agentId: 1, agentName: 'main-agent', channelType: 'web',
        peerId: 'admin', messageCount: 4, preview: 'just a chat',
        createdAt: '2026-05-14T10:00:00Z', updatedAt: '2026-05-14T10:00:00Z' },
    ])

    const component = await mountSuspended(Conversations)
    await flushPromises()

    // No badge for a row without parentConversationId.
    expect(component.text()).not.toContain('subagent')
  })
})
