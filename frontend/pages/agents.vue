<script setup lang="ts">
const { data: agents, refresh } = await useFetch<any[]>('/api/agents')
const { data: configData } = await useFetch<{ entries: any[] }>('/api/config')

const editing = ref<any>(null)
const creating = ref(false)
const workspaceTab = ref('AGENT.md')
const workspaceContent = ref('')
const form = ref({ name: '', modelProvider: '', modelId: '', enabled: true, isDefault: false })
const saving = ref(false)

// Extract configured providers (those with non-empty API keys)
const providers = computed(() => {
  const entries = configData.value?.entries ?? []
  const providerMap = new Map<string, { name: string, models: any[] }>()

  for (const e of entries) {
    if (!e.key.startsWith('provider.')) continue
    const parts = e.key.split('.')
    const name = parts[1]
    if (!providerMap.has(name)) {
      providerMap.set(name, { name, models: [] })
    }
  }

  // Filter to providers that have a non-empty API key
  for (const e of entries) {
    if (e.key.endsWith('.apiKey') && e.key.startsWith('provider.')) {
      const name = e.key.split('.')[1]
      // Masked keys show as "xxxx****" — if it has **** it means a key was set
      if (!e.value || e.value === '(empty)') {
        providerMap.delete(name)
      }
    }
  }

  // Parse models for each remaining provider
  for (const e of entries) {
    if (e.key.endsWith('.models') && e.key.startsWith('provider.')) {
      const name = e.key.split('.')[1]
      const provider = providerMap.get(name)
      if (provider) {
        try {
          provider.models = JSON.parse(e.value)
        } catch {
          provider.models = []
        }
      }
    }
  }

  return Array.from(providerMap.values())
})

// Models for the currently selected provider
const availableModels = computed(() => {
  const provider = providers.value.find(p => p.name === form.value.modelProvider)
  return provider?.models ?? []
})

// Whether the selected provider is configured and the selected model is available
const providerValid = computed(() => {
  const provider = providers.value.find(p => p.name === form.value.modelProvider)
  if (!provider) return false
  return !form.value.modelId || provider.models.some((m: any) => m.id === form.value.modelId)
})

// Auto-select first provider when creating
function newAgent() {
  const defaultProvider = providers.value[0]?.name ?? ''
  const defaultModel = providers.value[0]?.models?.[0]?.id ?? ''
  form.value = { name: '', modelProvider: defaultProvider, modelId: defaultModel, enabled: true, isDefault: false }
  creating.value = true
  editing.value = null
}

function editAgent(agent: any) {
  form.value = {
    name: agent.name,
    modelProvider: agent.modelProvider,
    modelId: agent.modelId,
    enabled: agent.enabled,
    isDefault: agent.isDefault
  }
  editing.value = agent
  creating.value = false
  loadWorkspaceFile(agent.id, 'AGENT.md')
}

// When provider changes, reset model to first available and disable if provider invalid
watch(() => form.value.modelProvider, (newProvider) => {
  const provider = providers.value.find(p => p.name === newProvider)
  const currentModelValid = provider?.models?.some((m: any) => m.id === form.value.modelId)
  if (!currentModelValid) {
    form.value.modelId = provider?.models?.[0]?.id ?? ''
  }
  if (!provider) {
    form.value.enabled = false
  }
})

async function saveAgent() {
  saving.value = true
  try {
    if (creating.value) {
      await $fetch('/api/agents', { method: 'POST', body: form.value })
    } else if (editing.value) {
      await $fetch(`/api/agents/${editing.value.id}`, { method: 'PUT', body: form.value })
    }
    editing.value = null
    creating.value = false
    refresh()
  } catch (e) {
    console.error('Failed to save agent:', e)
  } finally {
    saving.value = false
  }
}

async function deleteAgent(id: number) {
  await $fetch(`/api/agents/${id}`, { method: 'DELETE' })
  editing.value = null
  refresh()
}

async function loadWorkspaceFile(agentId: number, filename: string) {
  workspaceTab.value = filename
  try {
    const data = await $fetch<any>(`/api/agents/${agentId}/workspace/${filename}`)
    workspaceContent.value = data.content ?? ''
  } catch {
    workspaceContent.value = ''
  }
}

async function saveWorkspaceFile() {
  if (!editing.value) return
  await $fetch(`/api/agents/${editing.value.id}/workspace/${workspaceTab.value}`, {
    method: 'PUT',
    body: { content: workspaceContent.value }
  })
}

function cancel() {
  editing.value = null
  creating.value = false
}

