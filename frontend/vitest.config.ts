import { defineVitestConfig } from '@nuxt/test-utils/config'

// Node 25 turned its own Web Storage API on by default, and that partial
// implementation shadows jsdom's: `localStorage.getItem` resolves undefined and
// 93 specs fail on what looks like a jsdom bug. `--no-webstorage` hands the
// global back to jsdom. Gated on the major because Node 24 rejects the flag
// outright ("node: bad option"), and this repo has to run on both while the
// Node 26 rollout lands. Drop the gate once nothing builds on <25.
// Tracking: vitest-dev/vitest#8757.
// Set on the env rather than poolOptions.execArgv: the pool ignores execArgv here
// (verified — the workers' process.execArgv carried vitest's own flags and not
// this one), while NODE_OPTIONS is inherited by every worker vitest forks.
const nodeMajor = Number.parseInt(process.versions.node.split('.')[0] ?? '0', 10)
if (nodeMajor >= 25) {
  process.env.NODE_OPTIONS = `${process.env.NODE_OPTIONS ?? ''} --no-webstorage`.trim()
}

export default defineVitestConfig({
  test: {
    environment: 'nuxt',
    environmentOptions: {
      nuxt: {
        domEnvironment: 'jsdom',
      },
    },
    // jsdom lacks a few DOM APIs the app calls (matchMedia, scrollIntoView,
    // DataTransfer); test/setup.ts polyfills them globally.
    setupFiles: ['./test/setup.ts'],
    // Playwright E2E specs live under tests/e2e/ and use @playwright/test, not
    // Vitest. Exclude them so `pnpm test` only runs the Vitest unit suite.
    exclude: ['**/node_modules/**', '**/dist/**', '**/.{idea,git,cache,output,temp}/**', 'tests/e2e/**'],
    // `junit` feeds the Jenkinsfile's junit step so the frontend suite reaches the
    // Test Result Trend; without it a frontend regression showed only as a red build
    // with no test detail. `default` stays first because ./jclaw.sh test parses its
    // "Test Files"/"Tests"/"Duration" lines for the summary it prints.
    reporters: [
      'default',
      'junit',
      // Sonar reads test execution from its own Generic Execution format, not from
      // JUnit XML, so this is a second report rather than a reuse of the first.
      // onWritePath receives the path relative to process.cwd() — always frontend/,
      // since that is where pnpm runs — while Sonar resolves against the repo root,
      // so the prefix is what makes each path match an indexed file. Unlike lcov,
      // the Generic Execution importer will not guess: an unresolvable path is
      // dropped with a warning and the file silently reports no tests.
      ['vitest-sonar-reporter', {
        outputFile: 'test-report/sonar.xml',
        onWritePath: (path: string) => `frontend/${path}`,
      }],
    ],
    outputFile: { junit: 'test-report/junit.xml' },
    coverage: {
      // v8 is the native Vitest coverage provider (istanbul requires a
      // separate Babel transform); both emit Sonar-compatible lcov but v8
      // is lighter and ships in @vitest/coverage-v8 matching the vitest
      // major. Activated by `pnpm test --coverage` in the Jenkinsfile — no `--`
      // separator, or pnpm ends the flags and vitest reads `--coverage` as a
      // test-file pattern, producing a silent pass with no coverage at all.
      //
      // `lcov` is what sonar.javascript.lcov.reportPaths consumes; `text`
      // keeps a human-readable summary in the test log; `html` lets us
      // open coverage/index.html locally when chasing a missed branch.
      provider: 'v8',
      reporter: ['text', 'lcov', 'html'],
      reportsDirectory: 'coverage',
      // layouts/ must stay listed: sonar.coverage.exclusions does not exclude
      // it, so anything omitted here is counted by Sonar with no coverage data
      // and reports as 0% however well it is tested.
      include: ['components/**', 'composables/**', 'layouts/**', 'pages/**', 'plugins/**', 'utils/**'],
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
