import { chromium, type FullConfig } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { dirname } from 'node:path'

/**
 * Authenticate as admin once per test run, then persist the Play session cookie
 * via storageState so every test starts already signed in. This runs before any
 * test file; the resulting JSON is read back via `use.storageState` in
 * playwright.config.ts.
 *
 * Credentials default to admin / changeme (the Play.configuration defaults in
 * ApiAuthController). Override via JCLAW_ADMIN_USERNAME / JCLAW_ADMIN_PASSWORD
 * environment variables if your local config differs.
 */
export default async function globalSetup(_config: FullConfig) {
  const baseURL = 'http://localhost:3000'
  const username = process.env.JCLAW_ADMIN_USERNAME || 'admin'
  const password = process.env.JCLAW_ADMIN_PASSWORD || 'changeme'
  const statePath = './tests/e2e/.auth/admin.json'

  mkdirSync(dirname(statePath), { recursive: true })

  const browser = await chromium.launch()
  const context = await browser.newContext()
  const page = await context.newPage()

  // Hit any page first so the PLAY_SESSION cookie domain is bound.
  await page.goto(`${baseURL}/`)

  const response = await page.request.post(`${baseURL}/api/auth/login`, {
    data: { username, password },
  })
  if (!response.ok()) {
    throw new Error(
      `Admin login failed (${response.status()}). Is the dev server running ` +
        `via ./jclaw.sh --dev start, and do JCLAW_ADMIN_USERNAME / JCLAW_ADMIN_PASSWORD match?`,
    )
  }

  await context.storageState({ path: statePath })
  await browser.close()
}
