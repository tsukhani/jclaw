import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test'

/**
 * Shared fixtures for the UAT suite.
 *
 * The suite runs against a live server holding real operator data, so every
 * spec is read-only except for entities it creates itself. Those carry the
 * E2E_PREFIX and are removed in the same test that made them — a leaked row
 * is a defect, not cosmetic, because the next run asserts on list counts.
 */
export const E2E_PREFIX = 'e2e-uat-'

/**
 * Hosts the app talks to that are not the app. Blocked for every test.
 *
 * GithubStarsButton fetches api.github.com unauthenticated, which allows 60
 * requests/hour/IP — a parallel suite exhausts that in its first seconds and
 * every subsequent page then logs a 403 console error. That turned a
 * third-party rate limit into a red suite, including the pre-existing page
 * smoke tests. Blocking it also keeps the suite runnable with no egress.
 */
const EXTERNAL_HOSTS = new Set(['api.github.com', 'github.com'])

/**
 * The suite's base test. Identical to Playwright's except that outbound
 * requests to third parties are answered locally, so no assertion depends on
 * someone else's uptime or quota. Import `test` from here, never from
 * @playwright/test.
 *
 * Stubbed rather than aborted: an abort surfaces as a `net::ERR_FAILED`
 * console error, which the console-error assertions would then flag — the
 * same red suite by a different route.
 */
export const test = base.extend({
  page: async ({ page }, use) => {
    await page.route('**/*', (route) => {
      const host = new URL(route.request().url()).hostname
      if (!EXTERNAL_HOSTS.has(host)) return route.continue()
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ stargazers_count: 0 }),
      })
    })
    await use(page)
  },
})

export { expect } from '@playwright/test'

/** Unique per invocation so parallel workers and re-runs never collide on the
 *  unique-name constraints in Agent and Prompt. */
export function uniqueName(kind: string): string {
  return `${E2E_PREFIX}${kind}-${process.pid}-${Math.random().toString(36).slice(2, 8)}`
}

/**
 * True when the target is the Play backend serving the whole site — production
 * mode (`./jclaw.sh start`, :9000).
 *
 * Under `./jclaw.sh --dev start` Nuxt serves the frontend on :3000 and proxies
 * only `/api`, so anything Play routes itself is unreachable: `/apps/<slug>/`
 * falls through to the SPA catch-all and answers 200 HTML, which makes a naive
 * "the app is served" assertion pass for entirely the wrong reason.
 */
export const PLAY_SERVES_SITE = !(process.env.JCLAW_E2E_BASE_URL || '').includes(':3000')

/** Every route reachable from the sidebar, in sidebar order. */
export const SIDEBAR_ROUTES = [
  { path: '/', label: 'Dashboard' },
  { path: '/chat', label: 'Chat' },
  { path: '/prompts', label: 'Prompts' },
  { path: '/channels', label: 'Channels' },
  { path: '/conversations', label: 'Conversations' },
  { path: '/agents', label: 'Agents' },
  { path: '/subagents', label: 'Subagents' },
  { path: '/scrapes', label: 'Scrapes' },
  { path: '/apps', label: 'Apps' },
  { path: '/tasks', label: 'Tasks' },
  { path: '/reminders', label: 'Reminders' },
  { path: '/skills', label: 'Skills' },
  { path: '/tools', label: 'Tools' },
  { path: '/mcp-servers', label: 'MCP Servers' },
  { path: '/settings', label: 'Settings' },
  { path: '/memories', label: 'Memories' },
  { path: '/logs', label: 'Logs' },
  { path: '/guide', label: 'User Guide' },
] as const

/**
 * Navigate and wait for the SPA to mount. Deliberately not networkidle —
 * Logs, Skills and the Dashboard poll, so they never reach idle and the call
 * would always burn its full timeout before continuing.
 */
export async function gotoPage(page: Page, path: string) {
  await page.goto(path)
  await page.waitForLoadState('domcontentloaded')
  await expect(page.locator('main')).toBeVisible()
}

/**
 * Type a query into a FilterBar and commit it.
 *
 * Addressed by aria-label, not placeholder: FilterBar swaps its placeholder to
 * "Add filter..." once any chip is committed (FilterBar.vue:211), so a
 * placeholder locator silently stops matching after the first filter.
 *
 * The Enter press is load-bearing too — FilterBar parses its facet grammar on
 * submit, not on input, so a fill() alone leaves every row on screen and an
 * assertion on the filtered count passes or fails by accident.
 */
