import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus } from 'h3'
import { clearNuxtData } from '#app'
import SettingsDatabaseGrowth from '~/components/settings/SettingsDatabaseGrowth.vue'
import { DB_FREE_FILL, DB_INDEXES_COLOR, DB_OTHER_TABLES_COLOR, DB_TABLE_COLORS } from '~/utils/database-growth'

/**
 * Settings → Database → Where the space goes (JF-496, JF-525): the three figures, the file's daily chart and its
 * Columns / Area switch, the bar of the file's parts with each row in its part's color, and the tables in size order
 * with their change and a growth bar on one shared scale.
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
  return { name, bytes, rows, share: bytes / (1000 * MB), day: null, week: null, growth: null, ...over }
}

const fileDay = (day: string, fileMb: number, liveMb: number) =>
  ({ day, fileBytes: fileMb * MB, liveBytes: liveMb * MB, reclaimableBytes: (fileMb - liveMb) * MB })

function sampled(over: Record<string, unknown> = {}) {
  return {
    sampledOn: '2026-11-17',
    takenAt: '2026-11-17T01:00:00Z',
    historySince: '2026-11-17',
    growthDays: 0,
    file: fileDay('2026-11-17', 1000, 750),
    otherLiveBytes: 150 * MB,
    tables: [table('MESSAGE', 450 * MB, 75_226), table('EVENT_LOG', 150 * MB, 232_603)],
    fileTrend: [fileDay('2026-11-17', 1000, 750)],
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

const childTexts = (el: Element) => Array.from(el.children).map(e => (e.textContent ?? '').trim())

beforeEach(() => {
  clearNuxtData()
  localStorage.clear()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('Settings → Database → Where the space goes', () => {
  it('says no sizes are recorded yet, and shows no table, before the first sample', async () => {
    growth = { sampledOn: null, takenAt: null, historySince: null, growthDays: 0, file: null, otherLiveBytes: null, tables: [], fileTrend: [] }
    const c = await mount()
    expect(c.find('[data-testid="db-growth-state"]').text())
      .toBe('No sizes recorded yet. JClaw records each table\'s size once a day, the first time shortly after it starts.')
    expect(c.find('[data-testid="db-growth-table"]').exists()).toBe(false)
    expect(c.find('[data-testid="db-growth-figures"]').exists()).toBe(false)
    expect(c.find('[data-testid="db-growth-file"]').exists()).toBe(false)
    expect(c.find('[data-testid="db-growth-parts"]').exists()).toBe(false)
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
    expect(c.findAll('thead th').map(h => h.text())).toEqual(['Table', 'Size', 'Share', 'Rows', '1 day', '7 days', '30 days'])
  })

  it('leads with the file, what is in use and the free space, with their change since the chart\'s first day', async () => {
    growth = sampled({ fileTrend: [fileDay('2026-11-16', 900, 700), fileDay('2026-11-17', 1000, 750)] })
    const c = await mount()
    expect(childTexts(c.find('[data-testid="db-growth-figure-file"]').element))
      .toEqual(['Data file', '1000.0 MB', '+100.0 MB since 16 Nov'])
    expect(childTexts(c.find('[data-testid="db-growth-figure-live"]').element))
      .toEqual(['In use', '750.0 MB', '75.0% of the file · +50.0 MB since 16 Nov'])
    expect(childTexts(c.find('[data-testid="db-growth-figure-free"]').element))
      .toEqual(['Free space', '250.0 MB', '25.0% of the file. A compaction would remove it.'])

    growth = sampled()
    clearNuxtData()
    const one = await mount()
    expect(childTexts(one.find('[data-testid="db-growth-figure-file"]').element)).toEqual(['Data file', '1000.0 MB'])
    expect(childTexts(one.find('[data-testid="db-growth-figure-live"]').element)).toEqual(['In use', '750.0 MB', '75.0% of the file'])
  })

  it('shows a change signed in its text, with the rows beneath, and a dash where no sample is that old', async () => {
    growth = sampled({
      historySince: '2026-10-01',
      tables: [table('MESSAGE', 450 * MB, 75_226, { day: { bytes: 9 * MB, rows: 1_226 }, week: { bytes: -3.1 * MB, rows: -5 } })],
    })
    const c = await mount()
    const [day, week, grown] = c.find('[data-testid="db-growth-row-MESSAGE"]').findAll('td').slice(3, 6)
    expect(day!.findAll('span').map(s => s.text())).toEqual(['+9.0 MB', '+1,226 rows'])
    expect(week!.findAll('span').map(s => s.text())).toEqual(['−3.1 MB', '−5 rows'])
    expect(grown!.find('[aria-hidden="true"]').text()).toBe('—')
    expect(grown!.find('.sr-only').text()).toBe('no sample that old')
    expect(c.find('[data-testid="db-growth-state"]').text()).toBe('As of 17 Nov 2026 · 9 am, recorded once a day.')
  })

  it('draws every row\'s growth on one zero-based scale, with the window it covers in the header', async () => {
    growth = sampled({
      growthDays: 2,
      tables: [
        table('MESSAGE', 450 * MB, 75_226, { growth: { bytes: 40 * MB, rows: 400 } }),
        table('EVENT_LOG', 150 * MB, 232_603, { growth: { bytes: 10 * MB, rows: 0 } }),
        table('SCRAPE_JOB_PAGE', 50 * MB, 1_418, { growth: { bytes: -10 * MB, rows: -1 } }),
      ],
    })
    const c = await mount()
    expect(c.find('[data-testid="db-growth-growth-header"]').text()).toBe('Since 15 Nov')
    const bar = (name: string) => (c.find(`[data-testid="db-growth-row-${name}"] [data-testid="db-growth-bar"]`).element as HTMLElement).style
    expect([bar('MESSAGE').left, bar('MESSAGE').width]).toEqual(['20%', '80%'])
    expect([bar('EVENT_LOG').left, bar('EVENT_LOG').width]).toEqual(['20%', '20%'])
    expect([bar('SCRAPE_JOB_PAGE').left, bar('SCRAPE_JOB_PAGE').width], 'a shrinking table draws left of zero').toEqual(['0%', '20%'])
    const figure = c.find('[data-testid="db-growth-row-MESSAGE"]').findAll('td')[5]!
    expect(figure.text()).toBe('+40.0 MB')

    growth = sampled({ growthDays: 30 })
    clearNuxtData()
    const month = await mount()
    expect(month.find('[data-testid="db-growth-growth-header"]').text()).toBe('30 days')
  })

  it('splits the file into its parts, which add up to it, and paints each row in its part\'s color', async () => {
    const tables = [['A', 300], ['B', 150], ['C', 100], ['D', 50], ['E', 30], ['F', 20]]
      .map(([n, mb]) => table(n as string, (mb as number) * MB, 1))
    growth = sampled({ tables, otherLiveBytes: 100 * MB })
    const c = await mount()
    const parts = c.findAll('[data-testid^="db-growth-part-"]').filter(p => p.attributes('data-bytes'))
    expect(parts.map(p => p.attributes('data-testid')!.replace('db-growth-part-', '')))
      .toEqual(['A', 'B', 'C', 'D', 'others', 'index', 'free'])
    expect(parts.reduce((a, p) => a + Number(p.attributes('data-bytes')), 0)).toBe(1000 * MB)

    // jsdom writes a hex inside light-dark() back as rgb(), so a part is compared with what the same color renders as.
    const background = (selector: string) => (c.find(selector).element as HTMLElement).style.background
    const rendered = (color: string) => {
      const probe = document.createElement('span')
      probe.style.background = color
      return probe.style.background
    }
    const part = (key: string) => background(`[data-testid="db-growth-part-${key}"]`)
    const swatch = (name: string) => background(`[data-testid="db-growth-row-${name}"] [data-testid="db-growth-swatch"]`)
    expect(['A', 'B', 'C', 'D'].map(part)).toEqual(DB_TABLE_COLORS.map(rendered))
    expect(new Set(DB_TABLE_COLORS.map(rendered)).size).toBe(4)
    expect(['A', 'B', 'C', 'D'].map(swatch)).toEqual(['A', 'B', 'C', 'D'].map(part))
    expect([swatch('E'), swatch('F'), part('others')]).toEqual(Array(3).fill(rendered(DB_OTHER_TABLES_COLOR)))
    expect(part('index')).toBe(rendered(DB_INDEXES_COLOR))
    expect(part('free')).toBe(rendered(DB_FREE_FILL))
    expect(rendered(DB_TABLE_COLORS[0])).toMatch(/^light-dark\(/)
  })

  it('names a part when it is pointed at or focused, and a table row lights its part in the bar', async () => {
    growth = sampled()
    const c = await mount()
    const readout = c.find('[data-testid="db-growth-parts-readout"]')
    expect(readout.text()).toBe('The file today, by part. Each row below wears its part\'s color.')

    await c.find('[data-testid="db-growth-part-EVENT_LOG"]').trigger('focus')
    expect(readout.text()).toBe('EVENT_LOG: 150.0 MB, 15.0% of the file')
    expect(c.find('[data-testid="db-growth-part-MESSAGE"]').classes()).toContain('opacity-30')
    expect(c.find('[data-testid="db-growth-part-EVENT_LOG"]').classes()).not.toContain('opacity-30')
    await c.find('[data-testid="db-growth-part-EVENT_LOG"]').trigger('blur')
    expect(readout.text()).toBe('The file today, by part. Each row below wears its part\'s color.')

    await c.find('[data-testid="db-growth-free"]').trigger('pointerenter')
    expect(readout.text()).toBe('Free space a compaction would remove: 250.0 MB, 25.0% of the file')
    expect(c.find('[data-testid="db-growth-part-free"]').classes()).not.toContain('opacity-30')
    expect(c.find('[data-testid="db-growth-part-MESSAGE"]').classes()).toContain('opacity-30')
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

  it('draws one column a day on a 30-day axis, and says what changed', async () => {
    growth = sampled({ fileTrend: [fileDay('2026-11-16', 900, 700), fileDay('2026-11-17', 1000, 750)] })
    const c = await mount()
    const chart = c.find('[data-testid="db-growth-file"]')
    expect(chart.attributes('data-form')).toBe('columns')
    expect(chart.findAll('[data-testid="db-growth-column"]')).toHaveLength(2)
    expect(chart.find('svg').attributes('aria-label')).toBe('The data file by day, 16 Nov to 17 Nov 2026: from 900.0 MB '
      + 'to 1000.0 MB; in use from 700.0 MB to 750.0 MB; free space from 200.0 MB to 250.0 MB. Arrow keys step through the days.')
    expect(chart.findAll('svg text').map(t => t.text())).toContain('No samples before 16 Nov')
    expect(chart.find('[data-testid="db-growth-area-note"]').exists()).toBe(false)
  })

  it('steps a tooltip through the sampled days from the keyboard', async () => {
    growth = sampled({ fileTrend: [fileDay('2026-11-16', 900, 700), fileDay('2026-11-17', 1000, 750)] })
    const c = await mount()
    const svg = c.find('[data-testid="db-growth-file"] svg')
    await svg.trigger('focus')
    expect(childTexts(c.find('[data-testid="db-growth-tip"]').element))
      .toEqual(['17 Nov 2026', 'Data file1000.0 MB', 'In use750.0 MB', 'Free space250.0 MB · 25.0%'])
    await svg.trigger('keydown', { key: 'ArrowLeft' })
    expect(childTexts(c.find('[data-testid="db-growth-tip"]').element)[0]).toBe('16 Nov 2026')
    await svg.trigger('keydown', { key: 'ArrowLeft' })
    expect(childTexts(c.find('[data-testid="db-growth-tip"]').element)[0], 'the oldest day is the end').toBe('16 Nov 2026')
    await svg.trigger('keydown', { key: 'Escape' })
    expect(c.find('[data-testid="db-growth-tip"]').exists()).toBe(false)
  })

  it('switches to an area, says how to read it, and keeps the choice for the next visit', async () => {
    growth = sampled({ fileTrend: [fileDay('2026-11-16', 900, 700), fileDay('2026-11-17', 1000, 750)] })
    const c = await mount()
    await c.find('[data-testid="db-growth-form-area"]').trigger('click')
    const chart = c.find('[data-testid="db-growth-file"]')
    expect(chart.attributes('data-form')).toBe('area')
    expect(c.find('[data-testid="db-growth-form-area"]').attributes('aria-pressed')).toBe('true')
    expect(chart.findAll('[data-testid="db-growth-column"]')).toHaveLength(0)
    expect(chart.find('[data-testid="db-growth-area-free"]').exists()).toBe(true)
    expect(chart.find('[data-testid="db-growth-area-in-use"]').exists()).toBe(true)
    expect(chart.findAll('[data-testid="db-growth-area-point"]')).toHaveLength(2)
    expect(chart.find('[data-testid="db-growth-area-note"]').text()).toBe('The area joins one sample a day with straight '
      + 'lines, so it draws any change between two days as a slope, a compaction included.')
    expect(localStorage.getItem('jclaw:db-growth:chart-form')).toBe('area')

    clearNuxtData()
    const again = await mount()
    expect(again.find('[data-testid="db-growth-file"]').attributes('data-form')).toBe('area')

    await again.find('[data-testid="db-growth-form-columns"]').trigger('click')
    expect(localStorage.getItem('jclaw:db-growth:chart-form')).toBe('columns')
  })

  it('shows one day as points in the area, which starts with the second day', async () => {
    localStorage.setItem('jclaw:db-growth:chart-form', 'area')
    growth = sampled()
    const c = await mount()
    const chart = c.find('[data-testid="db-growth-file"]')
    expect(chart.attributes('data-form')).toBe('area')
    expect(chart.find('[data-testid="db-growth-area-free"]').exists()).toBe(false)
    expect(chart.findAll('[data-testid="db-growth-area-point"]')).toHaveLength(1)
    expect(chart.find('[data-testid="db-growth-area-note"]').text())
      .toBe('The area starts with the second day\'s sample; until then each day shows as points.')
    expect(chart.find('svg').attributes('aria-label'))
      .toBe('The data file on 17 Nov 2026: 1000.0 MB, of which 750.0 MB in use and 250.0 MB free.')
  })

  it('opens on Columns and still switches when the browser refuses storage', async () => {
    const refuse = () => {
      throw new DOMException('denied', 'SecurityError')
    }
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(refuse)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(refuse)
    growth = sampled({ fileTrend: [fileDay('2026-11-16', 900, 700), fileDay('2026-11-17', 1000, 750)] })
    const c = await mount()
    expect(c.find('[data-testid="db-growth-file"]').attributes('data-form')).toBe('columns')
    await c.find('[data-testid="db-growth-form-area"]').trigger('click')
    expect(c.find('[data-testid="db-growth-file"]').attributes('data-form')).toBe('area')
  })

  it('says why when the history cannot be read', async () => {
    growth = null
    const c = await mount()
    expect(c.find('[data-testid="db-growth-state"]').text()).toContain('Could not read the size history')
    expect(c.find('[data-testid="db-growth-table"]').exists()).toBe(false)
  })
})
