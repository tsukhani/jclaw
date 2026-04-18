<script setup lang="ts">
import type {
  Agent,
  AgentSkill,
  AgentTool,
  ConfigResponse,
  ConfigValueResponse,
  EffectiveAllowlist,
  PromptBreakdown,
  WorkspaceFileContent,
} from '~/types/api'
import { effectiveThinkingLevels, type ProviderModel } from '~/composables/useProviders'

const { confirm } = useConfirm()

// Parallel fetch — avoids sequential waterfall
const [{ data: agents, refresh }, { data: configData }] = await Promise.all([
  useFetch<Agent[]>('/api/agents'),
  useFetch<ConfigResponse>('/api/config'),
])

const editing = ref<Agent | null>(null)
const creating = ref(false)
const workspaceTab = ref('AGENT.md')
const workspaceContent = ref('')
const form = ref({ name: '', modelProvider: '', modelId: '', enabled: true, thinkingMode: '' })
const agentTools = ref<AgentTool[]>([])
const agentSkills = ref<AgentSkill[]>([])
// Effective shell allowlist for the current agent: global entries + per-skill
// contributions. Derived server-side so the UI doesn't have to re-compute the
// join. Populated on agent edit and refreshed whenever skill enable/disable or
// install actions happen — i.e., any time the union could change.
const effectiveAllowlist = ref<EffectiveAllowlist | null>(null)
const allowlistExpanded = ref(false)

// Group agentTools by category, in the canonical order from useToolMeta.
// Each entry is { category, tools[] } — empty categories are omitted.
const { TOOL_META, getToolMeta, getPillClass } = useToolMeta()
const toolsByCategory = computed(() => {
  const categories = ['System', 'Files', 'Web', 'Utilities'] as const
  const orderedNames = [
    'exec',
    'filesystem', 'documents',
    'web_fetch', 'web_search', 'browser',
    'datetime', 'checklist', 'task_manager',
  ]
  // Build a lookup from tool name → its position in the canonical order
  const posOf = (name: string) => {
    const i = orderedNames.indexOf(name)
    return i === -1 ? 999 : i
  }
  return categories
    .map(category => ({
      category,
      tools: agentTools.value
        .filter(t => !TOOL_META.value[t.name]?.system)
        .filter(t => (TOOL_META.value[t.name]?.category ?? 'Utilities') === category)
        .sort((a, b) => posOf(a.name) - posOf(b.name)),
    }))
    .filter(g => g.tools.length > 0)
})

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

// A11y: stable ids for label/control association in the edit form
const agentNameId = useId()
const agentProviderId = useId()
const agentModelId = useId()
const agentThinkingId = useId()
const agentQueueModeId = useId()
const agentWorkspaceTextareaId = useId()

// --- System prompt breakdown dialog state ---
// Scoped to a single agent: opened from a per-row button on the agent list, so
// there's no picker — the agent is known at open-time. Closing does not reset
// `promptBreakdownAgent` so re-opening the same agent's dialog feels instant.
const promptBreakdownOpen = ref(false)
const promptBreakdownAgent = ref<Agent | null>(null)
const promptBreakdownData = ref<PromptBreakdown | null>(null)
const promptBreakdownLoading = ref(false)
const promptBreakdownError = ref('')

async function openPromptBreakdown(agent: Agent) {
  promptBreakdownAgent.value = agent
  promptBreakdownOpen.value = true
  promptBreakdownData.value = null
  promptBreakdownError.value = ''
  promptBreakdownLoading.value = true
  try {
    promptBreakdownData.value = await $fetch<PromptBreakdown>(`/api/agents/${agent.id}/prompt-breakdown`)
  }
  catch (e: unknown) {
    promptBreakdownError.value = e instanceof Error ? e.message : 'Failed to load prompt breakdown'
  }
  finally {
    promptBreakdownLoading.value = false
  }
}

function closePromptBreakdown() {
  promptBreakdownOpen.value = false
}

// Global Escape handler so the modal dismisses via keyboard (the overlay is
// click-to-dismiss, so this keeps keyboard parity for a11y).
function handlePromptBreakdownEscape(e: KeyboardEvent) {
  if (promptBreakdownOpen.value && e.key === 'Escape') {
    e.preventDefault()
    closePromptBreakdown()
  }
}
onMounted(() => document.addEventListener('keydown', handlePromptBreakdownEscape))
onBeforeUnmount(() => document.removeEventListener('keydown', handlePromptBreakdownEscape))

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
const mainAgent = computed(() => (agents.value ?? []).find(a => a.isMain))
const customAgents = computed(() => (agents.value ?? []).filter(a => !a.isMain))

// Extract configured providers (those with non-empty API keys)
const configDataRef = computed(() => configData.value ?? null)
const { providers } = useProviders(configDataRef)

// Models for the currently selected provider
const availableModels = computed(() => {
  const provider = providers.value.find(p => p.name === form.value.modelProvider)
  return provider?.models ?? []
})

// The currently selected model's full metadata — drives the thinking-level dropdown.
const selectedModel = computed<ProviderModel | null>(() =>
  availableModels.value.find(m => m.id === form.value.modelId) ?? null,
)

// Thinking levels the selected provider+model pair accepts. Empty when the
// model doesn't support reasoning — the UI hides the selector in that case.
const thinkingLevels = computed<string[]>(() => effectiveThinkingLevels(selectedModel.value))

// Whether the selected provider is configured and the selected model is available.
// Kept with `_` prefix so the unused-vars rule is satisfied while the logic
// remains available for when the UI re-surfaces provider-mismatch warnings.
const _providerValid = computed(() => {
  const provider = providers.value.find(p => p.name === form.value.modelProvider)
  if (!provider) return false
  return !form.value.modelId || provider.models.some(m => m.id === form.value.modelId)
})

// Auto-select first provider when creating
function newAgent() {
  const defaultProvider = providers.value[0]?.name ?? ''
  const defaultModel = providers.value[0]?.models?.[0]?.id ?? ''
  form.value = { name: '', modelProvider: defaultProvider, modelId: defaultModel, enabled: true, thinkingMode: '' }
  creating.value = true
  editing.value = null
}

