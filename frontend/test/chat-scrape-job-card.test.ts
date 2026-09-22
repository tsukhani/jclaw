import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { setResponseStatus, type H3Event } from 'h3'
import ChatScrapeJobCard from '~/components/chat/ChatScrapeJobCard.vue'
import ChatScrapeJobNotice from '~/components/chat/ChatScrapeJobNotice.vue'
import { scrapeJobEndedKey } from '~/composables/useScrapeJobChat'
import type { ScrapeJob, ScrapeJobState } from '~/types/api'

/**
 * JCLAW-1273: the chat's card for a background scrape — it follows the job while it runs, reads the
 * job again after a reload, and tells the chat page when the job ends.
 */

function job(id: number, state: ScrapeJobState, pagesRead: number): ScrapeJob {
  return {
    id, agentId: 1, agentName: 'main', conversationId: 5, url: 'https://docs.example.test/guide', state,
    pagesRead, pagesFetched: pagesRead, pagesDiscovered: 40, stopReason: null, errorMessage: null, summary: null,
    folder: `scrapes/${id}`, combinedFile: null,
    options: { url: 'https://docs.example.test/guide', maxPages: 120, maxDepth: 2, maxMinutes: 60, sameHostOnly: true,
      respectRobots: true, seedFromSitemap: true, language: 'en', format: 'markdown', metadata: false },
    runtimeSeconds: 20, interruptions: 0, createdAt: '2026-09-22T10:00:00Z', startedAt: null, completedAt: null,
  }
}

async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
})

afterEach(() => {
  vi.useRealTimers()
})

describe('chat scrape job card', () => {
  it('follows a running job, tells the chat when it ends, then stops polling', async () => {
    let calls = 0
    registerEndpoint('/api/scrape-jobs/31', () => {
      calls += 1
      return calls === 1 ? job(31, 'RUNNING', 5) : job(31, 'SUCCEEDED', 40)
    })
    const ended = vi.fn()

    const card = await mountSuspended(ChatScrapeJobCard, {
      props: { jobRef: { id: 31, url: 'https://docs.example.test/guide', folder: 'scrapes/31' } },
      global: { provide: { [scrapeJobEndedKey as symbol]: ended } },
    })
    await settle()
    expect(card.text()).toContain('Scraping docs.example.test/guide')
    expect(card.find('[role="progressbar"]').attributes('aria-valuetext')).toBe('5 of 40 pages')
    expect(card.find('a').attributes('href')).toBe('/scrapes/31')

    vi.advanceTimersByTime(3000)
    await settle()
    expect(card.text()).toContain('Scraped docs.example.test/guide')
    expect(card.text()).toContain('Finished')
    expect(ended).toHaveBeenCalledTimes(1)

    vi.advanceTimersByTime(9000)
    await settle()
    expect(calls).toBe(2)
    card.unmount()
  })

  it('shows where a finished job ended after a reload, and does not report it as newly ended', async () => {
    registerEndpoint('/api/scrape-jobs/32', () => job(32, 'SUCCEEDED', 40))
    const ended = vi.fn()

    const card = await mountSuspended(ChatScrapeJobCard, {
      props: { jobRef: { id: 32, url: 'https://docs.example.test/guide', folder: 'scrapes/32' } },
      global: { provide: { [scrapeJobEndedKey as symbol]: ended } },
    })
    await settle()

    expect(card.text()).toContain('Finished')
    expect(ended).not.toHaveBeenCalled()
    card.unmount()
  })

  it('says so when the job was deleted', async () => {
    registerEndpoint('/api/scrape-jobs/33', (event: H3Event) => {
      setResponseStatus(event, 404)
      return ''
    })

    const card = await mountSuspended(ChatScrapeJobCard, {
      props: { jobRef: { id: 33, url: 'https://docs.example.test/guide', folder: 'scrapes/33' } },
    })
    await settle()

    expect(card.text()).toContain('This scrape was deleted.')
    expect(card.find('a').exists()).toBe(false)
    card.unmount()
  })

  it('renders the completion message as a notice with a link, not as the operator\'s message', async () => {
    const notice = await mountSuspended(ChatScrapeJobNotice, {
      props: { msg: {
        role: 'user', content: 'Background scrape job 31 finished: …', createdAt: '2026-09-22T10:05:00Z',
        messageKind: 'scrape_job_complete',
        metadata: { jobId: 31, state: 'SUCCEEDED', url: 'https://docs.example.test/guide', pagesRead: 40, pagesFetched: 38 },
      } },
    })

    expect(notice.text()).toContain('Background scrape finished: docs.example.test/guide')
    expect(notice.text()).toContain('38 of 40 pages read')
    expect(notice.find('a').attributes('href')).toBe('/scrapes/31')
    notice.unmount()
  })
})
