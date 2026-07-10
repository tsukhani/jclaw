<script setup lang="ts">
import type { Agent } from '~/types/api'

/**
 * Chat header agent picker (JCLAW-690 stage 5d; behaviour extracted verbatim
 * from pages/chat.vue). A `<select>` when more than one agent exists, static
 * text in the single-agent case (nothing to pick). v-models the selected agent
 * id back to the page; owns its own a11y label id.
 */
const props = defineProps<{
  agents: Agent[] | null | undefined
  modelValue: number | null
}>()
const emit = defineEmits<{ 'update:modelValue': [value: number | null] }>()

// A11y: generated id for label/control association.
const agentSelectId = useId()

const selected = computed({
  get: () => props.modelValue,
  set: value => emit('update:modelValue', value),
})

// Single-agent display name — the same lookup useAgentModel's selectedAgent does.
const selectedName = computed(() => props.agents?.find(a => a.id === props.modelValue)?.name)
</script>

<template>
  <label
    v-if="(agents?.length ?? 0) > 1"
    :for="agentSelectId"
    class="text-sm text-fg-muted flex items-center gap-1.5"
  >
    <span>Agent:</span>
    <select
      :id="agentSelectId"
      v-model="selected"
      class="bg-transparent border-0 text-base text-fg-strong px-1 py-1
             focus:outline-hidden cursor-pointer hover:bg-muted rounded"
    >
      <option
        v-for="agent in agents"
        :key="agent.id"
        :value="agent.id"
      >
        {{ agent.name }}
      </option>
    </select>
  </label>
  <!--
    Single-agent case: no dropdown — the user has nothing to pick
    between. Render as static text to preserve the same horizontal
    slot (keeps the absolute-centered model combobox optically
    centered) while making it obvious no choice is expected. Uses
    a div, not a label: there's no input to associate with.
  -->
  <div
    v-else-if="(agents?.length ?? 0) === 1"
    class="text-sm text-fg-muted flex items-center gap-1.5"
  >
    <span>Agent:</span>
    <span class="text-base text-fg-strong px-1 py-1">{{ selectedName }}</span>
  </div>
</template>
