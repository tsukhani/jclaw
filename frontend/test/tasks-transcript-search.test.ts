import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import Tasks from '~/pages/tasks.vue'

/**
 * Tasks page: a transcript-search hit shows the snippet around the match, and opens the run's
 * whole transcript from the run's own read (JF-141).
 */

const SNIPPET = '…sent the zebra invoice to accounts…'
const FULL_TEXT = 'Morning summary. I sent the zebra invoice to accounts and filed the receipt.'

describe('Tasks page — transcript search', () => {
  beforeEach(() => clearNuxtData())

  it('shows each hit\'s snippet and opens its run\'s transcript', async () => {
    registerEndpoint('/api/tasks', () => [{
      id: 1, name: 'alpha', type: 'SCHEDULED', status: 'PENDING', paused: false, description: 'do the thing',
      agentName: 'main', nextRunAt: null, retryCount: 0, maxRetries: 3, runningRunId: null, delivery: null,
    }])
    registerEndpoint('/api/tasks/stats', () => ({
      runsToday: 1, successRate: 1, avgDurationMs: 1000, pendingCount: 1, runningCount: 0, failedCount: 0,
    }))
    registerEndpoint('/api/task-runs/recent', () => [])
    registerEndpoint('/api/subagent-runs', () => [])
    let searchQuery: Record<string, unknown> = {}
    registerEndpoint('/api/task-runs/search', async (event) => {
      const { getQuery } = await import('h3')
      searchQuery = getQuery(event)
      return [{
        messageId: 901, role: 'assistant', snippet: SNIPPET, createdAt: '2026-10-13T06:00:05Z',
        taskRunId: 77, taskId: 1, taskName: 'alpha', agentId: 1, agentName: 'main',
      }]
    })
    let transcriptReads = 0
    registerEndpoint('/api/task-runs/77/messages', () => {
      transcriptReads++
      return [{
        id: 901, turnIndex: 1, role: 'assistant', content: FULL_TEXT, reasoning: null, toolCalls: null,
        toolResults: null, truncated: false, createdAt: '2026-10-13T06:00:05Z',
      }]
    })
    const component = await mountSuspended(Tasks)
    await flushPromises()

    const bar = component.find('input[placeholder^="Filter..."]')
    await bar.setValue('transcript:zebra')
    await bar.trigger('keydown', { key: 'Enter' })
    await vi.waitFor(() => expect(searchQuery).toEqual({ q: 'zebra', limit: '50' }))
    await flushPromises()

    const hit = component.findAll('button').find(b => b.text().includes(SNIPPET))
    expect(hit?.find('p').text()).toBe(SNIPPET)
    expect(component.text()).not.toContain(FULL_TEXT)
    expect(transcriptReads).toBe(0)

    await hit!.trigger('click')
    await vi.waitFor(() => {
      const panel = document.body.querySelector('[data-slot="sheet-content"]')?.textContent ?? ''
      expect(panel).toContain('Trace — alpha')
      expect(panel).toContain(FULL_TEXT)
    })
    expect(transcriptReads).toBe(1)
  })
})
