<script setup lang="ts">
import { GlobeAltIcon, PlusIcon } from '@heroicons/vue/24/outline'
import type { Agent, ApiErrorDetails, ScrapeJob, ScrapeJobState } from '~/types/api'
import { SCRAPE_STATE_LABEL, SCRAPE_STATES, formatRuntime, isActive, scrapeSite } from '~/utils/scrape-job'
import ScrapeJobActions from '~/components/scrapes/ScrapeJobActions.vue'
import ScrapeJobProgress from '~/components/scrapes/ScrapeJobProgress.vue'
import ScrapeJobStateBadge from '~/components/scrapes/ScrapeJobStateBadge.vue'

/**
 * JCLAW-1273: every background scrape job, newest first, with its progress while it runs and the
 * actions its state allows. A job's pages and content are on its detail view, /scrapes/{id}.
 */

/** Often enough that a running job's count visibly climbs; only while one is running. */
const POLL_MS = 3000
const PAGE_SIZE = 25

const { data: agentList } = await useFetch<Agent[]>('/api/agents', { default: () => [] })

const agentFilter = ref('')
const stateFilter = ref<ScrapeJobState | ''>('')
const page = ref(1)

const url = computed(() => {
  const params = new URLSearchParams()
  if (agentFilter.value) params.set('agentId', agentFilter.value)
  if (stateFilter.value) params.set('state', stateFilter.value)
  params.set('limit', String(PAGE_SIZE))
  params.set('offset', String((page.value - 1) * PAGE_SIZE))
  return `/api/scrape-jobs?${params}`
})

const jobs = ref<ScrapeJob[]>([])
const total = ref(0)
const loading = ref(false)
const listError = ref<ApiErrorDetails | null>(null)
const listLoads = useLatestRequest()

async function refresh() {
  const request = listLoads.begin()
  loading.value = true
  try {
    const res = await $fetch.raw<ScrapeJob[]>(url.value)
    // A poll started under the previous filter can land after the request for the new one.
    if (!listLoads.isCurrent(request)) return
    jobs.value = res._data ?? []
    const headerTotal = res.headers.get('x-total-count')
    total.value = headerTotal ? Number.parseInt(headerTotal, 10) : jobs.value.length
    listError.value = null
  }
  catch (e) {
    if (listLoads.isCurrent(request)) listError.value = apiErrorDetails(e)
  }
  finally {
    if (listLoads.isCurrent(request)) loading.value = false
  }
}
await refresh()
watch([agentFilter, stateFilter], () => {
  page.value = 1
})
watch(url, () => refresh())

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / PAGE_SIZE)))

const hasActive = computed(() => jobs.value.some(job => isActive(job.state)))
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  timer = setInterval(() => {
    if (hasActive.value && !document.hidden) void refresh()
  }, POLL_MS)
})
onUnmounted(() => clearInterval(timer))

