import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { defineComponent, h } from 'vue'
import Tasks from '~/pages/tasks.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * Tasks page — "Reset stats" clears an open RUN HISTORY panel.
 *
 * <p>Reset deletes terminal run rows server-side, but the per-task runsByTask
 * cache that feeds an expanded RUN HISTORY panel still holds them. The handler
 * must re-pull runs for every loaded task so the deleted rows disappear —
 * refresh()/refreshStats() only cover the task list and KPI aggregates.
 *
 * <p>Mounts a sibling ConfirmDialog so the resetStats() confirm() renders.
 */

const TasksHarness = defineComponent({
  setup() {
    return () => h('div', [h(Tasks), h(ConfirmDialog)])
  },
})

describe('Tasks page — Reset stats refreshes open run history', () => {
  beforeEach(() => clearNuxtData())

  it('re-pulls run history after reset so the open panel drops the cleared rows', async () => {
    let runsCalls = 0
    let didReset = false
    const taskRow = {
      id: 1, name: 'hourly-tracker', type: 'CRON', status: 'ACTIVE', paused: false,
      description: 'x', agentName: 'main', nextRunAt: null,
      retryCount: 0, maxRetries: 3, runningRunId: null, delivery: null,
    }
    registerEndpoint('/api/tasks', () => [taskRow])
    registerEndpoint('/api/tasks/stats', () => ({
      runsToday: 0, successRate: null, avgDurationMs: null,
      pendingCount: 0, runningCount: 0, failedCount: 0,
    }))
    registerEndpoint('/api/task-runs/recent', () => [])
    registerEndpoint('/api/config/tasks.retentionDays', () => ({ value: null }))
    registerEndpoint('/api/tasks/1/runs', () => {
      runsCalls++
      return didReset
        ? []
        : [{
            id: 7, status: 'COMPLETED',
            startedAt: '2026-06-09T00:00:00Z', completedAt: '2026-06-09T00:00:30Z',
            durationMs: 30000, outputSummary: 'done', turn: 1,
          }]
    })
    registerEndpoint('/api/task-runs/reset', () => {
      didReset = true
      return { status: 'reset', deletedRuns: 1 }
    })

    const component = await mountSuspended(TasksHarness)
    await flushPromises()

    // Expand the task → run history loads exactly once.
    await component.find('button[aria-label="Toggle details for hourly-tracker"]').trigger('click')
    await flushPromises()
    expect(runsCalls).toBe(1)

    // Reset stats: toolbar button (icon-only, aria-label) opens the dialog, which
    // ConfirmDialog teleports to <body>. Its confirm button has text "Reset stats"
    // (the toolbar button is icon-only, so its textContent is empty — no clash).
    await component.find('button[aria-label="Reset stats"]').trigger('click')
    await flushPromises()
    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Reset stats')
    expect(confirmBtn).toBeTruthy()
    confirmBtn!.click()
    await flushPromises()
    await flushPromises()

    expect(didReset).toBe(true)
    // The fix: the open panel's history is re-pulled after the delete.
    expect(runsCalls).toBeGreaterThanOrEqual(2)
  })
})