const workspaceFiles = ['AGENT.md', 'IDENTITY.md', 'USER.md']
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-white">Agents</h1>
      <button @click="newAgent" :disabled="!providers.length"
              class="px-3 py-1.5 bg-white text-neutral-950 text-xs font-medium hover:bg-neutral-200 disabled:opacity-40 transition-colors">
        New Agent
      </button>
    </div>

    <div v-if="!providers.length && !editing && !creating"
         class="bg-neutral-900 border border-neutral-800 p-4 mb-4 text-sm text-neutral-400">
      No LLM providers configured. Go to <NuxtLink to="/settings" class="text-white underline">Settings</NuxtLink> and add an API key first.
    </div>

    <!-- Agent list -->
    <div v-if="!editing && !creating" class="bg-neutral-900 border border-neutral-800">
      <div v-for="agent in agents" :key="agent.id"
           @click="editAgent(agent)"
           class="px-4 py-3 border-b border-neutral-800/50 flex items-center justify-between hover:bg-neutral-800/50 cursor-pointer transition-colors">
        <div>
          <span class="text-sm text-white">{{ agent.name }}</span>
          <span v-if="agent.isDefault" class="ml-2 text-[10px] text-neutral-500 border border-neutral-700 px-1">default</span>
          <div class="text-xs text-neutral-500 mt-0.5">{{ agent.modelProvider }} / {{ agent.modelId }}</div>
        </div>
        <span :class="agent.enabled && agent.providerConfigured ? 'text-green-400' : 'text-neutral-600'" class="text-xs font-mono">
          {{ agent.enabled && agent.providerConfigured ? 'enabled' : 'disabled' }}
        </span>
      </div>
      <div v-if="!agents?.length" class="px-4 py-8 text-center text-sm text-neutral-600">
        No agents configured
      </div>
    </div>

    <!-- Edit / Create form -->
    <div v-if="editing || creating" class="space-y-4">
      <div class="bg-neutral-900 border border-neutral-800 p-4">
        <h2 class="text-sm font-medium text-white mb-4">{{ creating ? 'New Agent' : 'Edit Agent' }}</h2>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Name</label>
            <input v-model="form.name" class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600" />
          </div>
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Model Provider</label>
            <select v-model="form.modelProvider"
                    class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600">
              <option v-for="p in providers" :key="p.name" :value="p.name">{{ p.name }}</option>
            </select>
          </div>
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Model</label>
            <select v-model="form.modelId"
                    class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600">
              <option v-for="m in availableModels" :key="m.id" :value="m.id">
                {{ m.name || m.id }}
              </option>
            </select>
          </div>
          <div class="flex items-end gap-4">
            <label class="flex items-center gap-1.5 text-xs" :class="providerValid ? 'text-neutral-400' : 'text-neutral-600'">
              <input type="checkbox" v-model="form.enabled" :disabled="!providerValid" class="accent-white" /> Enabled
              <span v-if="!providerValid" class="text-neutral-600 ml-1">(provider not configured)</span>
            </label>
            <label class="flex items-center gap-1.5 text-xs text-neutral-400">
              <input type="checkbox" v-model="form.isDefault" class="accent-white" /> Default
            </label>
          </div>
        </div>
        <div class="flex gap-2 mt-4">
          <button @click="saveAgent" :disabled="saving || !form.name || !form.modelProvider || !form.modelId"
                  class="px-4 py-1.5 bg-white text-neutral-950 text-xs font-medium hover:bg-neutral-200 disabled:opacity-40 transition-colors">
            {{ saving ? 'Saving...' : 'Save' }}
          </button>
          <button @click="cancel" class="px-4 py-1.5 text-xs text-neutral-400 hover:text-white transition-colors">Cancel</button>
          <button v-if="editing" @click="deleteAgent(editing.id)"
                  class="px-4 py-1.5 text-xs text-red-400/60 hover:text-red-400 ml-auto transition-colors">Delete</button>
        </div>
      </div>

      <!-- Workspace editor -->
      <div v-if="editing" class="bg-neutral-900 border border-neutral-800">
        <div class="flex border-b border-neutral-800">
          <button
            v-for="file in workspaceFiles" :key="file"
            @click="loadWorkspaceFile(editing.id, file)"
            :class="workspaceTab === file ? 'text-white border-b border-white' : 'text-neutral-500'"
            class="px-4 py-2 text-xs font-mono transition-colors"
          >{{ file }}</button>
        </div>
        <textarea
          v-model="workspaceContent"
          rows="16"
          class="w-full px-4 py-3 bg-transparent text-sm text-neutral-300 font-mono
                 resize-y focus:outline-none"
        />
        <div class="px-4 py-2 border-t border-neutral-800">
          <button @click="saveWorkspaceFile"
                  class="px-3 py-1 bg-neutral-800 text-xs text-neutral-300 hover:text-white border border-neutral-700 transition-colors">
            Save file
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
