<script setup lang="ts">
/**
 * The chat header's conversation-memory meter: how much of the conversation the agent rereads on every reply,
 * filling toward the size at which JClaw summarizes older messages, or toward the model's whole window when no
 * summary point is known for it. The hover card says what the reading means in words and adds the conversation's
 * running totals, so the header itself stays one number and a bar.
 */
import {
  Popover, PopoverContent, PopoverTrigger,
} from '~/components/ui/popover'

const props = defineProps<{
  /** The latest call's prompt and completion: together, what the next reply starts from. */
  promptTokens?: number | null
  completionTokens?: number | null
  contextWindow?: number | null
  /** The size at which the selected model's older messages get summarized; null when not known for that model. */
  compactAt?: number | null
  /**
   * Sum of (prompt + completion) across every assistant turn in the conversation. Every reread of the history counts,
   * as providers bill it, so it only grows, where the latest reading drops after a summary.
   */
  cumulativeTokens?: number | null
  /** Persisted message rows (user, assistant, tool and system) in the conversation. */
  messageCount?: number | null
  /** Rows the operator sent; a turn can add many assistant and tool rows to one prompt. */
  userPromptCount?: number | null
  /** SessionCompaction rows for this conversation: how many times its older messages were summarized. */
  compactionCount?: number | null
  /** A summary was stored after the latest reply, whose reading the meter still shows. */
  summarizedSinceReply?: boolean
  /** Running conversation cost string (e.g. "$0.0041"); omitted when pricing is absent. */
  costLabel?: string | null
  /** Hover tooltip on the cost row — the per-component breakdown. */
  costTooltip?: string | null
  /** Count of assistant replies contributing to costLabel. */
  turnCount?: number | null
}>()

const open = ref(false)

const total = computed(() => (props.promptTokens ?? 0) + (props.completionTokens ?? 0))
const capacity = computed(() => props.contextWindow ?? 0)
const compactAt = computed(() => props.compactAt ?? 0)
const target = computed(() => compactAt.value || capacity.value)
const cumulative = computed(() => props.cumulativeTokens ?? 0)

const percent = computed(() => target.value ? Math.min(100, (total.value / target.value) * 100) : 0)
const percentLabel = computed(() => {
  const p = percent.value
  if (p < 1 && p > 0) return `${p.toFixed(1)}%`
  return `${p.toFixed(0)}%`
})

// Red only near the model's own window, where the oldest messages are cut rather than summarized.
const level = computed<'ok' | 'warn' | 'danger'>(() => {
  if (capacity.value && total.value >= capacity.value * 0.9) return 'danger'
  if (percent.value >= 80) return 'warn'
  return 'ok'
})
const percentColor = computed(() => ({
  ok: 'text-emerald-700 dark:text-emerald-400',
  warn: 'text-amber-700 dark:text-amber-400',
  danger: 'text-red-700 dark:text-red-400',
})[level.value])
const barColor = computed(() => ({ ok: 'bg-ok', warn: 'bg-warning', danger: 'bg-danger' })[level.value])

/** 262144 -> "262.1k", 1000 -> "1.00k", 100 -> "100". */
function kFormat(n: number): string {
  if (!n) return '0'
  if (n < 1000) return String(n)
  const k = n / 1000
  if (k >= 100) return `${k.toFixed(1)}k`
  const digits = k < 10 ? 2 : 1
  return `${k.toFixed(digits)}k`
}

const triggerRight = computed(() => target.value ? kFormat(target.value) : '—')

const summary = computed(() => {
  const n = total.value.toLocaleString()
  if (!total.value) return 'Nothing to measure until the agent replies.'
  if (props.summarizedSinceReply) {
    return `The agent's last reply reread ${n} tokens of this conversation. Its oldest messages have since been `
      + 'summarized, so the next reply starts from a shorter conversation.'
  }
  const holds = `The agent rereads this conversation every time it answers: ${n} tokens right now.`
  const cut = level.value === 'danger'
    ? ' This close to the model\'s limit, the oldest messages are left out of what it reads.'
    : ''
  if (compactAt.value) {
    return `${holds} At about ${compactAt.value.toLocaleString()}, JClaw summarizes the oldest messages to make room.${cut}`
  }
  if (capacity.value) return `${holds} This model can take ${capacity.value.toLocaleString()} at most.${cut}`
  return holds
})

const summarizedLabel = computed(() => {
  const c = props.compactionCount ?? 0
  if (c === 0) return 'Not yet'
  if (c === 1) return 'Once'
  return `${c.toLocaleString()} times`
})

const hasTotals = computed(() => props.userPromptCount != null || (props.turnCount ?? 0) > 0
  || props.messageCount != null || cumulative.value > 0 || props.costLabel != null)

/**
 * The Popover primitive can programmatically focus the trigger on open, so a
 * mouse-only interaction leaves the button focused after mouseleave — which
 * :focus would paint as still-active. focus-visible solves the visual side,
 * and blurring here also clears any residual focus ring in assistive tech.
 */
const triggerButton = ref<HTMLButtonElement | null>(null)
function handleMouseLeave() {
  open.value = false
  triggerButton.value?.blur()
}

