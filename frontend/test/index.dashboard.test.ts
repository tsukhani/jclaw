import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { defineComponent, h } from 'vue'
import Index from '~/pages/index.vue'

/**
 * JCLAW-236 — Dashboard Recent Activity segmented All/Video toggle (Option B). The default view is the
 * EventLog feed; switching to the video view lazily loads VideoGenerationJob rows from
 * /api/videogen/jobs/recent and renders a job-status table with a "see in conversation" deep-link.
 */

function setupApi(opts?: { events?: unknown[], videoJobs?: unknown[], latency?: unknown, workspaceBytes?: number }) {
  registerEndpoint('/api/agents', () => [])
  registerEndpoint('/api/channels/active', () => ({ count: 0, channelTypes: [] }))
  registerEndpoint('/api/tasks', () => [])
  registerEndpoint('/api/conversations', () => [])
  registerEndpoint('/api/metrics/latency/rows', () => opts?.latency ?? ({ since: '', channels: [], segments: {} }))
  registerEndpoint('/api/logs', () => ({ events: opts?.events ?? [] }))
  registerEndpoint('/api/videogen/jobs/recent', () => opts?.videoJobs ?? [])
  registerEndpoint('/api/workspace/stats', () => ({ bytes: opts?.workspaceBytes ?? 2048 }))
}

// The Chat Cost / Compression sections own their own fetches; stub them so the test only exercises
// the Recent Activity panel.
const STUBS = { ChatCostSection: true, ChatCompressionSection: true }

describe('Dashboard — Recent Activity video toggle (JCLAW-236)', () => {
  beforeEach(() => clearNuxtData())

  it('defaults to the activity feed and switches to the video job table on toggle', async () => {
    setupApi({
      events: [{ id: 1, level: 'INFO', category: 'tool/INFO', agentId: 'main', message: 'Executing tool foo', timestamp: '2026-06-26T00:03:29Z' }],
      videoJobs: [{ id: 7, state: 'SUCCEEDED', prompt: 'a comet over a city', percent: null, errorMessage: null, conversationId: 42, createdAt: '2026-06-26T00:00:00Z' }],
    })
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    // Default = events feed.
    expect(c.text()).toContain('Recent Activity')
    expect(c.text()).toContain('Executing tool foo')
    expect(c.text()).not.toContain('a comet over a city')

    // Switch to the video view.
    const videoTab = c.find('button[title="Video jobs"]')
    expect(videoTab.exists()).toBe(true)
    await videoTab.trigger('click')
    await flushPromises()

    expect(c.text()).toContain('a comet over a city')
    expect(c.text()).toContain('SUCCEEDED')
    expect(c.find('a[href="/chat?conversation=42"]').exists()).toBe(true)
    // The events feed is hidden while the video view is active.
    expect(c.text()).not.toContain('Executing tool foo')
  })

  it('shows the empty state when the video view has no jobs', async () => {
    setupApi({ videoJobs: [] })
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    await c.find('button[title="Video jobs"]').trigger('click')
    await flushPromises()

    expect(c.text()).toContain('No video generation jobs yet.')
  })
})

describe('Dashboard — Recent Activity scrapes view (JCLAW-1273)', () => {
  beforeEach(() => clearNuxtData())

  it('loads scrape jobs only when their view opens, and polls them only while it shows', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    try {
      setupApi()
      let calls = 0
      registerEndpoint('/api/scrape-jobs', () => {
        calls += 1
        return [{
          id: 12, agentId: 1, agentName: 'main', conversationId: null, url: 'https://docs.example.test/', state: 'RUNNING',
          pagesRead: 8, pagesFetched: 8, pagesDiscovered: 30, stopReason: null, errorMessage: null, summary: null,
          folder: 'scrapes/12', combinedFile: null,
          options: { url: 'https://docs.example.test/', maxPages: 500, maxDepth: 2, maxMinutes: 60, sameHostOnly: true,
            respectRobots: true, seedFromSitemap: true, language: 'en', format: 'markdown', metadata: false },
          runtimeSeconds: 40, interruptions: 0, createdAt: '2026-09-22T10:00:00Z', startedAt: null, completedAt: null,
        }]
      })
      // The 5 s tick refreshes Chat Cost too, so its stub needs the refresh the page calls.
      const costStub = defineComponent({
        setup(_, { expose }) {
          expose({ refresh: async () => {} })
          return () => h('div')
        },
      })
      const c = await mountSuspended(Index, { global: { stubs: { ...STUBS, ChatCostSection: costStub } } })
      await flushPromises()
      vi.advanceTimersByTime(5000)
      await flushPromises()
      expect(calls).toBe(0)

      await c.find('[data-testid="activity-scrapes-tab"]').trigger('click')
      await flushPromises()
      expect(calls).toBe(1)
      const rows = c.find('[data-testid="activity-scrapes"]')
      expect(rows.text()).toContain('Running')
      expect(rows.find('a[href="/scrapes/12"]').text()).toContain('docs.example.test')

      vi.advanceTimersByTime(5000)
      await flushPromises()
      expect(calls).toBe(2)

      await c.find('button[title="All activity"]').trigger('click')
      vi.advanceTimersByTime(10000)
      await flushPromises()
      expect(calls).toBe(2)
      c.unmount()
    }
    finally {
      vi.useRealTimers()
    }
  })
})

