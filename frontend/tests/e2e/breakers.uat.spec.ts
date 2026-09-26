import { test, expect, gotoPage } from './helpers'

/**
 * UAT-17 — Circuit breakers (JCLAW-1170, JCLAW-1301).
 *
 * Every breaker is shown beside what it guards: a provider's card in Settings, a
 * server's row on the MCP page. The dashboard lists only the breakers that are
 * not serving, and is absent by design while all of them are, which is exactly
 * the shape that lets a silently broken panel pass for a healthy install. So the
 * assertions are joins against the registry the API reports. Read-only:
 * isolating a live breaker would turn real calls away.
 */
interface Breaker {
  name: string
  subsystem: string
  target: string
  state: 'CLOSED' | 'OPEN' | 'HALF_OPEN'
}

const GROUP_TITLE: Record<string, string> = { llm: 'LLM providers', mcp: 'MCP servers' }
const HOME: Record<string, string> = { llm: '/settings?section=providers', mcp: '/mcp-servers' }

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

  test('the dashboard lists only the breakers that are not serving, grouped by subsystem', async ({ page, request }) => {
    const rows = await (await request.get('/api/breakers')).json() as Breaker[]
    const notServing = rows.filter(r => r.state !== 'CLOSED')
    await gotoPage(page, '/')

    if (notServing.length === 0) {
      // Nothing is being turned away, so nothing here distorts the graphs below.
      await expect(page.getByTestId('breaker-status')).toHaveCount(0)
      return
    }

    const panel = page.getByTestId('breaker-status')
    await expect(panel).toBeVisible()
    await expect(panel.getByRole('heading', { name: 'Circuit Breakers' })).toBeVisible()
    await expect(panel.getByText(`${notServing.length} not serving`)).toBeVisible()

    for (const subsystem of new Set(notServing.map(r => r.subsystem))) {
      const group = panel.getByTestId(`breaker-group-${subsystem}`)
      await expect(group, `group for ${subsystem}`).toBeVisible()
      await expect(group.getByRole('heading', { level: 3 })).toHaveText(GROUP_TITLE[subsystem] ?? subsystem)
    }
    for (const b of notServing) {
      const row = panel.getByTestId(`breaker-row-${b.name}`)
      await expect(row, `row for ${b.name}`).toBeVisible()
      await expect(row).toContainText(b.state.replace('_', ' '))
      await expect(row.getByRole('button', { name: 'Restore' })).toBeVisible()
      if (HOME[b.subsystem]) await expect(row.getByTestId('breaker-home')).toHaveAttribute('href', HOME[b.subsystem]!)
    }
    for (const b of rows.filter(r => r.state === 'CLOSED')) {
      await expect(panel.getByTestId(`breaker-row-${b.name}`), `${b.name} is serving`).toHaveCount(0)
    }
  })

  test('a provider breaker is on its provider\'s card', async ({ page, request }) => {
    const rows = (await (await request.get('/api/breakers')).json() as Breaker[]).filter(r => r.subsystem === 'llm')
    test.skip(rows.length === 0, 'no provider has been called yet')
    await gotoPage(page, '/settings?section=providers')

    for (const b of rows) {
      // A provider removed from config keeps its breaker but no longer has a card.
      if (await page.getByRole('switch', { name: `${b.target} provider` }).count() === 0) continue
      const row = page.getByTestId(`provider-breaker-${b.target}`)
      await expect(row, `breaker on the ${b.target} card`).toBeVisible()
      await expect(row).toContainText(b.state.replace('_', ' '))
      await expect(row.getByRole('button', { name: b.state === 'CLOSED' ? 'Isolate' : 'Restore' })).toBeVisible()
    }
  })

  test('an MCP breaker is beside its server\'s status, and opens beneath the server', async ({ page, request }) => {
    const rows = (await (await request.get('/api/breakers')).json() as Breaker[]).filter(r => r.subsystem === 'mcp')
    test.skip(rows.length === 0, 'no MCP server has been called yet')
    await gotoPage(page, '/mcp-servers')

    // Deleting or reconfiguring a server drops its breaker, so every MCP breaker has a row.
    for (const b of rows) {
      const toggle = page.getByTestId(`mcp-breaker-toggle-${b.target}`)
      await expect(toggle, `breaker icon for ${b.target}`).toBeVisible()
      const detail = page.getByTestId(`mcp-breaker-row-${b.target}`)
      if (b.state === 'CLOSED') {
        // A serving breaker stays behind its icon until asked for.
        await expect(detail).toHaveCount(0)
        await toggle.click()
      }
      await expect(detail, `breaker row for ${b.target}`).toBeVisible()
      await expect(detail).toContainText(b.state.replace('_', ' '))
      await expect(detail.getByRole('button', { name: b.state === 'CLOSED' ? 'Isolate' : 'Restore' })).toBeVisible()
      if (b.state === 'CLOSED') await toggle.click()
    }
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
