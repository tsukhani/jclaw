import type { Ref } from 'vue'
import type { Provider } from '~/composables/useProviders'

/**
 * Web-chat autocomplete logic for the `/model NAME` slash-command argument
 * (JCLAW-114). Pure-logic helpers sit at the top so they're unit-testable
 * without mounting a Vue page; the composable below wires them to input
 * state and keyboard navigation.
 */

/** Literal prefix that activates the completer. Trailing space required. */
export const MODEL_COMMAND_PREFIX = '/model '

/** Fixed sub-keywords of /model that are NOT provider/model names — no completion. */
const FIXED_SUB_KEYWORDS = new Set(['status', 'reset'])

/** True when `text` starts with the /model command and has past the command literal. */
export function isModelArgumentContext(text: string): boolean {
  if (!text) return false
  const lower = text.toLowerCase()
  if (!lower.startsWith(MODEL_COMMAND_PREFIX)) return false
  const arg = text.slice(MODEL_COMMAND_PREFIX.length).trim()
  // When the user has typed a fixed sub-keyword like "status" or "reset",
  // they're not typing a provider/model — don't show the completer.
  if (FIXED_SUB_KEYWORDS.has(arg.toLowerCase())) return false
  return true
}

/**
 * Flatten the providers list into a sorted array of `provider/model-id`
 * strings — one per (provider, model) pair. Stable across renders so the
 * popup's highlighted index doesn't jump unless the underlying config
 * actually changed.
 */
export function buildModelOptions(providers: Provider[]): string[] {
  const out: string[] = []
  for (const p of providers) {
    for (const m of p.models) {
      out.push(`${p.name}/${m.id}`)
    }
  }
  return out
}

/**
 * Filter completions by the argument portion of `text`. Empty query returns
 * all options; non-empty matches substring (case-insensitive) against the
 * full `provider/model-id` string — so typing either the provider portion
 * or the model id narrows the list.
 */
export function filterModelOptions(allOptions: string[], text: string): string[] {
  if (!isModelArgumentContext(text)) return []
  const arg = text.slice(MODEL_COMMAND_PREFIX.length).trim().toLowerCase()
  if (!arg) return [...allOptions]
  return allOptions.filter(opt => opt.toLowerCase().includes(arg))
}

/**
 * Replace the argument portion of `text` with `choice`. Preserves the
 * command literal. Trims trailing whitespace from the replacement so
 * pressing Enter immediately after accepting a suggestion sends the
 * command cleanly — no "/model openrouter/gpt-4.1   " artifacts.
 */
export function applyModelOption(text: string, choice: string): string {
  if (!text.toLowerCase().startsWith(MODEL_COMMAND_PREFIX)) return text
  return MODEL_COMMAND_PREFIX + choice
}

/**
 * Compute the next highlighted index after ArrowDown / ArrowUp with wrap.
 * Returns 0 when options is empty — caller should check length before
 * rendering, but the wrap is still defined for test coverage.
 */
export function nextAutocompleteIndex(
  current: number,
  total: number,
  direction: 'up' | 'down',
): number {
  if (total <= 0) return 0
  if (direction === 'down') return (current + 1) % total
  return (current - 1 + total) % total
}

// ── Composable ──────────────────────────────────────────────────────

export interface UseModelAutocomplete {
  readonly open: Ref<boolean>
  readonly options: Ref<string[]>
  readonly highlightedIndex: Ref<number>
  readonly highlighted: Ref<string | null>
  /** Update internal state from the textarea's current value. */
  update: (text: string) => void
  /** Close the popup without changing input. */
  close: () => void
  /** ArrowDown / ArrowUp navigation with wrap. */
  moveHighlight: (direction: 'up' | 'down') => void
  /** Accept the currently-highlighted option and return the replacement text. */
  accept: (currentText: string) => string | null
}

/**
 * Reactive autocomplete state for the chat input. {@link update} is called
 * on every input change; {@link accept} returns the new textarea value
 * when Enter / Tab is pressed with an option highlighted. Composable is
 * pure Vue — no DOM access — so it's testable in isolation.
 */
export function useModelAutocomplete(providers: Ref<Provider[]>): UseModelAutocomplete {
  const open = ref(false)
  const options = ref<string[]>([])
  const highlightedIndex = ref(0)

  const highlighted = computed<string | null>(() => {
    if (!open.value) return null
    return options.value[highlightedIndex.value] ?? null
  })

  function update(text: string) {
    if (!isModelArgumentContext(text)) {
      close()
      return
    }
    const allOptions = buildModelOptions(providers.value)
    const filtered = filterModelOptions(allOptions, text)
    if (filtered.length === 0) {
      close()
      return
    }
    options.value = filtered
    open.value = true
    // Clamp so a filter that shrank the list doesn't leave the index past the end.
    if (highlightedIndex.value >= filtered.length) highlightedIndex.value = 0
  }

  function close() {
    open.value = false
    options.value = []
    highlightedIndex.value = 0
  }

  function moveHighlight(direction: 'up' | 'down') {
    if (!open.value || options.value.length === 0) return
    highlightedIndex.value = nextAutocompleteIndex(
      highlightedIndex.value,
      options.value.length,
      direction,
    )
  }

  function accept(currentText: string): string | null {
    if (!open.value) return null
    const choice = options.value[highlightedIndex.value]
    if (!choice) return null
    close()
    return applyModelOption(currentText, choice)
  }

  return { open, options, highlightedIndex, highlighted, update, close, moveHighlight, accept }
}
