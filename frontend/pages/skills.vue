<script setup lang="ts">
const { data: skills, refresh: refreshSkills } = await useFetch<any[]>('/api/skills')
const { data: agents } = await useFetch<any[]>('/api/agents')

// Skills per agent, keyed by agent id
const agentSkillsMap = ref<Record<number, any[]>>({})
const loadingAgents = ref(true)

async function loadAllAgentSkills() {
  loadingAgents.value = true
  const map: Record<number, any[]> = {}
  if (agents.value?.length) {
    await Promise.all(agents.value.map(async (agent) => {
      try {
        map[agent.id] = await $fetch<any[]>(`/api/agents/${agent.id}/skills`)
      } catch {
        map[agent.id] = []
      }
    }))
  }
  agentSkillsMap.value = map
  loadingAgents.value = false
}

watch(agents, () => loadAllAgentSkills(), { immediate: true })

// Confirm dialog (replaces native window.confirm for destructive actions)
const { confirm } = useConfirm()

// Drag state — supports both directions
const dragging = ref<any>(null)
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
  dragErrorTimer = setTimeout(() => { dragError.value = null }, 8000)
}

// Info banner for non-error events (e.g. promote no-op when workspace matches global)
const infoBanner = ref<string | null>(null)
let infoBannerTimer: ReturnType<typeof setTimeout> | null = null
function showInfo(msg: string) {
  infoBanner.value = msg
  if (infoBannerTimer) clearTimeout(infoBannerTimer)
  infoBannerTimer = setTimeout(() => { infoBanner.value = null }, 6000)
}

onUnmounted(() => { if (dragErrorTimer) clearTimeout(dragErrorTimer); if (infoBannerTimer) clearTimeout(infoBannerTimer) })

// Track multiple concurrent promotions by skill name (survives navigation via useState)
const promotingSkills = useState<Set<string>>('promotingSkills', () => new Set())

// Listen for promotion completion via SSE
const { onEvent } = useEventBus()
onEvent('skill.promoted', (data: any) => {
  refreshSkills()
  loadAllAgentSkills()
  const s = new Set(promotingSkills.value)
  s.delete(data.skillName)
  promotingSkills.value = s
})
onEvent('skill.promote_failed', (data: any) => {
  console.error('Skill promotion failed:', data.skillName, data.error)
  showDragError(data.error || `Failed to promote '${data.skillName}'`)
  const s = new Set(promotingSkills.value)
  s.delete(data.skillName)
  promotingSkills.value = s
})
onEvent('skill.promote_noop', (data: any) => {
  showInfo(data.reason || `Nothing to promote for '${data.skillName}'`)
  const s = new Set(promotingSkills.value)
  s.delete(data.skillName)
  promotingSkills.value = s
})

// Look up the current global version of a skill by folder name
function globalVersionOf(folderName: string): string | null {
  const s = skills.value?.find((x: any) => (x.folderName || x.name) === folderName)
  return s?.version ?? null
}

// Simple semver compare: returns negative/0/positive
function compareVersions(a: string, b: string): number {
  const pa = (a || '0.0.0').split('.').map(n => parseInt(n) || 0)
  const pb = (b || '0.0.0').split('.').map(n => parseInt(n) || 0)
  for (let i = 0; i < 3; i++) {
    const x = pa[i] || 0, y = pb[i] || 0
    if (x !== y) return x - y
  }
  return 0
}

// Returns the global version if an update is available, else null
function updateAvailable(skill: any): string | null {
  const gv = globalVersionOf(skill.folderName || skill.name)
  if (gv == null) return null
  return compareVersions(skill.version || '0.0.0', gv) < 0 ? gv : null
}

async function updateAgentSkillFromGlobal(agentId: number, skill: any) {
  const skillName = skill.folderName || skill.name
  try {
    await $fetch(`/api/agents/${agentId}/skills/${skillName}/copy`, { method: 'POST' })
    agentSkillsMap.value[agentId] = await $fetch<any[]>(`/api/agents/${agentId}/skills`)
    showInfo(`Updated '${skillName}' for this agent`)
  } catch (err: any) {
    const status = err?.response?.status
    if (status === 400) {
      const msg = err?.response?._data || err?.data || err?.message || 'Update failed'
      showDragError(typeof msg === 'string' ? msg : 'Update failed')
    } else {
      console.error('Failed to update skill from global:', err)
      showDragError('Failed to update skill from global')
    }
  }
}

