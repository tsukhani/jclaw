<script setup lang="ts">
import {
  ExclamationTriangleIcon,
  PencilIcon,
  QrCodeIcon,
  TrashIcon,
} from '@heroicons/vue/24/outline'
// Privacy: the QR is rendered locally with the qrcode lib. The raw pairing
// string is NEVER sent to any third-party/external QR service or URL.
import QRCode from 'qrcode'
import type { Agent, WhatsAppBindingSummary } from '~/types/api'

const [{ data: bindings, refresh }, { data: agents }] = await Promise.all([
  useFetch<WhatsAppBindingSummary[]>('/api/channels/whatsapp/bindings'),
  useFetch<Agent[]>('/api/agents'),
])

const enabledAgents = computed(() => (agents.value ?? []).filter(a => a.enabled))

/**
 * Agents still selectable for a new or edited binding. Agent memory is scoped
 * per agent, so any agent already bound to another WhatsApp binding is hidden to
 * prevent accidentally sharing memories across two WhatsApp numbers. When editing
 * an existing binding, that binding's current agent stays selectable.
 */
const availableAgents = computed(() => {
  const taken = new Set<number>()
  for (const b of bindings.value ?? []) {
    if (b.agentId == null) continue
    if (editing.value && b.id === editing.value.id) continue
    taken.add(b.agentId)
  }
  return enabledAgents.value.filter(a => !taken.has(a.id))
})

const { mutate, loading: saving, error: mutationError } = useApiMutation()
const { confirm } = useConfirm()

const creating = ref(false)
const editing = ref<WhatsAppBindingSummary | null>(null)

interface BindingForm {
  // CLOUD_API (official Cloud API) or WHATSAPP_WEB (unofficial QR-paired Cobalt).
  transport: string
  // Cloud-API credentials. phoneNumberId is an identifier (always editable);
  // the rest are secrets — required on create, blank-to-keep on edit.
  phoneNumberId: string
  accessToken: string
  appSecret: string
  verifyToken: string
  // JCLAW-445: pre-approved template (name + BCP-47 language) used for replies
  // sent outside WhatsApp's 24-hour window. Cloud-API only, optional.
  templateName: string
  templateLanguage: string
  // JCLAW-425: Cloud-API proactive-send recipient (E.164). The agent's outbound
  // destination when a send has no explicit target / live conversation peer.
  // Cloud-API only, optional (WhatsApp-Web uses its paired owner instead).
  defaultTarget: string
  agentId: number | null
  agentQuery: string
}

const emptyForm = (): BindingForm => ({
  transport: 'CLOUD_API',
  phoneNumberId: '',
  accessToken: '',
  appSecret: '',
  verifyToken: '',
  templateName: '',
  templateLanguage: '',
  defaultTarget: '',
  agentId: null,
  agentQuery: '',
})

const form = ref<BindingForm>(emptyForm())
const agentDropdownOpen = ref(false)
const errorMessage = ref('')

const filteredAgents = computed(() => {
  const q = form.value.agentQuery.toLowerCase().trim()
  if (!q) return availableAgents.value
  return availableAgents.value.filter(a => a.name.toLowerCase().includes(q))
})

const isCloud = computed(() => form.value.transport === 'CLOUD_API')

const canSave = computed(() => {
  if (!form.value.agentId) return false
  // Cloud API needs an outbound sender + auth on create; WhatsApp-Web carries no
  // credentials here (it's QR-paired later). On edit, secrets are blank-to-keep.
  if (editing.value === null && isCloud.value) {
    if (form.value.phoneNumberId.trim().length === 0) return false
    if (form.value.accessToken.trim().length === 0) return false
  }
  return true
})

function openCreate() {
  form.value = emptyForm()
  errorMessage.value = ''
  creating.value = true
  editing.value = null
}

