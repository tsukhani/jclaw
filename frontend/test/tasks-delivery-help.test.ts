import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Tasks from '~/pages/tasks.vue'

/**
 * JCLAW-457: the delivery editor carries an always-present, collapsible "What can I put
 * here?" helper below the input — documenting the delivery grammar (Slack example first)
 * and the Slack private-channel rule. Collapsed by default to conserve space.
 */

function taskRow() {
  return {
    id: 1,
    name: 'daily-briefing',
    type: 'CRON',
    status: 'ACTIVE',
    paused: false,
    description: 'brief me',
    agentName: 'main',
    nextRunAt: null,
    retryCount: 0,
    maxRetries: 3,
    runningRunId: null,
    delivery: 'slack:daily-briefings',
  }
}

registerEndpoint('/api/tasks', () => [taskRow()])
registerEndpoint('/api/tasks/stats', () => ({
  runsToday: 0, successRate: null, avgDurationMs: null,
  pendingCount: 1, runningCount: 0, failedCount: 0,
}))
registerEndpoint('/api/task-runs/recent', () => [])
registerEndpoint('/api/tasks/1/runs', () => [])
registerEndpoint('/api/tasks/1/delivery-advisory', () => ({ advisory: null }))

describe('Tasks page — delivery editor grammar helper (JCLAW-457)', () => {
  beforeEach(() => clearNuxtData())

  it('renders a collapsible helper below the edit input, closed by default', async () => {
    const c = await mountSuspended(Tasks)
    await flushPromises()
    await c.find('button[aria-label="Toggle details for daily-briefing"]').trigger('click')
    await flushPromises()
    const editBtn = c.findAll('button').find(b => b.text() === 'Edit'
      && b.element.closest('section')?.textContent?.includes('Channel'))
    await editBtn!.trigger('click')
    await flushPromises()

    const details = c.find('details')
    expect(details.exists()).toBe(true)
    expect(details.find('summary').text()).toContain('What can I put here?')
    // Collapsed by default (no `open` attribute) to conserve space.
    expect((details.element as HTMLDetailsElement).open).toBe(false)
  })

  it('documents the Slack grammar and the private-channel rule when expanded', async () => {
    const c = await mountSuspended(Tasks)
    await flushPromises()
    await c.find('button[aria-label="Toggle details for daily-briefing"]').trigger('click')
    await flushPromises()
    const editBtn = c.findAll('button').find(b => b.text() === 'Edit'
      && b.element.closest('section')?.textContent?.includes('Channel'))
    await editBtn!.trigger('click')
    await flushPromises()

    const text = c.find('details').text()
    expect(text).toContain('slack:#daily-briefings')
    expect(text).toContain('tool:send_gmail_message')
    expect(text).toContain('none')
    expect(text.toLowerCase()).toContain('invited')
    expect(text.toLowerCase()).toContain('user-token')
  })
})
