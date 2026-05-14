<script setup lang="ts">
import {
  ArrowPathIcon,
  CodeBracketIcon,
  Cog6ToothIcon,
  CommandLineIcon,
  DocumentTextIcon,
  EyeIcon,
  FolderIcon,
  MagnifyingGlassIcon,
  TrashIcon,
} from '@heroicons/vue/24/outline'
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import type {
  Agent,
  AgentSkill,
  Skill,
  SkillFile,
  SkillFileContent,
  SkillFilesResponse,
  SkillToolRef,
} from '~/types/api'

marked.setOptions({ breaks: true, gfm: true })

// Tool-pill color map (shared with agents.vue) — keeps per-tool color coding
// consistent between the Agent detail page and these skill cards.
const { getPillClass } = useToolMeta()

const { data: skills, refresh: refreshSkills } = await useFetch<Skill[]>('/api/skills')
const { data: agents } = await useFetch<Agent[]>('/api/agents')

// Skills per agent, keyed by agent id
const agentSkillsMap = ref<Record<number, AgentSkill[]>>({})
const loadingAgents = ref(true)

async function loadAllAgentSkills() {
  loadingAgents.value = true
  const map: Record<number, AgentSkill[]> = {}
  if (agents.value?.length) {
    await Promise.all(agents.value.map(async (agent) => {
      try {
        map[agent.id] = await $fetch<AgentSkill[]>(`/api/agents/${agent.id}/skills`)
      }
      catch {
        map[agent.id] = []
      }
    }))
  }
  agentSkillsMap.value = map
  loadingAgents.value = false
}

watch(agents, () => loadAllAgentSkills(), { immediate: true })

// Panel filters — case-insensitive substring match against the displayed name.
// Skills filter on the canonical folderName (falling back to name); agents on
// `agent.name`. Both lists are alphabetized by display name first so that newly
// added items land in their correct slot rather than at the end.
const globalFilter = ref('')
const agentFilter = ref('')

// Locale-aware, case-insensitive comparator — matches user expectations across
// mixed-case names without surprising ASCII-order placements (e.g. "Z" before "a").
const byName = (a: string, b: string) => a.localeCompare(b, undefined, { sensitivity: 'base' })

// Structural skills are pre-installed and undeletable — they ship with JClaw
// rather than arriving via promotion. They pin to the top of the global list
// (above the "CUSTOM SKILLS" divider) in a fixed canonical order: skill-creator
// is the seed every other skill promotes through, jclaw-api wraps the in-process
// JClaw-API tool that's main-agent-only by backend policy (AgentService disables
// the embedded tool on non-main agents at creation time). Single source of truth
// for the check + ordering so the sort, the trash-hide, and the divider's v-if
// can't drift apart. Lower number sorts first; Infinity falls into the
// alphabetical tail.
const STRUCTURAL_SKILL_ORDER: Record<string, number> = {
  'skill-creator': 0,
  'jclaw-api': 1,
}
// Duck-typed param so the same predicate covers Skill (global list), AgentSkill
// (per-agent cards), and the edit modal's union of both. AgentSkill's
// `[key: string]: unknown` index signature would otherwise force the call sites
// to narrow before passing — only `folderName || name` is needed and both shapes
// supply (or are willing to default) those.
type SkillNameRef = { folderName?: string, name?: string } | null
const structuralOrder = (s?: SkillNameRef) => STRUCTURAL_SKILL_ORDER[(s?.folderName || s?.name) ?? ''] ?? Infinity
const isStructuralSkill = (s?: SkillNameRef) => structuralOrder(s) !== Infinity

const filteredSkills = computed(() => {
  const q = globalFilter.value.trim().toLowerCase()
  const matched = q
    ? (skills.value ?? []).filter(s => (s.folderName || s.name).toLowerCase().includes(q))
    : (skills.value ?? [])
  return [...matched].sort((a, b) => {
    const oa = structuralOrder(a)
    const ob = structuralOrder(b)
    if (oa !== ob) return oa - ob
    return byName(a.folderName || a.name, b.folderName || b.name)
  })
})

// Agents listing: main agent always first (when it survives the filter), then
// the remaining agents alphabetically. The main-first rule is structural — it
// reflects "this is the agent the user converses with by default" — so it sits
// outside the alphabetical ordering rather than being a special case for
// names starting with 'm'.
const filteredAgents = computed(() => {
  const q = agentFilter.value.trim().toLowerCase()
  const matched = q
    ? (agents.value ?? []).filter(a => a.name.toLowerCase().includes(q))
    : (agents.value ?? [])
  return [...matched].sort((a, b) => {
    if (a.isMain !== b.isMain) return a.isMain ? -1 : 1
    return byName(a.name, b.name)
  })
})

// Per-agent skill list, alphabetized by skill name. Memoized via computed so
// the sort doesn't run on every render — only when the underlying map changes.
const sortedAgentSkillsMap = computed<Record<number, AgentSkill[]>>(() => {
  const out: Record<number, AgentSkill[]> = {}
  for (const [agentId, list] of Object.entries(agentSkillsMap.value)) {
    out[Number(agentId)] = [...list].sort((a, b) => byName(a.name, b.name))
  }
  return out
})

// Confirm dialog (replaces native window.confirm for destructive actions)
const { confirm } = useConfirm()

// A skill being dragged can originate from either the global list or an agent
// card; the union here keeps drag metadata readable without threading two refs.
type DraggingSkill = Skill | AgentSkill

// Drag state — supports both directions
const dragging = ref<DraggingSkill | null>(null)
const dragSource = ref<'global' | 'agent' | null>(null)
const dragSourceAgentId = ref<number | null>(null)
const dropTarget = ref<number | null>(null)
const dropTargetGlobal = ref(false)

// Error banner for drag-drop failures (e.g. missing tools on target agent)
const dragError = ref<string | null>(null)
let dragErrorTimer: ReturnType<typeof setTimeout> | null = null
function showDragError(msg: string) {
  dragError.value = msg
  if (dragErrorTimer) clearTimeout(dragErrorTimer)
  dragErrorTimer = setTimeout(() => {
    dragError.value = null
  }, 8000)
}

// Info banner for non-error events (e.g. promote no-op when workspace matches global)
const infoBanner = ref<string | null>(null)
let infoBannerTimer: ReturnType<typeof setTimeout> | null = null
function showInfo(msg: string) {
  infoBanner.value = msg
  if (infoBannerTimer) clearTimeout(infoBannerTimer)
  infoBannerTimer = setTimeout(() => {
    infoBanner.value = null
  }, 6000)
}