function openEdit(binding: WhatsAppBindingSummary) {
  form.value = {
    transport: binding.transport ?? 'CLOUD_API',
    phoneNumberId: binding.phoneNumberId ?? '',
    accessToken: '',
    appSecret: '',
    verifyToken: '',
    templateName: binding.templateName ?? '',
    templateLanguage: binding.templateLanguage ?? '',
    defaultTarget: binding.defaultTarget ?? '',
    agentId: binding.agentId,
    agentQuery: binding.agentName ?? '',
  }
  errorMessage.value = ''
  editing.value = binding
  creating.value = false
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

/** Copy a form field onto the request body only when it's non-blank (trimmed) —
 *  the "send only when provided" rule shared by every optional Cloud-API field. */
function putTrimmed(body: Record<string, unknown>, key: string, raw: string) {
  const value = raw.trim()
  if (value) body[key] = value
}

async function save() {
  if (!canSave.value) return
  errorMessage.value = ''
  const url = editing.value
    ? `/api/channels/whatsapp/bindings/${editing.value.id}`
    : '/api/channels/whatsapp/bindings'
  const method = editing.value ? 'PUT' : 'POST'
  const body: Record<string, unknown> = {
    agentId: form.value.agentId,
    transport: form.value.transport,
  }
  // New bindings default to enabled=true; existing state is preserved on edit
  // (the card toggle is the only control for it).
  if (!editing.value) body.enabled = true
  // Cloud-API fields: send only when provided. phoneNumberId/defaultTarget are
  // identifiers (sent as-is); secrets blank-to-keep. WhatsApp-Web sends none.
  // (JCLAW-445 template + JCLAW-425 default recipient are optional too.)
  if (isCloud.value) {
    putTrimmed(body, 'phoneNumberId', form.value.phoneNumberId)
    putTrimmed(body, 'accessToken', form.value.accessToken)
    putTrimmed(body, 'appSecret', form.value.appSecret)
    putTrimmed(body, 'verifyToken', form.value.verifyToken)
    putTrimmed(body, 'templateName', form.value.templateName)
    putTrimmed(body, 'templateLanguage', form.value.templateLanguage)
    putTrimmed(body, 'defaultTarget', form.value.defaultTarget)
  }
  const result = await mutate(url, { method, body })
  if (result === null) {
    // JCLAW-445: a Cloud-API save runs a Graph verify probe server-side and
    // rejects with 422 when the number isn't a registered WhatsApp Business
    // number. Surface that specifically; fall back to the generic message
    // for any other failure (and for WhatsApp-Web, which has no verify probe).
    errorMessage.value = isCloud.value && mutationError.value?.includes('422')
      ? 'Couldn\'t verify this Cloud API number — check the phone number id and access token (it must be a registered WhatsApp Business number).'
      : 'Save failed — check server logs for details.'
    return
  }
  closeModal()
  refresh()
}

async function toggleEnabled(binding: WhatsAppBindingSummary) {
  const next = !binding.enabled
  const result = await mutate(`/api/channels/whatsapp/bindings/${binding.id}`, {
    method: 'PUT',
    body: { enabled: next },
  })
  if (result !== null) refresh()
}

async function remove(binding: WhatsAppBindingSummary) {
  const ok = await confirm({
    title: 'Delete binding?',
    message: `Delete the WhatsApp binding for ${binding.agentName ?? '?'}? It stops sending and receiving immediately.`,
    confirmText: 'Delete',
  })
  if (!ok) return
  const result = await mutate(`/api/channels/whatsapp/bindings/${binding.id}`, { method: 'DELETE' })
  if (result !== null) refresh()
}

function transportLabel(t: string | null): string {
  return t === 'WHATSAPP_WEB' ? 'WhatsApp-Web' : 'Cloud API'
}

// ── JCLAW-448: WhatsApp-Web QR pairing ────────────────────────────────────
// The pairing string rotates every ~20s while unpaired; we poll the binding's
// /qr endpoint, re-render the QR image locally whenever the string changes, and
// stop once the session connects (paired=true). The poll interval is cleaned up
// on close and on unmount so it can't leak.

interface QrResponse {
  bindingId: number
  transport: string
  paired: boolean
  qr: string | null
}

const pairing = ref<WhatsAppBindingSummary | null>(null)
const pairImageUrl = ref('')
const pairConnected = ref(false)
const pairError = ref('')
const lastQrString = ref('')
let pollTimer: ReturnType<typeof setInterval> | null = null

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer)
    pollTimer = null
  }
}

