<script setup lang="ts">
import { PauseIcon, PlayIcon, StopIcon, TrashIcon } from '@heroicons/vue/24/outline'
import type { ScrapeJob } from '~/types/api'
import { canDelete, canPause, canResume, canStop, scrapeSite } from '~/utils/scrape-job'

/** The actions a job's state allows, as buttons; the page owns what each one does. */
defineProps<{ job: ScrapeJob, busy: boolean }>()
const emit = defineEmits<{
  (e: 'pause' | 'resume' | 'stop' | 'delete', job: ScrapeJob): void
}>()
</script>

<template>
  <div class="inline-flex items-center gap-1">
    <button
      v-if="canPause(job.state)"
      type="button"
      class="inline-flex items-center gap-1 px-2 py-1 text-xs border border-border text-fg-primary hover:bg-muted disabled:opacity-40"
      :disabled="busy"
      :aria-label="`Pause the scrape of ${scrapeSite(job.url)}`"
      @click="emit('pause', job)"
    >
      <PauseIcon
        class="w-3.5 h-3.5"
        aria-hidden="true"
      />Pause
    </button>
    <button
      v-if="canResume(job.state)"
      type="button"
      class="inline-flex items-center gap-1 px-2 py-1 text-xs border border-border text-fg-primary hover:bg-muted disabled:opacity-40"
      :disabled="busy"
      :aria-label="`Resume the scrape of ${scrapeSite(job.url)}`"
      @click="emit('resume', job)"
    >
      <PlayIcon
        class="w-3.5 h-3.5"
        aria-hidden="true"
      />Resume
    </button>
    <button
      v-if="canStop(job.state)"
      type="button"
      class="inline-flex items-center gap-1 px-2 py-1 text-xs border border-border text-fg-primary hover:bg-muted disabled:opacity-40"
      :disabled="busy"
      :aria-label="`Stop the scrape of ${scrapeSite(job.url)}`"
      @click="emit('stop', job)"
    >
      <StopIcon
        class="w-3.5 h-3.5"
        aria-hidden="true"
      />Stop
    </button>
    <button
      v-if="canDelete(job.state)"
      type="button"
      class="inline-flex items-center gap-1 px-2 py-1 text-xs border border-red-700 text-red-700 dark:text-red-400 hover:bg-red-700 hover:text-white disabled:opacity-40"
      :disabled="busy"
      :aria-label="`Delete the scrape of ${scrapeSite(job.url)}`"
      @click="emit('delete', job)"
    >
      <TrashIcon
        class="w-3.5 h-3.5"
        aria-hidden="true"
      />Delete
    </button>
  </div>
</template>