export function filterInput(page: Page) {
  return page.getByRole('textbox', { name: 'Filter query' })
}

export async function applyFilter(page: Page, query: string) {
  const input = filterInput(page)
  await input.fill(query)
  await input.press('Enter')
}

/**
 * Assert a `key:value` facet was parsed into a chip.
 *
 * This, not the resulting row count, is the test for "the grammar is wired
 * up": a facet that matches nothing legitimately renders an empty state, so
 * asserting on rows makes the test pass or fail with the operator's data.
 * A parser regression instead drops the text into a plain search term and no
 * chip appears. Chip label comes from FilterBar.vue:194.
 */
export async function expectFilterChip(page: Page, key: string, value: string) {
  await expect(page.getByLabel(`Filter: ${key} is ${value}`)).toBeVisible({ timeout: 10_000 })
}

/**
 * Answer every non-GET `/api` request locally and record it, so a spec driving
 * stubbed data can never write to the live instance. Register it after the
 * spec's own routes: Playwright consults the last-registered route first.
 */
export async function blockApiWrites(page: Page): Promise<() => string[]> {
  const blocked: string[] = []
  await page.route(url => url.pathname.startsWith('/api/'), (route) => {
    const req = route.request()
    if (req.method() === 'GET' || req.method() === 'HEAD') return route.fallback()
    blocked.push(`${req.method()} ${new URL(req.url()).pathname}`)
    return route.fulfill({ status: 204 })
  })
  return () => blocked
}

/** What the fake answers for `GET /api/conversations/{id}/queue`. */
export type FakeQueue = {
  busy: boolean
  queueSize: number
  stoppable: boolean
  stopDropsQueue?: boolean
  pending: Array<{ text: string, attachmentNames: string[], position: number }>
}

type FakeTurns = {
  sent: Array<Record<string, unknown>>
  stops: number
  queue: FakeQueue
  open: () => boolean
  push: (event: object) => void
}

/**
 * Stand in, in the page, for the server's half of a chat turn (JF-202): `POST /api/chat/turn` answers `started` in
 * `conversationId` and records its body, and that conversation's stream (`GET /api/conversations/{id}/stream`) is an
 * EventSource the test feeds, which stays open as the server's does. Its queue (`GET .../queue`) answers what the
 * test last set, idle by default, and its Stop (`POST .../stop`) is counted, hands the conversation to the first
 * queued message (or empties the queue when it says Stop drops it, as a voice one does) and ends the turn on the
 * stream before it answers. The nth send's
 * turn plays `turns[n]` once the stream is open; a send past the list plays nothing, for a test that pushes a turn's
 * frames itself while it runs. Call before the first navigation: fetch and EventSource are replaced before the page's
 * scripts run, and every other request passes through.
 */
