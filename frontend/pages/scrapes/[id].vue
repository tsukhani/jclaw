<script setup lang="ts">
import { ArrowDownTrayIcon, ArrowTopRightOnSquareIcon } from '@heroicons/vue/24/outline'
import type { ApiErrorDetails, ScrapeJob, ScrapeJobPage, ScrapeJobPageContent } from '~/types/api'
import { formatRuntime, isActive, isEnded, scrapeSite } from '~/utils/scrape-job'
import ScrapeJobActions from '~/components/scrapes/ScrapeJobActions.vue'
import ScrapeJobProgress from '~/components/scrapes/ScrapeJobProgress.vue'
import ScrapeJobStateBadge from '~/components/scrapes/ScrapeJobStateBadge.vue'
import ScrapePageViewer from '~/components/scrapes/ScrapePageViewer.vue'

/**
 * JCLAW-1273: one background scrape job — its state and options, its pages in the order it read
 * them, and the content of the one selected. While the job runs its pages arrive by cursor: each
 * poll asks only for pages after the last one received.
 */

const POLL_MS = 3000
/** Pages asked for per request, so a long job's list arrives in pieces rather than in one reply. */
const PAGE_BATCH = 200

const route = useRoute()
const id = Number(route.params.id)

const job = ref<ScrapeJob | null>(null)
const jobError = ref<ApiErrorDetails | null>(null)

async function loadJob() {
  try {
    job.value = await $fetch<ScrapeJob>(`/api/scrape-jobs/${id}`)
    jobError.value = null
  }
  catch (e) {
    jobError.value = apiErrorDetails(e)
  }
}

const pages = ref<ScrapeJobPage[]>([])
const pagesError = ref<ApiErrorDetails | null>(null)
let loadingPages = false

/** Everything after the last page received, a batch at a time. */
async function loadNewPages() {
  if (loadingPages) return
  loadingPages = true
  try {
    for (;;) {
      const after = pages.value.at(-1)?.index ?? 0
      const batch = await $fetch<ScrapeJobPage[]>(`/api/scrape-jobs/${id}/pages`, {
        query: { after, limit: PAGE_BATCH },
      })
      pages.value = [...pages.value, ...batch]
      if (batch.length < PAGE_BATCH) break
    }
    pagesError.value = null
  }
  catch (e) {
    pagesError.value = apiErrorDetails(e)
  }
  finally {
    loadingPages = false
  }
}

await loadJob()
await loadNewPages()

const breadcrumbExtra = useBreadcrumbExtra()
watch(job, (current) => {
  breadcrumbExtra.value = current ? scrapeSite(current.url) : null
}, { immediate: true })

let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  timer = setInterval(async () => {
    if (!job.value || !isActive(job.value.state) || document.hidden) return
    await loadJob()
    // Once more after the job stops, for the pages it read on its way out.
    await loadNewPages()
  }, POLL_MS)
})
onUnmounted(() => {
  clearInterval(timer)
  breadcrumbExtra.value = null
})

const selectedId = ref<number | null>(null)
const content = ref<ScrapeJobPageContent | null>(null)
const contentError = ref<ApiErrorDetails | null>(null)
const contentLoading = ref(false)
const contentLoads = useLatestRequest()

async function select(page: ScrapeJobPage) {
  if (!page.hasContent) return
  selectedId.value = page.id
  const request = contentLoads.begin()
  contentLoading.value = true
  try {
    const loaded = await $fetch<ScrapeJobPageContent>(`/api/scrape-jobs/${id}/pages/${page.id}/content`)
    // A slow page can answer after the operator has picked another.
    if (!contentLoads.isCurrent(request)) return
    content.value = loaded
    contentError.value = null
  }
  catch (e) {
    if (contentLoads.isCurrent(request)) {
      content.value = null
      contentError.value = apiErrorDetails(e)
    }
  }
  finally {
    if (contentLoads.isCurrent(request)) contentLoading.value = false
  }
}

const { pause, resume, stop, remove, actionError, busy } = useScrapeJobActions(async (updated) => {
  if (!updated) {
    await navigateTo('/scrapes')
    return
  }
  job.value = updated
  await loadNewPages()
})

const OUTCOME_LABEL: Record<ScrapeJobPage['outcome'], string> = {
  FETCHED: 'Read',
  BLOCKED: 'Blocked',
  FAILED: 'Not read',
}

