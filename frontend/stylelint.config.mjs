// Stylelint config for the Nuxt 4 frontend.
//
// Scope: custom CSS in `assets/css/*.css` and the `<style>` blocks inside
// Vue SFCs (chiefly `pages/chat.vue`, ~300 lines of custom rules). Tailwind
// utility classes in templates are NOT linted here — that's the job of
// ESLint or a future Tailwind-specific plugin.
//
// Tailwind v4 introduced CSS-first directives (`@theme`, `@custom-variant`,
// `@utility`, `@source`, `@plugin`, `@reference`, `@config`) that Stylelint's
// default at-rule rule flags as unknown. We allowlist them explicitly below
// until an upstream Tailwind-v4 preset exists. Also keep the older v1–v3
// directives (`@tailwind`, `@apply`, `@screen`, `@variants`, `@responsive`,
// `@layer`) because Tailwind's migration docs still show them in examples
// and some transitively-included CSS may rely on them.

export default {
  extends: [
    'stylelint-config-standard',
    'stylelint-config-standard-vue',
  ],
  rules: {
    'at-rule-no-unknown': [true, {
      ignoreAtRules: [
        // Tailwind v3 legacy
        'tailwind', 'apply', 'screen', 'variants', 'responsive', 'layer',
        // Tailwind v4
        'theme', 'custom-variant', 'utility', 'source', 'plugin', 'reference', 'config',
      ],
    }],
    // shadcn-vue tokens use hsl() without commas (modern syntax); don't fight it.
    'hue-degree-notation': null,
    'color-function-notation': null,
    // Nuxt/Vite dev CSS often uses numeric precision > 4 for calc() outputs.
    'number-max-precision': null,
    // Allow CSS vars inside hsl() (`hsl(var(--primary))`) — used heavily by shadcn-vue.
    'function-no-unknown': [true, { ignoreFunctions: ['theme'] }],
    // Component-scoped classes in Vue SFCs don't need to follow the kebab-case
    // selector-pattern rule — Vue's scoped style compiler handles uniqueness.
    'selector-class-pattern': null,
    'selector-id-pattern': null,
    // Allow :deep(), :slotted(), :global() — Vue SFC pseudo-classes.
    'selector-pseudo-class-no-unknown': [true, {
      ignorePseudoClasses: ['deep', 'slotted', 'global'],
    }],
  },
  overrides: [
    {
      files: ['**/*.vue'],
      customSyntax: 'postcss-html',
    },
  ],
  ignoreFiles: [
    '.nuxt/**',
    '.output/**',
    'dist/**',
    'node_modules/**',
    'playwright-report/**',
    'test-results/**',
    'components/ui/**', // shadcn-vue vendored components
  ],
}
