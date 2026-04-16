<script setup lang="ts">
const { data: channels, refresh } = await useFetch<any[]>('/api/channels')

const channelTypes = [
  { type: 'telegram', label: 'Telegram', fields: ['botToken', 'webhookSecret', 'webhookUrl'] },
  { type: 'slack', label: 'Slack', fields: ['botToken', 'signingSecret'] },
  { type: 'whatsapp', label: 'WhatsApp', fields: ['phoneNumberId', 'accessToken', 'appSecret', 'verifyToken'] },
]

interface ChannelInfo {
  channelType: string
  enabled: boolean
  config: Record<string, string>
}

const editing = ref<string | null>(null)
const form = ref<Record<string, string>>({})
const enabled = ref(false)
const { mutate, loading: saving } = useApiMutation()

function editChannel(type: string) {
  const existing = channels.value?.find((c: ChannelInfo) => c.channelType === type)
  if (existing) {
    form.value = { ...existing.config }
    enabled.value = existing.enabled
  } else {
    form.value = {}
    enabled.value = false
  }
  editing.value = type
}

async function saveChannel() {
  if (!editing.value) return
  const result = await mutate(`/api/channels/${editing.value}`, {
    method: 'PUT',
    body: { config: form.value, enabled: enabled.value }
  })
  if (result !== null) {
    editing.value = null
    refresh()
  }
}

function getChannelStatus(type: string) {
  const ch = channels.value?.find((c: ChannelInfo) => c.channelType === type)
  return ch?.enabled ? 'active' : 'inactive'
}
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">Channels</h1>

    <div class="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
      <div
        v-for="ch in channelTypes"
        :key="ch.type"
        class="bg-surface-elevated border border-border p-4"
      >
        <div class="flex items-center justify-between mb-3">
          <h3 class="text-sm font-medium text-fg-strong">{{ ch.label }}</h3>
          <span
            :class="getChannelStatus(ch.type) === 'active' ? 'text-green-400' : 'text-fg-muted'"
            class="text-xs font-mono"
          >{{ getChannelStatus(ch.type) }}</span>
        </div>
        <button
          @click="editChannel(ch.type)"
          class="text-xs text-fg-muted hover:text-fg-strong transition-colors"
        >Configure</button>
      </div>
    </div>

    <!-- Edit modal -->
    <div v-if="editing" class="bg-surface-elevated border border-border p-6">
      <h2 class="text-sm font-medium text-fg-strong mb-4">
        Configure {{ channelTypes.find(c => c.type === editing)?.label }}
      </h2>
      <div class="space-y-3">
        <div v-for="field in channelTypes.find(c => c.type === editing)?.fields" :key="field">
          <label class="block text-xs text-fg-muted mb-1">{{ field }}</label>
          <input
            v-model="form[field]"
            :type="field.toLowerCase().includes('token') || field.toLowerCase().includes('secret') ? 'password' : 'text'"
            class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong
                   focus:outline-hidden focus:border-ring transition-colors"
          />
        </div>
        <div class="flex items-center gap-2">
          <input type="checkbox" v-model="enabled" id="channel-enabled"
                 class="accent-white" />
          <label for="channel-enabled" class="text-xs text-fg-muted">Enabled</label>
        </div>
      </div>
      <div class="flex gap-2 mt-4">
        <button
          @click="saveChannel"
          :disabled="saving"
          class="px-4 py-1.5 bg-emerald-600 text-white text-sm font-medium
                 hover:bg-emerald-500 disabled:opacity-40 transition-colors"
        >{{ saving ? 'Saving...' : 'Save' }}</button>
        <button
          @click="editing = null"
          class="px-4 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors"
        >Cancel</button>
      </div>
    </div>
  </div>
</template>