// Closed by the pointer leaving, the primitive focuses the trigger, whose focus handler reopens the card: it stuck
// open, having taken focus from the message box.
function keepFocusWhereItIs(event: Event) {
  event.preventDefault()
}
</script>

<template>
  <Popover v-model:open="open">
    <PopoverTrigger as-child>
      <button
        ref="triggerButton"
        type="button"
        class="inline-flex items-center gap-2.5 px-2.5 py-1 rounded-md hover:bg-muted
               focus:outline-hidden focus-visible:bg-muted transition-colors"
        :aria-label="target ? `Conversation memory: ${percentLabel} full` : 'Conversation memory'"
        @mouseenter="open = true"
        @mouseleave="handleMouseLeave"
        @focus="open = true"
        @blur="open = false"
      >
        <span
          data-testid="context-readout"
          class="text-sm font-mono text-fg-muted tabular-nums"
        >
          {{ total.toLocaleString() }} / {{ triggerRight }}
        </span>
        <span class="w-10 h-1 bg-muted rounded-full overflow-hidden">
          <span
            data-testid="context-bar"
            class="block h-full rounded-full transition-[width] duration-300 ease-out"
            :class="barColor"
            :style="{ width: `${percent}%` }"
          />
        </span>
      </button>
    </PopoverTrigger>
    <!--
      Styling traced from Unsloth Studio's tooltip via devtools: px-3 py-2,
      rounded-[10px], text-xs body, font-mono on numerical values. w-72 gives
      the explanation a readable line length. The explicit border-neutral
      override is for Unsloth's hairline; --border is heavier. /50 opacity
      thins it in dark mode.
    -->
    <PopoverContent
      align="end"
      :side-offset="12"
      class="w-72 max-w-[calc(100vw-2rem)] px-3 py-2.5 rounded-[10px] border-neutral-200 dark:border-neutral-700/50"
      @mouseenter="open = true"
      @mouseleave="open = false"
      @focusin="open = true"
      @focusout="open = false"
      @close-auto-focus="keepFocusWhereItIs"
    >
      <div class="grid gap-2 text-xs">
        <div class="flex items-baseline justify-between gap-4">
          <span class="font-medium text-fg-strong">Conversation memory</span>
          <span
            v-if="target"
            data-testid="context-percent"
            class="font-mono tabular-nums font-medium"
            :class="percentColor"
          >{{ percentLabel }} full</span>
        </div>
        <p
          data-testid="context-summary"
          class="text-muted-foreground leading-relaxed"
        >
          {{ summary }}
        </p>
        <dl class="grid gap-1.5">
          <div
            v-if="compactAt && capacity"
            class="flex items-center justify-between gap-4"
          >
            <dt class="text-muted-foreground">
              Model's limit
            </dt>
            <dd class="font-mono tabular-nums">
              {{ capacity.toLocaleString() }} tokens
            </dd>
          </div>
          <div
            v-if="compactionCount != null"
            class="flex items-center justify-between gap-4"
          >
            <dt class="text-muted-foreground">
              Summarized so far
            </dt>
            <dd class="tabular-nums">
              {{ summarizedLabel }}
            </dd>
          </div>
        </dl>
        <template v-if="hasTotals">
          <div
            aria-hidden="true"
            class="border-t border-neutral-200 dark:border-neutral-700/50"
          />
          <p class="font-medium text-fg-strong">
            So far in this conversation
          </p>
          <dl class="grid gap-1.5">
            <div
              v-if="userPromptCount != null"
              class="flex items-center justify-between gap-4"
            >
              <dt class="text-muted-foreground">
                Your messages
              </dt>
              <dd class="font-mono tabular-nums">
                {{ (userPromptCount ?? 0).toLocaleString() }}
              </dd>
            </div>
            <div
              v-if="(turnCount ?? 0) > 0"
              class="flex items-center justify-between gap-4"
            >
              <dt class="text-muted-foreground">
                Replies
              </dt>
              <dd class="font-mono tabular-nums">
                {{ (turnCount ?? 0).toLocaleString() }}
              </dd>
            </div>
            <div
              v-if="messageCount != null"
              class="flex items-center justify-between gap-4"
            >
              <dt class="text-muted-foreground">
                All saved messages
              </dt>
              <dd class="font-mono tabular-nums">
                {{ (messageCount ?? 0).toLocaleString() }}
              </dd>
            </div>
            <div
              v-if="cumulative > 0"
              class="grid grid-cols-[1fr_auto] gap-x-4"
            >
              <dt class="text-muted-foreground">
                Tokens processed
              </dt>
              <dd class="font-mono tabular-nums text-right">
                {{ cumulative.toLocaleString() }}
              </dd>
              <dd class="col-span-2 text-[11px] text-muted-foreground">
                Every reread counts, as providers bill it.
              </dd>
            </div>
            <div
              v-if="costLabel != null"
              class="flex items-center justify-between gap-4"
              :title="costTooltip ?? undefined"
            >
              <dt class="text-muted-foreground">
                Cost
              </dt>
              <dd class="font-mono tabular-nums">
                {{ costLabel }}
              </dd>
            </div>
          </dl>
        </template>
        <p class="text-[11px] text-muted-foreground">
          Tokens are word pieces: 100 tokens is about 75 English words.
        </p>
      </div>
    </PopoverContent>
  </Popover>
</template>
