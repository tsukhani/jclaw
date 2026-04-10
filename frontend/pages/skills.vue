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
// Track multiple concurrent promotions by skill name (survives navigation via useState)
const promotingSkills = useState<Set<string>>('promotingSkills', () => new Set())

// Listen for promotion completion via SSE
const { on } = useEventBus()
on('skill.promoted', (data: any) => {
  refreshSkills()
  const s = new Set(promotingSkills.value)
  s.delete(data.skillName)
  promotingSkills.value = s
})
on('skill.promote_failed', (data: any) => {
  console.error('Skill promotion failed:', data.skillName, data.error)
  const s = new Set(promotingSkills.value)
  s.delete(data.skillName)
  promotingSkills.value = s
})

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
  const existing = agentSkillsMap.value[agent.id]?.find((s: any) => s.name === dragging.value.name)

  if (existing && existing.enabled) {
    dragging.value = null
    return
  }

  try {
    await $fetch(`/api/agents/${agent.id}/skills/${skillName}/copy`, { method: 'POST' })
    agentSkillsMap.value[agent.id] = await $fetch<any[]>(`/api/agents/${agent.id}/skills`)
  } catch (err: any) {
    const status = err?.response?.status
    if (status === 409 && existing && !existing.enabled) {
      await $fetch(`/api/agents/${agent.id}/skills/${skillName}`, {
        method: 'PUT', body: { enabled: true }
      })
      agentSkillsMap.value[agent.id] = await $fetch<any[]>(`/api/agents/${agent.id}/skills`)
    } else if (status === 400) {
      // Missing tools — surface the server's error message to the user
      const msg = err?.response?._data || err?.data || err?.message || 'Cannot add skill to this agent.'
      showDragError(typeof msg === 'string' ? msg : 'Cannot add skill to this agent.')
    } else if (status !== 409) {
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
const creating = ref(false)
const form = ref({ name: '', content: '' })
const saving = ref(false)

// File browser state for edit mode
const skillFiles = ref<any[]>([])
const skillTools = ref<any[]>([])
const activeFile = ref<string | null>(null)
const fileContent = ref('')
const fileDirty = ref(false)
const fileSaving = ref(false)
const editingAgentId = ref<number | null>(null)  // null = global skill, number = agent workspace skill
const isReadOnly = computed(() => (editing.value?.folderName || editing.value?.name) === 'skill-creator')

function newSkill() {
  form.value = { name: '', content: '---\nname: \ndescription: \n---\n\n' }
  creating.value = true
  editing.value = null
  skillFiles.value = []
  skillTools.value = []
  activeFile.value = null
}

async function editSkill(skill: any) {
  try {
    const folderName = skill.folderName || skill.name
    const full = await $fetch<any>(`/api/skills/${folderName}`)
    form.value = { name: full.folderName || full.name, content: full.content || '' }
    editing.value = { ...skill, folderName }
    creating.value = false

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
    fileDirty.value = false
  } catch (e) {
    console.error('Failed to read file:', e)
  }
}

async function saveFile() {
  if (!activeFile.value || !editing.value) return
  fileSaving.value = true
  try {
    await $fetch(`${skillFileApiBase()}/${activeFile.value}`, {
      method: 'PUT',
      body: { content: fileContent.value }
    })
    fileDirty.value = false
    if (activeFile.value === 'SKILL.md') {
      form.value.content = fileContent.value
    }
    refreshSkills()
    if (editingAgentId.value != null) loadAllAgentSkills()
  } catch (e) {
    console.error('Failed to save file:', e)
  } finally {
    fileSaving.value = false
  }
}

async function saveSkill() {
  saving.value = true
  try {
    if (creating.value) {
      await $fetch('/api/skills', { method: 'POST', body: { name: form.value.name, content: form.value.content } })
    }
    editing.value = null
    creating.value = false
    skillFiles.value = []
    activeFile.value = null
    refreshSkills()
  } catch (e) {
    console.error('Failed to save skill:', e)
  } finally {
    saving.value = false
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
    creating.value = false

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
  creating.value = false
  skillFiles.value = []
  skillTools.value = []
  activeFile.value = null
}

function fileIcon(file: any) {
  const name = file.name.toLowerCase()
  if (name.endsWith('.md')) return 'M'
  if (name.endsWith('.json')) return '{}'
  if (name.endsWith('.js') || name.endsWith('.ts')) return 'JS'
  if (name.endsWith('.py')) return 'PY'
  if (name.endsWith('.sh')) return 'SH'
  if (name.endsWith('.yaml') || name.endsWith('.yml')) return 'YL'
  if (file.isText) return 'TXT'
  return 'BIN'
}

function formatSize(bytes: number) {
  if (bytes < 1024) return bytes + ' B'
  if (bytes < 1048576) return (bytes / 1024).toFixed(1) + ' KB'
  return (bytes / 1048576).toFixed(1) + ' MB'
}

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
      <button v-if="!editing && !creating" @click="newSkill"
              class="px-3 py-1.5 bg-emerald-600 text-white text-xs font-medium hover:bg-emerald-500 transition-colors">
        New Skill
      </button>
    </div>

    <div v-if="dragError"
         class="mb-4 px-3 py-2 bg-red-950/40 border border-red-900/60 text-red-300 text-xs flex items-start justify-between gap-3">
      <span>{{ dragError }}</span>
      <button @click="dragError = null" class="text-red-400 hover:text-red-200 shrink-0" title="Dismiss">×</button>
    </div>

    <template v-if="!editing && !creating">
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
                <span v-if="agent.isDefault" class="ml-2 text-[10px] text-neutral-500 border border-neutral-700 px-1">default</span>
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
                </div>
                <div class="flex items-center gap-1 shrink-0">
                  <button @click.stop="editAgentSkill(agent.id, skill)"
                          class="p-1 text-neutral-600 hover:text-white transition-colors opacity-0 group-hover:opacity-100" title="Edit skill">
                    <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
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
          No global skills. Create one to get started.
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
              <span class="text-[10px] text-green-400 border border-green-400/30 px-1 shrink-0">global</span>
            </div>
            <div v-if="skill.name !== (skill.folderName || skill.name)"
                 class="text-[10px] text-neutral-600 mb-0.5">name: {{ skill.name }}</div>
            <div class="text-xs text-neutral-500">{{ skill.description || '(no description)' }}</div>
            <div class="mt-3 flex items-center justify-end gap-2">
              <button @click.stop="editSkill(skill)"
                      class="p-1.5 text-neutral-500 hover:text-white transition-colors"
                      :title="(skill.folderName || skill.name) === 'skill-creator' ? 'View skill' : 'Edit skill'">
                <!-- Eye icon for skill-creator (view-only) -->
                <svg v-if="(skill.folderName || skill.name) === 'skill-creator'"
                     class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z" /></svg>
                <!-- Pencil icon for editable skills -->
                <svg v-else
                     class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
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

    <!-- Create form -->
    <div v-if="creating" class="space-y-4">
      <button @click="cancel" class="text-xs text-neutral-500 hover:text-white transition-colors">&larr; Back to skills</button>
      <div class="bg-neutral-900 border border-neutral-800 p-4">
        <h2 class="text-sm font-medium text-white mb-4">New Skill</h2>
        <div class="space-y-3">
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Name</label>
            <input v-model="form.name"
                   class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600" />
          </div>
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Content (SKILL.md)</label>
            <textarea v-model="form.content" rows="20"
                      class="w-full px-4 py-3 bg-neutral-800 border border-neutral-700 text-sm text-neutral-300 font-mono resize-y focus:outline-none focus:border-neutral-600" />
          </div>
        </div>
        <div class="flex gap-2 mt-4">
          <button @click="saveSkill" :disabled="saving || !form.name"
                  class="px-4 py-1.5 bg-emerald-600 text-white text-xs font-medium hover:bg-emerald-500 disabled:opacity-40 transition-colors">
            {{ saving ? 'Saving...' : 'Create' }}
          </button>
          <button @click="cancel" class="px-4 py-1.5 text-xs text-neutral-400 hover:text-white transition-colors">Cancel</button>
        </div>
      </div>
    </div>

    <!-- Edit form — file browser -->
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
        <div class="flex items-center gap-2 mb-2">
          <svg class="w-4 h-4 text-amber-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" /><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" /></svg>
          <span class="text-xs font-medium text-neutral-400 uppercase tracking-wider">Required Tools</span>
        </div>
        <div class="flex flex-wrap gap-2">
          <div v-for="tool in skillTools" :key="tool.name"
               class="flex items-center gap-1.5 px-2.5 py-1 bg-amber-900/20 border border-amber-800/30 rounded">
            <span class="text-xs font-mono text-amber-300">{{ tool.name }}</span>
            <span class="text-[10px] text-neutral-500">{{ tool.description }}</span>
          </div>
        </div>
      </div>

      <div class="flex gap-4" style="min-height: 500px;">
        <!-- File sidebar -->
        <div class="w-52 shrink-0 bg-neutral-900 border border-neutral-800 overflow-y-auto">
          <div class="px-3 py-2 border-b border-neutral-800">
            <span class="text-[10px] font-medium text-neutral-500 uppercase tracking-wider">Files</span>
          </div>
          <div v-for="file in skillFiles" :key="file.path"
               @click="selectFile(file)"
               :class="[
                 'px-3 py-2 flex items-center gap-2 transition-colors',
                 activeFile === file.path ? 'bg-neutral-800 text-white' : 'text-neutral-400 hover:bg-neutral-800/50',
                 file.isText ? 'cursor-pointer' : 'cursor-default opacity-60'
               ]">
            <span class="text-[9px] font-mono px-1 py-0.5 rounded shrink-0"
                  :class="file.isText ? 'bg-emerald-900/40 text-emerald-400' : 'bg-neutral-700 text-neutral-400'">
              {{ fileIcon(file) }}
            </span>
            <div class="min-w-0">
              <div class="text-xs truncate">{{ file.path }}</div>
              <div class="text-[10px] text-neutral-600">{{ formatSize(file.size) }}</div>
            </div>
          </div>
        </div>

        <!-- File editor -->
        <div class="flex-1 flex flex-col bg-neutral-900 border border-neutral-800 min-w-0">
          <template v-if="activeFile">
            <div class="px-4 py-2 border-b border-neutral-800 flex items-center justify-between">
              <div class="flex items-center gap-2">
                <span class="text-xs font-mono text-neutral-400">{{ activeFile }}</span>
                <span v-if="isReadOnly" class="text-[10px] text-neutral-600">(read-only)</span>
                <span v-else-if="fileDirty" class="text-[10px] text-amber-400">(unsaved)</span>
              </div>
              <button v-if="!isReadOnly" @click="saveFile" :disabled="!fileDirty || fileSaving"
                      class="px-3 py-1 text-xs bg-emerald-600 text-white hover:bg-emerald-500 disabled:opacity-30 transition-colors">
                {{ fileSaving ? 'Saving...' : 'Save' }}
              </button>
            </div>
            <textarea v-model="fileContent"
                      @input="fileDirty = true"
                      :readonly="isReadOnly"
                      :class="isReadOnly ? 'cursor-default opacity-70' : ''"
                      class="flex-1 w-full px-4 py-3 bg-transparent text-sm text-neutral-300 font-mono resize-none focus:outline-none"
                      spellcheck="false" />
          </template>
          <template v-else>
            <div class="flex-1 flex items-center justify-center text-sm text-neutral-600">
              Select a file to edit
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>
