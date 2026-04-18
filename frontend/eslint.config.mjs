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
// `eslint-plugin-vuejs-accessibility` layers WCAG-oriented template rules on
// top — missing `alt`, missing form-label associations, non-keyboard-accessible
// event handlers, anchor validity, etc. Baseline findings on existing files
// are intentionally left as warnings for opportunistic cleanup; any new
// template markup is expected to be a11y-clean.
//
// See https://eslint.nuxt.com/ and https://vue-a11y.github.io/eslint-plugin-vuejs-accessibility/.

import withNuxt from './.nuxt/eslint.config.mjs'
import vueA11y from 'eslint-plugin-vuejs-accessibility'

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
  // Vue accessibility (WCAG) rules, Vue SFCs only.
  ...vueA11y.configs['flat/recommended'],
)
