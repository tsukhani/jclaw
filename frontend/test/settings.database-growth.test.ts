import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import SettingsDatabaseGrowth from '~/components/settings/SettingsDatabaseGrowth.vue'

/**
 * Settings → Database → Where the space goes (JF-496): the tables in size order with their part of the file and
 * their change, the rows that make the table add up to the file, and what it says before any history exists.
 */

const MB = 1024 * 1024

let growth: Record<string, unknown> | null
registerEndpoint('/api/system/database/growth', {
  method: 'GET',
  handler: (event) => {
    if (growth === null) {
      setResponseStatus(event, 500)
      return { type: 'error', code: 'internal_error', message: 'The database is closed' }
    }
    return growth
  },
})

function table(name: string, bytes: number, rows: number, over: Record<string, unknown> = {}) {
  return { name, bytes, rows, share: bytes / (1000 * MB), day: null, week: null, month: null, trend: [bytes], ...over }
}

function sampled(over: Record<string, unknown> = {}) {
  return {
    sampledOn: '2026-11-17',
    takenAt: '2026-11-17T01:00:00Z',
    historySince: '2026-11-17',
    file: { day: '2026-11-17', fileBytes: 1000 * MB, liveBytes: 750 * MB, reclaimableBytes: 250 * MB },
    otherLiveBytes: 150 * MB,
    tables: [table('MESSAGE', 450 * MB, 75_226), table('EVENT_LOG', 150 * MB, 232_603)],
    fileTrend: [],
    ...over,
  }
}

async function mount() {
  const c = await mountSuspended(SettingsDatabaseGrowth, { props: { timezone: 'Asia/Singapore' } })
  for (let i = 0; i < 4; i++) await flushPromises()
  return c
}

const cells = (row: { findAll: (s: string) => Array<{ text: () => string }> }) =>
  row.findAll('th, td').map(c => c.text())

beforeEach(() => {
  clearNuxtData()
})

describe('Settings → Database → Where the space goes', () => {
  it('says no sizes are recorded yet, and shows no table, before the first sample', async () => {
    growth = { sampledOn: null, takenAt: null, historySince: null, file: null, otherLiveBytes: null, tables: [], fileTrend: [] }
    const c = await mount()
    expect(c.find('[data-testid="db-growth-state"]').text())
      .toBe('No sizes recorded yet. JClaw records each table\'s size once a day, the first time shortly after it starts.')
    expect(c.find('[data-testid="db-growth-table"]').exists()).toBe(false)
  })

  it('lists the tables by size, then what no table counts, the free space and the file, which add up to it', async () => {
    growth = sampled()
    const c = await mount()
    expect(c.find('[data-testid="db-growth-state"]').text())
      .toBe('As of 17 Nov 2026 · 9 am, recorded once a day. History starts on 17 Nov 2026; the longer columns fill in as it grows.')
    expect(cells(c.find('[data-testid="db-growth-row-MESSAGE"]')).slice(0, 4)).toEqual(['MESSAGE', '450.0 MB', '45.0%', '75,226'])
    expect(cells(c.find('[data-testid="db-growth-row-EVENT_LOG"]')).slice(0, 4)).toEqual(['EVENT_LOG', '150.0 MB', '15.0%', '232,603'])
    expect(cells(c.find('[data-testid="db-growth-uncounted"]')).slice(0, 3)).toEqual(['Indexes and other', '150.0 MB', '15.0%'])
    expect(cells(c.find('[data-testid="db-growth-free"]')).slice(0, 3))
      .toEqual(['Free space a compaction would remove', '250.0 MB', '25.0%'])
    expect(cells(c.find('[data-testid="db-growth-total"]')).slice(0, 3)).toEqual(['The data file', '1000.0 MB', '100%'])
    const names = c.findAll('tbody tr').map(r => r.attributes('data-testid'))
    expect(names).toEqual(['db-growth-row-MESSAGE', 'db-growth-row-EVENT_LOG', 'db-growth-uncounted', 'db-growth-free'])
  })

  it('shows a change signed in its text, with the rows beneath, and a dash where no sample is that old', async () => {
    growth = sampled({
      historySince: '2026-10-01',
      tables: [table('MESSAGE', 450 * MB, 75_226, { day: { bytes: 9 * MB, rows: 1_226 }, week: { bytes: -3.1 * MB, rows: -5 } })],
    })
    const c = await mount()
    const [day, week, month] = c.find('[data-testid="db-growth-row-MESSAGE"]').findAll('td').slice(3, 6)
    expect(day!.findAll('span').map(s => s.text())).toEqual(['+9.0 MB', '+1,226 rows'])
    expect(week!.findAll('span').map(s => s.text())).toEqual(['−3.1 MB', '−5 rows'])
    expect(month!.find('[aria-hidden="true"]').text()).toBe('—')
    expect(month!.find('.sr-only').text()).toBe('no sample that old')
    expect(c.find('[data-testid="db-growth-state"]').text()).toBe('As of 17 Nov 2026 · 9 am, recorded once a day.')
  })

  it('keeps the ten largest tables in view and sums the rest in one row that expands', async () => {
    const tables = Array.from({ length: 12 }, (_, i) => table(`T${String(i).padStart(2, '0')}`, (12 - i) * MB, i + 1,
      { day: { bytes: MB, rows: 1 } }))
    growth = sampled({ tables })
    const c = await mount()
    expect(c.findAll('[data-testid^="db-growth-row-"]')).toHaveLength(10)
    const others = c.find('[data-testid="db-growth-others"]')
    expect(cells(others).slice(0, 5)).toEqual(['2 other tables', '3.0 MB', '0.3%', '23', '+2.0 MB'])
    const toggle = others.find('button')
    expect(toggle.attributes('aria-expanded')).toBe('false')

    await toggle.trigger('click')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(c.find('[data-testid="db-growth-row-T10"]').exists()).toBe(true)
    expect(c.find('[data-testid="db-growth-row-T11"]').exists()).toBe(true)
  })

  it('draws the file against its live data once there are two days, and says what changed', async () => {
    growth = sampled({
      fileTrend: [
        { day: '2026-11-16', fileBytes: 900 * MB, liveBytes: 700 * MB, reclaimableBytes: 200 * MB },
        { day: '2026-11-17', fileBytes: 1000 * MB, liveBytes: 750 * MB, reclaimableBytes: 250 * MB },
      ],
    })
    const c = await mount()
    expect(c.find('[data-testid="db-growth-file"] svg').attributes('aria-label')).toBe(
      'From 16 Nov 2026 to 17 Nov 2026, the file went from 900.0 MB to 1000.0 MB, and its live data from 700.0 MB to 750.0 MB.')

    growth = sampled({ fileTrend: [{ day: '2026-11-17', fileBytes: 1000 * MB, liveBytes: 750 * MB, reclaimableBytes: 250 * MB }] })
    clearNuxtData()
    const one = await mount()
    expect(one.find('[data-testid="db-growth-file"]').exists()).toBe(false)
  })

  it('says why when the history cannot be read', async () => {
    growth = null
    const c = await mount()
    expect(c.find('[data-testid="db-growth-state"]').text()).toContain('Could not read the size history')
    expect(c.find('[data-testid="db-growth-table"]').exists()).toBe(false)
  })
})
