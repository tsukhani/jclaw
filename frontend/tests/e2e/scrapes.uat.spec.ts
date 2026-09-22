import { test, expect, gotoPage, blockApiWrites } from './helpers'

/**
 * UAT — the Scrapes page and its New scrape form (JCLAW-1273). Starts no job: every API write is
 * blocked before the page loads, and the form is never submitted.
 */
test.describe('Scrapes', () => {
  test('the Scrapes page opens and leads to the New scrape form', async ({ page }) => {
    const blocked = await blockApiWrites(page)
    await gotoPage(page, '/scrapes')

    await expect(page.getByRole('heading', { level: 1, name: 'Scrapes' })).toBeVisible()
    await page.getByRole('link', { name: 'New scrape' }).first().click()

    await expect(page).toHaveURL(/\/scrapes\/new$/)
    await expect(page.getByLabel('Starting URL')).toBeVisible()
    await expect(page.getByLabel('Pages', { exact: true })).toHaveValue(/^\d+$/)
    await expect(page.getByText(/An agent may ask for up to \d+ pages; you may go higher\./)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Start scrape' })).toBeDisabled()
    expect(blocked()).toEqual([])
  })
})
