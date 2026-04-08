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
const promoting = ref(false)

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
    if (err?.response?.status === 409 && existing && !existing.enabled) {
      await $fetch(`/api/agents/${agent.id}/skills/${skillName}`, {
        method: 'PUT', body: { enabled: true }
      })
      agentSkillsMap.value[agent.id] = await $fetch<any[]>(`/api/agents/${agent.id}/skills`)
    } else if (err?.response?.status !== 409) {
      console.error('Failed to copy skill:', err)
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
  promoting.value = true

  try {
    await $fetch('/api/skills/promote', {
      method: 'POST',
      body: { agentId, skillName }
    })
    refreshSkills()
  } catch (err) {
    console.error('Failed to promote skill:', err)
  } finally {
    promoting.value = false
    dragging.value = null
  }
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

function newSkill() {
  form.value = { name: '', content: '---\nname: \ndescription: \n---\n\n' }
  creating.value = true
  editing.value = null
}

async function editSkill(skill: any) {
  try {
    const folderName = skill.folderName || skill.name
    const full = await $fetch<any>(`/api/skills/${folderName}`)
    form.value = { name: full.folderName || full.name, content: full.content || '' }
    editing.value = { ...skill, folderName }
    creating.value = false
  } catch (e) {
    console.error('Failed to load skill:', e)
  }
}

async function saveSkill() {
  saving.value = true
  try {
    if (creating.value) {
      await $fetch('/api/skills', { method: 'POST', body: { name: form.value.name, content: form.value.content } })
    } else if (editing.value) {
      const folderName = editing.value.folderName || editing.value.name
      await $fetch(`/api/skills/${folderName}`, { method: 'PUT', body: { content: form.value.content } })
    }
    editing.value = null
    creating.value = false
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
    refreshSkills()
    loadAllAgentSkills()
  } catch (e) {
    console.error('Failed to delete skill:', e)
  }
}

function cancel() {
  editing.value = null
  creating.value = false
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
                <label class="flex items-center shrink-0" @click.stop>
                  <input type="checkbox" :checked="skill.enabled"
                         @change="(e: Event) => toggleAgentSkill(agent.id, skill.name, (e.target as HTMLInputElement).checked)"
                         class="accent-emerald-500 scale-90" />
                </label>
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
          <div v-if="promoting" class="flex items-center gap-1.5 text-[10px] text-emerald-400">
            <svg class="w-3 h-3 animate-spin" viewBox="0 0 24 24" fill="none">
              <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"/>
              <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z"/>
            </svg>
            Sanitizing &amp; promoting...
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

        <div v-if="!skills?.length && !promoting" class="bg-neutral-900 border border-neutral-800 px-4 py-6 text-center text-sm text-neutral-600">
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
            <div class="mt-3 flex items-center gap-3">
              <button @click.stop="editSkill(skill)"
                      class="text-[10px] text-neutral-600 hover:text-neutral-300 transition-colors">
                Edit
              </button>
              <button v-if="(skill.folderName || skill.name) !== 'skill-creator'"
                      @click.stop="deleteSkill(skill)"
                      class="text-[10px] text-neutral-600 hover:text-red-400 transition-colors">
                Delete
              </button>
            </div>
          </div>
        </div>
      </div>
    </template>

    <!-- Edit / Create form -->
    <div v-if="editing || creating" class="space-y-4">
      <button @click="cancel" class="text-xs text-neutral-500 hover:text-white transition-colors">&larr; Back to skills</button>
      <div class="bg-neutral-900 border border-neutral-800 p-4">
        <h2 class="text-sm font-medium text-white mb-4">{{ creating ? 'New Skill' : 'Edit Skill' }}</h2>
        <div class="space-y-3">
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Name</label>
            <input v-model="form.name" :disabled="!!editing"
                   class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600 disabled:opacity-50" />
          </div>
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Content (SKILL.md)</label>
            <textarea v-model="form.content" rows="24"
                      class="w-full px-4 py-3 bg-neutral-800 border border-neutral-700 text-sm text-neutral-300 font-mono resize-y focus:outline-none focus:border-neutral-600" />
          </div>
        </div>
        <div class="flex gap-2 mt-4">
          <button @click="saveSkill" :disabled="saving || !form.name"
                  class="px-4 py-1.5 bg-emerald-600 text-white text-xs font-medium hover:bg-emerald-500 disabled:opacity-40 transition-colors">
            {{ saving ? 'Saving...' : 'Save' }}
          </button>
          <button @click="cancel" class="px-4 py-1.5 text-xs text-neutral-400 hover:text-white transition-colors">Cancel</button>
          <button v-if="editing && (editing.folderName || editing.name) !== 'skill-creator'"
                  @click="deleteSkill(editing)"
                  class="px-4 py-1.5 text-xs text-red-400/60 hover:text-red-400 ml-auto transition-colors">Delete</button>
        </div>
      </div>
    </div>
  </div>
</template>
