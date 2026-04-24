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
          src="/mascot-waving.png"
          alt="JClaw"
          width="119"
          height="128"
          class="select-none mb-2"
        >
        <h1 class="text-2xl font-semibold text-fg-strong">
          Welcome back
        </h1>
        <p class="mt-1 text-sm text-fg-muted">
          Sign in with your password.
        </p>
      </div>

      <form
        class="space-y-6"
        @submit.prevent="handleLogin"
      >
        <!--
          Hidden username input — JClaw is single-admin so the real username
          is always "admin" (see handleLogin). Password managers and Chrome's
          autofill heuristics pair credentials by looking for a sibling
          username field; without one, they save/restore the password under
          a guessed label. sr-only keeps the visual design unchanged.
        -->
        <input
          id="login-username"
          type="text"
          name="username"
          value="admin"
          autocomplete="username"
          readonly
          tabindex="-1"
          aria-label="Username (admin)"
          class="sr-only"
        >
        <label
          :for="passwordId"
          class="block"
        >
          <span class="block text-sm font-medium text-fg-strong mb-2">Password</span>
          <div class="relative">
            <input
              :id="passwordId"
              v-model="password"
              :type="showPassword ? 'text' : 'password'"
              autocomplete="current-password"
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
          class="w-full h-9 rounded-[26px] text-sm font-medium text-white
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
