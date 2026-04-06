<script setup lang="ts">
definePageMeta({ layout: false })

const { login } = useAuth()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

async function handleLogin() {
  error.value = ''
  loading.value = true
  const success = await login(username.value, password.value)
  loading.value = false
  if (success) {
    navigateTo('/')
  } else {
    error.value = 'Invalid username or password'
  }
}
</script>

<template>
  <div class="min-h-screen bg-neutral-950 flex items-center justify-center">
    <div class="w-full max-w-sm">
      <div class="mb-8 text-center">
        <h1 class="text-xl font-semibold text-white tracking-tight">JClaw</h1>
        <p class="text-sm text-neutral-500 mt-1">Sign in to continue</p>
      </div>

      <form @submit.prevent="handleLogin" class="space-y-4">
        <div>
          <label class="block text-xs font-medium text-neutral-400 mb-1.5">Username</label>
          <input
            v-model="username"
            type="text"
            autocomplete="username"
            class="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 text-white text-sm
                   focus:outline-none focus:border-neutral-600 transition-colors"
            placeholder="admin"
          />
        </div>

        <div>
          <label class="block text-xs font-medium text-neutral-400 mb-1.5">Password</label>
          <input
            v-model="password"
            type="password"
            autocomplete="current-password"
            class="w-full px-3 py-2 bg-neutral-900 border border-neutral-800 text-white text-sm
                   focus:outline-none focus:border-neutral-600 transition-colors"
          />
        </div>

        <p v-if="error" class="text-sm text-red-400">{{ error }}</p>

        <button
          type="submit"
          :disabled="loading || !username || !password"
          class="w-full py-2 bg-white text-neutral-950 text-sm font-medium
                 hover:bg-neutral-200 disabled:opacity-40 disabled:cursor-not-allowed
                 transition-colors"
        >
          {{ loading ? 'Signing in...' : 'Sign in' }}
        </button>
      </form>
    </div>
  </div>
</template>
