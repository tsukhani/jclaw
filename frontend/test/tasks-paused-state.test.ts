import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { defineComponent, h, nextTick } from 'vue'
import Tasks from '~/pages/tasks.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * Tasks page — paused is a visible state, not just an icon shape.
 *
 * <p>Task.paused is a flag orthogonal to Task.Status: a paused CRON task is
 * still ACTIVE server-side. Before this, the only signal that an operator had
 * suspended a schedule was the Pause glyph turning into a Play glyph, which
 * reads as "that action ran" rather than "this schedule is latched off" — the
 * row still said ACTIVE and the KPI strip still counted it under Active.
 */

interface TaskFixture {
  id: number
  name: string
  type: string
  status: string
  paused: boolean
  agentName: string | null
  nextRunAt: string | null
  retryCount: number
  maxRetries: number
  runningRunId: number | null
}

function task(over: Partial<TaskFixture> & { id: number, name: string }): TaskFixture {
  return {
    type: 'CRON',
    status: 'ACTIVE',
    paused: false,
    agentName: 'main',
    nextRunAt: null,
    retryCount: 0,
    maxRetries: 3,
    runningRunId: null,
    ...over,
  }
}

function registerTaskMounts(rows: TaskFixture[], pausedCount?: number) {
  registerEndpoint('/api/tasks', () => rows)
  // Recomputed per call, like the real endpoint — a frozen literal cannot tell
  // a stat card that never refetched from one that refetched and got the same
  // number, which is the bug the refresh test below exists to catch.
  registerEndpoint('/api/tasks/stats', () => ({
    runsToday: 0, successRate: null, avgDurationMs: null,
    pendingCount: 0, runningCount: 0,
    pausedCount: pausedCount ?? rows.filter(r => r.paused).length,
    activeCount: rows.filter(r => !r.paused).length, failedCount: 0,
    retentionDays: 30,
  }))
  registerEndpoint('/api/task-runs/recent', () => [])
  for (const r of rows) registerEndpoint(`/api/tasks/${r.id}/runs`, () => [])
}

/** The status pill is the only `span.font-mono` in a row (type/delivery are `td`s). */
function statusPills(component: { findAll: (s: string) => { text: () => string }[] }) {
  return component.findAll('span.font-mono').map(s => s.text())
}

