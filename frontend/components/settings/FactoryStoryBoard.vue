<script setup lang="ts">
import { elapsed, groupStories, mergedLabel, type BoardStory } from './factory-board'

const props = defineProps<{
  stories: BoardStory[]
  selected: string | null
  /** When the status was read; elapsed times are recomputed per refresh, not per second. */
  now: number
}>()

const emit = defineEmits<{ select: [key: string] }>()

const groups = computed(() => groupStories(props.stories).filter(g => g.stories.length > 0))
</script>

<template>
  <section
    class="space-y-3"
    data-testid="factory-board"
  >
    <h3 class="text-sm font-medium text-fg-strong">
      Stories
    </h3>
    <p
      v-if="groups.length === 0"
      class="text-xs text-fg-muted"
    >
      No stories on the board.
    </p>
    <div
      v-for="g in groups"
      :key="g.id"
      class="space-y-1"
      :data-testid="`factory-group-${g.id}`"
    >
      <h4 class="text-xs font-medium text-fg-muted">
        {{ g.title }} ({{ g.stories.length }})
      </h4>
      <ul class="bg-surface-elevated border border-border divide-y divide-border">
        <li
          v-for="s in g.stories"
          :key="s.key"
        >
          <button
            type="button"
            class="w-full text-left px-4 py-2.5 flex max-sm:flex-wrap items-start gap-3 hover:bg-muted/40"
            :aria-pressed="selected === s.key"
            :data-testid="`factory-story-${s.key}`"
            @click="emit('select', s.key)"
          >
            <span class="text-sm font-mono text-fg-strong w-28 shrink-0">{{ s.key }}</span>
            <span class="text-sm text-fg-strong flex-1 min-w-0">{{ s.summary }}</span>
            <span
              v-if="s.autoMerge"
              class="text-xs border px-1 text-sky-800 dark:text-sky-400 border-sky-400/30"
              data-testid="factory-automerge-badge"
            >Auto-merge</span>
            <span
              v-if="s.state === 'running'"
              class="text-xs text-fg-muted"
              data-testid="factory-story-detail"
            >{{ s.phase }} · {{ elapsed(s.since, now) }}</span>
            <span
              v-else-if="s.state === 'merged'"
              class="text-xs text-fg-muted"
              data-testid="factory-story-detail"
            >{{ mergedLabel(s) }}<template v-if="s.sha"> · <span
              class="font-mono"
              :title="s.sha"
            >{{ s.sha.slice(0, 8) }}</span></template></span>
            <span
              v-else-if="s.reason"
              class="text-xs text-fg-muted"
              data-testid="factory-story-detail"
            ><template v-if="s.state === 'refused'">Auto-merge refused: </template>{{ s.reason }}</span>
          </button>
        </li>
      </ul>
    </div>
  </section>
</template>
