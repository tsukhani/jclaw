import { describe, it, expect, beforeEach } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { clearNuxtData } from '#app'
import Reminders from '~/pages/reminders.vue'

// Two reminders: a one-shot SCHEDULED firing in ~3 hours (relative "When" +
// absolute "Schedule"), and a recurring CRON reminder (humanized cadence).
// nextRunAt is computed off Date.now() at module load so the relative label is
// deterministic to the hour.
const inThreeHours = new Date(Date.now() + 3 * 3_600_000).toISOString()

function reminderRows() {
  return [
    {
      id: 1,
      name: 'meeting-ibrahim',
      description: 'Reminder: Meeting with Ibrahim at 2:30 PM\nMaps: https://www.google.com/maps/dir/?api=1&destination=3.1547903,101.718559',
      type: 'SCHEDULED',
      status: 'PENDING',
      paused: false,
      autoDeleteOnComplete: true,
      agentName: 'main',
      nextRunAt: inThreeHours,
      effectiveTimezone: 'Asia/Kuala_Lumpur',
      scheduleDisplay: inThreeHours,
      delivery: null,
      lastFiredAt: null,
      retryCount: 0,
      maxRetries: 3,
      runningRunId: null,
    },
    {
      id: 2,
      name: 'weekly-standup',
      description: 'Reminder: Weekly standup',
      type: 'CRON',
      status: 'ACTIVE',
      paused: false,
      autoDeleteOnComplete: false,
      agentName: 'main',
      nextRunAt: inThreeHours,
      effectiveTimezone: 'Asia/Kuala_Lumpur',
      cronExpression: '0 0 17 * * 2',
      scheduleDisplay: '0 0 17 * * 2',
      delivery: null,
      lastFiredAt: null,
      retryCount: 0,
      maxRetries: 3,
      runningRunId: null,
    },
  ]
}

registerEndpoint('/api/tasks', () => reminderRows())
registerEndpoint('/api/tasks/stats', () => ({
  runsToday: 0, successRate: null, avgDurationMs: null, runningCount: 0,
  activeCount: 1, pendingCount: 1, failedCount: 0,
}))

beforeEach(() => {
  // useFetch caches by URL across mounts — clear so each test re-fetches.
  clearNuxtData()
})

describe('reminders page (JCLAW-438)', () => {
  it('Schedule column shows the countdown for a one-shot and cadence for recurring', async () => {
    const component = await mountSuspended(Reminders)
    const text = component.text()
    // One-shot fires in ~3 hours → countdown lives in the Schedule column now.
    expect(text).toContain('in 3 hours')
    // Recurring → human cadence, never the raw "0 0 17 * * 2".
    expect(text).toContain('weekly on Tue at 5 PM')
    expect(text).not.toContain('0 0 17 * * 2')
  })

  it('When column shows the absolute fire datetime with no timezone label', async () => {
    const component = await mountSuspended(Reminders)
    const text = component.text()
    // The When/Fired columns render an absolute datetime (date · time); the
    // configured zone is applied but NOT labelled (JCLAW-438 item 1).
    expect(text).toContain('·')
    expect(text).not.toContain('(Asia/Kuala_Lumpur)')
  })

  it('rows collapse to the kebab name and expand to the full reminder text', async () => {
    const component = await mountSuspended(Reminders)
    // Collapsed: the kebab name shows; the full reminder text is hidden.
    expect(component.text()).toContain('meeting-ibrahim')
    expect(component.text()).not.toContain('Meeting with Ibrahim at 2:30 PM')
    // Expanding the row reveals the verbatim reminder text.
    const toggle = component.findAll('button')
      .find(b => b.attributes('aria-label') === 'Toggle details for meeting-ibrahim')
    expect(toggle).toBeDefined()
    await toggle?.trigger('click')
    expect(component.text()).toContain('Meeting with Ibrahim at 2:30 PM')
  })

  it('renders bare URLs in the reminder body as clickable anchors', async () => {
    const component = await mountSuspended(Reminders)
    const toggle = component.findAll('button')
      .find(b => b.attributes('aria-label') === 'Toggle details for meeting-ibrahim')
    await toggle?.trigger('click')
    const link = component.findAll('a')
      .find(a => a.attributes('href')?.startsWith('https://www.google.com/maps'))
    expect(link).toBeDefined()
    expect(link?.attributes('target')).toBe('_blank')
    expect(link?.attributes('rel')).toBe('noopener noreferrer')
    // The query-string `&` round-trips through the href intact.
    expect(link?.attributes('href')).toContain('api=1&destination=3.1547903,101.718559')
  })

  it('Delete-all enters bulk-select mode with a select-all checkbox', async () => {
    const component = await mountSuspended(Reminders)
    const trash = component.findAll('button')
      .find(b => b.attributes('aria-label') === 'Delete reminders')
    expect(trash).toBeDefined()
    await trash?.trigger('click')
    // Select mode: a select-all checkbox + a Cancel button appear.
    expect(component.find('input[aria-label="Select all reminders"]').exists()).toBe(true)
    expect(component.findAll('button').some(b => b.text() === 'Cancel')).toBe(true)
  })

  it('renders the filter/search bar', async () => {
    const component = await mountSuspended(Reminders)
    const search = component.find('input[aria-label="Filter query"]')
    expect(search.exists()).toBe(true)
  })
})
