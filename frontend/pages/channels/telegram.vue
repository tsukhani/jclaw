<script setup lang="ts">
import type { Agent, TelegramBindingSummary } from '~/types/api'

const [{ data: bindings, refresh }, { data: agents }] = await Promise.all([
  useFetch<TelegramBindingSummary[]>('/api/channels/telegram/bindings'),
  useFetch<Agent[]>('/api/agents'),
])

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

/**
 * Ticks every second while the page is mounted so cooldown countdowns update
 * live without re-fetching. When a binding's cooldown crosses zero, a single
 * refresh() pulls fresh data from the server so the toggle unlocks naturally.
 */
const nowMs = ref(Date.now())
let tickHandle: ReturnType<typeof setInterval> | null = null
let refreshingAfterCooldown = false

onMounted(() => {
  tickHandle = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
})
onUnmounted(() => {
  if (tickHandle) clearInterval(tickHandle)
})

function cooldownSecondsLeft(b: TelegramBindingSummary): number {
  if (!b.cooldownUntil) return 0
  const until = Date.parse(b.cooldownUntil)
  if (Number.isNaN(until)) return 0
  return Math.max(0, Math.ceil((until - nowMs.value) / 1000))
}

watch(nowMs, async () => {
  if (refreshingAfterCooldown) return
  const anyExpired = (bindings.value ?? []).some((b) => {
    if (!b.cooldownUntil) return false
    const until = Date.parse(b.cooldownUntil)
    return !Number.isNaN(until) && until <= nowMs.value
  })
  if (!anyExpired) return
  refreshingAfterCooldown = true
  try {
    await refresh()
  }
  finally {
    refreshingAfterCooldown = false
  }
})

const creating = ref(false)
const editing = ref<TelegramBindingSummary | null>(null)

interface BindingForm {
  botToken: string
  agentId: number | null
  agentQuery: string
  telegramUserId: string
  transport: 'POLLING' | 'WEBHOOK'
  webhookUrl: string
  webhookSecret: string
}

const emptyForm = (): BindingForm => ({
  botToken: '',
  agentId: null,
  agentQuery: '',
  telegramUserId: '',
  transport: 'POLLING',
  webhookUrl: '',
  webhookSecret: '',
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
  if (form.value.transport === 'WEBHOOK') {
    if (form.value.webhookUrl.trim().length === 0) return false
    // Webhook secret is required when creating or when the existing binding
    // doesn't already have one; editing a binding with an existing secret
    // allows leaving this blank to keep the current value.
    const hadSecret = editing.value?.hasWebhookSecret === true
    if (!hadSecret && form.value.webhookSecret.trim().length === 0) return false
  }
  return true
})

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
    webhookUrl: binding.webhookUrl ?? '',
    webhookSecret: '',
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
    body.webhookUrl = form.value.webhookUrl.trim()
    // Blank-on-edit leaves the stored secret untouched; sending it only when
    // provided matches the botToken "leave blank to keep" semantics.
    if (form.value.webhookSecret.trim()) {
      body.webhookSecret = form.value.webhookSecret.trim()
    }
  }
  else {
    // Switching to POLLING clears webhook config so the stored binding doesn't
    // leak stale URL/secret if the admin flips back later.
    body.webhookUrl = null
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
  // Cooldown lock: ignore clicks during the post-unregister drain window.
  // The button is :disabled in that state, but this guards a defensive second
  // path (programmatic click, keyboard, etc.).
  if (cooldownSecondsLeft(binding) > 0) return
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
          Telegram bindings
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
            <span
              v-if="cooldownSecondsLeft(b) > 0"
              class="text-xs font-mono text-fg-muted"
              :title="`Telegram long-poll drain in progress — re-enable unlocks in ${cooldownSecondsLeft(b)}s`"
            >
              disabling {{ cooldownSecondsLeft(b) }}s
            </span>
            <button
              type="button"
              role="switch"
              :aria-checked="b.enabled"
              :disabled="cooldownSecondsLeft(b) > 0"
              :aria-label="b.enabled ? 'Disable binding' : 'Enable binding'"
              :title="cooldownSecondsLeft(b) > 0
                ? `Disabling — unlocks in ${cooldownSecondsLeft(b)}s`
                : (b.enabled ? 'Enabled — click to disable' : 'Disabled — click to enable')"
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
        <div class="flex justify-end gap-1">
          <button
            type="button"
            title="Edit binding"
            aria-label="Edit binding"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            @click="openEdit(b)"
          >
            <svg
              class="w-3.5 h-3.5"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            ><path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z"
            /></svg>
          </button>
          <button
            type="button"
            title="Delete binding"
            aria-label="Delete binding"
            class="p-1 text-fg-muted hover:text-red-400 transition-colors"
            @click="remove(b)"
          >
            <svg
              class="w-3.5 h-3.5"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            ><path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
            /></svg>
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

        <template v-if="form.transport === 'WEBHOOK'">
          <label
            for="binding-webhook-url"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">webhookUrl</span>
            <input
              id="binding-webhook-url"
              v-model="form.webhookUrl"
              type="url"
              placeholder="https://your-host.example.com/api/webhooks/telegram/…"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
          </label>

          <label
            for="binding-webhook-secret"
            class="block"
          >
            <span class="block text-xs text-fg-muted mb-1">webhookSecret</span>
            <input
              id="binding-webhook-secret"
              v-model="form.webhookSecret"
              type="password"
              :placeholder="editing && editing.hasWebhookSecret
                ? 'leave blank to keep existing secret'
                : 'shared secret Telegram echoes on every POST'"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                     focus:outline-hidden focus:border-ring transition-colors"
            >
          </label>
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
