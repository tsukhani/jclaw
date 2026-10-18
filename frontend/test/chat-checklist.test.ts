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
  it('lists each step with its status, the one in progress by its active form', async () => {
    const wrapper = await mountSuspended(ChatChecklist, { props: { items: ITEMS } })

    expect(wrapper.get('[data-testid="chat-checklist-count"]').text()).toBe('1 of 3 done')
    const rows = wrapper.findAll('[data-testid="chat-checklist-item"]')
    expect(rows.map(r => r.attributes('data-status'))).toEqual(['completed', 'in_progress', 'pending'])
    const expected = [['Run tests', '(done)'], ['Fixing the parser', '(in progress)'], ['Commit', '(to do)']]
    rows.forEach((row, i) => {
      for (const part of expected[i]!) expect(row.text()).toContain(part)
    })
    expect(rows[1]!.text()).not.toContain('Fix the parser')
  })

  it('folds to its header and keeps the step in progress in view', async () => {
    const wrapper = await mountSuspended(ChatChecklist, { props: { items: ITEMS } })
    const toggle = wrapper.get('[data-testid="chat-checklist-toggle"]')
    expect(toggle.attributes('aria-expanded')).toBe('true')
    expect(toggle.attributes('aria-controls')).toBe('chat-checklist-items')

    await toggle.trigger('click')

    expect(toggle.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[data-testid="chat-checklist-item"]').exists()).toBe(false)
    expect(toggle.text()).toContain('Fixing the parser')
  })
})
