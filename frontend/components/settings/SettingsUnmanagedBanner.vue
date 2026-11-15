<script setup lang="ts">
// Unmanaged-config warning banner. Config DB rows not owned by any managed
// Settings section shouldn't exist in normal operation — they're usually stale
// keys from a prior schema. Rather than a permanent "Unmanaged Config" nav
// section (which implies they're an expected, ongoing thing), this surfaces
// them at the top of the Settings page ONLY when present, as a cleanup signal.
// Renders nothing when the config is clean.
import { ChevronDownIcon, ExclamationTriangleIcon } from '@heroicons/vue/24/outline'
import type { ConfigEntry } from '~/types/api'
import { isManagedKey } from './managed-prefixes'
import { isSensitiveKey } from '~/utils/secrets'

const { configData, refresh } = useSettingsConfig()
const { confirm } = useConfirm()
const { mutate, error: mutationError } = useApiMutation()

const entries = computed<ConfigEntry[]>(() => configData.value?.entries ?? [])
// Only an explicit false: the server marks a row no code in this build reads (ConfigKeyCatalog).
const unused = computed<ConfigEntry[]>(() => entries.value.filter(e => e.known === false))
const unmanaged = computed<ConfigEntry[]>(() =>
  entries.value.filter(e => e.known !== false && !isManagedKey(e.key)),
)
const expanded = ref(false)
const unusedExpanded = ref(false)
const failure = ref<string | null>(null)

async function deleteUnused(entry: ConfigEntry) {
  const ok = await confirm({
    title: 'Delete config key',
    message: `Delete ${entry.key}? JClaw found no code that reads it. `
      + (isSensitiveKey(entry.key)
        ? 'The stored credential goes with it and cannot be recovered.'
        : 'This cannot be undone.'),
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  failure.value = null
  // A key can hold a slash (a model id), which the route only accepts encoded.
  const res = await mutate(`/api/config/${encodeURIComponent(entry.key)}`, { method: 'DELETE' })
  if (!res) failure.value = mutationError.value ?? `Could not delete ${entry.key}.`
  await refresh()
}
</script>

<template>
  <div
    v-if="unused.length"
    class="mb-6 border border-amber-400/40 bg-amber-50/60 dark:bg-amber-900/15 rounded-lg overflow-hidden"
    data-testid="unused-banner"
  >
    <button
      type="button"
      class="w-full flex items-start gap-2 px-4 py-3 text-left"
      :aria-expanded="unusedExpanded"
      data-testid="unused-banner-toggle"
      @click="unusedExpanded = !unusedExpanded"
    >
      <ExclamationTriangleIcon
        class="w-4 h-4 mt-0.5 shrink-0 text-amber-600 dark:text-amber-400"
        aria-hidden="true"
      />
      <span class="min-w-0 flex-1">
        <span class="text-sm font-medium text-amber-800 dark:text-amber-300">
          {{ unused.length }} unused config {{ unused.length === 1 ? 'key' : 'keys' }}
        </span>
        <span class="block text-xs text-amber-700 dark:text-amber-400">
          JClaw found no code in this version that reads these rows, so deleting one changes nothing.
        </span>
      </span>
      <ChevronDownIcon
        class="w-4 h-4 mt-0.5 shrink-0 text-amber-700 dark:text-amber-400 transition-transform"
        :class="unusedExpanded ? 'rotate-180' : ''"
        aria-hidden="true"
      />
    </button>
    <div
      v-if="unusedExpanded"
      class="border-t border-amber-400/30 divide-y divide-amber-400/20"
    >
      <p
        v-if="failure"
        class="px-4 py-2 text-xs text-red-700 dark:text-red-300"
        role="alert"
      >
        {{ failure }}
      </p>
      <div
        v-for="entry in unused"
        :key="entry.key"
        class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3"
        data-testid="unused-row"
      >
        <span class="text-xs font-mono text-fg-muted w-64 max-sm:w-full shrink-0 truncate">{{ entry.key }}</span>
        <span class="flex-1 text-sm text-fg-muted font-mono truncate">{{ entry.value && isSensitiveKey(entry.key) ? '••••••••' : entry.value }}</span>
        <button
          type="button"
          class="px-2 py-1 text-xs border border-red-200 dark:border-red-900/60 text-red-700 dark:text-red-300 hover:bg-red-50 dark:hover:bg-red-950/40 transition-colors"
          :aria-label="`Delete ${entry.key}`"
          @click="deleteUnused(entry)"
        >
          Delete
        </button>
      </div>
    </div>
  </div>
  <div
    v-if="unmanaged.length"
    class="mb-6 border border-amber-400/40 bg-amber-50/60 dark:bg-amber-900/15 rounded-lg overflow-hidden"
  >
    <button
      type="button"
      class="w-full flex items-start gap-2 px-4 py-3 text-left"
      :aria-expanded="expanded"
      data-testid="unmanaged-banner-toggle"
      @click="expanded = !expanded"
    >
      <ExclamationTriangleIcon
        class="w-4 h-4 mt-0.5 shrink-0 text-amber-600 dark:text-amber-400"
        aria-hidden="true"
      />
      <span class="min-w-0 flex-1">
        <span class="text-sm font-medium text-amber-800 dark:text-amber-300">
          {{ unmanaged.length }} unmanaged config {{ unmanaged.length === 1 ? 'key' : 'keys' }}
        </span>
        <span class="block text-xs text-amber-700 dark:text-amber-400">
          Config DB rows not owned by any Settings section — usually stale keys from a prior
          version. They shouldn't exist; review and remove them.
        </span>
      </span>
      <ChevronDownIcon
        class="w-4 h-4 mt-0.5 shrink-0 text-amber-700 dark:text-amber-400 transition-transform"
        :class="expanded ? 'rotate-180' : ''"
        aria-hidden="true"
      />
    </button>
    <div
      v-if="expanded"
      class="border-t border-amber-400/30 divide-y divide-amber-400/20"
    >
      <div
        v-for="entry in unmanaged"
        :key="entry.key"
        class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3"
      >
        <span class="text-xs font-mono text-fg-muted w-64 max-sm:w-full shrink-0 truncate">{{ entry.key }}</span>
        <span class="flex-1 text-sm text-fg-muted font-mono truncate">{{ entry.value && isSensitiveKey(entry.key) ? '••••••••' : entry.value }}</span>
      </div>
    </div>
  </div>
</template>
