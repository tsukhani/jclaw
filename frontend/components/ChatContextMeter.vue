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
        <span class="text-sm font-mono text-fg-muted tabular-nums">
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
      Styling traced from Unsloth Studio's tooltip via devtools (see JCLAW
      notes): px-3 py-2, rounded-[10px], grid gap-1.5, text-xs body,
      font-mono on numerical values, font-medium only on the emphasized
      percent. min-w-44 stops narrow rows collapsing on the token-only
      cases. We still need the explicit border-neutral override because
      the project's --border token is invisible against --popover in
      dark mode; /50 opacity thins it to the hairline Unsloth uses.
    -->
    <PopoverContent
      align="end"
      :side-offset="12"
      class="min-w-44 px-3 py-2 rounded-[10px] border-neutral-200 dark:border-neutral-700/50"
      @mouseenter="open = true"
      @mouseleave="open = false"
      @focusin="open = true"
      @focusout="open = false"
    >
      <dl class="grid gap-1.5 text-xs">
        <div class="flex items-center justify-between gap-4">
          <dt class="text-muted-foreground">
            Context usage
          </dt>
          <dd
            class="font-mono tabular-nums font-medium"
            :class="percentColor"
          >
            {{ percentLabel }}
          </dd>
        </div>
        <div class="flex items-center justify-between gap-4">
          <dt class="text-muted-foreground">
            Prompt tokens
          </dt>
          <dd class="font-mono tabular-nums">
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
          <dd class="font-mono tabular-nums">
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
          <dd class="font-mono tabular-nums">
            {{ cached.toLocaleString() }}
          </dd>
        </div>
        <div class="flex items-center justify-between gap-4">
          <dt class="text-muted-foreground">
            Completion
          </dt>
          <dd class="font-mono tabular-nums">
            {{ completion.toLocaleString() }}
          </dd>
        </div>
        <div
          aria-hidden="true"
          class="my-0.5 border-t border-neutral-200 dark:border-neutral-700/50"
        />
        <div class="flex items-center justify-between gap-4">
          <dt class="text-muted-foreground">
            Total
          </dt>
          <dd class="font-mono tabular-nums">
            {{ total.toLocaleString() }}<span
              v-if="capacity"
              class="text-muted-foreground"
            > / {{ capacity.toLocaleString() }}</span>
          </dd>
        </div>
        <template v-if="costLabel != null || (turnCount ?? 0) > 0">
          <div
            aria-hidden="true"
            class="my-0.5 border-t border-neutral-200 dark:border-neutral-700/50"
          />
          <div
            v-if="(turnCount ?? 0) > 0"
            class="flex items-center justify-between gap-4"
          >
            <dt class="text-muted-foreground">
              Turns
            </dt>
            <dd class="font-mono tabular-nums">
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
            <dd class="font-mono tabular-nums">
              {{ costLabel }}
            </dd>
          </div>
        </template>
      </dl>
    </PopoverContent>
  </Popover>
</template>
