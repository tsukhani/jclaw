import tailwindcss from '@tailwindcss/vite'

const backendPort = process.env.JCLAW_BACKEND_PORT || '9000'
const backendUrl = `http://localhost:${backendPort}`

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({

  modules: ['shadcn-nuxt', '@nuxt/eslint'],
  ssr: false,

  devtools: { enabled: true },

  css: ['~/assets/css/tailwind.css'],

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
