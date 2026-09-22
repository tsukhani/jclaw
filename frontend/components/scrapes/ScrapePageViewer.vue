<script setup lang="ts">
import type { ScrapeJobPageContent } from '~/types/api'
import { prettyJson, renderScrapedMarkdown } from '~/utils/scrape-markdown'

/** One scraped page, as its job's format renders it. Loads nothing from the site it came from. */
const props = defineProps<{ page: ScrapeJobPageContent }>()

const html = computed(() => props.page.format === 'markdown' ? renderScrapedMarkdown(props.page.content) : '')
const json = computed(() => props.page.format === 'json' ? prettyJson(props.page.content) : '')
</script>

<template>
  <!-- eslint-disable vue/no-v-html -- renderScrapedMarkdown sanitises with DOMPurify and turns every embed into a link. -->
  <div
    v-if="page.format === 'markdown'"
    class="prose-chat text-sm break-words"
    data-testid="scrape-page-markdown"
    v-html="html"
  />
  <!-- eslint-enable vue/no-v-html -->
  <pre
    v-else-if="page.format === 'json'"
    class="text-xs font-mono whitespace-pre-wrap break-words text-fg-primary"
    data-testid="scrape-page-json"
  >{{ json }}</pre>
  <pre
    v-else
    class="text-sm whitespace-pre-wrap break-words text-fg-primary font-sans"
    data-testid="scrape-page-text"
  >{{ page.content }}</pre>
</template>
