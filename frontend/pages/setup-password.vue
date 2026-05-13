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
function resolveEffectiveTheme(saved: string | null, prefersDark: boolean): 'dark' | 'light' {
  if (saved === 'dark') return 'dark'
  if (saved === 'light') return 'light'
  return prefersDark ? 'dark' : 'light'
}

onMounted(() => {
  const saved = localStorage.getItem('jclaw-theme')
  const prefersDark = window.matchMedia('(prefers-color-scheme: dark)').matches
  const effective = resolveEffectiveTheme(saved, prefersDark)
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
  <div
    class="relative min-h-screen flex items-center justify-center p-6
           bg-white dark:bg-surface"
  >
    <!--
      Ambient green wash — two layered radial gradients from the top
      corners give the backdrop Unsloth's soft "alive" feel. Matches
      Unsloth's green-500 (rgb 34 197 94) at the same alphas / overlay
      opacity they use. Emerald-500 read too cool next to Unsloth's
      purer green even at ~7% effective alpha.
    -->
    <div
      aria-hidden="true"
      class="pointer-events-none absolute inset-0 opacity-60"
      :style="{
        backgroundImage:
          'radial-gradient(circle at 20% 15%, rgb(34 197 94 / 0.113), transparent 70%), '
          + 'radial-gradient(circle at 80% 10%, rgb(34 197 94 / 0.088), transparent 75%)',
      }"
    />
    <!--
      Card ring + shadow ported faithfully from Unsloth: 1px ring in the
      mint-tinted pale grey #dfe7e3 (their oklch(0.9208 0.0101 164.854))
      layered under a soft rgba(0,0,0,0.1) 0 4px 16px drop shadow. Dark
      theme keeps our existing tokens — Unsloth's light-theme tint would
      look wrong against our dark surfaces.
    -->
    <div
      class="relative z-10 w-full max-w-sm bg-surface-elevated rounded-[26px]
             ring-1 ring-[#dfe7e3] dark:ring-fg-muted/20
             shadow-[0_4px_16px_#0000001a] dark:shadow-[0_4px_16px_#0000004d]
             px-6 py-8"
    >
      <div class="flex flex-col items-center mb-6">
        <img
          src="/clawdia-waving.webp"
          alt="JClaw"
          width="119"
          height="128"
          class="select-none mb-2"
        >
        <h1 class="text-2xl font-semibold text-fg-strong">
          Setup your account
        </h1>
        <p class="mt-1 text-sm text-fg-muted">
          Choose a new password
        </p>
      </div>

      <form
        class="space-y-6"
        @submit.prevent="handleSubmit"
      >
        <label
          :for="newPasswordId"
          class="block"
        >
          <span class="block text-sm font-medium text-fg-strong mb-2">New password</span>
          <div class="relative">
            <!-- NOSONAR(Web:S6840) — autocomplete="new-password" is a valid WHATWG token; the type attribute is bound dynamically (:type) to toggle visibility between 'password' and 'text', which Sonar's static analyzer cannot resolve. -->
            <input
              :id="newPasswordId"
              v-model="newPassword"
              :type="showNew ? 'text' : 'password'"
              autocomplete="new-password"
              class="w-full h-9 pl-4 pr-10 rounded-[26px] text-sm text-fg-strong
                     bg-[#dfe7e3]/30 border border-[#dfe7e3]
                     dark:bg-fg-muted/10 dark:border-border
                     focus:outline-hidden focus:ring-2 focus:ring-emerald-500/40
                     transition-colors"
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
          <span class="block text-sm font-medium text-fg-strong mb-2">Confirm password</span>
          <div class="relative">
            <!-- NOSONAR(Web:S6840) — autocomplete="new-password" is a valid WHATWG token; the type attribute is bound dynamically (:type) to toggle visibility between 'password' and 'text', which Sonar's static analyzer cannot resolve. -->
            <input
              :id="confirmPasswordId"
              v-model="confirmPassword"
              :type="showConfirm ? 'text' : 'password'"
              autocomplete="new-password"
              class="w-full h-9 pl-4 pr-10 rounded-[26px] text-sm text-fg-strong
                     bg-[#dfe7e3]/30 border border-[#dfe7e3]
                     dark:bg-fg-muted/10 dark:border-border
                     focus:outline-hidden focus:ring-2 focus:ring-emerald-500/40
                     transition-colors"
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
          class="w-full h-9 rounded-[26px] text-sm font-medium text-white
                 bg-emerald-600 hover:bg-emerald-500
                 disabled:bg-emerald-600/40 disabled:text-white/70 disabled:cursor-not-allowed
                 transition-colors"
        >
          {{ loading ? 'Saving…' : 'Change password' }}
        </button>
      </form>
    </div>
  </div>
</template>
