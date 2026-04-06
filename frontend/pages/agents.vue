<script setup lang="ts">
const { data: agents, refresh } = await useFetch<any[]>('/api/agents')

const editing = ref<any>(null)
const creating = ref(false)
const workspaceTab = ref('AGENT.md')
const workspaceContent = ref('')
const form = ref({ name: '', modelProvider: '', modelId: '', enabled: true, isDefault: false })
const saving = ref(false)

function newAgent() {
  form.value = { name: '', modelProvider: 'openrouter', modelId: '', enabled: true, isDefault: false }
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

async function saveAgent() {
  saving.value = true
  if (creating.value) {
    await $fetch('/api/agents', { method: 'POST', body: form.value })
  } else if (editing.value) {
    await $fetch(`/api/agents/${editing.value.id}`, { method: 'PUT', body: form.value })
  }
  saving.value = false
  editing.value = null
  creating.value = false
  refresh()
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
      <button @click="newAgent" class="px-3 py-1.5 bg-white text-neutral-950 text-xs font-medium hover:bg-neutral-200 transition-colors">
        New Agent
      </button>
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
        <span :class="agent.enabled ? 'text-green-400' : 'text-neutral-600'" class="text-xs font-mono">
          {{ agent.enabled ? 'enabled' : 'disabled' }}
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
            <input v-model="form.modelProvider" class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600" />
          </div>
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Model ID</label>
            <input v-model="form.modelId" class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600" />
          </div>
          <div class="flex items-end gap-4">
            <label class="flex items-center gap-1.5 text-xs text-neutral-400">
              <input type="checkbox" v-model="form.enabled" class="accent-white" /> Enabled
            </label>
            <label class="flex items-center gap-1.5 text-xs text-neutral-400">
              <input type="checkbox" v-model="form.isDefault" class="accent-white" /> Default
            </label>
          </div>
        </div>
        <div class="flex gap-2 mt-4">
          <button @click="saveAgent" :disabled="saving"
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
