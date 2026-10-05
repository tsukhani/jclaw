import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import { createError } from 'h3'
import SettingsSoftwareFactoryPanel from '~/components/settings/SettingsSoftwareFactoryPanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'
import { elapsed, groupStories, mergedLabel, type BoardStory, type StatusView } from '~/components/settings/factory-board'

/**
 * The Software Factory panel past Setup (JCLAW-1394): status strip and its confirmations, the
 * settings form, the grouped story board and the per-story log poll.
 */

const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsSoftwareFactoryPanel), h(ConfirmDialog)])
  },
})

function story(over: Partial<BoardStory> & { key: string, state: BoardStory['state'] }): BoardStory {
  return {
    summary: `Summary of ${over.key}`, source: 'jira', autoMerge: false, since: new Date(Date.now() - 125_000).toISOString(),
    reason: null, phase: null, phaseStartedAt: null, sha: null, by: null, logs: [], ...over,
  }
}

function stories(): BoardStory[] {
  return [
    story({ key: 'JCLAW-1', state: 'waiting', reason: 'blocked by JCLAW-9' }),
    story({ key: 'JCLAW-2', state: 'running', phase: 'implement', autoMerge: true, logs: ['JCLAW-2.log', 'JCLAW-2-review.log'] }),
    story({ key: 'JCLAW-3', state: 'review' }),
    story({ key: 'JCLAW-4', state: 'blocked', reason: 'won\'t do' }),
    story({ key: 'JCLAW-5', state: 'refused', reason: 'gate failed', autoMerge: true }),
    story({ key: 'JCLAW-6', state: 'merged', by: 'factory', sha: '0123456789abcdef', autoMerge: true }),
    story({ key: 'JCLAW-7', state: 'merged', by: 'operator', sha: 'fedcba9876543210' }),
    story({ key: 'JCLAW-8', state: 'merged', by: 'factory', sha: null }),
  ]
}

let status: StatusView
let statusGets = 0
let logGets = 0
let posts: string[] = []
let puts: unknown[] = []
let statusFails = false
let statusGate: Promise<void> | null = null
let refuseSettings = false

function installed(over: Partial<StatusView> = {}): StatusView {
  return {
    installed: true, supported: true, reason: null,
    harness: { state: 'running', pid: 42 }, gateway: { state: 'running' },
    sandboxes: [{ name: 'sandcastle-jclaw-2', story: 'JCLAW-2', upTime: '12 minutes' }],
    board: { schema: 1, updatedAt: '2026-10-05T10:00:00Z', harness: { pid: 42, startedAt: '2026-10-05T09:00:00Z', main: null }, settings: null, stories: stories() },
    boardReason: null,
    ...over,
  }
}

const SETTINGS = [
  { key: 'FACTORY_MAX_PARALLEL', value: '2', defaultValue: '2', set: false },
  { key: 'FACTORY_CPUS', value: '6', defaultValue: '6', set: false },
  { key: 'FACTORY_POLL_SECONDS', value: '120', defaultValue: '120', set: false },
  { key: 'FACTORY_MODEL', value: 'claude-opus-5-5', defaultValue: 'claude-opus-5-5', set: false },
]

registerEndpoint('/api/factory', { method: 'GET', handler: async () => {
  statusGets++
  if (statusGate) await statusGate
  if (statusFails) throw createError({ statusCode: 500, statusMessage: 'probe failed' })
  return status
} })
registerEndpoint('/api/factory/setup', { method: 'GET', handler: () => ({
  installed: true, prerequisites: [], hasModelCredential: true, hasJira: true, hasGithub: false, installJobId: null, message: null,
}) })
registerEndpoint('/api/factory/settings', { method: 'GET', handler: () => ({ settings: SETTINGS, message: null }) })
registerEndpoint('/api/factory/settings', { method: 'PUT', handler: async (event) => {
  const { readBody } = await import('h3')
  puts.push(await readBody(event))
  if (refuseSettings) throw createError({ statusCode: 400, statusMessage: 'FACTORY_CPUS is more than the 4 CPUs Docker has.' })
  return { settings: SETTINGS, message: 'Saved; applies once the factory is idle.' }
} })
for (const path of ['harness/start', 'harness/stop', 'gateway/pause', 'gateway/resume', 'sandboxes/sandcastle-jclaw-2/stop']) {
  registerEndpoint(`/api/factory/${path}`, { method: 'POST', handler: () => {
    posts.push(path)
    return path === 'harness/stop'
      ? { exitCode: 1, timedOut: false, output: 'launchctl: no such service', message: 'Harness stop failed.' }
      : { exitCode: 0, timedOut: false, output: '', message: `Done: ${path}.` }
  } })
}
registerEndpoint('/api/factory/stories/JCLAW-2/logs/JCLAW-2.log', { method: 'GET', handler: () => {
  logGets++
  return { key: 'JCLAW-2', file: 'JCLAW-2.log', size: 10, modifiedAt: '2026-10-05T10:00:00Z', truncated: false, text: `tail ${logGets}` }
} })

