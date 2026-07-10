import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { useConfirm } from '~/composables/useConfirm'
import Memory from '~/pages/memory.vue'

// JCLAW-40: cross-agent memory admin page — filter bar, agent column, inline
// importance edit, delete.

function mem(overrides: Record<string, unknown> = {}) {
  return {
    id: '10',
    agentName: 'main',
    text: 'The user prefers dark mode',
    category: 'preference',
    importance: 0.7,
    createdAt: '2026-06-29T00:00:00Z',
    supersededAt: null,
    supersededById: null,
    ...overrides,
  }
}

// Importance is unique per fixture row, so the ordered importance-input values
// are a precise fingerprint of the rendered row order — handy for sort assertions.
function importances(c: { findAll: (s: string) => { element: Element }[] }): number[] {
  return c.findAll('[data-testid="importance-input"]').map(i => Number((i.element as HTMLInputElement).value))
}

let memoriesResponse: unknown[] = []
let putBody: Record<string, unknown> | null = null
let bulkDeleteBody: Record<string, unknown> | null = null

// GET is stable across tests (the page fetches /api/memories?...; the path
// matches regardless of query string). PUT on the row is registered
// per-test (the 2-arg shorthand only matches GET). The bulk DELETE
// endpoint captures its body for the selection/filter assertions.
registerEndpoint('/api/memories', () => memoriesResponse)
registerEndpoint('/api/memories', {
  method: 'DELETE',
  handler: async (event) => {
    const { readBody } = await import('h3')
    bulkDeleteBody = await readBody(event) as Record<string, unknown>
    return { deleted: 1 }
  },
})

beforeEach(() => {
  // useFetch caches by URL across mounts; clear so each test re-fetches.
  clearNuxtData()
  memoriesResponse = []
  putBody = null
  bulkDeleteBody = null
})

describe('memories admin page (JCLAW-40)', () => {
  it('shows the empty state when there are no memories', async () => {
    const c = await mountSuspended(Memory)
    await flushPromises()
    expect(c.find('[data-testid="memory-empty"]').exists()).toBe(true)
  })

  it('renders memories across agents with agent, category, importance, and created date', async () => {
    memoriesResponse = [
      mem(),
      mem({ id: '11', agentName: 'support', text: 'Operator is the sole admin', category: 'core', importance: 0.9 }),
    ]
    const c = await mountSuspended(Memory)
    await flushPromises()
    const text = c.text()
    expect(text).toContain('dark mode')
    expect(text).toContain('main') // agent column
    expect(text).toContain('support') // second agent
    expect(text).toContain('preference')
    expect(text).toContain('core')
    expect(text).toContain('2026') // created date column
    expect(c.findAll('[data-testid="memory-row"]')).toHaveLength(2)
  })

  it('PUTs the clamped importance when the input changes', async () => {
    memoriesResponse = [mem()]
    registerEndpoint('/api/memories/10', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: '10', agentName: 'main', text: 'The user prefers dark mode', category: 'preference', importance: putBody.importance, createdAt: null }
      },
    })
    const c = await mountSuspended(Memory)
    await flushPromises()
    const input = c.find('[data-testid="importance-input"]')
    await input.setValue('0.95')
    await input.trigger('change')
    await vi.waitFor(() => expect(putBody).not.toBeNull())
    expect(putBody).toEqual({ importance: 0.95 })
  })

  it('renders superseded rows dimmed with a badge, active rows without (JCLAW-557)', async () => {
    memoriesResponse = [
      mem(),
      mem({
        id: '11',
        text: 'The user lives in Berlin',
        supersededAt: '2026-07-03T00:00:00Z',
        supersededById: '12',
      }),
    ]
    const c = await mountSuspended(Memory)
    await flushPromises()

    const rows = c.findAll('[data-testid="memory-row"]')
    expect(rows).toHaveLength(2)
    expect(rows[0]!.classes()).not.toContain('opacity-50')
    expect(rows[0]!.find('[data-testid="superseded-badge"]').exists()).toBe(false)
    expect(rows[1]!.classes()).toContain('opacity-50')
    const badge = rows[1]!.find('[data-testid="superseded-badge"]')
    expect(badge.exists()).toBe(true)
    expect(badge.text()).toBe('superseded')
    expect(badge.attributes('title')).toContain('by memory #12')
  })

  it('deletes the selected memories after the operator confirms (no per-row trash)', async () => {
    memoriesResponse = [mem(), mem({ id: '11', text: 'Second memory' })]
    const c = await mountSuspended(Memory)
    await flushPromises()
    expect(c.findAll('[data-testid="memory-row"]')).toHaveLength(2)
    // The per-row trash icon is gone — deletion is selection-driven.
    expect(c.find('[data-testid="delete-memory"]').exists()).toBe(false)

    // Delete is disabled until a row is selected.
    expect(c.find('[data-testid="delete-selected"]').attributes('disabled')).toBeDefined()
    await c.findAll('[data-testid="select-memory"]')[1]!.setValue(true)
    expect(c.find('[data-testid="delete-selected"]').attributes('disabled')).toBeUndefined()
    expect(c.find('[data-testid="delete-selected"]').text()).toContain('Delete 1')

    memoriesResponse = [mem()]
    await c.find('[data-testid="delete-selected"]').trigger('click')
    await flushPromises()
    useConfirm()._resolve(true)
    await vi.waitFor(() => expect(bulkDeleteBody).not.toBeNull())
    expect(bulkDeleteBody).toEqual({ ids: [11] })
    await flushPromises()
    expect(c.findAll('[data-testid="memory-row"]')).toHaveLength(1)
  })

  it('select-all drives the header checkbox and the Delete count', async () => {
    memoriesResponse = [mem(), mem({ id: '11' }), mem({ id: '12' })]
    const c = await mountSuspended(Memory)
    await flushPromises()
    await c.find('[data-testid="select-all"]').setValue(true)
    expect(c.find('[data-testid="delete-selected"]').text()).toContain('Delete 3')
  })

  it('Delete all sends the active filter set and requires the typed confirm', async () => {
    memoriesResponse = [mem()]
    const c = await mountSuspended(Memory)
    await flushPromises()

    memoriesResponse = []
    await c.find('[data-testid="delete-all"]').trigger('click')
    await flushPromises()
    useConfirm()._resolve(true)
    await vi.waitFor(() => expect(bulkDeleteBody).not.toBeNull())
    expect(bulkDeleteBody).toEqual({ filter: {} })
    await flushPromises()
    expect(c.find('[data-testid="memory-empty"]').exists()).toBe(true)
  })
})

