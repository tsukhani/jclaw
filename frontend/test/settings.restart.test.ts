import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { mockNuxtImport, mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises, type DOMWrapper } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { clearNuxtData } from '#app'
import { setResponseStatus } from 'h3'
import SettingsRestartPanel from '~/components/settings/SettingsRestartPanel.vue'
import ConfirmDialog from '~/components/ConfirmDialog.vue'

/**
 * Settings → Restart panel. The reboot is irreversible from the operator's
 * seat, so what's covered first is the gate around it: the button is dead when
 * the install can't restart, the confirmation states what is about to be
 * interrupted, and a cancelled dialog issues no POST.
 *
 * The narration block then drives the reconnect poll one fake second at a time
 * against stubbed /api/status and progress answers: what each process serves at
 * each point of a restart is the input, and the lines the panel shows are the outcome.
 */

const { reloads } = vi.hoisted(() => ({ reloads: { count: 0 } }))
mockNuxtImport('reloadNuxtApp', () => () => {
  reloads.count += 1
})

/** Mount alongside ConfirmDialog so confirm() actually renders — the dialog
 *  reads useConfirm()'s module singleton, which lives at app root in prod. */
const Harness = defineComponent({
  setup() {
    return () => h('div', [h(SettingsRestartPanel), h(ConfirmDialog)])
  },
})

let preflight: Record<string, unknown>
let restartPosts = 0
let aotPosts: Array<Record<string, unknown>> = []

registerEndpoint('/api/system/restart', {
  method: 'GET',
  handler: () => preflight,
})
registerEndpoint('/api/system/restart', {
  method: 'POST',
  handler: () => {
    restartPosts += 1
    return { status: 'ok', mode: 'PROD', backendOnly: false, rebuildExpected: false }
  },
})

// Down is the narrator's 503; until a test says otherwise the old JVM is up and serves no progress.
let backendUp = true
let progress: Record<string, unknown> | null = null

// A stalled answer waits for release(): the old JVM accepts and takes its time.
let stall = false
let stalled: Array<() => void> = []
function release() {
  const waiting = stalled
  stalled = []
  waiting.forEach(resolve => resolve())
}
const stalling = () => stall ? new Promise<void>(resolve => stalled.push(resolve)) : Promise.resolve()

registerEndpoint('/api/status', {
  method: 'GET',
  handler: async (event) => {
    await stalling()
    if (backendUp) return { status: 'ok' }
    setResponseStatus(event, 503)
    return { type: 'error', code: 'restarting', message: 'JClaw is restarting' }
  },
})
registerEndpoint('/api/system/restart/progress', {
  method: 'GET',
  handler: async (event) => {
    await stalling()
    if (progress) return progress
    setResponseStatus(event, 503)
    return { type: 'error', code: 'restarting', message: 'JClaw is restarting' }
  },
})

registerEndpoint('/api/system/aot-cache', {
  method: 'POST',
  handler: async (event) => {
    const { readBody } = await import('h3')
    const body = await readBody(event) as Record<string, unknown>
    aotPosts.push(body)
    preflight = { ...preflight, aotCacheEnabled: body.enabled }
    return { status: 'ok', enabled: body.enabled }
  },
})

function available(over: Record<string, unknown> = {}) {
  return {
    available: true,
    unavailableReason: null,
    mode: 'PROD',
    backendOnly: false,
    rebuildExpected: false,
    runningTasks: 0,
    activeSubagentRuns: 0,
    aotCacheEnabled: true,
    aotCacheStatus: null,
    aotCacheStatusAt: null,
    container: false,
    ...over,
  }
}

/** The button carrying `label` inside the confirm dialog, or null. Scoped to
 *  [role=dialog] because the panel's own trigger is labelled 'Restart' too — an
 *  unscoped search matches it first and silently tests the wrong button. */
function dialogButton(label: string): HTMLButtonElement | null {
  const buttons = [...document.querySelectorAll('[role="dialog"] button')]
  return (buttons.find(b => (b.textContent ?? '').trim() === label) ?? null) as HTMLButtonElement | null
}

beforeEach(() => {
  clearNuxtData()
  restartPosts = 0
  aotPosts = []
  preflight = available()
  document.body.innerHTML = ''
  backendUp = true
  progress = null
  reloads.count = 0
  stall = false
  stalled = []
})

