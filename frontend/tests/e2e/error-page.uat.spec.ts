import { test, expect, gotoPage } from './helpers'

/**
 * UAT — the branded error page. An unmatched route must show JClaw's own page, not the
 * framework's stock one, and its Dashboard button must lead back into the app.
 */
test.describe('UAT error page', () => {
  test('an unknown address shows the JClaw not-found page with a way home', async ({ page }) => {
    await gotoPage(page, '/no-such-page-e2e-uat')
    const errorPage = page.getByTestId('error-page')
    await expect(errorPage).toBeVisible()
    await expect(errorPage.getByRole('heading', { level: 1 })).toHaveText('Page not found')
    await expect(errorPage.getByTestId('error-path')).toHaveText('/no-such-page-e2e-uat')
    await expect(page).toHaveTitle(/Page not found · JClaw/)

    await errorPage.getByTestId('error-home').click()
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByTestId('error-page')).toHaveCount(0)
  })
})
