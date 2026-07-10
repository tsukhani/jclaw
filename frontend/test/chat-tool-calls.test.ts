import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatToolCalls from '~/components/chat/ChatToolCalls.vue'
import type { ToolCall } from '~/types/api'

function tc(o: Partial<ToolCall> = {}): ToolCall {
  return { id: 't1', name: 'web_search', icon: 'search', arguments: JSON.stringify({ query: 'cats' }), ...o }
}

describe('ChatToolCalls (JCLAW-170)', () => {
  it('renders the call count and the web_search preview when expanded', async () => {
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: [tc()], collapsed: false } })
    expect(c.text()).toContain('1 tool call')
    expect(c.text()).toContain('Searched "cats"')
  })
  it('hides the call rows when collapsed', async () => {
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: [tc()], collapsed: true } })
    expect(c.text()).not.toContain('Searched "cats"')
  })
  it('emits toggle-collapse when the header is clicked', async () => {
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: [tc()], collapsed: false } })
    await c.find('button[title="Collapse tool calls"]').trigger('click')
    expect(c.emitted('toggle-collapse')).toBeTruthy()
  })
  it('emits toggle-call with the call when an expandable row is clicked', async () => {
    const call = tc({ resultText: 'a result body' })
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: [call], collapsed: false } })
    const rowBtn = c.findAll('button').find(b => b.text().includes('Searched'))!
    await rowBtn.trigger('click')
    expect(c.emitted('toggle-call')).toBeTruthy()
    expect(c.emitted('toggle-call')![0]![0]).toBe(call)
  })
  it('shows the truncated result text when the call is expanded', async () => {
    const call = tc({ resultText: 'the full tool output', _expanded: true })
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: [call], collapsed: false } })
    expect(c.find('pre').text()).toContain('the full tool output')
  })
})
