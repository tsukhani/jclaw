import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright E2E configuration for JClaw.
 *
 * These tests assume a JClaw server is already running — they connect to it
 * rather than spinning up their own. Keeping the runner decoupled from the
 * server shaves ~15s off every run and matches the local workflow where jclaw
 * is usually running anyway.
 *
 * Defaults to http://localhost:3000 (the Nuxt port served by
 * `./jclaw.sh --dev start`). For prod mode (`./jclaw.sh start`, which serves
 * the built SPA from the Play JVM on :9000), set
 * `JCLAW_E2E_BASE_URL=http://localhost:9000`.
 *
 * This suite is intentionally excluded from Jenkins CI. It is a local UAT
 * safety net, not part of the merge gate.
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
    baseURL: process.env.JCLAW_E2E_BASE_URL || 'http://localhost:3000',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    // Auth bootstrap — global-setup.ts writes this file once per run.
    storageState: './tests/e2e/.auth/admin.json',
    // PWSLOWMO=500 ./pnpm test:e2e --headed slows each action so a human can
    // follow along. Default 0 keeps headless runs at full speed.
    launchOptions: {
      slowMo: Number(process.env.PWSLOWMO) || 0,
    },
  },

  globalSetup: './tests/e2e/global-setup.ts',

  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
  ],
})
