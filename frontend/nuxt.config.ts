import tailwindcss from '@tailwindcss/vite'

const backendPort = process.env.JCLAW_BACKEND_PORT || '9000'
const backendUrl = `http://localhost:${backendPort}`

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({

  modules: ['shadcn-nuxt', '@nuxt/eslint'],
  ssr: false,

  devtools: { enabled: true },

  // Document-level a11y defaults + favicons. `lang` so screen readers
  // pronounce content correctly, a non-empty `<title>` so tab/history
  // labels aren't blank, and the full RealFaviconGenerator link set so
  // iOS home-screen, Android PWA, and desktop browser tabs all resolve
  // their native sizes. Favicon files live in `public/` (served as-is
  // at the site root — see public/site.webmanifest).
  app: {
    head: {
      htmlAttrs: { lang: 'en' },
      title: 'JClaw',
      link: [
        { rel: 'icon', type: 'image/x-icon', href: '/favicon.ico' },
        { rel: 'icon', type: 'image/png', sizes: '32x32', href: '/favicon-32x32.png' },
        { rel: 'icon', type: 'image/png', sizes: '16x16', href: '/favicon-16x16.png' },
        { rel: 'apple-touch-icon', sizes: '180x180', href: '/apple-touch-icon.png' },
        { rel: 'manifest', href: '/site.webmanifest' },
      ],
    },
  },

  css: [
    '~/assets/css/tailwind.css',
    'driver.js/dist/driver.css',
    '~/assets/css/driver-theme.css',
  ],

  // Proxy API requests in production (SSR mode)
  routeRules: {
    '/api/**': { proxy: `${backendUrl}/api/**` },
  },

  // NOTE: do NOT add `experimental.payloadExtraction: 'client'` here even
  // though `nuxi generate` keeps emitting "Payload extraction is recommended
  // for full-static output" on every prod start. The warning is unfixable
  // from user config in Nuxt 4.4.2 SPA mode: @nuxt/schema's resolver
  // (see node_modules/.pnpm/@nuxt+schema@4.4.2/.../dist/index.mjs,
  // `payloadExtraction.$resolve`) hardcodes `return false` whenever
  // `ssr === false`, overwriting any user value before nitro-server's
  // warning check runs. Setting it to 'client' or true compiles into
  // nuxt.config but the schema strips it, the warning fires regardless,
  // and the dev server / build behavior is identical either way. Cosmetic
  // noise only — leave alone until upstream resolves the contradiction.

  compatibilityDate: '2026-04-16',

  // Proxy API requests to the Play backend during development
  nitro: {
    devProxy: {
      '/api': {
        target: `${backendUrl}/api`,
        changeOrigin: true,
      },
    },
  },

  vite: {
    plugins: [tailwindcss()],
    // Pre-bundle deps Vite otherwise discovers at runtime on the first
    // page load — avoids the "Vite discovered new dependencies" reload
    // that makes cold `pnpm dev` starts (and container restarts) flicker.
    // Keep in sync with the runtime-discovery log in dev output: these are
    // imported by shadcn-nuxt components + the chat-page markdown pipeline.
    optimizeDeps: {
      include: [
        'reka-ui',
        'lucide-vue-next',
        '@vueuse/core',
        'clsx',
        'tailwind-merge',
        'class-variance-authority',
        'marked',
        'dompurify',
        'driver.js',
      ],
    },
    // Filter the cosmetic "Sourcemap is likely to be incorrect" warnings
    // from @tailwindcss/vite:generate:build and nuxt:module-preload-polyfill.
    // Bisected to the Nuxt 3 -> 4 upgrade in commit 021bf0b (2026-04-16):
    // pre-upgrade builds (Nuxt 3.21.2) are silent; the first build on Nuxt
    // 4.4.2 already produces 6+ of these warnings, regardless of Tailwind
    // version (verified across Tailwind 4.2.2 and 4.2.4). The warning fires
    // inside Rollup's transform-chain integrity check, before output
    // decisions, so sourcemap-disable knobs (nuxt.sourcemap,
    // vite.build.sourcemap, nitro.sourceMap) suppress the .map artifacts
    // but not the warnings.
    //
    // Tracked upstream:
    //   Nuxt:     https://github.com/nuxt/nuxt/issues/34530  (tagged "upstream")
    //   Tailwind: https://github.com/tailwindlabs/tailwindcss/issues/19930
    //
    // Fix landed in Vite 8 per the Nuxt issue thread; Nuxt 4.4.2 still
    // pins Vite 7.3.2 as of 2026-04-28. REMOVE THIS BLOCK once Nuxt's
    // lockfile picks up Vite 8 stable.
    build: {
      rollupOptions: {
        onwarn(warning, defaultHandler) {
          if (warning.message?.includes('Sourcemap is likely to be incorrect')) return
          defaultHandler(warning)
        },
      },
    },
  },

  // @nuxt/eslint: stylistic rules double as the formatter (Prettier replacement).
  // Keep overrides minimal — module defaults are sensible for Nuxt 4.
  eslint: {
    config: {
      stylistic: true,
    },
  },

  shadcn: {
    prefix: '',
    componentDir: './components/ui',
  },
})