function editAgent(agent: Agent) {
  form.value = {
    name: agent.name,
    modelProvider: agent.modelProvider,
    modelId: agent.modelId,
    enabled: agent.enabled,
    thinkingMode: agent.thinkingMode ?? '',
  }
  editing.value = agent
  creating.value = false
  loadWorkspaceFile(agent.id, 'AGENT.md')
  loadAgentTools(agent.id)
  loadAgentSkills(agent.id)
  loadEffectiveAllowlist(agent.id)
  loadQueueMode(agent.name)
  loadExecConfig(agent.name)
}

// When the selected model changes, drop a thinking mode the new model doesn't
// advertise. Prevents submitting a stale level (e.g. "xhigh" after swapping
// from GPT-5 to Kimi) that would be silently normalized to null server-side.
watch(() => [form.value.modelProvider, form.value.modelId], () => {
  if (form.value.thinkingMode && !thinkingLevels.value.includes(form.value.thinkingMode)) {
    form.value.thinkingMode = ''
  }
})

async function loadAgentTools(agentId: number) {
  try {
    agentTools.value = await $fetch<AgentTool[]>(`/api/agents/${agentId}/tools`)
  }
  catch {
    agentTools.value = []
  }
}

async function toggleTool(toolName: string, enabled: boolean) {
  if (!editing.value) return
  try {
    await $fetch(`/api/agents/${editing.value.id}/tools/${toolName}`, {
      method: 'PUT',
      body: { enabled },
    })
  }
  catch (e) {
    console.error('Failed to toggle tool:', e)
  }
}

const toggleableAgentTools = computed(() =>
  agentTools.value.filter(t => !TOOL_META.value[t.name]?.system),
)

const allAgentToolsEnabled = computed(() =>
  toggleableAgentTools.value.length > 0 && toggleableAgentTools.value.every(t => t.enabled),
)

async function toggleAllAgentTools() {
  const next = !allAgentToolsEnabled.value
  toggleableAgentTools.value.forEach((t) => {
    t.enabled = next
  })
  await Promise.all(toggleableAgentTools.value.map(t => toggleTool(t.name, next)))
}

// Returns the tool names that a skill depends on but are currently disabled for this agent.
// Only flags tools that are registered (present in agentTools) — unknown tools are ignored.
function skillDisabledTools(skill: AgentSkill): string[] {
  if (!skill.tools?.length) return []
  const toolMap = new Map(agentTools.value.map(t => [t.name, t.enabled]))
  return skill.tools.filter(name => toolMap.has(name) && !toolMap.get(name))
}

async function loadAgentSkills(agentId: number) {
  try {
    agentSkills.value = await $fetch<AgentSkill[]>(`/api/agents/${agentId}/skills`)
  }
  catch {
    agentSkills.value = []
  }
}

async function loadEffectiveAllowlist(agentId: number) {
  try {
    effectiveAllowlist.value = await $fetch<EffectiveAllowlist>(`/api/agents/${agentId}/shell/effective-allowlist`)
  }
  catch {
    effectiveAllowlist.value = null
  }
}

async function loadQueueMode(agentName: string) {
  try {
    const config = await $fetch<ConfigValueResponse>(`/api/config/agent.${agentName}.queue.mode`)
    queueMode.value = config.value || 'queue'
  }
  catch {
    queueMode.value = 'queue'
  }
}

async function saveQueueMode() {
  if (!editing.value) return
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key: `agent.${editing.value.name}.queue.mode`, value: queueMode.value },
    })
  }
  catch (e) {
    console.error('Failed to save queue mode:', e)
  }
}

async function loadExecConfig(agentName: string) {
  try {
    const bypass = await $fetch<ConfigValueResponse>(`/api/config/agent.${agentName}.shell.bypassAllowlist`).catch(() => null)
    execBypassAllowlist.value = bypass?.value === 'true'
    const globalPaths = await $fetch<ConfigValueResponse>(`/api/config/agent.${agentName}.shell.allowGlobalPaths`).catch(() => null)
    execAllowGlobalPaths.value = globalPaths?.value === 'true'
  }
  catch {
    execBypassAllowlist.value = false
    execAllowGlobalPaths.value = false
  }
}

async function toggleExecConfig(key: string, value: boolean) {
  if (!editing.value) return
  try {
    await $fetch('/api/config', {
      method: 'POST',
      body: { key: `agent.${editing.value.name}.shell.${key}`, value: String(value) },
    })
  }
  catch (e) {
    console.error('Failed to save exec config:', e)
  }
}

async function toggleSkill(skillName: string, enabled: boolean) {
  if (!editing.value) return
  try {
    await $fetch(`/api/agents/${editing.value.id}/skills/${skillName}`, {
      method: 'PUT',
      body: { enabled },
    })
    await loadAgentSkills(editing.value.id)
    // Skill enable/disable flips which commands count toward the effective
    // allowlist — refresh the table so the bySkill section matches the toggle.
    await loadEffectiveAllowlist(editing.value.id)
  }
  catch (e) {
    console.error('Failed to toggle skill:', e)
  }
}

// When provider changes, reset model to first available and disable if provider invalid
watch(() => form.value.modelProvider, (newProvider) => {
  const provider = providers.value.find(p => p.name === newProvider)
  const currentModelValid = provider?.models?.some(m => m.id === form.value.modelId)
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
    // Empty string means "reasoning off" — send null so the backend clears the
    // column. The model also collapses unknown levels to null defensively, but
    // normalizing on the way out keeps the wire payload honest.
    const payload = {
      ...form.value,
      thinkingMode: form.value.thinkingMode || null,
    }
    if (creating.value) {
      await $fetch('/api/agents', { method: 'POST', body: payload })
    }
    else if (editing.value) {
      await $fetch(`/api/agents/${editing.value.id}`, { method: 'PUT', body: payload })
    }
    editing.value = null
    creating.value = false
    refresh()
  }
  catch (e) {
    console.error('Failed to save agent:', e)
  }
  finally {
    saving.value = false
  }
}

// Toggle a custom agent's enabled flag from the list view without opening the
// edit form. The PUT endpoint accepts partial updates, so we only send the
// enabled field — other fields fall through to their existing values.
async function toggleAgentEnabled(agent: Agent) {
  try {
    await $fetch(`/api/agents/${agent.id}`, {
      method: 'PUT',
      body: { enabled: !agent.enabled },
    })
    refresh()
  }
  catch (e) {
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
  if (next.has(id)) next.delete(id)
  else next.add(id)
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
  }
  catch (e) {
    console.error('Failed to delete selected agents:', e)
  }
  finally {
    deletingBulk.value = false
  }
}

