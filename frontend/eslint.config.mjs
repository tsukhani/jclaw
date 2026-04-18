// @ts-check
//
// Flat ESLint config for the Nuxt 4 frontend.
//
// `@nuxt/eslint` generates the base config (Vue SFC support, TypeScript,
// auto-import awareness, stylistic rules). We chain project overrides on top
// via `withNuxt(...)`. Stylistic rules are enabled in `nuxt.config.ts`, so
// `pnpm format` (= `eslint . --fix`) handles layout the way Prettier would,
// while `pnpm lint` covers logic + Vue + TS correctness.
//
// See https://eslint.nuxt.com/ for the full rule list.

import withNuxt from './.nuxt/eslint.config.mjs'

export default withNuxt(
  {
    ignores: [
      '.nuxt',
      '.output',
      'dist',
      'node_modules',
      'playwright-report',
      'test-results',
      'tests/e2e/.auth',
      'components/ui/**', // shadcn-nuxt copies these in verbatim — don't lint vendor UI
    ],
  },
  {
    rules: {
      // Nuxt pages / layouts are intentionally single-word per the framework's conventions.
      'vue/multi-word-component-names': 'off',
    },
  },
)
