<script setup lang="ts">
import { PlusIcon, XMarkIcon } from '@heroicons/vue/24/outline'
import type { Agent, ConfigResponse, ScrapeJob } from '~/types/api'
import { WEB_SCRAPE_DEFAULTS, type WebScrapeDefaultKey } from '~/utils/web-scrape-defaults'

/**
 * JCLAW-1273: the operator's New scrape form. Every per-job setting starts from current Settings and
 * applies to this one job only; nothing here writes to Settings. The page, depth and time limits bound
 * what an agent may ask for, not the operator, so the form shows them and lets a value go past them.
 * The proxy and concurrency are machine-wide and stay in Settings.
 */

const { data: agentList } = await useFetch<Agent[]>('/api/agents', { default: () => [] })
const { data: config } = await useFetch<ConfigResponse>('/api/config')

function setting(key: WebScrapeDefaultKey): string {
  return config.value?.entries.find(e => e.key === key)?.value?.trim() || WEB_SCRAPE_DEFAULTS[key]
}

const ceilings = computed(() => ({
  maxPages: Number(setting('web_scrape.job.max-pages')),
  maxDepth: Number(setting('web_scrape.max-depth')),
  maxMinutes: Number(setting('web_scrape.job.max-minutes')),
}))

interface ExtractRow { name: string, selector: string }

const form = reactive({
  url: '',
  agentId: agentList.value.find(a => a.isMain)?.id ?? agentList.value[0]?.id ?? null as number | null,
  maxPages: ceilings.value.maxPages,
  maxDepth: ceilings.value.maxDepth,
  maxMinutes: ceilings.value.maxMinutes,
  language: setting('web_scrape.language'),
  sameHostOnly: true,
  respectRobots: setting('web_scrape.respect-robots').toLowerCase() !== 'false',
  seedFromSitemap: setting('web_scrape.seed-from-sitemap').toLowerCase() !== 'false',
  format: 'markdown' as 'markdown' | 'text' | 'json',
  extract: [] as ExtractRow[],
  metadata: false,
})

const { mutate, loading, errorDetails } = useApiMutation()

/** The server names the field a 400 is about; a refusal naming none is shown above the form. */
function fieldError(field: string): string | null {
  return errorDetails.value?.field === field ? errorDetails.value.message : null
}

const KNOWN_FIELDS = new Set(['url', 'agentId', 'maxPages', 'maxDepth', 'maxMinutes', 'language', 'format', 'extract'])
const generalError = computed(() =>
  errorDetails.value && !KNOWN_FIELDS.has(errorDetails.value.field ?? '') ? errorDetails.value : null)

async function submit() {
  const extract = Object.fromEntries(form.extract
    .filter(row => row.name.trim() && row.selector.trim())
    .map(row => [row.name.trim(), row.selector.trim()]))
  const job = await mutate<ScrapeJob>('/api/scrape-jobs', {
    method: 'POST',
    body: {
      agentId: form.agentId,
      url: form.url.trim(),
      maxPages: form.maxPages,
      maxDepth: form.maxDepth,
      maxMinutes: form.maxMinutes,
      sameHostOnly: form.sameHostOnly,
      respectRobots: form.respectRobots,
      seedFromSitemap: form.seedFromSitemap,
      language: form.language.trim(),
      format: form.format,
      metadata: form.metadata,
      ...(Object.keys(extract).length ? { extract } : {}),
    },
  })
  if (job) await navigateTo(`/scrapes/${job.id}`)
}

const breadcrumbExtra = useBreadcrumbExtra()
breadcrumbExtra.value = 'New scrape'
onUnmounted(() => {
  breadcrumbExtra.value = null
})

const inputClass = 'bg-surface-elevated border border-input text-sm text-fg-primary px-2 py-1.5 w-full aria-[invalid=true]:border-red-600'
</script>