onUnmounted(() => {
  if (dragErrorTimer) clearTimeout(dragErrorTimer)
  if (infoBannerTimer) clearTimeout(infoBannerTimer)
})

// Track multiple concurrent promotions by skill name (survives navigation via useState)
const promotingSkills = useState<Set<string>>('promotingSkills', () => new Set())

// Reconcile: clear any "promoting" indicators for skills that already exist globally.
// Handles SSE events missed due to timing, reconnect, or component remount.
watch(skills, (globalSkills) => {
  if (!globalSkills || promotingSkills.value.size === 0) return
  const globalNames = new Set(globalSkills.map(s => s.folderName || s.name))
  const stillPromoting = new Set<string>()
  for (const name of promotingSkills.value) {
    if (!globalNames.has(name)) stillPromoting.add(name)
  }
  if (stillPromoting.size !== promotingSkills.value.size) {
    promotingSkills.value = stillPromoting
  }
}, { immediate: true })

// SSE events from the skill-promotion pipeline carry the skill's folder name
// and optional status text. Narrowing at each handler avoids an `any` leak.
interface SkillPromoteEvent {
  skillName: string
  error?: string
  reason?: string
}

function asSkillPromoteEvent(data: unknown): SkillPromoteEvent {
  const d = (data ?? {}) as Partial<SkillPromoteEvent>
  return {
    skillName: typeof d.skillName === 'string' ? d.skillName : '',
    error: typeof d.error === 'string' ? d.error : undefined,
    reason: typeof d.reason === 'string' ? d.reason : undefined,
  }
}

// Listen for promotion completion via SSE
const { onEvent } = useEventBus()
onEvent('skill.promoted', (data) => {
  const evt = asSkillPromoteEvent(data)
  refreshSkills()
  loadAllAgentSkills()
  const s = new Set(promotingSkills.value)
  s.delete(evt.skillName)
  promotingSkills.value = s
})
onEvent('skill.promote_failed', (data) => {
  const evt = asSkillPromoteEvent(data)
  console.error('Skill promotion failed:', evt.skillName, evt.error)
  showDragError(evt.error || `Failed to promote '${evt.skillName}'`)
  const s = new Set(promotingSkills.value)
  s.delete(evt.skillName)
  promotingSkills.value = s
})
onEvent('skill.promote_noop', (data) => {
  const evt = asSkillPromoteEvent(data)
  showInfo(evt.reason || `Nothing to promote for '${evt.skillName}'`)
  const s = new Set(promotingSkills.value)
  s.delete(evt.skillName)
  promotingSkills.value = s
})

// Look up the current global version of a skill by folder name
function globalVersionOf(folderName: string): string | null {
  const s = skills.value?.find(x => (x.folderName || x.name) === folderName)
  return s?.version ?? null
}

// Simple semver compare: returns negative/0/positive
function compareVersions(a: string, b: string): number {
  const pa = (a || '0.0.0').split('.').map(n => Number.parseInt(n) || 0)
  const pb = (b || '0.0.0').split('.').map(n => Number.parseInt(n) || 0)
  for (let i = 0; i < 3; i++) {
    const x = pa[i] || 0
    const y = pb[i] || 0
    if (x !== y) return x - y
  }
  return 0
}

// Returns the global version if an update is available, else null
function updateAvailable(skill: AgentSkill): string | null {
  const gv = globalVersionOf(skill.folderName as string || skill.name)
  if (gv == null) return null
  return compareVersions(skill.version as string || '0.0.0', gv) < 0 ? gv : null
}

// Extract a human-readable message from a $fetch error, which may carry the
// server-rendered text on either `.response._data` or `.data`.
function asFetchErrorMessage(err: unknown, fallback: string): string {
  const e = err as { response?: { status?: number, _data?: unknown }, data?: unknown, message?: string } | undefined
  const msg = e?.response?._data ?? e?.data ?? e?.message
  return typeof msg === 'string' ? msg : fallback
}

async function updateAgentSkillFromGlobal(agentId: number, skill: AgentSkill) {
  const skillName = (skill.folderName as string) || skill.name
  try {
    await $fetch(`/api/agents/${agentId}/skills/${skillName}/copy`, { method: 'POST' })
    agentSkillsMap.value[agentId] = await $fetch<AgentSkill[]>(`/api/agents/${agentId}/skills`)
    showInfo(`Updated '${skillName}' for this agent`)
  }
  catch (err: unknown) {
    console.error('Failed to update skill from global:', err)
    showDragError(asFetchErrorMessage(err, 'Failed to update skill from global'))
  }
}

// --- Global skill → Agent card (copy to workspace) ---

function onGlobalDragStart(e: DragEvent, skill: Skill) {
  dragging.value = skill
  dragSource.value = 'global'
  dragSourceAgentId.value = null
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'copy'
    e.dataTransfer.setData('text/plain', skill.name)
  }
}

function onAgentSkillDragStart(e: DragEvent, skill: AgentSkill, agentId: number) {
  dragging.value = skill
  dragSource.value = 'agent'
  dragSourceAgentId.value = agentId
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'copy'
    e.dataTransfer.setData('text/plain', skill.name)
  }
}

function onDragEnd() {
  dragging.value = null
  dragSource.value = null
  dragSourceAgentId.value = null
  dropTarget.value = null
  dropTargetGlobal.value = false
}

function onAgentDragOver(e: DragEvent, agentId: number) {
  if (dragSource.value !== 'global') return
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  dropTarget.value = agentId
}

function onAgentDragLeave(agentId: number) {
  if (dropTarget.value === agentId) dropTarget.value = null
}

