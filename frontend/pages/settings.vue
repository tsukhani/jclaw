<script setup lang="ts">
const { data: configData, refresh } = await useFetch<{ entries: any[] }>('/api/config')
const newKey = ref('')
const newValue = ref('')
const saving = ref(false)
const editingKey = ref<string | null>(null)
const editValue = ref('')

async function saveNew() {
  if (!newKey.value.trim()) return
  saving.value = true
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key: newKey.value, value: newValue.value }
    })
    newKey.value = ''
    newValue.value = ''
    refresh()
  } catch (e) {
    console.error('Failed to save config:', e)
  } finally {
    saving.value = false
  }
}

async function updateEntry(key: string) {
  saving.value = true
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key, value: editValue.value }
    })
    editingKey.value = null
    refresh()
  } catch (e) {
    console.error('Failed to update config:', e)
  } finally {
    saving.value = false
  }
}

async function deleteEntry(key: string) {
  await $fetch(`/api/config/${encodeURIComponent(key)}`, { method: 'DELETE' })
  refresh()
}

function startEdit(entry: any) {
  editingKey.value = entry.key
  editValue.value = entry.value
}

function isSensitive(key: string) {
  const lower = key.toLowerCase()
  return ['key', 'secret', 'password', 'token'].some(s => lower.includes(s))
}

// Config keys managed by dedicated UI sections (excluded from general Configuration)
const MANAGED_CONFIG_KEYS = new Set([
  'agent.maxToolRounds',
  'jclaw.tools.playwright.enabled', 'jclaw.tools.playwright.headless',
  'jclaw.tools.shell.enabled', 'shell.allowlist', 'shell.defaultTimeoutSeconds',
  'shell.maxTimeoutSeconds', 'shell.maxOutputBytes',
])

// Agent config
const agentMaxToolRounds = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find((e: any) => e.key === 'agent.maxToolRounds')?.value ?? '10'
})

const editingAgentField = ref<string | null>(null)
const agentFieldEdit = ref('')

async function saveAgentField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingAgentField.value = null
    refresh()
  } finally {
    saving.value = false
  }
}

// Playwright config
const playwrightEnabled = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find((e: any) => e.key === 'jclaw.tools.playwright.enabled')?.value === 'true'
})

const playwrightHeadless = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find((e: any) => e.key === 'jclaw.tools.playwright.headless')?.value !== 'false'
})

async function togglePlaywrightEnabled() {
  const newVal = playwrightEnabled.value ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: 'jclaw.tools.playwright.enabled', value: newVal } })
  refresh()
}

async function togglePlaywrightHeadless() {
  const newVal = playwrightHeadless.value ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: 'jclaw.tools.playwright.headless', value: newVal } })
  refresh()
}

// Shell execution config
const SHELL_KEYS = ['shell.allowlist', 'shell.defaultTimeoutSeconds', 'shell.maxTimeoutSeconds', 'shell.maxOutputBytes'] as const

const shellEnabled = computed(() => {
  const entries = configData.value?.entries ?? []
  return entries.find((e: any) => e.key === 'jclaw.tools.shell.enabled')?.value === 'true'
})

const shellConfig = computed(() => {
  const entries = configData.value?.entries ?? []
  const map = new Map<string, string>()
  for (const e of entries) {
    if (SHELL_KEYS.includes(e.key as any)) {
      map.set(e.key, e.value)
    }
  }
  return {
    allowlist: map.get('shell.allowlist') ?? '',
    defaultTimeout: map.get('shell.defaultTimeoutSeconds') ?? '30',
    maxTimeout: map.get('shell.maxTimeoutSeconds') ?? '300',
    maxOutput: map.get('shell.maxOutputBytes') ?? '102400',
  }
})

const shellAllowlistEdit = ref('')
const shellTimeoutEdit = ref('')
const editingShellField = ref<string | null>(null)

function startShellEdit(field: string, value: string) {
  editingShellField.value = field
  if (field === 'allowlist') shellAllowlistEdit.value = value
  else shellTimeoutEdit.value = value
}

async function saveShellField(configKey: string, value: string) {
  saving.value = true
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: configKey, value } })
    editingShellField.value = null
    refresh()
  } finally {
    saving.value = false
  }
}

