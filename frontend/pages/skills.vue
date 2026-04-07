<script setup lang="ts">
const { data: skills, refresh } = await useFetch<any[]>('/api/skills')

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
    refresh()
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
    refresh()
  } catch (e) {
    console.error('Failed to delete skill:', e)
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
              class="px-3 py-1.5 bg-emerald-600 text-white text-xs font-medium hover:bg-emerald-500 transition-colors">
        New Skill
      </button>
    </div>

    <!-- Skill list -->
    <div v-if="!editing && !creating" class="bg-neutral-900 border border-neutral-800">
      <div v-for="skill in skills" :key="skill.name"
           @click="editSkill(skill)"
           class="px-4 py-3 border-b border-neutral-800/50 flex items-center justify-between hover:bg-neutral-800/50 cursor-pointer transition-colors">
        <div>
          <span class="text-sm text-white font-mono">{{ skill.name }}</span>
          <span class="ml-2 text-[10px] text-green-400 border border-green-400/30 px-1">global</span>
          <div class="text-xs text-neutral-500 mt-0.5">{{ skill.description || '(no description)' }}</div>
        </div>
      </div>
      <div v-if="!skills?.length" class="px-4 py-8 text-center text-sm text-neutral-600">
        No global skills. Create one or let an agent create skills in its workspace.
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
