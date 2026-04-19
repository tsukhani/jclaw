<script setup lang="ts">
interface ChannelInfo {
  channelType: string
  enabled: boolean
  config: Record<string, string>
}

interface ChannelField {
  name: string
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

const { data: channels, refresh } = await useFetch<ChannelInfo[]>('/api/channels')

const channelTypes: ChannelTypeDef[] = [
  {
    type: 'telegram',
    label: 'Telegram',
    fields: [
      { name: 'botToken', type: 'password' },
      {
        name: 'transport',
        type: 'select',
        default: 'POLLING',
        options: [
          { value: 'POLLING', label: 'Polling (no public URL needed)' },
          { value: 'WEBHOOK', label: 'Webhook (requires public HTTPS URL)' },
        ],
      },
      { name: 'webhookSecret', type: 'password', showWhen: f => f.transport === 'WEBHOOK' },
      { name: 'webhookUrl', type: 'text', showWhen: f => f.transport === 'WEBHOOK' },
    ],
  },
  {
    type: 'slack',
    label: 'Slack',
    fields: [
      { name: 'botToken', type: 'password' },
      { name: 'signingSecret', type: 'password' },
    ],
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
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Channels
    </h1>

    <div class="grid grid-cols-1 md:grid-cols-3 gap-4 mb-6">
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

    <!-- Edit modal -->
    <div
      v-if="editing"
      class="bg-surface-elevated border border-border p-6"
    >
      <h2 class="text-sm font-medium text-fg-strong mb-4">
        Configure {{ channelTypes.find(c => c.type === editing)?.label }}
      </h2>
      <div
        v-if="editing === 'telegram'"
        class="mb-4 p-3 border border-border text-xs text-fg-muted"
      >
        <p class="text-fg-strong font-medium mb-2">
          How to get a bot token
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
            Send <code class="font-mono px-1 bg-muted text-fg-strong">/newbot</code>
            and follow the prompts: pick a display name, then a username ending in
            <code class="font-mono px-1 bg-muted text-fg-strong">bot</code>.
          </li>
          <li>
            BotFather replies with a token like
            <code class="font-mono px-1 bg-muted text-fg-strong">123456:ABC-DEF…</code>.
            Copy it.
          </li>
          <li>
            Paste it into the
            <code class="font-mono px-1 bg-muted text-fg-strong">botToken</code>
            field below.
          </li>
          <li>
            Optional: in BotFather, use
            <code class="font-mono px-1 bg-muted text-fg-strong">/setdescription</code>,
            <code class="font-mono px-1 bg-muted text-fg-strong">/setuserpic</code>, and
            <code class="font-mono px-1 bg-muted text-fg-strong">/setcommands</code>
            to polish your bot's profile.
          </li>
          <li>
            Choose <span class="text-fg-strong">Polling</span> if this JClaw instance
            isn't publicly reachable; otherwise <span class="text-fg-strong">Webhook</span>
            with an HTTPS URL and shared secret.
          </li>
          <li>
            Tick <span class="text-fg-strong">Enabled</span> and save.
          </li>
        </ol>
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
            <span class="block text-xs text-fg-muted mb-1">{{ field.name }}</span>
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
