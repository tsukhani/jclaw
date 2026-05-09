<script setup lang="ts">
/**
 * Capability indicators for an agent's selected model.
 *
 * Thinking and vision pills are interactive — they render as buttons that
 * toggle the agent's per-capability override. Parents listen for the
 * `toggle` event and persist the new state.
 *
 * The audio pill is purely informational (JCLAW-165): it indicates "this
 * model accepts native audio passthrough" without offering a toggle, since
 * the transcription pipeline gives every model an audio path. Operators
 * can't *disable* native audio — there's nothing to opt-out of when
 * transcription is the universal fallback. Renders as a non-interactive
 * span; click does not emit the `toggle` event.
 */
import type { ProviderModel } from '~/composables/useProviders'
import { Lightbulb, Eye, Volume2 } from 'lucide-vue-next'

export type Capability = 'thinking' | 'vision'

const props = withDefaults(defineProps<{
  model: ProviderModel | null | undefined
  /** Current thinking value — any non-empty string counts as "on". */
  thinkingMode?: string | null
  /** null or true render as on; only explicit false renders as off. */
  visionEnabled?: boolean | null
  /** Controls pill height / icon size. Defaults to the compact listing size. */
  size?: 'sm' | 'md'
}>(), {
  thinkingMode: null,
  visionEnabled: null,
  size: 'sm',
})

defineEmits<{
  (e: 'toggle', capability: Capability): void
}>()

interface PillDef {
  capability: Capability | 'audio'
  label: string
  icon: typeof Lightbulb
  supported: boolean
  enabled: boolean
  /** Tailwind classes for the ON (colored) variant. */
  onCls: string
  /** Whether the pill renders as a clickable button. False renders as a
   *  non-interactive span — used for the audio capability indicator,
   *  which has no per-agent override to toggle (JCLAW-165). */
  interactive: boolean
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
      interactive: true,
    },
    {
      capability: 'vision',
      label: 'vision',
      icon: Eye,
      supported: !!m.supportsVision,
      enabled: props.visionEnabled !== false,
      onCls: 'text-sky-400 border-sky-400/40 bg-sky-400/5 hover:bg-sky-400/10',
      interactive: true,
    },
    {
      // JCLAW-165: capability indicator only — there's no per-agent override
      // to toggle, since transcription gives every model an audio path. The
      // pill exists so operators can see at a glance which models accept
      // native audio passthrough.
      capability: 'audio',
      label: 'audio',
      icon: Volume2,
      supported: !!m.supportsAudio,
      enabled: true,
      onCls: 'text-amber-400 border-amber-400/40 bg-amber-400/5',
      interactive: false,
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
  if (!p.interactive) {
    return `${p.label} — native passthrough; transcription handles models without it`
  }
  const state = p.enabled ? 'on' : 'off'
  return `${p.label} is ${state} — click to toggle`
}
</script>

<template>
  <div
    v-if="pills.length"
    class="flex flex-wrap gap-1.5"
  >
    <template v-for="p in pills" :key="p.capability">
      <button
        v-if="p.interactive"
        type="button"
        :class="[
          'inline-flex items-center font-mono border rounded-sm transition-colors cursor-pointer',
          sizeCls,
          p.enabled ? p.onCls : offCls,
        ]"
        :title="tooltip(p)"
        :aria-pressed="p.enabled"
        @click.stop="$emit('toggle', p.capability as Capability)"
      >
        <component
          :is="p.icon"
          :class="iconCls"
          aria-hidden="true"
        />
        <span>{{ p.label }}</span>
      </button>
      <span
        v-else
        :class="[
          'inline-flex items-center font-mono border rounded-sm cursor-default',
          sizeCls,
          p.onCls,
        ]"
        :title="tooltip(p)"
      >
        <component
          :is="p.icon"
          :class="iconCls"
          aria-hidden="true"
        />
        <span>{{ p.label }}</span>
      </span>
    </template>
  </div>
</template>
