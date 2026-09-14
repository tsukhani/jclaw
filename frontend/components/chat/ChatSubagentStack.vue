<script setup lang="ts">
import { nextTick, onUnmounted, ref, watch, type ComponentPublicInstance } from 'vue'
import { ChevronDownIcon, UsersIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import type { SubagentChip, SubagentRunStatus } from '~/composables/useChatSubagentChips'
import { SUBAGENT_STATUS_BADGE } from '~/utils/subagent-status'

const props = defineProps<{
  runs: SubagentChip[]
  expandedId: number | null
  conversationId: number
  /** Every run the conversation spawned, inline ones included: the rows its Subagents page lists. */
  runsTotal: number
}>()
const emit = defineEmits<{ toggle: [id: number], close: [id: number] }>()
defineSlots<{ expanded?: (props: { run: SubagentChip }) => unknown }>()

const statusWords: Record<SubagentRunStatus, string> = {
  RUNNING: 'Running',
  COMPLETED: 'Completed',
  FAILED: 'Failed',
  KILLED: 'Killed',
  TIMEOUT: 'Timed out',
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

const listOpen = ref(true)
const viewListLink = ref<ComponentPublicInstance | null>(null)

const toggleButtons = new Map<number, HTMLElement>()
function bindToggle(id: number, el: unknown) {
  if (el instanceof HTMLElement) toggleButtons.set(id, el)
  else toggleButtons.delete(id)
}

// The close button removes its own row, so hand focus to the neighbouring chip, or to the header's link after the last one.
function close(index: number) {
  const run = props.runs[index]
  if (!run) return
  const neighbour = props.runs[index + 1] ?? props.runs[index - 1]
  emit('close', run.id)
  void nextTick(() => {
    const target = neighbour ? toggleButtons.get(neighbour.id) : viewListLink.value?.$el as HTMLElement | undefined
    target?.focus()
  })
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

onUnmounted(() => rowObserver?.disconnect())

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
      <div class="flex items-center gap-2 px-3 py-2 text-fg-muted">
        <UsersIcon
          class="w-3.5 h-3.5 shrink-0"
          aria-hidden="true"
        />
        <span
          data-testid="subagent-stack-count"
          class="min-w-0 truncate"
        >
          <strong>{{ runsTotal }}</strong>
          {{ runsTotal === 1 ? 'subagent' : 'subagents' }} spawned in this conversation
        </span>
        <NuxtLink
          ref="viewListLink"
          :to="`/subagents?parentConversationId=${conversationId}`"
          data-testid="subagent-stack-view-list"
          class="ml-auto shrink-0 rounded underline-offset-2 hover:text-fg-strong hover:underline"
        >
          View list →
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
            <!-- max-h-36 fits four minimized rows; an expanded chip needs room for its transcript. -->
            <ul
              id="subagent-stack-list"
              aria-label="Subagents spawned in this conversation"
              class="flex flex-col gap-1 p-1 border-t border-neutral-200 dark:border-neutral-700 overflow-y-auto overscroll-contain"
              :class="expandedId != null ? 'max-h-[50vh]' : 'max-h-36'"
            >
              <!-- A neutral row one surface step off the container; only the status pill carries colour. -->
              <li
                v-for="(run, index) in runs"
                :key="run.id"
                data-testid="subagent-chip"
                :data-status="run.status"
                class="shrink-0 text-xs text-fg-muted bg-muted dark:bg-white/5 border border-neutral-200 dark:border-neutral-700 rounded"
              >
                <div class="flex items-center gap-2 min-w-0 px-2 py-1">
                  <span
                    data-testid="subagent-chip-label"
                    class="font-mono text-fg-strong truncate min-w-0"
                    :title="chipTitle(run)"
                  >{{ chipName(run) }}</span>
                  <span
                    data-testid="subagent-chip-status"
                    class="ml-auto shrink-0 inline-flex items-center gap-1 px-1.5 py-0.5 border text-[10px] font-mono uppercase tracking-wide"
                    :class="SUBAGENT_STATUS_BADGE[run.status]"
                  >
                    <!-- The dot takes the pill's text colour: a -500 dot on the light -100 fill measured 2.18:1, under the 3:1 a status mark needs. -->
                    <span
                      data-testid="subagent-chip-dot"
                      class="w-1.5 h-1.5 shrink-0 rounded-full bg-current"
                      :class="{ 'animate-pulse': run.status === 'RUNNING' }"
                      aria-hidden="true"
                    />
                    {{ statusWords[run.status] }}
                  </span>
                  <button
                    :ref="(el: unknown) => bindToggle(run.id, el)"
                    type="button"
                    data-testid="subagent-chip-toggle"
                    :aria-expanded="expandedId === run.id"
                    :aria-controls="expandedId === run.id ? `subagent-chip-panel-${run.id}` : undefined"
                    :aria-label="`${expandedId === run.id ? 'Collapse' : 'Expand'} ${chipAccessibleName(run)}`"
                    class="shrink-0 p-0.5 rounded hover:bg-black/5 dark:hover:bg-white/10
                     focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-current"
                    @click="emit('toggle', run.id)"
                  >
                    <ChevronDownIcon
                      class="w-3.5 h-3.5 transition-transform"
                      :class="{ 'rotate-180': expandedId === run.id }"
                      aria-hidden="true"
                    />
                  </button>
                  <button
                    type="button"
                    data-testid="subagent-chip-close"
                    :aria-label="`Close ${chipName(run)}`"
                    class="shrink-0 p-0.5 rounded hover:bg-black/5 dark:hover:bg-white/10
                     focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-current"
                    @click="close(index)"
                  >
                    <XMarkIcon
                      class="w-3.5 h-3.5"
                      aria-hidden="true"
                    />
                  </button>
                </div>
                <!-- Not a scroller: the panel inside owns the height budget, so its footer stays in view. -->
                <div
                  v-if="expandedId === run.id"
                  :id="`subagent-chip-panel-${run.id}`"
                  data-testid="subagent-chip-expanded"
                  class="border-t border-neutral-200 dark:border-neutral-700 bg-surface-elevated text-fg-strong"
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
      <!-- The shade's bottom rail: a click control only, since the shade cannot be dragged; 24px tall for WCAG 2.5.8. -->
      <button
        v-if="runs.length"
        type="button"
        data-testid="subagent-stack-toggle"
        :aria-expanded="listOpen"
        :aria-controls="listOpen ? 'subagent-stack-list' : undefined"
        :aria-label="`${listOpen ? 'Collapse' : 'Expand'} the subagent list`"
        class="flex w-full h-6 items-center justify-center text-fg-muted hover:text-fg-strong hover:bg-black/5 dark:hover:bg-white/5
               rounded-b-lg focus-visible:outline-2 focus-visible:-outline-offset-2 focus-visible:outline-current"
        @click="listOpen = !listOpen"
      >
        <ChevronDownIcon
          class="w-3.5 h-3.5 transition-transform motion-reduce:transition-none"
          :class="{ 'rotate-180': listOpen }"
          aria-hidden="true"
        />
      </button>
    </div>
  </div>
</template>