async function onAgentDrop(e: DragEvent, agent: Agent) {
  e.preventDefault()
  dropTarget.value = null
  if (!dragging.value || dragSource.value !== 'global') return

  const dragged = dragging.value
  const skillName = (dragged.folderName as string) || dragged.name
  const globalVersion = (dragged.version as string) || '0.0.0'
  const existing = agentSkillsMap.value[agent.id]?.find(s => s.name === dragged.name)

  // If the agent already has this skill at the same or newer version, just ensure it's
  // enabled and move on — no point overwriting identical content.
  if (existing) {
    const cmp = compareVersions((existing.version as string) || '0.0.0', globalVersion)
    if (cmp >= 0) {
      if (!existing.enabled) {
        try {
          await $fetch(`/api/agents/${agent.id}/skills/${skillName}`, {
            method: 'PUT', body: { enabled: true },
          })
          agentSkillsMap.value[agent.id] = await $fetch<AgentSkill[]>(`/api/agents/${agent.id}/skills`)
        }
        catch (err) {
          console.error('Failed to re-enable skill:', err)
        }
      }
      dragging.value = null
      return
    }
    // Existing is older — confirm replacement
    const ok = await confirm({
      title: 'Replace skill',
      message:
        `Agent '${agent.name}' has '${skillName}' at version ${existing.version || '0.0.0'}.\n`
        + `Replace with global version ${globalVersion}?`,
      confirmText: 'Replace',
    })
    if (!ok) {
      dragging.value = null
      return
    }
  }

  try {
    await $fetch(`/api/agents/${agent.id}/skills/${skillName}/copy`, { method: 'POST' })
    agentSkillsMap.value[agent.id] = await $fetch<AgentSkill[]>(`/api/agents/${agent.id}/skills`)
    if (existing) showInfo(`Updated '${skillName}' on agent '${agent.name}' to version ${globalVersion}`)
  }
  catch (err: unknown) {
    // Surface the server's plain-text error message regardless of status code.
    // The backend uses renderText for both 400 (validation/scan failures) and
    // 500 (IOException during copy), so the body is always parseable here.
    console.error('Failed to copy skill:', err)
    showDragError(asFetchErrorMessage(err, 'Failed to add skill to agent.'))
  }
  dragging.value = null
}

// --- Agent skill → Global section (promote with LLM sanitization) ---

function onGlobalSectionDragOver(e: DragEvent) {
  if (dragSource.value !== 'agent') return
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  dropTargetGlobal.value = true
}

function onGlobalSectionDragLeave() {
  dropTargetGlobal.value = false
}

async function onGlobalSectionDrop(e: DragEvent) {
  e.preventDefault()
  dropTargetGlobal.value = false
  if (!dragging.value || dragSource.value !== 'agent' || dragSourceAgentId.value === null) return

  const skillName = (dragging.value.folderName as string) || dragging.value.name
  const agentId = dragSourceAgentId.value
  dragging.value = null

  // Skip if already promoting this skill
  if (promotingSkills.value.has(skillName)) return

  // If a global skill with this name already exists, confirm replacement before
  // firing the promote — the backend will overwrite it in place.
  const existingGlobal = skills.value?.find(s => (s.folderName || s.name) === skillName)
  if (existingGlobal) {
    const ok = await confirm({
      title: 'Replace global skill',
      message:
        `A global skill named '${skillName}' already exists.\n\n`
        + `Promoting will replace it with the sanitized version from the agent workspace. Continue?`,
      confirmText: 'Promote',
    })
    if (!ok) return
  }

  // Add to in-progress set and run in background (non-blocking)
  promotingSkills.value = new Set([...promotingSkills.value, skillName])

  // Send promote request — returns immediately, SSE event will notify on completion
  $fetch('/api/skills/promote', {
    method: 'POST',
    body: { agentId, skillName },
  }).catch((err) => {
    console.error('Failed to promote skill:', err)
    const s = new Set(promotingSkills.value)
    s.delete(skillName)
    promotingSkills.value = s
  })
}

// --- Global skill inline rename ---

const renamingSkill = ref<string | null>(null)
const renameValue = ref('')

function startRename(skill: Skill) {
  renamingSkill.value = skill.folderName || skill.name
  renameValue.value = skill.folderName || skill.name
}

function cancelRename() {
  renamingSkill.value = null
  renameValue.value = ''
}

async function commitRename(skill: Skill) {
  const oldName = skill.folderName || skill.name
  const newName = renameValue.value.trim()
  if (!newName || newName === oldName) {
    cancelRename()
    return
  }
  try {
    await $fetch(`/api/skills/${oldName}/rename`, {
      method: 'PUT', body: { newName },
    })
    refreshSkills()
  }
  catch (err: unknown) {
    console.error('Failed to rename skill:', err)
  }
  finally {
    renamingSkill.value = null
    renameValue.value = ''
  }
}

// --- Skill editing (create / edit form) ---

// The editing target is whichever skill was clicked — global Skill or per-agent
// AgentSkill. Both flows set `folderName` explicitly so the API path is stable.
const editing = ref<(Skill | AgentSkill) & { folderName?: string } | null>(null)

// Feed the layout breadcrumb: show "Skills > {name}" when a skill is open;
// reverse direction closes the viewer when the layout clears the extra (user
// clicked the "Skills" crumb while already on /skills).
const breadcrumbExtra = useBreadcrumbExtra()
watch(editing, (skill) => {
  breadcrumbExtra.value = skill ? (skill.folderName ?? skill.name) : null
}, { immediate: true })
watch(breadcrumbExtra, (value) => {
  if (value === null && editing.value) {
    editing.value = null
  }
})
onUnmounted(() => {
  breadcrumbExtra.value = null
})

// File browser state for view mode — skill file contents are read-only; authoring
// happens exclusively via the skill-creator skill (using the filesystem tool).
const skillFiles = ref<SkillFile[]>([])
const skillTools = ref<SkillToolRef[]>([])
// Shell commands this skill contributes to an installing agent's allowlist.
// Populated from the SKILL.md `commands:` frontmatter via the files API.
const skillCommands = ref<string[]>([])
// Agent name recorded in the SKILL.md `author:` frontmatter. Empty string for
// legacy skills that predate the field — the header suppresses the attribution
// rather than guessing.
const skillAuthor = ref<string>('')
const activeFile = ref<string | null>(null)
const fileContent = ref('')
const fileViewMode = ref<'raw' | 'rendered'>('rendered')

const isMarkdownFile = computed(() => activeFile.value?.endsWith('.md') ?? false)

const renderedMarkdown = computed(() => {
  if (!isMarkdownFile.value || !fileContent.value) return ''
  return DOMPurify.sanitize(marked.parse(fileContent.value) as string)
})
const editingAgentId = ref<number | null>(null) // null = global skill, number = agent workspace skill

