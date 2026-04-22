import { defineVitestConfig } from '@nuxt/test-utils/config'

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    environmentOptions: {
      nuxt: {
        domEnvironment: 'happy-dom',
      },
    },
    // Playwright E2E specs live under tests/e2e/ and use @playwright/test, not
    // Vitest. Exclude them so `pnpm test` only runs the Vitest unit suite.
    exclude: ['**/node_modules/**', '**/dist/**', '**/.{idea,git,cache,output,temp}/**', 'tests/e2e/**'],
    coverage: {
      // v8 is the native Vitest coverage provider (istanbul requires a
      // separate Babel transform); both emit Sonar-compatible lcov but v8
      // is lighter and ships in @vitest/coverage-v8 matching the vitest
      // major. Activated by `pnpm test -- --coverage` in the Jenkinsfile.
      //
      // `lcov` is what sonar.javascript.lcov.reportPaths consumes; `text`
      // keeps a human-readable summary in the test log; `html` lets us
      // open coverage/index.html locally when chasing a missed branch.
      provider: 'v8',
      reporter: ['text', 'lcov', 'html'],
      reportsDirectory: 'coverage',
      include: ['components/**', 'composables/**', 'pages/**', 'plugins/**', 'utils/**'],
      exclude: [
        'test/**',
        'tests/**',
        '.nuxt/**',
        '.output/**',
        'dist/**',
        'public/**',
        'node_modules/**',
      ],
    },
  },
})
