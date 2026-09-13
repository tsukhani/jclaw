import { describe, it, expect, afterEach } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, h, nextTick, onUnmounted, ref, type PropType } from 'vue'
import ChatSubagentStack from '~/components/chat/ChatSubagentStack.vue'
import type { SubagentChip, SubagentRunStatus } from '~/composables/useChatSubagentChips'

function chip(id: number, status: SubagentRunStatus, label: string | null = null): SubagentChip {
  return { id, label, childAgentName: `main-sub-${id}`, childAgentId: 90 + id, childConversationId: 600 + id, status }
}

const mounted: Array<{ unmount: () => void }> = []

afterEach(() => {
  for (const wrapper of mounted.splice(0)) wrapper.unmount()
})

/** Mounts the stack wired the way chat.vue wires it, with a probe in the expanded slot. */
async function mountStack(initial: SubagentChip[], extra: { conversationId?: number, allRunsTotal?: number | null } = {}) {
  const runs = ref(initial)
  const expandedIds = ref(new Set<number>())
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
        expandedIds: expandedIds.value,
        ...extra,
        onToggle: (id: number) => {
          const next = new Set(expandedIds.value)
          if (!next.delete(id)) next.add(id)
          expandedIds.value = next
        },
        onClose: (id: number) => {
          closedIds.value = new Set(closedIds.value).add(id)
        },
      }, { expanded: ({ run }: { run: SubagentChip }) => h(Probe, { run }) })
    },
  }), { attachTo: document.body })
  mounted.push(wrapper)
  return { wrapper, probe, closedIds, runs }
}

describe('ChatSubagentStack', () => {
  it('renders one minimized row per status with its colour, dot and status word', async () => {
    const cases: Array<[SubagentRunStatus, string, string, string]> = [
      ['RUNNING', 'bg-blue-100', 'bg-blue-500', 'Running'],
      ['COMPLETED', 'bg-emerald-100', 'bg-emerald-500', 'Completed'],
      ['FAILED', 'bg-red-100', 'bg-red-500', 'Failed'],
      ['KILLED', 'bg-yellow-100', 'bg-yellow-500', 'Killed'],
      ['TIMEOUT', 'bg-orange-100', 'bg-orange-500', 'Timed out'],
    ]
    const { wrapper } = await mountStack(cases.map(([status], i) => chip(i + 1, status, `task ${i + 1}`)))

    const rows = wrapper.findAll('[data-testid="subagent-chip"]')
    expect(rows).toHaveLength(cases.length)
    cases.forEach(([status, chipClass, dotClass, word], i) => {
      const row = rows[i]!
      const dot = row.find('[data-testid="subagent-chip-dot"]')
      expect(row.classes()).toContain(chipClass)
      expect(dot.classes()).toContain(dotClass)
      expect(dot.classes().includes('animate-pulse')).toBe(status === 'RUNNING')
      expect(row.find('[data-testid="subagent-chip-label"]').text()).toBe(`task ${i + 1}`)
      expect(row.find('[data-testid="subagent-chip-status"]').text()).toBe(word)
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

  it('hands focus to the next chip on close, or the previous one when the last row closes', async () => {
    const { wrapper } = await mountStack([chip(1, 'RUNNING'), chip(2, 'RUNNING'), chip(3, 'COMPLETED')])
    const closeButton = (id: number) => wrapper.find(`[aria-label="Close main-sub-${id}"]`)

    await closeButton(1).trigger('click')
    await nextTick()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Expand main-sub-2')

    await closeButton(3).trigger('click')
    await nextTick()
    expect(document.activeElement?.getAttribute('aria-label')).toBe('Expand main-sub-2')
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
    await nextTick()
    expect(announcer().text()).toBe('Watch the downloads: Killed')
  })

  it('links to every run on the Subagents page when the chip list was cut short', async () => {
    const { wrapper } = await mountStack([chip(1, 'RUNNING')], { conversationId: 5, allRunsTotal: 150 })
    const link = wrapper.find('[data-testid="subagent-stack-all-runs"]')
    expect(link.attributes('href')).toBe('/subagents?parentConversationId=5')
    expect(link.text()).toBe('View all 150 on the Subagents page')

    const { wrapper: complete } = await mountStack([chip(2, 'RUNNING')], { conversationId: 5, allRunsTotal: null })
    expect(complete.find('[data-testid="subagent-stack-all-runs"]').exists()).toBe(false)
  })
})
