import { nextTick, ref, watch, type Ref } from 'vue'
import { useComposerCompleter, type CompletionOption } from '~/composables/useComposerCompleter'
import { modelCompletionSource } from '~/composables/modelCompletionSource'
import { slashCommandSource } from '~/composables/slashCommandSource'
import { promptCompletionSource } from '~/composables/promptCompletionSource'
import type { Provider } from '~/composables/useProviders'
import type { Prompt, SlashCommand } from '~/types/api'

/**
 * Composer-local interaction logic (JCLAW-690 stage 5c; behaviour extracted
 * verbatim from pages/chat.vue). The composer <form> template stays in the page
 * — its DOM refs (chatInput, composerEl, fileInput) bind there — but the reactive
 * glue moves here: the completion popup (JCLAW-114, JCLAW-1071), the textarea
 * keyboard/resize handlers, the drop/paste/file-input attachment routing
 * (JCLAW-25), and the empty↔active FLIP animation.
 *
 * Refs it touches (chatInput, composerEl) are page-owned and passed in / returned
 * so the template keeps binding them locally — no cross-boundary ref forwarding.
 */
export interface UseChatComposerDeps {
  input: Ref<string>
  providers: Ref<Provider[]>
  /** Built-in commands for the "/" menu; empty until the lazy fetch resolves. */
  slashCommands: Ref<SlashCommand[]>
  /** Saved prompts for /prompt search; empty until {@link UseChatComposerDeps.loadPrompts} resolves. */
  prompts: Ref<Prompt[]>
  promptsLoading: Ref<boolean>
  /** Idempotent; fired on first entry into /prompt context, not on page load. */
  loadPrompts: () => void
  chatInput: Ref<HTMLTextAreaElement | null>
  subagentTranscript: Ref<{ agentId: number, agentName: string } | null>
  isEmptyChat: Ref<boolean>
  addAttachments: (files: File[]) => void
  sendMessage: () => void
}

export interface UseChatComposer {
  completer: ReturnType<typeof useComposerCompleter>
  composerEl: Ref<HTMLElement | null>
  onInputKeydown: (event: KeyboardEvent) => void
  onInputEnter: (event: KeyboardEvent) => void
  pickAutocomplete: (choice: CompletionOption) => void
  autoResize: () => void
  handleFileUpload: (event: Event) => void
  handleDrop: (event: DragEvent) => void
  handlePaste: (event: ClipboardEvent) => void
}

