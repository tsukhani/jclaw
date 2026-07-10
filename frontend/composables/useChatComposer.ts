import { nextTick, ref, watch, type Ref } from 'vue'
import { useModelAutocomplete } from '~/composables/useModelAutocomplete'
import type { Provider } from '~/composables/useProviders'

/**
 * Composer-local interaction logic (JCLAW-690 stage 5c; behaviour extracted
 * verbatim from pages/chat.vue). The composer <form> template stays in the page
 * — its DOM refs (chatInput, composerEl, fileInput) bind there — but the reactive
 * glue moves here: the /model NAME autocomplete (JCLAW-114), the textarea
 * keyboard/resize handlers, the drop/paste/file-input attachment routing
 * (JCLAW-25), and the empty↔active FLIP animation.
 *
 * Refs it touches (chatInput, composerEl) are page-owned and passed in / returned
 * so the template keeps binding them locally — no cross-boundary ref forwarding.
 */
export interface UseChatComposerDeps {
  input: Ref<string>
  providers: Ref<Provider[]>
  chatInput: Ref<HTMLTextAreaElement | null>
  subagentTranscript: Ref<{ agentId: number, agentName: string } | null>
  isEmptyChat: Ref<boolean>
  addAttachments: (files: File[]) => void
  sendMessage: () => void
}

export interface UseChatComposer {
  modelAutocomplete: ReturnType<typeof useModelAutocomplete>
  composerEl: Ref<HTMLElement | null>
  onInputKeydown: (event: KeyboardEvent) => void
  onInputEnter: (event: KeyboardEvent) => void
  pickAutocomplete: (choice: string) => void
  autoResize: () => void
  handleFileUpload: (event: Event) => void
  handleDrop: (event: DragEvent) => void
  handlePaste: (event: ClipboardEvent) => void
}

export function useChatComposer(deps: UseChatComposerDeps): UseChatComposer {
  const { input, providers, chatInput, subagentTranscript, isEmptyChat, addAttachments, sendMessage } = deps

  // Owned here (only the FLIP watcher below reads it); the page binds it via
  // ref="composerEl" on the composer wrapper div.
  const composerEl = ref<HTMLElement | null>(null)

  /**
   * JCLAW-114: /model NAME autocomplete state. Driven by the input watcher
   * below — watches for the /model <query> prefix and surfaces a floating
   * popup above the textarea with matching provider/model pairs from the
   * (already-filtered) providers list. Keyboard nav via ArrowUp/Down/Enter/
   * Tab/Escape, mouse via click.
   */
  const modelAutocomplete = useModelAutocomplete(providers)

  watch(input, (text) => {
    modelAutocomplete.update(text)
  })

  function onInputKeydown(event: KeyboardEvent) {
    // Only steal keys while the popup is open — when it's closed, the textarea
    // behaves exactly as before (Enter sends, everything else is text input).
    if (!modelAutocomplete.open.value) return
    if (event.key === 'ArrowDown') {
      event.preventDefault()
      modelAutocomplete.moveHighlight('down')
    }
    else if (event.key === 'ArrowUp') {
      event.preventDefault()
      modelAutocomplete.moveHighlight('up')
    }
    else if (event.key === 'Tab' || event.key === 'Enter') {
      const replacement = modelAutocomplete.accept(input.value)
      if (replacement !== null) {
        event.preventDefault()
        input.value = replacement
        nextTick(() => autoResize())
      }
    }
    else if (event.key === 'Escape') {
      event.preventDefault()
      modelAutocomplete.close()
    }
  }

  function onInputEnter(event: KeyboardEvent) {
    // When the autocomplete popup is open, Enter accepts the selection
    // (handled by onInputKeydown). Otherwise it sends the message.
    if (modelAutocomplete.open.value) {
      onInputKeydown(event)
      return
    }
    event.preventDefault()
    sendMessage()
  }

  function pickAutocomplete(choice: string) {
    modelAutocomplete.moveHighlight('down') // no-op if already highlighted
    const idx = modelAutocomplete.options.value.indexOf(choice)
    if (idx >= 0) modelAutocomplete.highlightedIndex.value = idx
    const replacement = modelAutocomplete.accept(input.value)
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
    modelAutocomplete,
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