async function editSkill(skill: Skill) {
  try {
    const folderName = skill.folderName || skill.name
    editing.value = { ...skill, folderName }

    // Load file listing and tool dependencies
    const res = await $fetch<SkillFilesResponse>(`/api/skills/${folderName}/files`)
    skillFiles.value = res.files || []
    skillTools.value = res.tools || []
    skillCommands.value = res.commands || []
    skillAuthor.value = res.author || ''

    // Auto-select SKILL.md
    const skillMd = skillFiles.value.find(f => f.path === 'SKILL.md')
    if (skillMd) {
      await selectFile(skillMd)
    }
    else if (skillFiles.value.length > 0 && skillFiles.value[0]!.isText) {
      await selectFile(skillFiles.value[0]!)
    }
    else {
      activeFile.value = null
      fileContent.value = ''
    }
  }
  catch (e) {
    console.error('Failed to load skill:', e)
  }
}

function skillFileApiBase() {
  const folderName = editing.value?.folderName || editing.value?.name
  if (editingAgentId.value != null) {
    return `/api/agents/${editingAgentId.value}/skills/${folderName}/files`
  }
  return `/api/skills/${folderName}/files`
}

async function selectFile(file: SkillFile) {
  if (!file.isText) return
  try {
    const res = await $fetch<SkillFileContent>(`${skillFileApiBase()}/${file.path}`)
    activeFile.value = file.path
    fileContent.value = res.content
  }
  catch (e) {
    console.error('Failed to read file:', e)
  }
}

async function deleteSkill(skill: Skill | AgentSkill) {
  const folderName = (skill.folderName as string) || skill.name
  try {
    await $fetch(`/api/skills/${folderName}`, { method: 'DELETE' })
    editing.value = null
    skillFiles.value = []
    activeFile.value = null
    refreshSkills()
    loadAllAgentSkills()
  }
  catch (e) {
    console.error('Failed to delete skill:', e)
  }
}

async function editAgentSkill(agentId: number, skill: AgentSkill) {
  try {
    const name = (skill.folderName as string) || skill.name
    editing.value = { ...skill, folderName: name }
    editingAgentId.value = agentId

    const res = await $fetch<SkillFilesResponse>(`/api/agents/${agentId}/skills/${name}/files`)
    skillFiles.value = res.files || []
    skillTools.value = res.tools || []
    skillCommands.value = res.commands || []
    skillAuthor.value = res.author || ''

    const skillMd = skillFiles.value.find(f => f.path === 'SKILL.md')
    if (skillMd) {
      await selectFile(skillMd)
    }
    else if (skillFiles.value.length > 0 && skillFiles.value[0]!.isText) {
      await selectFile(skillFiles.value[0]!)
    }
    else {
      activeFile.value = null
      fileContent.value = ''
    }
  }
  catch (e) {
    console.error('Failed to load agent skill:', e)
  }
}

async function deleteAgentSkill(agentId: number, skill: Skill | AgentSkill) {
  const name = (skill.folderName as string) || skill.name
  try {
    await $fetch(`/api/agents/${agentId}/skills/${name}/delete`, { method: 'DELETE' })
    editing.value = null
    editingAgentId.value = null
    skillFiles.value = []
    skillTools.value = []
    skillCommands.value = []
    skillAuthor.value = ''
    activeFile.value = null
    loadAllAgentSkills()
  }
  catch (e) {
    console.error('Failed to delete agent skill:', e)
  }
}

function cancel() {
  editing.value = null
  editingAgentId.value = null
  skillFiles.value = []
  skillTools.value = []
  activeFile.value = null
}

type FileNode = {
  name: string
  isDir: boolean
  path?: string
  file?: SkillFile
  children?: FileNode[]
}

const fileTree = computed<FileNode[]>(() => {
  const root: FileNode = { name: '', isDir: true, children: [] }
  for (const file of skillFiles.value) {
    const parts = (file.path ?? '').split('/').filter(Boolean)
    if (!parts.length) continue
    let node = root
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i]!
      const isLast = i === parts.length - 1
      if (isLast) {
        node.children!.push({ name: part, isDir: false, path: file.path, file })
      }
      else {
        let dir = node.children!.find(c => c.isDir && c.name === part)
        if (!dir) {
          dir = { name: part, isDir: true, children: [] }
          node.children!.push(dir)
        }
        node = dir
      }
    }
  }
  const sortNode = (n: FileNode) => {
    if (!n.children) return
    n.children.sort((a, b) => {
      if (a.isDir !== b.isDir) return a.isDir ? -1 : 1
      return a.name.localeCompare(b.name)
    })
    n.children.forEach(sortNode)
  }
  sortNode(root)
  return root.children!
})

function enabledSkillCount(agentId: number) {
  return agentSkillsMap.value[agentId]?.filter(s => s.enabled).length ?? 0
}

function totalSkillCount(agentId: number) {
  return agentSkillsMap.value[agentId]?.length ?? 0
}
</script>

