import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { createError } from 'h3'
import AgentWorkspaceManager from '~/components/agents/AgentWorkspaceManager.vue'
import type { WorkspaceListing } from '~/types/api'

/**
 * The workspace tree refreshes on its own.
 *
 * Four guarantees: a file a running agent writes reaches the screen within the interval,
 * a tab nobody is looking at costs nothing, a slow older reply never repaints the tree, and
 * no refresh moves a row while the pointer or keyboard focus is in the list.
 */
const REFRESH_MS = 10_000

function listingOf(...names: string[]): WorkspaceListing {
  return {
    total: names.length * 1024,
    entries: names.map(name => ({
      path: name,
      name,
      kind: 'file' as const,
      size: 1024,
      modified: 1_763_380_800_000,
      protected: false,
      children: null,
    })),
  }
}

function deferred() {
  let settle: (value: WorkspaceListing) => void = () => {}
  const promise = new Promise<WorkspaceListing>((resolve) => {
    settle = resolve
  })
  return { promise, resolve: (value: WorkspaceListing) => settle(value) }
}

// A poll's round trip needs a macrotask turn of its own beyond the one that dispatched it.
async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

function defineVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
}

function setVisibility(state: 'visible' | 'hidden') {
  defineVisibility(state)
  document.dispatchEvent(new Event('visibilitychange'))
}

