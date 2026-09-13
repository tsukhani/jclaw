import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import { defineComponent, h, onUnmounted, ref, type PropType } from 'vue'
import ChatSubagentStack from '~/components/chat/ChatSubagentStack.vue'
import type { SubagentChip, SubagentRunStatus } from '~/composables/useChatSubagentChips'

function chip(id: number, status: SubagentRunStatus, label: string | null = null): SubagentChip {
  return { id, label, childAgentName: `main-sub-${id}`, childAgentId: 90 + id, childConversationId: 600 + id, status }
}

/** Mounts the stack wired the way chat.vue wires it, with a probe in the expanded slot. */
async function mountStack(runs: SubagentChip[]) {
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
        runs: runs.filter(r => !closedIds.value.has(r.id)),
        expandedIds: expandedIds.value,
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
  }))
  return { wrapper, probe, closedIds }
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

  it('labels a chip with its spawn label, falls back to the child agent name, and keeps that name as the tooltip', async () => {
    const { wrapper } = await mountStack([chip(1, 'RUNNING', 'Watch the downloads'), chip(2, 'RUNNING')])

    const labels = wrapper.findAll('[data-testid="subagent-chip-label"]')
    expect(labels[0]!.text()).toBe('Watch the downloads')
    expect(labels[0]!.attributes('title')).toBe('main-sub-1')
    expect(labels[1]!.text()).toBe('main-sub-2')
    expect(labels[1]!.attributes('title')).toBe('main-sub-2')
  })

  it('mounts the expanded slot beneath the one chip expanded and unmounts it on collapse', async () => {
    const { wrapper, probe } = await mountStack([chip(1, 'RUNNING'), chip(2, 'COMPLETED')])
    const rows = () => wrapper.findAll('[data-testid="subagent-chip"]')

    await rows()[0]!.find('[data-testid="subagent-chip-toggle"]').trigger('click')
    expect(rows()[0]!.find('[data-testid="slot-probe"]').text()).toBe('transcript 601')
    expect(rows()[0]!.find('[data-testid="subagent-chip-toggle"]').attributes('aria-expanded')).toBe('true')
    expect(rows()[1]!.find('[data-testid="subagent-chip-expanded"]').exists()).toBe(false)

    await rows()[0]!.find('[data-testid="subagent-chip-toggle"]').trigger('click')
    expect(wrapper.find('[data-testid="slot-probe"]').exists()).toBe(false)
    expect(probe.unmounts).toBe(1)
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
})
