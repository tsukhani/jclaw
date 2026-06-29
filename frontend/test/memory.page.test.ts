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
    ...overrides,
  }
}

let memoriesResponse: unknown[] = []
let putBody: Record<string, unknown> | null = null
let deleted = false

// GET is stable across tests (the page fetches /api/memories?...; the path
// matches regardless of query string). PUT/DELETE on the row are registered
// per-test (the 2-arg shorthand only matches GET).
registerEndpoint('/api/memories', () => memoriesResponse)

beforeEach(() => {
  // useFetch caches by URL across mounts; clear so each test re-fetches.
  clearNuxtData()
  memoriesResponse = []
  putBody = null
  deleted = false
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

  it('deletes a memory after the operator confirms', async () => {
    memoriesResponse = [mem()]
    registerEndpoint('/api/memories/10', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return { status: 'deleted' }
      },
    })
    const c = await mountSuspended(Memory)
    await flushPromises()
    expect(c.findAll('[data-testid="memory-row"]')).toHaveLength(1)

    await c.find('[data-testid="delete-memory"]').trigger('click')
    await flushPromises() // let remove() reach `await confirm(...)` and open the dialog
    // Resolve the shared confirm dialog as "yes" (no ConfirmDialog mounted here).
    useConfirm()._resolve(true)
    await vi.waitFor(() => expect(deleted).toBe(true))
    await flushPromises()

    expect(c.findAll('[data-testid="memory-row"]')).toHaveLength(0)
  })
})
