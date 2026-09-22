import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { getQuery, setResponseStatus, type H3Event } from 'h3'
import Scrapes from '~/pages/scrapes/index.vue'
import type { ScrapeJob, ScrapeJobState } from '~/types/api'

/** JCLAW-1273: the Scrapes list — its rows, the actions each state allows, and when it polls. */

function job(id: number, state: ScrapeJobState, extra: Partial<ScrapeJob> = {}): ScrapeJob {
  return {
    id, agentId: 1, agentName: 'main', conversationId: null, url: `https://site${id}.test/docs`, state,
    pagesRead: 12, pagesFetched: 10, pagesDiscovered: 40, stopReason: null, errorMessage: null, summary: null,
    folder: `scrapes/${id}`, combinedFile: null,
    options: { url: `https://site${id}.test/docs`, maxPages: 30, maxDepth: 2, maxMinutes: 60, sameHostOnly: true,
      respectRobots: true, seedFromSitemap: true, language: 'en', format: 'markdown', metadata: false },
    runtimeSeconds: 75, interruptions: 0, createdAt: '2026-09-22T10:00:00Z', startedAt: null, completedAt: null,
    ...extra,
  }
}

async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

function listing(...jobs: ScrapeJob[]) {
  return (event: H3Event) => {
    event.node.res.setHeader('X-Total-Count', String(jobs.length))
    return jobs
  }
}

beforeEach(() => {
  registerEndpoint('/api/agents', () => [{ id: 1, name: 'main', isMain: true, enabled: true }])
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
})

afterEach(() => {
  vi.useRealTimers()
  const { _state, _resolve } = useConfirm()
  if (_state.open) _resolve(false)
})

describe('Scrapes page', () => {
  it('lists each job with its site, agent, state in words and its progress', async () => {
    registerEndpoint('/api/scrape-jobs', listing(job(1, 'RUNNING'), job(2, 'INTERRUPTED')))

    const page = await mountSuspended(Scrapes)
    await settle()

    const row = page.find('[data-testid="scrape-row-1"]')
    expect(row.text()).toContain('site1.test/docs')
    expect(row.text()).toContain('main')
    expect(row.text()).toContain('Running')
    expect(row.text()).toContain('1 min 15 s')
    const bar = row.find('[role="progressbar"]')
    expect(bar.attributes('aria-valuenow')).toBe('12')
    expect(bar.attributes('aria-valuemax')).toBe('30')
    expect(bar.attributes('aria-valuetext')).toBe('12 of 30 pages')
    expect(page.find('[data-testid="scrape-row-2"]').text()).toContain('Interrupted')
    page.unmount()
  })

  it('offers the actions each state allows and no others', async () => {
    registerEndpoint('/api/scrape-jobs', listing(job(1, 'RUNNING'), job(2, 'PAUSED'), job(3, 'SUCCEEDED'), job(4, 'PENDING')))

    const page = await mountSuspended(Scrapes)
    await settle()

    const buttons = (id: number) => page.find(`[data-testid="scrape-row-${id}"]`).findAll('button').map(b => b.text())
    expect(buttons(1)).toEqual(['Pause', 'Stop'])
    expect(buttons(2)).toEqual(['Resume', 'Stop', 'Delete'])
    expect(buttons(3)).toEqual(['Delete'])
    expect(buttons(4)).toEqual(['Pause', 'Stop'])
    page.unmount()
  })

  it('polls while a job is running, stops once none is, and stops when the page goes', async () => {
    let calls = 0
    registerEndpoint('/api/scrape-jobs', (event: H3Event) => {
      calls += 1
      event.node.res.setHeader('X-Total-Count', '1')
      return [job(1, calls < 3 ? 'RUNNING' : 'SUCCEEDED')]
    })

    const page = await mountSuspended(Scrapes)
    await settle()
    expect(calls).toBe(1)

    vi.advanceTimersByTime(3000)
    await settle()
    expect(calls).toBe(2)
    vi.advanceTimersByTime(3000)
    await settle()
    expect(calls).toBe(3)
    expect(page.find('[data-testid="scrape-row-1"]').text()).toContain('Finished')

    vi.advanceTimersByTime(9000)
    await settle()
    expect(calls).toBe(3)
    page.unmount()
  })

  it('stops polling when the operator leaves the page', async () => {
    let calls = 0
    registerEndpoint('/api/scrape-jobs', (event: H3Event) => {
      calls += 1
      event.node.res.setHeader('X-Total-Count', '1')
      return [job(1, 'RUNNING')]
    })

    const page = await mountSuspended(Scrapes)
    await settle()
    page.unmount()
    vi.advanceTimersByTime(9000)
    await settle()
    expect(calls).toBe(1)
  })

  it('pauses a job and refreshes the list', async () => {
    let paused = false
    registerEndpoint('/api/scrape-jobs', listing(job(1, 'RUNNING')))
    registerEndpoint('/api/scrape-jobs/1/pause', { method: 'POST', handler: () => {
      paused = true
      return job(1, 'PAUSED')
    } })

    const page = await mountSuspended(Scrapes)
    await settle()
    await page.find('[data-testid="scrape-row-1"]').findAll('button')[0]!.trigger('click')
    await settle()

    expect(paused).toBe(true)
    page.unmount()
  })

  it('asks before deleting, and shows why a refused action failed', async () => {
    registerEndpoint('/api/scrape-jobs', listing(job(3, 'SUCCEEDED')))
    registerEndpoint('/api/scrape-jobs/3', { method: 'DELETE', handler: (event: H3Event) => {
      setResponseStatus(event, 409)
      return { code: 'conflict', message: 'Scrape job 3 is still running; pause or stop it before deleting it.', template: null }
    } })

    const page = await mountSuspended(Scrapes)
    await settle()
    await page.find('[data-testid="scrape-row-3"]').find('button').trigger('click')
    await settle()

    const { _state, _resolve } = useConfirm()
    expect(_state.open).toBe(true)
    expect(_state.message).toContain('site3.test/docs')
    _resolve(true)
    await settle()

    expect(page.text()).toContain('pause or stop it before deleting it')
    page.unmount()
  })

  it('filters by agent and state', async () => {
    const queries: Array<Record<string, unknown>> = []
    registerEndpoint('/api/scrape-jobs', (event: H3Event) => {
      queries.push(getQuery(event))
      event.node.res.setHeader('X-Total-Count', '0')
      return []
    })

    const page = await mountSuspended(Scrapes)
    await settle()
    await page.find('#scrapes-state-filter').setValue('PAUSED')
    await settle()
    await page.find('#scrapes-agent-filter').setValue('1')
    await settle()

    expect(queries.at(-1)).toMatchObject({ state: 'PAUSED', agentId: '1' })
    expect(page.find('[data-testid="scrapes-empty"]').text()).toContain('No scrapes match these filters')
    page.unmount()
  })

  it('says how to start a scrape when there is none', async () => {
    registerEndpoint('/api/scrape-jobs', listing())

    const page = await mountSuspended(Scrapes)
    await settle()

    expect(page.find('[data-testid="scrapes-empty"]').text()).toContain('ask an agent to scrape a site in the background')
    page.unmount()
  })
})
