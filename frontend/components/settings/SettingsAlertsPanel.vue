<script setup lang="ts">
// Alerts settings panel (JCLAW-1279). alerts.delivery names the one channel, as
// channel:target, that hears about unattended failures; when to send is the
// backend's call (OperatorAlerts), and it sends through the main agent's bindings.
const { configData, saving, refresh } = useSettingsConfig()

const KEY = 'alerts.delivery'

const CHANNELS = [
  { value: 'telegram', label: 'Telegram', target: 'Chat ID' },
  { value: 'slack', label: 'Slack', target: 'Channel ID' },
  { value: 'whatsapp', label: 'WhatsApp', target: 'Phone number' },
  { value: 'web', label: 'Web chat', target: 'Conversation ID' },
] as const

const stored = computed(() => configData.value?.entries?.find(e => e.key === KEY)?.value?.trim() ?? '')

const channel = ref('')
const target = ref('')
watch(stored, (value) => {
  const colon = value.indexOf(':')
  channel.value = colon > 0 ? value.slice(0, colon) : ''
  target.value = colon > 0 ? value.slice(colon + 1) : ''
}, { immediate: true })

const targetLabel = computed(() => CHANNELS.find(c => c.value === channel.value)?.target ?? 'Target')

const { saveError, attempt } = useSaveAttempt()

async function save() {
  saving.value = true
  await attempt(async () => {
    await $fetch('/api/config', { method: 'POST', body: { key: KEY, value: `${channel.value}:${target.value.trim()}` } })
  })
  await refresh()
  saving.value = false
}

async function turnOff() {
  saving.value = true
  await attempt(async () => {
    await $fetch(`/api/config/${KEY}`, { method: 'DELETE' })
  })
  await refresh()
  saving.value = false
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Alerts
    </h2>
    <p class="text-xs text-fg-muted">
      A message on a channel you choose when work that runs without you fails: an
      LLM provider or MCP server stops answering, and again when it recovers, or a
      run of a recurring task fails. Alerts are sent through the
      <span class="font-mono">main</span> agent's channel connections.
    </p>
    <div class="bg-surface-elevated border border-border px-4 py-3 space-y-3">
      <p
        class="text-sm text-fg-primary"
        data-testid="alerts-status"
      >
        <template v-if="stored">
          Alerts go to <span class="font-mono">{{ stored }}</span>
        </template>
        <template v-else>
          Off. No alerts are sent.
        </template>
      </p>
      <div class="flex max-sm:flex-wrap items-end gap-3">
        <label
          for="alerts-channel"
          class="flex flex-col gap-1 text-xs text-fg-muted"
        >
          Channel
          <select
            id="alerts-channel"
            v-model="channel"
            class="px-2 py-1 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden"
          >
            <option
              value=""
              disabled
            >
              Choose a channel
            </option>
            <option
              v-for="c in CHANNELS"
              :key="c.value"
              :value="c.value"
            >
              {{ c.label }}
            </option>
          </select>
        </label>
        <label
          for="alerts-target"
          class="flex flex-1 min-w-0 flex-col gap-1 text-xs text-fg-muted"
        >
          {{ targetLabel }}
          <input
            id="alerts-target"
            v-model="target"
            type="text"
            class="min-w-0 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
          >
        </label>
        <button
          class="px-3 py-1.5 text-sm border border-border bg-surface-elevated text-fg-primary hover:bg-muted transition-colors disabled:opacity-50"
          :disabled="saving || !channel || !target.trim()"
          @click="save"
        >
          Save
        </button>
        <button
          v-if="stored"
          class="px-3 py-1.5 text-sm border border-border bg-surface-elevated text-fg-primary hover:bg-muted transition-colors disabled:opacity-50"
          :disabled="saving"
          @click="turnOff"
        >
          Turn off
        </button>
      </div>
    </div>
    <ApiErrorAlert :error="saveError" />
  </div>
</template>
