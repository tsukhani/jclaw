<script setup lang="ts">
import type { RecentRunView } from '~/types/api'
import { timelineBar } from '~/utils/timeline'

// JCLAW-22 (slice TL): runs-over-time view. Each task is a swimlane; each run
// is a bar positioned by startedAt, width proportional to duration, coloured
// by status. nowMs is passed in (the page's 1s ticker) so RUNNING bars grow.
const props = defineProps<{
  runs: RecentRunView[]
  nowMs: number
}>()

const emit = defineEmits<(e: 'select', run: RecentRunView) => void>()

function startOf(run: RecentRunView): number {
  return run.startedAt ? Date.parse(run.startedAt) : props.nowMs
}
function endOf(run: RecentRunView): number | null {
  return run.completedAt ? Date.parse(run.completedAt) : null
}

// Tight axis: earliest run on the left, now on the right (minimum 60s span so
// an all-just-now set still lays out sensibly).
const axis = computed(() => {
  const starts = props.runs.map(startOf)
  const earliest = starts.length ? Math.min(...starts) : props.nowMs - 3_600_000
  return { startMs: Math.min(earliest, props.nowMs - 60_000), endMs: props.nowMs }
})

const swimlanes = computed(() => {
  const byTask = new Map<string, RecentRunView[]>()
  for (const run of props.runs) {
    const key = run.taskName ?? '—'
    const list = byTask.get(key)
    if (list) list.push(run)
    else byTask.set(key, [run])
  }
  return [...byTask.entries()].map(([task, runs]) => ({ task, runs }))
})

const STATUS_BAR: Record<string, string> = {
  COMPLETED: 'bg-green-500',
  FAILED: 'bg-red-500',
  RUNNING: 'bg-blue-500',
  CANCELLED: 'bg-neutral-500',
  LOST: 'bg-orange-500',
}
function barColor(status: string | null): string {
  return STATUS_BAR[status ?? ''] ?? 'bg-neutral-500'
}

function barStyle(run: RecentRunView) {
  const b = timelineBar(startOf(run), endOf(run), axis.value.startMs, axis.value.endMs, props.nowMs)
  return { left: `${b.leftPct}%`, width: `${b.widthPct}%` }
}

function tooltip(run: RecentRunView): string {
  const dur = run.durationMs != null
    ? `${(run.durationMs / 1000).toFixed(1)}s`
    : run.status === 'RUNNING' ? 'running…' : '—'
  const when = run.startedAt ? new Date(run.startedAt).toLocaleString() : ''
  return `${run.taskName ?? 'task'} · ${run.status} · ${dur} · ${when}`
}

function axisLabel(ms: number): string {
  return new Date(ms).toLocaleString(undefined, {
    month: 'short', day: 'numeric', hour: '2-digit', minute: '2-digit',
  })
}
</script>

<template>
  <div class="bg-surface-elevated border border-border">
    <div
      v-if="!runs.length"
      class="px-4 py-8 text-center text-sm text-fg-muted"
    >
      No runs in the selected window.
    </div>
    <template v-else>
      <div class="flex items-center text-[10px] text-fg-muted px-4 py-2 border-b border-border bg-muted/30">
        <span class="w-40 shrink-0">Task</span>
        <span class="flex-1">{{ axisLabel(axis.startMs) }}</span>
        <span class="ml-auto">now</span>
      </div>
      <div class="divide-y divide-border">
        <div
          v-for="lane in swimlanes"
          :key="lane.task"
          class="flex items-center px-4 py-1.5"
        >
          <span
            class="w-40 shrink-0 truncate text-xs text-fg-primary pr-2"
            :title="lane.task"
          >{{ lane.task }}</span>
          <div class="relative flex-1 h-5 bg-muted/30">
            <button
              v-for="run in lane.runs"
              :key="run.id"
              type="button"
              class="absolute top-0.5 h-4 rounded-sm opacity-80 hover:opacity-100 cursor-pointer"
              :class="barColor(run.status)"
              :style="barStyle(run)"
              :title="tooltip(run)"
              :aria-label="tooltip(run)"
              @click="emit('select', run)"
            />
          </div>
        </div>
      </div>
      <div class="flex flex-wrap gap-3 px-4 py-2 text-[10px] text-fg-muted border-t border-border">
        <span class="flex items-center gap-1"><span class="w-2 h-2 bg-green-500 inline-block" />completed</span>
        <span class="flex items-center gap-1"><span class="w-2 h-2 bg-red-500 inline-block" />failed</span>
        <span class="flex items-center gap-1"><span class="w-2 h-2 bg-blue-500 inline-block" />running</span>
        <span class="flex items-center gap-1"><span class="w-2 h-2 bg-neutral-500 inline-block" />cancelled</span>
        <span class="flex items-center gap-1"><span class="w-2 h-2 bg-orange-500 inline-block" />lost</span>
      </div>
    </template>
  </div>
</template>
