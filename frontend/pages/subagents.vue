<script setup lang="ts">
import type { Agent } from '~/types/api'

/**
 * JCLAW-271: SubagentRuns admin page. Lists every subagent run with filters
 * (parent agent, status, since), shows status pills + duration, and offers
 * a per-row kill button for RUNNING rows. The kill action POSTs to the
 * shared {@code /api/subagent-runs/{id}/kill} endpoint, which delegates to
 * the same SubagentRegistry kill primitive as the {@code /subagent kill}
 * slash command — so admin-page kills and slash kills behave identically.
 */

interface SubagentRun {
  id: number
  parentAgentId: number | null
  parentAgentName: string | null
  childAgentId: number | null
  childAgentName: string | null
  parentConversationId: number | null
  childConversationId: number | null
  mode: string | null
  status: 'RUNNING' | 'COMPLETED' | 'FAILED' | 'KILLED' | 'TIMEOUT'
  startedAt: string | null
  endedAt: string | null
  outcome: string | null
}

const parentAgentFilter = ref<string>('')
const statusFilter = ref<string>('')
const sinceFilter = ref<string>('')

const { data: agentList } = await useFetch<Agent[]>('/api/agents', { default: () => [] })

const url = computed(() => {
  const params = new URLSearchParams()
  if (parentAgentFilter.value) params.set('parentAgentId', parentAgentFilter.value)
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (sinceFilter.value) {
    // Datetime-local inputs return "YYYY-MM-DDTHH:MM"; the backend wants
    // ISO-8601 with a timezone. Append :00Z so the value parses as UTC —
    // matching the timestamps the backend renders in the table.
    params.set('since', `${sinceFilter.value}:00Z`)
  }
  params.set('limit', '200')
  return `/api/subagent-runs?${params}`
})

// Plain ref + $fetch (not useFetch) so each mount fetches fresh — the
// shared useFetch cache otherwise returned stale rows when the page was
// re-opened mid-session after a separate run had finished, and confused
// the test harness which mounts the page across `it` blocks without
// invalidating the cache.
const runs = ref<SubagentRun[]>([])
async function refresh() {
  runs.value = await $fetch<SubagentRun[]>(url.value)
}
await refresh()
watch(url, () => { refresh() })

// Auto-refresh every 5s when there is at least one RUNNING row — keeps the
// admin view live during an actual subagent fan-out without polling
// hammering the DB once everything is terminal.
const hasRunning = computed(() => runs.value?.some(r => r.status === 'RUNNING') ?? false)
let interval: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  interval = setInterval(() => {
    if (hasRunning.value && !document.hidden) refresh()
  }, 5000)
})
onUnmounted(() => clearInterval(interval))

const statusColors: Record<string, string> = {
  RUNNING: 'bg-blue-100 dark:bg-blue-400/10 text-blue-700 dark:text-blue-300 border-blue-300 dark:border-blue-400/20',
  COMPLETED: 'bg-emerald-100 dark:bg-emerald-400/10 text-emerald-700 dark:text-emerald-400 border-emerald-300 dark:border-emerald-400/20',
  FAILED: 'bg-red-100 dark:bg-red-400/10 text-red-700 dark:text-red-400 border-red-300 dark:border-red-400/20',
  KILLED: 'bg-yellow-100 dark:bg-yellow-400/10 text-yellow-700 dark:text-yellow-400 border-yellow-300 dark:border-yellow-400/20',
  TIMEOUT: 'bg-orange-100 dark:bg-orange-400/10 text-orange-700 dark:text-orange-400 border-orange-300 dark:border-orange-400/20',
}

function durationSeconds(r: SubagentRun): number | null {
  if (!r.startedAt) return null
  const start = new Date(r.startedAt).getTime()
  const end = r.endedAt ? new Date(r.endedAt).getTime() : Date.now()
  if (Number.isNaN(start) || Number.isNaN(end)) return null
  return Math.max(0, Math.round((end - start) / 1000))
}

const killing = ref<Set<number>>(new Set())

async function killRun(id: number) {
  killing.value.add(id)
  try {
    await $fetch(`/api/subagent-runs/${id}/kill`, {
      method: 'POST',
      body: { reason: 'Killed by operator via admin page' },
    })
    await refresh()
  }
  catch (e) {
    console.error('Failed to kill subagent run:', e)
  }
  finally {
    killing.value.delete(id)
  }
}