export async function fakeChatTurns(page: Page, conversationId: number, turns: object[][] = []) {
  await page.addInitScript(({ conversationId, turns }) => {
    type Source = {
      readyState: number
      onopen: ((e: Event) => void) | null
      onmessage: ((e: MessageEvent) => void) | null
      onerror: ((e: Event) => void) | null
      close: () => void
    }
    const realFetch = window.fetch.bind(window)
    const RealEventSource = window.EventSource
    let source: Source | null = null
    let nextId = 1
    const push = (event: object) => source?.onmessage?.(
      new MessageEvent('message', { data: JSON.stringify(event), lastEventId: String(nextId++) }))
    const fake: FakeTurns = {
      sent: [], stops: 0, queue: { busy: false, queueSize: 0, stoppable: false, pending: [] },
      open: () => source?.readyState === 1, push,
    }
    const json = (body: unknown) => new Response(JSON.stringify(body),
      { status: 200, headers: { 'Content-Type': 'application/json' } })
    ;(window as unknown as { e2eTurns: FakeTurns }).e2eTurns = fake
    const playWhenOpen = (frames: object[]) => {
      if (!fake.open()) {
        setTimeout(() => playWhenOpen(frames), 20)
        return
      }
      for (const frame of frames) push(frame)
    }
    window.fetch = async (input, init) => {
      const url = input instanceof Request ? input.url : String(input)
      const path = new URL(url, location.href).pathname
      if (path === `/api/conversations/${conversationId}/queue`) return json(fake.queue)
      if (path === `/api/conversations/${conversationId}/stop`) {
        // As the server stops: hand the conversation to the first queued message, or drop the queue where the queue
        // says Stop does (a voice conversation's), end the turn on its stream, then answer.
        fake.stops++
        const [, ...behind] = fake.queue.pending
        fake.queue = fake.queue.stopDropsQueue === true || fake.queue.pending.length === 0
          ? { busy: false, queueSize: 0, stoppable: false, pending: [] }
          : { ...fake.queue, queueSize: fake.queue.queueSize - 1, pending: behind.map((p, i) => ({ ...p, position: i + 1 })) }
        push({ type: 'stopped' })
        return json({ stopped: true })
      }
      if (path !== '/api/chat/turn') return realFetch(input, init)
      fake.sent.push(JSON.parse(String(init?.body ?? '{}')) as Record<string, unknown>)
      const frames = turns[fake.sent.length - 1]
      if (frames) setTimeout(() => playWhenOpen(frames), 0)
      return json({ status: 'started', conversationId })
    }
    function HeldEventSource(url: string | URL, init?: EventSourceInit) {
      if (!String(url).startsWith(`/api/conversations/${conversationId}/stream`)) return new RealEventSource(url, init)
      const held: Source = {
        readyState: 0, onopen: null, onmessage: null, onerror: null,
        close: () => {
          held.readyState = 2
          if (source === held) source = null
        },
      }
      source = held
      setTimeout(() => {
        held.readyState = 1
        held.onopen?.(new Event('open'))
      }, 0)
      return held
    }
    window.EventSource = HeldEventSource as unknown as typeof EventSource
  }, { conversationId, turns })
  return {
    /** Resolves once the page has sent a turn and its stream is open. */
    opened: async () => {
      await page.waitForFunction(() => {
        const fake = (window as unknown as { e2eTurns: FakeTurns }).e2eTurns
        return fake.sent.length > 0 && fake.open()
      })
    },
    push: (...events: object[]) => page.evaluate((frames) => {
      for (const frame of frames) (window as unknown as { e2eTurns: FakeTurns }).e2eTurns.push(frame)
    }, events),
    /** The bodies the page posted to `/api/chat/turn`, in order. */
    sent: () => page.evaluate(() => (window as unknown as { e2eTurns: FakeTurns }).e2eTurns.sent),
    /** What the conversation's queue answers from now on. */
    setQueue: (queue: FakeQueue) => page.evaluate((q) => {
      (window as unknown as { e2eTurns: FakeTurns }).e2eTurns.queue = q
    }, queue),
    /** How many times the page posted the conversation's Stop. */
    stops: () => page.evaluate(() => (window as unknown as { e2eTurns: FakeTurns }).e2eTurns.stops),
  }
}

/** Collect console errors for the lifetime of the page. Returns a getter so a
 *  test can assert at the end rather than racing the handler. */
export function collectConsoleErrors(page: Page): () => string[] {
  const errors: string[] = []
  page.on('console', (msg) => {
    if (msg.type() === 'error') errors.push(msg.text())
  })
  return () => errors
}

/** Parse a JSON API response, failing with the body text when it isn't JSON —
 *  a 500 HTML error page otherwise surfaces as an opaque SyntaxError. */
export async function json(request: APIRequestContext, path: string) {
  const res = await request.get(path)
  const text = await res.text()
  let parsed: unknown
  try {
    parsed = JSON.parse(text)
  }
  catch {
    throw new Error(`GET ${path} returned ${res.status()} non-JSON: ${text.slice(0, 200)}`)
  }
  return { status: res.status(), headers: res.headers(), body: parsed as never }
}

/** Borrow a real provider/model pair from the main agent so created fixtures
 *  reference a configuration that actually exists on this install. */
export async function borrowModelConfig(request: APIRequestContext) {
  const res = await request.get('/api/agents')
  expect(res.ok(), 'GET /api/agents must succeed to seed a fixture agent').toBeTruthy()
  const agents = await res.json() as Array<{ name: string, modelProvider: string, modelId: string }>
  const source = agents.find(a => a.name === 'main') ?? agents[0]
  if (!source) throw new Error('no agent exists to borrow a model config from')
  return { modelProvider: source.modelProvider, modelId: source.modelId }
}
