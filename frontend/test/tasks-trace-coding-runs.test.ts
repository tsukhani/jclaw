import { describe, it, expect, beforeEach, vi } from 'vitest'
import { mountSuspended, registerEndpoint } from '@nuxt/test-utils/runtime'
import { flushPromises } from '@vue/test-utils'
import { clearNuxtData } from '#app'
import { defineComponent, h } from 'vue'
import Tasks from '~/pages/tasks.vue'

/**
 * Tasks page: a task run's trace panel shows the coding runs that run started, so a coding run a
 * scheduled task began is found from the task run and not from whichever chat was open last.
 */

describe('Tasks page — coding runs in a task run\'s trace', () => {
  beforeEach(() => clearNuxtData())

  it('asks for the coding runs of the opened task run and shows each one\'s log', async () => {
    registerEndpoint('/api/tasks', () => [{
      id: 1, name: 'alpha', type: 'SCHEDULED', status: 'PENDING', paused: false, description: 'do the thing',
      agentName: 'main', nextRunAt: null, retryCount: 0, maxRetries: 3, runningRunId: null, delivery: null,
    }])
    registerEndpoint('/api/tasks/stats', () => ({
      runsToday: 1, successRate: 1, avgDurationMs: 1000, pendingCount: 1, runningCount: 0, failedCount: 0,
    }))
    registerEndpoint('/api/task-runs/recent', () => [])
    registerEndpoint('/api/tasks/1/runs', () => [{
      id: 77, status: 'COMPLETED', startedAt: '2026-10-13T06:00:00Z', completedAt: '2026-10-13T06:00:20Z',
      durationMs: 20000, error: null, outputSummary: 'built', latestTurnPreview: null, deliveryStatus: null,
      deliveryTarget: null, deliveryError: null, traceJson: null, createdAt: '2026-10-13T06:00:00Z',
    }])
    registerEndpoint('/api/task-runs/77/messages', () => [])
    let query: Record<string, unknown> = {}
    registerEndpoint('/api/subagent-runs', async (event) => {
      const { getQuery } = await import('h3')
      query = getQuery(event)
      return [{
        id: 501, parentAgentId: 1, parentAgentName: 'main', childAgentId: 2, childAgentName: 'main-sub-a',
        parentConversationId: null, parentTaskRunId: 77, parentTaskId: 1, parentTaskName: 'alpha',
        childConversationId: 9, label: null, mode: 'session', status: 'COMPLETED',
        startedAt: '2026-10-13T06:00:02Z', endedAt: '2026-10-13T06:00:18Z', outcome: 'done',
        workdir: '/Users/op/coding/demo-project',
      }]
    })
    const monitor = defineComponent({
      props: { runId: { type: Number, required: true }, recordedStatus: String },
      setup: props => () => h('div', `log of run ${props.runId} (${props.recordedStatus})`),
    })
    const component = await mountSuspended(Tasks, { global: { stubs: { CodingRunMonitor: monitor } } })
    await flushPromises()

    await component.find('button[aria-label="Toggle details for alpha"]').trigger('click')
    await flushPromises()
    await component.find('button[aria-label="View execution trace for this run"]').trigger('click')
    // The stubbed endpoint answers a tick after the click's own promises settle.
    await vi.waitFor(() => expect(query).toMatchObject({ coding: 'true', parentTaskRunId: '77' }))
    await vi.waitFor(() => {
      const panel = document.body.querySelector('[data-slot="sheet-content"]')?.textContent ?? ''
      expect(panel).toContain('Coding runs')
      expect(panel).toContain('log of run 501 (COMPLETED)')
    })
  })
})
