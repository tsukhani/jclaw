import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Tasks from '~/pages/tasks.vue'

/**
 * JCLAW-455: the Tasks page shows a preflight Slack delivery-reachability advisory
 * directly below the delivery value when the bot can't reach the channel. It's fetched
 * lazily from GET /api/tasks/{id}/delivery-advisory on row-expand (not in the list
 * payload), so it must not appear until the row is opened.
 */

const ADVISORY = 'Can\'t find Slack channel #daily-briefings. If it\'s a private channel, '
  + 'invite the bot to it; if it\'s public, check the name (or grant the bot the chat:write.public scope).'

let advisoryResponse: { advisory: string | null } = { advisory: null }

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
registerEndpoint('/api/tasks/1/delivery-advisory', () => advisoryResponse)

const toggle = (c: Awaited<ReturnType<typeof mountSuspended>>) =>
  c.find('button[aria-label="Toggle details for daily-briefing"]')

describe('Tasks page — Slack delivery reachability advisory', () => {
  beforeEach(() => {
    clearNuxtData()
    advisoryResponse = { advisory: null }
  })

  it('renders the advisory below the delivery value once the row is expanded', async () => {
    advisoryResponse = { advisory: ADVISORY }
    const component = await mountSuspended(Tasks)
    await flushPromises()

    // Lazy: nothing fetched until expand.
    expect(component.text()).not.toContain('invite the bot')

    await toggle(component).trigger('click')
    await flushPromises()

    expect(component.text()).toContain('invite the bot')
  })

  it('renders no advisory when the channel is reachable (advisory null)', async () => {
    advisoryResponse = { advisory: null }
    const component = await mountSuspended(Tasks)
    await flushPromises()

    await toggle(component).trigger('click')
    await flushPromises()

    expect(component.text()).not.toContain('invite the bot')
  })
})
