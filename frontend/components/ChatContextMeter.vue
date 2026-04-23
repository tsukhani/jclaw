<script setup lang="ts">
/**
 * Unsloth-style running context-usage indicator: compact "N / Ck" readout
 * plus a thin progress bar, with a hover popover that breaks down prompt /
 * thinking / cached / completion / percentage / total against the model's
 * context window, and folds in the running cost/turns aggregate so the
 * header stays minimal.
 *
 * Derives its token numbers from the latest assistant turn's usage block.
 * When no usage is available yet (empty conversation, streaming first token)
 * the trigger renders as "0 / Ck" with a zeroed bar — the popover label
 * reads "No usage yet" so the operator isn't left guessing why it's flat.
 */
import {
  Popover, PopoverContent, PopoverTrigger,
} from '~/components/ui/popover'

const props = defineProps<{
  promptTokens?: number | null
  completionTokens?: number | null
  /** Reasoning/thinking tokens from the latest assistant turn. */
  reasoningTokens?: number | null
  /** Prompt-cache reads on the latest assistant turn. */
  cachedTokens?: number | null
  contextWindow?: number | null
  /** Running conversation cost string (e.g. "$0.0041"); omitted when pricing is absent. */
  costLabel?: string | null
  /** Hover tooltip on the cost row — the per-component breakdown. */
  costTooltip?: string | null
  /** Count of assistant turns contributing to costLabel. */
  turnCount?: number | null
}>()

const open = ref(false)

const prompt = computed(() => props.promptTokens ?? 0)
const completion = computed(() => props.completionTokens ?? 0)
const reasoning = computed(() => props.reasoningTokens ?? 0)
const cached = computed(() => props.cachedTokens ?? 0)
const total = computed(() => prompt.value + completion.value)
const capacity = computed(() => props.contextWindow ?? 0)

const percent = computed(() => {
  if (!capacity.value) return 0
  return Math.min(100, (total.value / capacity.value) * 100)
})
const percentLabel = computed(() => {
  if (!capacity.value) return '—'
  const p = percent.value
  if (p < 1 && p > 0) return `${p.toFixed(1)}%`
  return `${p.toFixed(0)}%`
})

/** 262144 -> "262.1k", 1000 -> "1k", 100 -> "100". */
function kFormat(n: number): string {
  if (!n) return '0'
  if (n < 1000) return String(n)
  const k = n / 1000
  return k >= 100 ? `${k.toFixed(1)}k` : `${k.toFixed(k < 10 ? 2 : 1)}k`
}

const triggerRight = computed(() => capacity.value ? kFormat(capacity.value) : '—')
const percentColor = computed(() => {
  const p = percent.value
  if (p >= 90) return 'text-red-400'
  if (p >= 70) return 'text-amber-400'
  return 'text-emerald-400'
})

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
</script>

<template>
  <Popover v-model:open="open">
    <PopoverTrigger as-child>
      <button
        ref="triggerButton"
        type="button"
        class="inline-flex items-center gap-2.5 px-2.5 py-1 rounded-md hover:bg-muted
               focus:outline-hidden focus-visible:bg-muted transition-colors"
        :title="capacity ? `${total.toLocaleString()} / ${capacity.toLocaleString()} tokens` : 'No context window metadata'"
        @mouseenter="open = true"
        @mouseleave="handleMouseLeave"
        @focus="open = true"
        @blur="open = false"
      >
        <span class="text-xs font-mono text-fg-muted tabular-nums">
          {{ total.toLocaleString() }} / {{ triggerRight }}
        </span>
        <span class="w-10 h-1 bg-muted rounded-full overflow-hidden">
          <span
            class="block h-full rounded-full transition-[width] duration-300 ease-out"
            :class="percent >= 90 ? 'bg-red-400' : percent >= 70 ? 'bg-amber-400' : 'bg-emerald-400'"
            :style="{ width: `${percent}%` }"
          />
        </span>
      </button>
    </PopoverTrigger>
    <!--
      PopoverContent ships with w-72 rounded-md border p-4 shadow-md bg-popover
      out of the box (see shadcn-vue Popover primitive). Two targeted overrides:
      - border-neutral-200 / dark:border-neutral-700 — the project's --border
        token resolves to the same hsl as the popover bg in dark mode, so the
        default edge disappears; pin explicit neutrals to restore the ring.
      - rounded-2xl — Unsloth Studio's popovers use a ~16px radius (their
        --radius is 1.1rem vs shadcn's default 0.625rem). At jclaw's token
        scale, rounded-2xl lands at the same ~16px and matches the reference
        screenshot's corner softness.
    -->
    <PopoverContent
      align="end"
      :side-offset="4"
      class="rounded-2xl border-neutral-200 dark:border-neutral-700"
      @mouseenter="open = true"
      @mouseleave="open = false"
      @focusin="open = true"
      @focusout="open = false"
    >
      <dl class="grid gap-2.5 text-sm">
        <div class="flex items-center justify-between gap-4">
          <dt class="text-muted-foreground">
            Context usage
          </dt>
          <dd
            class="tabular-nums"
            :class="percentColor"
          >
            {{ percentLabel }}
          </dd>
        </div>
        <div class="flex items-center justify-between gap-4">
          <dt class="text-muted-foreground">
            Prompt tokens
          </dt>
          <dd class="tabular-nums">
            {{ prompt.toLocaleString() }}
          </dd>
        </div>
        <div
          v-if="reasoning > 0"
          class="flex items-center justify-between gap-4"
        >
          <dt class="text-muted-foreground">
            Thinking tokens
          </dt>
          <dd class="tabular-nums">
            {{ reasoning.toLocaleString() }}
          </dd>
        </div>
        <div
          v-if="cached > 0"
          class="flex items-center justify-between gap-4"
        >
          <dt class="text-muted-foreground">
            Cached tokens
          </dt>
          <dd class="tabular-nums">
            {{ cached.toLocaleString() }}
          </dd>
        </div>
        <div class="flex items-center justify-between gap-4">
          <dt class="text-muted-foreground">
            Completion
          </dt>
          <dd class="tabular-nums">
            {{ completion.toLocaleString() }}
          </dd>
        </div>
        <div class="flex items-center justify-between gap-4 pt-2.5 border-t border-neutral-200 dark:border-neutral-700">
          <dt class="text-muted-foreground">
            Total
          </dt>
          <dd class="tabular-nums">
            {{ total.toLocaleString() }}<span
              v-if="capacity"
              class="text-muted-foreground"
            > / {{ capacity.toLocaleString() }}</span>
          </dd>
        </div>
        <div
          v-if="costLabel != null || (turnCount ?? 0) > 0"
          class="grid gap-2.5 pt-2.5 border-t border-neutral-200 dark:border-neutral-700"
        >
          <div
            v-if="(turnCount ?? 0) > 0"
            class="flex items-center justify-between gap-4"
          >
            <dt class="text-muted-foreground">
              Turns
            </dt>
            <dd class="tabular-nums">
              {{ turnCount }}
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
            <dd class="tabular-nums">
              {{ costLabel }}
            </dd>
          </div>
        </div>
      </dl>
    </PopoverContent>
  </Popover>
</template>
