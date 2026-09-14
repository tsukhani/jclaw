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
  it('names the tool and previews the argument that says what the call did', async () => {
    const spawn = tc({ name: 'subagent_spawn', icon: 'users',
      arguments: JSON.stringify({ async: true, label: 'Say hello', mode: 'session', task: 'Reply HELLO.' }) })
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: [spawn], collapsed: false } })
    expect(c.text()).toContain('Used tool: subagent_spawn · Say hello')
    expect(c.text()).not.toContain('async: true')
  })
  it('falls back to the first argument, and to the bare tool name for a call without arguments', async () => {
    const calls = [
      tc({ id: 't2', name: 'subagent_yield', icon: 'users', arguments: JSON.stringify({ all: true }) }),
      tc({ id: 't3', name: 'datetime', icon: 'users', arguments: '{}' }),
    ]
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: calls, collapsed: false } })
    const rows = c.findAll('button').filter(b => b.text().startsWith('Used tool:'))
    expect(rows.map(r => r.text())).toEqual(['Used tool: subagent_yield · all: true', 'Used tool: datetime'])
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
  it('draws the header icon beside its chevron, and the globe for a result chip without a favicon', async () => {
    const call = tc({ _expanded: true, resultStructured: { results: [{ title: 'Cats', url: 'https://example.com/cats' }] } as ToolCall['resultStructured'] })
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: [call], collapsed: false } })
    expect(c.findAll('button[title="Collapse tool calls"] svg')).toHaveLength(2)
    expect(c.find('a[href="https://example.com/cats"] img').exists()).toBe(false)
    expect(c.find('a[href="https://example.com/cats"] svg').exists()).toBe(true)
  })
  it('pretty-prints a JSON result and keeps the line breaks of a multi-line string', async () => {
    const resultText = JSON.stringify({ results: [{ run_id: '7', reply: 'First line.\n\nSecond "quoted" line.' }], count: 1 })
    const call = tc({ name: 'subagent_yield', arguments: '{"all":true}', resultText, _expanded: true })
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: [call], collapsed: false } })
    expect(c.find('pre').element.textContent).toBe(
      '{\n  "results": [\n    {\n      "run_id": "7",\n      "reply": "First line.\n\nSecond "quoted" line."\n    }\n  ],\n  "count": 1\n}',
    )
  })
  it('leaves a result that is not JSON exactly as the tool returned it', async () => {
    const resultText = '{not json}\nexit code 0 \\n stays escaped'
    const c = await mountSuspended(ChatToolCalls, { props: { toolCalls: [tc({ resultText, _expanded: true })], collapsed: false } })
    expect(c.find('pre').element.textContent).toBe(resultText)
  })
})
