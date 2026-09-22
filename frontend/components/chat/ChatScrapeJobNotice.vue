<script setup lang="ts">
import { GlobeAltIcon } from '@heroicons/vue/24/outline'
import type { Message, ScrapeJobState } from '~/types/api'
import { SCRAPE_STATE_LABEL, scrapeSite } from '~/utils/scrape-job'
import ScrapeJobStateBadge from '~/components/scrapes/ScrapeJobStateBadge.vue'

/**
 * JCLAW-1273: the message that tells a conversation its background scrape ended. It is a USER-role row
 * so the model reads it, but the operator did not write it, so it renders as a notice rather than as
 * one of their messages.
 */
const props = defineProps<{ msg: Message }>()

interface CompletionMetadata { jobId?: number, state?: ScrapeJobState, url?: string, pagesRead?: number, pagesFetched?: number }
const meta = computed(() => (props.msg.metadata ?? {}) as CompletionMetadata)
</script>

<template>
  <div
    class="flex justify-start"
    data-testid="chat-scrape-job-notice"
  >
    <div class="max-w-[85%] w-full min-w-0 border border-neutral-200 dark:border-neutral-700 rounded-xl bg-surface-elevated px-3 py-2 flex flex-wrap items-center gap-3">
      <GlobeAltIcon
        class="w-4 h-4 shrink-0 text-fg-muted"
        aria-hidden="true"
      />
      <p class="min-w-0 flex-1 text-sm text-fg-primary">
        Background scrape {{ meta.state ? SCRAPE_STATE_LABEL[meta.state].toLowerCase() : 'ended' }}:
        {{ meta.url ? scrapeSite(meta.url) : '' }}
        <span class="text-fg-muted">· {{ meta.pagesFetched ?? 0 }} of {{ meta.pagesRead ?? 0 }} pages read</span>
      </p>
      <ScrapeJobStateBadge
        v-if="meta.state"
        :state="meta.state"
      />
      <NuxtLink
        v-if="meta.jobId != null"
        :to="`/scrapes/${meta.jobId}`"
        class="text-xs text-emerald-700 dark:text-emerald-400 hover:underline"
      >
        Open scrape
      </NuxtLink>
    </div>
  </div>
</template>
