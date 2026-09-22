<script setup lang="ts">
import type { ScrapeJob } from '~/types/api'
import { scrapeProgress, scrapeSite } from '~/utils/scrape-job'

const props = defineProps<{ job: Pick<ScrapeJob, 'url' | 'pagesRead' | 'pagesDiscovered' | 'options'> }>()

const progress = computed(() => scrapeProgress(props.job))
const percent = computed(() => Math.round((progress.value.done / progress.value.total) * 100))
</script>

<template>
  <div class="flex items-center gap-2 min-w-32">
    <div
      role="progressbar"
      :aria-label="`Pages read from ${scrapeSite(job.url)}`"
      aria-valuemin="0"
      :aria-valuemax="progress.total"
      :aria-valuenow="progress.done"
      :aria-valuetext="`${progress.done} of ${progress.total} pages`"
      class="h-1.5 flex-1 bg-muted rounded-full overflow-hidden"
    >
      <div
        class="h-full bg-blue-600 dark:bg-blue-400 transition-[width]"
        :style="{ width: `${percent}%` }"
      />
    </div>
    <span class="text-xs text-fg-muted tabular-nums whitespace-nowrap">{{ progress.done }} / {{ progress.total }}</span>
  </div>
</template>
