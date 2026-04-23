<script setup lang="ts">
/**
 * Renders capability badges (thinking / vision / audio) for the currently
 * selected model. Silently renders nothing when no model is provided, or
 * when the model advertises none of the three capabilities.
 */
import type { ProviderModel } from '~/composables/useProviders'

const props = defineProps<{
  model: ProviderModel | null | undefined
  /** When true, render pills inline on a single line instead of wrapping. */
  compact?: boolean
}>()

interface Pill { label: string, cls: string }

const pills = computed<Pill[]>(() => {
  const m = props.model
  if (!m) return []
  const out: Pill[] = []
  if (m.supportsThinking) {
    out.push({ label: 'thinking', cls: 'text-violet-400 border-violet-400/40 bg-violet-400/5' })
  }
  if (m.supportsVision) {
    out.push({ label: 'vision', cls: 'text-sky-400 border-sky-400/40 bg-sky-400/5' })
  }
  if (m.supportsAudio) {
    out.push({ label: 'audio', cls: 'text-amber-400 border-amber-400/40 bg-amber-400/5' })
  }
  return out
})
</script>

<template>
  <div
    v-if="pills.length"
    class="flex flex-wrap gap-1"
    :class="compact ? 'items-center' : ''"
  >
    <span
      v-for="p in pills"
      :key="p.label"
      class="text-[10px] font-mono px-1.5 py-0.5 border rounded-sm"
      :class="p.cls"
    >
      {{ p.label }}
    </span>
  </div>
</template>
