<script setup lang="ts">
import { CheckIcon, ClipboardDocumentIcon } from '@heroicons/vue/24/outline'
import type { TelegramBindingSummary } from '~/types/api'

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

interface ChannelSetup {
  // Provider callback the channel must receive on (rendered as an absolute URL
  // against the live origin) — e.g. Slack's Events API Request URL.
  requestUrlPath?: string
  steps: string[]
  // Grouped values the operator transcribes verbatim into the provider's
  // dashboard (scopes, event names, settings), rendered as labeled monospace
  // blocks — one value per line — so they're easy to scan and copy (JCLAW-13).
  groups?: Array<{ label: string, hint?: string, values: string[] }>
}

interface ChannelTypeDef {
  type: string
  label: string
  fields: ChannelField[]
  setup?: ChannelSetup
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
  { data: tailscale, refresh: refreshTailscale },
] = await Promise.all([
  useFetch<ChannelInfo[]>('/api/channels'),
  useFetch<TelegramBindingSummary[]>('/api/channels/telegram/bindings'),
  useFetch<TailscaleStatus>('/api/tailscale'),
])

// Telegram was removed from the inline-configure list in JCLAW-89: it now
// manages a list of per-user bot bindings on its own detail page. Slack and
// WhatsApp retain the single-config-per-channel model.
const channelTypes: ChannelTypeDef[] = [
  {
    type: 'slack',
    label: 'Slack',
    fields: [
      {
        name: 'botToken',
        label: 'Bot token',
        type: 'password',
        hint: 'OAuth and Permissions: Bot User OAuth Token (starts with xoxb-).',
      },
      {
        name: 'signingSecret',
        label: 'Signing secret',
        type: 'password',
        hint: 'Basic Information: App Credentials: Signing Secret.',
      },
    ],
    setup: {
      requestUrlPath: '/api/webhooks/slack',
      steps: [
        'Create a Slack app at api.slack.com/apps (From scratch) in your workspace.',
        'OAuth & Permissions: add every Bot Token Scope listed below, then Install to Workspace.',
        'Copy the Bot User OAuth Token (xoxb-…) into Bot token, and the Signing Secret (Basic Information → App Credentials) into Signing secret, then Save here.',
        'Event Subscriptions: turn it On, set the Request URL above, and subscribe to every bot event listed below; Save Changes.',
        'App Home: enable both settings below so users can DM the bot.',
        'Agents & AI Apps: enable the Assistant feature (below) for the native "is typing…" indicator and streaming replies.',
        'Reinstall to Workspace if Slack prompts (scope/event changes require it). Then DM the bot, or invite it to a channel with /invite @YourBot.',
      ],
      groups: [
        {
          label: 'OAuth & Permissions → Bot Token Scopes',
          hint: 'chat:write sends replies; each *:history scope lets the bot read that surface; app_mentions:read covers @-mentions; assistant:write enables the typing indicator + streaming.',
          values: ['chat:write', 'assistant:write', 'app_mentions:read', 'channels:history', 'groups:history', 'im:history', 'mpim:history'],
        },
        {
          label: 'Event Subscriptions → Subscribe to bot events',
          hint: 'Each event needs its matching scope above (e.g. message.im ↔ im:history). message.im is what delivers a DM to the bot.',
          values: ['app_mention', 'message.channels', 'message.groups', 'message.im', 'message.mpim'],
        },
        {
          label: 'App Home → Show Tabs (toggle both On)',
          hint: 'Without these, Slack greys out the DM box with "Sending messages to this app has been turned off."',
          values: ['Messages Tab', 'Allow users to send Slash commands and messages from the messages tab'],
        },
        {
          label: 'Agents & AI Apps → enable the Assistant',
          hint: 'Optional but recommended: lets the bot stream replies with a native "is typing…" indicator (chat.startStream). Without it, replies post once when ready (still formatted, no "(edited)").',
          values: ['Enable the Assistant / Agent feature'],
        },
      ],
    },
  },
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
async function toggleTailscale() {
  tailscaleToggling.value = true
  const next = !(tailscale.value?.enabled ?? false)
  const result = await mutate('/api/tailscale', { method: 'POST', body: { enabled: next } })
  tailscaleToggling.value = false
  if (result !== null) refreshTailscale()
}

// JCLAW-83: setup guidance for the channel being configured. requestUrl renders
// the provider callback (e.g. Slack's Events API Request URL) against the live
// origin so the operator can copy it straight into their Slack app.
const currentDef = computed(() =>
  editing.value ? channelTypes.find(c => c.type === editing.value) ?? null : null)
const setupSteps = computed<string[]>(() => currentDef.value?.setup?.steps ?? [])
const setupGroups = computed(() => currentDef.value?.setup?.groups ?? [])
const requestPath = computed(() => currentDef.value?.setup?.requestUrlPath ?? '')

// Slack POSTs the webhook from its own servers, so the Request URL must be a
// public HTTPS address — the browser origin only qualifies in a real deployment,
// never localhost / LAN / plain HTTP (even https://localhost is unreachable from
// Slack). requestUrl is shown as a ready value only when this page is already
// loaded over such an address; otherwise we show the path + guidance.
const isPublicHttps = computed(() => {
  const o = import.meta.client ? window.location.origin : ''
  if (!/^https:\/\//i.test(o)) return false
  const host = o.replace(/^https:\/\//i, '').replace(/:\d+$/, '').toLowerCase()
  return !(host === 'localhost' || host.endsWith('.local') || host === '[::1]'
    || /^127\./.test(host) || /^10\./.test(host) || /^192\.168\./.test(host)
    || /^172\.(1[6-9]|2\d|3[01])\./.test(host) || host === '0.0.0.0')
})
// When the Tailscale Funnel (JCLAW-84) is enabled and live, its public URL is the
// real, copy-paste-ready base for webhook Request URLs — preferred over the
// browser origin, which only qualifies in a public deployment.
const funnelBaseUrl = computed(() => {
  const t = tailscale.value
  return t?.enabled && t.available && t.publicUrl ? t.publicUrl : ''
})
const requestUrl = computed(() => {
  if (!requestPath.value) return ''
  if (funnelBaseUrl.value) return `${funnelBaseUrl.value}${requestPath.value}`
  return isPublicHttps.value ? `${window.location.origin}${requestPath.value}` : ''
})
const requestUrlViaFunnel = computed(() => requestPath.value !== '' && funnelBaseUrl.value !== '')

// JCLAW-13: copy the transcribable setup values (Request URL, scopes, events) to
// the clipboard. `copied` holds the most-recently-copied value for a brief check.
const copied = ref('')
let copiedTimer: ReturnType<typeof setTimeout> | null = null
async function copyText(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    copied.value = text
    if (copiedTimer) {
      clearTimeout(copiedTimer)
    }
    copiedTimer = setTimeout(() => {
      copied.value = ''
    }, 1500)
  }
  catch {
    // Clipboard unavailable (insecure context or denied) — no-op.
  }
}

const telegramActiveCount = computed(() =>
  (telegramBindings.value ?? []).filter(b => b.enabled).length)

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

// Re-pull the bindings summary when the user returns from the Telegram detail
// page so the header badge reflects any add/remove they did there.
onActivated(() => refreshBindings())
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
      </p>
      <button
        :disabled="tailscaleToggling"
        class="px-4 py-1.5 text-sm font-medium border disabled:opacity-40 transition-colors"
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
      <div
        v-if="setupSteps.length"
        class="mb-4 border border-border bg-muted p-3 text-xs text-fg-muted space-y-2"
      >
        <div
          v-if="requestPath"
          class="text-fg-strong"
        >
          Events API Request URL
          <template v-if="requestUrlViaFunnel">
            <span class="block mt-1 font-normal text-fg-muted">
              Set this as the Request URL under Event Subscriptions (live via Tailscale Funnel):
            </span>
            <div class="mt-1 flex items-start gap-2">
              <code class="font-mono break-all text-emerald-400">{{ requestUrl }}</code>
              <button
                type="button"
                class="shrink-0 text-fg-muted transition-colors hover:text-emerald-400"
                :aria-label="copied === requestUrl ? 'Copied' : 'Copy request URL'"
                @click="copyText(requestUrl)"
              >
                <CheckIcon
                  v-if="copied === requestUrl"
                  class="h-4 w-4 text-emerald-400"
                />
                <ClipboardDocumentIcon
                  v-else
                  class="h-4 w-4"
                />
              </button>
            </div>
          </template>
          <template v-else>
            <span class="block mt-1 font-normal text-fg-muted">
              Append <code class="font-mono text-fg-strong">{{ requestPath }}</code> to your public HTTPS base URL and set it as the Request URL under Event Subscriptions.
            </span>
            <span
              v-if="requestUrl"
              class="block mt-1 font-normal text-fg-muted"
            >
              From this page that is
              <code class="font-mono break-all text-fg-strong">{{ requestUrl }}</code>
            </span>
            <span
              v-else
              class="block mt-1 font-normal text-fg-muted"
            >
              Slack reaches it from its own servers, so localhost, LAN, and plain HTTP will not work. Enable Tailscale Funnel above, or expose this host with another tunnel.
            </span>
          </template>
        </div>
        <ol class="list-decimal list-inside space-y-1">
          <li
            v-for="(step, i) in setupSteps"
            :key="i"
          >
            {{ step }}
          </li>
        </ol>
        <div
          v-for="g in setupGroups"
          :key="g.label"
          class="text-fg-strong"
        >
          {{ g.label }}
          <span
            v-if="g.hint"
            class="block font-normal text-fg-muted"
          >{{ g.hint }}</span>
          <div class="mt-1 space-y-1">
            <div
              v-for="v in g.values"
              :key="v"
              class="flex items-start gap-2"
            >
              <code class="font-mono break-all text-emerald-400">{{ v }}</code>
              <button
                type="button"
                class="shrink-0 text-fg-muted transition-colors hover:text-emerald-400"
                :aria-label="copied === v ? 'Copied' : `Copy ${v}`"
                @click="copyText(v)"
              >
                <CheckIcon
                  v-if="copied === v"
                  class="h-4 w-4 text-emerald-400"
                />
                <ClipboardDocumentIcon
                  v-else
                  class="h-4 w-4"
                />
              </button>
            </div>
          </div>
        </div>
      </div>
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
