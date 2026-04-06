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
  await $fetch('/api/config', {
    method: 'POST',
    body: { key: newKey.value, value: newValue.value }
  })
  saving.value = false
  newKey.value = ''
  newValue.value = ''
  refresh()
}

async function updateEntry(key: string) {
  saving.value = true
  await $fetch('/api/config', {
    method: 'POST',
    body: { key, value: editValue.value }
  })
  saving.value = false
  editingKey.value = null
  refresh()
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

// Group config entries by provider
const providerEntries = computed(() => {
  const entries = configData.value?.entries ?? []
  const providers = new Map<string, any[]>()
  const other: any[] = []
  for (const e of entries) {
    if (e.key.startsWith('provider.')) {
      const parts = e.key.split('.')
      const name = parts[1]
      if (!providers.has(name)) providers.set(name, [])
      providers.get(name)!.push(e)
    } else {
      other.push(e)
    }
  }
  return { providers, other }
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
              <button @click="updateEntry(entry.key)" class="text-xs text-white hover:text-green-400 transition-colors">Save</button>
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

    <!-- Config entries -->
    <div class="bg-neutral-900 border border-neutral-800">
      <div class="px-4 py-3 border-b border-neutral-800">
        <h2 class="text-sm font-medium text-neutral-300">Configuration</h2>
      </div>
      <div class="divide-y divide-neutral-800/50">
        <div v-for="entry in configData?.entries" :key="entry.key"
             class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-neutral-400 w-64 shrink-0 truncate">{{ entry.key }}</span>
          <template v-if="editingKey === entry.key">
            <input v-model="editValue"
                   :type="isSensitive(entry.key) ? 'password' : 'text'"
                   class="flex-1 px-2 py-1 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none" />
            <button @click="updateEntry(entry.key)" class="text-xs text-white hover:text-green-400 transition-colors">Save</button>
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
                class="px-3 py-1.5 bg-white text-neutral-950 text-xs font-medium hover:bg-neutral-200 disabled:opacity-40 transition-colors">Add</button>
      </div>
    </div>
  </div>
</template>