function describeSize(chars: number): string {
  return chars >= 1000 ? `${(chars / 1000).toFixed(1)}k chars` : `${chars} chars`
}

const extractFields = computed(() => Object.entries(job.value?.options.extract ?? {}))
</script>

<template>
  <div>
    <ApiErrorAlert
      :error="jobError"
      headline="Could not load this scrape"
      :retry="loadJob"
      class="mb-4"
    />
    <template v-if="job">
      <div class="flex flex-wrap items-start justify-between gap-3 mb-4">
        <div class="min-w-0">
          <h1 class="text-lg font-semibold text-fg-strong truncate">
            {{ scrapeSite(job.url) }}
          </h1>
          <a
            :href="job.url"
            target="_blank"
            rel="noopener noreferrer nofollow"
            class="inline-flex items-center gap-1 text-xs text-fg-muted hover:text-fg-strong break-all"
          >{{ job.url }}<ArrowTopRightOnSquareIcon
            class="w-3 h-3 shrink-0"
            aria-hidden="true"
          /><span class="sr-only">(opens the site in a new tab)</span></a>
        </div>
        <div class="flex items-center gap-2">
          <a
            v-if="job.combinedFile"
            :href="`/api/scrape-jobs/${job.id}/download`"
            download
            class="inline-flex items-center gap-1 px-2 py-1 text-xs border border-border text-fg-primary hover:bg-muted"
          >
            <ArrowDownTrayIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />Download all pages
          </a>
          <ScrapeJobActions
            :job="job"
            :busy="busy === job.id"
            @pause="pause"
            @resume="resume"
            @stop="stop"
            @delete="remove"
          />
        </div>
      </div>

      <ApiErrorAlert
        :error="actionError"
        class="mb-4"
      />

      <div
        class="bg-surface-elevated border border-border p-4 mb-4 text-sm"
        data-testid="scrape-job-header"
      >
        <div class="flex flex-wrap items-center gap-3 mb-3">
          <ScrapeJobStateBadge :state="job.state" />
          <ScrapeJobProgress
            :job="job"
            class="flex-1 max-w-md"
          />
        </div>
        <p
          v-if="job.state === 'FAILED' && job.errorMessage"
          class="text-danger mb-2"
        >
          {{ job.errorMessage }}
        </p>
        <p
          v-else-if="job.state === 'INTERRUPTED'"
          class="text-fg-primary mb-2"
        >
          Restarts of JClaw stopped this scrape {{ job.interruptions }} times while it ran, so it now waits to be resumed.
          Resuming continues from the {{ job.pagesRead }} pages it has.
        </p>
        <p
          v-else-if="job.state === 'PAUSED'"
          class="text-fg-primary mb-2"
        >
          Paused. Resuming continues from the {{ job.pagesRead }} pages it has.
        </p>
        <p
          v-else-if="isEnded(job.state) && job.stopReason"
          class="text-fg-muted mb-2"
        >
          Stopped: {{ job.stopReason }}.
        </p>
        <p
          v-if="job.interruptions > 0 && job.state !== 'INTERRUPTED'"
          class="text-fg-muted mb-2"
        >
          It continued on its own after JClaw restarted {{ job.interruptions === 1 ? 'once' : `${job.interruptions} times` }}.
        </p>
        <dl class="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-xs">
          <dt class="text-fg-muted">
            Agent
          </dt>
          <dd class="text-fg-primary">
            {{ job.agentName }}
          </dd>
          <dt class="text-fg-muted">
            Pages
          </dt>
          <dd class="text-fg-primary">
            {{ job.pagesFetched }} read of {{ job.pagesRead }} tried, {{ job.pagesDiscovered }} found
          </dd>
          <dt class="text-fg-muted">
            Running time
          </dt>
          <dd class="text-fg-primary">
            {{ formatRuntime(job.runtimeSeconds) }} of {{ job.options.maxMinutes }} min
          </dd>
          <dt class="text-fg-muted">
            Limits
          </dt>
          <dd class="text-fg-primary">
            {{ job.options.maxPages }} pages, {{ job.options.maxDepth }} links deep,
            {{ job.options.sameHostOnly ? 'same host only' : 'any host' }}
          </dd>
          <dt class="text-fg-muted">
            Output
          </dt>
          <dd class="text-fg-primary">
            {{ job.options.format }}{{ job.options.metadata ? ', with metadata' : '' }},
            language {{ job.options.language }},
            {{ job.options.respectRobots ? 'honouring robots.txt' : 'ignoring robots.txt' }}{{ job.options.seedFromSitemap ? ', seeded from sitemaps' : '' }}
          </dd>
          <template v-if="extractFields.length">
            <dt class="text-fg-muted">
              Fields
            </dt>
            <dd class="text-fg-primary font-mono break-all">
              <span
                v-for="([name, selector], i) in extractFields"
                :key="name"
              >{{ name }}: {{ selector }}{{ i < extractFields.length - 1 ? '; ' : '' }}</span>
            </dd>
          </template>
          <dt class="text-fg-muted">
            Folder
          </dt>
          <dd class="text-fg-primary font-mono">
            {{ job.folder }}
          </dd>
          <dt class="text-fg-muted">
            Queued
          </dt>
          <dd class="text-fg-primary">
            {{ new Date(job.createdAt).toLocaleString() }}{{ job.completedAt ? ` · ended ${new Date(job.completedAt).toLocaleString()}` : '' }}
          </dd>
        </dl>
        <details
          v-if="job.summary"
          class="mt-3"
        >
          <summary class="text-xs text-fg-muted cursor-pointer">
            Crawl summary
          </summary>
          <pre class="mt-2 text-xs whitespace-pre-wrap text-fg-primary">{{ job.summary }}</pre>
        </details>
      </div>

      <ApiErrorAlert
        :error="pagesError"
        headline="Could not load the pages"
        :retry="loadNewPages"
        class="mb-4"
      />

      <div class="grid gap-4 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
        <section
          class="bg-surface-elevated border border-border min-w-0"
          aria-labelledby="scrape-pages-heading"
        >
          <h2
            id="scrape-pages-heading"
            class="px-4 py-2.5 text-sm font-medium text-fg-primary border-b border-border"
          >
            Pages <span class="text-fg-muted font-normal">({{ pages.length }})</span>
          </h2>
          <p
            v-if="!pages.length"
            class="px-4 py-6 text-sm text-fg-muted"
          >
            {{ isActive(job.state) ? 'No page has been read yet.' : 'This scrape read no pages.' }}
          </p>
          <ol
            v-else
            class="divide-y divide-border max-h-[70vh] overflow-y-auto"
            data-testid="scrape-pages"
          >
            <li
              v-for="page in pages"
              :key="page.id"
            >
              <button
                type="button"
                class="w-full text-left px-4 py-2 flex items-start gap-3 hover:bg-muted/40 disabled:cursor-default disabled:hover:bg-transparent"
                :class="selectedId === page.id ? 'bg-muted' : ''"
                :aria-pressed="selectedId === page.id"
                :disabled="!page.hasContent"
                :data-testid="`scrape-page-${page.index}`"
                @click="select(page)"
              >
                <span class="text-xs text-fg-muted tabular-nums w-8 shrink-0">{{ page.index }}</span>
                <span class="min-w-0 flex-1">
                  <span class="block truncate text-fg-primary">{{ page.url }}</span>
                  <span class="block text-xs text-fg-muted">
                    {{ OUTCOME_LABEL[page.outcome] }}{{ page.reason ? ` (${page.reason})` : '' }}
                    · {{ page.servedBy.toLowerCase() }}
                    <template v-if="page.chars"> · {{ describeSize(page.chars) }}</template>
                  </span>
                </span>
              </button>
            </li>
          </ol>
        </section>
        <section
          class="bg-surface-elevated border border-border min-w-0 p-4"
          aria-label="Page content"
          aria-live="polite"
          :aria-busy="contentLoading"
        >
          <ApiErrorAlert
            :error="contentError"
            headline="Could not load this page"
          />
          <template v-if="content">
            <p class="text-xs text-fg-muted mb-3 break-all">
              {{ content.url }}
            </p>
            <ScrapePageViewer :page="content" />
          </template>
          <p
            v-else-if="!contentError"
            class="text-sm text-fg-muted"
          >
            Select a page to read what the scrape kept of it.
          </p>
        </section>
      </div>
    </template>
  </div>
</template>
