<script setup lang="ts">
import type { Agent, ConfigResponse } from '~/types/api'

const { confirm } = useConfirm()

const { data: agents, refresh } = await useFetch<Agent[]>('/api/agents')
const { data: configData } = await useFetch<ConfigResponse>('/api/config')

const editing = ref<any>(null)
const creating = ref(false)
const workspaceTab = ref('AGENT.md')
const workspaceContent = ref('')
const form = ref({ name: '', modelProvider: '', modelId: '', enabled: true, thinkingMode: '' })
const agentTools = ref<any[]>([])
const agentSkills = ref<any[]>([])
const queueMode = ref('queue')
const execBypassAllowlist = ref(false)
const execAllowGlobalPaths = ref(false)
const saving = ref(false)

// Bulk-delete selection state for the Custom Agents list. When selectMode is on,
// row clicks toggle selection instead of opening the edit form, and the header
// shows Cancel + "Delete N" instead of "New Agent" + "Delete".
const selectMode = ref(false)
const selectedIds = ref<Set<number>>(new Set())
const deletingBulk = ref(false)

// --- System prompt breakdown dialog state ---
// Scoped to a single agent: opened from a per-row button on the agent list, so
// there's no picker — the agent is known at open-time. Closing does not reset
// `promptBreakdownAgent` so re-opening the same agent's dialog feels instant.
interface PromptBreakdownEntry { name: string; chars: number; tokens: number }
interface PromptBreakdown {
  totalChars: number
  totalTokenEstimate: number
  cacheBoundaryMarker: string
  cacheablePrefixChars: number
  variableSuffixChars: number
  sections: PromptBreakdownEntry[]
  skills: PromptBreakdownEntry[]
  tools: PromptBreakdownEntry[]
}
const promptBreakdownOpen = ref(false)
const promptBreakdownAgent = ref<any>(null)
const promptBreakdownData = ref<PromptBreakdown | null>(null)
const promptBreakdownLoading = ref(false)
const promptBreakdownError = ref('')

async function openPromptBreakdown(agent: any) {
  promptBreakdownAgent.value = agent
  promptBreakdownOpen.value = true
  promptBreakdownData.value = null
  promptBreakdownError.value = ''
  promptBreakdownLoading.value = true
  try {
    promptBreakdownData.value = await $fetch<PromptBreakdown>(`/api/agents/${agent.id}/prompt-breakdown`)
  } catch (e: any) {
    promptBreakdownError.value = e?.message ?? 'Failed to load prompt breakdown'
  } finally {
    promptBreakdownLoading.value = false
  }
}

function closePromptBreakdown() {
  promptBreakdownOpen.value = false
}

function copyPromptBreakdownJson() {
  if (!promptBreakdownData.value) return
  navigator.clipboard.writeText(JSON.stringify(promptBreakdownData.value, null, 2))
}

function formatChars(n: number): string {
  return n.toLocaleString()
}

function formatTokens(n: number): string {
  return n.toLocaleString()
}

function percentOfTotal(chars: number, total: number): string {
  if (total === 0) return '0%'
  return ((chars / total) * 100).toFixed(1) + '%'
}

// The main agent is a structural singleton (seeded on first boot, cannot be
// renamed or deleted, always enabled). Splitting it out of the list keeps the
// Custom Agents section focused on user-created agents and lets the New Agent
// button sit where it's actually applicable.
const mainAgent = computed(() => (agents.value ?? []).find((a: any) => a.isMain))
const customAgents = computed(() => (agents.value ?? []).filter((a: any) => !a.isMain))

// Extract configured providers (those with non-empty API keys)
const { providers } = useProviders(configData)

// Models for the currently selected provider
const availableModels = computed(() => {
  const provider = providers.value.find(p => p.name === form.value.modelProvider)
  return provider?.models ?? []
})

