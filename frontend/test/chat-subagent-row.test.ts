import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatSubagentRow from '~/components/chat/ChatSubagentRow.vue'
import type { Message } from '~/types/api'

function announceMsg(payload: Record<string, unknown> = {}, extra: Record<string, unknown> = {}): Message {
  return { role: 'SYSTEM', content: '', messageKind: 'subagent_announce', metadata: payload, ...extra } as unknown as Message
}

describe('ChatSubagentRow (announce card, JCLAW-291)', () => {
  it('renders the label, status badge, and reply', async () => {
    const c = await mountSuspended(ChatSubagentRow, { props: { msg: announceMsg({ label: 'research-run', status: 'COMPLETED', reply: 'Found 3 sources' }), agentId: null } })
    expect(c.text()).toContain('Subagent: research-run')
    expect(c.text()).toContain('COMPLETED')
    expect(c.find('[data-testid="subagent-announce-card"]').text()).toContain('Found 3 sources')
  })
  it('shows the View full link when a child conversation id is present', async () => {
    const c = await mountSuspended(ChatSubagentRow, { props: { msg: announceMsg({ childConversationId: 42 }), agentId: null } })
    const link = c.find('[data-testid="subagent-announce-view-full"]')
    expect(link.exists()).toBe(true)
    expect(link.attributes('href')).toContain('conversation=42')
  })
  it('omits the View full link without a child id', async () => {
    const c = await mountSuspended(ChatSubagentRow, { props: { msg: announceMsg({}), agentId: null } })
    expect(c.find('[data-testid="subagent-announce-view-full"]').exists()).toBe(false)
  })
  it('shows the truncation marker when the reply was truncated', async () => {
    const c = await mountSuspended(ChatSubagentRow, { props: { msg: announceMsg({ truncated: true }), agentId: null } })
    expect(c.find('[data-testid="truncated-marker"]').exists()).toBe(true)
  })
  it('defaults an empty payload to label "run" and status COMPLETED', async () => {
    const c = await mountSuspended(ChatSubagentRow, { props: { msg: announceMsg({}), agentId: null } })
    expect(c.text()).toContain('Subagent: run')
    expect(c.text()).toContain('COMPLETED')
  })
})
