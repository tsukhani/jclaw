<script setup lang="ts">
definePageMeta({ layout: false })

const { login } = useAuth()
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

type TimeOfDay = 'morning' | 'afternoon' | 'evening'
function timeOfDay(hour: number): TimeOfDay {
  if (hour >= 5 && hour < 12) return 'morning'
  if (hour >= 12 && hour < 22) return 'afternoon'
  return 'evening'
}
const period = timeOfDay(new Date().getHours())
const greeting: Record<TimeOfDay, string> = {
  morning: 'Good morning',
  afternoon: 'Good day',
  evening: 'Good evening',
}

// The login screen is always light, regardless of the OS color scheme or
// any stored preference. The stored theme belongs to the signed-in user and
// the default layout's useTheme reapplies it after sign-in, so forcing light
// here is purely a logged-out presentation choice.
onMounted(() => {
  document.documentElement.classList.remove('dark')
})

async function handleLogin() {
  error.value = ''
  loading.value = true
  const success = await login(username.value, password.value)
  loading.value = false
  if (success) {
    navigateTo('/')
  }
  else {
    error.value = 'Invalid username or password'
  }
}

// A11y: stable ids for label/control association
const usernameId = useId()
const passwordId = useId()
</script>

<template>
  <div class="min-h-screen bg-surface flex items-center justify-center">
    <div class="w-full max-w-sm">
      <div class="mb-4 flex items-center justify-center gap-4">
        <img
          src="/mascot.gif"
          alt="JClaw"
          class="w-32 h-32 rounded-full shrink-0 select-none"
        >
        <h1 class="text-4xl font-semibold tracking-wider">
          <span class="text-emerald-700 dark:text-emerald-400">J</span><span class="text-red-600 dark:text-red-500">Claw</span>
        </h1>
      </div>
      <p class="mb-8 text-center text-base text-fg-muted">
        {{ greeting[period] }}! Sign in to continue
      </p>

      <form
        class="space-y-4"
        @submit.prevent="handleLogin"
      >
        <label
          :for="usernameId"
          class="block"
        >
          <span class="block text-xs font-medium text-fg-muted mb-1.5">Username</span>
          <input
            :id="usernameId"
            v-model="username"
            type="text"
            autocomplete="username"
            class="w-full px-3 py-2 bg-surface-elevated border border-border text-fg-strong text-sm
                   focus:outline-hidden focus:border-input transition-colors"
            placeholder="admin"
          >
        </label>

        <label
          :for="passwordId"
          class="block"
        >
          <span class="block text-xs font-medium text-fg-muted mb-1.5">Password</span>
          <input
            :id="passwordId"
            v-model="password"
            type="password"
            autocomplete="current-password"
            class="w-full px-3 py-2 bg-surface-elevated border border-border text-fg-strong text-sm
                   focus:outline-hidden focus:border-input transition-colors"
          >
        </label>

        <p
          v-if="error"
          class="text-sm text-red-400"
        >
          {{ error }}
        </p>

        <button
          type="submit"
          :disabled="loading || !username || !password"
          class="w-full py-2 bg-emerald-600 text-white text-sm font-medium
                 hover:bg-emerald-500 disabled:opacity-40 disabled:cursor-not-allowed
                 transition-colors"
        >
          {{ loading ? 'Signing in...' : 'Sign in' }}
        </button>
      </form>
    </div>
  </div>
</template>
