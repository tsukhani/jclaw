import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatThinkingCard from '~/components/chat/ChatThinkingCard.vue'

function props(o: Record<string, unknown> = {}) {
  return {
    collapsed: false,
    headerLabel: 'Thought for 3s',
    copied: false,
    reasoning: 'Let me think about this',
    agentId: null as number | null,
    isStreaming: false,
    streamHtml: '',
    ...o,
  }
}

describe('ChatThinkingCard', () => {
  it('renders the header label and the reasoning body when expanded', async () => {
    const c = await mountSuspended(ChatThinkingCard, { props: props() })
    expect(c.text()).toContain('Thought for 3s')
    const body = c.find('[data-reasoning-body]')
    expect(body.exists()).toBe(true)
    expect(body.text()).toContain('Let me think about this')
  })
  it('hides the reasoning body when collapsed', async () => {
    const c = await mountSuspended(ChatThinkingCard, { props: props({ collapsed: true }) })
    expect(c.find('[data-reasoning-body]').exists()).toBe(false)
  })
  it('renders the streaming HTML instead of the rendered reasoning while streaming', async () => {
    const c = await mountSuspended(ChatThinkingCard, { props: props({ isStreaming: true, streamHtml: '<p>live tokens</p>' }) })
    expect(c.find('[data-reasoning-body]').html()).toContain('live tokens')
  })
  it('reflects the copied flash state', async () => {
    const c = await mountSuspended(ChatThinkingCard, { props: props({ copied: true }) })
    expect(c.find('button[title="Copied"]').exists()).toBe(true)
  })
  it('emits toggle and copy from the respective buttons', async () => {
    const c = await mountSuspended(ChatThinkingCard, { props: props() })
    await c.find('button[title="Collapse reasoning"]').trigger('click')
    expect(c.emitted('toggle')).toBeTruthy()
    await c.find('button[title="Copy reasoning"]').trigger('click')
    expect(c.emitted('copy')).toBeTruthy()
  })
})
