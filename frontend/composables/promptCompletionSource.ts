import type { Ref } from 'vue'
import type { CompletionOption, CompletionSource } from '~/composables/useComposerCompleter'
import type { Prompt } from '~/types/api'

/**
 * Completion source for `/prompt <query>` (JCLAW-1072) — search the Prompts
 * Library (JCLAW-813) from the composer and insert a saved prompt, instead of
 * leaving chat for /prompts and coming back through ?compose=.
 */

/** Literal that opens prompt search. Trailing space required, like /model. */
export const PROMPT_COMMAND_PREFIX = '/prompt '

/** True once the user is past the /prompt literal and into its argument. */
export function isPromptArgumentContext(text: string): boolean {
  return text.toLowerCase().startsWith(PROMPT_COMMAND_PREFIX)
}

/**
 * True for any text the composer must not send verbatim. Covers the bare
 * literal as well as the argument form: sent, "/prompt foo" gets the server's
 * text reply instead of an insert.
 */
export function isPromptCommandText(text: string): boolean {
  const lower = text.toLowerCase().trimEnd()
  return lower === PROMPT_COMMAND_PREFIX.trimEnd() || isPromptArgumentContext(text)
}

/** Prompts matching the query, by title or tag. Empty query returns all. */
export function filterPrompts(prompts: Prompt[], text: string): Prompt[] {
  if (!isPromptArgumentContext(text)) return []
  const query = text.slice(PROMPT_COMMAND_PREFIX.length).trim().toLowerCase()
  if (!query) return [...prompts]
  return prompts.filter(p =>
    p.title.toLowerCase().includes(query)
    || (p.tags ?? '').toLowerCase().includes(query))
}

export interface PromptSourceDeps {
  prompts: Ref<Prompt[]>
  /** True while the library fetch is in flight. */
  loading: Ref<boolean>
  /** Idempotent; called on first entry into prompt context so chat's cold boot doesn't pay for the fetch. */
  ensureLoaded: () => void
}

export function promptCompletionSource(deps: PromptSourceDeps): CompletionSource {
  const { prompts, loading, ensureLoaded } = deps
  return {
    id: 'prompt',
    ariaLabel: 'Saved prompt options',
    blocksSend: isPromptCommandText,
    options: (text: string): CompletionOption[] => {
      if (!isPromptArgumentContext(text)) return []
      ensureLoaded()
      // A status row rather than an empty list: the popup must stay open so
      // Enter keeps being swallowed instead of sending the literal text.
      if (loading.value) return [{ value: '', detail: 'Loading prompts…', disabled: true }]
      const matches = filterPrompts(prompts.value, text)
      if (matches.length === 0) return [{ value: '', detail: 'No matching prompts', disabled: true }]
      return matches.map(p => ({
        value: String(p.id),
        label: p.title,
        detail: p.categoryLabel,
      }))
    },
    // Replaces the whole composer, caret left at the end so the user can append
    // context. Prompt content has no placeholder syntax, so it inserts verbatim.
    apply: (text: string, choice: CompletionOption) =>
      prompts.value.find(p => String(p.id) === choice.value)?.content ?? text,
  }
}
