<script setup lang="ts">
// Web Scraping settings panel: every web_scrape.* key the tool and its sitemap seeder read.
// WebScrapeSettingsTest fails when a key in app/ has no row here.
import {
  CheckIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const { configData, saving, refresh } = useSettingsConfig()

interface SettingField {
  key: string
  kind: 'number' | 'text' | 'boolean' | 'secret'
  /** Shown while the key is unset — mirrors the default in WebScrapeTool / SitemapSeeder. */
  fallback: string
  min?: number
  max?: number
  tip: string
}

const GROUPS: { label: string, fields: SettingField[] }[] = [
  {
    label: 'Crawl',
    fields: [
      {
        key: 'web_scrape.max-pages',
        kind: 'number',
        fallback: '25',
        min: 1,
        tip: 'Pages one call reads when it does not ask for fewer. A call asking for more is capped here. Minimum 1.',
      },
      {
        key: 'web_scrape.max-depth',
        kind: 'number',
        fallback: '2',
        min: 0,
        tip: 'How many links deep one call follows from the starting URL; 0 reads only that URL. A call asking for more is capped here.',
      },
      {
        key: 'web_scrape.timeout-seconds',
        kind: 'number',
        fallback: '60',
        min: 1,
        tip: 'Time budget for one crawl. When it runs out the crawl stops and returns the pages it has read, saying how many were left. Minimum 1.',
      },
      {
        key: 'web_scrape.concurrency',
        kind: 'number',
        fallback: '4',
        min: 1,
        max: 16,
        tip: 'Pages fetched in parallel. Per-host pacing still applies, so raising it overlaps round trips rather than hitting one site harder. 1 to 16.',
      },
      {
        key: 'web_scrape.max-escalations',
        kind: 'number',
        fallback: '5',
        min: 0,
        tip: 'Pages per crawl that may be retried with a slower fetcher (browser impersonation or a full render) when a plain fetch is blocked or comes back empty. Each costs seconds rather than milliseconds. 0 never escalates.',
      },
      {
        key: 'web_scrape.language',
        kind: 'text',
        fallback: 'en',
        tip: 'Preferred language on sites that publish translations, as an hreflang code such as en, ja or pt-BR. Other translations of a page are skipped. A call\'s own language argument overrides it.',
      },
    ],
  },
  {
    label: 'Robots & Sitemaps',
    fields: [
      {
        key: 'web_scrape.respect-robots',
        kind: 'boolean',
        fallback: 'true',
        tip: 'Honour each site\'s robots.txt. A call can still turn it off for one request when the user asks. Per-host pacing stays on either way.',
      },
      {
        key: 'web_scrape.seed-from-sitemap',
        kind: 'boolean',
        fallback: 'true',
        tip: 'Add URLs from the sitemaps a site\'s robots.txt declares to the crawl. Only applies while robots.txt is respected.',
      },
      {
        key: 'web_scrape.max-sitemap-urls',
        kind: 'number',
        fallback: '50',
        min: 0,
        tip: 'Most URLs one crawl takes from sitemaps. 0 seeds nothing.',
      },
      {
        key: 'web_scrape.max-sitemap-documents',
        kind: 'number',
        fallback: '3',
        min: 0,
        tip: 'Most sitemap files one crawl fetches, counting nested sitemap indexes. 0 fetches none.',
      },
    ],
  },
  {
    label: 'Background jobs',
    fields: [
      {
        key: 'web_scrape.job.max-pages',
        kind: 'number',
        fallback: '500',
        min: 1,
        tip: 'Most pages an agent\'s background scrape may read; a larger request is capped here, and it is the default when the agent names none. A scrape you start yourself is not capped. Minimum 1.',
      },
      {
        key: 'web_scrape.job.max-minutes',
        kind: 'number',
        fallback: '60',
        min: 1,
        tip: 'Longest an agent\'s background scrape may run, in minutes; a longer request is capped here. When it runs out the job stops and keeps the pages it has read. Minimum 1.',
      },
      {
        key: 'web_scrape.job.max-concurrent',
        kind: 'number',
        fallback: '2',
        min: 1,
        max: 8,
        tip: 'Background scrapes running at once; the rest wait their turn. Each one fetches with its own set of workers, so this multiplies the concurrency above. 1 to 8.',
      },
    ],
  },
  {
    label: 'Proxy',
    fields: [
      {
        key: 'web_scrape.proxy.url',
        kind: 'text',
        fallback: '',
        tip: 'Route web_fetch and web_scrape through this proxy, as http://host:port or socks5://host:port. Nothing else uses it. Leave empty to connect directly. Credentials go in username and password, not in the URL.',
      },
      {
        key: 'web_scrape.proxy.username',
        kind: 'text',
        fallback: '',
        tip: 'Username for an http:// proxy that asks for one. SOCKS5 proxies are used without credentials.',
      },
      {
        key: 'web_scrape.proxy.password',
        kind: 'secret',
        fallback: '',
        tip: 'Password for an http:// proxy. Stored like the other secrets here and never shown back.',
      },
      {
        key: 'web_scrape.proxy.enabled',
        kind: 'boolean',
        fallback: 'true',
        tip: 'Turn the proxy off without clearing its address.',
      },
    ],
  },
]

function labelOf(field: SettingField): string {
  return field.key.slice('web_scrape.'.length)
}

function valueOf(field: SettingField): string {
  return configData.value?.entries.find(e => e.key === field.key)?.value ?? field.fallback
}

// The backend treats anything but "false" as on.
function isOn(field: SettingField): boolean {
  return valueOf(field).trim().toLowerCase() !== 'false'
}

const editingKey = ref<string | null>(null)
// v-model on a type="number" input yields a number once the text parses.
const draft = ref<string | number>('')
const { saveError, attempt } = useSaveAttempt()

// A secret reads back masked; prefilling the editor with the mask would save the mask over the real value.
function startEdit(field: SettingField) {
  editingKey.value = field.key
  draft.value = field.kind === 'secret' ? '' : valueOf(field)
  saveError.value = null
}

function shown(field: SettingField): string {
  const value = valueOf(field)
  if (field.kind === 'secret') return value ? 'set' : 'not set'
  return value || 'not set'
}

async function save(key: string, value: string) {
  saving.value = true
  // A refused write is 403 {type, code, message, template}; message is setWithSideEffects' rejection.
  if (await attempt(() => $fetch('/api/config', { method: 'POST', body: { key, value: value.trim() } }))) {
    editingKey.value = null
    await refresh()
  }
  saving.value = false
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Web Scraping
    </h2>
    <p class="text-xs text-fg-muted">
      Settings for the <span class="font-mono">web_scrape</span> tool.
      <span class="font-mono">max-pages</span> and <span class="font-mono">max-depth</span>
      are the default when a call omits its own <span class="font-mono">maxPages</span> /
      <span class="font-mono">maxDepth</span>, and the ceiling when it asks for more — an
      agent can request a smaller crawl, never a larger one. Changes apply live; no
      restart needed.
    </p>
    <ApiErrorAlert :error="saveError" />
    <template
      v-for="group in GROUPS"
      :key="group.label"
    >
      <h3 class="text-[11px] font-semibold text-fg-muted uppercase tracking-wide pt-2">
        {{ group.label }}
      </h3>
      <div class="bg-surface-elevated border border-border">
        <div class="divide-y divide-border">
          <div
            v-for="field in group.fields"
            :key="field.key"
            :data-testid="`web-scrape-row-${labelOf(field)}`"
            class="px-4 py-2.5 flex max-sm:flex-wrap items-center gap-3"
          >
            <span class="text-xs font-mono text-fg-muted w-56 max-sm:w-full shrink-0 flex items-center gap-1.5">
              {{ labelOf(field) }}
              <InfoTip
                :label="`About ${labelOf(field)}`"
                content-class="w-64 font-mono"
              >
                {{ field.tip }}
              </InfoTip>
            </span>
            <template v-if="field.kind === 'boolean'">
              <button
                type="button"
                :aria-pressed="isOn(field)"
                :aria-label="labelOf(field)"
                :disabled="saving"
                :class="isOn(field) ? 'bg-emerald-600 hover:bg-emerald-500' : 'bg-muted hover:bg-muted'"
                class="relative w-9 h-5 rounded-full transition-colors"
                @click="save(field.key, isOn(field) ? 'false' : 'true')"
              >
                <span
                  :class="isOn(field) ? 'translate-x-4' : 'translate-x-0.5'"
                  class="block w-4 h-4 bg-white rounded-full transition-transform"
                />
              </button>
              <span class="ml-auto text-[11px] text-fg-muted">
                {{ isOn(field) ? 'on' : 'off' }}
              </span>
            </template>
            <template v-else-if="editingKey === field.key">
              <input
                v-model="draft"
                :type="field.kind === 'number' ? 'number' : field.kind === 'secret' ? 'password' : 'text'"
                :min="field.min"
                :max="field.max"
                :aria-label="labelOf(field)"
                :autocomplete="field.kind === 'secret' ? 'new-password' : undefined"
                :class="field.kind === 'number' ? 'w-24' : 'flex-1 min-w-0'"
                class="px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              >
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                :disabled="saving"
                @click="save(field.key, String(draft))"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editingKey = null; saveError = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
            <template v-else>
              <span class="flex-1 text-sm text-fg-primary font-mono break-all">{{ shown(field) }}</span>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Edit"
                @click="startEdit(field)"
              >
                <PencilIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </template>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>
