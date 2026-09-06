import { test, expect } from './helpers'

/**
 * UAT-1 — Authentication and session gating.
 *
 * The only spec in the suite that runs unauthenticated: `storageState: {}`
 * discards the admin cookie global-setup.ts minted, so these tests exercise
 * the door rather than the rooms behind it.
 *
 * Password comes from JCLAW_ADMIN_PASSWORD, the same variable `./jclaw.sh e2e`
 * loads out of certs/.env. There is no username field — JClaw is single-admin
 * and login.vue hardcodes "admin" (documented trade-off at login.vue:31).
 */
test.use({ storageState: { cookies: [], origins: [] } })

const PASSWORD = process.env.JCLAW_ADMIN_PASSWORD || 'changeme'

test.describe('UAT-1 authentication', () => {
  test('unauthenticated API request is rejected with 401 JSON', async ({ request }) => {
    const res = await request.get('/api/agents')
    expect(res.status()).toBe(401)
    expect(await res.json()).toHaveProperty('error')
  })

  test('unauthenticated app route redirects to the login page', async ({ page }) => {
    await page.goto('/agents')
    await page.waitForLoadState('domcontentloaded')
    await expect(page).toHaveURL(/\/login/)
    await expect(page.getByRole('button', { name: 'Login' })).toBeVisible()
  })

  test('login form carries password-manager autocomplete hints', async ({ page }) => {
    await page.goto('/login')
    await page.waitForLoadState('domcontentloaded')
    await expect(page.locator('input[type="password"]')).toBeVisible()
    // The username input exists only to give password managers a field to
    // bind to — login.vue posts the hardcoded "admin" regardless. Dropping
    // either autocomplete attribute silently breaks credential autofill.
    await expect(page.locator('input[autocomplete="username"]')).toBeAttached()
    await expect(page.locator('input[autocomplete="current-password"]')).toBeAttached()
  })

  test('wrong password is rejected and does not create a session', async ({ page }) => {
    await page.goto('/login')
    await page.waitForLoadState('domcontentloaded')
    await page.locator('input[type="password"]').fill('definitely-not-the-password')
    await page.getByRole('button', { name: 'Login' }).click()

    await expect(page.getByText('Invalid password')).toBeVisible()
    await expect(page).toHaveURL(/\/login/)
  })

  test('correct password signs in and lands on the dashboard', async ({ page }) => {
    await page.goto('/login')
    await page.waitForLoadState('domcontentloaded')
    await page.locator('input[type="password"]').fill(PASSWORD)
    await page.getByRole('button', { name: 'Login' }).click()

    await expect(page).toHaveURL(/\/$|\/#/)
    await expect(page.getByRole('navigation').first()).toContainText('Dashboard')
  })

  test('sign out clears the session and re-gates the API', async ({ page }) => {
    // Sign in first — this spec's context starts empty. Wait for the landing
    // rather than navigating: a goto() here races the post-login redirect and
    // bounces back to /login, which renders no <main> (layout: false).
    await page.goto('/login')
    await page.locator('input[type="password"]').fill(PASSWORD)
    await page.getByRole('button', { name: 'Login' }).click()
    await expect(page.locator('main')).toBeVisible()

    await page.getByRole('button', { name: 'Sign out' }).click()
    await expect(page).toHaveURL(/\/login/)

    // The cookie is gone from the browser context, so the API refuses again.
    const res = await page.request.get('/api/agents')
    expect(res.status()).toBe(401)
  })
})
