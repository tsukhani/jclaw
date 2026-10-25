<script setup lang="ts">
import { CheckIcon, ClipboardDocumentIcon } from '@heroicons/vue/24/outline'
import type { SlackBindingSummary, TelegramBindingSummary, WhatsAppBindingSummary } from '~/types/api'

const [
  { data: telegramBindings, refresh: refreshBindings },
  { data: slackBindings, refresh: refreshSlackBindings },
  { data: whatsappBindings, refresh: refreshWhatsappBindings },
] = await Promise.all([
  useFetch<TelegramBindingSummary[]>('/api/channels/telegram/bindings'),
  useFetch<SlackBindingSummary[]>('/api/channels/slack/bindings'),
  useFetch<WhatsAppBindingSummary[]>('/api/channels/whatsapp/bindings'),
])

// Funnel status loads lazily (it shells out to `tailscale status`, ~400ms) so it
// never gates the page render; shared across the channel pages via the composable.
const { data: tailscale, refresh: refreshTailscale, status: tailscaleStatus } = useTailscaleStatus()

const { mutate, errorDetails: funnelError } = useApiMutation()

// JCLAW-84: app-level Tailscale Funnel toggle. Exposes this whole instance (one
// port serves every channel webhook), so it's one switch, not per-channel.
const tailscaleToggling = ref(false)
// Enabling requires Tailscale to be installed AND connected (available); the
// disable direction stays clickable so a broken Tailscale can't trap the
// toggle in the on state.
const funnelEnableBlocked = computed(() =>
  !tailscale.value?.enabled && !tailscale.value?.available)
async function toggleTailscale() {
  tailscaleToggling.value = true
  const next = !(tailscale.value?.enabled ?? false)
  const result = await mutate('/api/tailscale', { method: 'POST', body: { enabled: next } })
  tailscaleToggling.value = false
  if (result !== null) refreshTailscale()
}

const publicUrlCopied = ref(false)
let publicUrlCopiedTimer: ReturnType<typeof setTimeout> | null = null
async function copyPublicUrl() {
  const url = tailscale.value?.publicUrl
  if (!url) return
  try {
    await navigator.clipboard.writeText(url)
    publicUrlCopied.value = true
    if (publicUrlCopiedTimer) clearTimeout(publicUrlCopiedTimer)
    publicUrlCopiedTimer = setTimeout(() => {
      publicUrlCopied.value = false
    }, 1500)
  }
  catch {
    // Clipboard unavailable (insecure context or denied) — no-op.
  }
}

const telegramActiveCount = computed(() =>
  (telegramBindings.value ?? []).filter(b => b.enabled).length)
const slackActiveCount = computed(() =>
  (slackBindings.value ?? []).filter(b => b.enabled).length)
// JCLAW-444: WhatsApp moved from a single app-global config to per-agent bindings
// (its own detail page), so it counts active bindings like Telegram/Slack.
const whatsappActiveCount = computed(() =>
  (whatsappBindings.value ?? []).filter(b => b.enabled).length)

