import { describe, it, expect } from 'vitest'
import { mountSuspended } from '@nuxt/test-utils/runtime'
import ChatChecklist from '~/components/chat/ChatChecklist.vue'
import type { ChecklistItem } from '~/types/api'

const ITEMS: ChecklistItem[] = [
  { content: 'Run tests', status: 'completed', activeForm: 'Running tests' },
  { content: 'Fix the parser', status: 'in_progress', activeForm: 'Fixing the parser' },
  { content: 'Commit', status: 'pending', activeForm: 'Committing' },
]

describe('ChatChecklist', () => {
  it('lists each step with its status while the turn runs, the one in progress spinning under its active form', async () => {
    const wrapper = await mountSuspended(ChatChecklist, { props: { items: ITEMS, running: true } })

    expect(wrapper.get('[data-testid="chat-checklist-count"]').text()).toBe('1 of 3 done')
    const rows = wrapper.findAll('[data-testid="chat-checklist-item"]')
    expect(rows.map(r => r.attributes('data-status'))).toEqual(['completed', 'in_progress', 'pending'])
    expect(rows.map(r => r.text())).toEqual(['Run tests(done)', 'Fixing the parser(in progress)', 'Commit(to do)'])
    expect(rows.map(r => r.find('.animate-spin').exists())).toEqual([false, true, false])
  })

  it('shows the step left in progress as unfinished once the turn has ended', async () => {
    const wrapper = await mountSuspended(ChatChecklist, { props: { items: ITEMS, running: false } })

    expect(wrapper.get('[data-testid="chat-checklist-count"]').text()).toBe('1 of 3 done')
    const rows = wrapper.findAll('[data-testid="chat-checklist-item"]')
    expect(rows.map(r => r.attributes('data-status'))).toEqual(['completed', 'in_progress', 'pending'])
    expect(rows.map(r => r.text())).toEqual(['Run tests(done)', 'Fix the parser(not finished)', 'Commit(to do)'])
    expect(wrapper.find('.animate-spin').exists()).toBe(false)
  })

  it('folds to its header and keeps the step in progress in view while the turn runs', async () => {
    const wrapper = await mountSuspended(ChatChecklist, { props: { items: ITEMS, running: true } })
    const toggle = wrapper.get('[data-testid="chat-checklist-toggle"]')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(toggle.attributes('aria-controls')).toBe('chat-checklist-items')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-testid="chat-checklist-item"]').exists()).toBe(false)
    expect(toggle.text()).toBe('Checklist1 of 3 done· Fixing the parser')
  })

  it('folds to the count alone once the turn has ended', async () => {
    const wrapper = await mountSuspended(ChatChecklist, { props: { items: ITEMS, running: false } })
    const toggle = wrapper.get('[data-testid="chat-checklist-toggle"]')

    await toggle.trigger('click')

    expect(toggle.text()).toBe('Checklist1 of 3 done')
  })
})
