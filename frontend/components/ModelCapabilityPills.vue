<script setup lang="ts">
/**
 * Interactive capability toggles (thinking / vision / audio) for an agent.
 *
 * Pills render only when the selected model advertises the capability. Each
 * pill is a button that toggles the agent's override for that capability:
 * colored when active, muted when disabled. Parents listen for the `toggle`
 * event and are responsible for persisting the new state (local form mutation
 * on the edit page, PUT /api/agents/{id} on the listing page).
 */
import type { ProviderModel } from '~/composables/useProviders'
import { Lightbulb, Eye, Volume2 } from 'lucide-vue-next'

export type Capability = 'thinking' | 'vision' | 'audio'

const props = withDefaults(defineProps<{
  model: ProviderModel | null | undefined
  /** Current thinking value — any non-empty string counts as "on". */
  thinkingMode?: string | null
  /** null or true render as on; only explicit false renders as off. */
  visionEnabled?: boolean | null
  audioEnabled?: boolean | null
  /** Controls pill height / icon size. Defaults to the compact listing size. */
  size?: 'sm' | 'md'
}>(), {
  thinkingMode: null,
  visionEnabled: null,
  audioEnabled: null,
  size: 'sm',
})

defineEmits<{
  (e: 'toggle', capability: Capability): void
}>()

interface PillDef {
  capability: Capability
  label: string
  icon: typeof Lightbulb
  supported: boolean
  enabled: boolean
  /** Tailwind classes for the ON (colored) variant. */
  onCls: string
}

const pills = computed<PillDef[]>(() => {
  const m = props.model
  if (!m) return []
  const all: PillDef[] = [
    {
      capability: 'thinking',
      label: 'thinking',
      icon: Lightbulb,
      supported: !!m.supportsThinking,
      enabled: !!props.thinkingMode,
      onCls: 'text-violet-400 border-violet-400/40 bg-violet-400/5 hover:bg-violet-400/10',
    },
    {
      capability: 'vision',
      label: 'vision',
      icon: Eye,
      supported: !!m.supportsVision,
      enabled: props.visionEnabled !== false,
      onCls: 'text-sky-400 border-sky-400/40 bg-sky-400/5 hover:bg-sky-400/10',
    },
    {
      capability: 'audio',
      label: 'audio',
      icon: Volume2,
      supported: !!m.supportsAudio,
      enabled: props.audioEnabled !== false,
      onCls: 'text-amber-400 border-amber-400/40 bg-amber-400/5 hover:bg-amber-400/10',
    },
  ]
  return all.filter(p => p.supported)
})

const sizeCls = computed(() =>
  props.size === 'md'
    ? 'text-xs px-2.5 py-1.5 gap-1.5'
    : 'text-[10px] px-1.5 py-0.5 gap-1',
)
const iconCls = computed(() => (props.size === 'md' ? 'w-3.5 h-3.5' : 'w-3 h-3'))

const offCls = 'text-neutral-500 border-neutral-600/40 bg-transparent hover:bg-neutral-500/5'

function tooltip(p: PillDef): string {
  const state = p.enabled ? 'on' : 'off'
  return `${p.label} is ${state} — click to toggle`
}
</script>

<template>
  <div
    v-if="pills.length"
    class="flex flex-wrap gap-1.5"
  >
    <button
      v-for="p in pills"
      :key="p.capability"
      type="button"
      :class="[
        'inline-flex items-center font-mono border rounded-sm transition-colors cursor-pointer',
        sizeCls,
        p.enabled ? p.onCls : offCls,
      ]"
      :title="tooltip(p)"
      :aria-pressed="p.enabled"
      @click.stop="$emit('toggle', p.capability)"
    >
      <component
        :is="p.icon"
        :class="iconCls"
        aria-hidden="true"
      />
      <span>{{ p.label }}</span>
    </button>
  </div>
</template>