async function pollQr() {
  const binding = pairing.value
  if (!binding) return
  const result = await mutate<QrResponse>(`/api/channels/whatsapp/bindings/${binding.id}/qr`, { method: 'GET' })
  // Guard against a late response after the panel was closed/switched.
  if (!pairing.value || pairing.value.id !== binding.id) return
  if (result === null) {
    pairError.value = 'Couldn\'t reach the pairing service — check server logs.'
    return
  }
  pairError.value = ''
  if (result.paired) {
    stopPoll()
    pairConnected.value = true
    pairImageUrl.value = ''
    lastQrString.value = ''
    refresh() // pick up the now-paired state on the card
    return
  }
  // Re-render only when the rotating string actually changed.
  if (result.qr && result.qr !== lastQrString.value) {
    lastQrString.value = result.qr
    try {
      // Local render only — the raw pairing string never leaves the browser.
      pairImageUrl.value = await QRCode.toDataURL(result.qr, { margin: 1, width: 256 })
    }
    catch {
      pairError.value = 'Couldn\'t render the QR code.'
    }
  }
}

function openPairing(binding: WhatsAppBindingSummary) {
  stopPoll()
  pairing.value = binding
  pairImageUrl.value = ''
  pairConnected.value = false
  pairError.value = ''
  lastQrString.value = ''
  pollQr() // immediate first fetch, then on an interval
  pollTimer = setInterval(pollQr, 2000)
}

function closePairing() {
  stopPoll()
  pairing.value = null
  pairImageUrl.value = ''
  pairConnected.value = false
  pairError.value = ''
  lastQrString.value = ''
}

