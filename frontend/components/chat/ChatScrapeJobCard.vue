<script setup lang="ts">
import { GlobeAltIcon } from '@heroicons/vue/24/outline'
import type { ScrapeJob, ScrapeJobRef } from '~/types/api'
import { isActive, isEnded, scrapeSite } from '~/utils/scrape-job'
import { scrapeJobEndedKey } from '~/composables/useScrapeJobChat'
import ScrapeJobProgress from '~/components/scrapes/ScrapeJobProgress.vue'
import ScrapeJobStateBadge from '~/components/scrapes/ScrapeJobStateBadge.vue'

/**
 * JCLAW-1273: the background scrape job a {@code web_scrape} call started, on the message that made
 * the call. Reads the job when it mounts, so a reload picks up where the job is, and polls only while
 * the job is queued or running.
 */
const POLL_MS = 3000

const props = defineProps<{ jobRef: ScrapeJobRef }>()
const jobEnded = inject(scrapeJobEndedKey, () => {})

const job = ref<ScrapeJob | null>(null)
const deleted = ref(false)

async function load() {
  try {
    const was = job.value?.state
    job.value = await $fetch<ScrapeJob>(`/api/scrape-jobs/${props.jobRef.id}`)
    // Watched ending, not found ended: only then is a completion message still on its way.
    if (was && isActive(was) && isEnded(job.value.state)) jobEnded()
  }
  catch (e) {
    if (apiErrorDetails(e).status === 404) deleted.value = true
  }
}

let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  void load()
  timer = setInterval(() => {
    if (job.value && isActive(job.value.state) && !document.hidden) void load()
  }, POLL_MS)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div
    class="mb-3 border border-neutral-200 dark:border-neutral-700 rounded-xl bg-surface-elevated px-3 py-2 flex flex-wrap items-center gap-3"
    data-testid="chat-scrape-job-card"
  >
    <GlobeAltIcon
      class="w-4 h-4 shrink-0 text-fg-muted"
      aria-hidden="true"
    />
    <div class="min-w-0 flex-1">
      <p class="text-sm text-fg-primary truncate">
        {{ job && isEnded(job.state) ? 'Scraped' : 'Scraping' }} {{ scrapeSite(jobRef.url) }}
      </p>
      <ScrapeJobProgress
        v-if="job"
        :job="job"
        class="max-w-xs"
      />
      <p
        v-else-if="deleted"
        class="text-xs text-fg-muted"
      >
        This scrape was deleted.
      </p>
    </div>
    <ScrapeJobStateBadge
      v-if="job"
      :state="job.state"
    />
    <NuxtLink
      v-if="!deleted"
      :to="`/scrapes/${jobRef.id}`"
      class="text-xs text-emerald-700 dark:text-emerald-400 hover:underline"
    >
      Open scrape<span class="sr-only"> of {{ scrapeSite(jobRef.url) }}</span>
    </NuxtLink>
  </div>
</template>
