// https://nuxt.com/docs/api/configuration/nuxt-config
export default defineNuxtConfig({
  modules: ['@nuxtjs/tailwindcss'],

  devtools: { enabled: true },

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
