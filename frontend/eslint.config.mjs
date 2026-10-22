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
import noBareWriteFetch from './eslint/no-bare-write-fetch.mjs'

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
  {
    // JCLAW-1218: the API error envelope is type/code/message. Five surfaces read a retired `error`
    // key off the response body and showed a stock fallback instead of the server's reason, while
    // a test mocking that same retired key kept passing. Read failures through apiErrorDetails().
    files: ['**/*.{ts,vue}'],
    ignores: ['test/**', 'tests/**'],
    rules: {
      'no-restricted-syntax': ['error',
        {
          selector: 'MemberExpression[property.name=\'error\'][object.property.name=\'data\']',
          message: 'API errors carry `message`, not `error`. Use apiErrorDetails(e).message.',
        },
        {
          selector: 'MemberExpression[property.name=\'error\'][object.name=\'data\']',
          message: 'API errors carry `message`, not `error`. Use apiErrorDetails(e).message.',
        },
      ],
    },
  },
  {
    // A write's failure has to arrive as ApiErrorDetails; the two wrappers and the Settings store
    // are the only places a bare write may live. See eslint/no-bare-write-fetch.mjs.
    files: ['**/*.{ts,vue}'],
    ignores: ['test/**', 'tests/**'],
    plugins: { jclaw: { rules: { 'no-bare-write-fetch': noBareWriteFetch } } },
    rules: {
      'jclaw/no-bare-write-fetch': ['error', {
        allowFiles: [
          'composables/useApiMutation.ts',
          'composables/useSaveAttempt.ts',
          'composables/useSettingsConfig.ts',
        ],
      }],
    },
  },
  // Vue accessibility (WCAG) rules, Vue SFCs only.
  ...vueA11y.configs['flat/recommended'],
)