// --- Global skill → Agent card (copy to workspace) ---

function onGlobalDragStart(e: DragEvent, skill: any) {
  dragging.value = skill
  dragSource.value = 'global'
  dragSourceAgentId.value = null
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'copy'
    e.dataTransfer.setData('text/plain', skill.name)
  }
}

function onAgentSkillDragStart(e: DragEvent, skill: any, agentId: number) {
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

async function onAgentDrop(e: DragEvent, agent: any) {
  e.preventDefault()
  dropTarget.value = null
  if (!dragging.value || dragSource.value !== 'global') return

  const skillName = dragging.value.folderName || dragging.value.name
  const globalVersion = dragging.value.version || '0.0.0'
  const existing = agentSkillsMap.value[agent.id]?.find((s: any) => s.name === dragging.value.name)

  // If the agent already has this skill at the same or newer version, just ensure it's
  // enabled and move on — no point overwriting identical content.
  if (existing) {
    const cmp = compareVersions(existing.version || '0.0.0', globalVersion)
    if (cmp >= 0) {
      if (!existing.enabled) {
        try {
          await $fetch(`/api/agents/${agent.id}/skills/${skillName}`, {
            method: 'PUT', body: { enabled: true }
          })
          agentSkillsMap.value[agent.id] = await $fetch<any[]>(`/api/agents/${agent.id}/skills`)
        } catch (err) {
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
        `Agent '${agent.name}' has '${skillName}' at version ${existing.version || '0.0.0'}.\n` +
        `Replace with global version ${globalVersion}?`,
      confirmText: 'Replace',
    })
    if (!ok) {
      dragging.value = null
      return
    }
  }

  try {
    await $fetch(`/api/agents/${agent.id}/skills/${skillName}/copy`, { method: 'POST' })
    agentSkillsMap.value[agent.id] = await $fetch<any[]>(`/api/agents/${agent.id}/skills`)
    if (existing) showInfo(`Updated '${skillName}' on agent '${agent.name}' to version ${globalVersion}`)
  } catch (err: any) {
    const status = err?.response?.status
    if (status === 400) {
      // Missing tools — surface the server's error message to the user
      const msg = err?.response?._data || err?.data || err?.message || 'Cannot add skill to this agent.'
      showDragError(typeof msg === 'string' ? msg : 'Cannot add skill to this agent.')
    } else {
      console.error('Failed to copy skill:', err)
      showDragError('Failed to add skill to agent.')
    }
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

  const skillName = dragging.value.folderName || dragging.value.name
  const agentId = dragSourceAgentId.value
  dragging.value = null

  // Skip if already promoting this skill
  if (promotingSkills.value.has(skillName)) return

  // If a global skill with this name already exists, confirm replacement before
  // firing the promote — the backend will overwrite it in place.
  const existingGlobal = skills.value?.find((s: any) => (s.folderName || s.name) === skillName)
  if (existingGlobal) {
    const ok = await confirm({
      title: 'Replace global skill',
      message:
        `A global skill named '${skillName}' already exists.\n\n` +
        `Promoting will replace it with the sanitized version from the agent workspace. Continue?`,
      confirmText: 'Promote',
    })
    if (!ok) return
  }

  // Add to in-progress set and run in background (non-blocking)
  promotingSkills.value = new Set([...promotingSkills.value, skillName])

  // Send promote request — returns immediately, SSE event will notify on completion
  $fetch('/api/skills/promote', {
    method: 'POST',
    body: { agentId, skillName }
  }).catch((err) => {
    console.error('Failed to promote skill:', err)
    const s = new Set(promotingSkills.value)
    s.delete(skillName)
    promotingSkills.value = s
  })
}

// --- Agent skill toggle ---

async function toggleAgentSkill(agentId: number, skillName: string, enabled: boolean) {
  try {
    await $fetch(`/api/agents/${agentId}/skills/${skillName}`, {
      method: 'PUT', body: { enabled }
    })
    agentSkillsMap.value[agentId] = await $fetch<any[]>(`/api/agents/${agentId}/skills`)
  } catch (e) {
    console.error('Failed to toggle skill:', e)
  }
}

// --- Global skill inline rename ---

const renamingSkill = ref<string | null>(null)
const renameValue = ref('')

function startRename(skill: any) {
  renamingSkill.value = skill.folderName || skill.name
  renameValue.value = skill.folderName || skill.name
}

function cancelRename() {
  renamingSkill.value = null
  renameValue.value = ''
}

async function commitRename(skill: any) {
  const oldName = skill.folderName || skill.name
  const newName = renameValue.value.trim()
  if (!newName || newName === oldName) {
    cancelRename()
    return
  }
  try {
    await $fetch(`/api/skills/${oldName}/rename`, {
      method: 'PUT', body: { newName }
    })
    refreshSkills()
  } catch (err: any) {
    console.error('Failed to rename skill:', err)
  } finally {
    renamingSkill.value = null
    renameValue.value = ''
  }
}

// --- Skill editing (create / edit form) ---

const editing = ref<any>(null)

// File browser state for view mode — skill file contents are read-only; authoring
// happens exclusively via the skill-creator skill (using the filesystem tool).
const skillFiles = ref<any[]>([])
const skillTools = ref<any[]>([])
const activeFile = ref<string | null>(null)
const fileContent = ref('')
const editingAgentId = ref<number | null>(null)  // null = global skill, number = agent workspace skill

async function editSkill(skill: any) {
  try {
    const folderName = skill.folderName || skill.name
    editing.value = { ...skill, folderName }

    // Load file listing and tool dependencies
    const res = await $fetch<any>(`/api/skills/${folderName}/files`)
    skillFiles.value = res.files || []
    skillTools.value = res.tools || []

    // Auto-select SKILL.md
    const skillMd = skillFiles.value.find((f: any) => f.path === 'SKILL.md')
    if (skillMd) {
      await selectFile(skillMd)
    } else if (skillFiles.value.length > 0 && skillFiles.value[0].isText) {
      await selectFile(skillFiles.value[0])
    } else {
      activeFile.value = null
      fileContent.value = ''
    }
  } catch (e) {
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

async function selectFile(file: any) {
  if (!file.isText) return
  try {
    const res = await $fetch<any>(`${skillFileApiBase()}/${file.path}`)
    activeFile.value = file.path
    fileContent.value = res.content
  } catch (e) {
    console.error('Failed to read file:', e)
  }
}

async function deleteSkill(skill: any) {
  const folderName = skill.folderName || skill.name
  try {
    await $fetch(`/api/skills/${folderName}`, { method: 'DELETE' })
    editing.value = null
    skillFiles.value = []
    activeFile.value = null
    refreshSkills()
    loadAllAgentSkills()
  } catch (e) {
    console.error('Failed to delete skill:', e)
  }
}

async function editAgentSkill(agentId: number, skill: any) {
  try {
    const name = skill.folderName || skill.name
    editing.value = { ...skill, folderName: name }
    editingAgentId.value = agentId

    const res = await $fetch<any>(`/api/agents/${agentId}/skills/${name}/files`)
    skillFiles.value = res.files || []
    skillTools.value = res.tools || []

    const skillMd = skillFiles.value.find((f: any) => f.path === 'SKILL.md')
    if (skillMd) {
      await selectFile(skillMd)
    } else if (skillFiles.value.length > 0 && skillFiles.value[0].isText) {
      await selectFile(skillFiles.value[0])
    } else {
      activeFile.value = null
      fileContent.value = ''
    }
  } catch (e) {
    console.error('Failed to load agent skill:', e)
  }
}

async function deleteAgentSkill(agentId: number, skill: any) {
  const name = skill.folderName || skill.name
  try {
    await $fetch(`/api/agents/${agentId}/skills/${name}/delete`, { method: 'DELETE' })
    editing.value = null
    editingAgentId.value = null
    skillFiles.value = []
    skillTools.value = []
    activeFile.value = null
    loadAllAgentSkills()
  } catch (e) {
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
  file?: any
  children?: FileNode[]
}

const fileTree = computed<FileNode[]>(() => {
  const root: FileNode = { name: '', isDir: true, children: [] }
  for (const file of skillFiles.value) {
    const parts = (file.path ?? '').split('/').filter(Boolean)
    if (!parts.length) continue
    let node = root
    for (let i = 0; i < parts.length; i++) {
      const part = parts[i]
      const isLast = i === parts.length - 1
      if (isLast) {
        node.children!.push({ name: part, isDir: false, path: file.path, file })
      } else {
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
  return agentSkillsMap.value[agentId]?.filter((s: any) => s.enabled).length ?? 0
}

function totalSkillCount(agentId: number) {
  return agentSkillsMap.value[agentId]?.length ?? 0
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-white">Skills</h1>
    </div>

    <div v-if="dragError"
         class="mb-4 px-3 py-2 bg-red-950/40 border border-red-900/60 text-red-300 text-xs flex items-start justify-between gap-3">
      <span>{{ dragError }}</span>
      <button @click="dragError = null" class="text-red-400 hover:text-red-200 shrink-0" title="Dismiss">×</button>
    </div>

    <div v-if="infoBanner"
         class="mb-4 px-3 py-2 bg-blue-950/40 border border-blue-900/60 text-blue-300 text-xs flex items-start justify-between gap-3">
      <span>{{ infoBanner }}</span>
      <button @click="infoBanner = null" class="text-blue-400 hover:text-blue-200 shrink-0" title="Dismiss">×</button>
    </div>

    <template v-if="!editing">
      <!-- Agent cards with skills -->
      <div class="mb-8">
        <div class="text-xs text-neutral-500 uppercase tracking-wider mb-3">Agents</div>
        <div v-if="!agents?.length" class="bg-neutral-900 border border-neutral-800 px-4 py-6 text-center text-sm text-neutral-600">
          No agents configured
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          <div v-for="agent in agents" :key="agent.id"
               @dragover="onAgentDragOver($event, agent.id)"
               @dragleave="onAgentDragLeave(agent.id)"
               @drop="onAgentDrop($event, agent)"
               :class="[
                 'bg-neutral-900 border p-4 transition-all duration-150',
                 dropTarget === agent.id
                   ? 'border-emerald-500/60 bg-emerald-500/5 ring-1 ring-emerald-500/20'
                   : 'border-neutral-800'
               ]">
            <!-- Agent header -->
            <div class="flex items-center justify-between mb-3">
              <div>
                <span class="text-sm font-medium text-white">{{ agent.name }}</span>
                <span v-if="agent.isMain" class="ml-2 text-[10px] text-neutral-500 border border-neutral-700 px-1">main</span>
              </div>
              <span class="text-[10px] text-neutral-500">
                {{ enabledSkillCount(agent.id) }}/{{ totalSkillCount(agent.id) }} skills
              </span>
            </div>
            <div class="text-xs text-neutral-600 mb-3">{{ agent.modelProvider }} / {{ agent.modelId }}</div>

            <!-- Agent's skills (draggable for promotion) -->
            <div v-if="agentSkillsMap[agent.id]?.length" class="space-y-1">
              <div v-for="skill in agentSkillsMap[agent.id]" :key="skill.name"
                   draggable="true"
                   @dragstart="onAgentSkillDragStart($event, skill, agent.id)"
                   @dragend="onDragEnd"
                   class="flex items-center justify-between px-2 py-1.5 bg-neutral-800/50 cursor-grab active:cursor-grabbing select-none group">
                <div class="flex items-center gap-2 min-w-0">
                  <span class="text-xs text-white font-mono truncate">{{ skill.name }}</span>
                  <span class="text-[9px] text-neutral-500 font-mono shrink-0">v{{ skill.version || '0.0.0' }}</span>
                  <button v-if="updateAvailable(skill)"
                          @click.stop="updateAgentSkillFromGlobal(agent.id, skill)"
                          class="text-[9px] text-amber-400 border border-amber-700/40 bg-amber-900/20 px-1.5 py-0.5 font-mono hover:bg-amber-900/40 transition-colors shrink-0"
                          :title="`Update to v${updateAvailable(skill)}`">
                    update → v{{ updateAvailable(skill) }}
                  </button>
                </div>
                <div class="flex items-center gap-1 shrink-0">
                  <button @click.stop="editAgentSkill(agent.id, skill)"
                          class="p-1 text-neutral-600 hover:text-white transition-colors opacity-0 group-hover:opacity-100" title="View skill">
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></svg>
                  </button>
                  <button @click.stop="deleteAgentSkill(agent.id, skill)"
                          class="p-1 text-neutral-600 hover:text-red-400 transition-colors opacity-0 group-hover:opacity-100" title="Delete skill">
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
                  </button>
                  <label class="flex items-center" @click.stop>
                    <input type="checkbox" :checked="skill.enabled"
                           @change="(e: Event) => toggleAgentSkill(agent.id, skill.name, (e.target as HTMLInputElement).checked)"
                           class="accent-emerald-500 scale-90" />
                  </label>
                </div>
              </div>
            </div>
            <div v-else class="text-xs text-neutral-600 italic">
              {{ loadingAgents ? 'Loading...' : 'No skills assigned' }}
            </div>

            <!-- Drop hint (global → agent) -->
            <div v-if="dragging && dragSource === 'global' && dropTarget !== agent.id"
                 class="mt-3 border border-dashed border-neutral-700 py-2 text-center text-[10px] text-neutral-600">
              Drop skill here
            </div>
            <div v-if="dropTarget === agent.id"
                 class="mt-3 border border-dashed border-emerald-500/40 py-2 text-center text-[10px] text-emerald-400">
              Release to assign
            </div>
          </div>
        </div>
      </div>

      <!-- Global skills (draggable + drop target for promotion) -->
      <div @dragover="onGlobalSectionDragOver"
           @dragleave="onGlobalSectionDragLeave"
           @drop="onGlobalSectionDrop"
           :class="[
             'transition-all duration-150 p-4 -m-4',
             dropTargetGlobal ? 'bg-emerald-500/5 ring-1 ring-emerald-500/20 rounded' : ''
           ]">
        <div class="flex items-center gap-3 mb-3">
          <div class="text-xs text-neutral-500 uppercase tracking-wider">Global Skills</div>
          <div v-if="promotingSkills.size" class="flex items-center gap-1.5 text-[10px] text-emerald-400">
            <svg class="w-3 h-3 animate-spin" viewBox="0 0 24 24" fill="none">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
            </svg>
            Promoting {{ promotingSkills.size }} skill{{ promotingSkills.size > 1 ? 's' : '' }}...
          </div>
        </div>
        <div class="text-[10px] text-neutral-600 mb-3">
          Drag a skill onto an agent above to assign it. Drag an agent skill here to promote it.
        </div>

        <!-- Drop hint when dragging agent skill over global section -->
        <div v-if="dragSource === 'agent' && dropTargetGlobal"
             class="mb-3 border border-dashed border-emerald-500/40 py-3 text-center text-xs text-emerald-400">
          Release to promote to global registry (secrets will be stripped)
        </div>

        <div v-if="!skills?.length && !promotingSkills.size" class="bg-neutral-900 border border-neutral-800 px-4 py-6 text-center text-sm text-neutral-600">
          No global skills. Create one via the skill-creator skill in an agent workspace, then drag it here to promote.
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          <div v-for="skill in skills" :key="skill.folderName || skill.name"
               draggable="true"
               @dragstart="onGlobalDragStart($event, skill)"
               @dragend="onDragEnd"
               :class="[
                 'bg-neutral-900 border border-neutral-800 p-4 cursor-grab active:cursor-grabbing transition-all duration-150 select-none',
                 dragging?.name === skill.name && dragSource === 'global' ? 'opacity-50 scale-95' : 'hover:border-neutral-700'
               ]">
            <div class="flex items-center justify-between mb-1">
              <!-- Inline folder name editing -->
              <template v-if="renamingSkill === (skill.folderName || skill.name)">
                <input v-model="renameValue"
                       @keydown.enter="commitRename(skill)"
                       @keydown.escape="cancelRename"
                       @blur="commitRename(skill)"
                       @click.stop
                       @mousedown.stop
                       ref="renameInput"
                       class="text-sm text-white font-mono bg-neutral-800 border border-neutral-600 px-1.5 py-0.5 w-full mr-2 focus:outline-none focus:border-emerald-500" />
              </template>
              <template v-else>
                <span class="text-sm text-white font-mono cursor-text"
                      @dblclick.stop="startRename(skill)">{{ skill.folderName || skill.name }}</span>
              </template>
              <div class="flex items-center gap-1.5 shrink-0">
                <span class="text-[10px] text-neutral-500 font-mono">v{{ skill.version || '0.0.0' }}</span>
                <span class="text-[10px] text-green-400 border border-green-400/30 px-1">global</span>
              </div>
            </div>
            <div v-if="skill.name !== (skill.folderName || skill.name)"
                 class="text-[10px] text-neutral-600 mb-0.5">name: {{ skill.name }}</div>
            <div class="text-xs text-neutral-500">{{ skill.description || '(no description)' }}</div>
            <div class="mt-3 flex items-center justify-end gap-2">
              <button @click.stop="editSkill(skill)"
                      class="p-1.5 text-neutral-500 hover:text-white transition-colors"
                      title="View skill">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></svg>
              </button>
              <button v-if="(skill.folderName || skill.name) !== 'skill-creator'"
                      @click.stop="deleteSkill(skill)"
                      class="p-1.5 text-neutral-500 hover:text-red-400 transition-colors" title="Delete skill">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- Skill viewer — read-only file browser. Skills are created and updated via
         the skill-creator skill, not through this page. -->
    <div v-if="editing" class="space-y-4">
      <div class="flex items-center justify-between">
        <button @click="cancel" class="text-xs text-neutral-500 hover:text-white transition-colors">&larr; Back to skills</button>
        <button v-if="(editing.folderName || editing.name) !== 'skill-creator'"
                @click="editingAgentId != null ? deleteAgentSkill(editingAgentId, editing) : deleteSkill(editing)"
                class="p-1.5 text-red-400/70 hover:text-red-400 transition-colors" title="Delete skill">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16" /></svg>
        </button>
      </div>

      <!-- Skill header -->
      <div class="bg-neutral-900 border border-neutral-800 px-4 py-3">
        <div class="flex items-center gap-3">
          <div class="w-8 h-8 bg-emerald-900/30 border border-emerald-800/40 rounded flex items-center justify-center">
            <svg class="w-4 h-4 text-emerald-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M3 7v10a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-6l-2-2H5a2 2 0 00-2 2z" /></svg>
          </div>
          <div>
            <div class="flex items-center gap-2">
              <span class="text-sm font-medium text-white font-mono">{{ editing.folderName || editing.name }}</span>
              <span v-if="editingAgentId != null" class="text-[10px] text-blue-400 border border-blue-400/30 px-1">
                {{ agents?.find((a: any) => a.id === editingAgentId)?.name ?? 'agent' }}
              </span>
              <span v-else class="text-[10px] text-green-400 border border-green-400/30 px-1">global</span>
            </div>
            <div class="text-xs text-neutral-500">{{ skillFiles.length }} file{{ skillFiles.length !== 1 ? 's' : '' }}</div>
          </div>
        </div>
      </div>

      <!-- Tool dependencies -->
      <div v-if="skillTools.length" class="bg-neutral-900 border border-neutral-800 px-4 py-3">
        <div class="flex items-center gap-2 mb-3">
          <svg class="w-4 h-4 text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
          <span class="text-xs font-medium text-neutral-400 uppercase tracking-wider">Required Tools</span>
        </div>
        <div class="flex flex-wrap gap-2">
          <span v-for="tool in skillTools" :key="tool.name"
                class="inline-flex items-center px-2.5 py-1 bg-amber-900/20 border border-amber-800/30 rounded text-xs font-mono text-amber-300 leading-none">
            {{ tool.name }}
          </span>
        </div>
      </div>

      <div class="flex gap-4" style="min-height: 500px;">
        <!-- File sidebar -->
        <div class="w-52 shrink-0 bg-neutral-900 border border-neutral-800 overflow-y-auto">
          <div class="px-3 py-2 border-b border-neutral-800">
            <span class="text-[10px] font-medium text-neutral-500 uppercase tracking-wider">Files</span>
          </div>
          <SkillFileTree
            :nodes="fileTree"
            :active-path="activeFile"
            @select="selectFile"
          />
        </div>

        <!-- File editor -->
        <div class="flex-1 flex flex-col bg-neutral-900 border border-neutral-800 min-w-0">
          <template v-if="activeFile">
            <div class="px-4 py-2 border-b border-neutral-800 flex items-center gap-2">
              <span class="text-xs font-mono text-neutral-400">{{ activeFile }}</span>
              <span class="text-[10px] text-neutral-600">(read-only — edit via skill-creator)</span>
            </div>
            <textarea :value="fileContent"
                      readonly
                      class="flex-1 w-full px-4 py-3 bg-transparent text-sm text-neutral-300 font-mono resize-none focus:outline-none cursor-default opacity-80"
                      spellcheck="false" />
          </template>
          <template v-else>
            <div class="flex-1 flex items-center justify-center text-sm text-neutral-600">
              Select a file to view
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
