<script setup lang="ts">
import {
  CheckIcon,
  ClipboardDocumentIcon,
  PencilIcon,
  TrashIcon,
} from '@heroicons/vue/24/outline'
import type { Agent, SlackBindingSummary } from '~/types/api'

// JCLAW-441: structured result of the per-binding health probe
// (POST /api/channels/slack/bindings/{id}/test) — Slack's auth.test.
interface ProbeResult {
  ok: boolean
  botUserId: string | null
  teamId: string | null
  teamName: string | null
  error: string | null
}

interface TailscaleStatus {
  enabled: boolean
  available: boolean
  publicUrl: string | null
  error: string | null
}

const [{ data: bindings, refresh }, { data: agents }, { data: tailscale }] = await Promise.all([
  useFetch<SlackBindingSummary[]>('/api/channels/slack/bindings'),
  useFetch<Agent[]>('/api/agents'),
  useFetch<TailscaleStatus>('/api/tailscale'),
])

const enabledAgents = computed(() => (agents.value ?? []).filter(a => a.enabled))

/**
 * Agents still selectable for a new or edited binding. Agent memory is scoped
 * per agent, so any agent already bound to another Slack binding is hidden to
 * prevent accidentally sharing memories across two Slack workspaces. When editing
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
const editing = ref<SlackBindingSummary | null>(null)

interface BindingForm {
  // Secrets: required on create, blank-to-keep on edit (we never receive them).
  botToken: string
  signingSecret: string
  agentId: number | null
  agentQuery: string
  // JCLAW-350: Slack user id (e.g. U012ABC) allowed to approve exec / dangerous-tool
  // requests via the interactivity endpoint. Optional — blank means no approval
  // surface (dangerous tools fall to the off-channel policy).
  ownerUserId: string
  // Editable public host (scheme + host, no path), e.g. https://host.ts.net. The
  // fixed path + binding id are appended to form the Events API Request URL.
  webhookBaseUrl: string
}

const emptyForm = (): BindingForm => ({
  botToken: '',
  signingSecret: '',
  agentId: null,
  agentQuery: '',
  ownerUserId: '',
  webhookBaseUrl: '',
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
  // On create both secrets are required; on edit blank means "keep existing".
  if (editing.value === null) {
    if (form.value.botToken.trim().length === 0) return false
    if (form.value.signingSecret.trim().length === 0) return false
  }
  // A binding needs a public host so its Events API Request URL is reachable.
  if (form.value.webhookBaseUrl.trim().length === 0) return false
  return true
})

// The public base is pre-filled from the live Tailscale Funnel, else the page's
// own origin when that's a public HTTPS host, else left blank for the operator.
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

// The full Events API Request URL preview: editable base + the FIXED contract
// path (/api/webhooks/slack/{id}). Unlike Telegram, JClaw does NOT register this
// with Slack — the operator pastes it into the app's Event Subscriptions. The id
// is assigned on save, so this is empty while creating.
const fullRequestUrl = computed(() => {
  const base = form.value.webhookBaseUrl.trim().replace(/\/$/, '')
  if (!base) return ''
  const id = editing.value?.id
  return id ? `${base}/api/webhooks/slack/${id}` : ''
})

// JCLAW-350: the Interactivity Request URL is the Events URL + /interactive. The
// operator pastes it into the Slack app's Interactivity & Shortcuts settings so
// exec-approval button taps reach the binding's interactivity endpoint.
const fullInteractiveUrl = computed(() =>
  fullRequestUrl.value ? `${fullRequestUrl.value}/interactive` : '')

// Pre-fill the base when the form opens without one. Idempotent — won't clobber
// an edited base.
function prefillBase() {
  if (!form.value.webhookBaseUrl.trim()) {
    form.value.webhookBaseUrl = defaultPublicBase.value
  }
}

function openCreate() {
  form.value = emptyForm()
  errorMessage.value = ''
  creating.value = true
  editing.value = null
  prefillBase()
}

function openEdit(binding: SlackBindingSummary) {
  form.value = {
    botToken: '',
    signingSecret: '',
    agentId: binding.agentId,
    agentQuery: binding.agentName ?? '',
    ownerUserId: binding.ownerUserId ?? '',
    webhookBaseUrl: binding.webhookBaseUrl ?? '',
  }
  errorMessage.value = ''
  editing.value = binding
  creating.value = false
  prefillBase()
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
  // Invalidate the selected id when the user edits past the match so a stale id
  // doesn't survive a typo. User must pick again from the dropdown.
  form.value.agentId = null
}

async function save() {
  if (!canSave.value) return
  errorMessage.value = ''
  const url = editing.value
    ? `/api/channels/slack/bindings/${editing.value.id}`
    : '/api/channels/slack/bindings'
  const method = editing.value ? 'PUT' : 'POST'
  const body: Record<string, unknown> = {
    agentId: form.value.agentId,
    webhookBaseUrl: form.value.webhookBaseUrl.trim(),
    // Always send: a blank clears the stored owner (null) so approvals can be turned off.
    ownerUserId: form.value.ownerUserId.trim() || null,
  }
  // New bindings default to enabled=true; existing enabled state is preserved on
  // edit (the card toggle is the only control for it). Only send it on create so
  // a subsequent edit doesn't re-enable a binding the operator just disabled.
  if (!editing.value) body.enabled = true
  // Secrets: send only when provided. Blank leaves the stored value untouched.
  if (form.value.botToken.trim()) body.botToken = form.value.botToken.trim()
  if (form.value.signingSecret.trim()) body.signingSecret = form.value.signingSecret.trim()
  const result = await mutate(url, { method, body })
  if (result === null) {
    errorMessage.value = 'Save failed — check server logs for details.'
    return
  }
  closeModal()
  refresh()
}

async function toggleEnabled(binding: SlackBindingSummary) {
  const next = !binding.enabled
  const result = await mutate(`/api/channels/slack/bindings/${binding.id}`, {
    method: 'PUT',
    body: { enabled: next },
  })
  if (result !== null) refresh()
}

async function remove(binding: SlackBindingSummary) {
  const ok = await confirm({
    title: 'Delete binding?',
    message: `Delete the Slack binding for ${binding.agentName ?? '?'}? Its webhook stops accepting events immediately.`,
    confirmText: 'Delete',
  })
  if (!ok) return
  const result = await mutate(`/api/channels/slack/bindings/${binding.id}`, { method: 'DELETE' })
  if (result !== null) refresh()
}

// JCLAW-441: per-binding health probe. Calls auth.test so a bad/revoked token
// surfaces here rather than at the next send, and refreshes the cached bot id.
// Keyed by binding id so each card shows its own state.
const probing = ref<Set<number>>(new Set())
const probeResults = ref<Record<number, ProbeResult>>({})

async function testBinding(binding: SlackBindingSummary) {
  probing.value = new Set(probing.value).add(binding.id)
  try {
    const result = await mutate<ProbeResult>(
      `/api/channels/slack/bindings/${binding.id}/test`,
      { method: 'POST' },
    )
    probeResults.value = {
      ...probeResults.value,
      [binding.id]: result ?? {
        ok: false,
        botUserId: null,
        teamId: null,
        teamName: null,
        error: 'Probe request failed — check server logs.',
      },
    }
    if (result?.ok) refresh() // pick up the freshly cached botUserId/teamId
  }
  finally {
    const next = new Set(probing.value)
    next.delete(binding.id)
    probing.value = next
  }
}

// JCLAW-441: copy a setup value (Request URL, scopes, events) to the clipboard.
// `copied` holds the most-recently-copied value for a brief check mark.
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

// Static Slack-app setup values the operator transcribes into the dashboard —
// exactly the scopes/events the code uses (verified against app/): chat:write
// (post/edit replies + the off-thread draft loop), assistant:write (native
// chat.startStream + the "is typing…" status), files:read (download inbound
// shared files, JCLAW-344), files:write + im:write (upload generated files,
// JCLAW-345; im:write opens a DM channel for the upload), and each *:history so
// the matching message.* event is delivered. No app_mention: parseEvent handles
// only `message` events, so the bot reacts to messages in the channels it's
// invited to (via message.channels), not to app_mention events.
const SETUP_SCOPES = ['chat:write', 'assistant:write', 'files:read', 'files:write', 'im:write', 'channels:history', 'groups:history', 'im:history', 'mpim:history']
const SETUP_EVENTS = ['message.channels', 'message.groups', 'message.im', 'message.mpim']
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
          Slack
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
      Each binding pairs one Slack app (bot token + signing secret) with one agent,
      so multiple Slack bots can coexist — one per agent. An agent can back only one
      binding: agent memory is per-agent, so sharing an agent across workspaces would
      leak memories between them. Each binding gets its own Events API Request URL
      (it carries the binding id), which you paste into that app's Event Subscriptions.
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
            <dt>Workspace</dt>
            <dd class="font-mono text-fg-strong truncate">
              {{ b.teamId ?? '—' }}
            </dd>
          </div>
          <div class="flex justify-between gap-4">
            <dt>Bot user</dt>
            <dd class="font-mono text-fg-strong truncate">
              {{ b.botUserId ?? 'not probed' }}
            </dd>
          </div>
        </dl>

        <!-- The Events API Request URL to paste into this app's Event
             Subscriptions. Shown only once the binding is saved (it carries the
             binding id) and a public base is set. -->
        <div
          v-if="b.effectiveRequestUrl"
          class="mb-3 text-xs text-fg-muted space-y-2"
        >
          <div>
            <span class="block mb-1">Events API Request URL:</span>
            <div class="flex items-start gap-2">
              <code class="font-mono break-all text-emerald-400">{{ b.effectiveRequestUrl }}</code>
              <button
                type="button"
                class="shrink-0 text-fg-muted transition-colors hover:text-emerald-400"
                :aria-label="copied === b.effectiveRequestUrl ? 'Copied' : 'Copy request URL'"
                @click="copyText(b.effectiveRequestUrl)"
              >
                <CheckIcon
                  v-if="copied === b.effectiveRequestUrl"
                  class="h-4 w-4 text-emerald-400"
                />
                <ClipboardDocumentIcon
                  v-else
                  class="h-4 w-4"
                />
              </button>
            </div>
          </div>
          <div>
            <span class="block mb-1">Interactivity Request URL:</span>
            <div class="flex items-start gap-2">
              <code class="font-mono break-all text-emerald-400">{{ `${b.effectiveRequestUrl}/interactive` }}</code>
              <button
                type="button"
                class="shrink-0 text-fg-muted transition-colors hover:text-emerald-400"
                :aria-label="copied === `${b.effectiveRequestUrl}/interactive` ? 'Copied' : 'Copy interactivity URL'"
                @click="copyText(`${b.effectiveRequestUrl}/interactive`)"
              >
                <CheckIcon
                  v-if="copied === `${b.effectiveRequestUrl}/interactive`"
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

        <div class="flex justify-end items-center gap-1">
          <button
            type="button"
            :disabled="probing.has(b.id)"
            title="Test binding — calls Slack auth.test"
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
        <!-- JCLAW-441: health-probe result. ok → green summary with the bot user
             + team; not-ok → the Slack error reason. -->
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
            <span class="font-mono text-fg-strong">{{ probeResults[b.id]?.botUserId ?? '?' }}</span>
            <span class="text-fg-muted"> in {{ probeResults[b.id]?.teamName ?? probeResults[b.id]?.teamId ?? '?' }}</span>
          </p>
          <p
            v-else
            class="text-red-400"
          >
            {{ probeResults[b.id]?.error ?? 'Probe failed.' }}
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
        class="mb-4 p-3 border border-border text-xs text-fg-muted space-y-2"
      >
        <p class="text-fg-strong font-medium">
          How to create a Slack app and get its credentials
        </p>
        <ol class="list-decimal pl-5 space-y-1">
          <li>
            Create a Slack app at
            <a
              href="https://api.slack.com/apps"
              target="_blank"
              rel="noopener noreferrer"
              class="text-fg-strong underline hover:text-emerald-400"
            >api.slack.com/apps</a>
            (From scratch) in your workspace.
          </li>
          <li>
            OAuth &amp; Permissions: add every Bot Token Scope below, then Install
            to Workspace. Copy the Bot User OAuth Token
            (<code class="font-mono px-1 bg-muted text-fg-strong">xoxb-…</code>) into
            <code class="font-mono px-1 bg-muted text-fg-strong">botToken</code>.
          </li>
          <li>
            Basic Information → App Credentials: copy the Signing Secret into
            <code class="font-mono px-1 bg-muted text-fg-strong">signingSecret</code>.
          </li>
          <li>
            Pick an agent, set your public URL, and Save. The binding's Request URL
            (it includes the new binding's id) then appears on its card.
          </li>
          <li>
            Event Subscriptions: turn On, paste that Request URL, subscribe to every
            bot event below, and Save Changes. App Home: enable the Messages tab and
            allow message sending. Agents &amp; AI Apps: enable the Assistant feature
            for the native "is typing…" indicator + streaming replies.
          </li>
          <li>
            Interactivity &amp; Shortcuts (optional, for exec approvals): turn On and
            paste the Interactivity Request URL (the Events URL with
            <code class="font-mono px-1 bg-muted text-fg-strong">/interactive</code>
            appended, shown on the binding's card). Then set the approver user id below
            so dangerous-tool requests prompt you with approve/deny buttons.
          </li>
        </ol>
        <p class="text-fg-muted">
          Lifecycle commands need no setup: in any conversation with the bot, type
          <code class="font-mono px-1 bg-muted text-fg-strong">!help</code>,
          <code class="font-mono px-1 bg-muted text-fg-strong">!reset</code>,
          <code class="font-mono px-1 bg-muted text-fg-strong">!new</code>, or
          <code class="font-mono px-1 bg-muted text-fg-strong">!stop</code>. Slack
          blocks <code class="font-mono px-1 bg-muted text-fg-strong">/</code> commands
          inside threads (including the Assistant pane), so JClaw uses a
          <code class="font-mono px-1 bg-muted text-fg-strong">!</code> prefix, which
          works everywhere.
        </p>
        <div class="text-fg-strong">
          OAuth &amp; Permissions → Bot Token Scopes
          <div class="mt-1 space-y-1">
            <div
              v-for="v in SETUP_SCOPES"
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
        <div class="text-fg-strong">
          Event Subscriptions → Subscribe to bot events
          <div class="mt-1 space-y-1">
            <div
              v-for="v in SETUP_EVENTS"
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
        <label
          for="binding-bot-token"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">botToken</span>
          <input
            id="binding-bot-token"
            v-model="form.botToken"
            type="password"
            :placeholder="editing ? 'leave blank to keep existing token' : 'xoxb-…'"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
          >
        </label>

        <label
          for="binding-signing-secret"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">signingSecret</span>
          <input
            id="binding-signing-secret"
            v-model="form.signingSecret"
            type="password"
            :placeholder="editing ? 'leave blank to keep existing secret' : 'App Credentials → Signing Secret'"
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
          for="binding-owner-user-id"
          class="block"
        >
          <span class="block text-xs text-fg-muted mb-1">approver user id (optional)</span>
          <input
            id="binding-owner-user-id"
            v-model="form.ownerUserId"
            type="text"
            placeholder="U012ABCDEF"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
          >
          <span class="mt-1 block text-xs text-fg-muted">
            Your Slack user id, allowed to approve exec / dangerous-tool requests via
            the Interactivity buttons. Leave blank to skip approvals (dangerous tools
            then follow the off-channel policy). Find it in your Slack profile → ⋮ →
            Copy member ID.
          </span>
        </label>

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
          v-if="fullRequestUrl"
          class="text-xs text-fg-muted space-y-2"
        >
          <div class="space-y-1">
            <span class="block">Events API Request URL — paste this into the Slack app's Event Subscriptions:</span>
            <div class="flex items-start gap-2">
              <code class="font-mono break-all text-emerald-400">{{ fullRequestUrl }}</code>
              <button
                type="button"
                class="shrink-0 text-fg-muted transition-colors hover:text-emerald-400"
                :aria-label="copied === fullRequestUrl ? 'Copied' : 'Copy request URL'"
                @click="copyText(fullRequestUrl)"
              >
                <CheckIcon
                  v-if="copied === fullRequestUrl"
                  class="h-4 w-4 text-emerald-400"
                />
                <ClipboardDocumentIcon
                  v-else
                  class="h-4 w-4"
                />
              </button>
            </div>
          </div>
          <div class="space-y-1">
            <span class="block">Interactivity Request URL — paste this into Interactivity &amp; Shortcuts (needed for exec approvals):</span>
            <div class="flex items-start gap-2">
              <code class="font-mono break-all text-emerald-400">{{ fullInteractiveUrl }}</code>
              <button
                type="button"
                class="shrink-0 text-fg-muted transition-colors hover:text-emerald-400"
                :aria-label="copied === fullInteractiveUrl ? 'Copied' : 'Copy interactivity URL'"
                @click="copyText(fullInteractiveUrl)"
              >
                <CheckIcon
                  v-if="copied === fullInteractiveUrl"
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
        <p
          v-else-if="!form.webhookBaseUrl.trim()"
          class="text-xs text-amber-400"
        >
          Enter your public HTTPS URL — or enable the Tailscale Funnel on the
          Channels page — so Slack can reach this binding's Request URL.
        </p>
        <p
          v-else
          class="text-xs text-fg-muted"
        >
          The full Request URL is generated when you save (it includes the new
          binding's id), then shown on the binding's card to copy into Slack.
        </p>
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
