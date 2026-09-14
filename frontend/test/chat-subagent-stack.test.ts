import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick, onUnmounted, ref, type PropType } from 'vue'
import ChatSubagentStack from '~/components/chat/ChatSubagentStack.vue'
import type { SubagentChip, SubagentRunStatus } from '~/composables/useChatSubagentChips'
import { SUBAGENT_STATUS_BADGE } from '~/utils/subagent-status'

function chip(id: number, status: SubagentRunStatus, label: string | null = null): SubagentChip {
  return { id, label, childAgentName: `main-sub-${id}`, childAgentId: 90 + id, childConversationId: 600 + id, status }
}

const mounted: Array<{ unmount: () => void }> = []

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
})

/** Records every scrollIntoView call until restore() puts the prototype back. */
function captureScrollIntoView() {
  const proto = Element.prototype as { scrollIntoView?: (options?: ScrollIntoViewOptions) => void }
  const original = Object.getOwnPropertyDescriptor(proto, 'scrollIntoView')
  const scrolled: Array<{ el: Element, options: unknown }> = []
  proto.scrollIntoView = function (this: Element, options?: ScrollIntoViewOptions) {
    scrolled.push({ el: this, options })
  }
  const restore = () => {
    if (original) Object.defineProperty(proto, 'scrollIntoView', original)
    else delete proto.scrollIntoView
  }
  return { scrolled, restore }
}

/** Mounts the stack wired the way chat.vue wires it, with a probe in the expanded slot. */
async function mountStack(initial: SubagentChip[], extra: { conversationId?: number, runsTotal?: number } = {}) {
  const runs = ref(initial)
  const expandedId = ref<number | null>(null)
  const closedIds = ref(new Set<number>())
  const probe = { unmounts: 0 }
  const Probe = defineComponent({
    props: { run: { type: Object as PropType<SubagentChip>, required: true } },
    setup(props) {
      onUnmounted(() => {
        probe.unmounts++
      })
      return () => h('div', { 'data-testid': 'slot-probe' }, `transcript ${props.run.childConversationId}`)
    },
  })
  const wrapper = await mountSuspended(defineComponent({
    setup() {
      return () => h(ChatSubagentStack, {
        runs: runs.value.filter(r => !closedIds.value.has(r.id)),
        expandedId: expandedId.value,
        conversationId: extra.conversationId ?? 5,
        runsTotal: extra.runsTotal ?? initial.length,
        onToggle: (id: number) => {
          expandedId.value = expandedId.value === id ? null : id
        },
        onClose: (id: number) => {
          closedIds.value = new Set(closedIds.value).add(id)
          if (expandedId.value === id) expandedId.value = null
        },
      }, { expanded: ({ run }: { run: SubagentChip }) => h(Probe, { run }) })
    },
  }), { attachTo: document.body })
  mounted.push(wrapper)
  return { wrapper, probe, closedIds, runs }
}

