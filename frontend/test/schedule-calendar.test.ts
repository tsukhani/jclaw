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

  it('marks a non-active fire with a status glyph, not colour alone', async () => {
    const c = await mountSuspended(ScheduleCalendar, { props: { items: items() } })
    const chips = c.findAll('li').filter(li => li.find('.font-mono').exists())
    const pending = chips.find(li => li.text().includes('pay-rent'))
    const active = chips.find(li => li.text().includes('daily-briefing'))
    expect(pending?.find('svg[aria-hidden="true"]').exists()).toBe(true)
    expect(active?.find('svg').exists()).toBe(false)
  })

  it('"+N more" drills into the day view for that cell', async () => {
    // Every 2 hours ⇒ 12 fires/day, well past the 4-per-cell month-grid cap.
    const busy = [{
      id: 3, name: 'every-2h', type: 'CRON', status: 'ACTIVE', paused: false,
      cronExpression: '0 0 */2 * * *', intervalSeconds: null, nextRunAt: soon, agentName: 'main',
    }] as unknown as Task[]
    const c = await mountSuspended(ScheduleCalendar, { props: { items: busy, showRuns: false } })
    const more = c.findAll('button').find(b => b.text().endsWith('more'))
    expect(more).toBeDefined()
    await more?.trigger('click')
    const dayBtn = c.findAll('button').find(b => b.text() === 'day')
    expect(dayBtn?.attributes('aria-pressed')).toBe('true')
  })

  it('renders concurrent fires in side-by-side lanes, not stacked', async () => {
    // Both fire at 09:00 every day, so every column of the week grid holds a
    // collision. Before lane packing both drew full-width and only one was legible.
    const at9 = (id: number, name: string) => ({
      id, name, type: 'CRON', status: 'ACTIVE', paused: false,
      cronExpression: '0 0 9 * * *', intervalSeconds: null, nextRunAt: soon, agentName: 'main',
    })
    const c = await mountSuspended(ScheduleCalendar, {
      props: { items: [at9(4, 'alpha'), at9(5, 'beta')] as unknown as Task[], showRuns: false },
    })
    await c.findAll('button').find(b => b.text() === 'week')?.trigger('click')
    const laned = c.findAll('div[style]')
      .map(d => d.attributes('style') ?? '')
      .filter(s => s.includes('width'))
    expect(laned).toHaveLength(14) // 7 days × 2 markers
    expect(laned.every(s => s.includes('width: calc(50% - 4px)'))).toBe(true)
    expect(laned.filter(s => s.includes('left: calc(0% + 2px)'))).toHaveLength(7)
    expect(laned.filter(s => s.includes('left: calc(50% + 2px)'))).toHaveLength(7)
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
