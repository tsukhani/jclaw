<script setup lang="ts">
import type { ScrapeJobState } from '~/types/api'
import { SCRAPE_STATE_BADGE, SCRAPE_STATE_LABEL } from '~/utils/scrape-job'

defineProps<{ state: ScrapeJobState }>()
</script>

<template>
  <span
    class="inline-flex items-center px-1.5 py-0.5 text-xs font-medium border whitespace-nowrap"
    :class="SCRAPE_STATE_BADGE[state]"
    :data-state="state"
  >{{ SCRAPE_STATE_LABEL[state] }}</span>
</template>
