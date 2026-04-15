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
const mascotSrc: Record<TimeOfDay, string> = {
  morning: '/mascot-morning.gif',
  afternoon: '/mascot.gif',
  evening: '/mascot-evening.gif',
}
const greeting: Record<TimeOfDay, string> = {
  morning: 'Good morning',
  afternoon: 'Good day',
  evening: 'Good evening',
}

// Easter egg: click the mascot to cycle through the day's other avatars.
// Greeting stays pinned to the actual time of day — only the image rotates.
const periods: TimeOfDay[] = ['morning', 'afternoon', 'evening']
const mascotPeriod = ref<TimeOfDay>(period)
function cycleMascot() {
  const next = (periods.indexOf(mascotPeriod.value) + 1) % periods.length
  mascotPeriod.value = periods[next]
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
  } else {
    error.value = 'Invalid username or password'
  }
}
</script>

<template>
  <div class="min-h-screen bg-white dark:bg-neutral-950 flex items-center justify-center">
    <div class="w-full max-w-sm">
      <div class="mb-4 flex items-center justify-center gap-4">
        <img :src="mascotSrc[mascotPeriod]" alt="JClaw" class="w-32 h-32 rounded-full shrink-0 cursor-pointer select-none" @click="cycleMascot" />
        <h1 class="text-4xl font-semibold tracking-[0.075em]">
          <span class="text-emerald-700 dark:text-emerald-400">J</span><span class="text-red-600 dark:text-red-500">Claw</span>
        </h1>
      </div>
      <p class="mb-8 text-center text-base text-neutral-500">{{ greeting[period] }}! Sign in to continue</p>

      <form @submit.prevent="handleLogin" class="space-y-4">
        <div>
          <label class="block text-xs font-medium text-neutral-600 dark:text-neutral-400 mb-1.5">Username</label>
          <input
            v-model="username"
            type="text"
            autocomplete="username"
            class="w-full px-3 py-2 bg-neutral-50 dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 text-neutral-900 dark:text-white text-sm
                   focus:outline-none focus:border-neutral-400 dark:focus:border-neutral-600 transition-colors"
            placeholder="admin"
          />
        </div>

        <div>
          <label class="block text-xs font-medium text-neutral-600 dark:text-neutral-400 mb-1.5">Password</label>
          <input
            v-model="password"
            type="password"
            autocomplete="current-password"
            class="w-full px-3 py-2 bg-neutral-50 dark:bg-neutral-900 border border-neutral-200 dark:border-neutral-800 text-neutral-900 dark:text-white text-sm
                   focus:outline-none focus:border-neutral-400 dark:focus:border-neutral-600 transition-colors"
          />
        </div>

        <p v-if="error" class="text-sm text-red-400">{{ error }}</p>

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
