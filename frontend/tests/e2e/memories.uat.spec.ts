import { test, expect, gotoPage, applyFilter, expectFilterChip } from './helpers'

/**
 * UAT-7 — Memories (JCLAW-39/40, retrieval epic JCLAW-942).
 *
 * Read-only. The page's two destructive controls are "Delete" (selected) and
 * "Delete all" — the latter wipes the whole corpus, so neither is clicked and
 * the selection tests assert on checkbox state rather than following through.
 *
 * Importance is editable inline; this spec writes a value and restores the
 * original in the same test so the operator's ranking is unchanged.
 *
 * Serial because of that write: the importance edit briefly reorders the
 * corpus, and the sort and filter tests read row order from the same live
 * table. Run in parallel they interleave and fail on each other's state.
 */
test.describe.configure({ mode: 'serial' })

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

  test('inline importance edit round-trips and is restored', async ({ page }) => {
    await gotoPage(page, '/memories')
    const firstRow = page.getByTestId('memory-row').first()
    // Identify the row by content, not by position. If a reload reorders the
    // table, restoring "the first row" would write the original value onto a
    // different memory — silent corruption of the operator's real corpus.
    const rowId = await firstRow.textContent()
    const input = firstRow.getByTestId('importance-input')
    const original = await input.inputValue()
    // A reload aborts the save still in flight, so each one waits for the PUT to answer first.
    const saved = () => page.waitForResponse(r => r.request().method() === 'PUT' && r.url().includes('/api/memories/'))

    const edit = saved()
    await input.fill('0.42')
    await input.blur()
    expect((await edit).ok()).toBe(true)
    await page.reload()
    await page.waitForLoadState('domcontentloaded')

    const sameRow = page.getByTestId('memory-row').first()
    expect(await sameRow.textContent(), 'table reordered — refusing to restore onto another row').toBe(rowId)
    await expect(sameRow.getByTestId('importance-input')).toHaveValue('0.42', { timeout: 10_000 })

    // Restore, so the operator's ranking survives the UAT run.
    const restore = saved()
    await sameRow.getByTestId('importance-input').fill(original)
    await sameRow.getByTestId('importance-input').blur()
    expect((await restore).ok()).toBe(true)
    await page.reload()
    await expect(page.getByTestId('memory-row').first().getByTestId('importance-input'))
      .toHaveValue(original, { timeout: 10_000 })
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
