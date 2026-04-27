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
    // emitted six-plus times per `nuxi generate` by @tailwindcss/vite
    // (Oxide engine) and nuxt:module-preload-polyfill. Both plugins
    // transform code without producing sourcemap entries, which Rollup
    // flags as a chain-integrity issue regardless of the user's
    // sourcemap config — none of the obvious knobs (nuxt.sourcemap,
    // vite.build.sourcemap, nitro.sourceMap) suppress them. The
    // transformations themselves are correct; the warning is purely
    // about a missing-map metadata gap in upstream plugins. Forwarding
    // every other warning class to the default handler keeps real
    // diagnostics visible.
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
