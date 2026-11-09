import { describe, it, expect, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import type { Provider } from '~/composables/useProviders'
import type { Prompt, SlashCommand } from '~/types/api'
import { useChatComposer, type UseChatComposer, type UseChatComposerDeps } from '~/composables/useChatComposer'

const PROVIDERS: Provider[] = [{ name: 'openai', models: [{ id: 'gpt-4', name: 'GPT-4' }] }]

const SLASH_COMMANDS: SlashCommand[] = [
  { literal: '/new', name: 'new', description: 'Start a fresh conversation' },
  { literal: '/model', name: 'model', description: 'Show current model and its capabilities' },
  { literal: '/stop', name: 'stop', description: 'Stop the current reply' },
  { literal: '/prompt', name: 'prompt', description: 'Use a saved prompt from your library' },
]

const PROMPTS: Prompt[] = [
  {
    id: 1,
    title: 'Code review',
    content: 'Review this diff for correctness.',
    tags: 'engineering',
    category: 'ENGINEERING',
    categoryLabel: 'Engineering',
    createdAt: null,
    updatedAt: null,
  },
]

function key(over: Partial<KeyboardEvent> = {}) {
  return { key: 'Enter', preventDefault: vi.fn(), ...over } as unknown as KeyboardEvent
}

function mountComposer(over: Partial<UseChatComposerDeps> = {}) {
  const deps: UseChatComposerDeps = {
    input: ref(''),
    providers: ref<Provider[]>(PROVIDERS),
    slashCommands: ref<SlashCommand[]>(SLASH_COMMANDS),
    prompts: ref<Prompt[]>(PROMPTS),
    promptsLoading: ref(false),
    loadPrompts: vi.fn(),
    chatInput: ref<HTMLTextAreaElement | null>(null),
    subagentTranscript: ref(null),
    isEmptyChat: ref(false),
    addAttachments: vi.fn(),
    sendMessage: vi.fn(),
    ...over,
  }
  let api!: UseChatComposer
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useChatComposer(deps)
        return () => h('div')
      },
    }),
  )
  return { wrapper, deps, api }
}

