<script setup lang="ts">
import {
  CheckCircleIcon,
  ChevronDownIcon,
  ChevronRightIcon,
  ChevronUpIcon,
  ClipboardDocumentCheckIcon,
  ClockIcon,
  CommandLineIcon,
  ComputerDesktopIcon,
  DocumentTextIcon,
  FolderIcon,
  GlobeAltIcon,
  MagnifyingGlassIcon,
  PlusIcon,
  PuzzlePieceIcon,
  TrashIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
import { Save } from 'lucide-vue-next'
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
import { effectiveThinkingLevels, findProviderModel, type ProviderModel } from '~/composables/useProviders'

const { confirm } = useConfirm()

// Parallel fetch — avoids sequential waterfall
const [{ data: agents, refresh }, { data: configData }] = await Promise.all([
  useFetch<Agent[]>('/api/agents'),
  useFetch<ConfigResponse>('/api/config'),
])

const editing = ref<Agent | null>(null)
const creating = ref(false)

// Feed the layout breadcrumb: when editing, show "Agents > {name}"; when
// creating, show "Agents > New agent"; otherwise just "Agents". The reverse
// direction closes the edit form when the layout clears the extra (user
// clicked the "Agents" crumb while already on /agents).
const breadcrumbExtra = useBreadcrumbExtra()
watch([editing, creating], ([agent, isCreating]) => {
  if (agent) breadcrumbExtra.value = agent.name
  else if (isCreating) breadcrumbExtra.value = 'New agent'
  else breadcrumbExtra.value = null
}, { immediate: true })
watch(breadcrumbExtra, (value) => {
  if (value === null && (editing.value || creating.value)) {
    editing.value = null
    creating.value = false
  }
})
onUnmounted(() => {
  breadcrumbExtra.value = null
})
const workspaceTab = ref('AGENT.md')
const workspaceContent = ref('')
// Snapshot of the last-saved workspace-file content for the active tab.
// Compared against workspaceContent to drive the save button's disabled state.
// Updated on load (reset to server copy) and on successful save (reset to the
// just-persisted value).
const workspaceBaseline = ref('')
interface AgentForm {
  name: string
  description: string
  modelProvider: string
  modelId: string
  enabled: boolean
  thinkingMode: string
}
const form = ref<AgentForm>({
  name: '',
  description: '',
  modelProvider: '',
  modelId: '',
  enabled: true,
  thinkingMode: '',
})
// Snapshot of the agent form at load time (or after a successful save). See
// formDirty below — together they gate the Save button so it's only active
// when the form has unsaved changes.
const formBaseline = ref({ ...form.value })
const formDirty = computed(() =>
  JSON.stringify(form.value) !== JSON.stringify(formBaseline.value),
)
const workspaceDirty = computed(() => workspaceContent.value !== workspaceBaseline.value)
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
const { TOOL_META, getToolMeta, getPillClass, refresh: refreshTools } = useToolMeta()

// Force a refetch on visit so the MCP rows in the agent's tools section
// reflect live server state (an operator who disabled a server in
// another tab shouldn't see its row here until a hard reload).
onMounted(() => {
  refreshTools()
})

// Map the backend-supplied tool icon key (see useToolMeta) to a Heroicons
// component. Returns null for unknown keys so the caller can suppress rendering.
const TOOL_ICON_COMPONENTS = {
  terminal: CommandLineIcon,
  folder: FolderIcon,
  document: DocumentTextIcon,
  globe: GlobeAltIcon,
  search: MagnifyingGlassIcon,
  browser: ComputerDesktopIcon,
  clock: ClockIcon,
  check: CheckCircleIcon,
  tasks: ClipboardDocumentCheckIcon,
} as const
function toolIconComponent(name: string) {
  const key = getToolMeta(name)?.icon as keyof typeof TOOL_ICON_COMPONENTS | undefined
  return key ? TOOL_ICON_COMPONENTS[key] ?? null : null
}
/**
 * One row in the agent's tools section. Either a single native tool with
 * its own toggle, or a folded group of MCP tools (all sharing one server)
 * with a single toggle that flips every member via the bulk endpoint.
 */
type ToolRow
  = | { kind: 'tool', key: string, tool: AgentTool, enabled: boolean }
    | { kind: 'group', key: string, group: string, members: AgentTool[], enabled: boolean, functionCount: number }

const toolsByCategory = computed(() => {
  const categories = ['System', 'Files', 'Web', 'Utilities', 'MCP'] as const
  const orderedNames = [
    'exec',
    'filesystem', 'documents',
    'web_fetch', 'web_search', 'browser',
    'datetime', 'checklist', 'task_manager',
  ]
  const posOf = (name: string) => {
    const i = orderedNames.indexOf(name)
    return i === -1 ? 999 : i
  }
  return categories
    .map((category) => {
      const inCategory = agentTools.value
        .filter(t => (TOOL_META.value[t.name]?.category ?? 'Utilities') === category)
      // Fold tools that share a `group` (MCP) into one row; emit single-tool
      // rows for everything else. Preserves first-appearance order within
      // the category so the rendering stays stable across reloads.
      const groupBuckets = new Map<string, AgentTool[]>()
      const rows: ToolRow[] = []
      for (const t of inCategory) {
        const groupName = (t.group as string | undefined) ?? null
        if (groupName) {
          const existing = groupBuckets.get(groupName)
          if (existing) {
            existing.push(t)
          }
          else {
            const bucket = [t]
            groupBuckets.set(groupName, bucket)
            // Reserve this group's slot on first sight; we'll fill its row
            // shape after the loop completes.
            rows.push({
              kind: 'group',
              key: `group:${groupName}`,
              group: groupName,
              members: bucket,
              enabled: false,
              functionCount: 0,
            })
          }
        }
        else {
          rows.push({
            kind: 'tool',
            key: t.name,
            tool: t,
            enabled: t.enabled,
          })
        }
      }
      // Resolve group rows now that we have all members.
      for (const row of rows) {
        if (row.kind === 'group') {
          row.enabled = row.members.every(m => m.enabled)
          row.functionCount = row.members.length
        }
      }
      // Native tools sort by canonical order; group rows trail.
      rows.sort((a, b) => {
        const ai = a.kind === 'tool' ? posOf(a.tool.name) : 1000
        const bi = b.kind === 'tool' ? posOf(b.tool.name) : 1000
        return ai - bi
      })
      return { category, rows }
    })
    .filter(g => g.rows.length > 0)
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
const agentDescriptionId = useId()
const agentProviderId = useId()
const agentModelId = useId()
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

/**
 * Which channel's prompt the dialog is currently previewing. Every real chat
 * lives on a channel, so we default to `web` — the admin chat UI — rather
 * than exposing a meaningless channel-less baseline.
 */
const promptBreakdownChannel = ref<'web' | 'telegram' | 'slack' | 'whatsapp'>('web')

async function loadPromptBreakdown() {
  if (!promptBreakdownAgent.value) return
  promptBreakdownData.value = null
  promptBreakdownError.value = ''
  promptBreakdownLoading.value = true
  try {
    promptBreakdownData.value = await $fetch<PromptBreakdown>(
      `/api/agents/${promptBreakdownAgent.value.id}/prompt-breakdown?channelType=${encodeURIComponent(promptBreakdownChannel.value)}`,
    )
  }
  catch (e: unknown) {
    promptBreakdownError.value = e instanceof Error ? e.message : 'Failed to load prompt breakdown'
  }
  finally {
    promptBreakdownLoading.value = false
  }
}

async function openPromptBreakdown(agent: Agent) {
  promptBreakdownAgent.value = agent
  promptBreakdownOpen.value = true
  promptBreakdownChannel.value = 'web'
  await loadPromptBreakdown()
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

const skillsExpanded = ref(true)

/**
 * Sort state for the two breakdown tables. `null` sortBy means
 * "as-is" (the order the backend sends, which for prompt sections is
 * the actual assembly order — meaningful, so it's the default).
 *
 * Click cycle on a column: desc → asc → as-is. Skill subrows ride
 * along with the parent Skills row regardless of sort, since they're
 * rendered inside the same v-for template; the Total row sits outside
 * the loop, so it always stays at the bottom.
 */
type BreakdownSortCol = 'name' | 'chars'
const sectionsSortBy = ref<BreakdownSortCol | null>(null)
const sectionsSortDir = ref<'asc' | 'desc'>('desc')
const toolsSortBy = ref<BreakdownSortCol | null>(null)
const toolsSortDir = ref<'asc' | 'desc'>('desc')

function cycleSectionsSort(col: BreakdownSortCol) {
  if (sectionsSortBy.value !== col) {
    sectionsSortBy.value = col
    sectionsSortDir.value = 'desc'
  }
  else if (sectionsSortDir.value === 'desc') {
    sectionsSortDir.value = 'asc'
  }
  else {
    sectionsSortBy.value = null
    sectionsSortDir.value = 'desc'
  }
}
function cycleToolsSort(col: BreakdownSortCol) {
  if (toolsSortBy.value !== col) {
    toolsSortBy.value = col
    toolsSortDir.value = 'desc'
  }
  else if (toolsSortDir.value === 'desc') {
    toolsSortDir.value = 'asc'
  }
  else {
    toolsSortBy.value = null
    toolsSortDir.value = 'desc'
  }
}
function sortBreakdownRows<T extends { name: string, chars: number, tokens: number }>(
  rows: T[],
  by: BreakdownSortCol | null,
  dir: 'asc' | 'desc',
): T[] {
  if (!by) return rows
  const sorted = [...rows]
  sorted.sort((a, b) => {
    const cmp = by === 'name' ? a.name.localeCompare(b.name) : a[by] - b[by]
    return dir === 'desc' ? -cmp : cmp
  })
  return sorted
}
const sortedSections = computed(() =>
  sortBreakdownRows(promptBreakdownData.value?.sections ?? [], sectionsSortBy.value, sectionsSortDir.value),
)
const sortedTools = computed(() =>
  sortBreakdownRows(promptBreakdownData.value?.tools ?? [], toolsSortBy.value, toolsSortDir.value),
)

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

/**
 * Bottom-of-table subtotals: PROMPT SECTIONS sum and TOOL SCHEMAS sum.
 * Together they equal TOTAL CHARS.
 */
const sectionsAggregate = computed(() => {
  const rows = promptBreakdownData.value?.sections ?? []
  return {
    chars: rows.reduce((s, r) => s + r.chars, 0),
    tokens: rows.reduce((s, r) => s + r.tokens, 0),
  }
})
const toolSchemasAggregate = computed(() => {
  const rows = promptBreakdownData.value?.tools ?? []
  return {
    chars: rows.reduce((s, r) => s + r.chars, 0),
    tokens: rows.reduce((s, r) => s + r.tokens, 0),
  }
})

/**
 * Per-skill rows account for the <skill> XML entries inside
 * <available_skills>, but the Skills section *also* carries the
 * skill-matching prose preamble + the <available_skills> open/close
 * tags. Surface that delta as its own subrow so the inlined children
 * genuinely sum to the parent Skills row.
 */
const skillsMatchingGap = computed(() => {
  const data = promptBreakdownData.value
  if (!data) return { chars: 0, tokens: 0 }
  const skillsSection = data.sections.find(s => s.name === 'Skills')
  if (!skillsSection) return { chars: 0, tokens: 0 }
  const itemized = data.skills.reduce(
    (acc, sk) => ({ chars: acc.chars + sk.chars, tokens: acc.tokens + sk.tokens }),
    { chars: 0, tokens: 0 },
  )
  return {
    chars: skillsSection.chars - itemized.chars,
    tokens: skillsSection.tokens - itemized.tokens,
  }
})

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

// Look up a listed agent's model metadata so the row can show capability pills.
function modelForAgent(agent: Agent | null | undefined): ProviderModel | null {
  if (!agent) return null
  return findProviderModel(providers.value, agent.modelProvider, agent.modelId)
}

// Pick a sensible default reasoning-effort level when the user toggles
// the Thinking pill on. Middle of the model's declared levels matches the
// spirit of backend DEFAULT_THINKING_LEVELS (low/medium/high → medium).
function defaultThinkingLevel(model: ProviderModel | null): string {
  const levels = effectiveThinkingLevels(model)
  if (!levels.length) return 'medium'
  return levels[Math.floor(levels.length / 2)] ?? 'medium'
}

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
  form.value = {
    name: '',
    description: '',
    modelProvider: defaultProvider,
    modelId: defaultModel,
    enabled: true,
    thinkingMode: '',
  }
  formBaseline.value = { ...form.value }
  creating.value = true
  editing.value = null
}

function editAgent(agent: Agent) {
  form.value = {
    name: agent.name,
    description: agent.description ?? '',
    modelProvider: agent.modelProvider,
    modelId: agent.modelId,
    enabled: agent.enabled,
    thinkingMode: agent.thinkingMode ?? '',
  }
  formBaseline.value = { ...form.value }
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
// advertise. Prevents submitting a stale level (e.g. "high" after swapping
// to a non-thinking model) that would be silently normalized server-side.
watch(() => [form.value.modelProvider, form.value.modelId], () => {
  if (form.value.thinkingMode) {
    const levels = effectiveThinkingLevels(selectedModel.value)
    if (!levels.includes(form.value.thinkingMode)) form.value.thinkingMode = ''
  }
})

// Pill toggle from inside the Edit Agent form. Thinking is the only
// remaining toggleable capability — vision/audio are pure capability
// indicators (no LLM API exposes an off-switch for either).
function toggleFormCapability(capability: 'thinking') {
  if (capability === 'thinking') {
    form.value.thinkingMode = form.value.thinkingMode
      ? ''
      : defaultThinkingLevel(selectedModel.value)
  }
}

// Pill toggle from a listing row — persists immediately via a partial PUT
// so the row stays in sync with the backend. Only the touched field is
// sent; the update endpoint honours absent-key-leaves-unchanged.
async function toggleListingCapability(agent: Agent | undefined, capability: 'thinking') {
  if (!agent) return
  const body: Record<string, unknown> = {}
  if (capability === 'thinking') {
    body.thinkingMode = agent.thinkingMode
      ? null
      : defaultThinkingLevel(modelForAgent(agent))
  }
  try {
    await $fetch(`/api/agents/${agent.id}`, { method: 'PUT', body })
    refresh()
  }
  catch (e) {
    console.error('Failed to toggle capability:', e)
  }
}

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

/**
 * Bulk-toggle every tool in a group (MCP server) for the editing agent,
 * via PUT /api/agents/:id/tool-groups/:group. One HTTP call regardless of
 * group size, and it updates the local agentTools state so the row's
 * toggle reflects immediately without waiting for a refetch.
 */
async function toggleToolGroup(group: string, enabled: boolean) {
  if (!editing.value) return
  // Optimistic local update so the toggle animates without a roundtrip.
  for (const t of agentTools.value) {
    if ((t.group as string | undefined) === group) t.enabled = enabled
  }
  try {
    await $fetch(`/api/agents/${editing.value.id}/tool-groups/${encodeURIComponent(group)}`, {
      method: 'PUT',
      body: { enabled },
    })
  }
  catch (e) {
    console.error('Failed to toggle tool group:', e)
  }
}

// JCLAW-281: every tool is operator-toggleable now (the system flag is gone),
// so the filter is a pure pass-through. Kept as a named computed for symmetry
// with the rest of the page rather than removing its call sites.
const toggleableAgentTools = computed(() => agentTools.value)

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

// Skills with no missing tool dependencies — these are the ones the per-skill
// toggle would actually flip. Bulk toggle must filter the same way so we never
// silently re-enable a skill that's structurally non-functional (its enabled
// state would have no effect, and the UI's opacity-45 + cursor-not-allowed
// already communicates "you can't toggle me").
const toggleableAgentSkills = computed(() =>
  agentSkills.value.filter(s => skillDisabledTools(s).length === 0),
)

const allAgentSkillsEnabled = computed(() =>
  toggleableAgentSkills.value.length > 0 && toggleableAgentSkills.value.every(s => s.enabled),
)

async function toggleAllAgentSkills() {
  const next = !allAgentSkillsEnabled.value
  toggleableAgentSkills.value.forEach((s) => {
    s.enabled = next
  })
  await Promise.all(toggleableAgentSkills.value.map(s => toggleSkill(s.name, next)))
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
      // Blank description clears the column; backend also strips/trims.
      description: form.value.description.trim() || null,
    }
    if (creating.value) {
      await $fetch('/api/agents', { method: 'POST', body: payload })
      // Create mode navigates back to the list so the user sees the new row.
      editing.value = null
      creating.value = false
    }
    else if (editing.value) {
      await $fetch(`/api/agents/${editing.value.id}`, { method: 'PUT', body: payload })
      // Edit mode stays on the detail page; reset the baseline so the Save
      // button disables until the user makes another change.
      formBaseline.value = { ...form.value }
    }
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
  workspaceBaseline.value = workspaceContent.value
}

async function saveWorkspaceFile() {
  if (!editing.value || !workspaceDirty.value) return
  const saved = workspaceContent.value
  await $fetch(`/api/agents/${editing.value.id}/workspace/${workspaceTab.value}`, {
    method: 'PUT',
    body: { content: saved },
  })
  // Snapshot the just-persisted value so the save button disables until the
  // textarea diverges again. Capture before the await resolved so a late
  // keystroke doesn't get clobbered into the baseline.
  workspaceBaseline.value = saved
}

function cancel() {
  editing.value = null
  creating.value = false
  agentTools.value = []
  agentSkills.value = []
  queueMode.value = 'queue'
}

const workspaceFiles = ['SOUL.md', 'IDENTITY.md', 'USER.md', 'BOOTSTRAP.md', 'AGENT.md']
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
            <PlusIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
          <button
            :disabled="!customAgents.length"
            class="p-2 border border-input text-fg-muted hover:text-red-400 hover:border-red-700/50 disabled:opacity-40 disabled:hover:text-fg-muted disabled:hover:border-input transition-colors"
            title="Delete an agent"
            @click="enterSelectMode"
          >
            <TrashIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
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
      <div
        class="bg-surface-elevated border border-border"
        data-tour="main-agent"
      >
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
            <div
              v-if="mainAgent.description"
              class="text-xs text-fg-muted mt-0.5"
            >
              {{ mainAgent.description }}
            </div>
            <div class="text-xs text-neutral-500 mt-3">
              {{ mainAgent.modelProvider }} / {{ mainAgent.modelId }}
            </div>
            <ModelCapabilityPills
              :model="modelForAgent(mainAgent)"
              :thinking-mode="mainAgent.thinkingMode"
              class="mt-2"
              @toggle="(cap) => toggleListingCapability(mainAgent, cap)"
            />
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
              <div
                v-if="agent.description"
                class="text-xs text-fg-muted mt-0.5"
              >
                {{ agent.description }}
              </div>
              <div class="text-xs text-neutral-500 mt-3">
                {{ agent.modelProvider }} / {{ agent.modelId }}
              </div>
              <ModelCapabilityPills
                :model="modelForAgent(agent)"
                :thinking-mode="agent.thinkingMode"
                class="mt-2"
                @toggle="(cap) => toggleListingCapability(agent, cap)"
              />
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
      <div
        class="bg-surface-elevated border border-border p-4"
        data-tour="agent-edit-form"
      >
        <div class="flex items-center justify-between mb-4 gap-2">
          <h2 class="text-sm font-medium text-fg-strong">
            {{ creating ? 'New Agent' : 'Edit Agent' }}
          </h2>
          <button
            v-if="editing"
            class="px-2.5 py-1 text-[11px] font-medium text-emerald-700 dark:text-emerald-400
                   bg-emerald-500/10 border border-emerald-600/30 hover:bg-emerald-500/20
                   hover:text-emerald-600 dark:hover:text-emerald-300 hover:border-emerald-600
                   dark:hover:border-emerald-500/50 transition-colors"
            title="Inspect the system prompt this agent receives — per-section char + token breakdown"
            @click="openPromptBreakdown(editing)"
          >
            Inspect prompt
          </button>
        </div>
        <div class="grid grid-cols-2 gap-x-4 gap-y-5">
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
            :for="agentDescriptionId"
            class="block"
          >
            <span class="block text-xs text-neutral-500 mb-1">Description</span>
            <input
              :id="agentDescriptionId"
              v-model="form.description"
              maxlength="255"
              placeholder="What is this agent for?"
              class="w-full px-3 py-2 bg-muted border border-input text-sm text-fg-strong focus:outline-hidden focus:border-ring"
            >
          </label>
          <label
            :for="agentProviderId"
            class="block"
          >
            <span class="block text-xs text-neutral-500 mb-1">Default Provider</span>
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
            <span class="block text-xs text-neutral-500 mb-1">Default Model</span>
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
        </div>
        <ModelCapabilityPills
          :model="selectedModel"
          :thinking-mode="form.thinkingMode"
          size="md"
          class="mt-5"
          @toggle="toggleFormCapability"
        />
        <div class="flex mt-4">
          <button
            :disabled="saving || !formDirty || !form.name || !form.modelProvider || !form.modelId"
            class="p-1.5 text-emerald-700 dark:text-emerald-400 hover:text-emerald-600 dark:hover:text-emerald-300 disabled:opacity-40 disabled:hover:text-emerald-700 dark:disabled:hover:text-emerald-400 transition-colors"
            :title="saving ? 'Saving...' : formDirty ? 'Save' : 'No changes to save'"
            @click="saveAgent"
          >
            <Save
              class="w-5 h-5"
              aria-hidden="true"
            />
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
          <ChevronRightIcon
            class="w-3 h-3 text-neutral-500 transition-transform"
            :class="allowlistExpanded ? 'rotate-90' : ''"
            aria-hidden="true"
          />
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

      <!-- Skills -->
      <div
        v-if="editing"
        class="bg-surface-elevated border border-border"
      >
        <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
          <div class="flex items-center gap-2">
            <span class="text-sm font-medium text-fg-strong">Skills</span>
            <span class="text-xs text-neutral-500">{{ toggleableAgentSkills.filter(s => s.enabled).length }}/{{ toggleableAgentSkills.length }} enabled</span>
          </div>
          <button
            v-if="toggleableAgentSkills.length"
            :title="allAgentSkillsEnabled ? 'Disable all skills for this agent' : 'Enable all skills for this agent'"
            class="shrink-0"
            @click="toggleAllAgentSkills()"
          >
            <div
              class="relative w-9 h-5 rounded-full transition-colors duration-200"
              :class="allAgentSkillsEnabled ? 'bg-emerald-500' : 'bg-muted'"
            >
              <div
                class="absolute top-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-all duration-200"
                :class="allAgentSkillsEnabled ? 'left-[18px]' : 'left-0.5'"
              />
            </div>
          </button>
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
            v-for="catGroup in toolsByCategory"
            :key="catGroup.category"
          >
            <!-- Category header row -->
            <div class="px-4 py-1.5 border-b border-border bg-surface-elevated">
              <span
                class="text-[10px] font-semibold uppercase tracking-widest"
                :class="{
                  'text-neutral-500': catGroup.category === 'System',
                  'text-amber-500/70': catGroup.category === 'Files',
                  'text-blue-500/70': catGroup.category === 'Web',
                  'text-emerald-500/70': catGroup.category === 'Utilities',
                  'text-violet-500/70': catGroup.category === 'MCP',
                }"
              >
                {{ catGroup.category }}
              </span>
            </div>
            <div class="divide-y divide-border">
              <!-- Group rows: one per MCP server, single toggle flips every tool the server contributes via the bulk endpoint -->
              <div
                v-for="row in catGroup.rows"
                :key="row.key"
                class="px-4 py-3 flex items-center gap-3"
              >
                <template v-if="row.kind === 'group'">
                  <!-- Violet plug icon for MCP groups, matching the /tools page MCP card -->
                  <div class="w-8 h-8 rounded flex items-center justify-center shrink-0 bg-violet-500/15">
                    <PuzzlePieceIcon
                      class="w-4 h-4 text-violet-400"
                      aria-hidden="true"
                    />
                  </div>
                  <div class="flex-1 min-w-0">
                    <span class="text-sm text-fg-strong font-mono">{{ row.group }}</span>
                    <div class="mt-1.5">
                      <span class="text-[10px] font-mono px-1.5 py-0.5 border rounded-sm bg-violet-500/10 border-violet-500/25 text-violet-400">
                        {{ row.functionCount }} function{{ row.functionCount === 1 ? '' : 's' }}
                      </span>
                    </div>
                  </div>
                  <button
                    :title="row.enabled ? `Disable ${row.group} for this agent` : `Enable ${row.group} for this agent`"
                    :aria-label="row.enabled ? `Disable ${row.group} for this agent` : `Enable ${row.group} for this agent`"
                    class="shrink-0"
                    @click="toggleToolGroup(row.group, !row.enabled)"
                  >
                    <div
                      class="relative w-9 h-5 rounded-full transition-colors duration-200"
                      :class="row.enabled ? 'bg-emerald-500' : 'bg-muted'"
                    >
                      <div
                        class="absolute top-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-all duration-200"
                        :class="row.enabled ? 'left-[18px]' : 'left-0.5'"
                      />
                    </div>
                  </button>
                </template>
                <template v-else>
                  <!-- Single native tool: existing render -->
                  <div
                    class="w-8 h-8 rounded flex items-center justify-center shrink-0"
                    :class="getToolMeta(row.tool.name)?.iconBg ?? 'bg-muted'"
                  >
                    <component
                      :is="toolIconComponent(row.tool.name)"
                      v-if="toolIconComponent(row.tool.name)"
                      class="w-4 h-4"
                      :class="getToolMeta(row.tool.name)?.iconColor ?? 'text-fg-muted'"
                      aria-hidden="true"
                    />
                  </div>
                  <div class="flex-1 min-w-0">
                    <span class="text-sm text-fg-strong font-mono">{{ row.tool.name }}</span>
                    <div class="flex flex-wrap gap-1 mt-1.5">
                      <span
                        v-for="fn in (getToolMeta(row.tool.name)?.functions ?? [])"
                        :key="fn.name"
                        class="text-[10px] font-mono px-1.5 py-0.5 border rounded-sm"
                        :class="getPillClass(row.tool.name)"
                      >
                        {{ fn.name }}
                      </span>
                    </div>
                  </div>
                  <button
                    :title="row.tool.enabled ? 'Disable tool for this agent' : 'Enable tool for this agent'"
                    class="shrink-0"
                    @click="row.tool.enabled = !row.tool.enabled; toggleTool(row.tool.name, row.tool.enabled)"
                  >
                    <div
                      class="relative w-9 h-5 rounded-full transition-colors duration-200"
                      :class="row.tool.enabled ? 'bg-emerald-500' : 'bg-muted'"
                    >
                      <div
                        class="absolute top-0.5 w-4 h-4 rounded-full bg-white shadow-sm transition-all duration-200"
                        :class="row.tool.enabled ? 'left-[18px]' : 'left-0.5'"
                      />
                    </div>
                  </button>
                </template>
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
            :disabled="!workspaceDirty"
            class="p-1.5 text-emerald-700 dark:text-emerald-400 hover:text-emerald-600 dark:hover:text-emerald-300 disabled:opacity-40 disabled:hover:text-emerald-700 dark:disabled:hover:text-emerald-400 transition-colors"
            :title="workspaceDirty ? 'Save file' : 'No changes to save'"
            @click="saveWorkspaceFile"
          >
            <Save
              class="w-5 h-5"
              aria-hidden="true"
            />
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
            <label
              for="prompt-breakdown-channel"
              class="flex items-center gap-1.5 text-[10px] text-fg-muted"
            >
              <span>channel</span>
              <select
                id="prompt-breakdown-channel"
                v-model="promptBreakdownChannel"
                class="px-2 py-1 bg-muted border border-input text-[10px] text-fg-strong
                       focus:outline-hidden focus:border-ring transition-colors"
                title="Preview the prompt as assembled for a specific channel"
                @change="loadPromptBreakdown()"
              >
                <option value="web">
                  web
                </option>
                <option value="telegram">
                  telegram
                </option>
                <option value="slack">
                  slack
                </option>
                <option value="whatsapp">
                  whatsapp
                </option>
              </select>
            </label>
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
              <XMarkIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
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
            <table class="w-full text-xs font-mono table-fixed">
              <colgroup>
                <col>
                <col class="w-24">
                <col class="w-24">
                <col class="w-24">
              </colgroup>
              <thead class="text-[10px] text-neutral-500 border-b border-border">
                <tr>
                  <th class="text-left py-1 pr-2">
                    <button
                      type="button"
                      class="inline-flex items-center gap-1 whitespace-nowrap hover:text-fg-strong transition-colors"
                      :class="sectionsSortBy === 'name' ? 'text-fg-strong' : ''"
                      @click="cycleSectionsSort('name')"
                    >
                      Section
                      <ChevronUpIcon
                        v-if="sectionsSortBy === 'name' && sectionsSortDir === 'asc'"
                        class="w-3 h-3"
                        aria-hidden="true"
                      />
                      <ChevronDownIcon
                        v-else-if="sectionsSortBy === 'name' && sectionsSortDir === 'desc'"
                        class="w-3 h-3"
                        aria-hidden="true"
                      />
                    </button>
                  </th>
                  <th class="text-right py-1 px-2">
                    <button
                      type="button"
                      class="inline-flex items-center gap-1 whitespace-nowrap hover:text-fg-strong transition-colors"
                      :class="sectionsSortBy === 'chars' ? 'text-fg-strong' : ''"
                      @click="cycleSectionsSort('chars')"
                    >
                      Chars
                      <ChevronUpIcon
                        v-if="sectionsSortBy === 'chars' && sectionsSortDir === 'asc'"
                        class="w-3 h-3"
                        aria-hidden="true"
                      />
                      <ChevronDownIcon
                        v-else-if="sectionsSortBy === 'chars' && sectionsSortDir === 'desc'"
                        class="w-3 h-3"
                        aria-hidden="true"
                      />
                    </button>
                  </th>
                  <th class="text-right py-1 px-2 whitespace-nowrap">
                    ≈ Tokens
                  </th>
                  <th class="text-right py-1 pl-2 whitespace-nowrap">
                    % of total
                  </th>
                </tr>
              </thead>
              <tbody>
                <template
                  v-for="s in sortedSections"
                  :key="'section-' + s.name"
                >
                  <tr
                    class="border-b border-neutral-900/50"
                    :class="s.name === 'Skills' && promptBreakdownData.skills.length > 0 ? 'cursor-pointer hover:bg-neutral-900/30' : ''"
                    @click="s.name === 'Skills' && promptBreakdownData.skills.length > 0 ? (skillsExpanded = !skillsExpanded) : null"
                  >
                    <td class="py-1 pr-2 text-fg-primary">
                      {{ s.name }}<ChevronRightIcon
                        v-if="s.name === 'Skills' && promptBreakdownData.skills.length > 0"
                        class="inline-block w-2.5 h-2.5 ml-1 align-middle transition-transform"
                        :class="skillsExpanded ? 'rotate-90' : ''"
                        aria-hidden="true"
                      />
                    </td>
                    <td class="py-1 px-2 text-right text-fg-muted">
                      {{ formatChars(s.chars) }}
                    </td>
                    <td class="py-1 px-2 text-right text-amber-300/80">
                      {{ formatTokens(s.tokens) }}
                    </td>
                    <td class="py-1 pl-2 text-right text-emerald-700 dark:text-emerald-400">
                      {{ percentOfTotal(s.chars, promptBreakdownData.totalChars) }}
                    </td>
                  </tr>
                  <!-- Inlined skill itemization: indented child rows under
                       the Skills section row. Chars/tokens are *inside*
                       the Skills row's totals, not additive — same pattern
                       as the dashboard's per-model rollup under Total.
                       The trailing "matching instructions" row absorbs
                       the prose preamble + <available_skills> wrapper so
                       all subrows together equal the parent. -->
                  <tr
                    v-for="sk in (s.name === 'Skills' && skillsExpanded ? promptBreakdownData.skills : [])"
                    :key="'skill-' + sk.name"
                  >
                    <td class="py-0.5 pr-2 pl-6 text-fg-muted text-[11px]">
                      <span class="text-neutral-600 mr-1">└</span>{{ sk.name }}
                    </td>
                    <td class="py-0.5 px-2 text-right text-fg-muted text-[11px]">
                      {{ formatChars(sk.chars) }}
                    </td>
                    <td class="py-0.5 px-2 text-right text-amber-300/60 text-[11px]">
                      {{ formatTokens(sk.tokens) }}
                    </td>
                    <td class="py-0.5 pl-2 text-right text-[11px]" />
                  </tr>
                  <tr
                    v-if="s.name === 'Skills' && skillsExpanded && promptBreakdownData.skills.length > 0 && skillsMatchingGap.chars > 0"
                    class="border-b border-neutral-900/50"
                  >
                    <td class="py-0.5 pr-2 pl-6 text-fg-muted text-[11px]">
                      <span class="text-neutral-600 mr-1">└</span>matching instructions
                    </td>
                    <td class="py-0.5 px-2 text-right text-fg-muted text-[11px]">
                      {{ formatChars(skillsMatchingGap.chars) }}
                    </td>
                    <td class="py-0.5 px-2 text-right text-amber-300/60 text-[11px]">
                      {{ formatTokens(skillsMatchingGap.tokens) }}
                    </td>
                    <td class="py-0.5 pl-2 text-right text-[11px]" />
                  </tr>
                </template>
                <tr class="border-t border-border">
                  <td class="py-1 pr-2 text-fg-primary font-semibold">
                    Total
                  </td>
                  <td class="py-1 px-2 text-right text-fg-primary font-semibold">
                    {{ formatChars(sectionsAggregate.chars) }}
                  </td>
                  <td class="py-1 px-2 text-right text-amber-300 font-semibold">
                    {{ formatTokens(sectionsAggregate.tokens) }}
                  </td>
                  <td class="py-1 pl-2 text-right text-emerald-700 dark:text-emerald-400 font-semibold">
                    {{ percentOfTotal(sectionsAggregate.chars, promptBreakdownData.totalChars) }}
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
            <table class="w-full text-xs font-mono table-fixed">
              <colgroup>
                <col>
                <col class="w-24">
                <col class="w-24">
                <col class="w-24">
              </colgroup>
              <thead class="text-[10px] text-neutral-500 border-b border-border">
                <tr>
                  <th class="text-left py-1 pr-2">
                    <button
                      type="button"
                      class="inline-flex items-center gap-1 whitespace-nowrap hover:text-fg-strong transition-colors"
                      :class="toolsSortBy === 'name' ? 'text-fg-strong' : ''"
                      @click="cycleToolsSort('name')"
                    >
                      Tool
                      <ChevronUpIcon
                        v-if="toolsSortBy === 'name' && toolsSortDir === 'asc'"
                        class="w-3 h-3"
                        aria-hidden="true"
                      />
                      <ChevronDownIcon
                        v-else-if="toolsSortBy === 'name' && toolsSortDir === 'desc'"
                        class="w-3 h-3"
                        aria-hidden="true"
                      />
                    </button>
                  </th>
                  <th class="text-right py-1 px-2">
                    <button
                      type="button"
                      class="inline-flex items-center gap-1 whitespace-nowrap hover:text-fg-strong transition-colors"
                      :class="toolsSortBy === 'chars' ? 'text-fg-strong' : ''"
                      @click="cycleToolsSort('chars')"
                    >
                      Chars
                      <ChevronUpIcon
                        v-if="toolsSortBy === 'chars' && toolsSortDir === 'asc'"
                        class="w-3 h-3"
                        aria-hidden="true"
                      />
                      <ChevronDownIcon
                        v-else-if="toolsSortBy === 'chars' && toolsSortDir === 'desc'"
                        class="w-3 h-3"
                        aria-hidden="true"
                      />
                    </button>
                  </th>
                  <th class="text-right py-1 px-2 whitespace-nowrap">
                    ≈ Tokens
                  </th>
                  <th class="text-right py-1 pl-2 whitespace-nowrap">
                    % of total
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr
                  v-for="t in sortedTools"
                  :key="'tool-' + t.name"
                  class="border-b border-neutral-900/50"
                >
                  <td class="py-1 pr-2 text-fg-primary">
                    {{ t.name }}
                  </td>
                  <td class="py-1 px-2 text-right text-fg-muted">
                    {{ formatChars(t.chars) }}
                  </td>
                  <td class="py-1 px-2 text-right text-amber-300/80">
                    {{ formatTokens(t.tokens) }}
                  </td>
                  <td class="py-1 pl-2 text-right text-emerald-700 dark:text-emerald-400">
                    {{ percentOfTotal(t.chars, promptBreakdownData.totalChars) }}
                  </td>
                </tr>
                <tr class="border-t border-border">
                  <td class="py-1 pr-2 text-fg-primary font-semibold">
                    Total
                  </td>
                  <td class="py-1 px-2 text-right text-fg-primary font-semibold">
                    {{ formatChars(toolSchemasAggregate.chars) }}
                  </td>
                  <td class="py-1 px-2 text-right text-amber-300 font-semibold">
                    {{ formatTokens(toolSchemasAggregate.tokens) }}
                  </td>
                  <td class="py-1 pl-2 text-right text-emerald-700 dark:text-emerald-400 font-semibold">
                    {{ percentOfTotal(toolSchemasAggregate.chars, promptBreakdownData.totalChars) }}
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
