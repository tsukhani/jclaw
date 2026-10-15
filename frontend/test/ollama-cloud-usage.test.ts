import { describe, it, expect, afterEach, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { enableAutoUnmount, flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { createError, getQuery } from 'h3'
import OllamaCloudUsage from '~/components/settings/OllamaCloudUsage.vue'
import OllamaCloudRequests from '~/components/OllamaCloudRequests.vue'
import type { CloudUsage } from '~/types/api'

/**
 * JCLAW-1449: Ollama Cloud's request history — the "Cloud usage" block on its Settings card, and
 * the request counts beside the Chat Cost fees. The 7d reading is the one ollama.com returned on
 * 2026-10-15, through the backend's /api/router/usage/ollama-cloud shape.
 */
const WEEK: CloudUsage = {
  provider: 'ollama-cloud',
  range: '7d',
  total: 8341,
  fetchedAt: '2026-10-15T13:17:00Z',
  buckets: [
    ['2026-10-08', '2026-10-09', 952],
    ['2026-10-09', '2026-10-10', 1137],
    ['2026-10-10', '2026-10-11', 1352],
    ['2026-10-11', '2026-10-12', 698],
    ['2026-10-12', '2026-10-13', 457],
    ['2026-10-13', '2026-10-14', 1430],
    ['2026-10-14', '2026-10-15', 1533],
  ].map(([from, until, n]) => ({ from: `${from}T00:00:00Z`, until: `${until}T00:00:00Z`, requestCount: n as number, partial: false }))
    .concat([{ from: '2026-10-15T00:00:00Z', until: '2026-10-15T13:16:46.941696596Z', requestCount: 782, partial: true }]),
}

const DAY: CloudUsage = {
  provider: 'ollama-cloud',
  range: '24h',
  total: 120,
  fetchedAt: '2026-10-15T13:17:00Z',
  buckets: [
    { from: '2026-10-15T11:00:00Z', until: '2026-10-15T12:00:00Z', requestCount: 100, partial: false },
    { from: '2026-10-15T12:00:00Z', until: '2026-10-15T13:16:46Z', requestCount: 20, partial: true },
  ],
}

function empty(range: string): CloudUsage {
  return { provider: 'ollama-cloud', range, total: 0, buckets: [], fetchedAt: null }
}

let readings: Record<string, CloudUsage>
let requested: string[]
/** Runs before each answer: a test holds the reply back, or fails it, from here. */
let beforeAnswer: () => Promise<void>

registerEndpoint('/api/router/usage/ollama-cloud', async (event) => {
  const range = String(getQuery(event).range)
  requested.push(range)
  await beforeAnswer()
  return readings[range] ?? empty(range)
})

// A mount left from an earlier test keeps its fetch keys alive and answers visibilitychange.
enableAutoUnmount(afterEach)

beforeEach(() => {
  clearNuxtData()
  readings = { '24h': DAY, '7d': WEEK, '30d': empty('30d') }
  requested = []
  beforeAnswer = async () => {}
})

describe('Cloud usage block (Settings → LLM Providers → Ollama Cloud)', () => {
  it('draws one bar per bucket with its count, marks the running one, and shows the total', async () => {
    const c = await mountSuspended(OllamaCloudUsage)
    await flushPromises()
    const block = c.find('[data-testid="ollama-cloud-usage"]')
    expect(block.text()).toContain('Cloud usage')
    expect(c.find('[data-testid="ollama-cloud-usage-total"]').text()).toBe('Total 8,341 requests')
    const bars = block.findAll('[aria-hidden="true"]')
    expect(bars).toHaveLength(8)
    expect(block.text()).toContain('1,533')
    expect(block.text()).toContain('782 · in progress')
    expect(block.text().match(/in progress/g)).toHaveLength(1)
    expect(requested).toEqual(['7d'])
  })

  it('scales the busiest bucket to the full width', async () => {
    const c = await mountSuspended(OllamaCloudUsage)
    await flushPromises()
    const widths = c.findAll('[aria-hidden="true"] > div').map(d => Number.parseFloat((d.element as HTMLElement).style.width))
    expect(widths).toHaveLength(8)
    expect(widths[6]).toBeCloseTo(100, 2)
    expect(widths[0]).toBeCloseTo((952 / 1533) * 100, 1)
  })

  it('reads the range the selector names', async () => {
    const c = await mountSuspended(OllamaCloudUsage)
    await flushPromises()
    const button = c.findAll('button').find(b => b.text() === '24h')!
    await button.trigger('click')
    await vi.waitFor(() => expect(requested).toContain('24h'))
    await flushPromises()
    expect(button.attributes('aria-pressed')).toBe('true')
    expect(c.find('[data-testid="ollama-cloud-usage-total"]').text()).toBe('Total 120 requests')
    expect(c.text()).toContain('20 · in progress')
  })

  it('shows a loading state until the reading arrives', async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => {
      release = resolve
    })
    beforeAnswer = () => gate
    const c = await mountSuspended(OllamaCloudUsage)
    expect(c.text()).toContain('Loading usage…')
    release()
    await vi.waitFor(() => expect(c.text()).toContain('Total 8,341 requests'))
    expect(c.text()).not.toContain('Loading usage…')
  })

  it('offers a retry when ollama.com gave no reading, and the retry reads again', async () => {
    readings['7d'] = empty('7d')
    const c = await mountSuspended(OllamaCloudUsage)
    await flushPromises()
    const alert = c.find('[role="alert"]')
    expect(alert.text()).toContain('Could not read usage from Ollama Cloud.')
    expect(c.find('[data-testid="ollama-cloud-usage-total"]').exists()).toBe(false)

    readings['7d'] = WEEK
    await alert.find('button').trigger('click')
    await vi.waitFor(() => expect(c.find('[data-testid="ollama-cloud-usage-total"]').exists()).toBe(true))
    expect(requested).toEqual(['7d', '7d'])
  })

  it('offers a retry when the request itself fails', async () => {
    beforeAnswer = async () => {
      throw createError({ statusCode: 502, statusMessage: 'Bad Gateway' })
    }
    const c = await mountSuspended(OllamaCloudUsage)
    await flushPromises()
    await vi.waitFor(() => expect(c.find('[role="alert"]').exists()).toBe(true))
    expect(c.find('[role="alert"]').text()).toContain('Retry')
  })
})

