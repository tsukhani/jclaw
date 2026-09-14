<script setup lang="ts">
import { computed, nextTick, onUnmounted, ref, watch } from 'vue'
import { ChevronDownIcon } from '@heroicons/vue/24/outline'
import { CheckCircleIcon, ClockIcon, StopCircleIcon, XCircleIcon } from '@heroicons/vue/16/solid'
import type { SubagentChip, SubagentRunStatus } from '~/composables/useChatSubagentChips'
import { SUBAGENT_STATUS_BADGE, SUBAGENT_STATUS_TEXT } from '~/utils/subagent-status'

const props = defineProps<{
  runs: SubagentChip[]
  expandedId: number | null
  conversationId: number
  /** Every run the conversation spawned, inline ones included: the rows its Subagents page lists. */
  runsTotal: number
}>()
const emit = defineEmits<{ toggle: [id: number] }>()
defineSlots<{ expanded?: (props: { run: SubagentChip }) => unknown }>()

const statusWords: Record<SubagentRunStatus, string> = {
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  KILLED: 'Killed',
  TIMEOUT: 'Timed out',
}

// RUNNING draws a spinner instead.
const statusIcons = { COMPLETED: CheckCircleIcon, FAILED: XCircleIcon, KILLED: StopCircleIcon, TIMEOUT: ClockIcon }

// Only an ended-badly run earns a coloured pill; running and completed read as plain words beside their icon.
function needsAttention(status: SubagentRunStatus): boolean {
  return status === 'FAILED' || status === 'KILLED' || status === 'TIMEOUT'
}

function chipName(run: SubagentChip): string {
  return run.label?.trim() || run.childAgentName || `Run #${run.id}`
}

// A labelled chip still names its child agent, which is how the Subagents page lists the run.
function chipTitle(run: SubagentChip): string | undefined {
  const label = run.label?.trim()
  if (!label) return run.childAgentName ?? undefined
  return run.childAgentName ? `${label} · ${run.childAgentName}` : label
}

function chipAccessibleName(run: SubagentChip): string {
  const label = run.label?.trim()
  return label && run.childAgentName ? `${label} (${run.childAgentName})` : chipName(run)
}

// A spawn label is prose; only the generated agent name falls back to monospace.
function chipLabelClass(run: SubagentChip): string {
  if (run.label?.trim()) return 'text-fg-strong'
  return run.childAgentName ? 'font-mono text-fg-muted' : 'text-fg-muted'
}

const runningCount = computed(() => props.runs.filter(r => r.status === 'RUNNING').length)

// Elapsed time on a running row ticks each second; "ago" on a finished row only needs a coarse refresh.
const now = ref(Date.now())
let clock: ReturnType<typeof setInterval> | undefined
watch(runningCount, (running) => {
  now.value = Date.now()
  clearInterval(clock)
  clock = setInterval(() => {
    now.value = Date.now()
  }, running ? 1000 : 30_000)
}, { immediate: true })

function formatElapsed(ms: number): string {
  const s = Math.max(0, Math.floor(ms / 1000))
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  if (m < 60) return `${m}m ${s % 60}s`
  const h = Math.floor(m / 60)
  return h < 24 ? `${h}h ${m % 60}m` : `${Math.floor(h / 24)}d ${h % 24}h`
}

