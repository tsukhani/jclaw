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
 * "Add filter..." once any chip is committed (FilterBar.vue:190), so a
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
 * chip appears. Chip label comes from FilterBar.vue:174.
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
