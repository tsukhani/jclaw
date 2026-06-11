<script setup lang="ts">
import type { SlackBindingSummary, TelegramBindingSummary } from '~/types/api'

interface ChannelInfo {
  channelType: string
  enabled: boolean
  config: Record<string, string>
}

interface ChannelField {
  name: string
  label?: string
  hint?: string
  type?: 'text' | 'password' | 'select'
  options?: Array<{ value: string, label: string }>
  default?: string
  showWhen?: (form: Record<string, string>) => boolean
}

interface ChannelTypeDef {
  type: string
  label: string
  fields: ChannelField[]
}

interface TailscaleStatus {
  enabled: boolean
  available: boolean
  publicUrl: string | null
  error: string | null
}

const [
  { data: channels, refresh },
  { data: telegramBindings, refresh: refreshBindings },
  { data: slackBindings, refresh: refreshSlackBindings },
  { data: tailscale, refresh: refreshTailscale },
] = await Promise.all([
  useFetch<ChannelInfo[]>('/api/channels'),
  useFetch<TelegramBindingSummary[]>('/api/channels/telegram/bindings'),
  useFetch<SlackBindingSummary[]>('/api/channels/slack/bindings'),
  useFetch<TailscaleStatus>('/api/tailscale'),
])

// Telegram (JCLAW-89) and Slack (JCLAW-441) manage per-agent bot bindings on
// their own detail pages. Only WhatsApp retains the single-config-per-channel
// model (its Cloud API token is workspace-scoped, not per-agent).
const channelTypes: ChannelTypeDef[] = [
  {
    type: 'whatsapp',
    label: 'WhatsApp',
    fields: [
      { name: 'phoneNumberId', type: 'text' },
      { name: 'accessToken', type: 'password' },
      { name: 'appSecret', type: 'password' },
      { name: 'verifyToken', type: 'password' },
    ],
  },
]

const editing = ref<string | null>(null)
const form = ref<Record<string, string>>({})
const enabled = ref(false)
const { mutate, loading: saving } = useApiMutation()

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

const telegramActiveCount = computed(() =>
  (telegramBindings.value ?? []).filter(b => b.enabled).length)
const slackActiveCount = computed(() =>
  (slackBindings.value ?? []).filter(b => b.enabled).length)

function editChannel(type: string) {
  const def = channelTypes.find(c => c.type === type)
  const existing = channels.value?.find(c => c.channelType === type)
  const base: Record<string, string> = {}
  def?.fields.forEach((f) => {
    if (f.default !== undefined) base[f.name] = f.default
  })
  if (existing) {
    form.value = { ...base, ...existing.config }
    enabled.value = existing.enabled
  }
  else {
    form.value = base
    enabled.value = false
  }
  editing.value = type
}

async function saveChannel() {
  if (!editing.value) return
  const result = await mutate(`/api/channels/${editing.value}`, {
    method: 'PUT',
    body: { config: form.value, enabled: enabled.value },
  })
  if (result !== null) {
    editing.value = null
    refresh()
  }
}

function getChannelStatus(type: string) {
  const ch = channels.value?.find(c => c.channelType === type)
  return ch?.enabled ? 'active' : 'inactive'
}

function visibleFields(def: ChannelTypeDef): ChannelField[] {
  return def.fields.filter(f => !f.showWhen || f.showWhen(form.value))
}