describe('AgentWorkspaceManager auto-refresh', () => {
  beforeEach(() => {
    clearNuxtData()
    // Only the interval is faked: flushPromises schedules itself on setTimeout/setImmediate.
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
  })

  afterEach(() => {
    vi.useRealTimers()
    defineVisibility('visible')
  })

  it('shows a file the agent wrote, without a click or a reload', async () => {
    let calls = 0
    registerEndpoint('/api/agents/21/workspace-tree', () => {
      calls += 1
      return calls === 1 ? listingOf('notes.md') : listingOf('notes.md', 'report.pdf')
    })

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 21 } })
    await settle()
    expect(component.find('[data-testid="ws-row-report.pdf"]').exists()).toBe(false)
    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 1.0 KB')

    vi.advanceTimersByTime(REFRESH_MS)
    await settle()

    expect(component.find('[data-testid="ws-row-report.pdf"]').exists()).toBe(true)
    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 2.0 KB')
    component.unmount()
  })

  it('makes no request while the tab is hidden, and catches up when it returns', async () => {
    let calls = 0
    registerEndpoint('/api/agents/22/workspace-tree', () => {
      calls += 1
      return listingOf('notes.md')
    })

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 22 } })
    await settle()
    expect(calls).toBe(1)

    setVisibility('hidden')
    vi.advanceTimersByTime(REFRESH_MS * 3)
    await settle()
    expect(calls).toBe(1)

    setVisibility('visible')
    await settle()
    expect(calls).toBe(2)

    vi.advanceTimersByTime(REFRESH_MS)
    await settle()
    expect(calls).toBe(3)
    component.unmount()
  })

  it('stops polling once the section is unmounted', async () => {
    let calls = 0
    registerEndpoint('/api/agents/23/workspace-tree', () => {
      calls += 1
      return listingOf('notes.md')
    })

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 23 } })
    await settle()
    expect(calls).toBe(1)

    component.unmount()
    vi.advanceTimersByTime(REFRESH_MS * 3)
    await settle()
    expect(calls).toBe(1)
  })

  it('discards a refresh that arrives after a newer one', async () => {
    const older = deferred()
    const newer = deferred()
    let seen = 0
    registerEndpoint('/api/agents/24/workspace-tree', () => {
      seen += 1
      return seen === 1 ? older.promise : newer.promise
    })

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 24 } })
    await settle()
    vi.advanceTimersByTime(REFRESH_MS)
    await settle()
    expect(seen).toBe(2)

    newer.resolve(listingOf('notes.md', 'report.pdf'))
    await settle()
    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 2.0 KB')

    older.resolve(listingOf('notes.md'))
    await settle()
    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 2.0 KB')
    expect(component.find('[data-testid="ws-row-report.pdf"]').exists()).toBe(true)
    component.unmount()
  })

  it('keeps the last good tree when a background refresh fails', async () => {
    let seen = 0
    registerEndpoint('/api/agents/26/workspace-tree', () => {
      seen += 1
      if (seen > 1) throw createError({ statusCode: 500, statusMessage: 'walk failed' })
      return listingOf('notes.md')
    })

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 26 } })
    await settle()
    expect(component.find('[data-testid="ws-row-notes.md"]').exists()).toBe(true)

    vi.advanceTimersByTime(REFRESH_MS)
    await settle()
    // ofetch retries a GET once on a 500, so the poll costs two requests here, not one.
    expect(seen).toBeGreaterThanOrEqual(2)
    expect(component.find('[data-testid="ws-row-notes.md"]').exists()).toBe(true)
    expect(component.find('[data-testid="workspace-total"]').text()).toBe('Total 1.0 KB')
    expect(component.text()).not.toContain('walk failed')
    component.unmount()
  })

  it('does not flash the loading marker on a background refresh', async () => {
    registerEndpoint('/api/agents/25/workspace-tree', () => listingOf('notes.md'))

    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 25 } })
    await settle()

    vi.advanceTimersByTime(REFRESH_MS)
    expect(component.text()).not.toContain('Loading…')
    await settle()
    expect(component.text()).not.toContain('Loading…')
    component.unmount()
  })

  it('makes no refresh while the pointer is over the list, and catches up when it leaves', async () => {
    let calls = 0
    registerEndpoint('/api/agents/62/workspace-tree', () => {
      calls += 1
      return calls === 1 ? listingOf('notes.md') : listingOf('notes.md', 'report.pdf')
    })
    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 62 } })
    await settle()
    const list = component.find('[data-testid="workspace-scroll"]')

    await list.trigger('pointerenter')
    vi.advanceTimersByTime(REFRESH_MS * 3)
    await settle()
    expect(calls).toBe(1)
    expect(component.find('[data-testid="ws-row-report.pdf"]').exists()).toBe(false)

    await list.trigger('pointerleave')
    await settle()
    expect(calls).toBe(2)
    expect(component.find('[data-testid="ws-row-report.pdf"]').exists()).toBe(true)
    component.unmount()
  })

  it('pauses while keyboard focus is in the list, also as focus moves between its rows, and resumes when it leaves', async () => {
    let calls = 0
    registerEndpoint('/api/agents/63/workspace-tree', () => {
      calls += 1
      return listingOf('notes.md', 'report.pdf')
    })
    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 63 } })
    await settle()
    const list = component.find('[data-testid="workspace-scroll"]')
    const otherRowLink = component.find('[data-testid="ws-download-report.pdf"]').element

    await list.trigger('focusin')
    await list.trigger('focusout', { relatedTarget: otherRowLink })
    vi.advanceTimersByTime(REFRESH_MS * 2)
    await settle()
    expect(calls).toBe(1)

    await list.trigger('focusout', { relatedTarget: null })
    await settle()
    expect(calls).toBe(2)
    component.unmount()
  })

  it('drops a refresh that was already on its way when the pointer arrived', async () => {
    const inFlight = deferred()
    let seen = 0
    registerEndpoint('/api/agents/64/workspace-tree', () => {
      seen += 1
      if (seen === 1) return listingOf('notes.md')
      return seen === 2 ? inFlight.promise : listingOf('notes.md', 'report.pdf', 'later.txt')
    })
    const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 64 } })
    await settle()
    vi.advanceTimersByTime(REFRESH_MS)
    await settle()
    expect(seen).toBe(2)

    const list = component.find('[data-testid="workspace-scroll"]')
    await list.trigger('pointerenter')
    inFlight.resolve(listingOf('notes.md', 'report.pdf'))
    await settle()
    expect(component.find('[data-testid="ws-row-report.pdf"]').exists()).toBe(false)

    await list.trigger('pointerleave')
    await settle()
    expect(seen).toBe(3)
    expect(component.find('[data-testid="ws-row-later.txt"]').exists()).toBe(true)
    component.unmount()
  })
})