registerEndpoint('/api/factory/stories/JCLAW-2/logs/JCLAW-2-review.log', { method: 'GET', handler: () => {
  logGets++
  throw createError({ statusCode: 404, statusMessage: 'JCLAW-2-review.log is not on the board.' })
} })

function dialogButton(label: string): HTMLButtonElement | null {
  const buttons = [...document.querySelectorAll('[role="dialog"] button')]
  return (buttons.find(b => (b.textContent ?? '').trim() === label) ?? null) as HTMLButtonElement | null
}

async function settle() {
  for (let i = 0; i < 4; i++) await flushPromises()
}

// Unmounted after each test: a live panel keeps its status interval and visibility listener.
let mounted: Array<{ unmount: () => void }> = []

async function mount() {
  const c = await mountSuspended(Harness)
  mounted.push(c)
  await settle()
  return c
}

function setVisibility(state: 'visible' | 'hidden') {
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => state })
  document.dispatchEvent(new Event('visibilitychange'))
}

beforeEach(() => {
  clearNuxtData()
  status = installed()
  statusGets = 0
  logGets = 0
  posts = []
  puts = []
  statusFails = false
  statusGate = null
  refuseSettings = false
})

afterEach(() => {
  for (const c of mounted) c.unmount()
  mounted = []
  vi.useRealTimers()
  Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'visible' })
})

describe('factory-board helpers', () => {
  it('groups in board order, with refused under Blocked and empty groups kept', () => {
    const groups = groupStories(stories())
    expect(groups.map(g => [g.title, g.stories.map(s => s.key)])).toEqual([
      ['Waiting', ['JCLAW-1']],
      ['Running', ['JCLAW-2']],
      ['In review', ['JCLAW-3']],
      ['Blocked or refused', ['JCLAW-4', 'JCLAW-5']],
      ['Merged', ['JCLAW-6', 'JCLAW-7', 'JCLAW-8']],
    ])
    expect(groupStories([])[0]!.stories).toEqual([])
  })

  it('labels who merged and shortens elapsed time', () => {
    expect(mergedLabel(story({ key: 'A', state: 'merged', by: 'factory' }))).toBe('Auto-merged')
    expect(mergedLabel(story({ key: 'A', state: 'merged', by: 'operator' }))).toBe('Merged by hand')
    const t0 = Date.parse('2026-10-05T10:00:00Z')
    expect(elapsed('2026-10-05T10:00:00Z', t0 + 45_000)).toBe('45s')
    expect(elapsed('2026-10-05T10:00:00Z', t0 + 12 * 60_000)).toBe('12m')
    expect(elapsed('2026-10-05T10:00:00Z', t0 + 185 * 60_000)).toBe('3h 5m')
    expect(elapsed('2026-10-05T10:00:00Z', t0 + 52 * 3600_000)).toBe('2d 4h')
    expect(elapsed('not a date', t0)).toBe('')
  })
})