describe('memories admin page — sortable columns', () => {
  // Three rows whose server order, importance order, and agent order are all
  // distinct, so each sort produces a recognisably different arrangement.
  function threeRows() {
    memoriesResponse = [
      mem({ id: '1', agentName: 'zeta', importance: 0.5 }),
      mem({ id: '2', agentName: 'alpha', importance: 0.7 }),
      mem({ id: '3', agentName: 'main', importance: 0.9 }),
    ]
  }

  it('preserves the server order until a header is clicked', async () => {
    threeRows()
    const c = await mountSuspended(Memory)
    await flushPromises()
    expect(importances(c)).toEqual([0.5, 0.7, 0.9])
  })

  it('sorts by importance descending on first click, ascending on the second', async () => {
    threeRows()
    const c = await mountSuspended(Memory)
    await flushPromises()

    await c.find('[data-testid="sort-importance"]').trigger('click')
    expect(importances(c)).toEqual([0.9, 0.7, 0.5]) // numeric default = descending

    await c.find('[data-testid="sort-importance"]').trigger('click')
    expect(importances(c)).toEqual([0.5, 0.7, 0.9]) // same column flips direction
  })

  it('sorts by agent ascending, and switching columns resets to that column default', async () => {
    threeRows()
    const c = await mountSuspended(Memory)
    await flushPromises()

    await c.find('[data-testid="sort-agent"]').trigger('click')
    expect(importances(c)).toEqual([0.7, 0.9, 0.5]) // agent asc: alpha, main, zeta

    // Switching to importance uses ITS default (desc), not the carried-over asc.
    await c.find('[data-testid="sort-importance"]').trigger('click')
    expect(importances(c)).toEqual([0.9, 0.7, 0.5])
  })

  it('marks the active column with aria-sort reflecting the direction', async () => {
    memoriesResponse = [mem()]
    const c = await mountSuspended(Memory)
    await flushPromises()

    // aria-sort lives on the <th> (columnheader) that owns the sort state; the
    // button inside is only the actuator. Read the header via the button's testid.
    const createdBtn = c.find('[data-testid="sort-created"]')
    const createdSort = () => createdBtn.element.closest('th')?.getAttribute('aria-sort')
    expect(createdSort()).toBe('none')
    await createdBtn.trigger('click')
    expect(createdSort()).toBe('descending') // created default = desc
    await createdBtn.trigger('click')
    expect(createdSort()).toBe('ascending')
    // A different column then reads 'none' on the created header.
    await c.find('[data-testid="sort-agent"]').trigger('click')
    expect(createdSort()).toBe('none')
  })
})
