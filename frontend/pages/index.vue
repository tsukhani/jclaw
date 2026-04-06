<script setup lang="ts">
const { data: status } = await useFetch('/api/status')
</script>

<template>
  <div>
    <div class="mb-8">
      <h2 class="text-2xl font-semibold text-gray-900 mb-2">Your application is ready</h2>
      <p class="text-gray-500">Nuxt 3 frontend connected to Play backend.</p>
    </div>

    <div class="bg-white border border-gray-200 rounded-lg p-6">
      <h3 class="text-sm font-medium text-gray-500 uppercase tracking-wide mb-3">API Status</h3>
      <pre v-if="status" class="text-sm text-gray-700 bg-gray-50 rounded p-4 overflow-auto">{{ JSON.stringify(status, null, 2) }}</pre>
      <p v-else class="text-sm text-gray-400">
        Start the Play backend with <code class="bg-gray-100 px-1.5 py-0.5 rounded text-xs">play run</code> to see API data.
      </p>
    </div>

    <div class="mt-8 text-sm text-gray-400 space-y-1">
      <p>Frontend: <code class="bg-gray-100 px-1.5 py-0.5 rounded text-xs">cd frontend && pnpm dev</code> (port 3000)</p>
      <p>Backend: <code class="bg-gray-100 px-1.5 py-0.5 rounded text-xs">play run</code> (port 9000)</p>
      <p>API calls from the frontend are proxied to the backend via <code class="bg-gray-100 px-1.5 py-0.5 rounded text-xs">/api/**</code>.</p>
    </div>
  </div>
</template>