afterEach(() => {
  vi.useRealTimers()
})

describe('SettingsRestartPanel — availability', () => {
  it('disables the button and explains why when the install has no jclaw.sh', async () => {
    preflight = available({
      available: false,
      unavailableReason: 'jclaw.sh not found at /srv/jclaw/jclaw.sh — this installation is not managed by jclaw.sh.',
    })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('not managed by jclaw.sh')
    // A button that the POST would refuse anyway must not be clickable — the
    // preflight and the POST share one availability rule on the backend.
    expect(c.find('button').attributes('disabled')).toBeDefined()
  })

  it('enables the button and reports the mode when a restart is possible', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('Mode: PROD')
    expect(c.find('button').attributes('disabled')).toBeUndefined()
  })

  it('says the dev server survives a dev-mode restart', async () => {
    preflight = available({ mode: 'DEV', backendOnly: true })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('backend only')
  })

  it('says in a container that only a restart policy starts the container again', async () => {
    preflight = available({ container: true })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.find('[data-testid="restart-container-note"]').text()).toBe(
      'In a container, Restart stops the container, and it starts again only if the container has '
      + 'a restart policy. JClaw\'s Docker Compose file sets restart: unless-stopped; a plain docker run '
      + 'needs --restart unless-stopped.',
    )
  })

  it('shows no container note outside a container', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.text()).toContain('Mode: PROD')
    expect(c.find('[data-testid="restart-container-note"]').exists()).toBe(false)
  })

  it('flags a source checkout as maybe-recompiling, with no duration claim', async () => {
    preflight = available({ rebuildExpected: true })
    const c = await mountSuspended(Harness)
    await flushPromises()

    // rebuildExpected only means "this is a source tree" — jclaw.sh still gates
    // the precompile and SPA rebuild on staleness. The one-line summary states
    // the possibility and leaves duration to the dialog.
    expect(c.text()).toContain('may recompile')
    expect(c.text()).not.toMatch(/minutes|seconds/)
  })
})

describe('SettingsRestartPanel — confirmation', () => {
  it('names the in-flight work the restart will interrupt', async () => {
    preflight = available({ runningTasks: 2, activeSubagentRuns: 1 })
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button').trigger('click')
    await flushPromises()

    // The operator is confirming against these counts, so they have to be in
    // the dialog rather than merely available on the endpoint.
    const dialog = document.body.textContent ?? ''
    expect(dialog).toContain('2 task runs')
    expect(dialog).toContain('1 subagent run')
    expect(restartPosts).toBe(0)
  })

  it('says so plainly when nothing is in flight', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button').trigger('click')
    await flushPromises()

    expect(document.body.textContent ?? '').toContain('No task or subagent runs are currently active')
  })

  it('names what makes a source-checkout restart slow without inventing a duration', async () => {
    preflight = available({ rebuildExpected: true })
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button').trigger('click')
    await flushPromises()

    const dialog = document.body.textContent ?? ''
    expect(dialog).toContain('may recompile Java sources')
    expect(dialog).toContain('skips both when nothing changed')
    expect(dialog).toContain('well under a minute')

    // The real guard. Two prior versions of this copy asserted a slow-case
    // duration nobody had measured — "expect several minutes, not seconds",
    // then "can take several minutes" — and timed restarts came in at 48s and
    // 58s. Fail if a confident number reappears without one.
    expect(dialog).not.toMatch(/several minutes|take minutes|minutes, not seconds/)
  })

  it('issues no POST when the dialog is cancelled', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button').trigger('click')
    await flushPromises()
    dialogButton('Cancel')?.click()
    await flushPromises()

    expect(restartPosts).toBe(0)
  })

  it('POSTs and moves into the waiting state once confirmed', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('button').trigger('click')
    await flushPromises()
    dialogButton('Restart')?.click()
    await flushPromises()

    expect(restartPosts).toBe(1)
    // The button must latch — a second POST would race a JVM already inside
    // its shutdown hooks.
    expect(c.find('button').attributes('disabled')).toBeDefined()

    // The panel hands off, then waits. vi.waitFor because the ack has to make
    // a round trip through the mocked endpoint before the phase advances.
    await vi.waitFor(() => expect(c.text()).toContain('Waiting for the backend'))

    // Stop the reconnect poll this started; nothing here is serving /api/status.
    c.unmount()
  })
})

