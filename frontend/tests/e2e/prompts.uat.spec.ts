import { test, expect, gotoPage, uniqueName, E2E_PREFIX } from './helpers'

/**
 * UAT-5 — Prompts Library (JCLAW-813).
 *
 * Creation goes through the API, not the "New prompt" button: that dialog is
 * a generate-from-description flow whose only forward path is Generate, which
 * spends a real model call and returns nondeterministic text. Every other
 * step — search, category filter, edit, delete — is driven through the UI,
 * where prompts.vue carries per-card testids and the edit dialog opens
 * straight onto the populated form.
 *
 * Serial: the lifecycle tests share one fixture prompt.
 */
test.describe.configure({ mode: 'serial' })

test.describe('UAT-5 prompts library', () => {
  const title = uniqueName('prompt')
  let promptId: number | null = null

  test.afterAll(async ({ playwright }) => {
    if (promptId === null) return
    const ctx = await playwright.request.newContext({
      baseURL: process.env.JCLAW_E2E_BASE_URL || 'http://localhost:3000',
      storageState: './tests/e2e/.auth/admin.json',
    })
    await ctx.delete(`/api/prompts/${promptId}`)
    await ctx.dispose()
  })

  test('library renders with category filters and a search box', async ({ page }) => {
    await gotoPage(page, '/prompts')
    await expect(page.getByTestId('new-prompt-button')).toBeVisible()
    await expect(page.getByTestId('prompt-search')).toBeVisible()
    await expect(page.getByTestId('category-filter-all')).toBeVisible()
    await expect(page.getByTestId('export-prompts-button')).toBeVisible()
  })

  test('the new-prompt dialog opens on its generate step', async ({ page }) => {
    await gotoPage(page, '/prompts')
    await page.getByTestId('new-prompt-button').click()
    await expect(page.getByTestId('prompt-description')).toBeVisible()
    await expect(page.getByTestId('prompt-generate')).toBeVisible()
    // Generate is not clicked — it bills a model call.
    await page.keyboard.press('Escape')
  })

  test('create a prompt', async ({ request }) => {
    const res = await request.post('/api/prompts', {
      data: { title, content: 'UAT fixture body. Safe to delete.', category: 'CUSTOM', tags: 'uat,e2e' },
    })
    expect(res.status(), await res.text()).toBe(200)
    const created = await res.json()
    promptId = created.id
    expect(created.title).toBe(title)
    expect(created.tags).toContain('uat')
  })

  test('missing required fields are rejected with 400', async ({ request }) => {
    const res = await request.post('/api/prompts', { data: { title: 'no content or category' } })
    expect(res.status()).toBe(400)
  })

  test('created prompt surfaces in the library', async ({ page }) => {
    await gotoPage(page, '/prompts')
    await expect(page.getByTestId(`prompt-card-${promptId}`)).toBeVisible({ timeout: 15_000 })
  })

  test('search filters the library down to the fixture', async ({ page }) => {
    await gotoPage(page, '/prompts')
    await page.getByTestId('prompt-search').fill(title)
    await expect(page.getByTestId(`prompt-card-${promptId}`)).toBeVisible({ timeout: 10_000 })

    // A search that matches nothing must not leave stale cards on screen.
    await page.getByTestId('prompt-search').fill('zzz-no-such-prompt-zzz')
    await expect(page.getByTestId(`prompt-card-${promptId}`)).toBeHidden()
  })

  test('category filter narrows the visible set', async ({ page }) => {
    await gotoPage(page, '/prompts')
    const cards = page.locator('[data-testid^="prompt-card-"]')
    // count() does not wait, so each count follows a signal: the fixture (category CUSTOM) is on
    // screen once the list has loaded, and gone once the Coding filter has applied.
    const fixture = page.getByTestId(`prompt-card-${promptId}`)
    await expect(fixture).toBeVisible({ timeout: 15_000 })
    const all = await cards.count()
    await page.getByTestId('category-filter-CODING').click()
    await expect(fixture).toBeHidden()
    const coding = await cards.count()
    expect(coding, 'the Coding filter must drop at least the CUSTOM fixture').toBeLessThan(all)
    expect(coding, 'the seeded Coding category is non-empty').toBeGreaterThan(0)
  })

  test('edit the prompt through the dialog', async ({ page }) => {
    await gotoPage(page, '/prompts')
    await page.getByTestId('prompt-search').fill(title)
    await page.getByTestId(`prompt-edit-${promptId}`).click()

    const body = page.getByTestId('prompt-content')
    await expect(body).toBeVisible()
    await body.fill('UAT fixture body, edited.')
    await page.getByTestId('prompt-save').click()

    await expect(page.getByTestId('prompt-content')).toBeHidden({ timeout: 10_000 })
  })

  test('edit persisted to the API', async ({ request }) => {
    const res = await request.get('/api/prompts')
    const list = await res.json() as Array<{ id: number, content: string }>
    expect(list.find(p => p.id === promptId)?.content).toBe('UAT fixture body, edited.')
  })

  test('export returns the library as JSON', async ({ request }) => {
    const res = await request.get('/api/prompts/export')
    expect(res.status()).toBe(200)
    const body = await res.json()
    expect(JSON.stringify(body)).toContain(title)
  })

  test('delete the prompt', async ({ page, request }) => {
    await gotoPage(page, '/prompts')
    await page.getByTestId('prompt-search').fill(title)
    await page.getByTestId(`prompt-delete-${promptId}`).click()

    // Destructive actions route through ConfirmDialog; accept it.
    const confirm = page.getByRole('button', { name: /^(delete|confirm|yes)/i }).last()
    await confirm.click()

    await expect(page.getByTestId(`prompt-card-${promptId}`)).toBeHidden({ timeout: 15_000 })

    const list = await (await request.get('/api/prompts')).json() as Array<{ id: number }>
    expect(list.some(p => p.id === promptId)).toBeFalsy()
    promptId = null
  })

  test('no UAT fixture prompts are left behind', async ({ request }) => {
    const list = await (await request.get('/api/prompts')).json() as Array<{ title: string }>
    expect(list.filter(p => p.title.startsWith(E2E_PREFIX)).map(p => p.title)).toEqual([])
  })
})
