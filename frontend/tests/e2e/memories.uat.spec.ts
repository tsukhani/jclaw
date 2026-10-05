import { test, expect, gotoPage, applyFilter, expectFilterChip, blockApiWrites } from './helpers'

/**
 * UAT-7 — Memories (JCLAW-39/40, retrieval epic JCLAW-942).
 *
 * Read-only. The page's two destructive controls are "Delete" (selected) and
 * "Delete all" — the latter wipes the whole corpus, so neither is clicked and
 * the selection tests assert on checkbox state rather than following through.
 *
 * Importance is editable inline, and its save is answered by a stub, never the
 * server: since JCLAW-1318 any real edit records an operator verification and
 * marks the memory human-reviewed, which no restore can undo.
 */

test.describe('UAT-7 memories', () => {
  test('memory table renders with sortable columns', async ({ page }) => {
    await gotoPage(page, '/memories')
    await expect(page.getByTestId('memory-table')).toBeVisible()
    for (const col of ['agent', 'text', 'category', 'importance', 'created']) {
      await expect(page.getByTestId(`sort-${col}`), `sort control for ${col}`).toBeVisible()
    }
  })

  test('rows render and pagination controls are present', async ({ page }) => {
    await gotoPage(page, '/memories')
    await expect(page.getByTestId('memory-row').first()).toBeVisible()
    await expect(page.getByRole('button', { name: 'Next' })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Prev' })).toBeVisible()
  })

  test('filter grammar narrows the corpus', async ({ page }) => {
    await gotoPage(page, '/memories')
    const before = await page.getByTestId('memory-row').count()
    expect(before, 'the corpus must be non-empty for this UAT to mean anything').toBeGreaterThan(0)

    await applyFilter(page, 'zzz-no-such-memory-zzz')
    await expect(async () => {
      expect(await page.getByTestId('memory-row').count()).toBe(0)
    }).toPass({ timeout: 10_000 })
  })

  test('category facet is accepted by the filter parser', async ({ page }) => {
    await gotoPage(page, '/memories')
    await applyFilter(page, 'category:core')
    await expectFilterChip(page, 'category', 'core')
  })

  test('sorting by importance reorders the table', async ({ page }) => {
    await gotoPage(page, '/memories')
    const firstBefore = await page.getByTestId('memory-row').first().textContent()
    await page.getByTestId('sort-importance').click()
    await expect(async () => {
      const firstAfter = await page.getByTestId('memory-row').first().textContent()
      expect(firstAfter).not.toBe(firstBefore)
    }).toPass({ timeout: 10_000 })
  })

  test('select-all arms the bulk controls without deleting', async ({ page }) => {
    await gotoPage(page, '/memories')
    await page.getByTestId('select-all').check()
    await expect(page.getByTestId('delete-selected')).toBeEnabled()

    // Unselect — this spec never confirms a deletion against a live corpus.
    await page.getByTestId('select-all').uncheck()
    await expect(page.getByTestId('select-memory').first()).not.toBeChecked()
  })

  test('inline importance edit saves to its own memory, and a refused save reverts', async ({ page }) => {
    const puts: Array<{ path: string, body: unknown }> = []
    let refuse = false
    await blockApiWrites(page)
    await page.route(url => /^\/api\/memories\/\d+$/.test(url.pathname), (route) => {
      const req = route.request()
      if (req.method() !== 'PUT') return route.fallback()
      puts.push({ path: new URL(req.url()).pathname, body: req.postDataJSON() })
      return refuse ? route.fulfill({ status: 500, json: { error: 'internal_error' } }) : route.fulfill({ json: {} })
    })
    const list = page.waitForResponse(r => r.request().method() === 'GET' && new URL(r.url()).pathname === '/api/memories')
    await gotoPage(page, '/memories')
    const first = (await (await list).json() as Array<{ id: number }>)[0]
    test.skip(!first, 'no memories on this install')
    const input = page.getByTestId('memory-row').first().getByTestId('importance-input')

    await input.fill('0.42')
    await input.blur()
    await expect.poll(() => puts.length).toBe(1)
    expect(puts[0]).toEqual({ path: `/api/memories/${first!.id}`, body: { importance: 0.42 } })
    await expect(input).toHaveValue('0.42')

    refuse = true
    await input.fill('0.43')
    await input.blur()
    await expect.poll(() => puts.length).toBe(2)
    await expect(input).toHaveValue('0.42')
  })

  test('recall endpoint answers a semantic query', async ({ request }) => {
    // The retrieval path the agent itself uses at turn time.
    const agents = await (await request.get('/api/agents')).json() as Array<{ id: number }>
    // agentId is required — memories are scoped per agent, never global.
    const res = await request.post('/api/memories/recall', { data: { query: 'project', agentId: agents[0]!.id, limit: 3 } })
    expect(res.status(), await res.text()).toBe(200)
  })

  test('re-embed status is readable without starting a re-embed', async ({ request }) => {
    const res = await request.get('/api/memories/reembed')
    expect(res.status()).toBe(200)
  })
})
