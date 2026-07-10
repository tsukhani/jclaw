import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import type { Agent } from '~/types/api'
import ChatAgentSelector from '~/components/chat/ChatAgentSelector.vue'

const AGENTS = [
  { id: 1, name: 'Main' },
  { id: 2, name: 'Research' },
] as unknown as Agent[]

describe('ChatAgentSelector', () => {
  it('renders a select with an option per agent when there is more than one', async () => {
    const w = await mountSuspended(ChatAgentSelector, { props: { agents: AGENTS, modelValue: 1 } })
    expect(w.find('select').exists()).toBe(true)
    const options = w.findAll('option')
    expect(options).toHaveLength(2)
    expect(options.map(o => o.text())).toEqual(['Main', 'Research'])
    expect((w.find('select').element as HTMLSelectElement).value).toBe('1')
  })

  it('emits update:modelValue with the numeric agent id on change', async () => {
    const w = await mountSuspended(ChatAgentSelector, { props: { agents: AGENTS, modelValue: 1 } })
    await w.find('select').setValue('2')
    expect(w.emitted('update:modelValue')?.[0]).toEqual([2])
  })

  it('renders static text (no select) in the single-agent case', async () => {
    const w = await mountSuspended(ChatAgentSelector, {
      props: { agents: [{ id: 1, name: 'Solo' }] as unknown as Agent[], modelValue: 1 },
    })
    expect(w.find('select').exists()).toBe(false)
    expect(w.text()).toContain('Solo')
  })

  it('renders nothing selectable when there are no agents', async () => {
    const w = await mountSuspended(ChatAgentSelector, { props: { agents: [], modelValue: null } })
    expect(w.find('select').exists()).toBe(false)
    expect(w.text()).not.toContain('Agent:')
  })
})
