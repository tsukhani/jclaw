<script setup lang="ts">
import {
  PencilIcon,
  TrashIcon,
} from '@heroicons/vue/24/outline'
import type { Agent, TelegramBindingSummary } from '~/types/api'

// JCLAW-378: per-binding setting overrides ride on the binding summary the
// backend returns; declared locally (rather than on the shared
// TelegramBindingSummary) since this page is their only consumer. Each is null
// when the binding falls back to the global config default.
interface BindingSettingOverrides {
  replyToMode: string | null
  errorReplyPolicy: string | null
  notifierCooldownMs: number | null
}
type BindingWithOverrides = TelegramBindingSummary & Partial<BindingSettingOverrides>

// JCLAW-362: structured result of the per-binding health probe
// (POST /api/channels/telegram/bindings/{id}/test).
interface ProbeResult {
  ok: boolean
  transport: string
  botUsername: string | null
  botId: number | null
  webhookUrl: string | null
  webhookPendingUpdates: number | null
  webhookLastError: string | null
  error: string | null
}

const [{ data: bindings, refresh }, { data: agents }] = await Promise.all([
  useFetch<TelegramBindingSummary[]>('/api/channels/telegram/bindings'),
  useFetch<Agent[]>('/api/agents'),
])
// Funnel status loads lazily (shells out to `tailscale status`, ~400ms) and only
// pre-fills the webhook URL — shared across the channel pages via the composable.
const { data: tailscale } = useTailscaleStatus()

const enabledAgents = computed(() => (agents.value ?? []).filter(a => a.enabled))

/**
 * Agents that are still selectable for a new or edited binding. Agent memory is
 * scoped per agent, so any agent already bound to another binding is hidden to
 * prevent accidentally sharing memories across two Telegram users. When editing
 * an existing binding, that binding's current agent stays selectable.
 */
const availableAgents = computed(() => {
  const takenAgentIds = new Set<number>()
  for (const b of bindings.value ?? []) {
    if (b.agentId == null) continue
    if (editing.value && b.id === editing.value.id) continue
    takenAgentIds.add(b.agentId)
  }
  return enabledAgents.value.filter(a => !takenAgentIds.has(a.id))
})

const { mutate, loading: saving } = useApiMutation()
const { confirm } = useConfirm()

const creating = ref(false)
const editing = ref<TelegramBindingSummary | null>(null)

interface BindingForm {
  botToken: string
  agentId: number | null
  agentQuery: string
  telegramUserId: string
  transport: 'POLLING' | 'WEBHOOK'
  // Editable public host (scheme + host, no path), e.g. https://host.ts.net.
  webhookBaseUrl: string
  // Auto-generated client-side for a new/secretless webhook binding; '' means
  // "keep the binding's existing secret" (we never receive it).
  webhookSecret: string
  // JCLAW-378: per-binding setting overrides. '' on a select means "use the
  // global default"; '' on the cooldown means "use the global default".
  replyToMode: '' | 'off' | 'first' | 'all'
  errorReplyPolicy: '' | 'reply' | 'silent'
  notifierCooldownMs: string
}

const emptyForm = (): BindingForm => ({
  botToken: '',
  agentId: null,
  agentQuery: '',
  telegramUserId: '',
  transport: 'POLLING',
  webhookBaseUrl: '',
  webhookSecret: '',
  replyToMode: '',
  errorReplyPolicy: '',
  notifierCooldownMs: '',
})

const form = ref<BindingForm>(emptyForm())
const agentDropdownOpen = ref(false)
const errorMessage = ref('')

const filteredAgents = computed(() => {
  const q = form.value.agentQuery.toLowerCase().trim()
  if (!q) return availableAgents.value
  return availableAgents.value.filter(a => a.name.toLowerCase().includes(q))
})

const canSave = computed(() => {
  if (!form.value.agentId) return false
  if (!/^\d+$/.test(form.value.telegramUserId.trim())) return false
  if (editing.value === null && form.value.botToken.trim().length === 0) return false
  if (form.value.transport === 'WEBHOOK' && form.value.webhookBaseUrl.trim().length === 0) {
    // A webhook binding needs a public host to register; the secret is
    // auto-generated, so that's the only thing the operator might supply.
    return false
  }
  return true
})

