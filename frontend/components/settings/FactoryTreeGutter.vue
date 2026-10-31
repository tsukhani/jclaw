<script setup lang="ts">
import { treeGutter, treeStem, type StoryRow } from './factory-board'

const props = defineProps<{ row: StoryRow }>()

const cells = computed(() => treeGutter(props.row))
const stem = computed(() => treeStem(props.row))
</script>

<template>
  <!-- Each row draws its own segments, so lines meet across rows only while the tree's rows have no dividers. The elbow
       sits on the first text line: the row's 0.625rem top padding plus half of text-sm's 1.25rem line. -->
  <span
    v-for="(cell, i) in cells"
    :key="i"
    class="relative w-4 shrink-0"
    aria-hidden="true"
    data-testid="factory-tree-cell"
    :data-cell="cell"
  >
    <span
      v-if="cell !== 'blank'"
      class="absolute left-1/2 top-0 border-l border-border"
      :class="cell === 'end' ? 'h-5' : 'bottom-0'"
    />
    <span
      v-if="cell !== 'pipe' && cell !== 'blank'"
      class="absolute left-1/2 -right-2 top-5 border-t border-border"
    />
  </span>
  <span
    v-if="stem"
    class="absolute top-[1.875rem] bottom-0 border-l border-border"
    :style="{ left: stem }"
    aria-hidden="true"
    data-testid="factory-tree-stem"
  />
</template>