// Re-pull the bindings summaries when the user returns from a detail page so the
// per-channel badges reflect any add/remove they did there.
onActivated(() => {
  refreshBindings()
  refreshSlackBindings()
  refreshWhatsappBindings()
})
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Channels
    </h1>

    <div
      class="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6"
      data-tour="channel-list"
    >
      <NuxtLink
        to="/channels/telegram"
        class="bg-surface-elevated border border-border p-4 block hover:border-ring transition-colors"
      >
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-sm font-medium text-fg-strong">
            Telegram
          </h2>
          <span
            :class="telegramActiveCount > 0 ? 'text-green-700 dark:text-green-400' : 'text-fg-muted'"
            class="text-xs font-mono"
          >{{ telegramActiveCount > 0 ? `${telegramActiveCount} active` : 'not configured' }}</span>
        </div>
        <span class="text-xs text-fg-muted">
          Manage bindings →
        </span>
      </NuxtLink>

      <NuxtLink
        to="/channels/slack"
        class="bg-surface-elevated border border-border p-4 block hover:border-ring transition-colors"
      >
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-sm font-medium text-fg-strong">
            Slack
          </h2>
          <span
            :class="slackActiveCount > 0 ? 'text-green-700 dark:text-green-400' : 'text-fg-muted'"
            class="text-xs font-mono"
          >{{ slackActiveCount > 0 ? `${slackActiveCount} active` : 'not configured' }}</span>
        </div>
        <span class="text-xs text-fg-muted">
          Manage bindings →
        </span>
      </NuxtLink>

      <NuxtLink
        to="/channels/whatsapp"
        class="bg-surface-elevated border border-border p-4 block hover:border-ring transition-colors"
      >
        <div class="flex items-center justify-between mb-3">
          <h2 class="text-sm font-medium text-fg-strong">
            WhatsApp
          </h2>
          <span
            :class="whatsappActiveCount > 0 ? 'text-green-700 dark:text-green-400' : 'text-fg-muted'"
            class="text-xs font-mono"
          >{{ whatsappActiveCount > 0 ? `${whatsappActiveCount} active` : 'not configured' }}</span>
        </div>
        <span class="text-xs text-fg-muted">
          Manage bindings →
        </span>
      </NuxtLink>
    </div>

    <div
      class="bg-surface-elevated border border-border p-4 mb-6"
      data-tour="tailscale-funnel"
    >
      <div class="flex items-center justify-between mb-2">
        <h2 class="text-sm font-medium text-fg-strong">
          Public access — Tailscale Funnel
        </h2>
        <span
          :class="tailscale?.enabled && tailscale?.available ? 'text-green-700 dark:text-green-400' : 'text-fg-muted'"
          class="text-xs font-mono"
        >{{ tailscaleStatus === 'pending' ? 'checking…' : (tailscale?.enabled ? (tailscale?.available ? 'active' : 'enabled (unavailable)') : 'off') }}</span>
      </div>
      <ApiErrorAlert
        :error="funnelError"
        class="mb-3"
      />
      <p class="text-xs text-fg-muted mb-3">
        Exposes this JClaw instance to the public internet over HTTPS via Tailscale
        Funnel, so webhook channels (e.g. the Slack Events API) get a reachable
        Request URL with no manual tunnel. One switch covers every channel — Funnel
        publishes the whole port.
      </p>
      <div
        v-if="tailscale?.publicUrl"
        class="flex items-start gap-2 text-xs text-fg-muted mb-3"
      >
        <span class="shrink-0">Public URL:</span>
        <a
          :href="tailscale.publicUrl"
          target="_blank"
          rel="noopener noreferrer"
          class="font-mono break-all text-fg-strong underline hover:text-emerald-700 dark:hover:text-emerald-400"
        >{{ tailscale.publicUrl }}</a>
        <button
          type="button"
          class="shrink-0 text-fg-muted transition-colors hover:text-emerald-700 dark:hover:text-emerald-400"
          :aria-label="publicUrlCopied ? 'Copied' : 'Copy public URL'"
          @click="copyPublicUrl"
        >
          <CheckIcon
            v-if="publicUrlCopied"
            class="h-4 w-4 text-emerald-700 dark:text-emerald-400"
          />
          <ClipboardDocumentIcon
            v-else
            class="h-4 w-4"
          />
        </button>
      </div>
      <p
        v-if="tailscale?.error"
        class="text-xs text-amber-700 dark:text-amber-400 mb-3"
      >
        {{ tailscale?.error }}
        <span
          v-if="tailscale?.enabled"
          class="block text-fg-muted mt-1"
        >The funnel will resume automatically when Tailscale reconnects.</span>
      </p>
      <button
        :disabled="tailscaleToggling || funnelEnableBlocked"
        class="px-4 py-1.5 text-sm font-medium border disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
        :class="tailscale?.enabled
          ? 'border-input text-fg-strong hover:border-ring hover:bg-muted'
          : 'border-emerald-600 bg-emerald-700 text-white hover:bg-emerald-800'"
        @click="toggleTailscale"
      >
        {{ tailscaleToggling ? 'Working...' : (tailscale?.enabled ? 'Disable Funnel' : 'Enable Funnel') }}
      </button>
    </div>
  </div>
</template>
