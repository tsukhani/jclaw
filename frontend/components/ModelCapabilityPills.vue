<script setup lang="ts">
/**
 * Capability indicators for an agent's selected model.
 *
 * The thinking pill is interactive on hybrid reasoning models — it renders
 * as a button that toggles the agent's reasoning mode. Parents listen for
 * the `toggle` event and persist the new state. The thinking off-switch is
 * real on hybrids: the LLM API carries a {@code reasoning_effort} /
 * {@code thinking_budget} parameter that the model honours, so the toggle
 * changes wire-level behaviour.
 *
 * Pure reasoning models ({@code model.alwaysThinks === true}, e.g. o1/o3,
 * DeepSeek-R1, Qwen QwQ) render the thinking pill as a non-interactive
 * on-color span instead. The provider API still accepts a "reasoning off"
 * value but the model thinks anyway, so a clickable toggle would lie about
 * the wire-level effect. Tooltip explains why.
 *
 * Vision and audio pills are purely informational. No major LLM API
 * exposes a "vision off" or "audio off" toggle — both modalities are
 * implicit (send images / audio = visible to the model; don't = not).
 * A client-side toggle would just be "don't attach images/audio," which
 * the operator can do directly by not attaching. So the pills indicate
 * capability ("this model handles X natively") without claiming to be
 * controls. Render as non-interactive spans; click does not emit
 * `toggle`. Audio's transcription pipeline (JCLAW-165) is the
 * universal-fallback story for non-audio-capable models; vision has no
 * such fallback today, so the pill is even more strictly informational.
 */
import type { ProviderModel } from '~/composables/useProviders'
import { Lightbulb, Eye, Volume2 } from 'lucide-vue-next'

export type Capability = 'thinking'

const props = withDefaults(defineProps<{
  model: ProviderModel | null | undefined
  /** Current thinking value — any non-empty string counts as "on". */
  thinkingMode?: string | null
  /** Controls pill height / icon size. Defaults to the compact listing size. */
  size?: 'sm' | 'md'
}>(), {
  thinkingMode: null,
  size: 'sm',
})

defineEmits<{
  (e: 'toggle', capability: Capability): void
}>()

interface PillDef {
  capability: Capability | 'vision' | 'audio'
  label: string
  icon: typeof Lightbulb
  supported: boolean
  enabled: boolean
  /** Tailwind classes for the ON (colored) variant. */
  onCls: string
  /** Whether the pill renders as a clickable button. False renders as a
   *  non-interactive span — used for capability indicators that don't
   *  have an LLM-API-level toggle the way thinking does. */
  interactive: boolean
  /** True when the pill is in a locked-on state (alwaysThinks pure
   *  reasoners). Triggers the darker violet shade + filled bulb so the
   *  user can distinguish "always-on" from a regular "on" at a glance. */
  locked: boolean
}

const pills = computed<PillDef[]>(() => {
  const m = props.model
  if (!m) return []
  // Pure reasoners always think regardless of thinkingMode — render with the
  // darker locked-on shade and a filled bulb, matching the chat composer's
  // lock behavior.
  const thinkingLocked = !!m.alwaysThinks
  const all: PillDef[] = [
    {
      capability: 'thinking',
      label: 'thinking',
      icon: Lightbulb,
      supported: !!m.supportsThinking,
      enabled: thinkingLocked || !!props.thinkingMode,
      onCls: thinkingLocked
        ? 'text-emerald-300 border-emerald-500/60 bg-emerald-500/15'
        : 'text-emerald-400 border-emerald-400/40 bg-emerald-400/5 hover:bg-emerald-400/10',
      interactive: !thinkingLocked,
      locked: thinkingLocked,
    },
    {
      capability: 'vision',
      label: 'vision',
      icon: Eye,
      supported: !!m.supportsVision,
      enabled: true,
      onCls: 'text-sky-400 border-sky-400/40 bg-sky-400/5',
      interactive: false,
      locked: false,
    },
    {
      capability: 'audio',
      label: 'audio',
      icon: Volume2,
      supported: !!m.supportsAudio,
      enabled: true,
      onCls: 'text-amber-400 border-amber-400/40 bg-amber-400/5',
      interactive: false,
      locked: false,
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
    if (p.capability === 'audio') {
      return 'audio — native passthrough; transcription handles models without it'
    }
    if (p.capability === 'vision') {
      return 'vision — this model accepts image inputs natively'
    }
    if (p.capability === 'thinking') {
      return 'thinking — this model always reasons; the toggle is fixed on'
    }
    return `${p.label} — supported by this model`
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
    <template
      v-for="p in pills"
      :key="p.capability"
    >
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
          :class="[iconCls, p.locked ? 'fill-current' : '']"
          aria-hidden="true"
        />
        <span>{{ p.label }}</span>
      </span>
    </template>
  </div>
</template>
