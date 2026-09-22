import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, mockNuxtImport, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import type { H3Event } from 'h3'
import { getQuery } from 'h3'
import ScrapeJobDetail from '~/pages/scrapes/[id].vue'
import type { ScrapeJob, ScrapeJobPage, ScrapeJobState } from '~/types/api'

/** JCLAW-1273: a scrape job's detail view — its pages by cursor, the viewer, and what its state says. */

const routeParams = { value: { id: '7' } as Record<string, string> }
mockNuxtImport('useRoute', () => () => ({ params: routeParams.value, query: {} }))

function job(state: ScrapeJobState, extra: Partial<ScrapeJob> = {}): ScrapeJob {
  return {
    id: 7, agentId: 1, agentName: 'main', conversationId: null, url: 'https://docs.example.test/', state,
    pagesRead: 2, pagesFetched: 2, pagesDiscovered: 9, stopReason: null, errorMessage: null, summary: null,
    folder: 'scrapes/7', combinedFile: null,
    options: { url: 'https://docs.example.test/', maxPages: 500, maxDepth: 2, maxMinutes: 60, sameHostOnly: true,
      respectRobots: true, seedFromSitemap: true, language: 'en', format: 'markdown', metadata: false },
    runtimeSeconds: 30, interruptions: 0, createdAt: '2026-09-22T10:00:00Z', startedAt: null, completedAt: null,
    ...extra,
  }
}

function page(index: number, extra: Partial<ScrapeJobPage> = {}): ScrapeJobPage {
  return { id: 100 + index, index, url: `https://docs.example.test/p${index}`, depth: 1, servedBy: 'PLAIN',
    outcome: 'FETCHED', reason: null, chars: 1200, hasContent: true, fetchedAt: '2026-09-22T10:00:01Z', ...extra }
}

async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

beforeEach(() => {
  routeParams.value = { id: '7' }
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
})

afterEach(() => {
  vi.useRealTimers()
})

describe('Scrape job detail', () => {
  it('asks only for pages after the last one it has, and stops once the job ends', async () => {
    let jobCalls = 0
    registerEndpoint('/api/scrape-jobs/7', () => {
      jobCalls += 1
      return job(jobCalls < 2 ? 'RUNNING' : 'SUCCEEDED')
    })
    const cursors: number[] = []
    registerEndpoint('/api/scrape-jobs/7/pages', (event: H3Event) => {
      const after = Number(getQuery(event).after)
      cursors.push(after)
      if (after === 0) return [page(1), page(2)]
      if (after === 2) return [page(3)]
      return []
    })

    const view = await mountSuspended(ScrapeJobDetail)
    await settle()
    expect(view.findAll('[data-testid^="scrape-page-"]')).toHaveLength(2)

    vi.advanceTimersByTime(3000)
    await settle()
    expect(cursors).toEqual([0, 2])
    expect(view.findAll('[data-testid^="scrape-page-"]')).toHaveLength(3)

    vi.advanceTimersByTime(9000)
    await settle()
    // No poll once the job has ended.
    expect(cursors).toEqual([0, 2])
    view.unmount()
  })

  it('loads a long job\'s pages a batch at a time', async () => {
    registerEndpoint('/api/scrape-jobs/7', () => job('SUCCEEDED', { pagesRead: 250 }))
    const requests: string[] = []
    registerEndpoint('/api/scrape-jobs/7/pages', (event: H3Event) => {
      const { after, limit } = getQuery(event)
      requests.push(`${after}/${limit}`)
      const from = Number(after)
      const count = Math.max(0, Math.min(Number(limit), 250 - from))
      return Array.from({ length: count }, (_, i) => page(from + i + 1))
    })

    const view = await mountSuspended(ScrapeJobDetail)
    await settle()

    expect(requests).toEqual(['0/200', '200/200'])
    expect(view.findAll('[data-testid^="scrape-page-"]')).toHaveLength(250)
    view.unmount()
  })

  it('shows a selected page and never a page it could not read', async () => {
    registerEndpoint('/api/scrape-jobs/7', () => job('SUCCEEDED'))
    registerEndpoint('/api/scrape-jobs/7/pages', () => [page(1), page(2, { outcome: 'BLOCKED', reason: 'TURNSTILE', hasContent: false, chars: 0 })])
    registerEndpoint('/api/scrape-jobs/7/pages/101/content', () => ({ id: 101, url: 'https://docs.example.test/p1', format: 'markdown', content: '# Install\n\nRun the thing.' }))

    const view = await mountSuspended(ScrapeJobDetail)
    await settle()
    const blocked = view.find('[data-testid="scrape-page-2"]')
    expect(blocked.text()).toContain('Blocked (TURNSTILE)')
    expect(blocked.attributes('disabled')).toBeDefined()

    await view.find('[data-testid="scrape-page-1"]').trigger('click')
    await settle()

    expect(view.find('[data-testid="scrape-page-markdown"]').html()).toContain('<h3>Install</h3>')
    expect(view.find('a[download]').exists()).toBe(false)
    expect(view.find('[data-testid="scrape-page-1"]').attributes('aria-pressed')).toBe('true')
    view.unmount()
  })

  it('says a paused job keeps its place and an interrupted one waits because of restarts', async () => {
    registerEndpoint('/api/scrape-jobs/7/pages', () => [page(1)])
    registerEndpoint('/api/scrape-jobs/7', () => job('PAUSED'))
    let view = await mountSuspended(ScrapeJobDetail)
    await settle()
    expect(view.find('[data-testid="scrape-job-header"]').text()).toContain('Paused. Resuming continues from the 2 pages it has.')
    expect(view.text()).not.toContain('Failed')
    view.unmount()

    routeParams.value = { id: '8' }
    registerEndpoint('/api/scrape-jobs/8/pages', () => [page(1)])
    registerEndpoint('/api/scrape-jobs/8', () => job('INTERRUPTED', { id: 8, interruptions: 3 }))
    view = await mountSuspended(ScrapeJobDetail)
    await settle()
    const header = view.find('[data-testid="scrape-job-header"]').text()
    expect(header).toContain('Interrupted')
    expect(header).toContain('Restarts of JClaw stopped this scrape 3 times')
    expect(header).toContain('waits to be resumed')
    view.unmount()
  })

  it('says when a job continued after a restart, and offers its combined file', async () => {
    routeParams.value = { id: '9' }
    registerEndpoint('/api/scrape-jobs/9/pages', () => [])
    registerEndpoint('/api/scrape-jobs/9', () => job('SUCCEEDED', { id: 9, interruptions: 1, stopReason: 'page budget (500) reached', combinedFile: 'scrapes/9/combined.md' }))

    const view = await mountSuspended(ScrapeJobDetail)
    await settle()

    const header = view.find('[data-testid="scrape-job-header"]').text()
    expect(header).toContain('Finished')
    expect(header).toContain('Stopped: page budget (500) reached.')
    expect(header).toContain('It continued on its own after JClaw restarted once.')
    expect(view.find('a[download]').attributes('href')).toBe('/api/scrape-jobs/9/download')
    view.unmount()
  })
})
