import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Subagents from '~/pages/subagents.vue'

/**
 * JCLAW-271 frontend coverage for the SubagentRuns admin page:
 *
 *   - The page renders rows from /api/subagent-runs.
 *   - The Kill button only appears for RUNNING rows.
 *   - Clicking Kill POSTs to /api/subagent-runs/{id}/kill.
 *
 * The admin sidebar link is exercised in pages.test.ts; this file pins
 * the page-local behavior.
 */

function setupAgents() {
  registerEndpoint('/api/agents', () => [
    { id: 1, name: 'main', modelProvider: 'openrouter', modelId: 'gpt-4.1',
      enabled: true, isMain: true, providerConfigured: true },
  ])
}

describe('Subagents admin page', () => {
  beforeEach(() => {
    setupAgents()
  })

  it('renders runs from the API including parent/child agent names and statuses', async () => {
    registerEndpoint('/api/subagent-runs', () => [
      { id: 11, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 2, childAgentName: 'main-sub-abc',
        parentConversationId: 5, childConversationId: 6,
        mode: 'session', status: 'RUNNING',
        startedAt: '2026-05-14T10:00:00Z', endedAt: null, outcome: null },
      { id: 12, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 3, childAgentName: 'main-sub-def',
        parentConversationId: 5, childConversationId: 7,
        mode: 'session', status: 'COMPLETED',
        startedAt: '2026-05-14T09:00:00Z',
        endedAt: '2026-05-14T09:00:30Z',
        outcome: 'Task done.' },
    ])

    const component = await mountSuspended(Subagents)
    await flushPromises()

    const text = component.text()
    expect(text).toContain('Subagent Runs')
    expect(text).toContain('#11')
    expect(text).toContain('#12')
    expect(text).toContain('main-sub-abc')
    expect(text).toContain('main-sub-def')
    expect(text).toContain('RUNNING')
    expect(text).toContain('COMPLETED')
    expect(text).toContain('session')
  })

  it('shows a Kill button only on RUNNING rows', async () => {
    registerEndpoint('/api/subagent-runs', () => [
      { id: 21, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 2, childAgentName: 'still-running',
        parentConversationId: 5, childConversationId: 6,
        mode: 'session', status: 'RUNNING',
        startedAt: '2026-05-14T10:00:00Z', endedAt: null, outcome: null },
      { id: 22, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 3, childAgentName: 'finished',
        parentConversationId: 5, childConversationId: 7,
        mode: 'session', status: 'COMPLETED',
        startedAt: '2026-05-14T09:00:00Z',
        endedAt: '2026-05-14T09:00:30Z', outcome: 'ok' },
    ])

    const component = await mountSuspended(Subagents)
    await flushPromises()

    // Kill buttons are the only buttons in the table cells; the filter
    // bar above has selects but no buttons, so a button-only query lands
    // on the action column.
    const killButtons = component.findAll('button')
      .filter(b => b.text().toLowerCase().includes('kill'))
    expect(killButtons.length).toBe(1)
  })

  it('renders a "View transcript" link per row with the right href (JCLAW-274)', async () => {
    registerEndpoint('/api/subagent-runs', () => [
      { id: 31, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 2, childAgentName: 'main-sub-running',
        parentConversationId: 5, childConversationId: 42,
        mode: 'session', status: 'RUNNING',
        startedAt: '2026-05-14T10:00:00Z', endedAt: null, outcome: null },
      { id: 32, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 3, childAgentName: 'main-sub-done',
        parentConversationId: 5, childConversationId: 43,
        mode: 'session', status: 'COMPLETED',
        startedAt: '2026-05-14T09:00:00Z',
        endedAt: '2026-05-14T09:00:30Z', outcome: 'ok' },
    ])

    const component = await mountSuspended(Subagents)
    await flushPromises()

    // One transcript link per row, regardless of status. The href shape
    // matches the JCLAW-270 announce card's "View full" link
    // (/chat?conversation=ID) so both surfaces converge on the standard
    // conversation viewer.
    const links = component.findAll('a')
      .filter(a => a.text().toLowerCase().includes('view transcript'))
    expect(links.length).toBe(2)
    const hrefs = links.map(a => a.attributes('href'))
    expect(hrefs).toContain('/chat?conversation=42')
    expect(hrefs).toContain('/chat?conversation=43')
  })

  it('calls the kill endpoint when the Kill button is clicked', async () => {
    // Use a distinct row id so the registerEndpoint registry state from
    // earlier tests in this file (vitest reuses the in-process Nuxt
    // instance across `it` blocks) can't bleed into the assertion.
    const targetId = 9931
    registerEndpoint('/api/subagent-runs', () => [
      { id: targetId, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 2, childAgentName: 'doomed',
        parentConversationId: 5, childConversationId: 6,
        mode: 'session', status: 'RUNNING',
        startedAt: '2026-05-14T10:00:00Z', endedAt: null, outcome: null },
    ])

    const killSpy = vi.fn(() => ({ killed: true, status: 'KILLED', message: 'Run killed.' }))
    registerEndpoint(`/api/subagent-runs/${targetId}/kill`, {
      method: 'POST',
      handler: killSpy,
    })

    const component = await mountSuspended(Subagents)
    await flushPromises()

    const killBtn = component.findAll('button')
      .find(b => b.text().toLowerCase().includes('kill'))
    expect(killBtn?.exists()).toBe(true)
    await killBtn!.trigger('click')
    await flushPromises()

    expect(killSpy).toHaveBeenCalledOnce()
  })
})
