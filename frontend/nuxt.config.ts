import tailwindcss from '@tailwindcss/vite'

const backendPort = process.env.JCLAW_BACKEND_PORT || '9000'
const backendUrl = `http://localhost:${backendPort}`

// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  ssr: false,

  css: ['~/assets/css/tailwind.css'],

  devtools: { enabled: true },

  vite: {
    plugins: [tailwindcss()],
  },

  // Proxy API requests to the Play backend during development
  nitro: {
    devProxy: {
      '/api': {
        target: `${backendUrl}/api`,
        changeOrigin: true
      }
    }
  },

  // Proxy API requests in production (SSR mode)
  routeRules: {
    '/api/**': { proxy: `${backendUrl}/api/**` }
  },

  compatibilityDate: '2025-01-01'
})
