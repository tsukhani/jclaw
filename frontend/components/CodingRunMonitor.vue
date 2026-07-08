<script setup lang="ts">
/**
 * JCLAW-663: live monitor for an external coding-harness ("acp" runtime)
 * subagent run. Renders the harness's step stream (assistant text, thoughts,
 * tool calls, and file diffs) as it arrives, plus a Kill control that routes
 * through the shared subagent kill endpoint.
 *
 * Two data sources feed the same step list:
 *  - catch-up: on mount, GET /api/subagent-runs/{runId}/steps replays every
 *    step recorded so far (so a mid-run page load still shows history);
 *  - live: `codingrun.step` / `codingrun.done` SSE events append the tail.
 * Both are deduplicated by the per-run monotonic `seq`, so the catch-up ↔
 * live race (an event landing during the await) can't double-render a step.
 */
import { z } from 'zod'
import { CommandLineIcon, DocumentIcon, PhotoIcon, StopIcon, WrenchIcon } from '@heroicons/vue/24/outline'
import { MessageAttachmentSchema, type MessageAttachment } from '~/types/schemas'
import { formatSize } from '~/utils/format'

const props = defineProps<{
  /** SubagentRun id of the coding run to monitor. */
  runId: number
}>()

/**
 * One step of the harness transcript. `seq` is a per-run monotonic order key
 * (the dedup + sort axis). `kind` drives the badge + rendering; `text` is the
 * human-readable body; `tool` names the tool for tool_call/tool_result steps;
 * `diff` carries a unified diff for file edits. `attachments` carries any
 * harness-generated artifacts (patch/diff files, generated files) as persisted
 * {@link MessageAttachmentSchema} rows (JCLAW-666). `.loose()` lets the wire
 * carry extras (runId, conversationId, timestamp) without failing validation.
 *
 * TODO(JCLAW-666): the backend does not attach artifacts to coding steps yet —
 * the per-step `attachments` array is a forward-declared, optional contract so
 * this monitor renders them the moment the harness→transcript path emits
 * MessageAttachment rows. Until then `attachments` is absent and the artifact
 * row below renders nothing.
 */
const CodingStepSchema = z.object({
  seq: z.number(),
  kind: z.string(),
  text: z.string().nullish(),
  tool: z.string().nullish(),
  diff: z.string().nullish(),
  attachments: z.array(MessageAttachmentSchema).nullish(),
}).loose()
type CodingStep = z.infer<typeof CodingStepSchema>
const CodingStepsSchema = z.array(CodingStepSchema)

interface CodingRunDoneEvent {
  runId?: number
  status?: string
  outcome?: string | null
}

// Kinds that mark the run terminal — recognised both from a replayed final
// step (so a reload after completion shows the right status) and the live
// codingrun.done event.
const TERMINAL_KINDS: Record<string, string> = {
  done: 'completed',
  completed: 'completed',
  failed: 'failed',
  timeout: 'timeout',
  killed: 'killed',
}

const steps = ref<CodingStep[]>([])
const seenSeqs = new Set<number>()
const status = ref<string>('running')
const outcome = ref<string | null>(null)
const loadError = ref<string | null>(null)
const scrollEl = ref<HTMLElement | null>(null)

const isRunning = computed(() => status.value === 'running')

function normalizeStatus(raw?: string | null): string | null {
  if (!raw) return null
  const s = raw.toLowerCase()
  return TERMINAL_KINDS[s] ?? s
}

/** Insert a step in seq order, deduping by seq, and reflect terminal kinds. */
function ingest(step: CodingStep): void {
  if (seenSeqs.has(step.seq)) return
  seenSeqs.add(step.seq)
  const arr = steps.value
  let i = arr.length
  while (i > 0 && arr[i - 1]!.seq > step.seq) i--
  arr.splice(i, 0, step)
  steps.value = [...arr]

  const terminal = TERMINAL_KINDS[step.kind.toLowerCase()]
  if (terminal) {
    status.value = terminal
    if (step.text) outcome.value = step.text
  }
}

const { onEvent } = useEventBus()
// Subscribe BEFORE the mount-time catch-up so no live step is dropped in the
// gap; the seq dedup reconciles anything that overlaps the replay. A single
// wildcard subscription takes both codingrun.step and codingrun.done — the
// concrete type arrives as the handler's 2nd arg.
onEvent('codingrun.*', (data, type) => {
  const envelope = data as { runId?: number }
  if (envelope.runId !== props.runId) return
  if (type === 'codingrun.done') {
    const done = data as CodingRunDoneEvent
    status.value = normalizeStatus(done.status) ?? 'completed'
    if (done.outcome != null) outcome.value = done.outcome
    return
  }
  const parsed = CodingStepSchema.safeParse(data)
  if (parsed.success) ingest(parsed.data)
})

