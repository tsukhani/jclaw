<script setup lang="ts">
/**
 * The page Nuxt shows in place of the app for an unmatched route or an unhandled error. It
 * replaces the framework's stock page, which ignores the theme and the brand. Kept free of the
 * default layout on purpose: an error thrown inside that layout would otherwise re-render here.
 * The theme plugin has already applied dark mode to the document by the time this mounts.
 */
import type { NuxtError } from '#app'

const props = defineProps<{ error: NuxtError }>()

const notFound = computed(() => props.error.statusCode === 404)
const heading = computed(() => notFound.value ? 'Page not found' : 'Something went wrong')
const detail = computed(() => {
  if (notFound.value) return 'There is nothing at this address. It may have moved, or the link may be wrong.'
  return props.error.statusMessage || props.error.message || 'An unexpected error interrupted the page.'
})
const route = useRoute()

useHead({ title: computed(() => `${heading.value} · JClaw`) })

function goHome() {
  clearError({ redirect: '/' })
}

// Clearing the error re-renders the app at the same address, which is what "try again" means.
function tryAgain() {
  clearError({ redirect: route.fullPath })
}

function goBack() {
  if (globalThis.history.length > 1) globalThis.history.back()
  else goHome()
}
</script>

<template>
  <main
    class="relative min-h-screen flex items-center justify-center p-6 bg-white dark:bg-surface"
    data-testid="error-page"
  >
    <div
      aria-hidden="true"
      class="pointer-events-none absolute inset-0 opacity-60"
      :style="{
        backgroundImage:
          'radial-gradient(circle at 20% 15%, rgb(34 197 94 / 0.113), transparent 70%), '
          + 'radial-gradient(circle at 80% 10%, rgb(34 197 94 / 0.088), transparent 75%)',
      }"
    />
    <div
      class="relative z-10 w-full max-w-md bg-surface-elevated rounded-[26px]
             ring-1 ring-[#dfe7e3] dark:ring-fg-muted/20
             shadow-[0_4px_16px_#0000001a] dark:shadow-[0_4px_16px_#0000004d]
             px-6 py-8 text-center"
    >
      <img
        src="/clawdia-waving.webp"
        alt=""
        width="119"
        height="128"
        class="select-none mx-auto mb-2"
      >
      <!-- The heading already says "not found"; the code only earns a line on a real error. -->
      <p
        v-if="!notFound"
        class="text-sm font-mono text-fg-muted"
        data-testid="error-status"
      >
        {{ error.statusCode }}
      </p>
      <h1 class="mt-1 text-2xl font-semibold text-fg-strong">
        {{ heading }}
      </h1>
      <p
        class="mt-2 text-sm text-fg-muted"
        data-testid="error-detail"
      >
        {{ detail }}
      </p>
      <p
        v-if="notFound"
        class="mt-3 text-xs font-mono text-fg-muted break-all"
        data-testid="error-path"
      >
        {{ route.fullPath }}
      </p>

      <div class="mt-6 flex flex-wrap items-center justify-center gap-3">
        <button
          type="button"
          class="rounded-md bg-emerald-700 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-800 transition-colors"
          data-testid="error-home"
          @click="goHome"
        >
          Go to Dashboard
        </button>
        <button
          v-if="notFound"
          type="button"
          class="rounded-md border border-border px-4 py-2 text-sm text-fg-primary hover:bg-muted transition-colors"
          data-testid="error-back"
          @click="goBack"
        >
          Go back
        </button>
        <button
          v-else
          type="button"
          class="rounded-md border border-border px-4 py-2 text-sm text-fg-primary hover:bg-muted transition-colors"
          data-testid="error-retry"
          @click="tryAgain"
        >
          Try again
        </button>
      </div>
    </div>
  </main>
</template>
