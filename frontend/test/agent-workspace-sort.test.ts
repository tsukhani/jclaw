import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, type VueWrapper } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import AgentWorkspaceManager from '~/components/agents/AgentWorkspaceManager.vue'
import type { WorkspaceEntry, WorkspaceListing } from '~/types/api'
import { formatDateTime } from '~/utils/schedule'

/**
 * The Name, Modified and Size headers sort the entries inside each folder, folders above files,
 * and say which column is sorted which way.
 */
const T1 = Date.UTC(2026, 10, 1, 9, 0, 0)
const T2 = Date.UTC(2026, 10, 2, 9, 0, 0)
const T3 = Date.UTC(2026, 10, 3, 9, 0, 0)
const T4 = Date.UTC(2026, 10, 4, 9, 0, 0)
const T5 = Date.UTC(2026, 10, 5, 9, 0, 0)

function file(path: string, size: number, modified: number): WorkspaceEntry {
  return { path, name: path.split('/').pop()!, kind: 'file', size, modified, protected: false, children: null }
}

function dir(path: string, modified: number, children: WorkspaceEntry[]): WorkspaceEntry {
  const size = children.reduce((n, c) => n + c.size, 0)
  return { path, name: path.split('/').pop()!, kind: 'dir', size, modified, protected: false, children }
}

// In the server's order: folders first, each group A to Z. a.txt and c.txt tie on size.
const LISTING: WorkspaceListing = {
  total: 300,
  entries: [
    dir('alpha', T2, [file('alpha/big.bin', 25, T1), file('alpha/small.txt', 5, T2)]),
    dir('beta', T5, [file('beta/huge.bin', 100, T5)]),
    file('a.txt', 50, T3),
    file('b.txt', 70, T1),
    file('c.txt', 50, T4),
  ],
}

async function settle() {
  for (let i = 0; i < 3; i++) await flushPromises()
}

async function mount(): Promise<VueWrapper> {
  const component = await mountSuspended(AgentWorkspaceManager, { props: { agentId: 61 } })
  await settle()
  return component
}

async function sortBy(component: VueWrapper, key: 'name' | 'modified' | 'size') {
  await component.find(`[data-testid="workspace-sort-${key}"]`).trigger('click')
  await settle()
}

function rowPaths(component: VueWrapper) {
  return component.findAll('[data-testid^="ws-row-"]').map(r => r.attributes('data-testid')!.replace('ws-row-', ''))
}

function ariaSorts(component: VueWrapper) {
  return component.findAll('[role="columnheader"][aria-sort]').map(h => `${h.text()}:${h.attributes('aria-sort')}`)
}

describe('AgentWorkspaceManager columns and sorting', () => {
  beforeEach(() => {
    clearNuxtData()
    registerEndpoint('/api/agents/61/workspace-tree', () => LISTING)
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it('lists the server order as Name A to Z until a header is clicked', async () => {
    const component = await mount()

    expect(rowPaths(component)).toEqual(['alpha', 'beta', 'a.txt', 'b.txt', 'c.txt'])
    expect(ariaSorts(component)).toEqual(['Name ↑:ascending', 'Modified:none', 'Size:none'])
  })

  it('reads as a table: a header row of four columns over one row per entry', async () => {
    const component = await mount()

    const table = component.find('[role="table"]')
    expect(table.attributes('aria-label')).toBe('Workspace files')
    expect(table.findAll('[role="row"]')).toHaveLength(6)
    expect(table.findAll('[role="columnheader"]').map(h => h.text())).toEqual(['Name ↑', 'Modified', 'Size', 'Actions'])
  })

  it('shows each entry\'s last change and size in its own column', async () => {
    const component = await mount()

    expect(component.find('[data-testid="ws-modified-a.txt"]').text()).toBe(formatDateTime(new Date(T3).toISOString()))
    expect(component.find('[data-testid="ws-modified-beta"]').text()).toBe(formatDateTime(new Date(T5).toISOString()))
    expect(component.find('[data-testid="ws-size-a.txt"]').text()).toBe('50 B')
    expect(component.find('[data-testid="ws-size-alpha"]').text()).toBe('30 B')
  })

  it('sorts by Size largest first on the first click, folders still above files, and smallest first on the second', async () => {
    const component = await mount()

    await sortBy(component, 'size')
    expect(rowPaths(component)).toEqual(['beta', 'alpha', 'b.txt', 'a.txt', 'c.txt'])
    expect(ariaSorts(component)).toEqual(['Name:none', 'Modified:none', 'Size ↓:descending'])

    await sortBy(component, 'size')
    expect(rowPaths(component)).toEqual(['alpha', 'beta', 'a.txt', 'c.txt', 'b.txt'])
    expect(ariaSorts(component)).toEqual(['Name:none', 'Modified:none', 'Size ↑:ascending'])
  })

  it('sorts by Modified newest first on the first click', async () => {
    const component = await mount()

    await sortBy(component, 'modified')

    expect(rowPaths(component)).toEqual(['beta', 'alpha', 'c.txt', 'a.txt', 'b.txt'])
    expect(ariaSorts(component)).toEqual(['Name:none', 'Modified ↓:descending', 'Size:none'])
  })

  it('sorts Name Z to A on a second click, folders still first', async () => {
    const component = await mount()

    await sortBy(component, 'name')

    expect(rowPaths(component)).toEqual(['beta', 'alpha', 'c.txt', 'b.txt', 'a.txt'])
    expect(ariaSorts(component)).toEqual(['Name ↓:descending', 'Modified:none', 'Size:none'])
  })

  it('sorts the entries inside an open folder by the same column', async () => {
    const component = await mount()
    await component.find('[data-testid="ws-row-alpha"] button').trigger('click')
    await settle()

    await sortBy(component, 'size')
    expect(rowPaths(component)).toEqual(['beta', 'alpha', 'alpha/big.bin', 'alpha/small.txt', 'b.txt', 'a.txt', 'c.txt'])

    await sortBy(component, 'modified')
    expect(rowPaths(component)).toEqual(['beta', 'alpha', 'alpha/small.txt', 'alpha/big.bin', 'c.txt', 'a.txt', 'b.txt'])
  })

  it('keeps the chosen sort while a filter is typed', async () => {
    // Only the debounce is faked: flushPromises schedules itself on setTimeout too.
    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const component = await mount()
    await sortBy(component, 'size')

    await component.find('[data-testid="workspace-filter"]').setValue('.txt')
    vi.advanceTimersByTime(200)
    await settle()

    expect(rowPaths(component)).toEqual(['alpha', 'alpha/small.txt', 'b.txt', 'a.txt', 'c.txt'])
  })
})
