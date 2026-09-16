<script setup lang="ts">
/**
 * JCLAW-1131: the one renderer for a failed API call — the headline plus the three actionable
 * parts (what broke, what to check, how to retry) the backend registry keys off the error code.
 *
 * Styled with the `text-danger` token, which `assets/css/tailwind.css` tunes to ≥4.5:1 on both
 * `--muted` and `--surface-elevated` in either theme. The raw `text-red-700 dark:text-red-400`
 * pair most panels still use is not that token and is not tuned against those backgrounds.
 *
 * It also supplies `bg-surface-elevated` itself (JCLAW-1213). Without a background of its own the
 * guarantee above held only where a caller happened to drop it on one of those two surfaces —
 * measured at 4.64:1 inside the providers panel's `bg-blue-50` container, which clears AA but by
 * 0.14, and no gate here can see composed contrast: jsdom has no computed colour and stylelint
 * checks declarations, not what they compose to. Owning the surface makes the claim true wherever
 * the component is placed, and lifts the floor to 4.93:1 — `--surface-elevated` is the roomier of
 * the two tuned backgrounds, `--muted` being the 4.64 one the token was tuned to just clear.
 */
import type { ApiErrorDetails } from '~/types/api'

withDefaults(defineProps<{
  error: ApiErrorDetails | null
  /** Replaces the headline where the caller knows more than the server did — which provider, which form. */
  headline?: string
  /**
   * Re-runs the failed call (JCLAW-1137). Absent means no button, and absent is the default on
   * purpose: only the caller knows whether the request that failed is safe to repeat, and the
   * component cannot tell. One error ref can cover operations of opposite safety — the prompt
   * form uses the same ref for an idempotent PUT and a non-idempotent POST — so a caller passes
   * this per call, never per component.
   */
  retry?: () => unknown
  /** True while the retry is in flight, so a second click cannot fire a second request. */
  retrying?: boolean
}>(), {
  headline: undefined,
  retry: undefined,
  retrying: false,
})
</script>

<template>
  <div
    v-if="error"
    class="text-xs text-danger bg-surface-elevated space-y-0.5"
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
    <button
      v-if="retry"
      type="button"
      class="mt-1 text-xs font-medium underline underline-offset-2 disabled:opacity-60 disabled:cursor-not-allowed"
      :disabled="retrying"
      data-testid="api-error-retry"
      @click="retry()"
    >
      {{ retrying ? 'Retrying…' : 'Retry' }}
    </button>
  </div>
</template>