async function loadWorkspaceFile(agentId: number, filename: string) {
  workspaceTab.value = filename
  try {
    const data = await $fetch<WorkspaceFileContent>(`/api/agents/${agentId}/workspace/${filename}`)
    workspaceContent.value = data.content ?? ''
  }
  catch {
    workspaceContent.value = ''
  }
}

async function saveWorkspaceFile() {
  if (!editing.value) return
  await $fetch(`/api/agents/${editing.value.id}/workspace/${workspaceTab.value}`, {
    method: 'PUT',
    body: { content: workspaceContent.value },
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
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        Agents
      </h1>
      <div
        v-if="!editing && !creating"
        class="flex items-center gap-2"
      >
        <template v-if="!selectMode">
          <button
            :disabled="!providers.length"
            class="p-2 border border-input text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 hover:border-emerald-600/50 disabled:opacity-40 disabled:hover:text-fg-muted disabled:hover:border-input transition-colors"
            title="New Agent"
            @click="newAgent"
          >
            <svg
              class="w-4 h-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            ><path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="1.75"
              d="M12 4.5v15m7.5-7.5h-15"
            /></svg>
          </button>
          <button
            :disabled="!customAgents.length"
            class="p-2 border border-input text-fg-muted hover:text-red-400 hover:border-red-700/50 disabled:opacity-40 disabled:hover:text-fg-muted disabled:hover:border-input transition-colors"
            title="Delete an agent"
            @click="enterSelectMode"
          >
            <svg
              class="w-4 h-4"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            ><path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="1.75"
              d="M14.74 9l-.346 9m-4.788 0L9.26 9m9.968-3.21c.342.052.682.107 1.022.166m-1.022-.165L18.16 19.673a2.25 2.25 0 01-2.244 2.077H8.084a2.25 2.25 0 01-2.244-2.077L4.772 5.79m14.456 0a48.108 48.108 0 00-3.478-.397m-12 .562c.34-.059.68-.114 1.022-.165m0 0a48.11 48.11 0 013.478-.397m7.5 0v-.916c0-1.18-.91-2.164-2.09-2.201a51.964 51.964 0 00-3.32 0c-1.18.037-2.09 1.022-2.09 2.201v.916m7.5 0a48.667 48.667 0 00-7.5 0"
            /></svg>
          </button>
        </template>
        <template v-else>
          <button
            class="px-3 py-1.5 border border-input text-fg-muted text-xs hover:text-fg-strong hover:border-neutral-500 transition-colors"
            @click="exitSelectMode"
          >
            Cancel
          </button>
          <button
            :disabled="!selectedIds.size || deletingBulk"
            class="px-3 py-1.5 bg-red-700 text-white text-xs font-medium hover:bg-red-600 disabled:opacity-40 transition-colors"
            @click="deleteSelected"
          >
            Delete {{ selectedIds.size || '' }}
          </button>
        </template>
      </div>
    </div>

    <div
      v-if="!providers.length && !editing && !creating"
      class="bg-surface-elevated border border-border p-4 mb-4 text-sm text-fg-muted"
    >
      No LLM providers configured. Go to <NuxtLink
        to="/settings"
        class="text-fg-strong underline"
      >Settings</NuxtLink> and add an API key first.
    </div>

    <!-- Main Agent section -->
    <div
      v-if="!editing && !creating"
      class="mb-6 space-y-2"
    >
      <h2 class="text-sm font-medium text-fg-muted">
        Main Agent
      </h2>
      <p class="text-xs text-fg-muted">
        The built-in singleton agent. Always enabled, cannot be renamed or deleted. Handles admin chat and acts as the fallback route for channels without an explicit binding.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div
          v-if="mainAgent"
          role="button"
          tabindex="0"
          class="px-4 py-3 flex items-center justify-between hover:bg-muted cursor-pointer transition-colors"
          @click="editAgent(mainAgent)"
          @keydown.enter.prevent="editAgent(mainAgent)"
          @keydown.space.prevent="editAgent(mainAgent)"
        >
          <div>
            <span class="text-sm text-fg-strong">{{ mainAgent.name }}</span>
            <div class="text-xs text-neutral-500 mt-0.5">
              {{ mainAgent.modelProvider }} / {{ mainAgent.modelId }}
            </div>
            <button
              class="mt-2 px-2.5 py-1 text-[11px] font-medium text-emerald-700 dark:text-emerald-400 bg-emerald-500/10 border border-emerald-600/30 hover:bg-emerald-500/20 hover:text-emerald-600 dark:hover:text-emerald-300 hover:border-emerald-600 dark:hover:border-emerald-500/50 transition-colors"
              title="Inspect the system prompt this agent receives — per-section char + token breakdown"
              @click.stop="openPromptBreakdown(mainAgent)"
            >
              Inspect prompt
            </button>
          </div>
          <div class="flex items-center gap-3 shrink-0">
            <span
              v-if="!mainAgent.providerConfigured"
              class="text-xs font-mono text-amber-400"
            >provider not configured</span>
          </div>
        </div>
      </div>
    </div>

    <!-- Custom Agents section -->
    <div
      v-if="!editing && !creating"
      class="mb-6 space-y-2"
    >
      <h2 class="text-sm font-medium text-fg-muted">
        Custom Agents
      </h2>
      <p class="text-xs text-fg-muted">
        Additional agents you create for specific channels, peers, or workflows.
      </p>
      <div class="bg-surface-elevated border border-border">
        <div
          v-for="agent in customAgents"
          :key="agent.id"
          role="button"
          tabindex="0"
          class="px-4 py-3 border-b border-border last:border-b-0 flex items-center justify-between hover:bg-muted cursor-pointer transition-colors"
          @click="selectMode ? toggleSelection(agent.id) : editAgent(agent)"
          @keydown.enter.prevent="selectMode ? toggleSelection(agent.id) : editAgent(agent)"
          @keydown.space.prevent="selectMode ? toggleSelection(agent.id) : editAgent(agent)"
        >
          <div class="flex items-center gap-3 min-w-0">
            <input
              v-if="selectMode"
              type="checkbox"
              :checked="selectedIds.has(agent.id)"
              :aria-label="`Select ${agent.name}`"
              class="accent-red-500 shrink-0"
              @click.stop="toggleSelection(agent.id)"
            >
            <div class="min-w-0">
              <span class="text-sm text-fg-strong">{{ agent.name }}</span>
              <div class="text-xs text-neutral-500 mt-0.5">
                {{ agent.modelProvider }} / {{ agent.modelId }}
              </div>
              <!-- Inspect prompt: below model line, hidden in select mode -->
              <button
                v-if="!selectMode"
                class="mt-2 px-2.5 py-1 text-[11px] font-medium text-emerald-700 dark:text-emerald-400 bg-emerald-500/10 border border-emerald-600/30 hover:bg-emerald-500/20 hover:text-emerald-600 dark:hover:text-emerald-300 hover:border-emerald-600 dark:hover:border-emerald-500/50 transition-colors"
                title="Inspect the system prompt this agent receives — per-section char + token breakdown"
                @click.stop="openPromptBreakdown(agent)"
              >
                Inspect prompt
              </button>
            </div>
          </div>
          <div class="flex items-center gap-3 shrink-0">
            <span
              v-if="agent.enabled && !agent.providerConfigured"
              class="text-[10px] font-mono text-amber-400 border border-amber-400/30 px-1"
            >provider not configured</span>
            <!-- Enabled toggle (hidden in select mode to keep the row's action surface unambiguous) -->
            <button
              v-if="!selectMode"
              :class="agent.enabled ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-neutral-300 dark:hover:bg-neutral-600'"
              class="relative w-9 h-5 rounded-full transition-colors"
              :title="agent.enabled ? 'Disable agent' : 'Enable agent'"
              @click.stop="toggleAgentEnabled(agent)"
            >
              <span
                :class="agent.enabled ? 'translate-x-4' : 'translate-x-0.5'"
                class="block w-4 h-4 bg-white rounded-full transition-transform"
              />
            </button>
          </div>
        </div>
        <div
          v-if="!customAgents.length"
          class="px-4 py-8 text-center text-sm text-fg-muted"
        >
          No custom agents yet. Click <span class="text-fg-muted">New Agent</span> to create one.
        </div>
      </div>
    </div>

    <!-- Edit / Create form -->
    <div
      v-if="editing || creating"
      class="space-y-4"
    >
      <button
        class="text-xs text-neutral-500 hover:text-fg-strong transition-colors"
        @click="cancel"
      >
        &larr; Back to agents
      </button>
      <div class="bg-surface-elevated border border-border p-4">
        <h2 class="text-sm font-medium text-fg-strong mb-4">
          {{ creating ? 'New Agent' : 'Edit Agent' }}
        </h2>
        <div class="grid grid-cols-2 gap-3">
          <label
            :for="agentNameId"
            class="block"
          >
            <span class="block text-xs text-neutral-500 mb-1">
              Name
              <span
                v-if="editing?.isMain"
                class="ml-1 text-fg-muted"
              >(locked)</span>
            </span>
            <input
              :id="agentNameId"
              v-model="form.name"
              :disabled="editing?.isMain"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden focus:border-ring disabled:opacity-50 disabled:cursor-not-allowed"
            >
          </label>
          <label
            :for="agentProviderId"
            class="block"
          >
            <span class="block text-xs text-neutral-500 mb-1">Model Provider</span>
            <select
              :id="agentProviderId"
              v-model="form.modelProvider"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden focus:border-ring"
            >
              <option
                v-for="p in providers"
                :key="p.name"
                :value="p.name"
              >
                {{ p.name }}
              </option>
            </select>
          </label>
          <label
            :for="agentModelId"
            class="block"
          >
            <span class="block text-xs text-neutral-500 mb-1">Model</span>
            <select
              :id="agentModelId"
              v-model="form.modelId"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden focus:border-ring"
            >
              <option
                v-for="m in availableModels"
                :key="m.id"
                :value="m.id"
              >
                {{ m.name || m.id }}
              </option>
            </select>
          </label>
          <!--
            Thinking mode selector. The available options come from the selected
            model's `thinkingLevels` metadata (falling back to low/medium/high for
            any thinking-capable model that doesn't declare its own). Non-thinking
            models render the selector disabled with "Not supported" so the agent
            form shape stays consistent across rows.
          -->
          <label
            :for="agentThinkingId"
            class="block"
          >
            <span
              class="block text-xs text-neutral-500 mb-1"
              :class="{ 'opacity-40': !thinkingLevels.length }"
            >
              Thinking
            </span>
            <select
              :id="agentThinkingId"
              v-model="form.thinkingMode"
              :disabled="!thinkingLevels.length"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden focus:border-ring
                           disabled:opacity-40 disabled:cursor-not-allowed"
            >
              <option
                v-if="!thinkingLevels.length"
                value=""
              >
                Not supported
              </option>
              <template v-else>
                <option value="">
                  Off
                </option>
                <option
                  v-for="level in thinkingLevels"
                  :key="level"
                  :value="level"
                >
                  {{ level.charAt(0).toUpperCase() + level.slice(1) }}
                </option>
              </template>
            </select>
          </label>
        </div>
        <div class="flex mt-4">
          <button
            :disabled="saving || !form.name || !form.modelProvider || !form.modelId"
            class="p-1.5 text-emerald-700 dark:text-emerald-400 hover:text-emerald-600 dark:hover:text-emerald-300 disabled:opacity-40 disabled:hover:text-emerald-700 dark:disabled:hover:text-emerald-400 transition-colors"
            :title="saving ? 'Saving...' : 'Save'"
            @click="saveAgent"
          >
            <svg
              class="w-5 h-5"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            ><path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="1.5"
              d="M3 5.25A2.25 2.25 0 015.25 3h10.379a2.25 2.25 0 011.59.659l2.122 2.121a2.25 2.25 0 01.659 1.591V18.75A2.25 2.25 0 0118.75 21H5.25A2.25 2.25 0 013 18.75V5.25z"
            /><path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="1.5"
              d="M7.5 3v4.5h7.5V3M7.5 21v-6.75h9V21"
            /></svg>
          </button>
        </div>
      </div>

      <!-- Queue Mode -->
      <div
        v-if="editing"
        class="bg-surface-elevated border border-border p-4"
      >
        <div class="flex items-center justify-between">
          <div>
            <span class="text-sm font-medium text-fg-strong">Queue Mode</span>
            <div class="text-xs text-neutral-500 mt-0.5">
              How to handle messages when the agent is busy
            </div>
          </div>
          <div class="flex items-center gap-2">
            <label :for="agentQueueModeId">
              <span class="sr-only">Queue mode</span>
              <select
                :id="agentQueueModeId"
                v-model="queueMode"
                class="bg-muted border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden focus:border-ring"
                @change="saveQueueMode"
              >
                <option value="queue">
                  Queue (FIFO)
                </option>
                <option value="collect">
                  Collect (batch)
                </option>
                <option value="interrupt">
                  Interrupt
                </option>
              </select>
            </label>
          </div>
        </div>
      </div>

      <!-- Tools -->
      <div
        v-if="editing"
        class="bg-surface-elevated border border-border"
      >
        <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-fg-strong">Tools</span>
            <span class="text-xs text-neutral-500">{{ toggleableAgentTools.filter(t => t.enabled).length }}/{{ toggleableAgentTools.length }} enabled</span>
          </div>
          <button
            v-if="toggleableAgentTools.length"
            :title="allAgentToolsEnabled ? 'Disable all tools for this agent' : 'Enable all tools for this agent'"
            class="shrink-0"
            @click="toggleAllAgentTools()"
          >
            <div
              class="relative w-9 h-5 rounded-full transition-colors duration-200"
              :class="allAgentToolsEnabled ? 'bg-emerald-500' : 'bg-muted'"
            >
              <div
                class="absolute top-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-all duration-200"
                :class="allAgentToolsEnabled ? 'left-[18px]' : 'left-0.5'"
              />
            </div>
          </button>
        </div>
        <div>
          <template
            v-for="group in toolsByCategory"
            :key="group.category"
          >
            <!-- Category header row -->
            <div class="px-4 py-1.5 border-b border-border bg-surface-elevated">
              <span
                class="text-[10px] font-semibold uppercase tracking-widest"
                :class="{
                  'text-neutral-500': group.category === 'System',
                  'text-amber-500/70': group.category === 'Files',
                  'text-blue-500/70': group.category === 'Web',
                  'text-emerald-500/70': group.category === 'Utilities',
                }"
              >
                {{ group.category }}
              </span>
            </div>
            <div class="divide-y divide-border">
              <div
                v-for="tool in group.tools"
                :key="tool.name"
                class="px-4 py-3 flex items-center gap-3"
              >
                <!-- Colored icon matching the Tools page -->
                <div
                  class="w-8 h-8 rounded flex items-center justify-center shrink-0"
                  :class="getToolMeta(tool.name)?.iconBg ?? 'bg-muted'"
                >
                  <svg
                    class="w-4 h-4"
                    :class="getToolMeta(tool.name)?.iconColor ?? 'text-fg-muted'"
                    fill="none"
                    stroke="currentColor"
                    viewBox="0 0 24 24"
                  >
                    <path
                      v-if="getToolMeta(tool.name)?.icon === 'terminal'"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.75"
                      d="M8 9l3 3-3 3m5 0h3M5 20h14a2 2 0 002-2V6a2 2 0 00-2-2H5a2 2 0 00-2 2v12a2 2 0 002 2z"
                    />
                    <path
                      v-if="getToolMeta(tool.name)?.icon === 'folder'"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.75"
                      d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z"
                    />
                    <path
                      v-if="getToolMeta(tool.name)?.icon === 'document'"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.75"
                      d="M9 12h6m-6 4h6m2 5H7a2 2 0 01-2-2V5a2 2 0 012-2h5.586a1 1 0 01.707.293l5.414 5.414a1 1 0 01.293.707V19a2 2 0 01-2 2z"
                    />
                    <path
                      v-if="getToolMeta(tool.name)?.icon === 'globe'"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.75"
                      d="M21 12a9 9 0 01-9 9m9-9a9 9 0 00-9-9m9 9H3m9 9a9 9 0 01-9-9m9 9c1.657 0 3-4.03 3-9s-1.343-9-3-9m0 18c-1.657 0-3-4.03-3-9s1.343-9 3-9m-9 9a9 9 0 019-9"
                    />
                    <path
                      v-if="getToolMeta(tool.name)?.icon === 'search'"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.75"
                      d="M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"
                    />
                    <path
                      v-if="getToolMeta(tool.name)?.icon === 'browser'"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.75"
                      d="M9.75 17L9 20l-1 1h8l-1-1-.75-3M3 13h18M5 17h14a2 2 0 002-2V5a2 2 0 00-2-2H5a2 2 0 00-2 2v10a2 2 0 002 2z"
                    />
                    <path
                      v-if="getToolMeta(tool.name)?.icon === 'clock'"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.75"
                      d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                    />
                    <path
                      v-if="getToolMeta(tool.name)?.icon === 'check'"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.75"
                      d="M9 12l2 2 4-4m6 2a9 9 0 11-18 0 9 9 0 0118 0z"
                    />
                    <path
                      v-if="getToolMeta(tool.name)?.icon === 'tasks'"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                      stroke-width="1.75"
                      d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4"
                    />
                  </svg>
                </div>
                <!-- Name + function pills -->
                <div class="flex-1 min-w-0">
                  <span class="text-sm text-fg-strong font-mono">{{ tool.name }}</span>
                  <div class="flex flex-wrap gap-1 mt-1.5">
                    <span
                      v-for="fn in (getToolMeta(tool.name)?.functions ?? [])"
                      :key="fn.name"
                      class="text-[10px] font-mono px-1.5 py-0.5 border rounded-sm"
                      :class="getPillClass(tool.name)"
                    >
                      {{ fn.name }}
                    </span>
                  </div>
                </div>
                <!-- Pill toggle -->
                <button
                  :title="tool.enabled ? 'Disable tool for this agent' : 'Enable tool for this agent'"
                  class="shrink-0"
                  @click="tool.enabled = !tool.enabled; toggleTool(tool.name, tool.enabled)"
                >
                  <div
                    class="relative w-9 h-5 rounded-full transition-colors duration-200"
                    :class="tool.enabled ? 'bg-emerald-500' : 'bg-muted'"
                  >
                    <div
                      class="absolute top-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-all duration-200"
                      :class="tool.enabled ? 'left-[18px]' : 'left-0.5'"
                    />
                  </div>
                </button>
              </div>
            </div>
          </template>
        </div>
        <div
          v-if="!agentTools.length"
          class="px-4 py-4 text-xs text-fg-muted text-center"
        >
          No tools registered
        </div>
      </div>

      <!-- Exec privileges (main agent only) -->
      <div
        v-if="editing && editing.isMain"
        class="bg-surface-elevated border border-border"
      >
        <div class="px-4 py-2.5 border-b border-border">
          <span class="text-sm font-medium text-fg-strong">Shell Exec Privileges</span>
          <span class="ml-2 text-[10px] text-amber-400">main agent only</span>
        </div>
        <div class="divide-y divide-border">
          <div class="px-4 py-2.5 flex items-center justify-between">
            <div>
              <span class="text-sm text-fg-strong">Bypass allowlist</span>
              <div class="text-xs text-neutral-500 mt-0.5">
                Allow any command without allowlist validation
              </div>
            </div>
            <button
              :class="execBypassAllowlist ? 'bg-amber-600 hover:bg-amber-500' : 'bg-muted hover:bg-neutral-300 dark:hover:bg-neutral-600'"
              class="relative w-9 h-5 rounded-full transition-colors shrink-0"
              @click="execBypassAllowlist = !execBypassAllowlist; toggleExecConfig('bypassAllowlist', execBypassAllowlist)"
            >
              <span
                :class="execBypassAllowlist ? 'translate-x-4' : 'translate-x-0.5'"
                class="block w-4 h-4 bg-white rounded-full transition-transform"
              />
            </button>
          </div>
          <div class="px-4 py-2.5 flex items-center justify-between">
            <div>
              <span class="text-sm text-fg-strong">Allow global paths</span>
              <div class="text-xs text-neutral-500 mt-0.5">
                Execute commands outside the agent workspace directory
              </div>
            </div>
            <button
              :class="execAllowGlobalPaths ? 'bg-amber-600 hover:bg-amber-500' : 'bg-muted hover:bg-neutral-300 dark:hover:bg-neutral-600'"
              class="relative w-9 h-5 rounded-full transition-colors shrink-0"
              @click="execAllowGlobalPaths = !execAllowGlobalPaths; toggleExecConfig('allowGlobalPaths', execAllowGlobalPaths)"
            >
              <span
                :class="execAllowGlobalPaths ? 'translate-x-4' : 'translate-x-0.5'"
                class="block w-4 h-4 bg-white rounded-full transition-transform"
              />
            </button>
          </div>
        </div>
      </div>

      <!-- Skills -->
      <div
        v-if="editing"
        class="bg-surface-elevated border border-border"
      >
        <div class="px-4 py-2.5 border-b border-border">
          <span class="text-sm font-medium text-fg-strong">Skills</span>
          <span class="ml-2 text-xs text-neutral-500">{{ agentSkills.filter(s => s.enabled).length }}/{{ agentSkills.length }} enabled</span>
        </div>
        <div class="divide-y divide-border">
          <div
            v-for="skill in agentSkills"
            :key="skill.name"
            class="px-4 py-2.5 flex items-start justify-between gap-3 transition-opacity"
            :class="skillDisabledTools(skill).length ? 'opacity-45' : ''"
          >
            <div
              class="flex-1 min-w-0 transition-[filter]"
              :class="skillDisabledTools(skill).length ? 'blur-[0.4px]' : ''"
            >
              <div class="flex items-center gap-2 flex-wrap">
                <span class="text-sm text-fg-strong font-mono">{{ skill.name }}</span>
                <span
                  v-if="skill.isGlobal"
                  class="text-[10px] text-green-400 border border-green-400/30 px-1"
                >global</span>
              </div>
              <div
                v-if="skill.tools?.length"
                class="flex flex-wrap gap-1 mt-1.5"
              >
                <span
                  v-for="tool in skill.tools"
                  :key="tool"
                  class="text-[10px] font-mono px-1.5 py-0.5 border rounded-sm"
                  :class="getPillClass(tool)"
                >
                  {{ tool }}
                </span>
              </div>
              <!--
                Shell commands this skill contributes to the agent's effective
                allowlist. These are the binaries bundled under the skill's
                tools/ directory, blessed at promotion time. Enabling this
                skill grants execution rights for these exact names.
              -->
              <div
                v-if="skill.commands?.length"
                class="mt-1.5 text-[11px] text-neutral-500 flex flex-wrap items-center gap-1"
              >
                <span class="text-fg-muted uppercase tracking-wide text-[10px]">Provides:</span>
                <span
                  v-for="cmd in skill.commands"
                  :key="cmd"
                  class="font-mono text-cyan-400/80 bg-cyan-400/5 border border-cyan-400/20 px-1.5 py-0.5 rounded-sm"
                >
                  {{ cmd }}
                </span>
              </div>
              <div
                v-if="skillDisabledTools(skill).length"
                class="text-[10px] text-amber-500/70 mt-1.5"
              >
                requires {{ skillDisabledTools(skill).join(', ') }}
              </div>
              <div
                v-else-if="skill.description"
                class="text-xs text-neutral-500 mt-1.5"
              >
                {{ skill.description }}
              </div>
            </div>
            <button
              :title="skillDisabledTools(skill).length
                ? 'Enable ' + skillDisabledTools(skill).join(', ') + ' to use this skill'
                : skill.enabled ? 'Disable skill' : 'Enable skill'"
              class="shrink-0 pt-0.5"
              :class="skillDisabledTools(skill).length ? 'cursor-not-allowed' : ''"
              @click="if (!skillDisabledTools(skill).length) { skill.enabled = !skill.enabled; toggleSkill(skill.name, skill.enabled) }"
            >
              <div
                class="relative w-9 h-5 rounded-full transition-colors duration-200"
                :class="(!skillDisabledTools(skill).length && skill.enabled) ? 'bg-emerald-500' : 'bg-muted'"
              >
                <div
                  class="absolute top-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-all duration-200"
                  :class="(!skillDisabledTools(skill).length && skill.enabled) ? 'left-[18px]' : 'left-0.5'"
                />
              </div>
            </button>
          </div>
        </div>
        <div
          v-if="!agentSkills.length"
          class="px-4 py-4 text-xs text-fg-muted text-center"
        >
          No skills available
        </div>
      </div>

      <!--
        Effective shell allowlist — derived view of what this agent can run via
        the exec tool. Aggregates the global shell.allowlist (edited in Settings)
        with the commands each enabled skill contributes at install time.
        Read-only: remove a per-skill grant by disabling or removing the skill;
        change global by editing shell.allowlist in Settings.
      -->
      <div
        v-if="editing && effectiveAllowlist"
        class="bg-surface-elevated border border-border"
      >
        <button
          class="w-full px-4 py-2.5 border-b border-border text-left hover:bg-muted transition-colors flex items-center justify-between"
          @click="allowlistExpanded = !allowlistExpanded"
        >
          <span class="text-sm font-medium text-fg-strong">
            Shell Allowlist
            <span class="ml-2 text-xs font-normal text-neutral-500">
              {{ effectiveAllowlist.global.length + Object.values(effectiveAllowlist.bySkill).reduce((n, arr) => n + arr.length, 0) }}
              commands
              ({{ effectiveAllowlist.global.length }} global +
              {{ Object.values(effectiveAllowlist.bySkill).reduce((n, arr) => n + arr.length, 0) }}
              from {{ Object.keys(effectiveAllowlist.bySkill).length }} skill{{ Object.keys(effectiveAllowlist.bySkill).length === 1 ? '' : 's' }})
            </span>
          </span>
          <svg
            class="w-3 h-3 text-neutral-500 transition-transform"
            :class="allowlistExpanded ? 'rotate-90' : ''"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="2"
              d="M9 5l7 7-7 7"
            />
          </svg>
        </button>
        <div
          v-if="allowlistExpanded"
          class="px-4 py-3"
        >
          <p class="text-[11px] text-neutral-500 mb-2">
            What this agent can run via the exec tool. Global entries come from
            <span class="font-mono text-fg-muted">shell.allowlist</span> in Settings;
            per-skill entries come from the skill's declared
            <span class="font-mono text-fg-muted">commands:</span>
            and disappear when you disable or remove the skill.
          </p>
          <table class="w-full text-xs">
            <thead>
              <tr class="text-neutral-500 text-[10px] uppercase tracking-wide">
                <th class="text-left font-medium py-1 pr-4">
                  Command
                </th>
                <th class="text-left font-medium py-1">
                  Source
                </th>
              </tr>
            </thead>
            <tbody class="divide-y divide-border">
              <tr
                v-for="cmd in effectiveAllowlist.global"
                :key="'g:' + cmd"
              >
                <td class="py-1 pr-4 font-mono text-fg-primary">
                  {{ cmd }}
                </td>
                <td class="py-1 text-neutral-500">
                  Global (shell.allowlist)
                </td>
              </tr>
              <template
                v-for="(cmds, skillName) in effectiveAllowlist.bySkill"
                :key="skillName"
              >
                <tr
                  v-for="cmd in cmds"
                  :key="skillName + ':' + cmd"
                >
                  <td class="py-1 pr-4 font-mono text-cyan-400/80">
                    {{ cmd }}
                  </td>
                  <td class="py-1 text-neutral-500">
                    Skill: <span class="font-mono text-fg-muted">{{ skillName }}</span>
                  </td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </div>

      <!-- Workspace editor -->
      <div
        v-if="editing"
        class="bg-surface-elevated border border-border"
      >
        <div class="flex border-b border-border">
          <button
            v-for="file in workspaceFiles"
            :key="file"
            :class="workspaceTab === file ? 'text-fg-strong border-b border-white' : 'text-neutral-500'"
            class="px-4 py-2 text-xs font-mono transition-colors"
            @click="loadWorkspaceFile(editing.id, file)"
          >
            {{ file }}
          </button>
        </div>
        <label :for="agentWorkspaceTextareaId">
          <span class="sr-only">Workspace file contents</span>
          <textarea
            :id="agentWorkspaceTextareaId"
            v-model="workspaceContent"
            rows="16"
            class="w-full px-4 py-3 bg-transparent text-sm text-fg-primary font-mono
                   resize-y focus:outline-hidden"
          />
        </label>
        <div class="px-4 py-2 border-t border-border flex">
          <button
            class="p-1.5 text-emerald-700 dark:text-emerald-400 hover:text-emerald-600 dark:hover:text-emerald-300 transition-colors"
            title="Save file"
            @click="saveWorkspaceFile"
          >
            <svg
              class="w-5 h-5"
              fill="none"
              stroke="currentColor"
              viewBox="0 0 24 24"
            ><path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="1.5"
              d="M3 5.25A2.25 2.25 0 015.25 3h10.379a2.25 2.25 0 011.59.659l2.122 2.121a2.25 2.25 0 01.659 1.591V18.75A2.25 2.25 0 0118.75 21H5.25A2.25 2.25 0 013 18.75V5.25z"
            /><path
              stroke-linecap="round"
              stroke-linejoin="round"
              stroke-width="1.5"
              d="M7.5 3v4.5h7.5V3M7.5 21v-6.75h9V21"
            /></svg>
          </button>
        </div>
      </div>
    </div>

    <!-- System prompt breakdown dialog -->
    <!-- eslint-disable-next-line vuejs-accessibility/click-events-have-key-events, vuejs-accessibility/no-static-element-interactions -- modal backdrop; Escape is handled globally via document keydown listener -->
    <div
      v-if="promptBreakdownOpen"
      class="fixed inset-0 z-50 flex items-start justify-center bg-black/70 p-6 overflow-y-auto"
      role="dialog"
      aria-modal="true"
      @click.self="closePromptBreakdown()"
    >
      <div class="bg-surface-elevated border border-border w-full max-w-4xl my-6 text-fg-primary">
        <div class="px-4 py-3 border-b border-border flex items-center justify-between gap-3">
          <div class="min-w-0">
            <h3 class="text-sm font-medium text-fg-strong truncate">
              System prompt — {{ promptBreakdownAgent?.name }}
            </h3>
            <p class="text-[10px] text-neutral-500 truncate">
              {{ promptBreakdownAgent?.modelProvider }} / {{ promptBreakdownAgent?.modelId }}
            </p>
          </div>
          <div class="flex items-center gap-2 shrink-0">
            <button
              v-if="promptBreakdownData"
              class="px-2 py-1 text-[10px] text-fg-muted border border-input hover:text-fg-strong hover:border-neutral-500"
              title="Copy the raw breakdown JSON (useful for bug reports)"
              @click="copyPromptBreakdownJson()"
            >
              Copy JSON
            </button>
            <button
              class="p-1 text-neutral-500 hover:text-fg-strong"
              title="Close"
              @click="closePromptBreakdown()"
            >
              <svg
                class="w-4 h-4"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              ><path
                stroke-linecap="round"
                stroke-linejoin="round"
                stroke-width="2"
                d="M6 18L18 6M6 6l12 12"
              /></svg>
            </button>
          </div>
        </div>

        <div
          v-if="promptBreakdownLoading"
          class="px-4 py-6 text-sm text-neutral-500"
        >
          Loading…
        </div>

        <div
          v-else-if="promptBreakdownError"
          class="px-4 py-6 text-sm text-red-400"
        >
          {{ promptBreakdownError }}
        </div>

        <div
          v-else-if="promptBreakdownData"
          class="px-4 py-4 space-y-5"
        >
          <!-- Totals strip -->
          <div class="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
            <div class="bg-muted border border-border px-3 py-2">
              <div class="text-[10px] text-neutral-500 uppercase tracking-wide">
                Total chars
              </div>
              <div class="text-sm font-mono text-fg-strong">
                {{ formatChars(promptBreakdownData.totalChars) }}
              </div>
              <div class="text-[10px] text-neutral-500">
                prompt + tool schemas
              </div>
            </div>
            <div class="bg-muted border border-border px-3 py-2">
              <div class="text-[10px] text-neutral-500 uppercase tracking-wide">
                ≈ tokens
              </div>
              <div class="text-sm font-mono text-amber-300">
                {{ formatTokens(promptBreakdownData.totalTokenEstimate) }}
              </div>
              <div class="text-[10px] text-neutral-500">
                chars/4 heuristic
              </div>
            </div>
            <div
              class="bg-muted border border-border px-3 py-2"
              title="Bytes above the cache boundary marker — hash-stable, reused from the provider cache on repeat turns"
            >
              <div class="text-[10px] text-neutral-500 uppercase tracking-wide">
                Cacheable prefix
              </div>
              <div class="text-sm font-mono text-emerald-700 dark:text-emerald-400">
                {{ formatChars(promptBreakdownData.cacheablePrefixChars) }}
              </div>
              <div class="text-[10px] text-neutral-500">
                ≈ {{ formatTokens(Math.round(promptBreakdownData.cacheablePrefixChars / 4)) }} tokens
              </div>
            </div>
            <div
              class="bg-muted border border-border px-3 py-2"
              title="Bytes after the cache boundary — per-turn-variable content (memories) that never hits the cache"
            >
              <div class="text-[10px] text-neutral-500 uppercase tracking-wide">
                Variable suffix
              </div>
              <div class="text-sm font-mono text-rose-400">
                {{ formatChars(promptBreakdownData.variableSuffixChars) }}
              </div>
              <div class="text-[10px] text-neutral-500">
                ≈ {{ formatTokens(Math.round(promptBreakdownData.variableSuffixChars / 4)) }} tokens
              </div>
            </div>
          </div>

          <!-- Sections table -->
          <div>
            <h4 class="text-[11px] text-neutral-500 uppercase tracking-wide mb-1.5">
              Prompt sections
            </h4>
            <table class="w-full text-xs font-mono">
              <thead class="text-[10px] text-neutral-500 border-b border-border">
                <tr>
                  <th class="text-left py-1 pr-2">
                    Section
                  </th>
                  <th class="text-right py-1 px-2">
                    Chars
                  </th>
                  <th class="text-right py-1 px-2">
                    ≈ Tokens
                  </th>
                  <th class="text-right py-1 pl-2">
                    % of prompt
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="s in promptBreakdownData.sections"
                  :key="'section-' + s.name"
                  class="border-b border-neutral-900/50"
                >
                  <td class="py-1 pr-2 text-fg-primary">
                    {{ s.name }}
                  </td>
                  <td class="py-1 px-2 text-right text-fg-muted">
                    {{ formatChars(s.chars) }}
                  </td>
                  <td class="py-1 px-2 text-right text-amber-300/80">
                    {{ formatTokens(s.tokens) }}
                  </td>
                  <td class="py-1 pl-2 text-right text-neutral-500">
                    {{ percentOfTotal(s.chars, promptBreakdownData.cacheablePrefixChars + promptBreakdownData.variableSuffixChars) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Skills table -->
          <div v-if="promptBreakdownData.skills.length > 0">
            <h4 class="text-[11px] text-neutral-500 uppercase tracking-wide mb-1.5">
              Skills included ({{ promptBreakdownData.skills.length }})
            </h4>
            <table class="w-full text-xs font-mono">
              <thead class="text-[10px] text-neutral-500 border-b border-border">
                <tr>
                  <th class="text-left py-1 pr-2">
                    Skill
                  </th>
                  <th class="text-right py-1 px-2">
                    Chars
                  </th>
                  <th class="text-right py-1 pl-2">
                    ≈ Tokens
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="s in promptBreakdownData.skills"
                  :key="'skill-' + s.name"
                  class="border-b border-neutral-900/50"
                >
                  <td class="py-1 pr-2 text-fg-primary">
                    {{ s.name }}
                  </td>
                  <td class="py-1 px-2 text-right text-fg-muted">
                    {{ formatChars(s.chars) }}
                  </td>
                  <td class="py-1 pl-2 text-right text-amber-300/80">
                    {{ formatTokens(s.tokens) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>

          <!-- Tools table -->
          <div v-if="promptBreakdownData.tools.length > 0">
            <h4 class="text-[11px] text-neutral-500 uppercase tracking-wide mb-1.5">
              Tool schemas ({{ promptBreakdownData.tools.length }})
            </h4>
            <p class="text-[10px] text-fg-muted mb-1">
              Sent separately as the <code class="text-neutral-500">tools</code> array, not part of the prompt string, but counted as input tokens by every provider.
            </p>
            <table class="w-full text-xs font-mono">
              <thead class="text-[10px] text-neutral-500 border-b border-border">
                <tr>
                  <th class="text-left py-1 pr-2">
                    Tool
                  </th>
                  <th class="text-right py-1 px-2">
                    Schema chars
                  </th>
                  <th class="text-right py-1 pl-2">
                    ≈ Tokens
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="t in promptBreakdownData.tools"
                  :key="'tool-' + t.name"
                  class="border-b border-neutral-900/50"
                >
                  <td class="py-1 pr-2 text-fg-primary">
                    {{ t.name }}
                  </td>
                  <td class="py-1 px-2 text-right text-fg-muted">
                    {{ formatChars(t.chars) }}
                  </td>
                  <td class="py-1 pl-2 text-right text-amber-300/80">
                    {{ formatTokens(t.tokens) }}
                  </td>
                </tr>
              </tbody>
            </table>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
