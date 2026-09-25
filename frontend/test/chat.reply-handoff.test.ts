import { describe, it, expect } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Chat from '~/pages/chat.vue'

// Its own file: a Chat page left mounted by another spec would take the hand-off first.
describe('Chat page — a reply handed over from a reminder toast (JCLAW-1299)', () => {
  it('opens the reminder\'s agent with the reminder quoted above the composer', async () => {
    registerEndpoint('/api/agents', () => [
      { id: 1, name: 'main', modelProvider: 'p', modelId: 'm', enabled: true, isMain: true, thinkingMode: null, providerConfigured: true },
      { id: 2, name: 'helper', modelProvider: 'p', modelId: 'm', enabled: true, isMain: false, thinkingMode: null, providerConfigured: true },
    ])
    registerEndpoint('/api/conversations', () => [])
    registerEndpoint('/api/subagent-runs', () => [])
    useChatReplyHandoff().value = { agentId: 2, quote: { kind: 'reminder', text: 'Call the dentist' } }

    const component = await mountSuspended(Chat)
    await flushPromises()

    const preview = component.find('[data-testid="reply-quote"]')
    expect(preview.text()).toContain('Replying to a reminder')
    expect(preview.text()).toContain('Call the dentist')
    expect((component.vm as unknown as { selectedAgentId: number }).selectedAgentId).toBe(2)
    expect(useChatReplyHandoff().value).toBeNull()
  })
})