onMounted(async () => {
  try {
    const replay = await fetchParsed(`/api/subagent-runs/${props.runId}/steps`, CodingStepsSchema)
    for (const step of replay) ingest(step)
  }
  catch (e: unknown) {
    loadError.value = e instanceof Error ? e.message : 'Failed to load coding-run history.'
  }
})

// Keep the newest step in view, mirroring the chat message scroller.
watch(() => steps.value.length, () => {
  nextTick(() => {
    if (scrollEl.value) scrollEl.value.scrollTop = scrollEl.value.scrollHeight
  })
})

const { mutate, loading: killing } = useApiMutation()
const { confirm } = useConfirm()

async function killRun(): Promise<void> {
  const ok = await confirm({
    title: 'Kill coding run',
    message: `Stop coding run #${props.runId}? The external harness will be force-terminated.`,
    confirmText: 'Kill run',
    variant: 'danger',
  })
  if (!ok) return
  const res = await mutate<{ killed: boolean, status: string | null, message: string }>(
    `/api/subagent-runs/${props.runId}/kill`,
    { method: 'POST', body: { reason: 'Killed by operator from the coding-run monitor' } },
  )
  const next = normalizeStatus(res?.status)
  if (next) status.value = next
}

// ----- presentation helpers -----

interface KindMeta { label: string, badge: string }

