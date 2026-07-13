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
  const prefersDark = globalThis.matchMedia('(prefers-color-scheme: dark)').matches
  const effective = resolveEffectiveTheme(saved, prefersDark)
  document.documentElement.classList.toggle('dark', effective === 'dark')
})

// JCLAW-741: live strength meter + requirement checklist. Advisory — the
// backend re-validates length and screens for breached passwords on submit.
const strength = computed(() => estimatePasswordStrength(newPassword.value))
const lengthOk = computed(() => newPassword.value.length >= MIN_PASSWORD_LENGTH)
const passwordsMatch = computed(() =>
  confirmPassword.value.length > 0 && newPassword.value === confirmPassword.value)

const strengthBarClass = computed(() => {
  const s = strength.value.score
  if (s <= 1) return 'bg-red-500'
  if (s === 2) return 'bg-amber-500'
  return 'bg-emerald-500'
})
const strengthTextClass = computed(() => {
  const s = strength.value.score
  if (s <= 1) return 'text-red-600 dark:text-red-400'
  if (s === 2) return 'text-amber-600 dark:text-amber-400'
  return 'text-emerald-700 dark:text-emerald-400'
})

const canSubmit = computed(() =>
  lengthOk.value
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
    error.value = `Password must be at least ${MIN_PASSWORD_LENGTH} characters.`
    return
  }
  if (result.error === 'password_too_long') {
    error.value = 'That password is too long.'
    return
  }
  if (result.error === 'password_breached') {
    error.value = 'This password appears in a known data breach. Please choose a different one.'
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
            <!-- The autocomplete token new-password is the valid WHATWG value here. The type attribute is bound dynamically to toggle visibility between password and text, which Sonar's static analyser cannot resolve. -->
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
            <!-- The autocomplete token new-password is the valid WHATWG value here. The type attribute is bound dynamically to toggle visibility between password and text, which Sonar's static analyser cannot resolve. -->
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

        <!-- JCLAW-741: live strength meter + requirement checklist. Advisory;
             the server re-validates length and screens for breaches on submit. -->
        <div
          v-if="newPassword"
          class="space-y-2"
        >
          <div
            class="flex gap-1"
            aria-hidden="true"
          >
            <span
              v-for="i in 4"
              :key="i"
              class="h-1 flex-1 rounded-full transition-colors"
              :class="i <= strength.score ? strengthBarClass : 'bg-fg-muted/20'"
            />
          </div>
          <p class="text-xs text-fg-muted">
            Strength: <span :class="strengthTextClass">{{ strength.label }}</span>
          </p>
          <ul class="text-xs space-y-1">
            <li :class="lengthOk ? 'text-emerald-700 dark:text-emerald-400' : 'text-fg-muted'">
              {{ lengthOk ? '✓' : '○' }} At least {{ MIN_PASSWORD_LENGTH }} characters
            </li>
            <li :class="passwordsMatch ? 'text-emerald-700 dark:text-emerald-400' : 'text-fg-muted'">
              {{ passwordsMatch ? '✓' : '○' }} Passwords match
            </li>
          </ul>
        </div>
        <p
          v-else
          class="text-xs text-fg-muted"
        >
          Use at least {{ MIN_PASSWORD_LENGTH }} characters. Longer passphrases are stronger.
        </p>

        <p
          v-if="error"
          class="text-sm text-red-700 dark:text-red-400"
        >
          {{ error }}
        </p>

        <button
          type="submit"
          :disabled="!canSubmit"
          class="w-full h-9 rounded-[26px] text-sm font-medium text-white
                 bg-emerald-700 hover:bg-emerald-600
                 disabled:bg-emerald-700/40 disabled:text-white/70 disabled:cursor-not-allowed
                 transition-colors"
        >
          {{ loading ? 'Saving…' : 'Change password' }}
        </button>
      </form>
    </div>
  </div>
</template>