describe('Tasks page — paused state', () => {
  beforeEach(() => clearNuxtData())

  it('renders PAUSED in the status column for a paused recurring task', async () => {
    registerTaskMounts([task({ id: 1, name: 'renu-tea-time', paused: true })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    expect(statusPills(component)).toContain('PAUSED')
    expect(statusPills(component)).not.toContain('ACTIVE')
  })

  it('leaves the status untouched for an unpaused task', async () => {
    registerTaskMounts([task({ id: 2, name: 'daily-brief' })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    expect(statusPills(component)).toContain('ACTIVE')
    expect(statusPills(component)).not.toContain('PAUSED')
  })

  /**
   * A terminal task can carry a stale paused flag (it is only cleared on
   * re-enable), and pause has no meaning there — the scheduler row is gone.
   */
  it('does not relabel a terminal task that carries a stale paused flag', async () => {
    registerTaskMounts([task({ id: 3, name: 'old-job', status: 'CANCELLED', paused: true })], 0)
    const component = await mountSuspended(Tasks)
    await flushPromises()

    expect(statusPills(component)).toContain('CANCELLED')
  })

  it('keeps one pause toggle in both states and resumes from it', async () => {
    let resumed = false
    registerTaskMounts([task({ id: 4, name: 'weekly-hunt', paused: true })])
    registerEndpoint('/api/tasks/4/resume', () => {
      resumed = true
      return { id: 4, paused: false }
    })
    const component = await mountSuspended(Tasks)
    await flushPromises()

    // Same control, same label, in both states — only aria-pressed and colour move.
    const toggle = component.find('button[aria-label="Pause schedule for weekly-hunt"]')
    expect(toggle.exists()).toBe(true)
    expect(toggle.attributes('aria-pressed')).toBe('true')

    await toggle.trigger('click')
    await flushPromises()
    expect(resumed).toBe(true)
  })

  it('marks the toggle unpressed when the task is running on schedule', async () => {
    registerTaskMounts([task({ id: 5, name: 'nightly-sync' })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    expect(component.find('button[aria-label="Pause schedule for nightly-sync"]').attributes('aria-pressed'))
      .toBe('false')
  })

  /**
   * TaskExecutionHandler skips a paused task's fire body, so run-now would
   * return 200 and do nothing. Disabled, not hidden: the column keeps its shape.
   */
  it('keeps run-once visible but disabled while paused', async () => {
    registerTaskMounts([task({ id: 6, name: 'payslip-process', paused: true })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    const run = component.find('button[aria-label="Run payslip-process now"]')
    expect(run.exists()).toBe(true)
    expect(run.attributes('disabled')).toBeDefined()
  })

  it('enables run-once when the task is not paused', async () => {
    registerTaskMounts([task({ id: 7, name: 'nas-db-backup-sync' })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    expect(component.find('button[aria-label="Run nas-db-backup-sync now"]').attributes('disabled'))
      .toBeUndefined()
  })

  it('shows the paused count in the KPI strip', async () => {
    registerTaskMounts([task({ id: 8, name: 'a', paused: true }), task({ id: 9, name: 'b', paused: true })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    const card = component.findAll('.bg-surface-elevated').find(c => c.text().startsWith('Paused'))
    expect(card?.text()).toContain('2')
  })

  /**
   * A one-shot is pausable too — the backend has always accepted PENDING, and
   * resume re-arms a fire the pause dropped. The control was gated on
   * isRecurring, so the only option on a one-shot row was the irreversible Cancel.
   */
  it('offers the pause toggle on a one-shot, alongside Cancel', async () => {
    registerTaskMounts([task({ id: 20, name: 'zz-oneshot', type: 'SCHEDULED', status: 'PENDING' })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    expect(component.find('button[aria-label="Pause schedule for zz-oneshot"]').exists()).toBe(true)
    // Cancel must survive: it used to chain off the toggle's v-else-if.
    expect(component.find('button[aria-label="Cancel zz-oneshot"]').exists()).toBe(true)
    // Run-once stays recurring-only — firing a one-shot early consumes it.
    expect(component.find('button[aria-label="Run zz-oneshot now"]').exists()).toBe(false)
  })

  it('pauses a one-shot through the toggle', async () => {
    let paused = false
    registerTaskMounts([task({ id: 21, name: 'zz-oneshot2', type: 'SCHEDULED', status: 'PENDING' })])
    registerEndpoint('/api/tasks/21/pause', () => {
      paused = true
      return { id: 21, paused: true }
    })
    const component = await mountSuspended(Tasks)
    await flushPromises()

    await component.find('button[aria-label="Pause schedule for zz-oneshot2"]').trigger('click')
    await flushPromises()
    expect(paused).toBe(true)
  })

  it('shows a paused one-shot as PAUSED, not PENDING', async () => {
    registerTaskMounts([task({ id: 22, name: 'zz-oneshot3', type: 'SCHEDULED', status: 'PENDING', paused: true })])
    const component = await mountSuspended(Tasks)
    await flushPromises()

    expect(statusPills(component)).toContain('PAUSED')
    expect(statusPills(component)).not.toContain('PENDING')
  })

  /**
   * Caught in live UAT, not by the tests above: the KPI strip refetches on task
   * *fire* lifecycle events, and pause/resume emits none. Without an explicit
   * refreshStats() the row flipped to PAUSED while the Paused and Active tiles
   * kept their pre-click numbers until something else happened to refetch.
   */
  it('refetches the KPI strip when a task is paused', async () => {
    const rows = [task({ id: 10, name: 'renu-tea-time' })]
    registerTaskMounts(rows)
    registerEndpoint('/api/tasks/10/pause', () => {
      rows[0]!.paused = true
      return { id: 10, paused: true }
    })
    const component = await mountSuspended(Tasks)
    await flushPromises()

    const paused = () => component.findAll('.bg-surface-elevated').find(c => c.text().startsWith('Paused'))?.text()
    const active = () => component.findAll('.bg-surface-elevated').find(c => c.text().startsWith('Active'))?.text()
    expect(paused()).toContain('0')
    expect(active()).toContain('1')

    await component.find('button[aria-label="Pause schedule for renu-tea-time"]').trigger('click')
    await flushPromises()
    await flushPromises()
    await nextTick()

    expect(paused()).toContain('1')
    expect(active()).toContain('0')
  })

  /**
   * The same gap applied to every other operator action that moves a task
   * between tiles. Counting stats fetches is the only way to see it: the tile
   * can hold the right number by accident when nothing refetched.
   */
  function countingMounts(rows: TaskFixture[], counter: { n: number }) {
    registerEndpoint('/api/tasks', () => rows)
    registerEndpoint('/api/task-runs/recent', () => [])
    for (const r of rows) registerEndpoint(`/api/tasks/${r.id}/runs`, () => [])
    registerEndpoint('/api/tasks/stats', () => {
      counter.n++
      return {
        runsToday: 0, successRate: null, avgDurationMs: null,
        pendingCount: 0, runningCount: 0, pausedCount: 0, activeCount: 0, failedCount: 0,
        retentionDays: 30,
      }
    })
  }

  it('refetches the KPI strip on cancel', async () => {
    const counter = { n: 0 }
    countingMounts([task({ id: 30, name: 'zz-probe', type: 'SCHEDULED', status: 'PENDING' })], counter)
    registerEndpoint('/api/tasks/30/cancel', () => ({ status: 'cancelled' }))
    const component = await mountSuspended(Tasks)
    await flushPromises()
    const before = counter.n

    await component.find('button[aria-label="Cancel zz-probe"]').trigger('click')
    await flushPromises()
    await nextTick()
    await flushPromises()

    expect(counter.n).toBeGreaterThan(before)
  })

  /** Delete goes through ConfirmDialog, so the harness mounts one alongside. */
  it('refetches the KPI strip on delete', async () => {
    const counter = { n: 0 }
    countingMounts([task({ id: 31, name: 'zz-probe2' })], counter)
    registerEndpoint('/api/tasks/31', () => ({ status: 'deleted' }))
    const Harness = defineComponent({ setup: () => () => h('div', [h(Tasks), h(ConfirmDialog)]) })
    const component = await mountSuspended(Harness)
    await flushPromises()
    const before = counter.n

    await component.find('button[aria-label="Delete zz-probe2"]').trigger('click')
    await flushPromises()
    const confirmBtn = Array.from(document.body.querySelectorAll<HTMLButtonElement>('button'))
      .find(b => (b.textContent ?? '').trim() === 'Delete')
    expect(confirmBtn).toBeTruthy()
    confirmBtn!.click()
    await flushPromises()
    await nextTick()
    await flushPromises()

    expect(counter.n).toBeGreaterThan(before)
  })
})

describe('Tasks page — a failed row action (JCLAW-1221)', () => {
  it('says why a pause did not take', async () => {
    registerTaskMounts([task({ id: 31, name: 'zz-refused', type: 'SCHEDULED', status: 'PENDING' })])
    const off = registerEndpoint('/api/tasks/31/pause', {
      method: 'POST',
      handler: async (event) => {
        const { setResponseStatus } = await import('h3')
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    try {
      const component = await mountSuspended(Tasks)
      await flushPromises()
      await component.find('button[aria-label="Pause schedule for zz-refused"]').trigger('click')
      await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
      expect(component.find('[data-testid="api-error"]').text()).toContain('/api/tasks/31/pause')
    }
    finally {
      off()
    }
  })
})
