import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { useConfirm } from '~/composables/useConfirm'
import Memory from '~/pages/memory.vue'

// JCLAW-40: agent memory admin page — list, inline importance edit, delete.

const AGENT = { id: 1, name: 'main', enabled: true, isMain: true, modelProvider: 'openrouter', modelId: 'gpt-4.1' }

function mem(overrides: Record<string, unknown> = {}) {
  return {
    id: '10',
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

// GET endpoints are stable across tests; the row PUT/DELETE endpoints are
// registered per-test (the 2-arg shorthand only matches GET, and the same path
// can't carry two methods at once in the test router).
registerEndpoint('/api/agents', () => [AGENT])
registerEndpoint('/api/agents/1/memories', () => memoriesResponse)

beforeEach(() => {
  // useFetch('/api/agents') caches by URL across mounts; clear so each test re-fetches.
  clearNuxtData()
  memoriesResponse = []
  putBody = null
  deleted = false
})

describe('memory admin page (JCLAW-40)', () => {
  it('shows the empty state when an agent has no memories', async () => {
    const c = await mountSuspended(Memory)
    await flushPromises()
    expect(c.find('[data-testid="memory-empty"]').exists()).toBe(true)
  })

  it('renders memories with category and importance', async () => {
    memoriesResponse = [mem(), mem({ id: '11', text: 'Operator is the sole admin', category: 'core', importance: 0.9 })]
    const c = await mountSuspended(Memory)
    await flushPromises()
    const text = c.text()
    expect(text).toContain('dark mode')
    expect(text).toContain('preference')
    expect(text).toContain('core')
    expect(c.findAll('[data-testid="memory-row"]')).toHaveLength(2)
  })

  it('PUTs the clamped importance when the input changes', async () => {
    memoriesResponse = [mem()]
    registerEndpoint('/api/agents/1/memories/10', {
      method: 'PUT',
      handler: async (event) => {
        const { readBody } = await import('h3')
        putBody = await readBody(event) as Record<string, unknown>
        return { id: '10', text: 'The user prefers dark mode', category: 'preference', importance: putBody.importance, createdAt: null }
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
    registerEndpoint('/api/agents/1/memories/10', {
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
