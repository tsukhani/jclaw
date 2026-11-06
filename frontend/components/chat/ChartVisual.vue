<script setup lang="ts">
/** A ```visual chart (JF-195): line, bar or scatter, drawn as SVG from the validated spec. */
import { computed, useId } from 'vue'
import VisualAxes from '~/components/chat/VisualAxes.vue'
import { linePath, linearScale } from '~/utils/visual/layout'
import { prefersReducedMotion } from '~/utils/visual/motion'
import type { ChartSpec } from '~/utils/visual/spec'
import { DASHES, MARKERS, axisHeader, isHollow, markerPath, seriesColor } from '~/utils/visual/style'

const props = defineProps<{ spec: ChartSpec }>()

const W = 560
const H = 300
const box = { left: 60, top: 18, right: W - 16, bottom: H - 46 }
const clipId = `visual-clip-${useId()}`
const drawIn = !prefersReducedMotion()
// 400 markers on a line chart bury the line; past this a line draws without them.
const MAX_LINE_MARKERS = 40

const xs = computed(() => [...new Set(props.spec.series.flatMap(s => s.points.map(([x]) => x)))].sort((a, b) => a - b))
const ys = computed(() => props.spec.series.flatMap(s => s.points.map(([, y]) => y)))
const isBar = computed(() => props.spec.chartType === 'bar')

// Bars sit in bands around each x; the band is the narrowest gap between neighbouring x values.
const band = computed(() => {
  const v = xs.value
  const gaps = v.slice(1).map((x, i) => x - v[i]!)
  return gaps.length ? Math.min(...gaps) : 1
})

const x = computed(() => isBar.value
  ? linearScale([xs.value[0]! - band.value / 2, xs.value.at(-1)! + band.value / 2], [box.left, box.right], { nice: false })
  : linearScale([...xs.value, ...(props.spec.marks ?? []).map(m => m.x)], [box.left, box.right]))
const y = computed(() => linearScale(ys.value, [box.bottom, box.top], { zero: isBar.value }))
const xTicks = computed(() => isBar.value || xs.value.length <= 12 ? xs.value : x.value.ticks)

const series = computed(() => props.spec.series.map((s, i) => {
  const pts = s.points.map(([px, py]) => [x.value(px), y.value(py)] as [number, number])
  const shape = MARKERS[i % MARKERS.length]!
  return {
    name: s.name,
    color: seriesColor(i),
    dash: DASHES[i % DASHES.length]!,
    shape,
    hollow: isHollow(shape),
    path: linePath(pts),
    markers: props.spec.chartType === 'scatter' || pts.length <= MAX_LINE_MARKERS
      ? pts.map(([px, py]) => markerPath(shape, px, py)).join(' ')
      : '',
  }
}))

const bars = computed(() => {
  if (!isBar.value) return []
  const count = props.spec.series.length
  const full = Math.abs(x.value(band.value) - x.value(0)) * 0.8
  const each = full / count
  const base = y.value(Math.max(y.value.domain[0], Math.min(0, y.value.domain[1])))
  return props.spec.series.flatMap((s, i) => s.points.map(([px, py]) => {
    const top = y.value(py)
    return {
      key: `${i}-${px}`,
      series: i,
      x: x.value(px) - full / 2 + i * each,
      y: Math.min(top, base),
      w: Math.max(1, each - 1),
      h: Math.abs(base - top),
    }
  }))
})

const marks = computed(() => (props.spec.marks ?? []).map(m => ({ x: x.value(m.x), label: m.label ?? '' })))
</script>

<template>
  <div class="visual-chart">
    <svg
      :viewBox="`0 0 ${W} ${H}`"
      role="img"
      :aria-label="spec.title"
      :class="{ 'visual-draw-in': drawIn }"
    >
      <defs>
        <clipPath :id="clipId">
          <rect
            class="visual-clip-rect"
            :x="box.left - 8"
            :y="0"
            :width="box.right - box.left + 16"
            :height="H"
          />
        </clipPath>
      </defs>
      <VisualAxes
        :x="x"
        :y="y"
        :x-ticks="xTicks"
        :box="box"
        :x-title="axisHeader(spec.x, '')"
        :y-title="axisHeader(spec.y, '')"
      />
      <g v-if="isBar">
        <rect
          v-for="b in bars"
          :key="b.key"
          class="visual-bar"
          :x="b.x"
          :y="b.y"
          :width="b.w"
          :height="b.h"
          :fill="series[b.series]!.color"
          :stroke="series[b.series]!.color"
          fill-opacity="0.8"
        />
        <!-- The series marker caps each bar, so bars are told apart without their colour. -->
        <path
          v-for="b in bars"
          :key="`m${b.key}`"
          class="visual-marker"
          :d="markerPath(series[b.series]!.shape, b.x + b.w / 2, b.y - 6)"
          :stroke="series[b.series]!.color"
          :fill="series[b.series]!.hollow ? 'none' : series[b.series]!.color"
        />
      </g>
      <g
        v-else
        :clip-path="`url(#${clipId})`"
      >
        <g
          v-for="s in series"
          :key="s.name"
          class="visual-series"
        >
          <path
            v-if="spec.chartType === 'line'"
            class="visual-line"
            :d="s.path"
            :stroke="s.color"
            :stroke-dasharray="s.dash"
            fill="none"
          />
          <path
            v-if="s.markers"
            class="visual-marker"
            :d="s.markers"
            :stroke="s.color"
            :fill="s.hollow ? 'none' : s.color"
          />
        </g>
      </g>
      <g
        v-for="(m, i) in marks"
        :key="`mark${i}`"
        class="visual-mark"
      >
        <line
          :x1="m.x"
          :x2="m.x"
          :y1="box.top"
          :y2="box.bottom"
        />
        <text
          :x="m.x + 4"
          :y="box.top + 10"
        >{{ m.label }}</text>
      </g>
    </svg>
    <ul class="visual-legend">
      <li
        v-for="s in series"
        :key="s.name"
      >
        <svg
          width="34"
          height="12"
          aria-hidden="true"
        >
          <line
            x1="1"
            x2="33"
            y1="6"
            y2="6"
            :stroke="s.color"
            :stroke-dasharray="s.dash"
            stroke-width="2"
          />
          <path
            :d="markerPath(s.shape, 17, 6)"
            :stroke="s.color"
            :fill="s.hollow ? 'none' : s.color"
          />
        </svg>
        {{ s.name }}
      </li>
    </ul>
  </div>
</template>