// Whether the selected model supports thinking/reasoning
const selectedModelSupportsThinking = computed(() => {
  const model = availableModels.value.find((m: any) => m.id === form.value.modelId)
  return model?.supportsThinking === true
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
  form.value = { name: '', modelProvider: defaultProvider, modelId: defaultModel, enabled: true, thinkingMode: '' }
  creating.value = true
  editing.value = null
}

function editAgent(agent: any) {
  form.value = {
    name: agent.name,
    modelProvider: agent.modelProvider,
    modelId: agent.modelId,
    enabled: agent.enabled,
    thinkingMode: agent.thinkingMode || ''
  }
  editing.value = agent
  creating.value = false
  loadWorkspaceFile(agent.id, 'AGENT.md')
  loadAgentTools(agent.id)
  loadAgentSkills(agent.id)
  loadQueueMode(agent.name)
  loadExecConfig(agent.name)
}

async function loadAgentTools(agentId: number) {
  try {
    agentTools.value = await $fetch<any[]>(`/api/agents/${agentId}/tools`)
  } catch {
    agentTools.value = []
  }
}

async function toggleTool(toolName: string, enabled: boolean) {
  if (!editing.value) return
  try {
    await $fetch(`/api/agents/${editing.value.id}/tools/${toolName}`, {
      method: 'PUT',
      body: { enabled }
    })
  } catch (e) {
    console.error('Failed to toggle tool:', e)
  }
}

async function loadAgentSkills(agentId: number) {
  try {
    agentSkills.value = await $fetch<any[]>(`/api/agents/${agentId}/skills`)
  } catch {
    agentSkills.value = []
  }
}

async function loadQueueMode(agentName: string) {
  try {
    const config = await $fetch<any>(`/api/config/agent.${agentName}.queue.mode`)
    queueMode.value = config.value || 'queue'
  } catch {
    queueMode.value = 'queue'
  }
}

async function saveQueueMode() {
  if (!editing.value) return
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key: `agent.${editing.value.name}.queue.mode`, value: queueMode.value }
    })
  } catch (e) {
    console.error('Failed to save queue mode:', e)
  }
}

async function loadExecConfig(agentName: string) {
  try {
    const bypass = await $fetch<any>(`/api/config/agent.${agentName}.shell.bypassAllowlist`).catch(() => null)
    execBypassAllowlist.value = bypass?.value === 'true'
    const globalPaths = await $fetch<any>(`/api/config/agent.${agentName}.shell.allowGlobalPaths`).catch(() => null)
    execAllowGlobalPaths.value = globalPaths?.value === 'true'
  } catch {
    execBypassAllowlist.value = false
    execAllowGlobalPaths.value = false
  }
}

async function toggleExecConfig(key: string, value: boolean) {
  if (!editing.value) return
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key: `agent.${editing.value.name}.shell.${key}`, value: String(value) }
    })
  } catch (e) {
    console.error('Failed to save exec config:', e)
  }
}

