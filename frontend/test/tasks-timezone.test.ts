import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { readBody } from 'h3'
import { clearNuxtData } from '#app'
import Tasks from '~/pages/tasks.vue'

/**
 * JCLAW-1106 — per-task timezone override on the Tasks page.
 *
 * The field was persisted and returned by the API long before it was reachable
 * from the UI, so these cover the two halves that were missing: showing which
 * zone is in force (and whether it is the task's own or inherited), and setting
 * or clearing it. The type guard matters as much as the editor — INTERVAL and
 * IMMEDIATE ignore the field, so offering the control there would promise an
 * effect that does not exist.
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
  timezone?: string | null
  effectiveTimezone?: string
}

function task(over: Partial<TaskFixture> & { id: number, name: string }): TaskFixture {
  return {
    type: 'CRON',
    status: 'PENDING',
    paused: false,
    description: 'do the thing',
    agentName: 'main',
    nextRunAt: null,
    retryCount: 0,
    maxRetries: 3,
    runningRunId: null,
    timezone: null,
    effectiveTimezone: 'Asia/Kuala_Lumpur',
    ...over,
  }
}

function mountWith(rows: TaskFixture[], capturePatch?: (id: string, body: Record<string, unknown>) => void) {
  registerEndpoint('/api/tasks', () => rows)
  registerEndpoint('/api/tasks/stats', () => ({
    runsToday: 0, successRate: null, avgDurationMs: null,
    pendingCount: rows.length, runningCount: 0, failedCount: 0,
  }))
  registerEndpoint('/api/task-runs/recent', () => [])
  registerEndpoint('/api/timezones', () => ({
    timezones: ['America/New_York', 'Asia/Kuala_Lumpur', 'Asia/Tokyo'],
    default: 'Asia/Kuala_Lumpur',
    appDefault: 'Asia/Kuala_Lumpur',
  }))
  for (const r of rows) {
    registerEndpoint(`/api/tasks/${r.id}/runs`, () => [])
    registerEndpoint(`/api/tasks/${r.id}`, {
      method: 'PATCH',
      handler: async (event) => {
        capturePatch?.(String(r.id), await readBody(event) as Record<string, unknown>)
        return { id: r.id }
      },
    })
  }
}

async function expandAndOpenTimezoneEditor(name: string) {
  const component = await mountSuspended(Tasks)
  await flushPromises()
  const expand = component.find(`button[aria-label="Toggle details for ${name}"]`)
  await expand.trigger('click')
  await flushPromises()
  const editBtn = component.findAll('button').find(b => b.text() === 'Edit'
    && b.element.closest('section')?.textContent?.includes('Timezone'))
  return { component, editBtn }
}

describe('Tasks page — per-task timezone', () => {
  beforeEach(() => clearNuxtData())

  it('marks an inherited zone as inherited and an override as its own', async () => {
    mountWith([
      task({ id: 1, name: 'inherits', timezone: null, effectiveTimezone: 'Asia/Kuala_Lumpur' }),
      task({ id: 2, name: 'overrides', timezone: 'Asia/Tokyo', effectiveTimezone: 'Asia/Tokyo' }),
    ])
    const component = await mountSuspended(Tasks)
    await flushPromises()
    for (const n of ['inherits', 'overrides']) {
      await component.find(`button[aria-label="Toggle details for ${n}"]`).trigger('click')
      await flushPromises()
    }
    const text = component.text()
    // Without the suffix the two are indistinguishable on screen.
    expect(text).toContain('Asia/Kuala_Lumpur (inherited)')
    expect(text).toContain('Asia/Tokyo')
    expect(text).not.toContain('Asia/Tokyo (inherited)')
  })

  it('PATCHes the chosen zone', async () => {
    const captured: Array<{ id: string, body: Record<string, unknown> }> = []
    mountWith([task({ id: 1, name: 'cron task' })], (id, body) => captured.push({ id, body }))
    const { component, editBtn } = await expandAndOpenTimezoneEditor('cron task')
    expect(editBtn).toBeTruthy()
    await editBtn!.trigger('click')
    await flushPromises()

    const select = component.find('select[aria-label="Task timezone"]')
    expect(select.exists()).toBe(true)
    await select.setValue('America/New_York')
    const saveBtn = component.findAll('button').find(b => b.text().includes('Save')
      && b.element.closest('section')?.textContent?.includes('Timezone'))
    await saveBtn!.trigger('click')
    await flushPromises()

    expect(captured).toEqual([{ id: '1', body: { timezone: 'America/New_York' } }])
  })

  it('sends null — not an empty string — when the override is cleared', async () => {
    const captured: Array<{ id: string, body: Record<string, unknown> }> = []
    mountWith([task({ id: 1, name: 'cron task', timezone: 'Asia/Tokyo', effectiveTimezone: 'Asia/Tokyo' })],
      (id, body) => captured.push({ id, body }))
    const { component, editBtn } = await expandAndOpenTimezoneEditor('cron task')
    await editBtn!.trigger('click')
    await flushPromises()

    await component.find('select[aria-label="Task timezone"]').setValue('')
    const saveBtn = component.findAll('button').find(b => b.text().includes('Save')
      && b.element.closest('section')?.textContent?.includes('Timezone'))
    await saveBtn!.trigger('click')
    await flushPromises()

    // '' would be read as a value; only an explicit null clears the override.
    expect(captured).toEqual([{ id: '1', body: { timezone: null } }])
  })

  it('is not offered for INTERVAL, which ignores the field', async () => {
    mountWith([task({ id: 1, name: 'interval task', type: 'INTERVAL' })])
    const { editBtn } = await expandAndOpenTimezoneEditor('interval task')
    expect(editBtn).toBeUndefined()
  })
})
