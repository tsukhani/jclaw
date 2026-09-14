import { describe, it, expect, afterEach, vi } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { defineComponent, h, nextTick, onUnmounted, ref, type PropType } from 'vue'
import ChatSubagentStack from '~/components/chat/ChatSubagentStack.vue'
import type { SubagentChip, SubagentRunStatus } from '~/composables/useChatSubagentChips'
import { SUBAGENT_STATUS_BADGE, SUBAGENT_STATUS_TEXT } from '~/utils/subagent-status'

function chip(id: number, status: SubagentRunStatus, label: string | null = null): SubagentChip {
  return { id, label, childAgentName: `main-sub-${id}`, childAgentId: 90 + id, childConversationId: 600 + id, status, startedAt: null, endedAt: null, outcome: null }
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
        runs: runs.value,
        expandedId: expandedId.value,
        conversationId: extra.conversationId ?? 5,
        runsTotal: extra.runsTotal ?? initial.length,
        onToggle: (id: number) => {
          expandedId.value = expandedId.value === id ? null : id
        },
      }, { expanded: ({ run }: { run: SubagentChip }) => h(Probe, { run }) })
    },
  }), { attachTo: document.body })
  mounted.push(wrapper)
  return { wrapper, probe, runs }
}

describe('ChatSubagentStack', () => {
  it('marks each status with a coloured icon and pills only the endings that need attention', async () => {
    const cases: Array<[SubagentRunStatus, string]> = [
      ['RUNNING', 'Running'],
      ['COMPLETED', 'Completed'],
      ['FAILED', 'Failed'],
      ['KILLED', 'Killed'],
      ['TIMEOUT', 'Timed out'],
    ]
    const pilled: SubagentRunStatus[] = ['FAILED', 'KILLED', 'TIMEOUT']
    const { wrapper } = await mountStack(cases.map(([status], i) => chip(i + 1, status, `task ${i + 1}`)))
    const statusHue = /^(dark:)?(bg|text|border)-(blue|emerald|red|yellow|orange)-/

    const rows = wrapper.findAll('[data-testid="subagent-chip"]')
    expect(rows).toHaveLength(cases.length)
    cases.forEach(([status, word], i) => {
      const row = rows[i]!
      const icon = row.find('[data-testid="subagent-chip-icon"]')
      const statusWord = row.find('[data-testid="subagent-chip-status"]')
      const toggle = row.find('[data-testid="subagent-chip-toggle"]')
      expect(row.classes().filter(c => statusHue.test(c))).toEqual([])
      expect(icon.classes()).toEqual(expect.arrayContaining(SUBAGENT_STATUS_TEXT[status].split(' ')))
      expect(icon.classes().includes('animate-spin')).toBe(status === 'RUNNING')
      if (pilled.includes(status)) expect(statusWord.classes()).toEqual(expect.arrayContaining(SUBAGENT_STATUS_BADGE[status].split(' ')))
      else expect(statusWord.classes().filter(c => statusHue.test(c))).toEqual([])
      expect(statusWord.text()).toBe(word)
      // The toggle's aria-label replaces its content, so the status reaches a screen reader as its description.
      expect(toggle.attributes('aria-describedby')!.split(' ')).toContain(statusWord.attributes('id'))
      expect(row.find('[data-testid="subagent-chip-label"]').text()).toBe(`task ${i + 1}`)
      expect(toggle.attributes('aria-expanded')).toBe('false')
      expect(row.find('[data-testid="subagent-chip-expanded"]').exists()).toBe(false)
    })
  })

  it('gives a run that ended badly its reason on the pill and above its transcript, but not a completed run its reply', async () => {
    const timedOut = { ...chip(1, 'TIMEOUT', 'Impatient wait'), outcome: 'Subagent run exceeded its 15-second idle budget (no activity)' }
    const completed = { ...chip(2, 'COMPLETED', 'Say hello'), outcome: 'HELLO' }
    const { wrapper } = await mountStack([timedOut, completed])
    const row = (i: number) => wrapper.findAll('[data-testid="subagent-chip"]')[i]!

    expect(row(0).find('[data-testid="subagent-chip-status"]').attributes('title')).toBe(timedOut.outcome)
    expect(row(1).find('[data-testid="subagent-chip-status"]').attributes('title')).toBeUndefined()

    await row(0).find('[data-testid="subagent-chip-toggle"]').trigger('click')
    expect(row(0).find('[data-testid="subagent-chip-reason"]').text()).toBe(timedOut.outcome)
    await row(1).find('[data-testid="subagent-chip-toggle"]').trigger('click')
    expect(row(1).find('[data-testid="subagent-chip-expanded"]').exists()).toBe(true)
    expect(row(1).find('[data-testid="subagent-chip-reason"]').exists()).toBe(false)
  })

  it('labels a chip with its spawn label and keeps the child agent name in its tooltip and toggle name', async () => {
    const { wrapper } = await mountStack([chip(1, 'RUNNING', 'Watch the downloads'), chip(2, 'RUNNING')])

    const labels = wrapper.findAll('[data-testid="subagent-chip-label"]')
    expect(labels[0]!.text()).toBe('Watch the downloads')
    expect(labels[0]!.attributes('title')).toBe('Watch the downloads · main-sub-1')
    expect(labels[1]!.text()).toBe('main-sub-2')
    expect(labels[1]!.attributes('title')).toBe('main-sub-2')
    // A spawn label is prose; only the generated agent name is set in monospace.
    expect(labels[0]!.classes()).not.toContain('font-mono')
    expect(labels[1]!.classes()).toContain('font-mono')

    const toggles = wrapper.findAll('[data-testid="subagent-chip-toggle"]')
    expect(toggles[0]!.attributes('aria-label')).toBe('Expand Watch the downloads (main-sub-1)')
    expect(toggles[1]!.attributes('aria-label')).toBe('Expand main-sub-2')
    // The whole row opens the transcript, not a chevron beside it.
    expect(toggles[0]!.element.contains(labels[0]!.element)).toBe(true)
  })

  it('shows how long a running chip has run, ticking each second, and how long ago a finished one ended', async () => {
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'Date'] })
    vi.setSystemTime(new Date('2026-09-14T10:02:14Z'))
    try {
      const running = { ...chip(1, 'RUNNING'), startedAt: '2026-09-14T10:00:00Z' }
      // The server writes Instant.toString(), which can carry microseconds.
      const finished = { ...chip(2, 'FAILED'), startedAt: '2026-09-14T09:50:00Z', endedAt: '2026-09-14T09:58:00.123456Z' }
      const { wrapper } = await mountStack([running, finished])
      const row = (i: number) => wrapper.findAll('[data-testid="subagent-chip"]')[i]!
      const time = (i: number) => row(i).find('[data-testid="subagent-chip-time"]')

      expect(time(0).text()).toBe('2m 14s')
      expect(time(1).text()).toBe('4m ago')
      expect(time(1).attributes('title')).toBe('Ran for 8m 0s')
      expect(row(1).find('[data-testid="subagent-chip-toggle"]').attributes('aria-describedby')!.split(' '))
        .toContain(time(1).attributes('id'))

      vi.advanceTimersByTime(1000)
      await nextTick()
      expect(time(0).text()).toBe('2m 15s')
    }
    finally {
      vi.useRealTimers()
    }
  })

  it('shows no time for a run whose row carries no timestamps', async () => {
    const { wrapper } = await mountStack([chip(1, 'RUNNING'), chip(2, 'COMPLETED')])
    expect(wrapper.find('[data-testid="subagent-chip-time"]').exists()).toBe(false)
  })

  it('heads the list with the run count, how many are running, and a link to the conversation\'s runs on the Subagents page', async () => {
    const { wrapper } = await mountStack([chip(1, 'RUNNING'), chip(2, 'COMPLETED')], { conversationId: 7, runsTotal: 3 })
    expect(wrapper.find('[data-testid="subagent-stack-count"]').text()).toBe('3 subagents · 1 running')
    const link = wrapper.find('[data-testid="subagent-stack-view-list"]')
    expect(link.attributes('href')).toBe('/subagents?parentConversationId=7')
    expect(link.text()).toBe('View all →')

    const { wrapper: single } = await mountStack([chip(3, 'COMPLETED')], { runsTotal: 1 })
    expect(single.find('[data-testid="subagent-stack-count"]').text()).toBe('1 subagent')
  })

  it('collapses the whole list from the header and opens it again, unmounting an open transcript meanwhile', async () => {
    const { wrapper, probe } = await mountStack([chip(1, 'RUNNING'), chip(2, 'COMPLETED')])
    const toggle = () => wrapper.find('[data-testid="subagent-stack-toggle"]')
    // The header itself is the toggle: it holds the count and sits above the list it controls.
    const list = document.getElementById('subagent-stack-list')!
    expect(list.compareDocumentPosition(toggle().element) & Node.DOCUMENT_POSITION_PRECEDING).toBeTruthy()
    expect(toggle().element.contains(wrapper.find('[data-testid="subagent-stack-count"]').element)).toBe(true)
    expect(toggle().attributes('aria-expanded')).toBe('true')
    expect(toggle().attributes('aria-controls')).toBe('subagent-stack-list')
    expect(document.getElementById('subagent-stack-list')).not.toBeNull()
    await wrapper.findAll('[data-testid="subagent-chip-toggle"]')[0]!.trigger('click')
    expect(wrapper.find('[data-testid="slot-probe"]').exists()).toBe(true)

    await toggle().trigger('click')
    expect(wrapper.findAll('[data-testid="subagent-chip"]')).toHaveLength(0)
    expect(probe.unmounts).toBe(1)
    expect(toggle().attributes('aria-expanded')).toBe('false')
    expect(toggle().attributes('aria-controls')).toBeUndefined()
    expect(wrapper.find('[data-testid="subagent-stack-count"]').text()).toBe('2 subagents · 1 running')

    await toggle().trigger('click')
    expect(wrapper.findAll('[data-testid="subagent-chip"]')).toHaveLength(2)
    expect(wrapper.find('[data-testid="slot-probe"]').text()).toBe('transcript 601')
  })

  it('keeps the header and its link, with no list toggle, when no chip is left to show', async () => {
    const { wrapper } = await mountStack([], { runsTotal: 2 })
    expect(wrapper.find('ul').exists()).toBe(false)
    expect(wrapper.find('[data-testid="subagent-stack-toggle"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="subagent-stack-count"]').element.closest('button')).toBeNull()
    expect(wrapper.find('[data-testid="subagent-stack-count"]').text()).toBe('2 subagents')
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