const { pause, resume, stop, remove, actionError, busy } = useScrapeJobActions(() => refresh())
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6 gap-3">
      <h1 class="text-lg font-semibold text-fg-strong">
        Scrapes
      </h1>
      <NuxtLink
        to="/scrapes/new"
        class="inline-flex items-center gap-1.5 px-3 py-1.5 bg-emerald-700 text-white text-xs font-medium hover:bg-emerald-800 transition-colors"
      >
        <PlusIcon
          class="w-4 h-4"
          aria-hidden="true"
        />New scrape
      </NuxtLink>
    </div>

    <ApiErrorAlert
      :error="listError"
      headline="Could not load scrape jobs"
      :retry="refresh"
      :retrying="loading"
      class="mb-4"
    />
    <ApiErrorAlert
      :error="actionError"
      class="mb-4"
    />

    <div class="flex flex-wrap items-end gap-3 mb-4">
      <label
        for="scrapes-agent-filter"
        class="flex flex-col gap-1 text-xs text-fg-muted"
      >
        Agent
        <select
          id="scrapes-agent-filter"
          v-model="agentFilter"
          class="bg-surface-elevated border border-input text-sm text-fg-primary px-2 py-1.5 min-w-40"
          data-testid="scrapes-agent-filter"
        >
          <option value="">All agents</option>
          <option
            v-for="agent in agentList"
            :key="agent.id"
            :value="String(agent.id)"
          >{{ agent.name }}</option>
        </select>
      </label>
      <label
        for="scrapes-state-filter"
        class="flex flex-col gap-1 text-xs text-fg-muted"
      >
        State
        <select
          id="scrapes-state-filter"
          v-model="stateFilter"
          class="bg-surface-elevated border border-input text-sm text-fg-primary px-2 py-1.5 min-w-40"
          data-testid="scrapes-state-filter"
        >
          <option value="">All states</option>
          <option
            v-for="state in SCRAPE_STATES"
            :key="state"
            :value="state"
          >{{ SCRAPE_STATE_LABEL[state] }}</option>
        </select>
      </label>
    </div>

    <div
      v-if="!jobs.length && !listError"
      class="rounded-lg border border-dashed border-zinc-300 bg-zinc-50 px-6 py-12 text-center dark:border-zinc-700 dark:bg-zinc-900/30"
      data-testid="scrapes-empty"
    >
      <GlobeAltIcon
        class="mx-auto h-10 w-10 text-zinc-400"
        aria-hidden="true"
      />
      <h2 class="mt-3 text-sm font-medium text-zinc-700 dark:text-zinc-300">
        {{ agentFilter || stateFilter ? 'No scrapes match these filters' : 'No scrapes yet' }}
      </h2>
      <p class="mt-1 text-sm text-zinc-600 dark:text-zinc-400">
        Start one with
        <NuxtLink
          to="/scrapes/new"
          class="font-medium text-emerald-700 underline underline-offset-2 hover:decoration-2 dark:text-emerald-400"
        >New scrape</NuxtLink>,
        or ask an agent to scrape a site in the background.
      </p>
    </div>

    <div
      v-else-if="jobs.length"
      class="bg-surface-elevated border border-border overflow-x-auto"
    >
      <table class="w-full text-sm">
        <caption class="sr-only">
          Background scrape jobs, newest first
        </caption>
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th
              scope="col"
              class="px-4 py-2.5 font-medium"
            >
              Site
            </th>
            <th
              scope="col"
              class="px-4 py-2.5 font-medium"
            >
              Agent
            </th>
            <th
              scope="col"
              class="px-4 py-2.5 font-medium"
            >
              State
            </th>
            <th
              scope="col"
              class="px-4 py-2.5 font-medium"
            >
              Pages
            </th>
            <th
              scope="col"
              class="px-4 py-2.5 font-medium"
            >
              Running time
            </th>
            <th
              scope="col"
              class="px-4 py-2.5 font-medium"
            >
              Queued
            </th>
            <th
              scope="col"
              class="px-4 py-2.5 font-medium text-right"
            >
              Actions
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <tr
            v-for="job in jobs"
            :key="job.id"
            class="hover:bg-muted/30 transition-colors"
            :data-testid="`scrape-row-${job.id}`"
          >
            <td class="px-4 py-2.5 max-w-80">
              <NuxtLink
                :to="`/scrapes/${job.id}`"
                class="block truncate text-fg-primary hover:underline"
                :title="job.url"
              >
                {{ scrapeSite(job.url) }}
              </NuxtLink>
            </td>
            <td class="px-4 py-2.5 text-fg-primary">
              {{ job.agentName }}
            </td>
            <td class="px-4 py-2.5">
              <ScrapeJobStateBadge :state="job.state" />
            </td>
            <td class="px-4 py-2.5">
              <ScrapeJobProgress :job="job" />
            </td>
            <td class="px-4 py-2.5 text-xs text-fg-muted tabular-nums whitespace-nowrap">
              {{ formatRuntime(job.runtimeSeconds) }}
            </td>
            <td class="px-4 py-2.5 text-xs text-fg-muted whitespace-nowrap">
              {{ new Date(job.createdAt).toLocaleString() }}
            </td>
            <td class="px-4 py-2.5 text-right">
              <ScrapeJobActions
                :job="job"
                :busy="busy === job.id"
                @pause="pause"
                @resume="resume"
                @stop="stop"
                @delete="remove"
              />
            </td>
          </tr>
        </tbody>
      </table>
      <div
        v-if="totalPages > 1"
        class="flex items-center justify-end gap-2 px-4 py-2 border-t border-border text-xs text-fg-muted"
      >
        <span>Page {{ page }} of {{ totalPages }}</span>
        <button
          type="button"
          class="px-2 py-1 border border-border hover:bg-muted disabled:opacity-40"
          :disabled="page <= 1 || loading"
          @click="page--"
        >
          Previous
        </button>
        <button
          type="button"
          class="px-2 py-1 border border-border hover:bg-muted disabled:opacity-40"
          :disabled="page >= totalPages || loading"
          @click="page++"
        >
          Next
        </button>
      </div>
    </div>
  </div>
</template>