describe('Settings — Software Factory panel', () => {
  it('shows only Setup when the factory is not installed', async () => {
    status = installed({ installed: false })
    const c = await mount()
    expect(c.find('[data-testid="factory-setup"]').exists()).toBe(true)
    expect(c.find('[data-testid="factory-status"]').exists()).toBe(false)
    expect(c.find('[data-testid="factory-settings"]').exists()).toBe(false)
    expect(c.find('[data-testid="factory-board"]').exists()).toBe(false)
  })

  it('says why in place of the strip when unsupported, and still renders settings and board', async () => {
    status = installed({ supported: false, reason: 'The factory runs only on macOS.' })
    const c = await mount()
    expect(c.find('[data-testid="factory-unsupported"]').text()).toBe('The factory runs only on macOS.')
    expect(c.find('[data-testid="factory-status"]').exists()).toBe(false)
    expect(c.find('[data-testid="factory-settings"]').exists()).toBe(true)
    expect(c.find('[data-testid="factory-board"]').exists()).toBe(true)
  })

  it('shows the board reason when there is no board, and Docker\'s silence for null sandboxes', async () => {
    status = installed({ board: null, boardReason: 'board.json is absent.', sandboxes: null })
    const c = await mount()
    expect(c.find('[data-testid="factory-board-reason"]').text()).toBe('board.json is absent.')
    expect(c.find('[data-testid="factory-sandboxes"]').text()).toContain('Docker could not be asked')
  })

  it('groups the board with reasons, phase, elapsed time and merge labels', async () => {
    const c = await mount()
    const text = (id: string) => c.find(`[data-testid="factory-group-${id}"]`).text()
    expect(text('waiting')).toContain('Waiting (1)')
    expect(text('waiting')).toContain('blocked by JCLAW-9')
    expect(text('running')).toContain('implement · 2m')
    expect(text('review')).toContain('JCLAW-3')
    expect(text('blocked')).toContain('Blocked or refused (2)')
    expect(text('blocked')).toContain('won\'t do')
    expect(text('blocked')).toContain('gate failed')
    const merged = c.find('[data-testid="factory-group-merged"]')
    expect(merged.find('[data-testid="factory-story-JCLAW-6"]').text()).toContain('Auto-merged · 01234567')
    expect(merged.find('[data-testid="factory-story-JCLAW-6"] [title="0123456789abcdef"]').exists()).toBe(true)
    expect(merged.find('[data-testid="factory-story-JCLAW-7"]').text()).toContain('Merged by hand · fedcba98')
    expect(merged.find('[data-testid="factory-story-JCLAW-8"]').text()).toContain('Auto-merged')
    expect(merged.find('[data-testid="factory-story-JCLAW-8"] .font-mono[title]').exists()).toBe(false)
  })

  it('badges Auto-merge on exactly the stories that carry it', async () => {
    const c = await mount()
    const badged = stories().map(s => s.key)
      .filter(k => c.find(`[data-testid="factory-story-${k}"] [data-testid="factory-automerge-badge"]`).exists())
    expect(badged).toEqual(['JCLAW-2', 'JCLAW-5', 'JCLAW-6'])
  })

  it.each([
    ['factory-harness-stop', 'Stop', 'harness/stop', 'Stories running now will fail'],
    ['factory-gateway-pause', 'Pause', 'gateway/pause', 'stories running now will fail'],
    ['factory-sandbox-stop-sandcastle-jclaw-2', 'Stop', 'sandboxes/sandcastle-jclaw-2/stop', 'JCLAW-2? The story running in it will fail'],
  ])('%s asks first: cancel sends nothing, confirm posts', async (testid, confirmLabel, path, warning) => {
    const c = await mount()
    await c.find(`[data-testid="${testid}"]`).trigger('click')
    await settle()
    expect(document.querySelector('[role="dialog"]')?.textContent).toContain(warning)
    dialogButton('Cancel')!.click()
    await settle()
    expect(posts).toEqual([])

    const before = statusGets
    await c.find(`[data-testid="${testid}"]`).trigger('click')
    await settle()
    dialogButton(confirmLabel)!.click()
    await settle()
    expect(posts).toEqual([path])
    await vi.waitFor(() => expect(statusGets).toBeGreaterThan(before))
  })

  it('shows the message and output tail of a failed control action', async () => {
    const c = await mount()
    await c.find('[data-testid="factory-harness-stop"]').trigger('click')
    await settle()
    dialogButton('Stop')!.click()
    await settle()
    const result = c.find('[data-testid="factory-action-result"]')
    expect(result.text()).toContain('Harness stop failed.')
    expect(result.text()).toContain('launchctl: no such service')
  })

  it('starts the harness and resumes the gateway without a dialog', async () => {
    status = installed({ harness: { state: 'stopped', pid: null }, gateway: { state: 'exited' } })
    const c = await mount()
    expect(c.find('[data-testid="factory-gateway"]').text()).toContain('paused')
    await c.find('[data-testid="factory-harness-start"]').trigger('click')
    await settle()
    await c.find('[data-testid="factory-gateway-resume"]').trigger('click')
    await settle()
    expect(document.querySelector('[role="dialog"]')).toBeNull()
    expect(posts).toEqual(['harness/start', 'gateway/resume'])
    expect(c.text()).toContain('Done: gateway/resume.')
  })

  it('offers no control for an unknown harness or an absent gateway', async () => {
    status = installed({ harness: { state: 'unknown', pid: null }, gateway: { state: 'absent' } })
    const c = await mount()
    expect(c.find('[data-testid="factory-harness"] button').exists()).toBe(false)
    expect(c.find('[data-testid="factory-gateway"] button').exists()).toBe(false)
    expect(c.find('[data-testid="factory-gateway"]').text()).toContain('absent')
  })

  it('validates settings, accepting 1 beside 0, and PUTs only the changed keys as strings', async () => {
    const c = await mount()
    expect(c.find('[data-testid="factory-settings"]').text()).toContain('Changes apply once the factory is idle.')
    const save = c.find('[data-testid="factory-settings-save"]')
    const cpus = c.find('[data-testid="factory-setting-FACTORY_CPUS"]')
    expect(save.attributes('disabled')).toBeDefined()

    for (const bad of ['0', 'x', '']) {
      await cpus.setValue(bad)
      expect(c.find('[data-testid="factory-setting-error-FACTORY_CPUS"]').exists(), bad).toBe(true)
      expect(save.attributes('disabled'), bad).toBeDefined()
    }
    await cpus.setValue('1')
    expect(c.find('[data-testid="factory-setting-error-FACTORY_CPUS"]').exists()).toBe(false)
    expect(save.attributes('disabled')).toBeUndefined()

    const model = c.find('[data-testid="factory-setting-FACTORY_MODEL"]')
    for (const bad of ['a b', ' ']) {
      await model.setValue(bad)
      expect(c.find('[data-testid="factory-setting-error-FACTORY_MODEL"]').exists(), bad).toBe(true)
      expect(save.attributes('disabled'), bad).toBeDefined()
    }
    await model.setValue('claude-opus-5-5')
    await cpus.setValue('6')
    expect(save.attributes('disabled')).toBeDefined()

    await cpus.setValue('4')
    await c.find('[data-testid="factory-settings"]').trigger('submit')
    await vi.waitFor(() => expect(puts).toEqual([{ FACTORY_CPUS: '4' }]))
    expect(c.find('[data-testid="factory-settings"]').text()).toContain('Saved; applies once the factory is idle.')
  })

  it('shows No logs yet without a request for a story with no logs', async () => {
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-3"]').trigger('click')
    await settle()
    expect(c.find('[data-testid="factory-story-JCLAW-3"]').attributes('aria-pressed')).toBe('true')
    expect(c.find('[data-testid="factory-story-view"]').text()).toContain('No logs yet.')
    expect(logGets).toBe(0)
  })

  it('polls a running story\'s log every 3 s and stops once the board shows it not running', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    expect(c.findAll('[role="tab"]').map(t => t.text())).toEqual(['JCLAW-2.log', 'JCLAW-2-review.log'])
    expect(logGets).toBe(1)
    expect(c.find('[data-testid="factory-log-text"]').text()).toBe('tail 1')

    await vi.advanceTimersByTimeAsync(3_000)
    await settle()
    expect(logGets).toBe(2)
    expect(c.find('[data-testid="factory-log-text"]').text()).toBe('tail 2')

    status = installed({ board: { ...installed().board!, stories: stories().map(s => s.key === 'JCLAW-2' ? { ...s, state: 'review' as const } : s) } })
    await vi.advanceTimersByTimeAsync(2_000)
    await settle()
    expect(c.find('[data-testid="factory-group-review"]').text()).toContain('JCLAW-2')
    await vi.advanceTimersByTimeAsync(10_000)
    await settle()
    expect(logGets).toBe(2)
    expect(c.find('[data-testid="factory-story-view"]').exists()).toBe(true)
  })

  it('stops polling the status while the document is hidden and refreshes on return', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    await mount()
    const afterMount = statusGets
    await vi.advanceTimersByTimeAsync(5_000)
    await settle()
    expect(statusGets).toBe(afterMount + 1)

    setVisibility('hidden')
    await vi.advanceTimersByTimeAsync(20_000)
    await settle()
    expect(statusGets).toBe(afterMount + 1)

    setVisibility('visible')
    await settle()
    expect(statusGets).toBe(afterMount + 2)
    await vi.advanceTimersByTimeAsync(5_000)
    await settle()
    expect(statusGets).toBe(afterMount + 3)
  })

  it('shows Setup and a loading line until the status arrives, with no strip, form or board', async () => {
    let open!: () => void
    statusGate = new Promise(r => (open = r))
    const c = await mount()
    expect(c.find('[data-testid="factory-status-pending"]').text()).toBe('Loading…')
    expect(c.text()).toContain('Setup')
    for (const id of ['factory-status', 'factory-settings', 'factory-board']) {
      expect(c.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
    }
    open()
    await settle()
    expect(c.find('[data-testid="factory-status"]').exists()).toBe(true)
  })

  it('says it could not read the status when the request fails, and shows Setup alone', async () => {
    statusFails = true
    const c = await mount()
    expect(c.find('[data-testid="factory-status-pending"]').text()).toBe('Could not read the factory status.')
    expect(c.text()).toContain('Setup')
    for (const id of ['factory-status', 'factory-settings', 'factory-board']) {
      expect(c.find(`[data-testid="${id}"]`).exists(), id).toBe(false)
    }
  })

  it.each(['paused', 'created'])('offers Resume for a %s gateway', async (state) => {
    status = installed({ gateway: { state } })
    const c = await mount()
    expect(c.find('[data-testid="factory-gateway-resume"]').exists()).toBe(true)
    expect(c.find('[data-testid="factory-gateway-pause"]').exists()).toBe(false)
  })

  it('shows an unknown gateway\'s state with no control', async () => {
    status = installed({ gateway: { state: 'unknown' } })
    const c = await mount()
    expect(c.find('[data-testid="factory-gateway"] button').exists()).toBe(false)
    expect(c.find('[data-testid="factory-gateway"]').text()).toContain('unknown')
  })

  it('accepts 6 CPUs and shows the server\'s refusal of a saved value', async () => {
    refuseSettings = true
    const c = await mount()
    const cpus = c.find('[data-testid="factory-setting-FACTORY_CPUS"]')
    await cpus.setValue('6')
    expect(c.find('[data-testid="factory-setting-error-FACTORY_CPUS"]').exists()).toBe(false)
    await cpus.setValue('8')
    await c.find('[data-testid="factory-settings"]').trigger('submit')
    await vi.waitFor(() => expect(puts).toEqual([{ FACTORY_CPUS: '8' }]))
    await settle()
    expect(c.find('[data-testid="factory-settings"]').text()).toContain('FACTORY_CPUS is more than the 4 CPUs Docker has.')
  })

  it('shows a rotated log\'s 404 in its tab and keeps polling while the story runs', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const c = await mount()
    await c.find('[data-testid="factory-story-JCLAW-2"]').trigger('click')
    await settle()
    await c.find('[data-testid="factory-log-tab-JCLAW-2-review.log"]').trigger('click')
    await settle()
    const view = c.find('[data-testid="factory-story-view"]')
    expect(view.text()).toContain('JCLAW-2-review.log is not on the board.')
    expect(view.find('[data-testid="factory-log-text"]').exists()).toBe(false)
    const after = logGets
    await vi.advanceTimersByTimeAsync(3_000)
    await settle()
    expect(logGets).toBe(after + 1)
  })
})
