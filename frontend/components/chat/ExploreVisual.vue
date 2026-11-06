<script setup lang="ts">
/**
 * A ```visual explore figure (JF-195): sliders over precomputed frames. Moving one interpolates between the frames
 * that bracket it; nothing is fetched and nothing the model wrote is evaluated.
 */
import { computed, onBeforeUnmount, reactive, ref, useId, watch } from 'vue'
import VisualAxes from '~/components/chat/VisualAxes.vue'
import { interpolateFrames, snapParam } from '~/utils/visual/explore'
import { linePath, linearScale } from '~/utils/visual/layout'
import { prefersReducedMotion } from '~/utils/visual/motion'
import type { ExploreSpec, VisualState } from '~/utils/visual/spec'
import { MARKERS, axisHeader, formatNumber, markerPath, seriesColor } from '~/utils/visual/style'

const props = defineProps<{ spec: ExploreSpec, initial: VisualState }>()
const emit = defineEmits<{ change: [state: VisualState] }>()

const W = 560
const H = 300
const TWEEN_MS = 250
const MAX_MARKERS = 40
const box = { left: 60, top: 18, right: W - 16, bottom: H - 46 }
const uid = useId()
const reducedMotion = prefersReducedMotion()

const values = reactive<Record<string, number>>(Object.fromEntries(props.spec.params.map(p =>
  [p.id, snapParam(p, props.initial.values?.[p.id] ?? p.value)])))

const target = computed(() => interpolateFrames(props.spec, values))
const shown = ref<number[]>(target.value.map(([, y]) => y))
let frame: number | null = null

function cancelTween() {
  if (frame != null) cancelAnimationFrame(frame)
  frame = null
}

watch(target, (next) => {
  cancelTween()
  const to = next.map(([, y]) => y)
  if (reducedMotion || typeof requestAnimationFrame === 'undefined') {
    shown.value = to
    return
  }
  const from = shown.value
  const start = performance.now()
  const step = (now: number) => {
    const t = Math.min(1, (now - start) / TWEEN_MS)
    const ease = 1 - (1 - t) ** 3
    shown.value = to.map((y, i) => (from[i] ?? y) + ease * (y - (from[i] ?? y)))
    frame = t < 1 ? requestAnimationFrame(step) : null
  }
  frame = requestAnimationFrame(step)
})

watch(values, () => emit('change', { values: { ...values } }))
onBeforeUnmount(cancelTween)

const xs = computed(() => props.spec.frames[0]!.points.map(([x]) => x))
const x = computed(() => linearScale(xs.value, [box.left, box.right]))
// The y axis spans every frame, so it holds still while the curve moves.
const y = computed(() => linearScale(props.spec.frames.flatMap(f => f.points.map(([, py]) => py)), [box.bottom, box.top]))
const xTicks = computed(() => xs.value.length <= 12 ? xs.value : x.value.ticks)
const pts = computed(() => xs.value.map((px, i) => [x.value(px), y.value(shown.value[i] ?? 0)] as [number, number]))
const path = computed(() => linePath(pts.value))
const markers = computed(() => pts.value.length <= MAX_MARKERS
  ? pts.value.map(([px, py]) => markerPath(MARKERS[0]!, px, py)).join(' ')
  : '')

function onInput(id: string, event: Event) {
  const param = props.spec.params.find(p => p.id === id)!
  values[id] = snapParam(param, Number((event.target as HTMLInputElement).value))
}
</script>

<template>
  <div class="visual-explore">
    <svg
      :viewBox="`0 0 ${W} ${H}`"
      role="img"
      :aria-label="spec.title"
    >
      <VisualAxes
        :x="x"
        :y="y"
        :x-ticks="xTicks"
        :box="box"
        :x-title="axisHeader(spec.x, '')"
        :y-title="axisHeader(spec.y, '')"
      />
      <path
        class="visual-line"
        :d="path"
        :stroke="seriesColor(0)"
        fill="none"
      />
      <path
        v-if="markers"
        class="visual-marker"
        :d="markers"
        :stroke="seriesColor(0)"
        :fill="seriesColor(0)"
      />
    </svg>
    <div class="visual-params">
      <div
        v-for="p in spec.params"
        :key="p.id"
        class="visual-param"
      >
        <label
          :for="`${uid}-${p.id}`"
          class="visual-param-label"
        >
          <span>{{ p.label }}</span>
          <input
            :id="`${uid}-${p.id}`"
            type="range"
            :min="p.min"
            :max="p.max"
            :step="p.step"
            :value="values[p.id]"
            @input="onInput(p.id, $event)"
          >
        </label>
        <span class="visual-param-value">{{ formatNumber(values[p.id]!) }}{{ p.unit ? ` ${p.unit}` : '' }}</span>
      </div>
    </div>
  </div>
</template>
