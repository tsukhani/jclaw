import { test, expect, gotoPage } from './helpers'

/**
 * UAT-17 — Circuit breakers (JCLAW-1170).
 *
 * The dashboard panel is the only place an operator sees that a provider or an
 * MCP server is being turned away, and it is absent by design until a breaker
 * exists — which is exactly the shape that lets a silently broken panel pass
 * for a healthy install. So the assertion is the join: every breaker the API
 * reports has a row under the heading for its subsystem, and no breakers means
 * no panel. Read-only: isolating a live breaker would turn real calls away.
 */
interface Breaker {
  name: string
  subsystem: string
  target: string
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
}

const GROUP_TITLE: Record<string, string> = { llm: 'LLM providers', mcp: 'MCP servers' }

test.describe('UAT-17 circuit breakers', () => {
  test('the breakers endpoint reports one row per registered breaker', async ({ request }) => {
    const res = await request.get('/api/breakers')
    expect(res.status()).toBe(200)
    const rows = await res.json() as Breaker[]
    expect(Array.isArray(rows)).toBeTruthy()
    for (const b of rows) {
      expect(b.name, 'registry name is <subsystem>:<target>').toBe(`${b.subsystem}:${b.target}`)
      expect(['CLOSED', 'OPEN', 'HALF_OPEN'], `${b.name} state`).toContain(b.state)
    }
  })

  test('the dashboard panel mirrors the registry, grouped by subsystem', async ({ page, request }) => {
    const rows = await (await request.get('/api/breakers')).json() as Breaker[]
    await gotoPage(page, '/')

    if (rows.length === 0) {
      // A fresh install has minted nothing; an empty box would be noise, not signal.
      await expect(page.getByTestId('breaker-status')).toHaveCount(0)
      return
    }

    const panel = page.getByTestId('breaker-status')
    await expect(panel).toBeVisible()
    await expect(panel.getByRole('heading', { name: 'Circuit Breakers' })).toBeVisible()

    for (const subsystem of new Set(rows.map(r => r.subsystem))) {
      const group = panel.getByTestId(`breaker-group-${subsystem}`)
      await expect(group, `group for ${subsystem}`).toBeVisible()
      await expect(group.getByRole('heading', { level: 3 })).toHaveText(GROUP_TITLE[subsystem] ?? subsystem)
    }
    for (const b of rows) {
      const row = panel.getByTestId(`breaker-row-${b.name}`)
      await expect(row, `row for ${b.name}`).toBeVisible()
      await expect(row).toContainText(b.target)
      await expect(row).toContainText(b.state.replace('_', ' '))
      // Every row offers the way out of its current state — nothing is read-only.
      await expect(row.getByRole('button', { name: b.state === 'CLOSED' ? 'Isolate' : 'Restore' })).toBeVisible()
    }

    const notServing = rows.filter(r => r.state !== 'CLOSED').length
    if (notServing) await expect(panel.getByText(`${notServing} not serving`)).toBeVisible()
    else await expect(panel.getByText(/not serving/)).toHaveCount(0)
  })

  test('trip and reset refuse a breaker that does not exist', async ({ request }) => {
    // Breakers are minted on first use, so an unknown name is a 404 rather than
    // a silent no-op — a typo in an operator script must not read as success.
    for (const action of ['trip', 'reset']) {
      const res = await request.post(`/api/breakers/${action}`, { data: { name: 'llm:e2e-uat-no-such-provider' } })
      expect(res.status(), `POST /api/breakers/${action}`).toBe(404)
    }
  })
})
