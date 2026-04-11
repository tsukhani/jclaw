import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright E2E configuration for JClaw.
 *
 * These tests assume a dev server is already running via `./jclaw.sh --dev start`
 * — they connect to http://localhost:3000 rather than spinning up their own Nuxt
 * instance. Keeping the runner decoupled from the server shaves ~15s off every run
 * and matches the local dev workflow where jclaw is usually running anyway.
 *
 * This suite is intentionally excluded from Jenkins CI. It is a local UAT safety
 * net, not part of the merge gate.
 *
 * Run: cd frontend && pnpm test:e2e
 */
export default defineConfig({
  testDir: './tests/e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: 0,
  workers: undefined, // defaults to half the logical CPU count
  reporter: [['list'], ['html', { open: 'never' }]],

  use: {
    baseURL: 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // Auth bootstrap — global-setup.ts writes this file once per run.
    storageState: './tests/e2e/.auth/admin.json',
  },

  globalSetup: './tests/e2e/global-setup.ts',

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})