describe('Ollama Cloud requests (Dashboard → Chat Cost)', () => {
  function stubVisibility(state: 'visible' | 'hidden') {
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
    document.dispatchEvent(new Event('visibilitychange'))
  }

  it('shows the last 24 hours and 7 days', async () => {
    const c = await mountSuspended(OllamaCloudRequests)
    await flushPromises()
    const card = c.find('[data-testid="ollama-cloud-requests"]')
    expect(card.text()).toContain('Ollama Cloud requests')
    expect(card.text()).toContain('120last 24 hours')
    expect(card.text()).toContain('8,341last 7 days')
  })

  it('renders nothing without a reading', async () => {
    readings = {}
    const c = await mountSuspended(OllamaCloudRequests)
    await flushPromises()
    expect(c.find('[data-testid="ollama-cloud-requests"]').exists()).toBe(false)
  })

  it('reads on load and every 5 minutes while visible, and not while the tab is hidden', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    try {
      stubVisibility('visible')
      await mountSuspended(OllamaCloudRequests)
      await flushPromises()
      expect(requested.sort()).toEqual(['24h', '7d'])

      requested = []
      vi.advanceTimersByTime(5 * 60 * 1000)
      await flushPromises()
      expect(requested.sort()).toEqual(['24h', '7d'])

      requested = []
      stubVisibility('hidden')
      vi.advanceTimersByTime(15 * 60 * 1000)
      await flushPromises()
      expect(requested).toEqual([])

      stubVisibility('visible')
      await flushPromises()
      expect(requested.sort()).toEqual(['24h', '7d'])
    }
    finally {
      vi.useRealTimers()
      Reflect.deleteProperty(document, 'visibilityState')
    }
  })
})
