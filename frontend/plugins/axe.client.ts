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

  // vue-axe ships its overlay UI with a bundled Tailwind v2 preflight that
  // includes unlayered universal resets like `* { --tw-shadow: 0 0 #0000; }`.
  // In the CSS Cascade Layers spec, unlayered rules trump any layered rule
  // regardless of specificity — so those `*` resets stomp on every
  // `.shadow-*` utility we emit from Tailwind v4's `@layer utilities`, which
  // causes all Tailwind shadows (including custom arbitrary values) to be
  // forced back to `0 0 #0000` for every element in the page.
  // Strip them surgically. vue-axe's own overlay doesn't rely on these
  // resets at runtime — it sets its own shadows via `.va-shadow-*` classes.
  const sanitizeVueAxeStylesheet = (style: HTMLStyleElement) => {
    if (style.dataset.jclawVaxePatched === '1') return
    const txt = style.textContent
    if (!txt?.includes('.va-shadow-lg')) return
    style.textContent = txt.replace(/\*\s*\{\s*--tw-[^}]*\}/g, '')
    style.dataset.jclawVaxePatched = '1'
  }
  document.querySelectorAll('style').forEach(s => sanitizeVueAxeStylesheet(s as HTMLStyleElement))
  new MutationObserver((mutations) => {
    for (const m of mutations) {
      for (const node of Array.from(m.addedNodes)) {
        if (node instanceof HTMLStyleElement) sanitizeVueAxeStylesheet(node)
      }
    }
  }).observe(document.head, { childList: true, subtree: true })
})
