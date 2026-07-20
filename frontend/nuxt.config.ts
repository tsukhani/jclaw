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
    // Silence a cosmetic Rolldown warning from @nuxt/nitro-server's generated
    // h3.mjs re-export glue: it re-exports h3 symbols (H3Error, getCookie, ...)
    // the local module never uses, which Rolldown flags as unused external
    // imports. Third-party code, harmless — drop once Nitro's codegen is fixed.
    rollupConfig: {
      onwarn(warning, defaultHandler) {
        if (warning.message?.includes('imported from external module')
          && warning.message?.includes('h3')) return
        defaultHandler(warning)
      },
    },
  },

  vite: {
    plugins: [tailwindcss()],
    // The User Guide imports `../../docs/user-guide/*.md?raw` so the
    // canonical operator-facing copy can live next to the rest of the
    // repo's docs. Vite's default fs.allow root is the frontend/ folder;
    // widen it one level to the repo root so those imports resolve.
    server: {
      fs: {
        allow: ['..'],
      },
    },
    // Pre-bundle deps Vite otherwise discovers at runtime on the first
    // page load — avoids the "Vite discovered new dependencies" reload
    // that makes cold `pnpm dev` starts (and container restarts) flicker.
    // Keep in sync with the runtime-discovery log in dev output: these are
    // imported by shadcn-nuxt components + the chat-page markdown pipeline.
    optimizeDeps: {
      include: [
        'reka-ui',
        '@lucide/vue',
        '@vueuse/core',
        'clsx',
        'tailwind-merge',
        'class-variance-authority',
        'marked',
        'dompurify',
        'driver.js',
      ],
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