describe('useChatComposer', () => {
  it('onInputEnter sends the message when the autocomplete popup is closed', () => {
    const { api, deps } = mountComposer({ input: ref('hello') })
    const ev = key()
    api.onInputEnter(ev)
    expect(deps.sendMessage).toHaveBeenCalledOnce()
    expect(ev.preventDefault).toHaveBeenCalled()
  })

  it('onInputKeydown is a no-op while the autocomplete popup is closed', () => {
    const { api, deps } = mountComposer({ input: ref('hello') })
    api.onInputKeydown(key({ key: 'ArrowDown' }))
    // No send, no crash — popup closed means keys pass through to the textarea.
    expect(deps.sendMessage).not.toHaveBeenCalled()
  })

  it('autoResize clamps the textarea height to 200px', () => {
    const el = { style: { height: '' }, scrollHeight: 300 } as unknown as HTMLTextAreaElement
    const { api } = mountComposer({ chatInput: ref(el) })
    api.autoResize()
    expect(el.style.height).toBe('200px')
  })

  it('handleFileUpload routes picked files to addAttachments and resets the input', () => {
    const { api, deps } = mountComposer()
    const file = new File(['x'], 'a.txt')
    const target = { files: [file], value: 'a.txt' } as unknown as HTMLInputElement
    api.handleFileUpload({ target } as unknown as Event)
    expect(deps.addAttachments).toHaveBeenCalledWith([file])
    expect(target.value).toBe('')
  })

  it('handleFileUpload is silently dropped in a read-only subagent transcript', () => {
    const { api, deps } = mountComposer({ subagentTranscript: ref({ agentId: 9, agentName: 'x' }) })
    const target = { files: [new File(['x'], 'a.txt')], value: 'a.txt' } as unknown as HTMLInputElement
    api.handleFileUpload({ target } as unknown as Event)
    expect(deps.addAttachments).not.toHaveBeenCalled()
  })

  it('handleDrop routes dropped files and no-ops on an empty drop', () => {
    const { api, deps } = mountComposer()
    const file = new File(['x'], 'b.png')
    api.handleDrop({ preventDefault: vi.fn(), dataTransfer: { files: [file] } } as unknown as DragEvent)
    expect(deps.addAttachments).toHaveBeenCalledWith([file])

    deps.addAttachments = vi.fn()
    api.handleDrop({ preventDefault: vi.fn(), dataTransfer: { files: [] } } as unknown as DragEvent)
    expect(deps.addAttachments).not.toHaveBeenCalled()
  })

  it('handlePaste extracts file items and preventDefaults, ignoring text-only pastes', () => {
    const { api, deps } = mountComposer()
    const file = new File(['x'], 'c.png')
    const pasteEvent = {
      preventDefault: vi.fn(),
      clipboardData: { items: [{ kind: 'file', getAsFile: () => file }] },
    } as unknown as ClipboardEvent
    api.handlePaste(pasteEvent)
    expect(deps.addAttachments).toHaveBeenCalledWith([file])
    expect(pasteEvent.preventDefault).toHaveBeenCalled()

    const textPaste = {
      preventDefault: vi.fn(),
      clipboardData: { items: [{ kind: 'string', getAsFile: () => null }] },
    } as unknown as ClipboardEvent
    deps.addAttachments = vi.fn()
    api.handlePaste(textPaste)
    expect(deps.addAttachments).not.toHaveBeenCalled()
    expect(textPaste.preventDefault).not.toHaveBeenCalled()
  })

  it('opens the /model autocomplete when the input matches the trigger', async () => {
    const input = ref('')
    const { api } = mountComposer({ input })
    input.value = '/model gpt'
    await nextTick()
    expect(api.completer.open.value).toBe(true)
    expect(api.completer.activeSourceId.value).toBe('model')
  })

  // ── JCLAW-1071: one popup, both sources, one Enter ──

  it('opens the "/" command menu and keeps Enter away from sendMessage', async () => {
    const input = ref('')
    const { api, deps } = mountComposer({ input })
    input.value = '/'
    await nextTick()
    expect(api.completer.open.value).toBe(true)
    expect(api.completer.activeSourceId.value).toBe('slash-command')

    api.onInputEnter(key())
    // Enter accepted the highlighted command instead of sending it as a message.
    expect(deps.sendMessage).not.toHaveBeenCalled()
    expect(input.value).toBe('/new ')
  })

  it('sends normally once the command menu has closed', async () => {
    const input = ref('')
    const { api, deps } = mountComposer({ input })
    input.value = '/zzz-not-a-command'
    await nextTick()
    expect(api.completer.open.value).toBe(false)

    api.onInputEnter(key())
    expect(deps.sendMessage).toHaveBeenCalledOnce()
  })

  it('pickAutocomplete accepts the clicked option, not the highlighted one', async () => {
    const input = ref('')
    const { api } = mountComposer({ input })
    input.value = '/'
    await nextTick()
    const stop = api.completer.options.value.find(o => o.value === '/stop')!
    api.pickAutocomplete(stop)
    expect(input.value).toBe('/stop ')
  })

  // ── JCLAW-1072: /prompt never reaches the model as literal text ──

  it('offers /prompt in the "/" menu alongside the backend commands', async () => {
    const input = ref('')
    const { api } = mountComposer({ input })
    input.value = '/'
    await nextTick()
    expect(api.completer.options.value.map(o => o.value)).toContain('/prompt')
  })

  it('loads the library on first entry into /prompt context, not on mount', async () => {
    const input = ref('')
    const loadPrompts = vi.fn()
    const { api } = mountComposer({ input, loadPrompts })
    expect(loadPrompts).not.toHaveBeenCalled()

    input.value = '/prompt '
    await nextTick()
    expect(loadPrompts).toHaveBeenCalled()
    expect(api.completer.activeSourceId.value).toBe('prompt')
  })

  it('re-renders when the library arrives after the popup opened', async () => {
    // The completer is pull-based, so a source whose data lands between
    // keystrokes would otherwise leave the popup on its "Loading" snapshot
    // until the user typed again. Caught in UAT, not by the unit suites.
    const input = ref('')
    const prompts = ref<Prompt[]>([])
    const promptsLoading = ref(true)
    const { api } = mountComposer({ input, prompts, promptsLoading })

    input.value = '/prompt code'
    await nextTick()
    expect(api.completer.options.value[0]?.detail).toBe('Loading prompts…')

    // Fetch resolves; no further keystroke.
    prompts.value = PROMPTS
    promptsLoading.value = false
    await nextTick()
    expect(api.completer.options.value.map(o => o.label)).toEqual(['Code review'])
  })

  it('Enter inserts the prompt body rather than sending', async () => {
    const input = ref('')
    const { api, deps } = mountComposer({ input })
    input.value = '/prompt code'
    await nextTick()

    api.onInputEnter(key())
    expect(deps.sendMessage).not.toHaveBeenCalled()
    expect(input.value).toBe('Review this diff for correctness.')
  })

  it('swallows Enter on the no-match status row instead of sending the literal', async () => {
    const input = ref('')
    const { api, deps } = mountComposer({ input })
    input.value = '/prompt zzz-nothing'
    await nextTick()
    // Popup stays open on a status row so the key has somewhere to go.
    expect(api.completer.open.value).toBe(true)
    expect(api.completer.options.value[0]?.disabled).toBe(true)

    const ev = key()
    api.onInputEnter(ev)
    expect(deps.sendMessage).not.toHaveBeenCalled()
    expect(ev.preventDefault).toHaveBeenCalled()
    expect(input.value).toBe('/prompt zzz-nothing') // unchanged, not cleared
  })

  it('still blocks send after Escape dismisses the picker', async () => {
    const input = ref('')
    const { api, deps } = mountComposer({ input })
    input.value = '/prompt zzz-nothing'
    await nextTick()
    api.onInputKeydown(key({ key: 'Escape' }))
    expect(api.completer.open.value).toBe(false)

    api.onInputEnter(key())
    expect(deps.sendMessage).not.toHaveBeenCalled()
    expect(api.completer.blocksSend.value).toBe(true)
  })

  it('unblocks send once the text is no longer a /prompt invocation', async () => {
    const input = ref('')
    const { api, deps } = mountComposer({ input })
    input.value = '/prompt zzz'
    await nextTick()
    input.value = 'just a normal message'
    await nextTick()
    expect(api.completer.blocksSend.value).toBe(false)

    api.onInputEnter(key())
    expect(deps.sendMessage).toHaveBeenCalledOnce()
  })
})
