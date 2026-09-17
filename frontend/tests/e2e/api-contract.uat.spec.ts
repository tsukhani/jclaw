import { test, expect, json } from './helpers'

/**
 * UAT-2 — Read-side API contract.
 *
 * Request-level rather than DOM-level: every page in the SPA is a view over
 * these endpoints, so a break here is the root cause of a whole class of UI
 * failures and this spec names it directly instead of leaving a page blank.
 *
 * Excluded on purpose:
 *   GET /api/printers          — live mDNS browse, seconds of latency per call
 *   /api/system/{restart,upgrade} — preflights for actions that stop this JVM
 *   per-provider model discovery — outbound calls to third-party APIs
 *   /api/metrics/loadtest       — starts synthetic traffic
 */

/** Endpoints the whole SPA reads on load. Each must answer 200 with JSON. */
const READ_ENDPOINTS = [
  '/api/status',
  '/api/auth/status',
  '/api/workspace/stats',
  '/api/onboarding/tour-status',
  '/api/agents',
  '/api/tasks',
  '/api/tasks/stats',
  '/api/task-runs/recent',
  '/api/notifications',
  '/api/bindings',
  '/api/memories',
  '/api/mcp-servers',
  '/api/prompts',
  '/api/prompts/categories',
  '/api/tools',
  '/api/tools/meta',
  '/api/skills',
  '/api/providers',
  '/api/config',
  '/api/logging/levels',
  '/api/conversations',
  '/api/conversations/channels',
  '/api/channels',
  '/api/channels/active',
  '/api/logs',
  '/api/subagent-runs',
  '/api/subagents/acp-harnesses',
  '/api/apps',
  '/api/timezones',
  '/api/ocr/status',
  '/api/tailscale',
  '/api/transcription/state',
  '/api/tts/state',
  '/api/imagegen/local/state',
  '/api/videogen/jobs',
  '/api/metrics/latency',
  '/api/metrics/cost',
  '/api/metrics/db-pool',
  '/api/metrics/compression',
]

test.describe('UAT-2 read-side API contract', () => {
  for (const path of READ_ENDPOINTS) {
    test(`GET ${path} answers 200 JSON`, async ({ request }) => {
      const res = await request.get(path)
      expect(res.status(), `${path} status`).toBe(200)
      expect(res.headers()['content-type'], `${path} content-type`).toContain('json')
      // Body must parse — a truncated stream or an HTML error page both fail here.
      await res.json()
    })
  }

  test('list endpoints return arrays, not objects', async ({ request }) => {
    // These back tables and grids; an object where the SPA expects an array
    // renders an empty page rather than an error, so assert the shape.
    for (const path of ['/api/agents', '/api/prompts', '/api/mcp-servers', '/api/skills', '/api/tools']) {
      const { body } = await json(request, path)
      expect(Array.isArray(body), `${path} must be a JSON array`).toBeTruthy()
    }
  })

  test('unknown /api path returns clean 404 JSON, not a framework stack trace', async ({ request }) => {
    // JCLAW-336: without ApiNotFoundController this is an ActionNotFoundException
    // logged at ERROR, and the response fingerprints Play to a scanner.
    const res = await request.get('/api/there-is-no-such-endpoint')
    expect(res.status()).toBe(404)
    const body = await res.json()
    // Assert the envelope's stable parts: this pinned the retired "error" key until JCLAW-1218
    // moved the 404 onto the canonical type/code/message envelope.
    expect(body.type).toBe('error')
    expect(body.code).toBe('not_found')
    const text = JSON.stringify(body)
    expect(text).not.toContain('play.')
    expect(text).not.toContain('Exception')
  })

  test('unknown /api path 404s without revealing that auth exists', async ({ playwright }) => {
    // ApiNotFoundController deliberately carries no @With(AuthCheck.class): a
    // 401 here would tell an unauthenticated scanner the path space is gated.
    const anon = await playwright.request.newContext({
      baseURL: process.env.JCLAW_E2E_BASE_URL || 'http://localhost:3000',
      storageState: { cookies: [], origins: [] },
    })
    const res = await anon.get('/api/there-is-no-such-endpoint')
    expect(res.status()).toBe(404)
    await anon.dispose()
  })

  test('status endpoint reports a running application', async ({ request }) => {
    const { body } = await json(request, '/api/status')
    expect(JSON.stringify(body).length, '/api/status must not be an empty object').toBeGreaterThan(2)
  })

  test('SPA deep link falls through to index.html rather than 404', async ({ request }) => {
    // The catch-all at conf/routes:349 is what makes browser refresh work on
    // any client-side route. A regression here breaks reload everywhere.
    const res = await request.get('/agents')
    expect(res.status()).toBe(200)
    expect(res.headers()['content-type']).toContain('html')
  })
})