// Re-pull the bindings summaries when the user returns from a detail page so the
// Telegram/Slack badges reflect any add/remove they did there.
onActivated(() => {
  refreshBindings()
  refreshSlackBindings()
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
          <h3 class="text-sm font-medium text-fg-strong">
            Telegram
          </h3>
          <span
            :class="telegramActiveCount > 0 ? 'text-green-400' : 'text-fg-muted'"
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
          <h3 class="text-sm font-medium text-fg-strong">
            Slack
          </h3>
          <span
            :class="slackActiveCount > 0 ? 'text-green-400' : 'text-fg-muted'"
            class="text-xs font-mono"
          >{{ slackActiveCount > 0 ? `${slackActiveCount} active` : 'not configured' }}</span>
        </div>
        <span class="text-xs text-fg-muted">
          Manage bindings →
        </span>
      </NuxtLink>

      <div
        v-for="ch in channelTypes"
        :key="ch.type"
        class="bg-surface-elevated border border-border p-4"
      >
        <div class="flex items-center justify-between mb-3">
          <h3 class="text-sm font-medium text-fg-strong">
            {{ ch.label }}
          </h3>
          <span
            :class="getChannelStatus(ch.type) === 'active' ? 'text-green-400' : 'text-fg-muted'"
            class="text-xs font-mono"
          >{{ getChannelStatus(ch.type) }}</span>
        </div>
        <button
          class="text-xs text-fg-muted hover:text-fg-strong transition-colors"
          @click="editChannel(ch.type)"
        >
          Configure
        </button>
      </div>
    </div>

    <div
      class="bg-surface-elevated border border-border p-4 mb-6"
      data-tour="tailscale-funnel"
    >
      <div class="flex items-center justify-between mb-2">
        <h3 class="text-sm font-medium text-fg-strong">
          Public access — Tailscale Funnel
        </h3>
        <span
          :class="tailscale?.enabled && tailscale?.available ? 'text-green-400' : 'text-fg-muted'"
          class="text-xs font-mono"
        >{{ tailscale?.enabled ? (tailscale?.available ? 'active' : 'enabled (unavailable)') : 'off' }}</span>
      </div>
      <p class="text-xs text-fg-muted mb-3">
        Exposes this JClaw instance to the public internet over HTTPS via Tailscale
        Funnel, so webhook channels (e.g. the Slack Events API) get a reachable
        Request URL with no manual tunnel. One switch covers every channel — Funnel
        publishes the whole port.
      </p>
      <p
        v-if="tailscale?.publicUrl"
        class="text-xs text-fg-muted mb-3"
      >
        Public URL:
        <code class="font-mono break-all text-fg-strong">{{ tailscale?.publicUrl }}</code>
      </p>
      <p
        v-if="tailscale?.error"
        class="text-xs text-amber-400 mb-3"
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
          : 'border-emerald-600 bg-emerald-600 text-white hover:bg-emerald-500'"
        @click="toggleTailscale"
      >
        {{ tailscaleToggling ? 'Working...' : (tailscale?.enabled ? 'Disable Funnel' : 'Enable Funnel') }}
      </button>
    </div>

    <div
      v-if="editing"
      class="bg-surface-elevated border border-border p-6"
    >
      <h2 class="text-sm font-medium text-fg-strong mb-4">
        Configure {{ channelTypes.find(c => c.type === editing)?.label }}
      </h2>
      <div class="space-y-3">
        <template
          v-for="field in visibleFields(channelTypes.find(c => c.type === editing)!)"
          :key="field.name"
        >
          <label
            :for="`channel-field-${field.name}`"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">{{ field.label ?? field.name }}</span>
            <select
              v-if="field.type === 'select'"
              :id="`channel-field-${field.name}`"
              v-model="form[field.name]"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
              <option
                v-for="opt in field.options"
                :key="opt.value"
                :value="opt.value"
              >
                {{ opt.label }}
              </option>
            </select>
            <input
              v-else
              :id="`channel-field-${field.name}`"
              v-model="form[field.name]"
              :type="field.type ?? 'text'"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
            <span
              v-if="field.hint"
              class="block text-xs text-fg-muted mt-1"
            >{{ field.hint }}</span>
          </label>
        </template>
        <label
          for="channel-enabled"
          class="flex items-center gap-2 text-xs text-fg-muted"
        >
          <input
            id="channel-enabled"
            v-model="enabled"
            type="checkbox"
            class="accent-white"
          >
          Enabled
        </label>
      </div>
      <div class="flex gap-2 mt-4">
        <button
          :disabled="saving"
          class="px-4 py-1.5 bg-emerald-600 text-white text-sm font-medium
                 hover:bg-emerald-500 disabled:opacity-40 transition-colors"
          @click="saveChannel"
        >
          {{ saving ? 'Saving...' : 'Save' }}
        </button>
        <button
          class="px-4 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors"
          @click="editing = null"
        >
          Cancel
        </button>
      </div>
    </div>
  </div>
</template>
