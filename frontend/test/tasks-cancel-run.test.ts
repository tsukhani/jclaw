import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Tasks from '~/pages/tasks.vue'

/**
 * Tasks page — "Cancel the running fire" action visibility (JCLAW-414).
 *
 * <p>Regression guard: a recurring task mid-fire sits in the RUNNING state,
 * which isLive() deliberately excludes (pause only applies to PENDING/ACTIVE).
 * The cancel-run button must therefore gate on runningRunId, NOT isLive —
 * otherwise it vanishes exactly when there is a run to cancel, leaving only
 * Delete in the Actions column. (Broke when Task gained a RUNNING status.)
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
    type: 'CRON',
    status: 'ACTIVE',
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
    pendingCount: 0, runningCount: rows.filter(r => r.runningRunId != null).length, failedCount: 0,
  }))
  registerEndpoint('/api/task-runs/recent', () => [])
  registerEndpoint('/api/config/tasks.retentionDays', () => ({ value: null }))
  for (const r of rows) {
    registerEndpoint(`/api/tasks/${r.id}/runs`, () => [])
  }
}

describe('Tasks page — cancel-running-fire action', () => {
  beforeEach(() => clearNuxtData())

  it('shows the cancel-run button for a recurring task in RUNNING state with a run in flight', async () => {
    registerTaskMounts([task({ id: 1, name: 'hourly-tracker', status: 'RUNNING', runningRunId: 99 })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    // The in-flight run is cancellable even though the task is RUNNING (not ACTIVE).
    expect(component.find('button[aria-label="Cancel the running fire of hourly-tracker"]').exists()).toBe(true)
    // Run-now is hidden while a run is in flight (you can't fire on top of a live run).
    expect(component.find('button[aria-label="Run hourly-tracker now"]').exists()).toBe(false)
  })

  it('hides the cancel-run button for an idle ACTIVE recurring task and offers Run now instead', async () => {
    registerTaskMounts([task({ id: 2, name: 'daily-brief', status: 'ACTIVE', runningRunId: null })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    expect(component.find('button[aria-label="Cancel the running fire of daily-brief"]').exists()).toBe(false)
    expect(component.find('button[aria-label="Run daily-brief now"]').exists()).toBe(true)
  })

  it('POSTs to the run-cancel endpoint when the button is clicked', async () => {
    let cancelledRunId: number | null = null
    registerTaskMounts([task({ id: 3, name: 'weekly-hunt', status: 'RUNNING', runningRunId: 42 })])
    registerEndpoint('/api/task-runs/42/cancel', () => {
      cancelledRunId = 42
      return { id: 42, status: 'CANCELLED' }
    })
    const component = await mountSuspended(Tasks)
    await flushPromises()

    await component.find('button[aria-label="Cancel the running fire of weekly-hunt"]').trigger('click')
    await flushPromises()

    expect(cancelledRunId).toBe(42)
  })
})