describe('ChatSubagentStack', () => {
  it('renders one neutral row per status, with the status colour, dot and word in its pill', async () => {
    const cases: Array<[SubagentRunStatus, string]> = [
      ['RUNNING', 'Running'],
      ['COMPLETED', 'Completed'],
      ['FAILED', 'Failed'],
      ['KILLED', 'Killed'],
      ['TIMEOUT', 'Timed out'],
    ]
    const { wrapper } = await mountStack(cases.map(([status], i) => chip(i + 1, status, `task ${i + 1}`)))
    const statusHue = /^(dark:)?(bg|text|border)-(blue|emerald|red|yellow|orange)-/

    const rows = wrapper.findAll('[data-testid="subagent-chip"]')
    expect(rows).toHaveLength(cases.length)
    cases.forEach(([status, word], i) => {
      const row = rows[i]!
      const pill = row.find('[data-testid="subagent-chip-status"]')
      const dot = pill.find('[data-testid="subagent-chip-dot"]')
      expect(row.classes().filter(c => statusHue.test(c))).toEqual([])
      expect(pill.classes()).toEqual(expect.arrayContaining(SUBAGENT_STATUS_BADGE[status].split(' ')))
      expect(dot.classes()).toContain('bg-current')
      expect(dot.classes().includes('animate-pulse')).toBe(status === 'RUNNING')
      expect(row.find('[data-testid="subagent-chip-label"]').text()).toBe(`task ${i + 1}`)
      expect(pill.text()).toBe(word)
      expect(row.find('[data-testid="subagent-chip-toggle"]').attributes('aria-expanded')).toBe('false')
      expect(row.find('[data-testid="subagent-chip-expanded"]').exists()).toBe(false)
    })
  })

  it('labels a chip with its spawn label and keeps the child agent name in its tooltip and toggle name', async () => {
    const { wrapper } = await mountStack([chip(1, 'RUNNING', 'Watch the downloads'), chip(2, 'RUNNING')])

    const labels = wrapper.findAll('[data-testid="subagent-chip-label"]')
    expect(labels[0]!.text()).toBe('Watch the downloads')
    expect(labels[0]!.attributes('title')).toBe('Watch the downloads · main-sub-1')
    expect(labels[1]!.text()).toBe('main-sub-2')
    expect(labels[1]!.attributes('title')).toBe('main-sub-2')

    const toggles = wrapper.findAll('[data-testid="subagent-chip-toggle"]')
    expect(toggles[0]!.attributes('aria-label')).toBe('Expand Watch the downloads (main-sub-1)')
    expect(toggles[1]!.attributes('aria-label')).toBe('Expand main-sub-2')
  })

  it('heads the list with the run count and a link to the conversation\'s runs on the Subagents page', async () => {
    const { wrapper } = await mountStack([chip(1, 'RUNNING'), chip(2, 'COMPLETED')], { conversationId: 7, runsTotal: 3 })
    expect(wrapper.find('[data-testid="subagent-stack-count"]').text()).toBe('3 subagents spawned in this conversation')
    const link = wrapper.find('[data-testid="subagent-stack-view-list"]')
    expect(link.attributes('href')).toBe('/subagents?parentConversationId=7')
    expect(link.text()).toBe('View list →')

    const { wrapper: single } = await mountStack([chip(3, 'RUNNING')], { runsTotal: 1 })
    expect(single.find('[data-testid="subagent-stack-count"]').text()).toBe('1 subagent spawned in this conversation')
  })

  it('collapses the whole list from the header and opens it again, unmounting an open transcript meanwhile', async () => {
    const { wrapper, probe } = await mountStack([chip(1, 'RUNNING'), chip(2, 'COMPLETED')])
    const toggle = () => wrapper.find('[data-testid="subagent-stack-toggle"]')
    // The toggle is the shade's bottom rail: after the list, not in the header row.
    const list = document.getElementById('subagent-stack-list')!
    expect(list.compareDocumentPosition(toggle().element) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(wrapper.find('[data-testid="subagent-stack-count"]').element.parentElement!.contains(toggle().element)).toBe(false)
    expect(toggle().attributes('aria-expanded')).toBe('true')
    expect(toggle().attributes('aria-label')).toBe('Collapse the subagent list')
    expect(toggle().attributes('aria-controls')).toBe('subagent-stack-list')
    expect(document.getElementById('subagent-stack-list')).not.toBeNull()
    await wrapper.findAll('[data-testid="subagent-chip-toggle"]')[0]!.trigger('click')
    expect(wrapper.find('[data-testid="slot-probe"]').exists()).toBe(true)

    await toggle().trigger('click')
    expect(wrapper.findAll('[data-testid="subagent-chip"]')).toHaveLength(0)
    expect(probe.unmounts).toBe(1)
    expect(toggle().attributes('aria-expanded')).toBe('false')
    expect(toggle().attributes('aria-label')).toBe('Expand the subagent list')
    expect(toggle().attributes('aria-controls')).toBeUndefined()
    expect(wrapper.find('[data-testid="subagent-stack-count"]').text()).toBe('2 subagents spawned in this conversation')

    await toggle().trigger('click')
    expect(wrapper.findAll('[data-testid="subagent-chip"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="slot-probe"]').text()).toBe('transcript 601')
  })

  it('keeps the header and its link, with no list toggle, when no chip is left to show', async () => {
    const { wrapper } = await mountStack([], { runsTotal: 2 })
    expect(wrapper.find('ul').exists()).toBe(false)
    expect(wrapper.find('[data-testid="subagent-stack-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="subagent-stack-count"]').text()).toBe('2 subagents spawned in this conversation')
    expect(wrapper.find('[data-testid="subagent-stack-view-list"]').exists()).toBe(true)
  })

  it('mounts the expanded slot beneath the one chip expanded and unmounts it on collapse', async () => {
    const { wrapper, probe } = await mountStack([chip(1, 'RUNNING'), chip(2, 'COMPLETED')])
    const rows = () => wrapper.findAll('[data-testid="subagent-chip"]')
    const toggle = () => rows()[0]!.find('[data-testid="subagent-chip-toggle"]')
    expect(toggle().attributes('aria-controls')).toBeUndefined()

    await toggle().trigger('click')
    expect(rows()[0]!.find('[data-testid="slot-probe"]').text()).toBe('transcript 601')
    expect(toggle().attributes('aria-expanded')).toBe('true')
    expect(toggle().attributes('aria-controls')).toBe('subagent-chip-panel-1')
    expect(document.getElementById('subagent-chip-panel-1')).not.toBeNull()
    // The panel in the slot owns the height budget; a second scroller here would clip its footer.
    const expanded = rows()[0]!.find('[data-testid="subagent-chip-expanded"]')
    expect(expanded.classes().filter(c => c.startsWith('overflow') || c.startsWith('max-h'))).toEqual([])
    expect(rows()[1]!.find('[data-testid="subagent-chip-expanded"]').exists()).toBe(false)

    await toggle().trigger('click')
    expect(wrapper.find('[data-testid="slot-probe"]').exists()).toBe(false)
    expect(probe.unmounts).toBe(1)
    expect(toggle().attributes('aria-controls')).toBeUndefined()
  })

  it('closes a chip from its named close control', async () => {
    const { wrapper, probe, closedIds } = await mountStack([chip(1, 'RUNNING', 'Watch the downloads'), chip(2, 'FAILED')])
    const first = wrapper.findAll('[data-testid="subagent-chip"]')[0]!
    await first.find('[data-testid="subagent-chip-toggle"]').trigger('click')

    const close = wrapper.findAll('[data-testid="subagent-chip"]')[0]!.find('[data-testid="subagent-chip-close"]')
    expect(close.attributes('aria-label')).toBe('Close Watch the downloads')
    await close.trigger('click')

    expect([...closedIds.value]).toEqual([1])
    const rows = wrapper.findAll('[data-testid="subagent-chip"]')
    expect(rows).toHaveLength(1)
    expect(rows[0]!.attributes('data-status')).toBe('FAILED')
    expect(probe.unmounts).toBe(1)
  })

  it('hands focus to the next chip on close, the previous one when the last row closes, and the header link after the final chip', async () => {
    const { wrapper } = await mountStack([chip(1, 'RUNNING'), chip(2, 'RUNNING'), chip(3, 'COMPLETED')])
    const closeButton = (id: number) => wrapper.find(`[aria-label="Close main-sub-${id}"]`)

    await closeButton(1).trigger('click')
    await nextTick()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Expand main-sub-2')

    await closeButton(3).trigger('click')
    await nextTick()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Expand main-sub-2')

    await closeButton(2).trigger('click')
    await nextTick()
    expect(document.activeElement).toBe(wrapper.find('[data-testid="subagent-stack-view-list"]').element)
  })

  it('announces a run that ends, but not a chip that arrives already finished', async () => {
    const { wrapper, runs } = await mountStack([chip(1, 'RUNNING', 'Watch the downloads'), chip(2, 'COMPLETED')])
    const announcer = () => wrapper.find('[data-testid="subagent-stack-announcer"]')
    expect(announcer().attributes('aria-live')).toBe('polite')
    expect(announcer().text()).toBe('')

    runs.value = [chip(1, 'RUNNING', 'Watch the downloads'), chip(2, 'COMPLETED'), chip(3, 'FAILED')]
    await nextTick()
    expect(announcer().text()).toBe('')

    runs.value = [chip(1, 'KILLED', 'Watch the downloads'), chip(2, 'COMPLETED'), chip(3, 'FAILED')]
    await flushPromises()
    expect(announcer().text()).toBe('Watch the downloads: Killed')
  })

  it('announces a second ending worded the same as the first', async () => {
    const { wrapper, runs } = await mountStack([chip(1, 'RUNNING', 'Fetch'), chip(2, 'RUNNING', 'Fetch')])
    const announcer = wrapper.find('[data-testid="subagent-stack-announcer"]').element
    const written: string[] = []
    const observer = new MutationObserver((records) => {
      for (const record of records) record.addedNodes.forEach(node => written.push(node.textContent ?? ''))
    })
    observer.observe(announcer, { childList: true, characterData: true, subtree: true })

    runs.value = [chip(1, 'FAILED', 'Fetch'), chip(2, 'RUNNING', 'Fetch')]
    await flushPromises()
    runs.value = [chip(1, 'FAILED', 'Fetch'), chip(2, 'FAILED', 'Fetch')]
    await flushPromises()
    observer.disconnect()
    expect(written.filter(text => text === 'Fetch: Failed')).toHaveLength(2)
  })

  it('scrolls a chip it expands into view within the stack, and not on collapse', async () => {
    const { scrolled, restore } = captureScrollIntoView()
    try {
      const { wrapper } = await mountStack([chip(1, 'RUNNING'), chip(2, 'COMPLETED')])
      const rows = () => wrapper.findAll('[data-testid="subagent-chip"]')
      await rows()[1]!.find('[data-testid="subagent-chip-toggle"]').trigger('click')
      expect(scrolled).toEqual([{ el: rows()[1]!.element, options: { block: 'nearest' } }])

      await rows()[1]!.find('[data-testid="subagent-chip-toggle"]').trigger('click')
      expect(scrolled).toHaveLength(1)
    }
    finally {
      restore()
    }
  })

  it('keeps an expanded chip in view as its transcript grows, and again when the list reopens', async () => {
    const { scrolled, restore } = captureScrollIntoView()
    const resize = { fire: () => {}, observed: [] as Element[] }
    vi.stubGlobal('ResizeObserver', class {
      constructor(callback: (entries: Array<{ target: Element }>) => void) {
        resize.fire = () => callback(resize.observed.map(target => ({ target })))
      }

      observe(target: Element) {
        resize.observed.push(target)
      }

      unobserve() {}
      disconnect() {
        resize.observed = []
      }
    })
    try {
      const { wrapper } = await mountStack([chip(1, 'RUNNING'), chip(2, 'COMPLETED')])
      const row = (i: number) => wrapper.findAll('[data-testid="subagent-chip"]')[i]!.element
      const listToggle = () => wrapper.find('[data-testid="subagent-stack-toggle"]')

      await wrapper.findAll('[data-testid="subagent-chip-toggle"]')[1]!.trigger('click')
      expect(resize.observed).toEqual([row(1)])
      // The transcript arrives and the row grows.
      resize.fire()
      expect(scrolled.map(s => s.el)).toEqual([row(1), row(1)])

      await listToggle().trigger('click')
      expect(resize.observed).toEqual([])
      await listToggle().trigger('click')
      expect(resize.observed).toEqual([row(1)])
      expect(scrolled).toHaveLength(3)
      expect(scrolled.at(-1)!.el).toBe(row(1))
    }
    finally {
      vi.unstubAllGlobals()
      restore()
    }
  })
})
