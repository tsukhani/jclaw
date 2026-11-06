<script setup lang="ts">
/**
 * One ```visual fence drawn inline (JF-195). utils/visual/directive.ts mounts it into a finished message's figure while
 * the figure is near the viewport. Every kind carries a Data disclosure with its numbers in a real table.
 */
import { computed, onErrorCaptured, ref } from 'vue'
import ChartVisual from '~/components/chat/ChartVisual.vue'
import DiagramVisual from '~/components/chat/DiagramVisual.vue'
import ExploreVisual from '~/components/chat/ExploreVisual.vue'
import { interpolateFrames, snapParam } from '~/utils/visual/explore'
import type { ExploreSpec, VisualSpec, VisualState } from '~/utils/visual/spec'
import { axisHeader, formatNumber } from '~/utils/visual/style'

const props = defineProps<{ spec: VisualSpec, initial: VisualState }>()
const emit = defineEmits<{ change: [state: VisualState], failed: [message: string] }>()

const state = ref<VisualState>({ ...props.initial })

function onChange(next: VisualState) {
  state.value = next
  emit('change', next)
}

onErrorCaptured((err) => {
  emit('failed', `Could not draw this visual: ${err instanceof Error ? err.message : String(err)}`)
  return false
})

const paramText = (spec: ExploreSpec, values: Record<string, number>) =>
  spec.params.map(p => `${p.label} ${formatNumber(values[p.id]!)}${p.unit ? ` ${p.unit}` : ''}`).join(', ')

const explore = computed(() => {
  const spec = props.spec
  if (spec.kind !== 'explore') return null
  const values = Object.fromEntries(spec.params.map(p => [p.id, snapParam(p, state.value.values?.[p.id] ?? p.value)]))
  const current = interpolateFrames(spec, values)
  return {
    xHeader: axisHeader(spec.x, 'x'),
    currentHeader: `Shown (${paramText(spec, values)})`,
    frameHeaders: spec.frames.map(f => paramText(spec, f.params)),
    rows: current.map(([x, y], i) => ({ x, y, frames: spec.frames.map(f => f.points[i]![1]) })),
  }
})
</script>

<template>
  <div class="visual-figure">
    <p
      v-if="spec.caption"
      class="visual-title"
    >
      {{ spec.title }}
    </p>
    <ChartVisual
      v-if="spec.kind === 'chart'"
      :spec="spec"
    />
    <DiagramVisual
      v-else-if="spec.kind === 'diagram'"
      :spec="spec"
      :initial="state"
      @change="onChange"
    />
    <ExploreVisual
      v-else
      :spec="spec"
      :initial="state"
      @change="onChange"
    />
    <details class="visual-data">
      <summary>Data</summary>
      <table v-if="spec.kind === 'chart'">
        <thead>
          <tr>
            <th scope="col">
              Series
            </th>
            <th scope="col">
              {{ axisHeader(spec.x, 'x') }}
            </th>
            <th scope="col">
              {{ axisHeader(spec.y, 'y') }}
            </th>
          </tr>
        </thead>
        <tbody>
          <template
            v-for="s in spec.series"
            :key="s.name"
          >
            <tr
              v-for="(p, i) in s.points"
              :key="i"
            >
              <th scope="row">
                {{ s.name }}
              </th>
              <td>{{ p[0] }}</td>
              <td>{{ p[1] }}</td>
            </tr>
          </template>
        </tbody>
      </table>
      <template v-else-if="spec.kind === 'diagram'">
        <table>
          <caption>Nodes</caption>
          <thead>
            <tr>
              <th scope="col">
                Node
              </th>
              <th scope="col">
                Detail
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="n in spec.nodes"
              :key="n.id"
            >
              <th scope="row">
                {{ n.label }}
              </th>
              <td>{{ n.detail ?? '' }}</td>
            </tr>
          </tbody>
        </table>
        <table v-if="spec.edges.length">
          <caption>Edges</caption>
          <thead>
            <tr>
              <th scope="col">
                From
              </th>
              <th scope="col">
                To
              </th>
              <th scope="col">
                Label
              </th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="(e, i) in spec.edges"
              :key="i"
            >
              <th scope="row">
                {{ spec.nodes.find(n => n.id === e.from)?.label ?? e.from }}
              </th>
              <td>{{ spec.nodes.find(n => n.id === e.to)?.label ?? e.to }}</td>
              <td>{{ e.label ?? '' }}</td>
            </tr>
          </tbody>
        </table>
      </template>
      <table v-else-if="explore">
        <thead>
          <tr>
            <th scope="col">
              {{ explore.xHeader }}
            </th>
            <th scope="col">
              {{ explore.currentHeader }}
            </th>
            <th
              v-for="(h, i) in explore.frameHeaders"
              :key="i"
              scope="col"
            >
              {{ h }}
            </th>
          </tr>
        </thead>
        <tbody>
          <tr
            v-for="(r, i) in explore.rows"
            :key="i"
          >
            <th scope="row">
              {{ r.x }}
            </th>
            <td>{{ formatNumber(r.y) }}</td>
            <td
              v-for="(fy, j) in r.frames"
              :key="j"
            >
              {{ fy }}
            </td>
          </tr>
        </tbody>
      </table>
    </details>
  </div>
</template>