describe('SettingsRestartPanel — AOT cache', () => {
  it('shows the cache switched on, and what the last start did with it', async () => {
    preflight = available({ aotCacheStatus: 'in use', aotCacheStatusAt: '2026-10-21T13:31:00Z' })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.find('[data-testid="aot-cache-toggle"]').attributes('aria-checked')).toBe('true')
    expect(c.find('[data-testid="aot-cache-status"]').text()).toContain('Last start: in use')
  })

  it('says nothing about a last start before any start wrote one', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.find('[data-testid="aot-cache-status"]').exists()).toBe(false)
  })

  it('switches the cache off for the next start', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()

    await c.find('[data-testid="aot-cache-toggle"]').trigger('click')
    await vi.waitFor(() => expect(aotPosts).toEqual([{ enabled: false }]))
    await vi.waitFor(() =>
      expect(c.find('[data-testid="aot-cache-toggle"]').attributes('aria-checked')).toBe('false'))
    // Switching the cache is not a restart.
    expect(restartPosts).toBe(0)
  })

  it('warns in the confirmation that a rebuild of the cache adds time, only while it is on', async () => {
    const c = await mountSuspended(Harness)
    await flushPromises()
    await c.find('button').trigger('click')
    await flushPromises()
    expect(document.querySelector('[role="dialog"]')?.textContent)
      .toContain('A restart that has to rebuild the AOT cache takes about 30 seconds longer.')
    dialogButton('Cancel')?.click()
    await flushPromises()

    preflight = available({ aotCacheEnabled: false })
    clearNuxtData()
    document.body.innerHTML = ''
    const off = await mountSuspended(Harness)
    await flushPromises()
    await off.find('button').trigger('click')
    await flushPromises()
    expect(document.querySelector('[role="dialog"]')?.textContent).not.toContain('AOT cache')
    dialogButton('Cancel')?.click()
  })

  it('switches it back on when it is off', async () => {
    preflight = available({ aotCacheEnabled: false, aotCacheStatus: 'off (switched off in Settings)' })
    const c = await mountSuspended(Harness)
    await flushPromises()

    expect(c.find('[data-testid="aot-cache-toggle"]').attributes('aria-checked')).toBe('false')
    await c.find('[data-testid="aot-cache-toggle"]').trigger('click')
    await vi.waitFor(() => expect(aotPosts).toEqual([{ enabled: true }]))
  })
})

type Step = { id: string, label: string, state: 'done' | 'running' | 'failed', seconds: number }

function served(source: 'narrator' | 'jclaw', state: string, steps: Step[], extra: Record<string, unknown> = {}) {
  return { source, state, failure: null, steps, services: null, ...extra }
}

const step = (id: string, label: string, state: Step['state'], seconds: number): Step => ({ id, label, state, seconds })

/** Each line the panel narrates, as `label | seconds`. */
function lines(c: Awaited<ReturnType<typeof mountSuspended>>): string[] {
  return c.findAll('[data-testid="restart-steps"] li').map((li: DOMWrapper<Element>) => {
    const spans = li.findAll('span')
    return `${spans[0]!.text()} | ${spans[1]?.text() ?? ''}`
  })
}

/** One poll of the panel: a fake second, then the two answers it waits for. */
async function tick(times = 1) {
  for (let i = 0; i < times; i++) {
    await vi.advanceTimersByTimeAsync(1000)
    for (let j = 0; j < 6; j++) await flushPromises()
  }
}

async function restartConfirmed() {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
  const c = await mountSuspended(Harness)
  await flushPromises()
  await c.find('button').trigger('click')
  await flushPromises()
  dialogButton('Restart')?.click()
  await vi.waitFor(() => expect(restartPosts).toBe(1))
  await flushPromises()
  return c
}

