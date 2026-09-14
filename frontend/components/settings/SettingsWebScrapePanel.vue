<script setup lang="ts">
// Web Scraping settings panel. The crawl limits web_scrape reads on every call.
import {
  CheckIcon,
  InformationCircleIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const { configData, saving, refresh } = useSettingsConfig()

interface LimitField {
  key: string
  label: string
  /** Shown while the key is unset — mirrors DEFAULT_MAX_PAGES / DEFAULT_MAX_DEPTH in WebScrapeTool. */
  fallback: string
  min: number
  ariaLabel: string
  tip: string
}

const FIELDS: LimitField[] = [
  {
    key: 'web_scrape.max-pages',
    label: 'maxPages',
    fallback: '25',
    min: 1,
    ariaLabel: 'Max pages per scrape',
    tip: 'Pages one web_scrape call reads when it does not ask for fewer. A call asking for more is capped here. Minimum 1.',
  },
  {
    key: 'web_scrape.max-depth',
    label: 'maxDepth',
    fallback: '2',
    min: 0,
    ariaLabel: 'Max link depth per scrape',
    tip: 'How many links deep one web_scrape call follows from the starting URL. 0 reads only the starting URL. A call asking for more is capped here.',
  },
]

function valueOf(field: LimitField): string {
  return configData.value?.entries.find(e => e.key === field.key)?.value ?? field.fallback
}

const editingKey = ref<string | null>(null)
// v-model on a type="number" input yields a number once the text parses.
const draft = ref<string | number>('')
const saveError = ref<string | null>(null)

function startEdit(field: LimitField) {
  editingKey.value = field.key
  draft.value = valueOf(field)
  saveError.value = null
}

function messageOf(e: unknown): string {
  // A refused write is 403 {type, code, message}, where message is setWithSideEffects' rejection.
  const data = (e as { data?: { message?: string } })?.data
  return data?.message ?? (e instanceof Error ? e.message : 'Save failed')
}

async function save(key: string) {
  saving.value = true
  saveError.value = null
  try {
    await $fetch('/api/config', { method: 'POST', body: { key, value: String(draft.value).trim() } })
    editingKey.value = null
    await refresh()
  }
  catch (e) {
    saveError.value = messageOf(e)
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Web Scraping
    </h2>
    <p class="text-xs text-fg-muted">
      Crawl limits for the <span class="font-mono">web_scrape</span> tool. Each is the
      default when a call omits its own <span class="font-mono">maxPages</span> /
      <span class="font-mono">maxDepth</span>, and the ceiling when it asks for more — an
      agent can request a smaller crawl, never a larger one. Changes apply live; no
      restart needed.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <div
          v-for="field in FIELDS"
          :key="field.key"
          :data-testid="`web-scrape-row-${field.label}`"
          class="px-4 py-2.5 flex items-center gap-3"
        >
          <span class="text-xs font-mono text-fg-muted w-56 shrink-0 flex items-center gap-1.5">
            {{ field.label }}
            <span class="relative group/tip">
              <InformationCircleIcon
                class="w-3 h-3 text-fg-muted group-hover/tip:text-fg-muted cursor-help transition-colors"
                aria-hidden="true"
              />
              <span class="absolute left-0 top-5 z-20 hidden group-hover/tip:block w-64 px-2.5 py-2 bg-muted border border-input text-xs text-fg-muted leading-relaxed shadow-xl pointer-events-none">
                {{ field.tip }}
              </span>
            </span>
          </span>
          <template v-if="editingKey === field.key">
            <input
              v-model="draft"
              type="number"
              :min="field.min"
              :aria-label="field.ariaLabel"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              :disabled="saving"
              @click="save(field.key)"
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
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ valueOf(field) }}</span>
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
    <p
      v-if="saveError"
      class="text-xs text-red-700 dark:text-red-400"
      role="alert"
    >
      {{ saveError }}
    </p>
  </div>
</template>
