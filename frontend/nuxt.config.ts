import tailwindcss from '@tailwindcss/vite'

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
        target: 'http://localhost:9000/api',
        changeOrigin: true
      }
    }
  },

  // Proxy API requests in production (SSR mode)
  routeRules: {
    '/api/**': { proxy: 'http://localhost:9000/api/**' }
  },

  compatibilityDate: '2025-01-01'
})
