import { describe, it, expect, vi } from 'vitest'
import { defineComponent, h, ref } from 'vue'
import { mount } from '@vue/test-utils'
import type { Message } from '~/types/api'
import { useChatSubagents, type UseChatSubagents } from '~/composables/useChatSubagents'

// Capture the codingrun.step handler the composable registers so the test can
// fire it directly, standing in for the live SSE bus. vi.hoisted keeps the
// holder available inside the (hoisted) vi.mock factory.
const bus = vi.hoisted(() => ({
  stepHandler: null as null | ((data: unknown, type: string) => void),
}))

vi.mock('~/composables/useEventBus', () => ({
  useEventBus: () => ({
    on: vi.fn(),
    off: vi.fn(),
    onEvent: (type: string, handler: (data: unknown, type: string) => void) => {
      if (type === 'codingrun.step') bus.stepHandler = handler
    },
  }),
}))

function msg(over: Partial<Message>): Message {
  return { role: 'assistant', content: '', ...over } as Message
}

function mountSubagents(msgs: Message[] = [], convoId: number | null = null) {
  const displayMessages = ref<Message[]>(msgs)
  const selectedConvoId = ref<number | null>(convoId)
  let api!: UseChatSubagents
  const wrapper = mount(
    defineComponent({
      setup() {
        api = useChatSubagents(displayMessages, selectedConvoId)
        return () => h('div')
      },
    }),
  )
  return { wrapper, displayMessages, selectedConvoId, api }
}

describe('useChatSubagents', () => {
  describe('subagentRunSlices', () => {
    it('marks run boundaries first / middle / last and passes non-run rows through as null', () => {
      const { api } = mountSubagents([
        msg({}),
        msg({ subagentRunId: 5 }),
        msg({ subagentRunId: 5 }),
        msg({ subagentRunId: 5 }),
        msg({}),
      ])
      const slices = api.subagentRunSlices.value
      expect(slices[0]).toBeNull()
      expect(slices[1]).toMatchObject({ runId: 5, position: 'first' })
      expect(slices[2]).toMatchObject({ runId: 5, position: 'middle' })
      expect(slices[3]).toMatchObject({ runId: 5, position: 'last' })
      expect(slices[4]).toBeNull()
    })

    it('treats a single-message run as a first-position slice', () => {
      const { api } = mountSubagents([msg({ subagentRunId: 7 })])
      expect(api.subagentRunSlices.value[0]).toMatchObject({ runId: 7, position: 'first' })
    })
  })

  describe('initSubagentCollapsedState', () => {
    it('collapses completed runs by default and reflects it in the slices', () => {
      const msgs = [msg({ subagentRunId: 5, content: 'Subagent completed' })]
      const { api } = mountSubagents(msgs)
      expect(api.subagentRunSlices.value[0]!.collapsed).toBe(false)
      api.initSubagentCollapsedState(msgs)
      expect(api.subagentRunSlices.value[0]!.collapsed).toBe(true)
    })

    it('leaves failed / timed-out runs expanded so the error is visible', () => {
      const msgs = [
        msg({ subagentRunId: 5, content: 'Spawning subagent: X' }),
        msg({ subagentRunId: 5, content: 'Subagent failed: boom' }),
      ]
      const { api } = mountSubagents(msgs)
      api.initSubagentCollapsedState(msgs)
      expect(api.subagentRunSlices.value[0]!.collapsed).toBe(false)
    })

    it('preserves an operator toggle across re-init (idempotent)', () => {
      const msgs = [msg({ subagentRunId: 5, content: 'Subagent completed' })]
      const { api } = mountSubagents(msgs)
      api.toggleSubagentRun(5) // operator expands
      expect(api.subagentRunSlices.value[0]!.collapsed).toBe(true)
      api.initSubagentCollapsedState(msgs)
      // Already in the set → left as the operator set it, not re-collapsed anew.
      expect(api.subagentRunSlices.value[0]!.collapsed).toBe(true)
    })
  })

  describe('toggleSubagentRun', () => {
    it('flips a run between collapsed and expanded', () => {
      const { api } = mountSubagents([msg({ subagentRunId: 5 })])
      expect(api.subagentRunSlices.value[0]!.collapsed).toBe(false)
      api.toggleSubagentRun(5)
      expect(api.subagentRunSlices.value[0]!.collapsed).toBe(true)
      api.toggleSubagentRun(5)
      expect(api.subagentRunSlices.value[0]!.collapsed).toBe(false)
    })
  })

  describe('subagentBlockLabel', () => {
    it('derives the label from the Spawning-subagent marker', () => {
      const msgs = [msg({ subagentRunId: 5, content: 'Spawning subagent: Research agent' })]
      const { api } = mountSubagents(msgs)
      expect(api.subagentBlockLabel(5, msgs)).toBe('Research agent')
    })

    it('falls back to a neutral label when no marker is present', () => {
      const msgs = [msg({ subagentRunId: 5, content: 'some output' })]
      const { api } = mountSubagents(msgs)
      expect(api.subagentBlockLabel(5, msgs)).toBe('Subagent run')
    })
  })

  describe('subagentBlockStatus', () => {
    it.each([
      ['Subagent completed', 'Completed'],
      ['Subagent failed: x', 'Failed'],
      ['Subagent timeout', 'Timed out'],
    ])('maps %s to %s', (content, expected) => {
      const msgs = [msg({ subagentRunId: 5, content })]
      const { api } = mountSubagents(msgs)
      expect(api.subagentBlockStatus(5, msgs)).toBe(expected)
    })

    it('reports Running while no end marker is present', () => {
      const msgs = [msg({ subagentRunId: 5, content: 'Spawning subagent: X' })]
      const { api } = mountSubagents(msgs)
      expect(api.subagentBlockStatus(5, msgs)).toBe('Running')
    })
  })

  describe('activeCodingRunId', () => {
    it('is null until a codingrun.step for the open conversation arrives', () => {
      const { api, selectedConvoId } = mountSubagents([], 1)
      expect(api.activeCodingRunId.value).toBeNull()
      bus.stepHandler?.({ runId: 9, conversationId: 1 }, 'codingrun.step')
      expect(api.activeCodingRunId.value).toBe(9)
      // Switching to a conversation with no run shows none.
      selectedConvoId.value = 2
      expect(api.activeCodingRunId.value).toBeNull()
    })

    it('keys runs per conversation and ignores steps without a runId', () => {
      const { api, selectedConvoId } = mountSubagents([], 1)
      bus.stepHandler?.({ conversationId: 1 }, 'codingrun.step') // no runId → ignored
      expect(api.activeCodingRunId.value).toBeNull()
      bus.stepHandler?.({ runId: 3, conversationId: 2 }, 'codingrun.step')
      expect(api.activeCodingRunId.value).toBeNull() // convo 1 is open, run was for 2
      selectedConvoId.value = 2
      expect(api.activeCodingRunId.value).toBe(3)
    })
  })
})
