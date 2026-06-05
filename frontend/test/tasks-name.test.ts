import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Tasks from '~/pages/tasks.vue'

/**
 * JCLAW-426 — task `name` inline editor on the Tasks page.
 *
 * <p>Expanding a row reveals a Name section; Edit → input → Save PATCHes
 * {@code /api/tasks/:id} with {@code {name}}. Unlike description, name is
 * required: an empty value is blocked client-side (no PATCH) with an inline
 * error, while the backend re-validates and 400s independently.
 */

interface TaskFixture {
  id: number
  name: string
  type: string
  status: string
  paused: boolean
  description?: string | null
  agentName: string | null
  nextRunAt: string | null
  retryCount: number
  maxRetries: number
  runningRunId: number | null
  delivery?: string | null
}

function task(over: Partial<TaskFixture> & { id: number, name: string }): TaskFixture {
  return {
    type: 'SCHEDULED',
    status: 'PENDING',
    paused: false,
    description: 'do the thing',
    agentName: 'main',
    nextRunAt: null,
    retryCount: 0,
    maxRetries: 3,
    runningRunId: null,
    delivery: null,
    ...over,
  }
}

function registerTaskMounts(opts?: {
  tasks?: TaskFixture[]
  capturePatch?: (id: string, body: { name?: string }) => void
}) {
  const rows = opts?.tasks ?? [task({ id: 1, name: 'old name' })]
  registerEndpoint('/api/tasks', () => rows)
  registerEndpoint('/api/tasks/stats', () => ({
    runsToday: 0, successRate: null, avgDurationMs: null,
    pendingCount: rows.length, runningCount: 0, failedCount: 0,
  }))
  registerEndpoint('/api/task-runs/recent', () => [])
  registerEndpoint('/api/config/tasks.retentionDays', () => ({ value: null }))
  for (const r of rows) {
    registerEndpoint(`/api/tasks/${r.id}/runs`, () => [])
    registerEndpoint(`/api/tasks/${r.id}`, {
      method: 'PATCH',
      handler: async (event) => {
        const body = await readBody(event) as { name?: string }
        opts?.capturePatch?.(String(r.id), body)
        return { id: r.id }
      },
    })
  }
}

describe('Tasks page — JCLAW-426 inline name editor → PATCH', () => {
  beforeEach(() => clearNuxtData())

  it('PATCHes /api/tasks/:id with {name} when the operator renames the task', async () => {
    const captured: Array<{ id: string, body: { name?: string } }> = []
    registerTaskMounts({ capturePatch: (id, body) => captured.push({ id, body }) })
    const component = await mountSuspended(Tasks)
    await flushPromises()

    // Expand the row so the Name editor section renders.
    const expand = component.find('button[aria-label="Toggle details for old name"]')
    expect(expand.exists()).toBe(true)
    await expand.trigger('click')
    await flushPromises()

    // The Name section's Edit button reveals the input.
    const editBtn = component.findAll('button').find(b => b.text() === 'Edit'
      && b.element.closest('section')?.textContent?.includes('Name'))
    expect(editBtn).toBeTruthy()
    await editBtn!.trigger('click')
    await flushPromises()

    const input = component.find('input[aria-label="Task name"]')
    expect(input.exists()).toBe(true)
    await input.setValue('new name')

    const saveBtn = component.findAll('button').find(b => b.text().includes('Save')
      && b.element.closest('section')?.textContent?.includes('Name'))
    expect(saveBtn).toBeTruthy()
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(captured.length).toBe(1)
    expect(captured[0]!.id).toBe('1')
    expect(captured[0]!.body).toEqual({ name: 'new name' })
  })

  it('blocks an empty name client-side (no PATCH) and shows an error', async () => {
    const captured: Array<{ id: string, body: { name?: string } }> = []
    registerTaskMounts({ capturePatch: (id, body) => captured.push({ id, body }) })
    const component = await mountSuspended(Tasks)
    await flushPromises()

    const expand = component.find('button[aria-label="Toggle details for old name"]')
    await expand.trigger('click')
    await flushPromises()

    const editBtn = component.findAll('button').find(b => b.text() === 'Edit'
      && b.element.closest('section')?.textContent?.includes('Name'))
    await editBtn!.trigger('click')
    await flushPromises()

    // Whitespace-only collapses to empty after trim → blocked before any PATCH.
    await component.find('input[aria-label="Task name"]').setValue('   ')
    const saveBtn = component.findAll('button').find(b => b.text().includes('Save')
      && b.element.closest('section')?.textContent?.includes('Name'))
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(captured.length).toBe(0)
    expect(component.text()).toContain('Name cannot be empty')
  })
})
