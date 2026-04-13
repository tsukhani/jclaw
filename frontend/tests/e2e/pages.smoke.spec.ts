import { test, expect } from '@playwright/test'

/**
 * Smoke tests for every top-level page in the JClaw sidebar. Each test navigates
 * to the page, waits for the network to settle, and asserts the breadcrumb
 * matches the expected page name plus that the <main> element is visible. If a
 * page fails to render — unhandled exception, broken API call, missing route —
 * one of these assertions will fail.
 *
 * These tests run in parallel (the default project setting) since they have no
 * shared state beyond the pre-authenticated session cookie loaded via
 * storageState in global-setup.ts.
 */
const pages = [
  { path: '/', breadcrumb: 'Dashboard' },
  { path: '/chat', breadcrumb: 'Chat' },
  { path: '/channels', breadcrumb: 'Channels' },
  { path: '/conversations', breadcrumb: 'Conversations' },
  { path: '/tasks', breadcrumb: 'Tasks' },
  { path: '/agents', breadcrumb: 'Agents' },
  { path: '/skills', breadcrumb: 'Skills' },
  { path: '/tools', breadcrumb: 'Tools' },
  { path: '/settings', breadcrumb: 'Settings' },
  { path: '/logs', breadcrumb: 'Logs' },
]

for (const { path, breadcrumb } of pages) {
  test(`${breadcrumb} page renders`, async ({ page }) => {
    const consoleErrors: string[] = []
    page.on('console', msg => {
      if (msg.type() === 'error') consoleErrors.push(msg.text())
    })

    await page.goto(path)
    // Avoid networkidle — some pages poll (Logs, Skills) and never reach idle.
    // domcontentloaded is enough for a smoke-level "did the page mount" check.
    await page.waitForLoadState('domcontentloaded')

    await expect(page.locator('main')).toBeVisible()
    await expect(page.getByRole('navigation').first()).toContainText(breadcrumb)
    expect(consoleErrors, `console errors on ${path}`).toHaveLength(0)
  })
}
