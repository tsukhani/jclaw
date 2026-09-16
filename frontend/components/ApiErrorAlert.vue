<script setup lang="ts">
/**
 * JCLAW-1131: the one renderer for a failed API call — the headline plus the three actionable
 * parts (what broke, what to check, how to retry) the backend registry keys off the error code.
 *
 * Styled with the `text-danger` token, which `assets/css/tailwind.css` tunes to ≥4.5:1 on both
 * `--muted` and `--surface-elevated` in either theme. The raw `text-red-700 dark:text-red-400`
 * pair most panels still use is not that token and is not tuned against those backgrounds.
 */
import type { ApiErrorDetails } from '~/types/api'

withDefaults(defineProps<{
  error: ApiErrorDetails | null
  /** Replaces the headline where the caller knows more than the server did — which provider, which form. */
  headline?: string
}>(), {
  headline: undefined,
})
</script>

<template>
  <div
    v-if="error"
    class="text-xs text-danger space-y-0.5"
    role="alert"
    data-testid="api-error"
  >
    <p class="font-medium">
      {{ headline ?? error.message }}
    </p>
    <!-- With no code the envelope never parsed, so `message` is the transport's raw HTTP status text. -->
    <p v-if="headline && error.code">
      {{ error.message }}
    </p>
    <template v-if="error.template">
      <p>
        <span class="font-semibold">What broke</span> — {{ error.template.whatBroke }}
      </p>
      <p>
        <span class="font-semibold">What to check</span> — {{ error.template.whatToCheck }}
      </p>
      <p v-if="error.template.howToRetry">
        <span class="font-semibold">How to retry</span> — {{ error.template.howToRetry }}
      </p>
    </template>
  </div>
</template>