// JCLAW-339: the webhook URL is base + a FIXED contract path
// (/api/webhooks/telegram/{id}/{secret}). Only the public base is editable; the
// secret is auto-generated and the path is derived. The base is pre-filled from
// the live Tailscale Funnel, else the page's own origin when that's a public
// HTTPS host, else left blank for the operator to enter.
const funnelBaseUrl = computed(() => {
  const t = tailscale.value
  return t?.enabled && t.available && t.publicUrl ? t.publicUrl : ''
})

const isPublicHttps = computed(() => {
  const o = import.meta.client ? globalThis.location.origin : ''
  if (!/^https:\/\//i.test(o)) return false
  const host = o.replace(/^https:\/\//i, '').replace(/:\d+$/, '').toLowerCase()
  return !(host === 'localhost' || host.endsWith('.local') || host === '[::1]'
    || /^127\./.test(host) || /^10\./.test(host) || /^192\.168\./.test(host)
    || /^172\.(1[6-9]|2\d|3[01])\./.test(host) || host === '0.0.0.0')
})

const defaultPublicBase = computed(() =>
  funnelBaseUrl.value || (isPublicHttps.value ? globalThis.location.origin : ''))

// Telegram secret_token charset is base64url (A-Z a-z 0-9 _ -).
function generateWebhookSecret(): string {
  const bytes = new Uint8Array(32)
  crypto.getRandomValues(bytes)
  let bin = ''
  for (const b of bytes) bin += String.fromCharCode(b)
  return btoa(bin).replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/, '')
}

// The full webhook URL preview: editable base + the fixed path. For an existing
// secret (which we never receive) we reuse the server-built path from
// effectiveWebhookUrl, swapping in the current base; otherwise we build it from
// the freshly generated secret. Empty while creating (id assigned on save).
const fullWebhookUrl = computed(() => {
  if (form.value.transport !== 'WEBHOOK') return ''
  const base = form.value.webhookBaseUrl.trim().replace(/\/$/, '')
  if (!base) return ''
  const existing = editing.value?.effectiveWebhookUrl
  if (existing && !form.value.webhookSecret) {
    return base + existing.replace(/^https?:\/\/[^/]+/i, '')
  }
  const id = editing.value?.id
  if (id && form.value.webhookSecret) {
    return `${base}/api/webhooks/telegram/${id}/${form.value.webhookSecret}`
  }
  return '' // creating: the id (and thus the path) is assigned on save
})

// Pre-fill the base + generate a secret when a binding enters webhook mode
// without an existing one. Idempotent — won't clobber an edited base or
// regenerate over an existing/already-generated secret.
function enterWebhookMode() {
  if (form.value.transport !== 'WEBHOOK') return
  if (!form.value.webhookBaseUrl.trim()) {
    form.value.webhookBaseUrl = defaultPublicBase.value
  }
  if (!editing.value?.hasWebhookSecret && !form.value.webhookSecret) {
    form.value.webhookSecret = generateWebhookSecret()
  }
}
watch(() => form.value.transport, () => enterWebhookMode())

function openCreate() {
  form.value = emptyForm()
  errorMessage.value = ''
  creating.value = true
  editing.value = null
}

function openEdit(binding: TelegramBindingSummary) {
  form.value = {
    botToken: '',
    agentId: binding.agentId,
    agentQuery: binding.agentName ?? '',
    telegramUserId: binding.telegramUserId,
    transport: binding.transport === 'WEBHOOK' ? 'WEBHOOK' : 'POLLING',
    webhookBaseUrl: binding.webhookBaseUrl ?? '',
    webhookSecret: '',
    replyToMode: ((binding as BindingWithOverrides).replyToMode as BindingForm['replyToMode']) ?? '',
    errorReplyPolicy: ((binding as BindingWithOverrides).errorReplyPolicy as BindingForm['errorReplyPolicy']) ?? '',
    notifierCooldownMs: (binding as BindingWithOverrides).notifierCooldownMs != null
      ? String((binding as BindingWithOverrides).notifierCooldownMs)
      : '',
  }
  errorMessage.value = ''
  editing.value = binding
  creating.value = false
  enterWebhookMode()
}

function closeModal() {
  creating.value = false
  editing.value = null
  errorMessage.value = ''
}

function selectAgent(a: Agent) {
  form.value.agentId = a.id
  form.value.agentQuery = a.name
  agentDropdownOpen.value = false
}

function onAgentQueryInput() {
  agentDropdownOpen.value = true
  // Invalidate the selected id when the user edits past the match so a stale
  // id doesn't survive a typo. User must pick again from the dropdown.
  form.value.agentId = null
}

async function save() {
  if (!canSave.value) return
  errorMessage.value = ''
  const url = editing.value
    ? `/api/channels/telegram/bindings/${editing.value.id}`
    : '/api/channels/telegram/bindings'
  const method = editing.value ? 'PUT' : 'POST'
  const body: Record<string, unknown> = {
    agentId: form.value.agentId,
    telegramUserId: form.value.telegramUserId.trim(),
    transport: form.value.transport,
    // JCLAW-378: per-binding overrides. '' / blank means "use the global
    // default" → send null so the backend clears any stored override.
    replyToMode: form.value.replyToMode || null,
    errorReplyPolicy: form.value.errorReplyPolicy || null,
    // Coerce defensively: the numeric input can yield a number, string, or null.
    notifierCooldownMs: String(form.value.notifierCooldownMs ?? '').trim()
      ? Number(String(form.value.notifierCooldownMs).trim())
      : null,
  }
  // New bindings default to enabled=true; existing enabled state is preserved
  // on edit (the card toggle is the only control for it). Only send it on
  // create so a subsequent edit doesn't accidentally re-enable a binding the
  // operator just disabled via the card toggle.
  if (!editing.value) body.enabled = true
  if (form.value.botToken.trim()) {
    body.botToken = form.value.botToken.trim()
  }
  if (form.value.transport === 'WEBHOOK') {
    body.webhookBaseUrl = form.value.webhookBaseUrl.trim()
    // Only send a secret when we generated one (new/secretless binding); blank
    // leaves the stored secret untouched, like the botToken "leave blank to keep".
    if (form.value.webhookSecret) {
      body.webhookSecret = form.value.webhookSecret
    }
  }
  else {
    // Switching to POLLING clears webhook config so the stored binding doesn't
    // leak a stale base/secret if the admin flips back later.
    body.webhookBaseUrl = null
    body.webhookSecret = null
  }
  const result = await mutate(url, { method, body })
  if (result === null) {
    errorMessage.value = 'Save failed — check server logs for details.'
    return
  }
  closeModal()
  refresh()
}

async function toggleEnabled(binding: TelegramBindingSummary) {
  const next = !binding.enabled
  const result = await mutate(`/api/channels/telegram/bindings/${binding.id}`, {
    method: 'PUT',
    body: { enabled: next },
  })
  if (result !== null) refresh()
}

async function remove(binding: TelegramBindingSummary) {
  const ok = await confirm({
    title: 'Delete binding?',
    message: `Delete the Telegram binding for ${binding.agentName ?? '?'} / ${binding.telegramUserId}? Its polling session stops immediately.`,
    confirmText: 'Delete',
  })
  if (!ok) return
  const result = await mutate(`/api/channels/telegram/bindings/${binding.id}`, { method: 'DELETE' })
  if (result !== null) refresh()
}

// JCLAW-362: per-binding health probe. Calls getMe (and getWebhookInfo for
// webhook bindings) so a bad token or stale webhook surfaces here rather than
// only at the next send. Keyed by binding id so each card shows its own state.
const probing = ref<Set<number>>(new Set())
const probeResults = ref<Record<number, ProbeResult>>({})

async function testBinding(binding: TelegramBindingSummary) {
  probing.value = new Set(probing.value).add(binding.id)
  try {
    const result = await mutate<ProbeResult>(
      `/api/channels/telegram/bindings/${binding.id}/test`,
      { method: 'POST' },
    )
    probeResults.value = {
      ...probeResults.value,
      [binding.id]: result ?? {
        ok: false,
        transport: binding.transport,
        botUsername: null,
        botId: null,
        webhookUrl: null,
        webhookPendingUpdates: null,
        webhookLastError: null,
        error: 'Probe request failed — check server logs.',
      },
    }
  }
  finally {
    const next = new Set(probing.value)
    next.delete(binding.id)
    probing.value = next
  }
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-4">
      <div>
        <NuxtLink
          to="/channels"
          class="text-xs text-fg-muted hover:text-fg-strong transition-colors"
        >
          ← Channels
        </NuxtLink>
        <h1 class="text-lg font-semibold text-fg-strong mt-1">
          Telegram
        </h1>
      </div>
      <button
        class="px-4 py-1.5 bg-emerald-600 text-white text-sm font-medium
               hover:bg-emerald-500 transition-colors"
        @click="openCreate"
      >
        + New binding
      </button>
    </div>

    <p class="text-xs text-fg-muted mb-6 max-w-2xl">
      Each binding pairs one Telegram bot with one agent and one Telegram user.
      A bot responds only to messages from its bound user, and each agent can
      only back one binding — agent memory is per-agent, so sharing an agent
      across users would leak memories between them.
    </p>

    <div
      v-if="!bindings?.length"
      class="bg-surface-elevated border border-border p-6 text-sm text-fg-muted"
    >
      No bindings yet. Click
      <span class="text-fg-strong">+ New binding</span>
      to create one.
    </div>

    <div
      v-else
      class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-4"
    >
      <div
        v-for="b in bindings"
        :key="b.id"
        class="bg-surface-elevated border border-border p-4"
      >
        <div class="flex items-center justify-between mb-3 gap-2">
          <h3 class="text-sm font-medium text-fg-strong truncate">
            {{ b.agentName ?? '(no agent)' }}
          </h3>
          <div class="flex items-center gap-2 shrink-0">
            <!-- The aria-checked attribute is dynamically bound via the Vue colon shorthand; Sonar's static analyser does not resolve those bindings, but the required ARIA property is in fact present. -->
            <button
              type="button"
              role="switch"
              :aria-checked="b.enabled"
              :aria-label="b.enabled ? 'Disable binding' : 'Enable binding'"
              :title="b.enabled ? 'Enabled — click to disable' : 'Disabled — click to enable'"
              class="relative inline-flex h-5 w-9 shrink-0 items-center rounded-full
                     transition-colors focus:outline-hidden focus:ring-1 focus:ring-ring
                     disabled:cursor-not-allowed disabled:opacity-60"
              :class="b.enabled ? 'bg-emerald-500' : 'bg-muted border border-border'"
              @click="toggleEnabled(b)"
            >
              <span
                class="inline-block h-4 w-4 transform rounded-full bg-white shadow transition-transform"
                :class="b.enabled ? 'translate-x-4' : 'translate-x-0.5'"
              />
            </button>
          </div>
        </div>
        <dl class="text-xs text-fg-muted space-y-1 mb-4">
          <div class="flex justify-between gap-4">
            <dt>Telegram user</dt>
            <dd class="font-mono text-fg-strong">
              {{ b.telegramUserId }}
            </dd>
          </div>
          <div class="flex justify-between gap-4">
            <dt>Transport</dt>
            <dd class="font-mono text-fg-strong">
              {{ b.transport.toLowerCase() }}
            </dd>
          </div>
        </dl>
        <div class="flex justify-end items-center gap-1">
          <button
            type="button"
            :disabled="probing.has(b.id)"
            title="Test binding — calls Telegram getMe (and getWebhookInfo for webhooks)"
            class="px-2 py-1 mr-auto text-xs text-fg-muted hover:text-fg-strong
                   border border-border transition-colors disabled:opacity-40
                   disabled:cursor-not-allowed"
            @click="testBinding(b)"
          >
            {{ probing.has(b.id) ? 'Testing…' : 'Test' }}
          </button>
          <button
            type="button"
            title="Edit binding"
            aria-label="Edit binding"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            @click="openEdit(b)"
          >
            <PencilIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            title="Delete binding"
            aria-label="Delete binding"
            class="p-1 text-fg-muted hover:text-red-400 transition-colors"
            @click="remove(b)"
          >
            <TrashIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </div>
        <!-- JCLAW-362: health-probe result. ok → green summary with the bot
             username; not-ok → the error reason. Webhook bindings also surface
             pending-update count and the last delivery error when present. -->
        <div
          v-if="probeResults[b.id]"
          :data-testid="`probe-result-${b.id}`"
          class="mt-3 pt-3 border-t border-border text-xs"
        >
          <p
            v-if="probeResults[b.id]?.ok"
            class="text-emerald-400"
          >
            OK — connected as
            <span class="font-mono text-fg-strong">@{{ probeResults[b.id]?.botUsername ?? '?' }}</span>
          </p>
          <p
            v-else
            class="text-red-400"
          >
            {{ probeResults[b.id]?.error ?? 'Probe failed.' }}
          </p>
          <p
            v-if="probeResults[b.id]?.ok && probeResults[b.id]?.transport === 'WEBHOOK'"
            class="text-fg-muted mt-1"
          >
            Webhook pending updates:
            <span class="font-mono text-fg-strong">{{ probeResults[b.id]?.webhookPendingUpdates ?? 0 }}</span>
          </p>
          <p
            v-if="probeResults[b.id]?.webhookLastError"
            class="text-amber-400 mt-1"
          >
            Last webhook error: {{ probeResults[b.id]?.webhookLastError }}
          </p>
        </div>
      </div>
    </div>

    <div
      v-if="creating || editing"
      class="bg-surface-elevated border border-border p-6 mt-6"
    >
      <h2 class="text-sm font-medium text-fg-strong mb-4">
        {{ editing ? 'Edit binding' : 'New binding' }}
      </h2>

      <div
        v-if="!editing"
        class="mb-4 p-3 border border-border text-xs text-fg-muted"
      >
        <p class="text-fg-strong font-medium mb-2">
          How to get a bot token and your Telegram user id
        </p>
        <ol class="list-decimal pl-5 space-y-1">
          <li>
            Open Telegram and start a chat with
            <a
              href="https://t.me/BotFather"
              target="_blank"
              rel="noopener noreferrer"
              class="text-fg-strong underline hover:text-emerald-400"
            >@BotFather</a>.
          </li>
          <li>
            Send
            <code class="font-mono px-1 bg-muted text-fg-strong">/newbot</code>
            and follow the prompts: pick a display name, then a username ending in
            <code class="font-mono px-1 bg-muted text-fg-strong">bot</code>.
          </li>
          <li>
            BotFather replies with a token like
            <code class="font-mono px-1 bg-muted text-fg-strong">123456:ABC-DEF…</code>.
            Copy it into the
            <code class="font-mono px-1 bg-muted text-fg-strong">botToken</code>
            field below.
          </li>
          <li>
            DM
            <a
              href="https://t.me/userinfobot"
              target="_blank"
              rel="noopener noreferrer"
              class="text-fg-strong underline hover:text-emerald-400"
            >@userinfobot</a>;
            it replies with your numeric Telegram user id. Paste that into
            <code class="font-mono px-1 bg-muted text-fg-strong">telegramUserId</code>.
          </li>
          <li>
            Pick an agent from the autocomplete and save. New bindings are
            enabled by default — use the toggle on the card if you want to
            disable it later.
          </li>
        </ol>
      </div>

      <div class="space-y-3">
        <label
          for="binding-bot-token"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">botToken</span>
          <input
            id="binding-bot-token"
            v-model="form.botToken"
            type="password"
            :placeholder="editing ? 'leave blank to keep existing token' : '123456:ABC-DEF…'"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
          >
        </label>

        <label
          for="binding-agent"
          class="block relative"
        >
          <span class="block text-xs text-fg-muted mb-1">agent</span>
          <input
            id="binding-agent"
            v-model="form.agentQuery"
            type="text"
            placeholder="type to search enabled agents"
            autocomplete="off"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
            @focus="agentDropdownOpen = true"
            @blur="agentDropdownOpen = false"
            @input="onAgentQueryInput"
          >
          <ul
            v-if="agentDropdownOpen && filteredAgents.length"
            class="absolute z-10 left-0 right-0 mt-1 max-h-48 overflow-auto bg-surface-elevated
                   border border-border shadow-sm"
          >
            <li
              v-for="a in filteredAgents"
              :key="a.id"
            >
              <button
                type="button"
                class="w-full text-left px-3 py-1.5 text-sm text-fg-strong hover:bg-muted
                       cursor-pointer"
                @mousedown.prevent="selectAgent(a)"
              >
                {{ a.name }}
                <span class="text-xs text-fg-muted font-mono ml-2">{{ a.modelId }}</span>
              </button>
            </li>
          </ul>
          <p
            v-if="form.agentQuery && !form.agentId && !agentDropdownOpen"
            class="mt-1 text-xs text-amber-400"
          >
            Select an agent from the dropdown before saving.
          </p>
        </label>

        <label
          for="binding-user-id"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">telegramUserId</span>
          <input
            id="binding-user-id"
            v-model="form.telegramUserId"
            type="text"
            inputmode="numeric"
            pattern="[0-9]+"
            placeholder="e.g. 123456789"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
          >
        </label>

        <label
          for="binding-transport"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">transport</span>
          <select
            id="binding-transport"
            v-model="form.transport"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
          >
            <option value="POLLING">
              Polling (no public URL needed)
            </option>
            <option value="WEBHOOK">
              Webhook (requires public HTTPS URL)
            </option>
          </select>
        </label>

        <!-- JCLAW-378: per-binding setting overrides. Each blank/Default option
             leaves the binding on the global config default. -->
        <label
          for="binding-reply-mode"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">reply mode</span>
          <select
            id="binding-reply-mode"
            v-model="form.replyToMode"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
          >
            <option value="">
              Default (global config)
            </option>
            <option value="off">
              Off — never reply-to the message
            </option>
            <option value="first">
              First — reply on the first chunk only
            </option>
            <option value="all">
              All — reply on every chunk
            </option>
          </select>
        </label>

        <label
          for="binding-error-policy"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">error reply policy</span>
          <select
            id="binding-error-policy"
            v-model="form.errorReplyPolicy"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
          >
            <option value="">
              Default (global config)
            </option>
            <option value="reply">
              Reply — tell the user delivery failed
            </option>
            <option value="silent">
              Silent — log only, no chat message
            </option>
          </select>
        </label>

        <label
          for="binding-notifier-cooldown"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">notifier cooldown (ms)</span>
          <input
            id="binding-notifier-cooldown"
            v-model="form.notifierCooldownMs"
            type="number"
            min="1"
            inputmode="numeric"
            placeholder="blank = global default"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
          >
        </label>

        <template v-if="form.transport === 'WEBHOOK'">
          <label
            for="binding-webhook-base"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">Public URL</span>
            <input
              id="binding-webhook-base"
              v-model="form.webhookBaseUrl"
              type="url"
              placeholder="https://your-host.example.com"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
          </label>

          <div
            v-if="fullWebhookUrl"
            class="text-xs text-fg-muted space-y-1"
          >
            <span class="block">Webhook URL — JClaw registers this with Telegram automatically on save:</span>
            <code class="block font-mono break-all text-emerald-400">{{ fullWebhookUrl }}</code>
            <span class="block">The path and secret are generated for you; only the public host above is editable. Keep it private.</span>
          </div>
          <p
            v-else-if="!form.webhookBaseUrl.trim()"
            class="text-xs text-amber-400"
          >
            Enter your public HTTPS URL — or enable the Tailscale Funnel on the
            Channels page — so JClaw can register the webhook with Telegram.
          </p>
          <p
            v-else
            class="text-xs text-fg-muted"
          >
            The full webhook URL is generated when you save (it includes the new binding's id).
          </p>
        </template>
      </div>

      <p
        v-if="errorMessage"
        class="mt-3 text-xs text-red-400"
      >
        {{ errorMessage }}
      </p>

      <div class="flex gap-2 mt-4">
        <button
          :disabled="saving || !canSave"
          class="px-4 py-1.5 bg-emerald-600 text-white text-sm font-medium
                 hover:bg-emerald-500 disabled:opacity-40 transition-colors"
          @click="save"
        >
          {{ saving ? 'Saving…' : 'Save' }}
        </button>
        <button
          class="px-4 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors"
          @click="closeModal"
        >
          Cancel
        </button>
      </div>
    </div>
  </div>
</template>
