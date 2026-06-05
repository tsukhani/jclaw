import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Tasks from '~/pages/tasks.vue'

/**
 * Tasks page — independent multi-row expand/collapse.
 *
 * <p>Expanding a task must NOT collapse any other open task: the page tracks
 * a Set of open ids, so several detail panels can be open at once and each
 * collapses independently.
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

function registerTaskMounts(rows: TaskFixture[]) {
  registerEndpoint('/api/tasks', () => rows)
  registerEndpoint('/api/tasks/stats', () => ({
    runsToday: 0, successRate: null, avgDurationMs: null,
    pendingCount: rows.length, runningCount: 0, failedCount: 0,
  }))
  registerEndpoint('/api/task-runs/recent', () => [])
  registerEndpoint('/api/config/tasks.retentionDays', () => ({ value: null }))
  for (const r of rows) {
    registerEndpoint(`/api/tasks/${r.id}/runs`, () => [])
  }
}

describe('Tasks page — independent multi-row expand', () => {
  beforeEach(() => clearNuxtData())

  it('keeps each row expanded independently; expanding one never collapses another', async () => {
    registerTaskMounts([task({ id: 1, name: 'alpha' }), task({ id: 2, name: 'beta' })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    const alpha = () => component.find('button[aria-label="Toggle details for alpha"]')
    const beta = () => component.find('button[aria-label="Toggle details for beta"]')

    // Both start collapsed.
    expect(alpha().attributes('aria-expanded')).toBe('false')
    expect(beta().attributes('aria-expanded')).toBe('false')

    // Expand alpha.
    await alpha().trigger('click')
    await flushPromises()
    expect(alpha().attributes('aria-expanded')).toBe('true')

    // Expand beta — alpha must stay open (the bug being fixed: it used to collapse).
    await beta().trigger('click')
    await flushPromises()
    expect(beta().attributes('aria-expanded')).toBe('true')
    expect(alpha().attributes('aria-expanded')).toBe('true')

    // Collapse alpha — beta stays open.
    await alpha().trigger('click')
    await flushPromises()
    expect(alpha().attributes('aria-expanded')).toBe('false')
    expect(beta().attributes('aria-expanded')).toBe('true')
  })
})
