<script setup lang="ts">
import { EyeIcon, EyeSlashIcon } from '@heroicons/vue/24/outline'

definePageMeta({ layout: false })

const { login } = useAuth()
const password = ref('')
const showPassword = ref(false)
const error = ref('')
const loading = ref(false)

// Respect the stored theme preference (same logic as /setup-password).
// Previously this page forced light mode — but once the user has set a
// password and picked a theme, returning to /login after sign-out in dark
// mode would flash them white. Stay consistent with whatever they chose.
onMounted(() => {
  const saved = localStorage.getItem('jclaw-theme')
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  const effective = saved === 'dark'
    ? 'dark'
    : saved === 'light'
      ? 'light'
      : prefersDark ? 'dark' : 'light'
  document.documentElement.classList.toggle('dark', effective === 'dark')
})

async function handleLogin() {
  error.value = ''
  loading.value = true
  // Username is hardcoded to "admin" — JClaw is single-admin, the
  // frontend doesn't surface the username-override path (conf still has
  // jclaw.admin.username for operators who absolutely need it, but
  // changing it breaks this login form; documented trade-off for UX).
  const success = await login('admin', password.value)
  loading.value = false
  if (success) navigateTo('/')
  else error.value = 'Invalid password'
}

const passwordId = useId()
</script>

<template>
  <div
    class="min-h-screen flex items-center justify-center p-6
           bg-gradient-to-br from-emerald-50 via-white to-emerald-50
           dark:bg-surface dark:from-surface dark:via-surface dark:to-surface"
  >
    <div
      class="w-full max-w-md bg-surface-elevated border border-fg-muted/20 rounded-2xl
             shadow-[0_12px_40px_rgba(0,0,0,0.08)] dark:shadow-[0_12px_40px_rgba(0,0,0,0.35)]
             p-8"
    >
      <div class="flex flex-col items-center mb-6">
        <img
          src="/mascot-waving.png"
          alt="JClaw"
          class="h-32 w-auto select-none mb-4"
        >
        <h1 class="text-2xl font-bold text-fg-strong">
          Welcome back
        </h1>
        <p class="mt-1 text-sm text-fg-muted">
          Sign in with your password.
        </p>
      </div>

      <form
        class="space-y-4"
        @submit.prevent="handleLogin"
      >
        <label
          :for="passwordId"
          class="block"
        >
          <span class="block text-sm font-semibold text-fg-strong mb-2">Password</span>
          <div class="relative">
            <input
              :id="passwordId"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
              class="w-full pl-4 pr-11 py-2.5 rounded-full text-sm text-fg-strong
                     bg-muted border-0 focus:outline-hidden focus:ring-2 focus:ring-emerald-500/40
                     transition-shadow"
            >
            <button
              type="button"
              class="absolute inset-y-0 right-3 flex items-center text-fg-muted hover:text-fg-strong
                     transition-colors"
              :title="showPassword ? 'Hide password' : 'Show password'"
              @click="showPassword = !showPassword"
            >
              <component
                :is="showPassword ? EyeSlashIcon : EyeIcon"
                class="w-5 h-5"
                aria-hidden="true"
              />
            </button>
          </div>
        </label>

        <p
          v-if="error"
          class="text-sm text-red-500 dark:text-red-400"
        >
          {{ error }}
        </p>

        <button
          type="submit"
          :disabled="loading || !password"
          class="w-full py-3 rounded-full text-sm font-semibold text-white
                 bg-emerald-600 hover:bg-emerald-500
                 disabled:bg-emerald-600/40 disabled:text-white/70 disabled:cursor-not-allowed
                 transition-colors"
        >
          {{ loading ? 'Signing in…' : 'Login' }}
        </button>
      </form>
    </div>
  </div>
</template>