onBeforeUnmount(stopPoll)
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
          WhatsApp
        </h1>
      </div>
      <button
        class="px-4 py-1.5 bg-emerald-700 text-white text-sm font-medium
               hover:bg-emerald-600 transition-colors"
        @click="openCreate"
      >
        + New binding
      </button>
    </div>

    <p class="text-xs text-fg-muted mb-6 max-w-2xl">
      Each binding pairs one WhatsApp number with one agent, so multiple WhatsApp
      bots can coexist — one per agent. An agent can back only one binding: agent
      memory is per-agent, so sharing an agent across numbers would leak memories
      between them. Pick the transport per binding: the official
      <span class="text-fg-strong">Cloud API</span> (compliant, zero ban risk,
      rich 1:1 messaging) or the unofficial
      <span class="text-fg-strong">WhatsApp-Web</span> (your own number and group
      chats, but a real risk of the number being banned).
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
            <dt>Transport</dt>
            <dd class="font-mono text-fg-strong truncate">
              {{ transportLabel(b.transport) }}
            </dd>
          </div>
          <div
            v-if="b.transport !== 'WHATSAPP_WEB'"
            class="flex justify-between gap-4"
          >
            <dt>Number id</dt>
            <dd class="font-mono text-fg-strong truncate">
              {{ b.phoneNumberId ?? '—' }}
            </dd>
          </div>
          <div
            v-else
            class="flex justify-between gap-4"
          >
            <dt>Pairing</dt>
            <dd class="font-mono text-fg-strong truncate">
              not yet paired
            </dd>
          </div>
          <!-- JCLAW-445: Meta's verified business name + display number, shown
               once the Cloud-API save's verify probe has succeeded. -->
          <div
            v-if="b.transport !== 'WHATSAPP_WEB' && b.verifiedName"
            class="flex justify-between gap-4 text-emerald-700 dark:text-emerald-400"
          >
            <dt>Verified</dt>
            <dd
              class="font-mono truncate"
              :title="b.displayPhoneNumber ? `${b.verifiedName} (${b.displayPhoneNumber})` : b.verifiedName"
            >
              {{ b.verifiedName }}<template v-if="b.displayPhoneNumber">
                ({{ b.displayPhoneNumber }})
              </template>
            </dd>
          </div>
        </dl>

        <div class="flex justify-end items-center gap-1">
          <!-- JCLAW-448: open the QR-pairing panel for an unofficial
               WhatsApp-Web binding. -->
          <button
            v-if="b.transport === 'WHATSAPP_WEB'"
            type="button"
            title="Pair this number by scanning a QR code"
            aria-label="Pair binding"
            class="px-2 py-1 mr-auto inline-flex items-center gap-1 text-xs text-fg-muted
                   hover:text-emerald-400 border border-border transition-colors"
            @click="openPairing(b)"
          >
            <QrCodeIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
            Pair
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
      </div>
    </div>

    <div
      v-if="creating || editing"
      class="bg-surface-elevated border border-border p-6 mt-6"
    >
      <h2 class="text-sm font-medium text-fg-strong mb-4">
        {{ editing ? 'Edit binding' : 'New binding' }}
      </h2>

      <div class="space-y-3">
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
            <option value="CLOUD_API">Cloud API (official)</option>
            <option value="WHATSAPP_WEB">WhatsApp-Web (unofficial)</option>
          </select>
          <span class="mt-1 block text-xs text-fg-muted">
            Cloud API is Meta's official platform: a registered business number,
            compliant, no ban risk, rich 1:1 messaging (no group chats).
            WhatsApp-Web pairs your own number for full group support, but uses an
            unofficial client that can get the number banned.
          </span>
        </label>

        <!-- WhatsApp-Web ban-risk warning (JCLAW-444). Shown before save whenever
             the unofficial transport is selected. -->
        <div
          v-if="!isCloud"
          class="border border-amber-500/60 bg-amber-500/10 p-3 text-xs text-amber-300 space-y-2"
        >
          <p class="flex items-center gap-1.5 font-medium text-amber-200">
            <ExclamationTriangleIcon
              class="h-4 w-4 shrink-0"
              aria-hidden="true"
            />
            WhatsApp-Web uses an unofficial client — ban risk
          </p>
          <ul class="list-disc pl-5 space-y-1">
            <li>
              It speaks WhatsApp's reverse-engineered web protocol, which violates
              WhatsApp's Terms of Service. Numbers get banned — documented 2–8 week
              timelines, even for low-volume, reply-only bots.
            </li>
            <li>
              Meta is actively cracking down on third-party AI chatbots on
              WhatsApp, so the risk is tightening, not easing.
            </li>
            <li>
              Use a <span class="font-medium">dedicated secondary number</span> you
              can afford to lose — never your primary line.
            </li>
            <li>
              You'll link the number by scanning a QR code in a later step (pairing
              isn't wired up yet).
            </li>
          </ul>
        </div>

        <template v-if="isCloud">
          <div class="p-3 border border-border text-xs text-fg-muted space-y-1">
            <p class="text-fg-strong font-medium">
              Where to find Cloud API credentials
            </p>
            <p>
              In the
              <a
                href="https://developers.facebook.com/apps"
                target="_blank"
                rel="noopener noreferrer"
                class="text-fg-strong underline hover:text-emerald-400"
              >Meta for Developers</a>
              dashboard → your app → WhatsApp → API Setup: copy the
              <code class="font-mono px-1 bg-muted text-fg-strong">Phone number ID</code>
              and a
              <code class="font-mono px-1 bg-muted text-fg-strong">Access token</code>.
              The
              <code class="font-mono px-1 bg-muted text-fg-strong">App secret</code>
              (Settings → Basic) and your chosen
              <code class="font-mono px-1 bg-muted text-fg-strong">Verify token</code>
              are used by the inbound webhook.
            </p>
          </div>

          <label
            for="binding-phone-number-id"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">phoneNumberId</span>
            <input
              id="binding-phone-number-id"
              v-model="form.phoneNumberId"
              type="text"
              placeholder="e.g. 123456789012345"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
          </label>

          <label
            for="binding-access-token"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">accessToken</span>
            <input
              id="binding-access-token"
              v-model="form.accessToken"
              type="password"
              :placeholder="editing ? 'leave blank to keep existing token' : 'Graph API access token'"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
          </label>

          <label
            for="binding-app-secret"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">appSecret</span>
            <input
              id="binding-app-secret"
              v-model="form.appSecret"
              type="password"
              :placeholder="editing ? 'leave blank to keep existing secret' : 'App secret (for inbound webhook)'"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
          </label>

          <label
            for="binding-verify-token"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">verifyToken</span>
            <input
              id="binding-verify-token"
              v-model="form.verifyToken"
              type="password"
              :placeholder="editing ? 'leave blank to keep existing token' : 'Your chosen webhook verify token'"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
          </label>

          <!-- JCLAW-445: optional pre-approved template for out-of-window replies. -->
          <div class="grid grid-cols-1 sm:grid-cols-2 gap-3">
            <label
              for="binding-template-name"
              class="block"
            >
              <span class="block text-xs text-fg-muted mb-1">templateName (optional)</span>
              <input
                id="binding-template-name"
                v-model="form.templateName"
                type="text"
                placeholder="e.g. assistant_reply"
                class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                       focus:outline-hidden focus:border-ring transition-colors"
              >
            </label>
            <label
              for="binding-template-language"
              class="block"
            >
              <span class="block text-xs text-fg-muted mb-1">templateLanguage (optional)</span>
              <input
                id="binding-template-language"
                v-model="form.templateLanguage"
                type="text"
                placeholder="e.g. en_US"
                class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                       focus:outline-hidden focus:border-ring transition-colors"
              >
            </label>
          </div>
          <p class="text-xs text-fg-muted">
            Used to reply outside WhatsApp's 24-hour customer-service window: after
            24 hours of silence Meta only allows a pre-approved template message, so
            set this to keep late replies deliverable.
          </p>

          <!-- JCLAW-425: optional proactive-send recipient. A Cloud-API business
               number receives from many customers, so an agent-initiated send with
               no active chat needs an explicit destination — set the one this
               agent should reach (e.g. your own phone). -->
          <label
            for="binding-default-target"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">default recipient (optional)</span>
            <input
              id="binding-default-target"
              v-model="form.defaultTarget"
              type="text"
              placeholder="e.g. +15551234567 (E.164)"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
          </label>
          <p class="text-xs text-fg-muted">
            Where this agent sends a proactive message (a progress update or a
            scheduled briefing) when it isn't replying inside an existing chat. A
            business number has no single "owner", so set the recipient explicitly;
            out-of-window sends use the template above.
          </p>
        </template>

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
            class="mt-1 text-xs text-amber-700 dark:text-amber-400"
          >
            Select an agent from the dropdown before saving.
          </p>
        </label>
      </div>

      <p
        v-if="errorMessage"
        class="mt-3 text-xs text-red-700 dark:text-red-400"
      >
        {{ errorMessage }}
      </p>

      <div class="flex gap-2 mt-4">
        <button
          :disabled="saving || !canSave"
          class="px-4 py-1.5 bg-emerald-700 text-white text-sm font-medium
                 hover:bg-emerald-600 disabled:opacity-40 transition-colors"
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

    <!-- JCLAW-448: WhatsApp-Web QR pairing panel. Polls the binding's /qr
         endpoint and renders the rotating pairing string as a QR image locally
         (the raw string never leaves the browser). -->
    <div
      v-if="pairing"
      class="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4"
      role="dialog"
      aria-modal="true"
      aria-label="Pair WhatsApp-Web number"
    >
      <div class="w-full max-w-sm bg-surface-elevated border border-border p-6">
        <h2 class="text-sm font-medium text-fg-strong mb-1">
          Pair {{ pairing.agentName ?? 'binding' }}
        </h2>
        <p class="text-xs text-fg-muted mb-4">
          On the phone you want to link, open WhatsApp → Settings → Linked Devices →
          Link a Device, then scan this code. It refreshes every few seconds until
          you scan it.
        </p>

        <div class="flex flex-col items-center gap-3 min-h-[16rem] justify-center">
          <p
            v-if="pairConnected"
            class="text-sm text-emerald-700 dark:text-emerald-400 font-medium"
          >
            Connected ✓
          </p>
          <p
            v-else-if="pairError"
            class="text-xs text-red-700 dark:text-red-400 text-center"
          >
            {{ pairError }}
          </p>
          <img
            v-else-if="pairImageUrl"
            :src="pairImageUrl"
            alt="WhatsApp-Web pairing QR code"
            class="w-56 h-56 bg-white p-2"
          >
          <p
            v-else
            class="text-xs text-fg-muted"
          >
            Waiting for a pairing code…
          </p>
        </div>

        <div class="flex justify-end mt-4">
          <button
            type="button"
            class="px-4 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors"
            @click="closePairing"
          >
            {{ pairConnected ? 'Close' : 'Cancel' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
