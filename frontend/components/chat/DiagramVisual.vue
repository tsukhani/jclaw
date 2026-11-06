<script setup lang="ts">
/** A ```visual diagram (JF-195): nodes and edges in a flow, tree or cycle layout, with an optional step strip. */
import { computed, onBeforeUnmount, ref, useId } from 'vue'
import { layoutDiagram } from '~/utils/visual/layout'
import { prefersReducedMotion } from '~/utils/visual/motion'
import type { DiagramSpec, VisualState } from '~/utils/visual/spec'

const props = defineProps<{ spec: DiagramSpec, initial: VisualState }>()
const emit = defineEmits<{ change: [state: VisualState] }>()

const STEP_MS = 2500
const uid = useId()
const arrowId = `visual-arrow-${uid}`
const reducedMotion = prefersReducedMotion()

const layout = computed(() => layoutDiagram(props.spec))
const steps = computed(() => props.spec.steps ?? [])
const startStep = props.initial.step
const step = ref<number | null>(startStep != null && startStep >= 0 && startStep < steps.value.length ? startStep : null)
const playing = ref(false)
let timer: ReturnType<typeof setTimeout> | null = null

const current = computed(() => step.value == null ? null : steps.value[step.value] ?? null)
const focus = computed(() => new Set(current.value?.focus ?? []))
const labelOf = (id: string) => props.spec.nodes.find(n => n.id === id)?.label ?? id
const focusDetails = computed(() => props.spec.nodes.filter(n => focus.value.has(n.id) && n.detail))

function nodeClass(id: string) {
  if (!current.value) return {}
  return { 'visual-focus': focus.value.has(id), 'visual-dim': !focus.value.has(id) }
}

function select(i: number | null) {
  step.value = i
  emit('change', { step: i })
}

function stopPlaying() {
  playing.value = false
  if (timer) clearTimeout(timer)
  timer = null
}

function choose(i: number) {
  stopPlaying()
  select(i)
}

function advance() {
  const next = (step.value ?? -1) + 1
  if (next >= steps.value.length) return stopPlaying()
  select(next)
  if (next === steps.value.length - 1) return stopPlaying()
  timer = setTimeout(advance, STEP_MS)
}

function togglePlay() {
  if (playing.value) return stopPlaying()
  playing.value = true
  if (step.value == null || step.value >= steps.value.length - 1) {
    select(0)
    timer = setTimeout(advance, STEP_MS)
  }
  else advance()
}

onBeforeUnmount(stopPlaying)
</script>

<template>
  <div class="visual-diagram">
    <svg
      :viewBox="`0 0 ${layout.width} ${layout.height}`"
      :style="{ maxWidth: `${layout.width}px` }"
      role="img"
      :aria-label="spec.title"
    >
      <defs>
        <marker
          :id="arrowId"
          viewBox="0 0 10 10"
          refX="9"
          refY="5"
          markerWidth="7"
          markerHeight="7"
          orient="auto-start-reverse"
        >
          <path
            d="M0,0 L10,5 L0,10 Z"
            class="visual-arrow"
          />
        </marker>
      </defs>
      <g
        v-for="(e, i) in layout.edges"
        :key="`e${i}`"
        class="visual-edge"
        :class="{ 'visual-back': e.back, 'visual-dim': current && !(focus.has(e.from) && focus.has(e.to)) }"
      >
        <path
          :d="e.path"
          fill="none"
          :marker-end="`url(#${arrowId})`"
        />
        <text
          v-if="e.label"
          class="visual-edge-label"
          :x="e.labelX"
          :y="e.labelY"
          text-anchor="middle"
        >{{ e.label }}</text>
      </g>
      <g
        v-for="n in layout.nodes"
        :key="n.id"
        class="visual-node"
        :class="nodeClass(n.id)"
        :data-node="n.id"
      >
        <!-- The browser shows the title on hover, so a node's detail is a hover away without a script handler. -->
        <title>{{ n.lines.join(' ') }}{{ n.detail ? `: ${n.detail}` : '' }}</title>
        <rect
          :x="n.x"
          :y="n.y"
          :width="n.w"
          :height="n.h"
          rx="6"
        />
        <text
          :x="n.x + n.w / 2"
          :y="n.y + n.h / 2 - ((n.lines.length - 1) * 15) / 2"
          text-anchor="middle"
          dominant-baseline="middle"
        >
          <tspan
            v-for="(line, j) in n.lines"
            :key="j"
            :x="n.x + n.w / 2"
            :dy="j ? 15 : 0"
          >{{ line }}</tspan>
        </text>
      </g>
    </svg>
    <div
      v-if="steps.length"
      class="visual-steps"
    >
      <div class="visual-step-controls">
        <button
          type="button"
          :disabled="step == null || step === 0"
          @click="choose((step ?? 1) - 1)"
        >
          Previous
        </button>
        <button
          type="button"
          :disabled="reducedMotion"
          :aria-pressed="playing"
          :title="reducedMotion ? 'Playback is off while reduced motion is on' : undefined"
          @click="togglePlay"
        >
          {{ playing ? 'Pause' : 'Play' }}
        </button>
        <button
          type="button"
          :disabled="step === steps.length - 1"
          @click="choose((step ?? -1) + 1)"
        >
          Next
        </button>
      </div>
      <ol class="visual-step-list">
        <li
          v-for="(s, i) in steps"
          :key="i"
        >
          <button
            type="button"
            :aria-current="i === step ? 'step' : undefined"
            @click="choose(i)"
          >
            {{ s.title }}
          </button>
        </li>
      </ol>
      <div
        class="visual-step-body"
        aria-live="polite"
      >
        <template v-if="current">
          <p>{{ current.body }}</p>
          <p
            v-for="n in focusDetails"
            :key="n.id"
            class="visual-detail"
          >
            <strong>{{ labelOf(n.id) }}:</strong> {{ n.detail }}
          </p>
        </template>
      </div>
    </div>
  </div>
</template>
