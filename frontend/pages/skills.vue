<script setup lang="ts">
const { data: skills, refresh } = await useFetch<any[]>('/api/skills')
const { data: agents } = await useFetch<any[]>('/api/agents')

const editing = ref<any>(null)
const creating = ref(false)
const form = ref({ name: '', description: '', content: '', isGlobal: false })
const saving = ref(false)
const assignedAgents = ref<Set<number>>(new Set())

function newSkill() {
  form.value = { name: '', description: '', content: '', isGlobal: false }
  assignedAgents.value = new Set()
  creating.value = true
  editing.value = null
}

async function editSkill(skill: any) {
  try {
    const full = await $fetch<any>(`/api/skills/${skill.id}`)
    form.value = {
      name: full.name,
      description: full.description || '',
      content: full.content || '',
      isGlobal: full.isGlobal
    }
    editing.value = skill
    creating.value = false
    await loadAssignments(skill.id)
  } catch (e) {
    console.error('Failed to load skill:', e)
  }
}

async function loadAssignments(skillId: number) {
  const assigned = new Set<number>()
  for (const agent of (agents.value ?? [])) {
    try {
      const agentSkills = await $fetch<any[]>(`/api/agents/${agent.id}/skills`)
      if (agentSkills.some((s: any) => s.id === skillId && !s.isGlobal)) {
        assigned.add(agent.id)
      }
    } catch { /* ignore */ }
  }
  assignedAgents.value = assigned
}

async function saveSkill() {
  saving.value = true
  try {
    if (creating.value) {
      await $fetch('/api/skills', { method: 'POST', body: form.value })
    } else if (editing.value) {
      await $fetch(`/api/skills/${editing.value.id}`, { method: 'PUT', body: form.value })
    }
    editing.value = null
    creating.value = false
    refresh()
  } catch (e) {
    console.error('Failed to save skill:', e)
  } finally {
    saving.value = false
  }
}

async function deleteSkill(id: number) {
  try {
    await $fetch(`/api/skills/${id}`, { method: 'DELETE' })
    editing.value = null
    refresh()
  } catch (e) {
    console.error('Failed to delete skill:', e)
  }
}

async function toggleAgent(agentId: number, skillId: number) {
  try {
    if (assignedAgents.value.has(agentId)) {
      await $fetch(`/api/agents/${agentId}/skills/${skillId}`, { method: 'DELETE' })
      assignedAgents.value.delete(agentId)
    } else {
      await $fetch(`/api/agents/${agentId}/skills/${skillId}`, { method: 'POST' })
      assignedAgents.value.add(agentId)
    }
    assignedAgents.value = new Set(assignedAgents.value)
  } catch (e) {
    console.error('Failed to toggle agent assignment:', e)
  }
}

function cancel() {
  editing.value = null
  creating.value = false
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-white">Skills</h1>
      <button v-if="!editing && !creating" @click="newSkill"
              class="px-3 py-1.5 bg-white text-neutral-950 text-xs font-medium hover:bg-neutral-200 transition-colors">
        New Skill
      </button>
    </div>

    <!-- Skill list -->
    <div v-if="!editing && !creating" class="bg-neutral-900 border border-neutral-800">
      <div v-for="skill in skills" :key="skill.id"
           @click="editSkill(skill)"
           class="px-4 py-3 border-b border-neutral-800/50 flex items-center justify-between hover:bg-neutral-800/50 cursor-pointer transition-colors">
        <div>
          <span class="text-sm text-white">{{ skill.name }}</span>
          <span v-if="skill.isGlobal" class="ml-2 text-[10px] text-green-400 border border-green-400/30 px-1">global</span>
          <span v-else class="ml-2 text-[10px] text-neutral-500 border border-neutral-700 px-1">agent</span>
          <div class="text-xs text-neutral-500 mt-0.5">{{ skill.description || '(no description)' }}</div>
        </div>
        <span class="text-xs text-neutral-600">{{ new Date(skill.updatedAt).toLocaleDateString() }}</span>
      </div>
      <div v-if="!skills?.length" class="px-4 py-8 text-center text-sm text-neutral-600">
        No skills in the registry
      </div>
    </div>

    <!-- Edit / Create form -->
    <div v-if="editing || creating" class="space-y-4">
      <button @click="cancel" class="text-xs text-neutral-500 hover:text-white transition-colors">&larr; Back to skills</button>
      <div class="bg-neutral-900 border border-neutral-800 p-4">
        <h2 class="text-sm font-medium text-white mb-4">{{ creating ? 'New Skill' : 'Edit Skill' }}</h2>
        <div class="space-y-3">
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Name</label>
            <input v-model="form.name" class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600" />
          </div>
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Description</label>
            <input v-model="form.description" class="w-full px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white focus:outline-none focus:border-neutral-600" />
          </div>
          <div class="flex items-center gap-2">
            <input type="checkbox" v-model="form.isGlobal" id="skill-global" class="accent-white" />
            <label for="skill-global" class="text-xs text-neutral-400">Available to all agents</label>
          </div>
          <div>
            <label class="block text-xs text-neutral-500 mb-1">Content</label>
            <textarea v-model="form.content" rows="20"
                      class="w-full px-4 py-3 bg-neutral-800 border border-neutral-700 text-sm text-neutral-300 font-mono resize-y focus:outline-none focus:border-neutral-600" />
          </div>
        </div>
        <div class="flex gap-2 mt-4">
          <button @click="saveSkill" :disabled="saving || !form.name"
                  class="px-4 py-1.5 bg-white text-neutral-950 text-xs font-medium hover:bg-neutral-200 disabled:opacity-40 transition-colors">
            {{ saving ? 'Saving...' : 'Save' }}
          </button>
          <button @click="cancel" class="px-4 py-1.5 text-xs text-neutral-400 hover:text-white transition-colors">Cancel</button>
          <button v-if="editing" @click="deleteSkill(editing.id)"
                  class="px-4 py-1.5 text-xs text-red-400/60 hover:text-red-400 ml-auto transition-colors">Delete</button>
        </div>
      </div>

      <!-- Agent assignments (non-global only) -->
      <div v-if="editing && !form.isGlobal" class="bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800">
          <span class="text-sm font-medium text-white">Assigned Agents</span>
          <span class="ml-2 text-xs text-neutral-500">{{ assignedAgents.size }} assigned</span>
        </div>
        <div class="divide-y divide-neutral-800/50">
          <div v-for="agent in agents" :key="agent.id"
               class="px-4 py-2 flex items-center justify-between">
            <div>
              <span class="text-sm text-white">{{ agent.name }}</span>
              <span v-if="agent.isDefault" class="ml-2 text-[10px] text-neutral-500 border border-neutral-700 px-1">default</span>
            </div>
            <label class="flex items-center">
              <input type="checkbox" :checked="assignedAgents.has(agent.id)"
                     @change="toggleAgent(agent.id, editing.id)"
                     class="accent-white" />
            </label>
          </div>
        </div>
      </div>
      <div v-if="editing && form.isGlobal" class="bg-neutral-900 border border-neutral-800 px-4 py-3 text-xs text-neutral-500">
        This skill is global — it's automatically available to all agents.
      </div>
    </div>
  </div>
</template>