// A11y: stable ids for filter inputs.
const parentSelectId = useId()
const statusSelectId = useId()
const sinceInputId = useId()
</script>

<template>
  <div>
    <h1 class="text-lg font-semibold text-fg-strong mb-6">
      Subagent Runs
    </h1>

    <!-- Filter bar -->
    <div class="flex flex-wrap gap-3 mb-4">
      <label :for="parentSelectId">
        <span class="sr-only">Parent agent filter</span>
        <select
          :id="parentSelectId"
          v-model="parentAgentFilter"
          class="bg-muted border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden"
        >
          <option value="">
            All parent agents
          </option>
          <option
            v-for="a in agentList"
            :key="a.id"
            :value="a.id"
          >
            {{ a.name }}
          </option>
        </select>
      </label>
      <label :for="statusSelectId">
        <span class="sr-only">Status filter</span>
        <select
          :id="statusSelectId"
          v-model="statusFilter"
          class="bg-muted border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden"
        >
          <option value="">
            All statuses
          </option>
          <option
            v-for="s in ['RUNNING', 'COMPLETED', 'FAILED', 'KILLED', 'TIMEOUT']"
            :key="s"
            :value="s"
          >
            {{ s }}
          </option>
        </select>
      </label>
      <label :for="sinceInputId">
        <span class="sr-only">Started after</span>
        <input
          :id="sinceInputId"
          v-model="sinceFilter"
          type="datetime-local"
          class="bg-muted border border-input text-sm text-fg-strong px-2 py-1 focus:outline-hidden"
        >
      </label>
    </div>

    <!-- Table -->
    <div class="bg-surface-elevated border border-border">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th class="px-4 py-2.5 font-medium">
              ID
            </th>
            <th class="px-4 py-2.5 font-medium">
              Parent
            </th>
            <th class="px-4 py-2.5 font-medium">
              Child
            </th>
            <th class="px-4 py-2.5 font-medium">
              Mode
            </th>
            <th class="px-4 py-2.5 font-medium">
              Status
            </th>
            <th class="px-4 py-2.5 font-medium">
              Started
            </th>
            <th class="px-4 py-2.5 font-medium">
              Duration
            </th>
            <th class="px-4 py-2.5 font-medium text-right">
              Actions
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <tr
            v-for="run in runs"
            :key="run.id"
          >
            <td class="px-4 py-2.5 font-mono text-xs text-fg-muted">
              #{{ run.id }}
            </td>
            <td class="px-4 py-2.5 text-fg-primary">
              {{ run.parentAgentName || '—' }}
            </td>
            <td class="px-4 py-2.5 text-fg-primary">
              {{ run.childAgentName || '—' }}
            </td>
            <td class="px-4 py-2.5">
              <span
                v-if="run.mode"
                class="font-mono text-xs bg-muted px-1.5 py-0.5 text-fg-primary"
              >{{ run.mode }}</span>
              <span
                v-else
                class="text-fg-muted"
              >—</span>
            </td>
            <td class="px-4 py-2.5">
              <span
                :class="statusColors[run.status]"
                class="inline-flex items-center gap-1 text-[10px] font-mono uppercase tracking-wide px-1.5 py-0.5 border"
              >
                <span
                  v-if="run.status === 'RUNNING'"
                  class="w-1.5 h-1.5 rounded-full bg-blue-500 animate-pulse"
                  aria-hidden="true"
                />
                {{ run.status }}
              </span>
            </td>
            <td class="px-4 py-2.5 text-fg-muted text-xs">
              {{ run.startedAt ? new Date(run.startedAt).toLocaleString() : '—' }}
            </td>
            <td class="px-4 py-2.5 text-fg-muted text-xs font-mono">
              <template v-if="durationSeconds(run) !== null">
                {{ durationSeconds(run) }}s
              </template>
              <template v-else>
                —
              </template>
            </td>
            <td class="px-4 py-2.5 text-right">
              <button
                v-if="run.status === 'RUNNING'"
                :disabled="killing.has(run.id)"
                class="text-xs text-red-600 dark:text-red-400 hover:text-red-700 dark:hover:text-red-300 transition-colors disabled:opacity-40"
                @click="killRun(run.id)"
              >
                {{ killing.has(run.id) ? 'Killing...' : 'Kill' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
      <div
        v-if="!runs?.length"
        class="px-4 py-8 text-center text-sm text-fg-muted"
      >
        No subagent runs matching filters
      </div>
    </div>
  </div>
</template>
