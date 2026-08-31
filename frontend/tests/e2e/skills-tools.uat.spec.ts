import { test, expect, gotoPage } from './helpers'

/**
 * UAT-10 — Skills, tools and MCP servers (the agent's capability surface).
 *
 * Per-agent tool config is not cosmetic: JCLAW-883 added an execution guard in
 * ToolRegistry so a disabled tool is refused at call time, not merely hidden
 * from the schema. So the per-agent toggle is a security control, and this
 * spec asserts it round-trips — against a scratch agent, never against main.
 */
test.describe('UAT-10 capability surface', () => {
  test('skills page lists skills and per-agent grants', async ({ page }) => {
    await gotoPage(page, '/skills')
    await expect(page.getByPlaceholder('Filter skills...')).toBeVisible()
    await expect(page.getByPlaceholder('Filter agents...')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Browse catalog' })).toBeVisible()
    // The agent chips carry a granted/total count — "main" always exists.
    await expect(page.getByRole('button', { name: /^main/ })).toBeVisible()
  })

  test('skill filter narrows the list', async ({ page }) => {
    await gotoPage(page, '/skills')
    const filter = page.getByPlaceholder('Filter skills...')
    await filter.fill('zzz-no-such-skill-zzz')
    await expect(page.getByText(/no skills|nothing|no match/i).first()).toBeVisible({ timeout: 10_000 })
  })

  test('tools page groups tools by category', async ({ page }) => {
    await gotoPage(page, '/tools')
    await expect(page.getByRole('button', { name: /^All \(\d+\)$/ })).toBeVisible()
    await expect(page.getByRole('button', { name: /^System \(\d+\)$/ })).toBeVisible()
    await expect(page.getByRole('button', { name: 'Expand all' })).toBeVisible()
  })

  test('tool category filter narrows the visible groups', async ({ page }) => {
    await gotoPage(page, '/tools')
    // Wait for a specific other group to exist before filtering. Counting
    // headings up front races the list render: an early count is small, and
    // the post-filter count then compares against a baseline that was never
    // the unfiltered set.
    const systemGroup = page.getByRole('heading', { name: /^System \(\d+\)$/ })
    await expect(systemGroup).toBeVisible()

    await page.getByRole('button', { name: /^Web \(\d+\)$/ }).click()

    // Selecting a single category flattens the page: the per-group headings
    // are an "All" affordance and disappear, leaving a bare list of that
    // category's tools. So assert the grouping collapsed AND that what
    // remains is Web.
    await expect(systemGroup, 'selecting one category drops the group headings').toBeHidden()
    await expect(page.getByText('web_search')).toBeVisible()
  })

  test('expand all reveals per-tool functions', async ({ page }) => {
    await gotoPage(page, '/tools')
    await page.getByRole('button', { name: 'Expand all' }).click()
    await expect(page.getByRole('button', { name: /^Functions \d+$/ }).first()).toBeVisible()
  })

  test('per-agent tool grant round-trips through the API', async ({ request }) => {
    // Uses whichever non-main agent exists; main's configuration is the
    // operator's live agent and is never touched.
    const agents = await (await request.get('/api/agents')).json() as Array<{ id: number, name: string }>
    const target = agents.find(a => a.name !== 'main')
    test.skip(!target, 'no non-main agent to exercise tool grants against')

    const tools = await (await request.get(`/api/agents/${target!.id}/tools`)).json() as Array<{ name: string, enabled: boolean }>
    test.skip(tools.length === 0, 'agent exposes no tools')

    const tool = tools[0]!
    const original = tool.enabled

    try {
      const flip = await request.put(`/api/agents/${target!.id}/tools/${tool.name}`, { data: { enabled: !original } })
      expect(flip.ok(), await flip.text()).toBeTruthy()

      const after = await (await request.get(`/api/agents/${target!.id}/tools`)).json() as Array<{ name: string, enabled: boolean }>
      expect(after.find(t => t.name === tool.name)?.enabled).toBe(!original)
    }
    finally {
      // Restore in `finally`, not after the assertion: a failed expect aborts the test, and
      // this suite runs against a live instance — the leftover would be a real tool silently
      // granted or revoked on the operator's agent, not fixture data (JCLAW-1140).
      await request.put(`/api/agents/${target!.id}/tools/${tool.name}`, { data: { enabled: original } })
    }
    const restored = await (await request.get(`/api/agents/${target!.id}/tools`)).json() as Array<{ name: string, enabled: boolean }>
    expect(restored.find(t => t.name === tool.name)?.enabled).toBe(original)
  })

  test('skills are read-only over HTTP', async ({ request }) => {
    // Authoring is filesystem-only by design (conf/routes:153) — there is no
    // POST /api/skills. A route appearing here would be a real regression.
    const res = await request.post('/api/skills', { data: { name: 'uat-should-not-exist' } })
    expect([404, 405]).toContain(res.status())
  })

  test('mcp servers page lists configured servers', async ({ page }) => {
    await gotoPage(page, '/mcp-servers')
    await expect(page.getByRole('button', { name: 'Add server' })).toBeVisible()
    await expect(page.locator('table')).toBeVisible()
  })

  test('skill catalog is browsable', async ({ request }) => {
    expect((await request.get('/api/skills/catalogs')).status()).toBe(200)
  })
})