describe('SettingsRestartPanel — narration', () => {
  it('narrates each phase with its time, then the services, and reloads once they settle', async () => {
    const c = await restartConfirmed()

    // The JVM about to stop serves this restart's first phase, beside the services it is about to stop.
    progress = served('jclaw', 'running', [step('stop', 'Stopping JClaw', 'running', 1)],
      { services: { channels: ['telegram'], mcpConnected: 1, mcpConfigured: 1, settled: true } })
    await tick()
    expect(lines(c)).toEqual(['Stopping JClaw… | 1s'])

    // It has gone: the narrator answers, and /api/status gets its 503.
    backendUp = false
    progress = served('narrator', 'running', [step('stop', 'Stopping JClaw', 'running', 5)])
    await tick()
    expect(lines(c)).toEqual(['Stopping JClaw… | 5s'])

    // The narrator steps aside for the JVM's launch.
    progress = null
    await tick()
    expect(lines(c)).toEqual(['Stopping JClaw | 5s', 'Starting JClaw (connecting channels)… | 0s'])
    await tick()
    expect(lines(c)).toEqual(['Stopping JClaw | 5s', 'Starting JClaw (connecting channels)… | 1s'])

    // JClaw answers with jclaw.sh's times and what it is still connecting.
    backendUp = true
    progress = served('jclaw', 'done', [step('stop', 'Stopping JClaw', 'done', 6), step('start', 'Starting JClaw (connecting channels)', 'done', 9)],
      { services: { channels: ['telegram', 'slack'], mcpConnected: 2, mcpConfigured: 4, settled: false } })
    await tick(2)
    expect(lines(c)).toEqual([
      'Stopping JClaw | 6s',
      'Starting JClaw (connecting channels) | 9s',
      'Channels running: Telegram, Slack | ',
      'MCP servers: 2 of 4 connected… | 0s',
    ])
    expect(reloads.count).toBe(0)

    progress = served('jclaw', 'done', [step('stop', 'Stopping JClaw', 'done', 6), step('start', 'Starting JClaw (connecting channels)', 'done', 9)],
      { services: { channels: ['telegram', 'slack'], mcpConnected: 4, mcpConfigured: 4, settled: true } })
    await tick()
    expect(reloads.count).toBe(1)
    expect(lines(c).at(-1)).toBe('MCP servers: 4 of 4 connected | ')
    c.unmount()
  })

  it('reloads without the services once their budget runs out', async () => {
    const c = await restartConfirmed()
    backendUp = false
    await tick()
    backendUp = true
    progress = served('jclaw', 'done', [step('stop', 'Stopping JClaw', 'done', 4)],
      { services: { channels: [], mcpConnected: 2, mcpConfigured: 4, settled: false } })

    // The second answer calls JClaw back, and the servers get 30 s from that answer on.
    await tick(1 + 30)
    expect(reloads.count).toBe(0)
    expect(lines(c)).toEqual(['Stopping JClaw | 4s', 'MCP servers: 2 of 4 connected… | 29s'])
    await tick()
    expect(reloads.count).toBe(1)
    c.unmount()
  })

  it('reloads at once when JClaw has nothing still connecting', async () => {
    const c = await restartConfirmed()
    backendUp = false
    await tick()
    backendUp = true
    progress = served('jclaw', 'done', [step('stop', 'Stopping JClaw', 'done', 4)],
      { services: { channels: [], mcpConnected: 0, mcpConfigured: 0, settled: true } })
    await tick(2)
    expect(reloads.count).toBe(1)
    c.unmount()
  })

  it('says the restart failed, with the reason the narrator serves, and stops waiting', async () => {
    const c = await restartConfirmed()
    backendUp = false
    progress = served('narrator', 'failed', [step('stop', 'Stopping JClaw', 'done', 5), step('start', 'Starting JClaw (connecting channels)', 'failed', 12)],
      { failure: 'JClaw did not start' })
    await tick()

    expect(c.find('[role="status"] > span').text()).toBe('The restart failed: JClaw did not start. See logs/restart.log.')
    expect(lines(c)).toEqual(['Stopping JClaw | 5s', 'Starting JClaw (connecting channels) | 12s'])
    expect(c.find('[data-state="failed"]').exists()).toBe(true)
    // The button is offered again, and nothing polls on.
    expect(c.find('button').attributes('disabled')).toBeUndefined()
    progress = null
    backendUp = true
    await tick(3)
    expect(reloads.count).toBe(0)
    c.unmount()
  })

  it('counts as it did before narration when nothing narrates the restart', async () => {
    const c = await restartConfirmed()
    // The JVM about to stop serves the previous restart's finished file, which is not this restart.
    progress = served('jclaw', 'done', [step('stop', 'Stopping JClaw', 'done', 3)])
    await tick()
    expect(c.find('[data-testid="restart-steps"]').exists()).toBe(false)
    expect(c.find('[role="status"]').text()).toBe('Waiting for the backend to stop… (1s)')

    backendUp = false
    progress = null
    await tick(6)
    expect(c.find('[data-testid="restart-steps"]').exists()).toBe(false)
    expect(c.find('[role="status"]').text()).toBe('Waiting for the backend to come back… (5s)')

    // No progress route from the JVM that comes back either: reload, as before.
    backendUp = true
    await tick(2)
    expect(reloads.count).toBe(1)
    c.unmount()
  })

  it('keeps the steps the old JVM served and reads the silence after them as the launch', async () => {
    const c = await restartConfirmed()
    progress = served('jclaw', 'running', [step('stop', 'Stopping JClaw', 'running', 1)])
    await tick()
    expect(lines(c)).toEqual(['Stopping JClaw… | 1s'])

    // A quick restart: no narrator answer reaches the page before the new JVM launches.
    backendUp = false
    progress = null
    await tick(6)
    expect(lines(c)).toEqual(['Stopping JClaw | 1s', 'Starting JClaw (connecting channels)… | 5s'])

    backendUp = true
    progress = served('jclaw', 'done', [step('stop', 'Stopping JClaw', 'done', 3), step('start', 'Starting JClaw (connecting channels)', 'done', 7)],
      { services: { channels: [], mcpConnected: 0, mcpConfigured: 0, settled: true } })
    await tick(2)
    expect(lines(c)).toEqual(['Stopping JClaw | 3s', 'Starting JClaw (connecting channels) | 7s'])
    expect(reloads.count).toBe(1)
    c.unmount()
  })

  it('gives up once the old JVM has answered for 90 s', async () => {
    const c = await restartConfirmed()
    await tick(89)
    expect(c.find('[role="status"]').text()).toBe('Waiting for the backend to stop… (89s)')
    await tick()
    expect(c.find('[role="status"]').text())
      .toBe('The backend was still responding 90s after the restart was accepted. Check logs/restart.log.')
    expect(reloads.count).toBe(0)
    c.unmount()
  })

  it('gives up 180 s after the backend went down when it never comes back', async () => {
    const c = await restartConfirmed()
    backendUp = false
    progress = served('narrator', 'running', [step('stop', 'Stopping JClaw', 'done', 4), step('start', 'Starting JClaw (connecting channels)', 'running', 1)])
    await tick()
    await tick(179)
    expect(c.find('[role="status"] > span').exists()).toBe(false)
    await tick()
    expect(c.find('[role="status"] > span').text()).toBe('The backend did not come back within 180s. Check logs/restart.log.')
    expect(reloads.count).toBe(0)
    c.unmount()
  })

  it('counts its budgets in time, not in polls, when every answer takes 4 s', async () => {
    const c = await restartConfirmed()
    stall = true
    // Each poll waits on answers that take 4 s; the seconds between are skipped polls, not lost time.
    async function slowPoll() {
      await vi.advanceTimersByTimeAsync(4000)
      release()
      for (let j = 0; j < 6; j++) await flushPromises()
    }
    for (let i = 0; i < 22; i++) await slowPoll()
    expect(c.find('[role="status"]').text()).toBe('Waiting for the backend to stop… (88s)')
    await slowPoll()
    expect(c.find('[role="status"]').text())
      .toBe('The backend was still responding 90s after the restart was accepted. Check logs/restart.log.')
    c.unmount()
  })

  it('keeps waiting while the narrator answers /api/status with 503', async () => {
    const c = await restartConfirmed()
    backendUp = false
    progress = served('narrator', 'running', [step('stop', 'Stopping JClaw', 'done', 4), step('aot', 'Rebuilding the startup cache', 'running', 2)])
    await tick(10)
    expect(reloads.count).toBe(0)
    expect(lines(c)).toEqual(['Stopping JClaw | 4s', 'Rebuilding the startup cache… | 2s'])
    c.unmount()
  })
})
