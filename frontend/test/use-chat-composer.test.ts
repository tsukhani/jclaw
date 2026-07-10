import { describe, it, expect, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { mount } from '@vue/test-utils'
import type { Provider } from '~/composables/useProviders'
import { useChatComposer, type UseChatComposer, type UseChatComposerDeps } from '~/composables/useChatComposer'

const PROVIDERS: Provider[] = [{ name: 'openai', models: [{ id: 'gpt-4', name: 'GPT-4' }] }]

function key(over: Partial<KeyboardEvent> = {}) {
  return { key: 'Enter', preventDefault: vi.fn(), ...over } as unknown as KeyboardEvent
}

function mountComposer(over: Partial<UseChatComposerDeps> = {}) {
  const deps: UseChatComposerDeps = {
    input: ref(''),
    providers: ref<Provider[]>(PROVIDERS),
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
    expect(api.modelAutocomplete.open.value).toBe(true)
  })
})
