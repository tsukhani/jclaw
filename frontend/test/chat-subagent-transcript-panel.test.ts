import { describe, it, expect, vi, afterEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick, ref } from 'vue'
import ChatSubagentTranscriptPanel from '~/components/chat/ChatSubagentTranscriptPanel.vue'
import type { SubagentRunStatus } from '~/composables/useSubagentTranscript'

afterEach(() => {
  vi.useRealTimers()
})

function row(id: number, o: Record<string, unknown> = {}) {
  return { id, role: 'assistant', content: `reply ${id}`, createdAt: '2026-09-13T00:00:00Z', ...o }
}

// Each test serves its own conversation id: the transcript cache is module-level by design.
function serve(convoId: number, initial: unknown[]) {
  const server = { rows: [...initial], calls: 0, gate: null as Promise<void> | null }
  registerEndpoint(`/api/conversations/${convoId}/messages`, async () => {
    if (server.gate) await server.gate
    server.calls++
    return structuredClone(server.rows)
  })
  return server
}

async function mountPanel(convoId: number, initial: SubagentRunStatus = 'RUNNING') {
  const status = ref<SubagentRunStatus>(initial)
  const wrapper = await mountSuspended(defineComponent({
    setup: () => () => h(ChatSubagentTranscriptPanel, { childConversationId: convoId, status: status.value, agentId: null }),
  }))
  return { wrapper, status }
}

function occurrences(text: string, needle: string) {
  return text.split(needle).length - 1
}

function fakeLayout(el: HTMLElement, layout: { scrollTop: number, scrollHeight: () => number, clientHeight: number }) {
  Object.defineProperty(el, 'scrollHeight', { configurable: true, get: layout.scrollHeight })
  Object.defineProperty(el, 'clientHeight', { configurable: true, value: layout.clientHeight })
  Object.defineProperty(el, 'scrollTop', { configurable: true, writable: true, value: layout.scrollTop })
}

