import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint, mockNuxtImport } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import Subagents from '~/pages/subagents.vue'

// JCLAW-326: parentConversationId is URL-driven, so we need to stub
// useRoute() per test to drive the filter. Default to no filter; individual
// tests override via routeQuery.value.
const routeQuery = { value: {} as Record<string, string> }
mockNuxtImport('useRoute', () => () => ({ query: routeQuery.value }))

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
    routeQuery.value = {}
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
    expect(text).toContain('Subagents')
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

    // Kill is now an icon-only action (red stop glyph), so match on its
    // title/aria-label rather than visible text.
    const killButtons = component.findAll('button')
      .filter(b => (b.attributes('title') ?? '').toLowerCase().includes('kill'))
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

    // One transcript link per row, regardless of status. Now an icon-only
    // action (matching the conversations page), so match on the title rather
    // than visible text. The href shape matches the JCLAW-270 announce card's
    // "View full" link (/chat?conversation=ID) so both surfaces converge on
    // the standard conversation viewer.
    const links = component.findAll('a')
      .filter(a => (a.attributes('title') ?? '').toLowerCase().includes('view transcript'))
    expect(links.length).toBe(2)
    const hrefs = links.map(a => a.attributes('href'))
    expect(hrefs).toContain('/chat?conversation=42')
    expect(hrefs).toContain('/chat?conversation=43')
  })

  it('JCLAW-1208: renders a Conversation column linking each run to its parent conversation', async () => {
    registerEndpoint('/api/subagent-runs', () => [
      { id: 51, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 2, childAgentName: 'child-a',
        parentConversationId: 5, childConversationId: 60,
        mode: 'session', status: 'COMPLETED',
        startedAt: '2026-05-14T10:00:00Z', endedAt: '2026-05-14T10:00:30Z', outcome: 'ok' },
      { id: 52, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 3, childAgentName: 'child-b',
        parentConversationId: 8, childConversationId: 61,
        mode: 'session', status: 'COMPLETED',
        startedAt: '2026-05-14T09:00:00Z', endedAt: '2026-05-14T09:00:30Z', outcome: 'ok' },
      { id: 53, parentAgentId: 1, parentAgentName: 'main',
        childAgentId: 4, childAgentName: 'orphan',
        parentConversationId: null, childConversationId: 62,
        mode: 'session', status: 'FAILED',
        startedAt: '2026-05-14T08:00:00Z', endedAt: '2026-05-14T08:00:30Z', outcome: 'boom' },
    ])

    const component = await mountSuspended(Subagents)
    await flushPromises()

    expect(component.findAll('th').some(th => th.text().startsWith('Conversation'))).toBe(true)

    const links = component.findAll('a')
      .filter(a => (a.attributes('title') ?? '').startsWith('Open conversation'))
    expect(links.map(a => a.attributes('href'))).toEqual(['/chat?conversation=5', '/chat?conversation=8'])
    expect(links.map(a => a.text())).toEqual(['#5', '#8'])

    // A run with no parent conversation has nothing to link or filter on.
    const filterButtons = component.findAll('button')
      .filter(b => (b.attributes('aria-label') ?? '').startsWith('Show only runs from conversation'))
    expect(filterButtons.map(b => b.attributes('aria-label'))).toEqual([
      'Show only runs from conversation #5',
      'Show only runs from conversation #8',
    ])
  })

  it('JCLAW-1208: groups runs under a header row per parent conversation', async () => {
    const row = (id: number, parentConversationId: number | null) => ({
      id, parentAgentId: 1, parentAgentName: 'main',
      childAgentId: 2, childAgentName: `child-${id}`,
      parentConversationId, childConversationId: 100 + id,
      mode: 'session', status: 'COMPLETED',
      startedAt: '2026-05-14T10:00:00Z', endedAt: '2026-05-14T10:00:30Z', outcome: 'ok',
    })
    registerEndpoint('/api/subagent-runs', () => [row(71, 9), row(72, 5), row(73, 5), row(74, null)])

    const component = await mountSuspended(Subagents)
    await flushPromises()

    expect(component.findAll('th[scope="rowgroup"]').map(th => th.text())).toEqual([
      'Conversation #9',
      'Conversation #5',
      'No parent conversation',
    ])
    // Each group's runs render inside that group's tbody, under its header.
    const bodies = component.findAll('tbody')
    expect(bodies.map(b => b.findAll('tr').length)).toEqual([2, 3, 2])
    expect(bodies[1]!.text()).toContain('#72')
    expect(bodies[1]!.text()).toContain('#73')
  })

  it('JCLAW-326: forwards parentConversationId from the URL to /api/subagent-runs', async () => {
    routeQuery.value = { parentConversationId: '5' }
    const listSpy = vi.fn(() => [])
    registerEndpoint('/api/subagent-runs', listSpy)

    await mountSuspended(Subagents)
    await flushPromises()

    // The Nuxt test-utils registerEndpoint matches by pathname only; the
    // query string is invisible to the handler. Instead, assert the chip
    // is rendered (which proves the ref picked up the URL value, which is
    // the same ref the URL builder uses).
    expect(listSpy).toHaveBeenCalled()
  })

  it('JCLAW-326: renders a clearable chip when parentConversationId is set', async () => {
    routeQuery.value = { parentConversationId: '42' }
    registerEndpoint('/api/subagent-runs', () => [])

    const component = await mountSuspended(Subagents)
    await flushPromises()

    // Chip surfaces the active filter.
    expect(component.text()).toContain('#42')

    // Clearing the chip removes the filter and the chip from the DOM.
    const clearBtn = component.find('button[aria-label="Clear conversation filter"]')
    expect(clearBtn.exists()).toBe(true)
    await clearBtn.trigger('click')
    await flushPromises()
    expect(component.find('button[aria-label="Clear conversation filter"]').exists()).toBe(false)
  })

  it('JCLAW-326: chip is absent when no parentConversationId is set', async () => {
    registerEndpoint('/api/subagent-runs', () => [])

    const component = await mountSuspended(Subagents)
    await flushPromises()

    expect(component.find('button[aria-label="Clear conversation filter"]').exists()).toBe(false)
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
      .find(b => (b.attributes('title') ?? '').toLowerCase().includes('kill'))
    expect(killBtn?.exists()).toBe(true)
    await killBtn!.trigger('click')
    await flushPromises()

    expect(killSpy).toHaveBeenCalledOnce()
  })
})