export function useChatComposer(deps: UseChatComposerDeps): UseChatComposer {
  const {
    input, providers, slashCommands, prompts, promptsLoading, loadPrompts,
    chatInput, subagentTranscript, isEmptyChat, addAttachments, sendMessage,
  } = deps

  // Owned here (only the FLIP watcher below reads it); the page binds it via
  // ref="composerEl" on the composer wrapper div.
  const composerEl = ref<HTMLElement | null>(null)

  /**
   * Composer typeahead (JCLAW-1071). One popup fed by ordered sources: the "/"
   * command menu first, then the /model NAME argument completer (JCLAW-114).
   * Keyboard nav via ArrowUp/Down/Enter/Tab/Escape, mouse via click.
   *
   * Sources are ordered, not merged, because their contexts are disjoint — "/"
   * with no space is command selection, a space ends it. One popup keeps a
   * single answer to "is a completion open?", which is what onInputEnter needs
   * to decide whether Enter accepts or sends.
   */
  const completer = useComposerCompleter([
    slashCommandSource(slashCommands),
    modelCompletionSource(providers),
    promptCompletionSource({ prompts, loading: promptsLoading, ensureLoaded: loadPrompts }),
  ])

  watch(input, (text) => {
    completer.update(text)
  })

  // The prompt library resolves after the keystroke that opened the picker, and
  // the completer only recomputes on input — without this the popup sits on its
  // "Loading prompts…" snapshot until the user happens to type again.
  watch([prompts, promptsLoading], () => {
    completer.refresh()
  })

  function onInputKeydown(event: KeyboardEvent) {
    // Only steal keys while the popup is open — when it's closed, the textarea
    // behaves exactly as before (Enter sends, everything else is text input).
    if (!completer.open.value) return
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      completer.moveHighlight('down')
    }
    else if (event.key === 'ArrowUp') {
      event.preventDefault()
      completer.moveHighlight('up')
    }
    else if (event.key === 'Tab' || event.key === 'Enter') {
      // Swallowed unconditionally while the popup is open: on a status row
      // ("No matching prompts") accept() returns null, and letting Enter
      // through there would send the half-finished invocation as a message.
      event.preventDefault()
      const replacement = completer.accept(input.value)
      if (replacement !== null) {
        input.value = replacement
        nextTick(() => autoResize())
      }
    }
    else if (event.key === 'Escape') {
      event.preventDefault()
      completer.close()
    }
  }

  function onInputEnter(event: KeyboardEvent) {
    // When the completion popup is open, Enter accepts the selection
    // (handled by onInputKeydown). Otherwise it sends the message.
    if (completer.open.value) {
      onInputKeydown(event)
      return
    }
    event.preventDefault()
    // Escape closes the popup but leaves "/prompt foo" in the box; sending it
    // would get the server's /prompt reply instead.
    if (completer.blocksSend.value) return
    sendMessage()
  }

  function pickAutocomplete(choice: CompletionOption) {
    const idx = completer.options.value.findIndex(o => o.value === choice.value)
    if (idx >= 0) completer.highlightedIndex.value = idx
    const replacement = completer.accept(input.value)
    if (replacement !== null) {
      input.value = replacement
      nextTick(() => {
        autoResize()
        chatInput.value?.focus()
      })
    }
  }

  function autoResize() {
    const el = chatInput.value
    if (!el) return
    el.style.height = 'auto'
    el.style.height = Math.min(el.scrollHeight, 200) + 'px'
  }

  // FLIP the composer between its empty-state (centered) and active-state
  // (bottom-anchored) positions when isEmptyChat flips. Watcher's default
  // flush:'pre' fires after the reactive change but before the DOM updates,
  // so we capture the OLD rect there; nextTick yields the NEW rect; the
  // difference becomes the starting translateY, animated back to 0. This
  // is the same technique Unsloth uses via Framer Motion's layoutId.
  watch(isEmptyChat, async () => {
    const el = composerEl.value
    if (!el) return
    const before = el.getBoundingClientRect()
    await nextTick()
    if (!composerEl.value) return
    if (globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches) return
    const after = composerEl.value.getBoundingClientRect()
    const dy = before.top - after.top
    if (Math.abs(dy) < 4) return
    composerEl.value.animate(
      [{ transform: `translateY(${dy}px)` }, { transform: 'translateY(0)' }],
      { duration: 500, easing: 'cubic-bezier(0.32, 0.72, 0, 1)' },
    )
  })

  function handleFileUpload(event: Event) {
    const target = event.target as HTMLInputElement
    const picked = target.files ? Array.from(target.files) : []
    if (subagentTranscript.value) return // read-only transcript: drop attachments silently
    addAttachments(picked)
    target.value = ''
  }

  // JCLAW-25 drop path. Mirrors the paperclip flow: read files off the
  // DataTransfer, route through addAttachments so the vision gate, size
  // cap, and thumbnail preview apply uniformly regardless of how the file
  // entered the composer.
  function handleDrop(event: DragEvent) {
    if (subagentTranscript.value) return
    const files = event.dataTransfer?.files
    if (!files || files.length === 0) return
    addAttachments(Array.from(files))
  }

  // JCLAW-25 paste path. Inspects the clipboard items for file entries —
  // typically a single image from a screenshot-copy (Cmd/Ctrl-Shift-4,
  // Windows Snipping Tool, etc.). Text pastes fall through untouched.
  function handlePaste(event: ClipboardEvent) {
    if (subagentTranscript.value) return
    const items = event.clipboardData?.items
    if (!items || items.length === 0) return
    const files: File[] = []
    for (const item of items) {
      if (item.kind === 'file') {
        const f = item.getAsFile()
        if (f) files.push(f)
      }
    }
    if (files.length === 0) return
    event.preventDefault()
    addAttachments(files)
  }

  return {
    completer,
    composerEl,
    onInputKeydown,
    onInputEnter,
    pickAutocomplete,
    autoResize,
    handleFileUpload,
    handleDrop,
    handlePaste,
  }
}
