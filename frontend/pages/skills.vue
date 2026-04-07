<script setup lang="ts">
const { data: agents } = await useFetch<any[]>('/api/agents')
const selectedAgentId = ref<number | null>(null)
const skills = ref<any[]>([])
const selectedSkill = ref<any>(null)
const skillContent = ref('')
const saving = ref(false)

watch(agents, (val) => {
  if (val?.length && !selectedAgentId.value) {
    selectedAgentId.value = val[0].id
  }
}, { immediate: true })

watch(selectedAgentId, (id) => {
  if (!id) return
  selectedSkill.value = null
  skillContent.value = ''
  loadSkills()
})

async function loadSkills() {
  if (!selectedAgentId.value) return
  try {
    const data = await $fetch<any>(`/api/agents/${selectedAgentId.value}/workspace/skills`)
    // Parse the directory listing to extract skill names
    if (data?.content) {
      skills.value = data.content.split('\n')
        .filter((s: string) => s.endsWith('/'))
        .map((s: string) => ({ name: s.replace('/', '') }))
    }
  } catch {
    skills.value = []
  }
}

async function selectSkill(skill: any) {
  selectedSkill.value = skill
  try {
    const data = await $fetch<any>(
      `/api/agents/${selectedAgentId.value}/workspace/skills/${skill.name}/SKILL.md`
    )
    skillContent.value = data?.content ?? ''
  } catch {
    skillContent.value = '(File not found)'
  }
}

async function saveSkill() {
  if (!selectedAgentId.value || !selectedSkill.value) return
  saving.value = true
  try {
    await $fetch(
      `/api/agents/${selectedAgentId.value}/workspace/skills/${selectedSkill.value.name}/SKILL.md`,
      { method: 'PUT', body: { content: skillContent.value } }
    )
  } catch (e) {
    console.error('Failed to save skill:', e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-white mb-6">Skills</h1>

    <!-- Agent selector -->
    <div class="mb-4">
      <label class="text-xs text-neutral-500 mr-2">Agent:</label>
      <select v-model="selectedAgentId"
              class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1 focus:outline-none">
        <option v-for="agent in agents" :key="agent.id" :value="agent.id">{{ agent.name }}</option>
      </select>
    </div>

    <div class="flex gap-4">
      <!-- Skill list -->
      <div class="w-48 shrink-0 bg-neutral-900 border border-neutral-800">
        <div class="px-3 py-2 border-b border-neutral-800 text-xs text-neutral-500 font-medium">
          Skills ({{ skills.length }})
        </div>
        <div v-for="skill in skills" :key="skill.name"
             @click="selectSkill(skill)"
             :class="selectedSkill?.name === skill.name ? 'bg-neutral-800 text-white' : 'text-neutral-400'"
             class="px-3 py-2 text-sm hover:bg-neutral-800 cursor-pointer transition-colors">
          {{ skill.name }}
        </div>
        <div v-if="!skills.length" class="px-3 py-4 text-xs text-neutral-600 text-center">
          No skills
        </div>
      </div>

      <!-- Skill editor -->
      <div v-if="selectedSkill" class="flex-1 bg-neutral-900 border border-neutral-800">
        <div class="px-4 py-2.5 border-b border-neutral-800 flex items-center justify-between">
          <span class="text-sm text-white font-mono">{{ selectedSkill.name }}/SKILL.md</span>
          <button @click="saveSkill" :disabled="saving"
                  class="px-3 py-1 bg-neutral-800 text-xs text-neutral-300 hover:text-white border border-neutral-700 transition-colors">
            {{ saving ? 'Saving...' : 'Save' }}
          </button>
        </div>
        <textarea
          v-model="skillContent"
          rows="24"
          class="w-full px-4 py-3 bg-transparent text-sm text-neutral-300 font-mono resize-y focus:outline-none"
        />
      </div>
      <div v-else class="flex-1 flex items-center justify-center text-sm text-neutral-600">
        Select a skill to view
      </div>
    </div>
  </div>
</template>
