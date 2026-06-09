import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import type { Task } from '~/types/api'
import ScheduleCalendar from '~/components/ScheduleCalendar.vue'

// A one-shot firing ~2h from now (so it lands on a visible day in the current
// month grid) plus a daily CRON (exercises the cron expander).
const soon = new Date(Date.now() + 2 * 3_600_000).toISOString()

function items(): Task[] {
  return [
    {
      id: 1, name: 'daily-briefing', type: 'CRON', status: 'ACTIVE', paused: false,
      cronExpression: '0 0 9 * * *', intervalSeconds: null, nextRunAt: soon, agentName: 'main',
    },
    {
      id: 2, name: 'pay-rent', type: 'SCHEDULED', status: 'PENDING', paused: false,
      cronExpression: null, intervalSeconds: null, nextRunAt: soon, agentName: 'main',
    },
  ] as unknown as Task[]
}

registerEndpoint('/api/task-runs/recent', () => [])

beforeEach(() => {
  clearNuxtData()
})

describe('ScheduleCalendar (JCLAW-440)', () => {
  it('renders the month grid (weekday headers + granularity nav)', async () => {
    const c = await mountSuspended(ScheduleCalendar, { props: { items: items() } })
    const text = c.text()
    for (const dn of ['Sun', 'Wed', 'Sat']) expect(text).toContain(dn)
    // Granularity buttons + Today control.
    expect(c.findAll('button').some(b => b.text() === 'month')).toBe(true)
    expect(c.findAll('button').some(b => b.text() === 'Today')).toBe(true)
  })

  it('projects fires onto the grid (item names appear)', async () => {
    const c = await mountSuspended(ScheduleCalendar, { props: { items: items() } })
    const text = c.text()
    // The one-shot fires ~2h out → shows on its day; the daily cron fires across
    // the whole grid.
    expect(text).toContain('pay-rent')
    expect(text).toContain('daily-briefing')
  })

  it('fire-projection mode (showRuns=false) mounts and navigates without runs', async () => {
    const c = await mountSuspended(ScheduleCalendar, { props: { items: items(), showRuns: false } })
    // Switch to the week hourly grid — with showRuns=false it shows fire markers
    // only (no run-block fetch), and must render without error.
    const weekBtn = c.findAll('button').find(b => b.text() === 'week')
    expect(weekBtn).toBeDefined()
    await weekBtn?.trigger('click')
    expect(c.text()).toContain('Today')
  })
})