describe('ChatSubagentTranscriptPanel', () => {
  it('renders the child transcript through the chat page\'s message renderer', async () => {
    serve(201, [
      row(1, { role: 'user', content: 'hello there' }),
      row(2, { content: null, toolCalls: [{ id: 'c1', function: { name: 'web_search', arguments: '{"query":"cats"}' } }] }),
      row(3, { role: 'tool', content: 'found cats', toolResults: 'c1' }),
      row(4, { content: '# Big Heading', reasoning: 'let me think' }),
    ])
    const { wrapper } = await mountPanel(201, 'COMPLETED')
    await vi.waitFor(() => expect(wrapper.text()).toContain('Big Heading'))

    expect(wrapper.text()).toContain('hello there')
    expect(wrapper.text()).toContain('1 tool call')
    expect(wrapper.find('textarea').exists()).toBe(false)

    // Collapsed by default, as on a chat reload; the toggle has to reach a row it mutates in place.
    expect(wrapper.find('[data-reasoning-body]').exists()).toBe(false)
    await wrapper.findAll('button').find(b => b.text().includes('Thinking'))!.trigger('click')
    expect(wrapper.find('[data-reasoning-body]').text()).toContain('let me think')
  })

  it('links to the full-page transcript', async () => {
    serve(202, [row(1)])
    const { wrapper } = await mountPanel(202, 'COMPLETED')
    expect(wrapper.find('[data-testid="subagent-transcript-full"]').attributes('href')).toBe('/chat?conversation=202')
  })

  it('shows a placeholder for an empty transcript', async () => {
    serve(203, [])
    const { wrapper } = await mountPanel(203, 'RUNNING')
    await vi.waitFor(() => expect(wrapper.find('[data-testid="subagent-transcript-empty"]').exists()).toBe(true))
  })

  it('appends a new message while RUNNING without duplicating the ones shown', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const server = serve(204, [row(1)])
    const { wrapper } = await mountPanel(204, 'RUNNING')
    await vi.waitFor(() => expect(wrapper.text()).toContain('reply 1'))

    server.rows.push(row(2))
    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(wrapper.text()).toContain('reply 2'))

    vi.advanceTimersByTime(5000)
    await vi.waitFor(() => expect(server.calls).toBe(3))
    await flushPromises()
    expect(occurrences(wrapper.text(), 'reply 1')).toBe(1)
    expect(occurrences(wrapper.text(), 'reply 2')).toBe(1)
  })

  it('fetches the last messages when the run ends, then stops refreshing', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const server = serve(205, [row(1)])
    const { wrapper, status } = await mountPanel(205, 'RUNNING')
    await vi.waitFor(() => expect(wrapper.text()).toContain('reply 1'))

    server.rows.push(row(2))
    status.value = 'KILLED'
    await vi.waitFor(() => expect(wrapper.text()).toContain('reply 2'))

    vi.advanceTimersByTime(20_000)
    await flushPromises()
    expect(server.calls).toBe(2)
  })

  it('stops refreshing when unmounted, as a collapsed chip does', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] })
    const server = serve(206, [row(1)])
    const { wrapper } = await mountPanel(206, 'RUNNING')
    await vi.waitFor(() => expect(server.calls).toBe(1))

    wrapper.unmount()
    vi.advanceTimersByTime(20_000)
    await flushPromises()
    expect(server.calls).toBe(1)
  })

  it('shows what was loaded at once on re-expand, then catches up', async () => {
    const server = serve(207, [row(1)])
    const first = await mountPanel(207, 'RUNNING')
    await vi.waitFor(() => expect(first.wrapper.text()).toContain('reply 1'))
    first.wrapper.unmount()

    let release!: () => void
    server.gate = new Promise((resolve) => {
      release = resolve
    })
    server.rows.push(row(2))
    const second = await mountPanel(207, 'RUNNING')
    expect(second.wrapper.text()).toContain('reply 1')
    expect(second.wrapper.text()).not.toContain('reply 2')

    release()
    await vi.waitFor(() => expect(second.wrapper.text()).toContain('reply 2'))
  })

  it('keeps the reader\'s place above the bottom when a message arrives', async () => {
    const server = serve(208, [row(1)])
    const { wrapper, status } = await mountPanel(208, 'RUNNING')
    await vi.waitFor(() => expect(wrapper.text()).toContain('reply 1'))
    const scroller = wrapper.find('[data-testid="subagent-transcript-scroll"]')
    const el = scroller.element as HTMLElement
    let height = 1000
    fakeLayout(el, { scrollTop: 100, scrollHeight: () => height, clientHeight: 200 })
    await scroller.trigger('scroll')

    height = 1400
    server.rows.push(row(2))
    status.value = 'COMPLETED'
    await vi.waitFor(() => expect(wrapper.text()).toContain('reply 2'))
    await nextTick()
    expect(el.scrollTop).toBe(100)
  })

  it('follows new messages when the reader is already at the bottom', async () => {
    const server = serve(209, [row(1)])
    const { wrapper, status } = await mountPanel(209, 'RUNNING')
    await vi.waitFor(() => expect(wrapper.text()).toContain('reply 1'))
    const scroller = wrapper.find('[data-testid="subagent-transcript-scroll"]')
    const el = scroller.element as HTMLElement
    let height = 1000
    fakeLayout(el, { scrollTop: 800, scrollHeight: () => height, clientHeight: 200 })
    await scroller.trigger('scroll')

    height = 1400
    server.rows.push(row(2))
    status.value = 'COMPLETED'
    await vi.waitFor(() => expect(wrapper.text()).toContain('reply 2'))
    await nextTick()
    expect(el.scrollTop).toBe(1400)
  })

  it('wires no delete action: a click on the row\'s delete control sends nothing', async () => {
    serve(210, [row(1, { role: 'user', content: 'keep me' })])
    let deleted = false
    registerEndpoint('/api/conversations/210/messages/1', {
      method: 'DELETE',
      handler: () => {
        deleted = true
        return {}
      },
    })
    const { wrapper } = await mountPanel(210, 'COMPLETED')
    await vi.waitFor(() => expect(wrapper.text()).toContain('keep me'))

    await wrapper.find('button[title="Delete message"]').trigger('click')
    await flushPromises()
    expect(deleted).toBe(false)
    expect(wrapper.text()).toContain('keep me')
  })
})