const KIND_META: Record<string, KindMeta> = {
  text: { label: 'Message', badge: 'bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-300' },
  message: { label: 'Message', badge: 'bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-300' },
  assistant: { label: 'Message', badge: 'bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-300' },
  thought: { label: 'Thinking', badge: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-900/30 dark:text-indigo-300' },
  thinking: { label: 'Thinking', badge: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-900/30 dark:text-indigo-300' },
  reasoning: { label: 'Thinking', badge: 'bg-indigo-50 text-indigo-600 dark:bg-indigo-900/30 dark:text-indigo-300' },
  tool_call: { label: 'Tool', badge: 'bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300' },
  tool_result: { label: 'Result', badge: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300' },
  diff: { label: 'Diff', badge: 'bg-violet-50 text-violet-600 dark:bg-violet-900/30 dark:text-violet-300' },
  error: { label: 'Error', badge: 'bg-red-50 text-red-600 dark:bg-red-900/30 dark:text-red-300' },
}

function kindMeta(kind: string): KindMeta {
  return KIND_META[kind.toLowerCase()]
    ?? { label: kind, badge: 'bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-300' }
}

const statusMeta = computed<KindMeta>(() => {
  switch (status.value) {
    case 'running': return { label: 'Running', badge: 'bg-blue-50 text-blue-600 dark:bg-blue-900/30 dark:text-blue-300' }
    case 'completed': return { label: 'Completed', badge: 'bg-emerald-50 text-emerald-600 dark:bg-emerald-900/30 dark:text-emerald-300' }
    case 'failed': return { label: 'Failed', badge: 'bg-red-50 text-red-600 dark:bg-red-900/30 dark:text-red-300' }
    case 'timeout': return { label: 'Timed out', badge: 'bg-amber-50 text-amber-600 dark:bg-amber-900/30 dark:text-amber-300' }
    case 'killed': return { label: 'Killed', badge: 'bg-red-50 text-red-600 dark:bg-red-900/30 dark:text-red-300' }
    default: return { label: status.value, badge: 'bg-neutral-100 text-neutral-600 dark:bg-neutral-800 dark:text-neutral-300' }
  }
})

/** Split a unified diff into lines for per-line +/- coloring in the template. */
function diffLines(diff: string): string[] {
  return diff.replace(/\n$/, '').split('\n')
}

function diffLineClass(line: string): string {
  if (line.startsWith('+') && !line.startsWith('+++')) return 'text-emerald-600 dark:text-emerald-400'
  if (line.startsWith('-') && !line.startsWith('---')) return 'text-red-600 dark:text-red-400'
  if (line.startsWith('@@')) return 'text-cyan-600 dark:text-cyan-400'
  return 'text-fg-muted'
}

/** Pick the chip glyph for a harness artifact from its attachment kind. */
function attachmentIcon(kind: MessageAttachment['kind']): typeof DocumentIcon {
  return kind === 'IMAGE' ? PhotoIcon : DocumentIcon
}
</script>

<template>
  <section class="mx-auto w-full max-w-3xl px-4 pb-2">
    <div class="rounded-lg border border-border bg-surface-elevated overflow-hidden">
      <!-- Header: run identity, live status, and the Kill control. -->
      <div class="flex items-center gap-2 px-3 py-2 border-b border-border">
        <CommandLineIcon
          class="w-4 h-4 text-fg-muted shrink-0"
          aria-hidden="true"
        />
        <span class="text-sm font-medium text-fg-strong">Coding run</span>
        <span class="text-xs text-fg-muted">#{{ runId }}</span>
        <span
          class="text-xs px-1.5 py-0.5 rounded-full font-medium"
          :class="statusMeta.badge"
        >{{ statusMeta.label }}</span>
        <button
          v-if="isRunning"
          type="button"
          class="ml-auto inline-flex items-center gap-1 text-xs font-medium px-2 py-1 rounded
                 text-red-600 dark:text-red-400 hover:bg-red-50 dark:hover:bg-red-900/20
                 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
          :disabled="killing"
          @click="killRun"
        >
          <StopIcon
            class="w-4 h-4"
            aria-hidden="true"
          />
          {{ killing ? 'Killing…' : 'Kill' }}
        </button>
      </div>

      <!-- Step stream. -->
      <div
        ref="scrollEl"
        class="max-h-72 overflow-y-auto px-3 py-2 space-y-2 text-sm"
      >
        <p
          v-if="loadError"
          class="text-xs text-red-600 dark:text-red-400"
        >
          {{ loadError }}
        </p>
        <p
          v-else-if="steps.length === 0 && isRunning"
          class="text-xs text-fg-muted animate-pulse"
        >
          Waiting for the harness to emit its first step…
        </p>
        <p
          v-else-if="steps.length === 0"
          class="text-xs text-fg-muted"
        >
          No steps were recorded for this run.
        </p>

        <div
          v-for="step in steps"
          :key="step.seq"
          class="flex flex-col gap-1"
        >
          <div class="flex items-center gap-1.5">
            <span
              class="text-[10px] uppercase tracking-wide px-1.5 py-0.5 rounded font-medium"
              :class="kindMeta(step.kind).badge"
            >{{ kindMeta(step.kind).label }}</span>
            <span
              v-if="step.tool"
              class="inline-flex items-center gap-1 text-xs text-fg-muted font-mono"
            >
              <WrenchIcon
                class="w-3 h-3"
                aria-hidden="true"
              />
              {{ step.tool }}
            </span>
          </div>

          <!-- Diff steps get monospace, per-line +/- coloring. -->
          <pre
            v-if="step.diff"
            class="text-xs font-mono leading-relaxed overflow-x-auto rounded
                   bg-muted px-2 py-1.5 border border-border"
          ><span
            v-for="(line, i) in diffLines(step.diff)"
            :key="i"
            class="block"
            :class="diffLineClass(line)"
          >{{ line || ' ' }}</span></pre>

          <!-- Everything else renders as pre-wrapped text. -->
          <p
            v-if="step.text"
            class="whitespace-pre-wrap break-words text-fg-primary"
            :class="{ 'text-fg-muted italic': ['thought', 'thinking', 'reasoning'].includes(step.kind.toLowerCase()) }"
          >
            {{ step.text }}
          </p>

          <!-- JCLAW-666: harness-generated artifacts (patch/diff files, generated
               files) as downloadable chips. Absent today (see the TODO on
               CodingStepSchema); renders nothing until the backend attaches
               MessageAttachment rows to a coding step. -->
          <div
            v-if="step.attachments?.length"
            class="flex flex-wrap gap-2"
          >
            <a
              v-for="att in step.attachments"
              :key="att.uuid"
              :href="`/api/attachments/${att.uuid}`"
              target="_blank"
              rel="noopener"
              class="inline-flex items-center gap-2 max-w-[260px] bg-muted border border-border rounded-lg px-2.5 py-1 text-xs text-fg-strong hover:bg-muted/60 transition-colors"
              :title="`${att.originalFilename} · ${formatSize(att.sizeBytes)} · ${att.mimeType}`"
            >
              <component
                :is="attachmentIcon(att.kind)"
                class="w-3.5 h-3.5 shrink-0 text-fg-muted"
                aria-hidden="true"
              />
              <span class="truncate">{{ att.originalFilename }}</span>
              <span class="text-fg-muted shrink-0">{{ formatSize(att.sizeBytes) }}</span>
            </a>
          </div>
        </div>
      </div>

      <!-- Terminal outcome footer. -->
      <div
        v-if="!isRunning && outcome"
        class="px-3 py-2 border-t border-border text-xs text-fg-muted whitespace-pre-wrap break-words"
      >
        {{ outcome }}
      </div>
    </div>
  </section>
</template>
