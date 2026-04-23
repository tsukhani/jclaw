// Dev-only runtime a11y scanner. Mounts `vue-axe` into the Vue app so axe-core
// re-runs on every render cycle (debounced) and prints WCAG violations to the
// browser console. Complements the static `eslint-plugin-vuejs-accessibility`
// rules enforced by pre-commit: the linter catches template-shape issues, axe
// catches runtime-only ones (contrast, duplicated IDs, ARIA-in-context, content
// injected after hydration).
//
// The `.client.ts` suffix restricts this to the browser bundle — axe needs
// `window` and computed styles. The `import.meta.dev` guard plus dynamic
// import means vue-axe/axe-core never ship to production: Nuxt tree-shakes the
// whole branch at build time.
export default defineNuxtPlugin(async (nuxtApp) => {
  if (!import.meta.dev) return
  const { default: VueAxe } = await import('vue-axe')
  nuxtApp.vueApp.use(VueAxe, {
    // Scope to WCAG 2.0/2.1 A + AA rules. Drops best-practice and experimental
    // rules that tend to produce noisy console output on third-party markup.
    runOptions: {
      runOnly: {
        type: 'tag',
        values: ['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa'],
      },
    },
  })
})
