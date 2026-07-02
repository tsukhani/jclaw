import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
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
})

describe('Dashboard — workspace disk footprint line', () => {
  beforeEach(() => clearNuxtData())

  it('renders the size muted below the warn threshold', async () => {
    setupApi({ workspaceBytes: 2048 })
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    const value = c.find('[data-testid="workspace-size-value"]')
    expect(value.exists()).toBe(true)
    expect(value.text()).toBe('2.0 KB')
    expect(value.classes()).not.toContain('text-amber-500')
  })

  it('turns amber and formats GB past the 10 GiB warn threshold', async () => {
    // 30 GiB — the real incident size; also exercises formatSize's GB tier.
    setupApi({ workspaceBytes: 30 * 1024 ** 3 })
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    const value = c.find('[data-testid="workspace-size-value"]')
    expect(value.text()).toBe('30.0 GB')
    expect(value.classes()).toContain('text-amber-500')
  })

  it('hides the line when the walk failed (bytes = -1)', async () => {
    setupApi({ workspaceBytes: -1 })
    const c = await mountSuspended(Index, { global: { stubs: STUBS } })
    await flushPromises()

    expect(c.find('[data-testid="workspace-size"]').exists()).toBe(false)
  })
})
