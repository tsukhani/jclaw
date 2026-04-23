// Minimal ambient declaration for `vue-axe` (dev-only runtime a11y scanner).
// Upstream ships no types; we only need the default export to be a valid Vue
// plugin so `nuxtApp.vueApp.use(VueAxe, options)` typechecks. The options bag
// is left loose — vue-axe passes the relevant fields straight through to
// axe-core's `axe.run()`, so we don't want to hand-roll a stale mirror here.
declare module 'vue-axe' {
  import type { Plugin } from 'vue'

  const VueAxe: Plugin
  export default VueAxe
}