async function toggleSkill(skillName: string, enabled: boolean) {
  if (!editing.value) return
  try {
    await $fetch(`/api/agents/${editing.value.id}/skills/${skillName}`, {
      method: 'PUT',
      body: { enabled }
    })
    await loadAgentSkills(editing.value.id)
  } catch (e) {
    console.error('Failed to toggle skill:', e)
  }
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

// Clear thinking mode if selected model doesn't support it
watch(() => form.value.modelId, () => {
  if (!selectedModelSupportsThinking.value) {
    form.value.thinkingMode = ''
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

// Toggle a custom agent's enabled flag from the list view without opening the
// edit form. The PUT endpoint accepts partial updates, so we only send the
// enabled field — other fields fall through to their existing values.
async function toggleAgentEnabled(agent: any) {
  try {
    await $fetch(`/api/agents/${agent.id}`, {
      method: 'PUT',
      body: { enabled: !agent.enabled },
    })
    refresh()
  } catch (e) {
    console.error('Failed to toggle agent enabled:', e)
  }
}

function enterSelectMode() {
  selectMode.value = true
  selectedIds.value = new Set()
}

function exitSelectMode() {
  selectMode.value = false
  selectedIds.value = new Set()
}

function toggleSelection(id: number) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  selectedIds.value = next
}

async function deleteSelected() {
  if (!selectedIds.value.size) return
  const count = selectedIds.value.size
  const ok = await confirm({
    title: 'Delete custom agents',
    message: `Delete ${count} custom agent${count === 1 ? '' : 's'}? This cannot be undone.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  deletingBulk.value = true
  try {
    // Sequential deletes keep per-row error handling simple and avoid thundering
    // the API with parallel DELETEs. The selection is small (user-curated).
    for (const id of selectedIds.value) {
      await $fetch(`/api/agents/${id}`, { method: 'DELETE' })
    }
    exitSelectMode()
    refresh()
  } catch (e) {
    console.error('Failed to delete selected agents:', e)
  } finally {
    deletingBulk.value = false
  }
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
  agentTools.value = []
  agentSkills.value = []
  queueMode.value = 'queue'
}

const workspaceFiles = ['AGENT.md', 'IDENTITY.md', 'USER.md']
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-white mb-6">Agents</h1>

    <div v-if="!providers.length && !editing && !creating"
         class="bg-neutral-900 border border-neutral-800 p-4 mb-4 text-sm text-neutral-400">
      No LLM providers configured. Go to <NuxtLink to="/settings" class="text-white underline">Settings</NuxtLink> and add an API key first.
    </div>

    <!-- Main Agent section -->
    <div v-if="!editing && !creating" class="mb-6 space-y-2">
      <h2 class="text-sm font-medium text-neutral-400">Main Agent</h2>
      <p class="text-xs text-neutral-600">The built-in singleton agent. Always enabled, cannot be renamed or deleted. Handles admin chat and acts as the fallback route for channels without an explicit binding.</p>
      <div class="bg-neutral-900 border border-neutral-800">
        <div v-if="mainAgent"
             @click="editAgent(mainAgent)"
             class="px-4 py-3 flex items-center justify-between hover:bg-neutral-800/50 cursor-pointer transition-colors">
          <div>
            <span class="text-sm text-white">{{ mainAgent.name }}</span>
            <div class="text-xs text-neutral-500 mt-0.5">{{ mainAgent.modelProvider }} / {{ mainAgent.modelId }}</div>
          </div>
          <div class="flex items-center gap-3 shrink-0">
            <span v-if="!mainAgent.providerConfigured" class="text-xs font-mono text-amber-400">provider not configured</span>
            <button @click.stop="openPromptBreakdown(mainAgent)"
                    class="px-2 py-0.5 text-[10px] text-neutral-400 border border-neutral-700 hover:text-white hover:border-neutral-500 transition-colors"
                    title="Inspect the system prompt this agent receives — per-section char + token breakdown">
              Inspect prompt
            </button>
          </div>
        </div>
      </div>
    </div>

    <!-- Custom Agents section -->
    <div v-if="!editing && !creating" class="mb-6 space-y-2">
      <div class="flex items-center justify-between">
        <h2 class="text-sm font-medium text-neutral-400">Custom Agents</h2>
        <div class="flex items-center gap-2">
          <button @click="newAgent" :disabled="!providers.length || selectMode"
                  class="px-3 py-1.5 bg-emerald-600 text-white text-xs font-medium hover:bg-emerald-500 disabled:opacity-40 transition-colors">
            New Agent
          </button>
          <!-- Delete affordance: outside select mode, entering it requires at least one custom
               agent to exist. Inside select mode, the same button becomes "Delete N" and
               confirms the bulk delete. A Cancel button sits alongside to exit select mode. -->
          <template v-if="!selectMode">
            <button @click="enterSelectMode" :disabled="!customAgents.length"
                    class="px-3 py-1.5 bg-neutral-800 text-neutral-300 text-xs font-medium hover:bg-red-900/40 hover:text-red-300 border border-neutral-700 disabled:opacity-40 disabled:hover:bg-neutral-800 disabled:hover:text-neutral-300 transition-colors">
              Delete
            </button>
          </template>
          <template v-else>
            <button @click="exitSelectMode"
                    class="px-3 py-1.5 bg-neutral-800 text-neutral-300 text-xs font-medium hover:bg-neutral-700 border border-neutral-700 transition-colors">
              Cancel
            </button>
            <button @click="deleteSelected" :disabled="!selectedIds.size || deletingBulk"
                    class="px-3 py-1.5 bg-red-700 text-white text-xs font-medium hover:bg-red-600 disabled:opacity-40 transition-colors">
              Delete {{ selectedIds.size || '' }}
            </button>
          </template>
        </div>
      </div>
      <p class="text-xs text-neutral-600">Additional agents you create for specific channels, peers, or workflows.</p>
      <div class="bg-neutral-900 border border-neutral-800">
        <div v-for="agent in customAgents" :key="agent.id"
             @click="selectMode ? toggleSelection(agent.id) : editAgent(agent)"
             class="px-4 py-3 border-b border-neutral-800/50 last:border-b-0 flex items-center justify-between hover:bg-neutral-800/50 cursor-pointer transition-colors">
          <div class="flex items-center gap-3 min-w-0">
            <input v-if="selectMode" type="checkbox"
                   :checked="selectedIds.has(agent.id)"
                   @click.stop="toggleSelection(agent.id)"
                   class="accent-red-500 shrink-0" />
            <div class="min-w-0">
              <span class="text-sm text-white">{{ agent.name }}</span>
              <div class="text-xs text-neutral-500 mt-0.5">{{ agent.modelProvider }} / {{ agent.modelId }}</div>
            </div>
          </div>
          <div class="flex items-center gap-3 shrink-0">
            <span v-if="agent.enabled && !agent.providerConfigured"
                  class="text-[10px] font-mono text-amber-400 border border-amber-400/30 px-1">provider not configured</span>
            <!-- Inspect system prompt: per-agent diagnostic, hidden in select mode so the row's
                 click surface is unambiguous (select vs. inspect would be confusing). -->
            <button v-if="!selectMode" @click.stop="openPromptBreakdown(agent)"
                    class="px-2 py-0.5 text-[10px] text-neutral-400 border border-neutral-700 hover:text-white hover:border-neutral-500 transition-colors"
                    title="Inspect the system prompt this agent receives — per-section char + token breakdown">
              Inspect prompt
            </button>
            <!-- Enabled toggle (hidden in select mode to keep the row's action surface unambiguous) -->
            <button v-if="!selectMode" @click.stop="toggleAgentEnabled(agent)"
                    :class="agent.enabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-neutral-700 hover:bg-neutral-600'"
                    class="relative w-9 h-5 rounded-full transition-colors"
                    :title="agent.enabled ? 'Disable agent' : 'Enable agent'">
              <span :class="agent.enabled ? 'translate-x-4' : 'translate-x-0.5'"
                    class="block w-4 h-4 bg-white rounded-full transition-transform" />
            </button>
          </div>
        </div>
        <div v-if="!customAgents.length" class="px-4 py-8 text-center text-sm text-neutral-600">
          No custom agents yet. Click <span class="text-neutral-400">New Agent</span> to create one.
        </div>
      </div>
    </div>

    <!-- Edit / Create form -->
    <div v-if="editing || creating" class="space-y-4">
      <button @click="cancel" class="text-xs text-neutral-500 hover:text-white transition-colors">&larr; Back to agents</button>
      <div class="bg-neutral-900 border border-neutral-800 p-4">
        <h2 class="text-sm font-medium text-white mb-4">{{ creating ? 'New Agent' : 'Edit Agent' }}</h2>
        <div class="grid grid-cols-2 gap-3">
          <div>
            <label class="block text-xs text-neutral-500 mb-1">
              Name
              <span v-if="editing?.isMain" class="ml-1 text-neutral-600">(locked)</span>
            </label>
            <input v-model="form.name" :disabled="editing?.isMain"
                   class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600 disabled:opacity-50 disabled:cursor-not-allowed" />
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
          <div>
            <label class="block text-xs text-neutral-500 mb-1" :class="{ 'opacity-40': !selectedModelSupportsThinking }">Thinking Mode</label>
            <select v-model="form.thinkingMode" :disabled="!selectedModelSupportsThinking"
                    class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600
                           disabled:opacity-40 disabled:cursor-not-allowed">
              <option value="">Off</option>
              <option value="low">Low</option>
              <option value="medium">Medium</option>
              <option value="high">High</option>
            </select>
          </div>
        </div>
        <div class="flex mt-4">
          <button @click="saveAgent" :disabled="saving || !form.name || !form.modelProvider || !form.modelId"
                  class="p-1.5 text-emerald-400 hover:text-emerald-300 disabled:opacity-40 disabled:hover:text-emerald-400 transition-colors"
                  :title="saving ? 'Saving...' : 'Save'">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3 5.25A2.25 2.25 0 015.25 3h10.379a2.25 2.25 0 011.59.659l2.122 2.121a2.25 2.25 0 01.659 1.591V18.75A2.25 2.25 0 0118.75 21H5.25A2.25 2.25 0 013 18.75V5.25z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M7.5 3v4.5h7.5V3M7.5 21v-6.75h9V21" /></svg>
          </button>
        </div>
      </div>

      <!-- Queue Mode -->
      <div v-if="editing" class="bg-neutral-900 border border-neutral-800 p-4">
        <div class="flex items-center justify-between">
          <div>
            <span class="text-sm font-medium text-white">Queue Mode</span>
            <div class="text-xs text-neutral-500 mt-0.5">How to handle messages when the agent is busy</div>
          </div>
          <div class="flex items-center gap-2">
            <select v-model="queueMode" @change="saveQueueMode"
                    class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1 focus:outline-none focus:border-neutral-600">
              <option value="queue">Queue (FIFO)</option>
              <option value="collect">Collect (batch)</option>
              <option value="interrupt">Interrupt</option>
            </select>
          </div>
        </div>
      </div>

      <!-- Tools -->
      <div v-if="editing" class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800">
          <span class="text-sm font-medium text-white">Tools</span>
          <span class="ml-2 text-xs text-neutral-500">{{ agentTools.filter(t => t.enabled).length }}/{{ agentTools.length }} enabled</span>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <div v-for="tool in agentTools" :key="tool.name"
               class="px-4 py-2 flex items-center justify-between">
            <div>
              <span class="text-sm text-white font-mono">{{ tool.name }}</span>
              <div class="text-xs text-neutral-500 mt-0.5">{{ tool.description }}</div>
            </div>
            <label class="flex items-center gap-1.5 shrink-0">
              <input type="checkbox" :checked="tool.enabled"
                     @change="(e: Event) => { tool.enabled = (e.target as HTMLInputElement).checked; toggleTool(tool.name, tool.enabled) }"
                     class="accent-white" />
            </label>
          </div>
        </div>
        <div v-if="!agentTools.length" class="px-4 py-4 text-xs text-neutral-600 text-center">
          No tools registered
        </div>
      </div>

      <!-- Exec privileges (main agent only) -->
      <div v-if="editing && editing.isMain" class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800">
          <span class="text-sm font-medium text-white">Shell Exec Privileges</span>
          <span class="ml-2 text-[10px] text-amber-400">main agent only</span>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <div class="px-4 py-2.5 flex items-center justify-between">
            <div>
              <span class="text-sm text-white">Bypass allowlist</span>
              <div class="text-xs text-neutral-500 mt-0.5">Allow any command without allowlist validation</div>
            </div>
            <button @click="execBypassAllowlist = !execBypassAllowlist; toggleExecConfig('bypassAllowlist', execBypassAllowlist)"
                    :class="execBypassAllowlist ? 'bg-amber-600 hover:bg-amber-500' : 'bg-neutral-700 hover:bg-neutral-600'"
                    class="relative w-9 h-5 rounded-full transition-colors shrink-0">
              <span :class="execBypassAllowlist ? 'translate-x-4' : 'translate-x-0.5'"
                    class="block w-4 h-4 bg-white rounded-full transition-transform" />
            </button>
          </div>
          <div class="px-4 py-2.5 flex items-center justify-between">
            <div>
              <span class="text-sm text-white">Allow global paths</span>
              <div class="text-xs text-neutral-500 mt-0.5">Execute commands outside the agent workspace directory</div>
            </div>
            <button @click="execAllowGlobalPaths = !execAllowGlobalPaths; toggleExecConfig('allowGlobalPaths', execAllowGlobalPaths)"
                    :class="execAllowGlobalPaths ? 'bg-amber-600 hover:bg-amber-500' : 'bg-neutral-700 hover:bg-neutral-600'"
                    class="relative w-9 h-5 rounded-full transition-colors shrink-0">
              <span :class="execAllowGlobalPaths ? 'translate-x-4' : 'translate-x-0.5'"
                    class="block w-4 h-4 bg-white rounded-full transition-transform" />
            </button>
          </div>
        </div>
      </div>

      <!-- Skills -->
      <div v-if="editing" class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800">
          <span class="text-sm font-medium text-white">Skills</span>
          <span class="ml-2 text-xs text-neutral-500">{{ agentSkills.filter(s => s.enabled).length }}/{{ agentSkills.length }} enabled</span>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <div v-for="skill in agentSkills" :key="skill.name"
               class="px-4 py-2 flex items-center justify-between">
            <div>
              <span class="text-sm text-white font-mono">{{ skill.name }}</span>
              <span v-if="skill.isGlobal" class="ml-2 text-[10px] text-green-400 border border-green-400/30 px-1">global</span>
              <span v-else class="ml-2 text-[10px] text-neutral-500 border border-neutral-700 px-1">workspace</span>
              <div class="text-xs text-neutral-500 mt-0.5">{{ skill.description || '' }}</div>
            </div>
            <label class="flex items-center shrink-0">
              <input type="checkbox" :checked="skill.enabled"
                     @change="(e: Event) => { skill.enabled = (e.target as HTMLInputElement).checked; toggleSkill(skill.name, skill.enabled) }"
                     class="accent-white" />
            </label>
          </div>
        </div>
        <div v-if="!agentSkills.length" class="px-4 py-4 text-xs text-neutral-600 text-center">
          No skills available
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
        <div class="px-4 py-2 border-t border-neutral-800 flex">
          <button @click="saveWorkspaceFile"
                  class="p-1.5 text-emerald-400 hover:text-emerald-300 transition-colors"
                  title="Save file">
            <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3 5.25A2.25 2.25 0 015.25 3h10.379a2.25 2.25 0 011.59.659l2.122 2.121a2.25 2.25 0 01.659 1.591V18.75A2.25 2.25 0 0118.75 21H5.25A2.25 2.25 0 013 18.75V5.25z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M7.5 3v4.5h7.5V3M7.5 21v-6.75h9V21" /></svg>
          </button>
        </div>
      </div>
    </div>

    <!-- System prompt breakdown dialog -->
    <div v-if="promptBreakdownOpen"
         class="fixed inset-0 z-50 flex items-start justify-center bg-black/70 p-6 overflow-y-auto"
         @click.self="closePromptBreakdown()">
      <div class="bg-neutral-900 border border-neutral-800 w-full max-w-4xl my-6 text-neutral-200">
        <div class="px-4 py-3 border-b border-neutral-800 flex items-center justify-between gap-3">
          <div class="min-w-0">
            <h3 class="text-sm font-medium text-white truncate">System prompt — {{ promptBreakdownAgent?.name }}</h3>
            <p class="text-[10px] text-neutral-500 truncate">{{ promptBreakdownAgent?.modelProvider }} / {{ promptBreakdownAgent?.modelId }}</p>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <button v-if="promptBreakdownData"
                    @click="copyPromptBreakdownJson()"
                    class="px-2 py-1 text-[10px] text-neutral-400 border border-neutral-700 hover:text-white hover:border-neutral-500"
                    title="Copy the raw breakdown JSON (useful for bug reports)">
              Copy JSON
            </button>
            <button @click="closePromptBreakdown()" class="p-1 text-neutral-500 hover:text-white" title="Close">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M6 18L18 6M6 6l12 12" /></svg>
            </button>
          </div>
        </div>

        <div v-if="promptBreakdownLoading" class="px-4 py-6 text-sm text-neutral-500">
          Loading…
        </div>

        <div v-else-if="promptBreakdownError" class="px-4 py-6 text-sm text-red-400">
          {{ promptBreakdownError }}
        </div>

        <div v-else-if="promptBreakdownData" class="px-4 py-4 space-y-5">
          <!-- Totals strip -->
          <div class="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
            <div class="bg-neutral-800/60 border border-neutral-800 px-3 py-2">
              <div class="text-[10px] text-neutral-500 uppercase tracking-wide">Total chars</div>
              <div class="text-sm font-mono text-white">{{ formatChars(promptBreakdownData.totalChars) }}</div>
              <div class="text-[10px] text-neutral-500">prompt + tool schemas</div>
            </div>
            <div class="bg-neutral-800/60 border border-neutral-800 px-3 py-2">
              <div class="text-[10px] text-neutral-500 uppercase tracking-wide">≈ tokens</div>
              <div class="text-sm font-mono text-amber-300">{{ formatTokens(promptBreakdownData.totalTokenEstimate) }}</div>
              <div class="text-[10px] text-neutral-500">chars/4 heuristic</div>
            </div>
            <div class="bg-neutral-800/60 border border-neutral-800 px-3 py-2"
                 title="Bytes above the cache boundary marker — hash-stable, reused from the provider cache on repeat turns">
              <div class="text-[10px] text-neutral-500 uppercase tracking-wide">Cacheable prefix</div>
              <div class="text-sm font-mono text-emerald-400">{{ formatChars(promptBreakdownData.cacheablePrefixChars) }}</div>
              <div class="text-[10px] text-neutral-500">≈ {{ formatTokens(Math.round(promptBreakdownData.cacheablePrefixChars / 4)) }} tokens</div>
            </div>
            <div class="bg-neutral-800/60 border border-neutral-800 px-3 py-2"
                 title="Bytes after the cache boundary — per-turn-variable content (memories) that never hits the cache">
              <div class="text-[10px] text-neutral-500 uppercase tracking-wide">Variable suffix</div>
              <div class="text-sm font-mono text-rose-400">{{ formatChars(promptBreakdownData.variableSuffixChars) }}</div>
              <div class="text-[10px] text-neutral-500">≈ {{ formatTokens(Math.round(promptBreakdownData.variableSuffixChars / 4)) }} tokens</div>
            </div>
          </div>

          <!-- Sections table -->
          <div>
            <h4 class="text-[11px] text-neutral-500 uppercase tracking-wide mb-1.5">Prompt sections</h4>
            <table class="w-full text-xs font-mono">
              <thead class="text-[10px] text-neutral-500 border-b border-neutral-800">
                <tr>
                  <th class="text-left py-1 pr-2">Section</th>
                  <th class="text-right py-1 px-2">Chars</th>
                  <th class="text-right py-1 px-2">≈ Tokens</th>
                  <th class="text-right py-1 pl-2">% of prompt</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in promptBreakdownData.sections" :key="'section-' + s.name"
                    class="border-b border-neutral-900/50">
                  <td class="py-1 pr-2 text-neutral-300">{{ s.name }}</td>
                  <td class="py-1 px-2 text-right text-neutral-400">{{ formatChars(s.chars) }}</td>
                  <td class="py-1 px-2 text-right text-amber-300/80">{{ formatTokens(s.tokens) }}</td>
                  <td class="py-1 pl-2 text-right text-neutral-500">{{ percentOfTotal(s.chars, promptBreakdownData.cacheablePrefixChars + promptBreakdownData.variableSuffixChars) }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Skills table -->
          <div v-if="promptBreakdownData.skills.length > 0">
            <h4 class="text-[11px] text-neutral-500 uppercase tracking-wide mb-1.5">Skills included ({{ promptBreakdownData.skills.length }})</h4>
            <table class="w-full text-xs font-mono">
              <thead class="text-[10px] text-neutral-500 border-b border-neutral-800">
                <tr>
                  <th class="text-left py-1 pr-2">Skill</th>
                  <th class="text-right py-1 px-2">Chars</th>
                  <th class="text-right py-1 pl-2">≈ Tokens</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="s in promptBreakdownData.skills" :key="'skill-' + s.name"
                    class="border-b border-neutral-900/50">
                  <td class="py-1 pr-2 text-neutral-300">{{ s.name }}</td>
                  <td class="py-1 px-2 text-right text-neutral-400">{{ formatChars(s.chars) }}</td>
                  <td class="py-1 pl-2 text-right text-amber-300/80">{{ formatTokens(s.tokens) }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Tools table -->
          <div v-if="promptBreakdownData.tools.length > 0">
            <h4 class="text-[11px] text-neutral-500 uppercase tracking-wide mb-1.5">Tool schemas ({{ promptBreakdownData.tools.length }})</h4>
            <p class="text-[10px] text-neutral-600 mb-1">Sent separately as the <code class="text-neutral-500">tools</code> array, not part of the prompt string, but counted as input tokens by every provider.</p>
            <table class="w-full text-xs font-mono">
              <thead class="text-[10px] text-neutral-500 border-b border-neutral-800">
                <tr>
                  <th class="text-left py-1 pr-2">Tool</th>
                  <th class="text-right py-1 px-2">Schema chars</th>
                  <th class="text-right py-1 pl-2">≈ Tokens</th>
                </tr>
              </thead>
              <tbody>
                <tr v-for="t in promptBreakdownData.tools" :key="'tool-' + t.name"
                    class="border-b border-neutral-900/50">
                  <td class="py-1 pr-2 text-neutral-300">{{ t.name }}</td>
                  <td class="py-1 px-2 text-right text-neutral-400">{{ formatChars(t.chars) }}</td>
                  <td class="py-1 pl-2 text-right text-amber-300/80">{{ formatTokens(t.tokens) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
