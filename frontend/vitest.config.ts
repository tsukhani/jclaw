import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    environmentOptions: {
      nuxt: {
        domEnvironment: 'happy-dom'
      }
    },
    // Playwright E2E specs live under tests/e2e/ and use @playwright/test, not
    // Vitest. Exclude them so `pnpm test` only runs the Vitest unit suite.
    exclude: ['**/node_modules/**', '**/dist/**', '**/.{idea,git,cache,output,temp}/**', 'tests/e2e/**']
  }
})