async function toggleShellEnabled() {
  const newVal = shellEnabled.value ? 'false' : 'true'
  await $fetch('/api/config', { method: 'POST', body: { key: 'jclaw.tools.shell.enabled', value: newVal } })
  refresh()
}


const SEARCH_PROVIDERS: Record<string, { label: string, keys: { key: string, label: string, placeholder: string }[] }> = {
  exa: {
    label: 'Exa',
    keys: [
      { key: 'exa.apiKey', label: 'apiKey', placeholder: 'Your Exa API key from exa.ai' }
    ]
  },
  brave: {
    label: 'Brave',
    keys: [
      { key: 'brave.apiKey', label: 'apiKey', placeholder: 'Your Brave Search API key from brave.com/search/api' }
    ]
  },
  tavily: {
    label: 'Tavily',
    keys: [
      { key: 'tavily.apiKey', label: 'apiKey', placeholder: 'Your Tavily API key from tavily.com' }
    ]
  }
}

// Group config entries by provider
const providerEntries = computed(() => {
  const entries = configData.value?.entries ?? []
  const providers = new Map<string, any[]>()
  const searchEntries = new Map<string, Map<string, any>>()
  const other: any[] = []

  // Initialize search providers with all expected keys
  for (const [id, def] of Object.entries(SEARCH_PROVIDERS)) {
    const keyMap = new Map<string, any>()
    for (const k of def.keys) {
      keyMap.set(k.key, { key: k.key, value: '', label: k.label, placeholder: k.placeholder })
    }
    searchEntries.set(id, keyMap)
  }

  for (const e of entries) {
    if (e.key.startsWith('provider.')) {
      const parts = e.key.split('.')
      const name = parts[1]
      if (!providers.has(name)) providers.set(name, [])
      providers.get(name)!.push(e)
    } else {
      // Check if it belongs to a search provider
      let matched = false
      for (const [id, def] of Object.entries(SEARCH_PROVIDERS)) {
        for (const k of def.keys) {
          if (e.key === k.key) {
            searchEntries.get(id)!.set(k.key, { ...e, label: k.label, placeholder: k.placeholder })
            matched = true
          }
        }
      }
      if (!matched && !MANAGED_CONFIG_KEYS.has(e.key)) other.push(e)
    }
  }
  return { providers, searchEntries, other }
})
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-white mb-6">Settings</h1>

    <!-- Provider sections -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">LLM Providers</h2>
      <p class="text-xs text-neutral-600">Enter an API key for at least one provider to enable chat. Base URLs and models are pre-configured.</p>
      <div v-for="[name, entries] in providerEntries.providers" :key="name"
           class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800">
          <span class="text-sm font-medium text-white">{{ name }}</span>
          <span v-if="entries.find((e: any) => e.key.endsWith('.apiKey') && e.value && !e.value.startsWith('****') && e.value !== '****')"
                class="ml-2 text-[10px] text-green-400 border border-green-400/30 px-1">configured</span>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <div v-for="entry in entries" :key="entry.key" class="px-4 py-2 flex items-center gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0">{{ entry.key.split('.').slice(2).join('.') }}</span>
            <template v-if="editingKey === entry.key">
              <input v-model="editValue"
                     :type="isSensitive(entry.key) ? 'password' : 'text'"
                     class="flex-1 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none" />
              <button @click="updateEntry(entry.key)" class="text-xs text-white hover:text-emerald-400 transition-colors">Save</button>
              <button @click="editingKey = null" class="text-xs text-neutral-500 hover:text-white transition-colors">Cancel</button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono truncate">{{ entry.value || '(empty)' }}</span>
              <button @click="startEdit(entry)" class="text-xs text-neutral-500 hover:text-white transition-colors">Edit</button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Search Providers -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">Search Providers</h2>
      <p class="text-xs text-neutral-600">Configure API keys for web search tools used by agents.</p>
      <div v-for="[id, keyMap] in providerEntries.searchEntries" :key="id"
           class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800">
          <span class="text-sm font-medium text-white">{{ SEARCH_PROVIDERS[id].label }}</span>
          <span v-if="[...keyMap.values()].find(e => e.key.endsWith('apiKey') && e.value && e.value !== '(empty)')"
                class="ml-2 text-[10px] text-green-400 border border-green-400/30 px-1">configured</span>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <div v-for="entry in [...keyMap.values()]" :key="entry.key" class="px-4 py-2 flex items-center gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0">{{ entry.label }}</span>
            <template v-if="editingKey === entry.key">
              <input v-model="editValue"
                     :type="isSensitive(entry.key) ? 'password' : 'text'"
                     :placeholder="entry.placeholder"
                     class="flex-1 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none" />
              <button @click="updateEntry(entry.key)" class="text-xs text-white hover:text-emerald-400 transition-colors">Save</button>
              <button @click="editingKey = null" class="text-xs text-neutral-500 hover:text-white transition-colors">Cancel</button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono truncate">{{ entry.value || '(not set)' }}</span>
              <button @click="startEdit(entry)" class="text-xs text-neutral-500 hover:text-white transition-colors">Edit</button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Agent Settings -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">Agent</h2>
      <p class="text-xs text-neutral-600">Configure agent behavior limits.</p>
      <div class="bg-neutral-900 border border-neutral-800">
        <div class="divide-y divide-neutral-800/50">
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0">maxToolRounds</span>
            <template v-if="editingAgentField === 'maxToolRounds'">
              <input v-model="agentFieldEdit" type="number" min="1" max="50"
                     class="w-24 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white font-mono focus:outline-none" />
              <button @click="saveAgentField('agent.maxToolRounds', agentFieldEdit)" class="text-xs text-white hover:text-emerald-400 transition-colors">Save</button>
              <button @click="editingAgentField = null" class="text-xs text-neutral-500 hover:text-white transition-colors">Cancel</button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono">{{ agentMaxToolRounds }} rounds</span>
              <button @click="editingAgentField = 'maxToolRounds'; agentFieldEdit = agentMaxToolRounds" class="text-xs text-neutral-500 hover:text-white transition-colors">Edit</button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Browser (Playwright) -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">Browser (Playwright)</h2>
      <p class="text-xs text-neutral-600">Headless browser automation for JS-heavy pages. Requires the Playwright driver bundle.</p>
      <div class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-white">Enabled</span>
            <span v-if="playwrightEnabled" class="text-[10px] text-green-400 border border-green-400/30 px-1">active</span>
            <span v-else class="text-[10px] text-neutral-500 border border-neutral-700 px-1">disabled</span>
          </div>
          <button @click="togglePlaywrightEnabled"
                  :class="playwrightEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-neutral-700 hover:bg-neutral-600'"
                  class="relative w-9 h-5 rounded-full transition-colors">
            <span :class="playwrightEnabled ? 'translate-x-4' : 'translate-x-0.5'"
                  class="block w-4 h-4 bg-white rounded-full transition-transform" />
          </button>
        </div>
        <div class="px-4 py-2.5 flex items-center justify-between">
          <div>
            <span class="text-xs font-mono text-neutral-500">headless</span>
            <p v-if="!playwrightHeadless" class="text-[10px] text-amber-400 mt-0.5">Browser window will be visible on the host</p>
          </div>
          <button @click="togglePlaywrightHeadless"
                  :class="playwrightHeadless ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-neutral-700 hover:bg-neutral-600'"
                  class="relative w-9 h-5 rounded-full transition-colors">
            <span :class="playwrightHeadless ? 'translate-x-4' : 'translate-x-0.5'"
                  class="block w-4 h-4 bg-white rounded-full transition-transform" />
          </button>
        </div>
      </div>
    </div>

    <!-- Shell Execution -->
    <div class="mb-6 space-y-4">
      <h2 class="text-sm font-medium text-neutral-400">Shell Execution</h2>
      <p class="text-xs text-neutral-600">Allow agents to execute shell commands on the host.</p>
      <div class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800 flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-white">Enabled</span>
            <span v-if="shellEnabled" class="text-[10px] text-green-400 border border-green-400/30 px-1">active</span>
            <span v-else class="text-[10px] text-neutral-500 border border-neutral-700 px-1">disabled</span>
          </div>
          <button @click="toggleShellEnabled"
                  :class="shellEnabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-neutral-700 hover:bg-neutral-600'"
                  class="relative w-9 h-5 rounded-full transition-colors">
            <span :class="shellEnabled ? 'translate-x-4' : 'translate-x-0.5'"
                  class="block w-4 h-4 bg-white rounded-full transition-transform" />
          </button>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <!-- Allowlist -->
          <div class="px-4 py-2.5 flex items-start gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0 pt-0.5">allowlist</span>
            <template v-if="editingShellField === 'allowlist'">
              <textarea v-model="shellAllowlistEdit" rows="3"
                        class="flex-1 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white font-mono focus:outline-none resize-none" />
              <div class="flex flex-col gap-1">
                <button @click="saveShellField('shell.allowlist', shellAllowlistEdit)" class="text-xs text-white hover:text-emerald-400 transition-colors">Save</button>
                <button @click="editingShellField = null" class="text-xs text-neutral-500 hover:text-white transition-colors">Cancel</button>
              </div>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono truncate">{{ shellConfig.allowlist || '(not set)' }}</span>
              <button @click="startShellEdit('allowlist', shellConfig.allowlist)" class="text-xs text-neutral-500 hover:text-white transition-colors">Edit</button>
            </template>
          </div>
          <!-- Default timeout -->
          <div class="px-4 py-2.5 flex items-center gap-3">
            <span class="text-xs font-mono text-neutral-500 w-48 shrink-0">defaultTimeoutSeconds</span>
            <template v-if="editingShellField === 'timeout'">
              <input v-model="shellTimeoutEdit" type="number" min="1" max="300"
                     class="w-24 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white font-mono focus:outline-none" />
              <button @click="saveShellField('shell.defaultTimeoutSeconds', shellTimeoutEdit)" class="text-xs text-white hover:text-emerald-400 transition-colors">Save</button>
              <button @click="editingShellField = null" class="text-xs text-neutral-500 hover:text-white transition-colors">Cancel</button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-neutral-300 font-mono">{{ shellConfig.defaultTimeout }}s</span>
              <button @click="startShellEdit('timeout', shellConfig.defaultTimeout)" class="text-xs text-neutral-500 hover:text-white transition-colors">Edit</button>
            </template>
          </div>
        </div>
      </div>
    </div>

    <!-- Config entries -->
    <div class="bg-neutral-900 border border-neutral-800">
      <div class="px-4 py-3 border-b border-neutral-800">
        <h2 class="text-sm font-medium text-neutral-300">Configuration</h2>
      </div>
      <div class="divide-y divide-neutral-800/50">
        <div v-for="entry in providerEntries.other" :key="entry.key"
             class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-neutral-400 w-64 shrink-0 truncate">{{ entry.key }}</span>
          <template v-if="editingKey === entry.key">
            <input v-model="editValue"
                   :type="isSensitive(entry.key) ? 'password' : 'text'"
                   class="flex-1 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none" />
            <button @click="updateEntry(entry.key)" class="text-xs text-white hover:text-emerald-400 transition-colors">Save</button>
            <button @click="editingKey = null" class="text-xs text-neutral-500 hover:text-white transition-colors">Cancel</button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-neutral-300 font-mono truncate">{{ entry.value }}</span>
            <button @click="startEdit(entry)" class="text-xs text-neutral-500 hover:text-white transition-colors">Edit</button>
            <button @click="deleteEntry(entry.key)" class="text-xs text-neutral-600 hover:text-red-400 transition-colors">Delete</button>
          </template>
        </div>
      </div>
      <div v-if="!configData?.entries?.length" class="px-4 py-8 text-center text-sm text-neutral-600">
        No configuration entries
      </div>
    </div>

    <!-- Add new -->
    <div class="mt-4 bg-neutral-900 border border-neutral-800 p-4">
      <h3 class="text-xs font-medium text-neutral-400 mb-3">Add Entry</h3>
      <div class="flex gap-2">
        <input v-model="newKey" placeholder="key" class="flex-1 px-2 py-1.5 bg-neutral-800 border border-neutral-700 text-sm text-white font-mono focus:outline-none" />
        <input v-model="newValue" placeholder="value" class="flex-1 px-2 py-1.5 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none" />
        <button @click="saveNew" :disabled="saving || !newKey.trim()"
                class="px-3 py-1.5 bg-emerald-600 text-white text-xs font-medium hover:bg-emerald-500 disabled:opacity-40 transition-colors">Add</button>
      </div>
    </div>
  </div>
</template>