describe('Dashboard — Chat Performance latency filters (JCLAW-515)', () => {
  beforeEach(() => clearNuxtData())

  it('renders the windowed latency panel with 7d/30d/All + agent + channel filters', async () => {
    const hist = (p50: number) => ({
      count: 10, sum_ms: 1000, min_ms: 50, max_ms: 200,
      p50_ms: p50, p90_ms: 180, p99_ms: 200, p999_ms: 200, buckets: [],
    })
    setupApi({
      latency: {
        since: '2026-06-01T00:00:00Z',
        channels: ['web', 'telegram'],
        segments: { total: hist(100), ttft: hist(30) },
      },
    })
    // Chat Cost / Compression also carry a 7d/30d/All control, so stub them — the only
    // window buttons left are the Chat Performance panel's.
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    expect(c.text()).toContain('Chat Performance')
    expect(c.text()).not.toContain('No latency samples in this window')

    const buttonLabels = c.findAll('button').map(b => b.text())
    expect(buttonLabels).toContain('7d')
    expect(buttonLabels).toContain('30d')
    expect(buttonLabels).toContain('All')

    expect(c.find('select[aria-label="Filter by agent"]').exists()).toBe(true)
    const channelSelect = c.find('select[aria-label="Filter by channel"]')
    expect(channelSelect.exists()).toBe(true)
    const channelOpts = channelSelect.findAll('option').map(o => (o.element as HTMLOptionElement).value)
    expect(channelOpts).toContain('web')
    expect(channelOpts).toContain('telegram')
  })

  it('hides count metrics from the latency table and shows them in the counts view (JCLAW-884)', async () => {
    const hist = (p50: number, sum = 1000) => ({
      count: 10, sum_ms: sum, min_ms: p50, max_ms: p50,
      p50_ms: p50, p90_ms: p50, p99_ms: p50, p999_ms: p50, buckets: [],
    })
    setupApi({
      latency: {
        since: '2026-06-01T00:00:00Z',
        channels: ['web'],
        segments: {
          llm_call_count: hist(3, 40),
          llm_call_cached: hist(1, 10),
          ttft: hist(30),
          total: hist(100),
        },
      },
    })
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    // Default (latency) view: durations only — Total summarises what is above it.
    // "text", not "token": TTFT now names the wait until the model emits anything,
    // and this segment is the narrower one that stops at the first visible token.
    expect(c.text()).toContain('Time to first text')
    expect(c.text()).not.toContain('LLM calls / turn')

    // Switching to the counts view surfaces them, with the cache share derived
    // from summed calls (10/40 = 25%), not from a percentile comparison.
    const countsTab = c.findAll('button[role="tab"]').at(2)!
    await countsTab.trigger('click')
    await flushPromises()

    expect(c.text()).toContain('LLM calls / turn')
    expect(c.text()).toContain('25.0%')
    // A cardinality must never render through the ms formatter.
    expect(c.text()).not.toContain('3 ms')
  })
})

describe('Dashboard — workspace disk footprint line', () => {
  beforeEach(() => clearNuxtData())

  it('renders the bare value with the unit named in the label below the warn threshold', async () => {
    setupApi({ workspaceBytes: 2048 })
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    const value = c.find('[data-testid="workspace-size-value"]')
    expect(value.exists()).toBe(true)
    expect(value.text()).toBe('2.0')
    expect(value.classes()).not.toContain('text-amber-700')
    expect(c.find('[data-testid="workspace-size-label"]').text()).toBe('Size (in KB)')
  })

  it('turns amber and steps the unit up to GB past the 10 GiB warn threshold', async () => {
    // 30 GiB — the real incident size; the unit adapts so the value stays
    // small (30.0 + GB label, never 30720 + MB).
    setupApi({ workspaceBytes: 30 * 1024 ** 3 })
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    const value = c.find('[data-testid="workspace-size-value"]')
    expect(value.text()).toBe('30.0')
    expect(value.classes()).toContain('text-amber-700')
    expect(value.classes()).toContain('dark:text-amber-400')
    expect(c.find('[data-testid="workspace-size-label"]').text()).toBe('Size (in GB)')
  })

  it('hides the line when the walk failed (bytes = -1)', async () => {
    setupApi({ workspaceBytes: -1 })
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    expect(c.find('[data-testid="workspace-size"]').exists()).toBe(false)
  })
})

describe('Dashboard — a failed latency reset (JCLAW-1221)', () => {
  it('says why the latency metrics were not cleared', async () => {
    setupApi()
    const off = registerEndpoint('/api/metrics/latency/rows', {
      method: 'DELETE',
      handler: async (event) => {
        const { setResponseStatus } = await import('h3')
        setResponseStatus(event, 502)
        return '<html><body>Bad Gateway</body></html>'
      },
    })
    try {
      const component = await mountSuspended(Index)
      await flushPromises()
      await component.find('button[title="Clear latency metrics"]').trigger('click')
      await vi.waitFor(() => expect(component.find('[data-testid="api-error"]').exists()).toBe(true))
      expect(component.find('[data-testid="api-error"]').text()).toContain('/api/metrics/latency/rows')
    }
    finally {
      off()
    }
  })
})
