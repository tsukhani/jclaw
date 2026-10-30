<script setup lang="ts">
import { ChevronRightIcon } from '@heroicons/vue/24/outline'
import { boardGroups, elapsed, mergedLabel, outsideDetail, rowIndent, type BoardBlocker, type BoardStory } from './factory-board'

const props = defineProps<{
  stories: BoardStory[]
  /** Open stories outside the factory that keep waiting ones from starting; drawn in Waiting's tree. */
  blockers: BoardBlocker[]
  selected: string | null
  /** When the status was read; elapsed times are recomputed per refresh, not per second. */
  now: number
}>()

const emit = defineEmits<{ select: [key: string] }>()

const groups = computed(() => boardGroups(props.stories, props.blockers).filter(g => g.stories.length > 0))

const collapsed = ref<Record<string, boolean>>({})
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
      :data-testid="`factory-group-${g.id}`"
    >
      <h4>
        <button
          type="button"
          class="w-full flex items-center gap-1 py-1 text-left text-xs font-medium text-fg-muted hover:text-fg-strong"
          :aria-expanded="!collapsed[g.id]"
          :data-testid="`factory-toggle-${g.id}`"
          @click="collapsed[g.id] = !collapsed[g.id]"
        >
          <ChevronRightIcon
            class="w-3.5 h-3.5 shrink-0 transition-transform duration-150"
            :class="collapsed[g.id] ? '' : 'rotate-90'"
            aria-hidden="true"
          />
          {{ g.title }} ({{ g.stories.length }})<template v-if="g.outside > 0">
            · {{ g.outside }} outside the factory
          </template>
        </button>
      </h4>
      <ul
        v-if="!collapsed[g.id]"
        class="bg-surface-elevated border border-border divide-y divide-border"
      >
        <li
          v-for="r in g.rows"
          :key="r.key"
          :data-depth="r.depth"
        >
          <button
            v-if="r.kind === 'story'"
            type="button"
            class="w-full text-left px-4 py-2.5 flex flex-wrap items-start gap-3 hover:bg-muted/40"
            :style="{ paddingLeft: rowIndent(r.depth) }"
            :aria-pressed="selected === r.key"
            :data-testid="`factory-story-${r.key}`"
            @click="emit('select', r.key)"
          >
            <span class="text-sm font-mono text-fg-strong w-28 shrink-0">{{ r.key }}</span>
            <span class="text-sm text-fg-strong flex-1 min-w-[min(12rem,100%)]">{{ r.story.summary }}</span>
            <span
              v-if="r.story.autoMerge"
              class="text-xs border px-1 text-sky-800 dark:text-sky-400 border-sky-400/30"
              data-testid="factory-automerge-badge"
            >Auto-merge</span>
            <span
              v-if="r.story.state === 'running'"
              class="text-xs text-fg-muted"
              data-testid="factory-story-detail"
            >{{ r.story.phase }} · {{ elapsed(r.story.since, now) }}</span>
            <span
              v-else-if="r.story.state === 'merged'"
              class="text-xs text-fg-muted"
              data-testid="factory-story-detail"
            >{{ mergedLabel(r.story) }}<template v-if="r.story.sha"> · <span
              class="font-mono"
              :title="r.story.sha"
            >{{ r.story.sha.slice(0, 8) }}</span></template></span>
            <span
              v-else-if="r.story.reason"
              class="text-xs text-fg-muted"
              data-testid="factory-story-detail"
            ><template v-if="r.story.state === 'refused'">Auto-merge refused: </template>{{ r.story.reason }}</span>
          </button>
          <div
            v-else
            class="px-4 py-2.5 flex flex-wrap items-start gap-3"
            :style="{ paddingLeft: rowIndent(r.depth) }"
            :data-testid="`factory-outside-${r.key}`"
          >
            <span class="text-sm font-mono text-fg-muted w-28 shrink-0">{{ r.key }}</span>
            <span class="text-sm text-fg-muted flex-1 min-w-[min(12rem,100%)]">{{ r.blocker.summary }}</span>
            <span class="text-xs border px-1 text-fg-muted border-border">Outside the factory</span>
            <span
              class="text-xs text-fg-muted"
              data-testid="factory-outside-detail"
            >{{ outsideDetail(r.blocker) }}</span>
          </div>
        </li>
      </ul>
    </div>
  </section>
</template>