// A running row shows how long it has run, a finished one how long ago it ended; a clock behind the server's reads as just now.
function chipTime(run: SubagentChip): string | null {
  if (run.status === 'RUNNING') {
    const started = Date.parse(run.startedAt ?? '')
    return Number.isNaN(started) ? null : formatElapsed(now.value - started)
  }
  const ended = Date.parse(run.endedAt ?? '')
  if (Number.isNaN(ended)) return null
  const minutes = Math.floor((now.value - ended) / 60_000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes}m ago`
  return minutes < 1440 ? `${Math.floor(minutes / 60)}h ago` : `${Math.floor(minutes / 1440)}d ago`
}

function chipDuration(run: SubagentChip): string | undefined {
  const ms = Date.parse(run.endedAt ?? '') - Date.parse(run.startedAt ?? '')
  return Number.isNaN(ms) ? undefined : `Ran for ${formatElapsed(ms)}`
}

const listOpen = ref(true)

// With no chip to show the header is only a summary, so it stops being a toggle.
const headerToggle = computed(() => props.runs.length
  ? {
      'type': 'button',
      'data-testid': 'subagent-stack-toggle',
      'aria-expanded': listOpen.value,
      'aria-controls': listOpen.value ? 'subagent-stack-list' : undefined,
    }
  : {})

function toggleList() {
  if (props.runs.length) listOpen.value = !listOpen.value
}

const toggleButtons = new Map<number, HTMLElement>()
function bindToggle(id: number, el: unknown) {
  if (el instanceof HTMLElement) toggleButtons.set(id, el)
  else toggleButtons.delete(id)
}

function scrollRowIntoView(row: Element) {
  if (typeof row.scrollIntoView === 'function') row.scrollIntoView({ block: 'nearest' })
}

// The transcript loads after its row opens and grows the row past the list's fold, so the row is followed as it grows.
const rowObserver = typeof ResizeObserver === 'undefined'
  ? null
  : new ResizeObserver(entries => entries.forEach(entry => scrollRowIntoView(entry.target)))

// A chip expanded low in the stack would open its panel below the scroller's fold; reopening the list renders it there again.
watch([() => props.expandedId, listOpen], ([id]) => {
  rowObserver?.disconnect()
  const row = id == null ? null : toggleButtons.get(id)?.closest('li')
  if (!row) return
  scrollRowIntoView(row)
  rowObserver?.observe(row)
}, { flush: 'post' })

onUnmounted(() => {
  rowObserver?.disconnect()
  clearInterval(clock)
})

const announcement = ref('')
const lastStatus = new Map<number, SubagentRunStatus>()
watch(() => props.runs.map(r => r.status), () => {
  const ended: string[] = []
  for (const run of props.runs) {
    if (lastStatus.get(run.id) === 'RUNNING' && run.status !== 'RUNNING') {
      ended.push(`${chipName(run)}: ${statusWords[run.status]}`)
    }
    lastStatus.set(run.id, run.status)
  }
  if (!ended.length) return
  // A live region ignores a write of the text it already holds, so a repeat is cleared first.
  const text = ended.join('. ')
  announcement.value = ''
  void nextTick(() => {
    announcement.value = text
  })
}, { immediate: true })
</script>

<template>
  <div
    data-testid="subagent-stack"
    class="relative z-10 mx-auto w-full max-w-3xl px-4"
  >
    <p
      data-testid="subagent-stack-announcer"
      class="sr-only"
      aria-live="polite"
    >
      {{ announcement }}
    </p>
    <!-- A shade hanging from the chat header: no top edge of its own, so the header's bottom border is its top.
         Dark mode lifts it with the lighter surface, where a shadow would not show. -->
    <div class="text-xs bg-surface-elevated border-x border-b border-neutral-300 dark:border-neutral-700 rounded-b-lg shadow-md dark:shadow-none">
      <div class="flex items-center gap-3 px-3 py-2 text-fg-muted">
        <component
          :is="runs.length ? 'button' : 'div'"
          v-bind="headerToggle"
          class="flex min-w-0 flex-1 items-center gap-2 rounded text-left enabled:hover:text-fg-strong
                 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-current"
          @click="toggleList"
        >
          <ChevronDownIcon
            v-if="runs.length"
            class="w-3.5 h-3.5 shrink-0 transition-transform motion-reduce:transition-none"
            :class="{ '-rotate-90': !listOpen }"
            aria-hidden="true"
          />
          <span
            data-testid="subagent-stack-count"
            class="min-w-0 truncate"
          >
            <strong class="text-fg-strong">{{ runsTotal }}</strong> {{ runsTotal === 1 ? 'subagent' : 'subagents' }}<template v-if="runningCount"> · {{ runningCount }} running</template>
          </span>
        </component>
        <NuxtLink
          :to="`/subagents?parentConversationId=${conversationId}`"
          data-testid="subagent-stack-view-list"
          class="shrink-0 rounded underline-offset-2 hover:text-fg-strong hover:underline"
        >
          View all →
        </NuxtLink>
      </div>
      <!-- The list slides like a shade: grid rows animate 0fr to 1fr, which every current browser interpolates. -->
      <Transition
        enter-active-class="transition-[grid-template-rows] duration-[250ms] ease-[cubic-bezier(0.05,0.7,0.1,1)] motion-reduce:transition-none"
        enter-from-class="grid-rows-[0fr]"
        enter-to-class="grid-rows-[1fr]"
        leave-active-class="transition-[grid-template-rows] duration-200 ease-[cubic-bezier(0.3,0,0.8,0.15)] motion-reduce:transition-none"
        leave-from-class="grid-rows-[1fr]"
        leave-to-class="grid-rows-[0fr]"
      >
        <div
          v-if="runs.length && listOpen"
          data-testid="subagent-stack-shade"
          class="grid"
        >
          <!-- clip, not hidden: a clip box is no scroll container, so scrollIntoView cannot shift the list mid-slide. -->
          <div class="min-h-0 overflow-clip">
            <!-- max-h-36 shows four minimized rows and part of a fifth; an expanded chip needs room for its transcript. -->
            <ul
              id="subagent-stack-list"
              aria-label="Subagents spawned in this conversation"
              class="flex flex-col gap-px p-1 border-t border-neutral-200 dark:border-neutral-700 overflow-y-auto overscroll-contain"
              :class="expandedId != null ? 'max-h-[50vh]' : 'max-h-36'"
            >
              <!-- A flat row on the shade: no border or fill of its own until hovered or expanded. -->
              <li
                v-for="run in runs"
                :key="run.id"
                data-testid="subagent-chip"
                :data-status="run.status"
                class="shrink-0 rounded"
                :class="{ 'bg-black/3 dark:bg-white/5': expandedId === run.id }"
              >
                <button
                  :ref="(el: unknown) => bindToggle(run.id, el)"
                  type="button"
                  data-testid="subagent-chip-toggle"
                  :aria-expanded="expandedId === run.id"
                  :aria-controls="expandedId === run.id ? `subagent-chip-panel-${run.id}` : undefined"
                  :aria-label="`${expandedId === run.id ? 'Collapse' : 'Expand'} ${chipAccessibleName(run)}`"
                  :aria-describedby="`subagent-chip-status-${run.id} subagent-chip-time-${run.id}`"
                  class="flex w-full min-w-0 h-7 items-center gap-2 px-2 rounded text-left hover:bg-black/5 dark:hover:bg-white/5
                     focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-current"
                  @click="emit('toggle', run.id)"
                >
                  <!-- A heroicon is a functional component and drops data-testid, so the wrapper carries it. -->
                  <span
                    data-testid="subagent-chip-icon"
                    class="flex w-4 h-4 shrink-0 items-center justify-center"
                    :class="[SUBAGENT_STATUS_TEXT[run.status], { 'animate-spin motion-reduce:animate-none': run.status === 'RUNNING' }]"
                    aria-hidden="true"
                  >
                    <span
                      v-if="run.status === 'RUNNING'"
                      class="w-3.5 h-3.5 rounded-full border-2 border-current border-t-transparent"
                    />
                    <component
                      :is="statusIcons[run.status]"
                      v-else
                      class="w-4 h-4"
                    />
                  </span>
                  <span
                    data-testid="subagent-chip-label"
                    class="truncate min-w-0"
                    :class="chipLabelClass(run)"
                    :title="chipTitle(run)"
                  >{{ chipName(run) }}</span>
                  <span
                    :id="`subagent-chip-status-${run.id}`"
                    data-testid="subagent-chip-status"
                    class="ml-auto shrink-0"
                    :class="needsAttention(run.status)
                      ? ['rounded-full border px-1.5 py-px text-[11px] font-medium', SUBAGENT_STATUS_BADGE[run.status]]
                      : 'text-fg-muted'"
                  >{{ statusWords[run.status] }}</span>
                  <span
                    v-if="chipTime(run)"
                    :id="`subagent-chip-time-${run.id}`"
                    data-testid="subagent-chip-time"
                    class="shrink-0 text-fg-muted tabular-nums"
                    :title="chipDuration(run)"
                  >{{ chipTime(run) }}</span>
                  <ChevronDownIcon
                    class="w-3.5 h-3.5 shrink-0 text-fg-muted transition-transform motion-reduce:transition-none"
                    :class="{ 'rotate-180': expandedId === run.id }"
                    aria-hidden="true"
                  />
                </button>
                <!-- Not a scroller: the panel inside owns the height budget, so its footer stays in view. -->
                <div
                  v-if="expandedId === run.id"
                  :id="`subagent-chip-panel-${run.id}`"
                  data-testid="subagent-chip-expanded"
                  class="px-1 pb-1 text-fg-strong"
                >
                  <slot
                    name="expanded"
                    :run="run"
                  />
                </div>
              </li>
            </ul>
          </div>
        </div>
      </Transition>
    </div>
  </div>
</template>
