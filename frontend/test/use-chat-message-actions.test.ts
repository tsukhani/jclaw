import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { defineComponent, h, ref, shallowRef } from 'vue'
import type { Message } from '~/types/api'
import {
  useChatMessageActions,
  type UseChatMessageActions,
  type UseChatMessageActionsDeps,
} from '~/composables/useChatMessageActions'

const writeText = vi.fn().mockResolvedValue(undefined)

beforeEach(() => {
  writeText.mockClear()
  Object.defineProperty(globalThis.navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  })
  registerEndpoint('/api/conversations/5/messages/10', { method: 'DELETE', handler: () => ({}) })
  registerEndpoint('/api/conversations/5/messages/11', { method: 'DELETE', handler: () => ({}) })
  registerEndpoint('/api/attachments/att-1', { method: 'DELETE', handler: () => ({}) })
})

function msg(over: Partial<Message>): Message {
  return { role: 'assistant', content: '', ...over } as Message
}

async function mountActions(over: Partial<UseChatMessageActionsDeps> = {}) {
  const deps: UseChatMessageActionsDeps = {
    messages: shallowRef<Message[]>([]),
    selectedConvoId: ref<number | null>(5),
    streaming: ref(false),
    input: ref(''),
    chatInput: ref<HTMLTextAreaElement | null>(null),
    sendMessage: vi.fn(),
    autoResize: vi.fn(),
    ...over,
  }
  let api!: UseChatMessageActions
  const wrapper = await mountSuspended(
    defineComponent({
      setup() {
        api = useChatMessageActions(deps)
        return () => h('div')
      },
    }),
  )
  return { wrapper, deps, api }
}

describe('useChatMessageActions', () => {
  it('toggles thinking / tool-calls collapse and per-call expansion', async () => {
    const { api } = await mountActions()
    const m = msg({ thinkingCollapsed: false }) as Message & { toolCallsCollapsed?: boolean }
    api.toggleThinking(m)
    expect(m.thinkingCollapsed).toBe(true)
    api.toggleToolCalls(m)
    expect(m.toolCallsCollapsed).toBe(true)
    const tc = { id: 't', name: 'x', icon: 'wrench', arguments: '', _expanded: false }
    api.toggleToolCallExpansion(tc)
    expect(tc._expanded).toBe(true)
  })

  it('copyMessage writes the content and flashes the copied id', async () => {
    const { api } = await mountActions()
    await api.copyMessage(msg({ id: 7, content: 'hello' }))
    expect(writeText).toHaveBeenCalledWith('hello')
    expect(api.copiedMessageId.value).toBe(7)
  })

  it('copyReasoning writes only the reasoning under a namespaced key', async () => {
    const { api } = await mountActions()
    await api.copyReasoning(msg({ id: 7, reasoning: 'because' }))
    expect(writeText).toHaveBeenCalledWith('because')
    expect(api.copiedMessageId.value).toBe('reason:7')
  })

  it('deleteMessage DELETEs the row and splices it out locally', async () => {
    const messages = shallowRef<Message[]>([msg({ id: 10, role: 'user', content: 'hi' }), msg({ id: 11, content: 'reply' })])
    const { api } = await mountActions({ messages })
    await api.deleteMessage(messages.value[0]!)
    expect(messages.value.map(m => m.id)).toEqual([11])
  })

  it('deleteAttachment soft-deletes via the API and flips the local flag', async () => {
    const { api } = await mountActions()
    const att = { uuid: 'att-1', deleted: false } as unknown as Parameters<UseChatMessageActions['deleteAttachment']>[0]
    await api.deleteAttachment(att)
    expect(att.deleted).toBe(true)
  })

  it('editUserMessage loads the text back into the composer and resizes', async () => {
    const input = ref('')
    const el = { focus: vi.fn(), setSelectionRange: vi.fn(), scrollIntoView: vi.fn(), value: '' } as unknown as HTMLTextAreaElement
    const { api, deps } = await mountActions({ input, chatInput: ref(el) })
    await api.editUserMessage(msg({ role: 'user', content: 'redo this' }))
    expect(input.value).toBe('redo this')
    expect(deps.autoResize).toHaveBeenCalled()
    expect((el.focus as ReturnType<typeof vi.fn>)).toHaveBeenCalled()
  })

  it('regenerateMessage truncates to the prior user turn, restores its text, and re-sends', async () => {
    const messages = shallowRef<Message[]>([
      msg({ id: 10, role: 'user', content: 'question' }),
      msg({ id: 11, role: 'assistant', content: 'answer' }),
    ])
    const input = ref('')
    const sendMessage = vi.fn()
    const { api } = await mountActions({ messages, input, sendMessage })
    await api.regenerateMessage(messages.value[1]!) // regenerate the assistant turn
    expect(messages.value).toHaveLength(0) // truncated to before the user turn
    expect(input.value).toBe('question')
    expect(sendMessage).toHaveBeenCalledOnce()
  })

  it('regenerateMessage is a no-op while streaming', async () => {
    const messages = shallowRef<Message[]>([msg({ id: 10, role: 'user' }), msg({ id: 11 })])
    const sendMessage = vi.fn()
    const { api } = await mountActions({ messages, streaming: ref(true), sendMessage })
    await api.regenerateMessage(messages.value[1]!)
    expect(sendMessage).not.toHaveBeenCalled()
    expect(messages.value).toHaveLength(2)
  })
})