<template>
  <div class="h-full flex flex-col">
    <div class="flex items-center justify-between mb-6 shrink-0">
      <h1 class="text-lg font-semibold text-fg-strong">
        Skills
      </h1>
    </div>

    <div
      v-if="dragError"
      class="mb-4 px-3 py-2 bg-red-50 dark:bg-red-950/40 border border-red-200 dark:border-red-900/60 text-red-700 dark:text-red-300 text-xs flex items-start justify-between gap-3 shrink-0"
    >
      <span>{{ dragError }}</span>
      <button
        class="text-red-600 dark:text-red-400 hover:text-red-800 dark:hover:text-red-200 shrink-0"
        title="Dismiss"
        @click="dragError = null"
      >
        ×
      </button>
    </div>

    <div
      v-if="infoBanner"
      class="mb-4 px-3 py-2 bg-blue-50 dark:bg-blue-950/40 border border-blue-200 dark:border-blue-900/60 text-blue-700 dark:text-blue-300 text-xs flex items-start justify-between gap-3 shrink-0"
    >
      <span>{{ infoBanner }}</span>
      <button
        class="text-blue-600 dark:text-blue-400 hover:text-blue-800 dark:hover:text-blue-200 shrink-0"
        title="Dismiss"
        @click="infoBanner = null"
      >
        ×
      </button>
    </div>

    <template v-if="!editing">
      <!--
        Two-column layout: Global Skills (left) | Agents (right).
        Both columns are flex-1 within a min-h-0 grid so each panel grows to
        consume the layout's available height; their inner bodies own the
        vertical scroll, keeping the page header and column headers fixed.
        Drag-and-drop directions are unchanged from the prior grid layout — a
        global skill drops onto an agent (assign), an agent skill drops onto
        the left panel (promote).
      -->
      <div class="flex-1 min-h-0 grid grid-cols-1 md:grid-cols-2 gap-4">
        <!-- LEFT: Global Skills (draggable + drop target for promotion) -->
        <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -- drop target for drag-to-promote; HTML5 drag events have no keyboard equivalent -->
        <section
          data-tour="global-skills"
          :class="[
            'flex flex-col bg-surface-elevated border min-h-0 transition-all duration-150',
            dropTargetGlobal ? 'border-emerald-600 dark:border-emerald-500/60 bg-emerald-500/5 ring-1 ring-emerald-500/20' : 'border-border',
          ]"
          @dragover="onGlobalSectionDragOver"
          @dragleave="onGlobalSectionDragLeave"
          @drop="onGlobalSectionDrop"
        >
          <!-- Header: title + filter -->
          <div class="px-3 py-2.5 border-b border-border flex flex-col gap-2 shrink-0">
            <div class="flex items-center justify-between gap-2">
              <div class="flex items-center gap-2 min-w-0">
                <span class="text-xs text-fg-muted uppercase tracking-wider">Global Skills</span>
                <div
                  v-if="promotingSkills.size"
                  class="flex items-center gap-1 text-[10px] text-emerald-700 dark:text-emerald-400"
                >
                  <ArrowPathIcon
                    class="w-3 h-3 animate-spin"
                    aria-hidden="true"
                  />
                  Promoting {{ promotingSkills.size }}
                </div>
              </div>
              <span class="text-[10px] text-fg-muted shrink-0 tabular-nums">
                {{ filteredSkills.length }}{{ globalFilter ? ` / ${skills?.length ?? 0}` : '' }}
              </span>
            </div>
            <div class="flex items-center gap-2 px-2 py-1 bg-muted border border-input">
              <MagnifyingGlassIcon
                class="w-3.5 h-3.5 text-fg-muted shrink-0"
                aria-hidden="true"
              />
              <input
                v-model="globalFilter"
                placeholder="Filter skills..."
                aria-label="Filter global skills by name"
                class="flex-1 bg-transparent text-xs text-fg-strong placeholder-fg-muted focus:outline-hidden"
              >
              <button
                v-if="globalFilter"
                class="text-[10px] text-fg-muted hover:text-fg-strong shrink-0"
                title="Clear filter"
                @click="globalFilter = ''"
              >
                ×
              </button>
            </div>
          </div>

          <!-- Drop hint when dragging an agent skill over the panel -->
          <div
            v-if="dragSource === 'agent' && dropTargetGlobal"
            class="mx-3 mt-3 border border-dashed border-emerald-600 dark:border-emerald-500/40 py-2 text-center text-[10px] text-emerald-700 dark:text-emerald-400 shrink-0"
          >
            Release to promote (secrets will be stripped)
          </div>

          <!-- Empty state: no globals at all -->
          <div
            v-if="!skills?.length && !promotingSkills.size"
            class="flex-1 flex items-center justify-center px-4 py-6 text-center text-xs text-fg-muted"
          >
            No global skills. Create one via the skill-creator skill in an agent workspace, then drag it here to promote.
          </div>

          <!-- Skills list (compact rows, scrollable) -->
          <div
            v-else
            class="flex-1 overflow-y-auto"
          >
            <template
              v-for="(skill, index) in filteredSkills"
              :key="skill.folderName || skill.name"
            >
              <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -- drag source for skill assignment; HTML5 drag events have no keyboard equivalent -->
              <div
                draggable="true"
                :class="[
                  'group flex items-start gap-2 px-3 py-2 cursor-grab active:cursor-grabbing select-none transition-colors',
                  index > 0 && !isStructuralSkill(filteredSkills[index - 1]) ? 'border-t border-border' : '',
                  dragging?.name === skill.name && dragSource === 'global' ? 'opacity-50' : 'hover:bg-muted',
                ]"
                @dragstart="onGlobalDragStart($event, skill)"
                @dragend="onDragEnd"
              >
                <div class="min-w-0 flex-1">
                  <div class="flex items-center gap-2">
                    <span
                      v-if="skill.icon"
                      aria-hidden="true"
                      class="shrink-0 text-xs leading-none"
                    >{{ skill.icon }}</span>
                    <template v-if="renamingSkill === (skill.folderName || skill.name)">
                      <input
                        ref="renameInput"
                        v-model="renameValue"
                        :aria-label="`Rename skill ${skill.folderName || skill.name}`"
                        class="text-xs text-fg-strong font-mono bg-muted border border-input px-1.5 py-0.5 flex-1 focus:outline-hidden focus:border-emerald-600 dark:focus:border-emerald-500"
                        @keydown.enter="commitRename(skill)"
                        @keydown.escape="cancelRename"
                        @blur="commitRename(skill)"
                        @click.stop
                        @mousedown.stop
                      >
                    </template>
                    <template v-else>
                      <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -- double-click to rename is a desktop-style affordance; dblclick has no keyboard equivalent -->
                      <span
                        class="text-xs text-fg-strong font-mono truncate min-w-0 flex-1"
                        @dblclick.stop="startRename(skill)"
                      >{{ skill.folderName || skill.name }}</span>
                    </template>
                    <span class="text-[10px] text-emerald-700 dark:text-emerald-300 font-mono font-semibold shrink-0 bg-emerald-100 dark:bg-emerald-900/30 border border-emerald-200 dark:border-emerald-800/50 px-1 py-0.5">v{{ skill.version || '0.0.0' }}</span>
                  </div>
                  <div
                    v-if="skill.description"
                    class="text-[10px] text-fg-muted truncate mt-0.5"
                  >
                    {{ skill.description }}
                  </div>
                  <div
                    v-if="skill.tools?.length || skill.commands?.length"
                    class="flex items-center gap-2 mt-1 text-[10px] text-fg-muted"
                  >
                    <span v-if="skill.tools?.length">{{ skill.tools.length }} tool{{ skill.tools.length !== 1 ? 's' : '' }}</span>
                    <span v-if="skill.commands?.length">{{ skill.commands.length }} cmd{{ skill.commands.length !== 1 ? 's' : '' }}</span>
                  </div>
                </div>
                <div class="flex items-center gap-0.5 shrink-0 opacity-0 group-hover:opacity-100 transition-opacity">
                  <button
                    class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                    title="View skill"
                    @click.stop="editSkill(skill)"
                  >
                    <EyeIcon
                      class="w-3.5 h-3.5"
                      aria-hidden="true"
                    />
                  </button>
                  <button
                    v-if="!isStructuralSkill(skill)"
                    class="p-1 text-fg-muted hover:text-red-400 transition-colors"
                    title="Delete skill"
                    @click.stop="deleteSkill(skill)"
                  >
                    <TrashIcon
                      class="w-3.5 h-3.5"
                      aria-hidden="true"
                    />
                  </button>
                  <!-- Same-geometry spacer when the trash button is suppressed,
                       so the version-pill column aligns across all rows
                       regardless of how many actions the skill carries. -->
                  <span
                    v-else
                    aria-hidden="true"
                    class="p-1 inline-block"
                  >
                    <span class="block w-3.5 h-3.5" />
                  </span>
                </div>
              </div>

              <!-- Section divider after the last structural skill: same
                   labeled-rule pattern as the agents panel ("CUSTOM AGENTS"),
                   here naming the alphabetical group below "CUSTOM SKILLS".
                   Renders once, after the boundary between the pinned
                   structural skills and the user-promoted ones — i.e. when
                   the current row is structural and the next one isn't.
                   Decorative only — `aria-hidden` keeps it out of the
                   accessibility tree since the order is already conveyed
                   by the rendered list. -->
              <div
                v-if="isStructuralSkill(skill) && index < filteredSkills.length - 1 && !isStructuralSkill(filteredSkills[index + 1])"
                class="flex items-center gap-3 px-3 py-2.5 select-none"
                aria-hidden="true"
              >
                <span class="h-px flex-1 bg-input" />
                <span class="text-[10px] font-mono uppercase tracking-[0.15em] text-fg-muted">CUSTOM SKILLS</span>
                <span class="h-px flex-1 bg-input" />
              </div>
            </template>

            <!-- Empty filter result -->
            <div
              v-if="!filteredSkills.length && skills?.length"
              class="px-4 py-6 text-center text-xs text-fg-muted italic"
            >
              No skills match "{{ globalFilter }}"
            </div>
          </div>
        </section>

        <!-- RIGHT: Agents (drop targets for skill assignment) -->
        <section class="flex flex-col bg-surface-elevated border border-border min-h-0">
          <!-- Header: title + filter -->
          <div class="px-3 py-2.5 border-b border-border flex flex-col gap-2 shrink-0">
            <div class="flex items-center justify-between gap-2">
              <span class="text-xs text-fg-muted uppercase tracking-wider">Agent Skills</span>
              <span class="text-[10px] text-fg-muted shrink-0 tabular-nums">
                {{ filteredAgents.length }}{{ agentFilter ? ` / ${agents?.length ?? 0}` : '' }}
              </span>
            </div>
            <div class="flex items-center gap-2 px-2 py-1 bg-muted border border-input">
              <MagnifyingGlassIcon
                class="w-3.5 h-3.5 text-fg-muted shrink-0"
                aria-hidden="true"
              />
              <input
                v-model="agentFilter"
                placeholder="Filter agents..."
                aria-label="Filter agents by name"
                class="flex-1 bg-transparent text-xs text-fg-strong placeholder-fg-muted focus:outline-hidden"
              >
              <button
                v-if="agentFilter"
                class="text-[10px] text-fg-muted hover:text-fg-strong shrink-0"
                title="Clear filter"
                @click="agentFilter = ''"
              >
                ×
              </button>
            </div>
          </div>

          <!-- Empty state -->
          <div
            v-if="!agents?.length"
            class="flex-1 flex items-center justify-center px-4 py-6 text-sm text-fg-muted"
          >
            No agents configured
          </div>

          <!-- Agent list (each agent stacked vertically, scrollable). The main
               agent is followed by a labeled section divider — a small
               monospaced "CUSTOM AGENTS" caption flanked by thin rules — that reads as
               a typographic section break rather than yet another row
               separator. The row immediately after the divider skips its own
               top border so the divider's right-hand rule isn't doubled. -->
          <div
            v-else
            class="flex-1 overflow-y-auto"
          >
            <template
              v-for="(agent, index) in filteredAgents"
              :key="agent.id"
            >
              <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -- drop target for skill assignment; HTML5 drag events have no keyboard equivalent -->
              <div
                :class="[
                  'p-3 transition-all duration-150',
                  index > 0 && !filteredAgents[index - 1]?.isMain ? 'border-t border-border' : '',
                  dropTarget === agent.id ? 'bg-emerald-500/5 ring-1 ring-emerald-500/20 ring-inset' : '',
                ]"
                @dragover="onAgentDragOver($event, agent.id)"
                @dragleave="onAgentDragLeave(agent.id)"
                @drop="onAgentDrop($event, agent)"
              >
                <!-- Agent header -->
                <div class="flex items-center justify-between gap-2 mb-1">
                  <div class="flex items-center gap-2 min-w-0">
                    <span class="text-sm font-medium text-fg-strong truncate">{{ agent.name }}</span>
                    <span
                      v-if="agent.isMain"
                      class="text-[10px] text-fg-muted border border-input px-1 shrink-0"
                    >main</span>
                  </div>
                  <span class="text-[10px] text-fg-muted shrink-0 tabular-nums">
                    {{ enabledSkillCount(agent.id) }}/{{ totalSkillCount(agent.id) }}
                  </span>
                </div>
                <div class="text-[10px] text-fg-muted mb-2 truncate">
                  {{ agent.modelProvider }} / {{ agent.modelId }}
                </div>

                <!-- Agent's skills (draggable for promotion). Enable/disable
                   toggling lives on the Agents page; this panel is read-only
                   for the enabled state. -->
                <div
                  v-if="sortedAgentSkillsMap[agent.id]?.length"
                  class="space-y-1"
                >
                  <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -- drag source for skill promotion; HTML5 drag events have no keyboard equivalent -->
                  <div
                    v-for="skill in sortedAgentSkillsMap[agent.id]"
                    :key="skill.name"
                    draggable="true"
                    class="flex items-center justify-between px-2 py-1 bg-muted cursor-grab active:cursor-grabbing select-none group/skill"
                    @dragstart="onAgentSkillDragStart($event, skill, agent.id)"
                    @dragend="onDragEnd"
                  >
                    <div class="flex items-center gap-2 min-w-0 flex-1">
                      <span
                        v-if="skill.icon"
                        aria-hidden="true"
                        class="shrink-0 text-xs leading-none"
                      >{{ skill.icon }}</span>
                      <span class="text-xs text-fg-strong font-mono truncate">{{ skill.name }}</span>
                      <button
                        v-if="updateAvailable(skill)"
                        class="text-[9px] text-amber-700 dark:text-amber-400 border border-amber-300 dark:border-amber-700/40 bg-amber-50 dark:bg-amber-900/20 px-1.5 py-0.5 font-mono hover:bg-amber-100 dark:hover:bg-amber-900/40 transition-colors shrink-0"
                        :title="`Update to v${updateAvailable(skill)}`"
                        @click.stop="updateAgentSkillFromGlobal(agent.id, skill)"
                      >
                        → v{{ updateAvailable(skill) }}
                      </button>
                    </div>
                    <div class="flex items-center gap-1 shrink-0">
                      <span class="text-[10px] text-emerald-700 dark:text-emerald-300 font-mono font-semibold shrink-0 bg-emerald-100 dark:bg-emerald-900/30 border border-emerald-200 dark:border-emerald-800/50 px-1 py-0.5">v{{ skill.version || '0.0.0' }}</span>
                      <button
                        class="p-1 text-fg-muted hover:text-fg-strong transition-colors opacity-0 group-hover/skill:opacity-100"
                        title="View skill"
                        @click.stop="editAgentSkill(agent.id, skill)"
                      >
                        <EyeIcon
                          class="w-3.5 h-3.5"
                          aria-hidden="true"
                        />
                      </button>
                      <button
                        class="p-1 text-fg-muted hover:text-red-400 transition-colors opacity-0 group-hover/skill:opacity-100"
                        title="Delete skill"
                        @click.stop="deleteAgentSkill(agent.id, skill)"
                      >
                        <TrashIcon
                          class="w-3.5 h-3.5"
                          aria-hidden="true"
                        />
                      </button>
                    </div>
                  </div>
                </div>
                <div
                  v-else
                  class="text-[10px] text-fg-muted italic"
                >
                  {{ loadingAgents ? 'Loading...' : 'No skills assigned' }}
                </div>

                <!-- Drop hint (global → agent). Only shown while a global skill is
                   being dragged AND this agent isn't already the active drop target. -->
                <div
                  v-if="dragging && dragSource === 'global' && dropTarget !== agent.id"
                  class="mt-2 border border-dashed border-input py-1.5 text-center text-[10px] text-fg-muted"
                >
                  Drop skill here
                </div>
                <div
                  v-if="dropTarget === agent.id"
                  class="mt-2 border border-dashed border-emerald-600 dark:border-emerald-500/40 py-1.5 text-center text-[10px] text-emerald-700 dark:text-emerald-400"
                >
                  Release to assign
                </div>
              </div>

              <!-- Section divider after the main agent: a small monospaced
                   "CUSTOM AGENTS" label flanked by 1px rules in the same neutral
                   `bg-input` shade as the badge borders elsewhere on the
                   page. Decorative-only (`aria-hidden`) — the screen-reader
                   experience already conveys ordering through the rendered
                   list itself. -->
              <div
                v-if="agent.isMain && index < filteredAgents.length - 1"
                class="flex items-center gap-3 px-3 py-2.5 select-none"
                aria-hidden="true"
              >
                <span class="h-px flex-1 bg-input" />
                <span class="text-[10px] font-mono uppercase tracking-[0.15em] text-fg-muted">CUSTOM AGENTS</span>
                <span class="h-px flex-1 bg-input" />
              </div>
            </template>

            <!-- Empty filter result -->
            <div
              v-if="!filteredAgents.length && agents?.length"
              class="px-4 py-6 text-center text-xs text-fg-muted italic"
            >
              No agents match "{{ agentFilter }}"
            </div>
          </div>
        </section>
      </div>
    </template>

    <!-- Skill viewer — read-only file browser. Skills are created and updated via
         the skill-creator skill, not through this page. -->
    <div
      v-if="editing"
      class="space-y-4"
    >
      <div class="flex items-center justify-between">
        <button
          class="text-xs text-fg-muted hover:text-fg-strong transition-colors"
          @click="cancel"
        >
          &larr; Back to skills
        </button>
        <button
          v-if="!isStructuralSkill(editing)"
          class="p-1.5 text-red-400/70 hover:text-red-400 transition-colors"
          title="Delete skill"
          @click="editingAgentId != null ? deleteAgentSkill(editingAgentId, editing) : deleteSkill(editing)"
        >
          <TrashIcon
            class="w-5 h-5"
            aria-hidden="true"
          />
        </button>
      </div>

      <!-- Skill header -->
      <div class="bg-surface-elevated border border-border px-4 py-3">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 bg-emerald-100 dark:bg-emerald-900/30 border border-emerald-200 dark:border-emerald-800/40 rounded flex items-center justify-center">
            <FolderIcon
              class="w-4 h-4 text-emerald-700 dark:text-emerald-400"
              aria-hidden="true"
            />
          </div>
          <div>
            <div class="flex items-center gap-2">
              <span class="text-sm font-medium text-fg-strong font-mono">{{ editing.folderName || editing.name }}</span>
              <span
                v-if="editingAgentId != null"
                class="text-[10px] text-blue-400 border border-blue-400/30 px-1"
              >
                {{ agents?.find((a: any) => a.id === editingAgentId)?.name ?? 'agent' }}
              </span>
              <span
                v-else
                class="text-[10px] text-green-400 border border-green-400/30 px-1"
              >global</span>
            </div>
            <div class="text-xs text-fg-muted">
              {{ skillFiles.length }} file{{ skillFiles.length !== 1 ? 's' : '' }}
              <span
                v-if="skillAuthor"
                class="ml-2"
              >
                · by <span class="font-mono text-fg-muted">{{ skillAuthor }}</span>
              </span>
            </div>
          </div>
        </div>
      </div>

      <!-- Tool dependencies -->
      <div
        v-if="skillTools.length"
        class="bg-surface-elevated border border-border px-4 py-3"
      >
        <div class="flex items-center gap-2 mb-3">
          <Cog6ToothIcon
            class="w-4 h-4 text-amber-400"
            aria-hidden="true"
          />
          <span class="text-xs font-medium text-fg-muted uppercase tracking-wider">Required Tools</span>
        </div>
        <div class="flex flex-wrap gap-2">
          <span
            v-for="tool in skillTools"
            :key="tool.name"
            class="inline-flex items-center px-2.5 py-1 border rounded text-xs font-mono leading-none"
            :class="getPillClass(tool.name)"
          >
            {{ tool.name }}
          </span>
        </div>
      </div>

      <!--
        Shell commands this skill contributes to an installing agent's
        effective allowlist (from the `commands:` frontmatter). Rendered below
        Required Tools because tools are the dependencies the skill consumes
        while commands are the binaries it *provides* — consume-before-provide
        matches the reading order a reviewer uses to trust the skill.
      -->
      <div
        v-if="skillCommands.length"
        class="bg-surface-elevated border border-border px-4 py-3"
      >
        <div class="flex items-center gap-2 mb-3">
          <CommandLineIcon
            class="w-4 h-4 text-cyan-400"
            aria-hidden="true"
          />
          <span class="text-xs font-medium text-fg-muted uppercase tracking-wider">Commands</span>
          <span class="text-[10px] text-fg-muted normal-case tracking-normal">
            added to the agent's shell allowlist when this skill is installed
          </span>
        </div>
        <div class="flex flex-wrap gap-2">
          <span
            v-for="cmd in skillCommands"
            :key="cmd"
            class="inline-flex items-center px-2.5 py-1 bg-cyan-50 dark:bg-cyan-900/20 border border-cyan-200 dark:border-cyan-800/40 rounded text-xs font-mono text-cyan-700 dark:text-cyan-300 leading-none"
          >
            {{ cmd }}
          </span>
        </div>
      </div>

      <div
        class="flex gap-4"
        style="min-height: 500px;"
      >
        <!-- File sidebar -->
        <div class="w-52 shrink-0 bg-surface-elevated border border-border overflow-y-auto">
          <div class="px-3 py-2 border-b border-border">
            <span class="text-[10px] font-medium text-fg-muted uppercase tracking-wider">Files</span>
          </div>
          <SkillFileTree
            :nodes="fileTree"
            :active-path="activeFile"
            @select="selectFile"
          />
        </div>

        <!-- File editor -->
        <div class="flex-1 flex flex-col bg-surface-elevated border border-border min-w-0">
          <template v-if="activeFile">
            <div class="px-4 py-2 border-b border-border flex items-center gap-2">
              <span class="text-xs font-mono text-fg-muted">{{ activeFile }}</span>
              <span class="text-[10px] text-fg-muted">(read-only — edit via skill-creator)</span>
              <div
                v-if="isMarkdownFile"
                class="ml-auto flex items-center gap-0.5"
              >
                <button
                  :class="fileViewMode === 'rendered' ? 'text-fg-strong bg-muted' : 'text-fg-muted hover:text-fg-strong'"
                  class="p-1 rounded transition-colors"
                  title="Rendered markdown"
                  @click="fileViewMode = 'rendered'"
                >
                  <DocumentTextIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                </button>
                <button
                  :class="fileViewMode === 'raw' ? 'text-fg-strong bg-muted' : 'text-fg-muted hover:text-fg-strong'"
                  class="p-1 rounded transition-colors"
                  title="Raw text"
                  @click="fileViewMode = 'raw'"
                >
                  <CodeBracketIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                </button>
              </div>
            </div>
            <!-- eslint-disable vue/no-v-html -- renderedMarkdown runs the file through DOMPurify.sanitize (see computed above) before returning. -->
            <div
              v-if="isMarkdownFile && fileViewMode === 'rendered'"
              class="prose-skill flex-1 overflow-y-auto px-6 py-4 text-sm text-fg-primary"
              v-html="renderedMarkdown"
            />
            <!-- eslint-enable vue/no-v-html -->
            <textarea
              v-else
              :value="fileContent"
              readonly
              aria-label="Skill file contents"
              class="flex-1 w-full px-4 py-3 bg-transparent text-sm text-fg-primary font-mono resize-none focus:outline-hidden cursor-default opacity-80"
              spellcheck="false"
            />
          </template>
          <template v-else>
            <div class="flex-1 flex items-center justify-center text-sm text-fg-muted">
              Select a file to view
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<style>
.prose-skill { overflow-wrap: anywhere; line-height: 1.7; }
.prose-skill p { margin: 0.6em 0; }
.prose-skill p:first-child { margin-top: 0; }
.prose-skill p:last-child { margin-bottom: 0; }
.prose-skill ul, .prose-skill ol { padding-left: 1.5em; margin: 0.5em 0; }
.prose-skill ul { list-style-type: disc; }
.prose-skill ol { list-style-type: decimal; }
.prose-skill li { margin: 0.25em 0; }
.prose-skill h1, .prose-skill h2, .prose-skill h3 { font-weight: 600; margin: 1em 0 0.4em; }
.prose-skill h1 { font-size: 1.4em; }
.prose-skill h2 { font-size: 1.2em; }
.prose-skill h3 { font-size: 1.05em; }
.prose-skill pre { padding: 0.75em 1em; margin: 0.5em 0; overflow-x: auto; background: rgb(255,255,255,4%); border: 1px solid rgb(255,255,255,8%); border-radius: 0.375rem; }
.prose-skill pre code { background: none; padding: 0; font-size: 0.85em; }
.prose-skill code { background: rgb(255,255,255,8%); padding: 0.15em 0.35em; border-radius: 0.25rem; font-size: 0.85em; }
.prose-skill a { color: var(--color-fg-muted); text-decoration: underline; }
.prose-skill a:hover { color: var(--color-fg-strong); }
.prose-skill blockquote { border-left: 2px solid rgb(255,255,255,15%); padding-left: 1em; margin: 0.5em 0; color: var(--color-fg-muted); }
.prose-skill hr { border: none; border-top: 1px solid rgb(255,255,255,10%); margin: 1em 0; }
.prose-skill strong { font-weight: 600; color: var(--color-fg-strong); }
.prose-skill table { width: 100%; border-collapse: collapse; margin: 0.5em 0; font-size: 0.9em; }
.prose-skill th, .prose-skill td { padding: 0.4em 0.75em; text-align: left; border-bottom: 1px solid rgb(255,255,255,8%); }
.prose-skill th { font-weight: 600; color: var(--color-fg-strong); border-bottom-width: 2px; }
</style>
