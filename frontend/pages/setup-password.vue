<script setup lang="ts">
import { EyeIcon, EyeSlashIcon } from '@heroicons/vue/24/outline'

definePageMeta({ layout: false })

const { setupPassword, login } = useAuth()

const newPassword = ref('')
const confirmPassword = ref('')
const showNew = ref(false)
const showConfirm = ref(false)
const error = ref('')
const loading = ref(false)

// Respect the stored theme preference (unlike /login which forces light).
// The setup screen is the user's first impression — if they've already
// toggled dark mode via system preference it'd be jarring to flash light.
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

const canSubmit = computed(() =>
  newPassword.value.length >= 8
  && newPassword.value === confirmPassword.value
  && !loading.value,
)

async function handleSubmit() {
  if (!canSubmit.value) return
  error.value = ''
  loading.value = true
  const result = await setupPassword(newPassword.value)
  if (result.ok) {
    // Auto-login so the user doesn't have to retype the password they
    // just chose — friendlier first-run experience.
    const ok = await login('admin', newPassword.value)
    if (ok) {
      loading.value = false
      navigateTo('/')
      return
    }
    loading.value = false
    navigateTo('/login')
    return
  }
  loading.value = false
  if (result.error === 'already_set') {
    navigateTo('/login')
    return
  }
  if (result.error === 'password_too_short') {
    error.value = 'Password must be at least 8 characters.'
    return
  }
  error.value = 'Something went wrong. Please try again.'
}

const newPasswordId = useId()
const confirmPasswordId = useId()
</script>

<template>
  <div class="min-h-screen bg-surface flex items-center justify-center p-6">
    <div
      class="w-full max-w-md bg-surface-elevated border border-fg-muted/20 rounded-2xl
             shadow-[0_12px_40px_rgba(0,0,0,0.08)] dark:shadow-[0_12px_40px_rgba(0,0,0,0.35)]
             p-8"
    >
      <div class="flex flex-col items-center mb-6">
        <img
          src="/mascot-waving.png"
          alt="JClaw"
          class="h-24 w-auto select-none mb-4"
        >
        <h1 class="text-2xl font-bold text-fg-strong">
          Setup your account
        </h1>
        <p class="mt-1 text-sm text-fg-muted">
          Choose a new password
        </p>
      </div>

      <form
        class="space-y-4"
        @submit.prevent="handleSubmit"
      >
        <label
          :for="newPasswordId"
          class="block"
        >
          <span class="block text-sm font-semibold text-fg-strong mb-2">New password</span>
          <div class="relative">
            <input
              :id="newPasswordId"
              v-model="newPassword"
              :type="showNew ? 'text' : 'password'"
              autocomplete="new-password"
              class="w-full pl-4 pr-11 py-2.5 rounded-full text-sm text-fg-strong
                     bg-muted border-0 focus:outline-hidden focus:ring-2 focus:ring-emerald-500/40
                     transition-shadow"
            >
            <button
              type="button"
              class="absolute inset-y-0 right-3 flex items-center text-fg-muted hover:text-fg-strong
                     transition-colors"
              :title="showNew ? 'Hide password' : 'Show password'"
              @click="showNew = !showNew"
            >
              <component
                :is="showNew ? EyeSlashIcon : EyeIcon"
                class="w-5 h-5"
                aria-hidden="true"
              />
            </button>
          </div>
        </label>

        <label
          :for="confirmPasswordId"
          class="block"
        >
          <span class="block text-sm font-semibold text-fg-strong mb-2">Confirm password</span>
          <div class="relative">
            <input
              :id="confirmPasswordId"
              v-model="confirmPassword"
              :type="showConfirm ? 'text' : 'password'"
              autocomplete="new-password"
              class="w-full pl-4 pr-11 py-2.5 rounded-full text-sm text-fg-strong
                     bg-muted border-0 focus:outline-hidden focus:ring-2 focus:ring-emerald-500/40
                     transition-shadow"
            >
            <button
              type="button"
              class="absolute inset-y-0 right-3 flex items-center text-fg-muted hover:text-fg-strong
                     transition-colors"
              :title="showConfirm ? 'Hide password' : 'Show password'"
              @click="showConfirm = !showConfirm"
            >
              <component
                :is="showConfirm ? EyeSlashIcon : EyeIcon"
                class="w-5 h-5"
                aria-hidden="true"
              />
            </button>
          </div>
        </label>

        <p class="text-xs text-fg-muted">
          Must be at least 8 characters.
        </p>

        <p
          v-if="error"
          class="text-sm text-red-500 dark:text-red-400"
        >
          {{ error }}
        </p>

        <button
          type="submit"
          :disabled="!canSubmit"
          class="w-full py-3 rounded-full text-sm font-semibold text-white
                 bg-emerald-600 hover:bg-emerald-500
                 disabled:bg-emerald-600/40 disabled:text-white/70 disabled:cursor-not-allowed
                 transition-colors"
        >
          {{ loading ? 'Saving…' : 'Change password' }}
        </button>

        <p class="text-center text-sm text-fg-muted pt-2">
          Password already setup?
          <NuxtLink
            to="/login"
            class="text-emerald-700 dark:text-emerald-400 hover:underline font-medium"
          >
            Back to login
          </NuxtLink>
        </p>
      </form>
    </div>
  </div>
</template>
