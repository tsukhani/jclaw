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

// Drag state
const dragging = ref<any>(null)
const dropTarget = ref<number | null>(null)

function onDragStart(e: DragEvent, skill: any) {
  dragging.value = skill
  if (e.dataTransfer) {
    e.dataTransfer.effectAllowed = 'copy'
    e.dataTransfer.setData('text/plain', skill.name)
  }
}

function onDragEnd() {
  dragging.value = null
  dropTarget.value = null
}

function onDragOver(e: DragEvent, agentId: number) {
  e.preventDefault()
  if (e.dataTransfer) e.dataTransfer.dropEffect = 'copy'
  dropTarget.value = agentId
}

function onDragLeave(agentId: number) {
  if (dropTarget.value === agentId) dropTarget.value = null
}

async function onDrop(e: DragEvent, agent: any) {
  e.preventDefault()
  dropTarget.value = null
  if (!dragging.value) return

  const skillName = dragging.value.name
  const existing = agentSkillsMap.value[agent.id]?.find((s: any) => s.name === skillName)

  // If skill already exists as a workspace skill and is enabled, do nothing
  if (existing && !existing.isGlobal && existing.enabled) {
    dragging.value = null
    return
  }

  try {
    // Copy the global skill into the agent's workspace
    await $fetch(`/api/agents/${agent.id}/skills/${skillName}/copy`, {
      method: 'POST'
    })
    // Reload skills for this agent
    agentSkillsMap.value[agent.id] = await $fetch<any[]>(`/api/agents/${agent.id}/skills`)
  } catch (err: any) {
    // 409 = already exists in workspace, just ensure it's enabled
    if (err?.response?.status === 409 && existing && !existing.enabled) {
      await $fetch(`/api/agents/${agent.id}/skills/${skillName}`, {
        method: 'PUT',
        body: { enabled: true }
      })
      agentSkillsMap.value[agent.id] = await $fetch<any[]>(`/api/agents/${agent.id}/skills`)
    } else if (err?.response?.status !== 409) {
      console.error('Failed to copy skill:', err)
    }
  }
  dragging.value = null
}

async function toggleAgentSkill(agentId: number, skillName: string, enabled: boolean) {
  try {
    await $fetch(`/api/agents/${agentId}/skills/${skillName}`, {
      method: 'PUT',
      body: { enabled }
    })
    agentSkillsMap.value[agentId] = await $fetch<any[]>(`/api/agents/${agentId}/skills`)
  } catch (e) {
    console.error('Failed to toggle skill:', e)
  }
}

// Skill editing
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
    const full = await $fetch<any>(`/api/skills/${skill.name}`)
    form.value = { name: full.name, content: full.content || '' }
    editing.value = skill
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
      await $fetch(`/api/skills/${editing.value.name}`, { method: 'PUT', body: { content: form.value.content } })
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

async function deleteSkill(name: string) {
  try {
    await $fetch(`/api/skills/${name}`, { method: 'DELETE' })
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
               @dragover="onDragOver($event, agent.id)"
               @dragleave="onDragLeave(agent.id)"
               @drop="onDrop($event, agent)"
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

            <!-- Agent's skills -->
            <div v-if="agentSkillsMap[agent.id]?.length" class="space-y-1">
              <div v-for="skill in agentSkillsMap[agent.id]" :key="skill.name"
                   class="flex items-center justify-between px-2 py-1.5 bg-neutral-800/50 group">
                <div class="flex items-center gap-2 min-w-0">
                  <span class="text-xs text-white font-mono truncate">{{ skill.name }}</span>
                </div>
                <label class="flex items-center shrink-0">
                  <input type="checkbox" :checked="skill.enabled"
                         @change="(e: Event) => toggleAgentSkill(agent.id, skill.name, (e.target as HTMLInputElement).checked)"
                         class="accent-emerald-500 scale-90" />
                </label>
              </div>
            </div>
            <div v-else class="text-xs text-neutral-600 italic">
              {{ loadingAgents ? 'Loading...' : 'No skills assigned' }}
            </div>

            <!-- Drop hint -->
            <div v-if="dragging && dropTarget !== agent.id"
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

      <!-- Global skills (draggable) -->
      <div>
        <div class="text-xs text-neutral-500 uppercase tracking-wider mb-3">Global Skills</div>
        <div class="text-[10px] text-neutral-600 mb-3">Drag a skill onto an agent card above to assign it</div>
        <div v-if="!skills?.length" class="bg-neutral-900 border border-neutral-800 px-4 py-6 text-center text-sm text-neutral-600">
          No global skills. Create one to get started.
        </div>
        <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-3">
          <div v-for="skill in skills" :key="skill.name"
               draggable="true"
               @dragstart="onDragStart($event, skill)"
               @dragend="onDragEnd"
               :class="[
                 'bg-neutral-900 border border-neutral-800 p-4 cursor-grab active:cursor-grabbing transition-all duration-150 select-none',
                 dragging?.name === skill.name ? 'opacity-50 scale-95' : 'hover:border-neutral-700'
               ]">
            <div class="flex items-center justify-between mb-1">
              <span class="text-sm text-white font-mono">{{ skill.name }}</span>
              <span class="text-[10px] text-green-400 border border-green-400/30 px-1">global</span>
            </div>
            <div class="text-xs text-neutral-500">{{ skill.description || '(no description)' }}</div>
            <button @click.stop="editSkill(skill)"
                    class="mt-3 text-[10px] text-neutral-600 hover:text-neutral-300 transition-colors">
              Edit
            </button>
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
          <button v-if="editing" @click="deleteSkill(editing.name)"
                  class="px-4 py-1.5 text-xs text-red-400/60 hover:text-red-400 ml-auto transition-colors">Delete</button>
        </div>
      </div>
    </div>
  </div>
</template>