<template>
  <div class="max-w-2xl">
    <h1 class="text-lg font-semibold text-fg-strong mb-1">
      New scrape
    </h1>
    <p class="text-sm text-fg-muted mb-6">
      Runs in the background until it finishes, writing each page to the chosen agent's workspace.
      These values apply to this scrape only; the proxy and concurrency come from Settings.
    </p>

    <ApiErrorAlert
      :error="generalError"
      headline="Could not start the scrape"
      class="mb-4"
    />

    <form
      class="flex flex-col gap-4"
      novalidate
      data-testid="new-scrape-form"
      @submit.prevent="submit"
    >
      <div class="flex flex-col gap-1">
        <label
          for="scrape-url"
          class="flex flex-col gap-1 text-xs font-medium text-fg-primary"
        >Starting URL
          <input
            id="scrape-url"
            v-model="form.url"
            type="url"
            required
            placeholder="https://docs.example.com/"
            :class="inputClass"
            :aria-invalid="!!fieldError('url')"
            :aria-describedby="fieldError('url') ? 'scrape-url-error' : undefined"
          >
        </label>
        <p
          v-if="fieldError('url')"
          id="scrape-url-error"
          class="text-xs text-danger"
        >
          {{ fieldError('url') }}
        </p>
      </div>

      <div class="flex flex-col gap-1">
        <label
          for="scrape-agent"
          class="flex flex-col gap-1 text-xs font-medium text-fg-primary"
        >Agent
          <select
            id="scrape-agent"
            v-model="form.agentId"
            required
            :class="inputClass"
            :aria-invalid="!!fieldError('agentId')"
            aria-describedby="scrape-agent-hint"
          >
            <option
              v-for="agent in agentList"
              :key="agent.id"
              :value="agent.id"
            >
              {{ agent.name }}
            </option>
          </select>
        </label>
        <p
          id="scrape-agent-hint"
          class="text-xs text-fg-muted"
        >
          {{ fieldError('agentId') ?? 'The pages go to this agent\'s workspace, under scrapes/.' }}
        </p>
      </div>

      <fieldset class="grid gap-4 sm:grid-cols-3">
        <legend class="sr-only">
          Limits
        </legend>
        <div
          v-for="limit in ([
            { key: 'maxPages', label: 'Pages', unit: 'pages', min: 1 },
            { key: 'maxDepth', label: 'Links deep', unit: 'links', min: 0 },
            { key: 'maxMinutes', label: 'Time limit (minutes)', unit: 'minutes', min: 1 },
          ] as const)"
          :key="limit.key"
          class="flex flex-col gap-1"
        >
          <label
            :for="`scrape-${limit.key}`"
            class="flex flex-col gap-1 text-xs font-medium text-fg-primary"
          >{{ limit.label }}
            <input
              :id="`scrape-${limit.key}`"
              v-model.number="form[limit.key]"
              type="number"
              :min="limit.min"
              step="1"
              :class="inputClass"
              :aria-invalid="!!fieldError(limit.key)"
              :aria-describedby="`scrape-${limit.key}-hint`"
            >
          </label>
          <p
            :id="`scrape-${limit.key}-hint`"
            class="text-xs"
            :class="fieldError(limit.key) ? 'text-danger' : 'text-fg-muted'"
          >
            {{ fieldError(limit.key) ?? `An agent may ask for up to ${ceilings[limit.key]} ${limit.unit}; you may go higher.` }}
          </p>
        </div>
      </fieldset>

      <fieldset class="flex flex-col gap-2">
        <legend class="text-xs font-medium text-fg-primary mb-1">
          Crawl
        </legend>
        <label
          for="scrape-same-host"
          class="flex items-center gap-2 text-sm text-fg-primary"
        >
          <input
            id="scrape-same-host"
            v-model="form.sameHostOnly"
            type="checkbox"
          >Stay on the starting URL's host
        </label>
        <label
          for="scrape-robots"
          class="flex items-center gap-2 text-sm text-fg-primary"
        >
          <input
            id="scrape-robots"
            v-model="form.respectRobots"
            type="checkbox"
          >Honour the site's robots.txt
        </label>
        <label
          for="scrape-sitemap"
          class="flex items-center gap-2 text-sm text-fg-primary"
        >
          <input
            id="scrape-sitemap"
            v-model="form.seedFromSitemap"
            type="checkbox"
          >Add pages from the site's sitemaps
        </label>
        <div class="flex flex-col gap-1 max-w-48">
          <label
            for="scrape-language"
            class="flex flex-col gap-1 text-xs font-medium text-fg-primary"
          >Preferred language
            <input
              id="scrape-language"
              v-model="form.language"
              type="text"
              :class="inputClass"
              :aria-invalid="!!fieldError('language')"
              aria-describedby="scrape-language-hint"
            >
          </label>
          <p
            id="scrape-language-hint"
            class="text-xs"
            :class="fieldError('language') ? 'text-danger' : 'text-fg-muted'"
          >
            {{ fieldError('language') ?? 'An hreflang code such as en, ja or pt-BR.' }}
          </p>
        </div>
      </fieldset>

      <fieldset class="flex flex-col gap-2">
        <legend class="text-xs font-medium text-fg-primary mb-1">
          Output
        </legend>
        <div class="flex flex-col gap-1 max-w-48">
          <label
            for="scrape-format"
            class="flex flex-col gap-1 text-xs text-fg-muted"
          >Format
            <select
              id="scrape-format"
              v-model="form.format"
              :class="inputClass"
              :aria-invalid="!!fieldError('format')"
            >
              <option value="markdown">
                Markdown
              </option>
              <option value="text">
                Plain text
              </option>
              <option value="json">
                JSON, one record per page
              </option>
            </select>
          </label>
        </div>
        <label
          for="scrape-metadata"
          class="flex items-center gap-2 text-sm text-fg-primary"
        >
          <input
            id="scrape-metadata"
            v-model="form.metadata"
            type="checkbox"
          >Include each page's metadata (title, OpenGraph, JSON-LD)
        </label>
        <div class="flex flex-col gap-2">
          <span
            id="scrape-extract-label"
            class="text-xs text-fg-muted"
          >Fields to extract, as CSS selectors; add @attr to read an attribute. Extracting makes the output JSON.</span>
          <div
            v-for="(row, i) in form.extract"
            :key="i"
            class="flex items-center gap-2"
          >
            <input
              v-model="row.name"
              type="text"
              placeholder="price"
              :aria-label="`Name of field ${i + 1}`"
              :class="inputClass"
              class="max-w-40"
            >
            <input
              v-model="row.selector"
              type="text"
              placeholder=".price or a.next@href"
              :aria-label="`CSS selector of field ${i + 1}`"
              :class="inputClass"
              :aria-invalid="!!fieldError('extract')"
            >
            <button
              type="button"
              class="p-1 text-fg-muted hover:text-fg-strong"
              :aria-label="`Remove field ${i + 1}`"
              @click="form.extract.splice(i, 1)"
            >
              <XMarkIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
          </div>
          <p
            v-if="fieldError('extract')"
            class="text-xs text-danger"
            data-testid="scrape-extract-error"
          >
            {{ fieldError('extract') }}
          </p>
          <button
            type="button"
            class="self-start inline-flex items-center gap-1 text-xs text-fg-primary border border-border px-2 py-1 hover:bg-muted"
            aria-describedby="scrape-extract-label"
            @click="form.extract.push({ name: '', selector: '' })"
          >
            <PlusIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />Add field
          </button>
        </div>
      </fieldset>

      <div class="flex items-center gap-2 pt-2">
        <button
          type="submit"
          class="px-3 py-1.5 bg-emerald-700 text-white text-sm font-medium hover:bg-emerald-600 disabled:opacity-40"
          :disabled="loading || !form.url.trim() || form.agentId == null"
        >
          {{ loading ? 'Starting…' : 'Start scrape' }}
        </button>
        <NuxtLink
          to="/scrapes"
          class="px-3 py-1.5 text-sm text-fg-muted hover:text-fg-strong"
        >
          Cancel
        </NuxtLink>
      </div>
    </form>
  </div>
</template>
