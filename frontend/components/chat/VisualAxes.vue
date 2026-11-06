<script setup lang="ts">
/** The axes, ticks and axis titles a chart or explore figure draws its data over (JF-195). */
import { formatTick, type Scale } from '~/utils/visual/layout'

defineProps<{
  x: Scale
  y: Scale
  xTicks: number[]
  box: { left: number, top: number, right: number, bottom: number }
  xTitle: string
  yTitle: string
}>()
</script>

<template>
  <g
    class="visual-axes"
    aria-hidden="true"
  >
    <g
      v-for="t in y.ticks"
      :key="`y${t}`"
    >
      <line
        class="visual-grid"
        :x1="box.left"
        :x2="box.right"
        :y1="y(t)"
        :y2="y(t)"
      />
      <text
        class="visual-tick"
        :x="box.left - 6"
        :y="y(t)"
        text-anchor="end"
        dominant-baseline="middle"
      >
        {{ formatTick(t) }}
      </text>
    </g>
    <text
      v-for="t in xTicks"
      :key="`x${t}`"
      class="visual-tick"
      :x="x(t)"
      :y="box.bottom + 14"
      text-anchor="middle"
    >
      {{ formatTick(t) }}
    </text>
    <line
      class="visual-axis"
      :x1="box.left"
      :x2="box.right"
      :y1="box.bottom"
      :y2="box.bottom"
    />
    <line
      class="visual-axis"
      :x1="box.left"
      :x2="box.left"
      :y1="box.top"
      :y2="box.bottom"
    />
    <text
      v-if="xTitle"
      class="visual-axis-title"
      :x="(box.left + box.right) / 2"
      :y="box.bottom + 32"
      text-anchor="middle"
    >
      {{ xTitle }}
    </text>
    <text
      v-if="yTitle"
      class="visual-axis-title"
      :transform="`translate(12 ${(box.top + box.bottom) / 2}) rotate(-90)`"
      text-anchor="middle"
    >
      {{ yTitle }}
    </text>
  </g>
</template>
