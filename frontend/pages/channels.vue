<script setup lang="ts">
const { data: channels, refresh } = await useFetch<any[]>('/api/channels')

const channelTypes = [
  { type: 'telegram', label: 'Telegram', fields: ['botToken', 'webhookSecret', 'webhookUrl'] },
  { type: 'slack', label: 'Slack', fields: ['botToken', 'signingSecret'] },
  { type: 'whatsapp', label: 'WhatsApp', fields: ['phoneNumberId', 'accessToken', 'appSecret', 'verifyToken'] },
]

const editing = ref<string | null>(null)
const form = ref<Record<string, string>>({})
const enabled = ref(false)
const saving = ref(false)

function editChannel(type: string) {
  const existing = channels.value?.find((c: any) => c.channelType === type)
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
  saving.value = true
  try {
    await $fetch(`/api/channels/${editing.value}`, {
      method: 'PUT',
      body: { config: form.value, enabled: enabled.value }
    })
    editing.value = null
    refresh()
  } catch (e) {
    console.error('Failed to save channel:', e)
  } finally {
    saving.value = false
  }
}

function getChannelStatus(type: string) {
  const ch = channels.value?.find((c: any) => c.channelType === type)
  return ch?.enabled ? 'active' : 'inactive'
}
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-white mb-6">Channels</h1>

    <div class="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
      <div
        v-for="ch in channelTypes"
        :key="ch.type"
        class="bg-neutral-900 border border-neutral-800 p-4"
      >
        <div class="flex items-center justify-between mb-3">
          <h3 class="text-sm font-medium text-white">{{ ch.label }}</h3>
          <span
            :class="getChannelStatus(ch.type) === 'active' ? 'text-green-400' : 'text-neutral-600'"
            class="text-xs font-mono"
          >{{ getChannelStatus(ch.type) }}</span>
        </div>
        <button
          @click="editChannel(ch.type)"
          class="text-xs text-neutral-400 hover:text-white transition-colors"
        >Configure</button>
      </div>
    </div>

    <!-- Edit modal -->
    <div v-if="editing" class="bg-neutral-900 border border-neutral-800 p-6">
      <h2 class="text-sm font-medium text-white mb-4">
        Configure {{ channelTypes.find(c => c.type === editing)?.label }}
      </h2>
      <div class="space-y-3">
        <div v-for="field in channelTypes.find(c => c.type === editing)?.fields" :key="field">
          <label class="block text-xs text-neutral-500 mb-1">{{ field }}</label>
          <input
            v-model="form[field]"
            :type="field.toLowerCase().includes('token') || field.toLowerCase().includes('secret') ? 'password' : 'text'"
            class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white
                   focus:outline-none focus:border-neutral-600 transition-colors"
          />
        </div>
        <div class="flex items-center gap-2">
          <input type="checkbox" v-model="enabled" id="channel-enabled"
                 class="accent-white" />
          <label for="channel-enabled" class="text-xs text-neutral-400">Enabled</label>
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
          class="px-4 py-1.5 text-sm text-neutral-400 hover:text-white transition-colors"
        >Cancel</button>
      </div>
    </div>
  </div>
</template>
