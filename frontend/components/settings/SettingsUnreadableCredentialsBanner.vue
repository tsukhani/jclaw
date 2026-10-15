<script setup lang="ts">
// Stored credentials that do not decrypt under this installation's secret (JCLAW-1455). Each reads as missing
// wherever it is used, so this names them and where each is entered again. Renders nothing when every one decrypts.
import { ExclamationTriangleIcon } from '@heroicons/vue/24/outline'

interface UnreadableCredential {
  store: string
  owner: string
  field: string
  page: string
}

const { data } = useLazyFetch<{ unreadable: UnreadableCredential[] }>('/api/credentials/unreadable')
const items = computed(() => data.value?.unreadable ?? [])

// The Settings section that edits a config key, by the key's prefix; anything else opens Settings itself.
const SECTION_BY_PREFIX: ReadonlyArray<[string, string]> = [
  ['provider.', 'providers'],
  ['decision.', 'decision-providers'],
  ['search.', 'search'],
  ['web_scrape.proxy.', 'proxy-providers'],
  ['scanner.', 'malware'],
  ['otel.', 'telemetry'],
  ['transcription.', 'transcription'],
  ['tts.', 'speech'],
  ['caption.', 'image-caption'],
  ['imagegen.', 'image-generation'],
  ['videogen.', 'video-generation'],
  ['memory.', 'memory'],
]

function link(item: UnreadableCredential): string {
  if (item.store !== 'config') return item.page
  const section = SECTION_BY_PREFIX.find(([prefix]) => item.owner.startsWith(prefix))?.[1]
  return section ? `/settings?section=${section}` : '/settings'
}

function label(item: UnreadableCredential): string {
  return item.store === 'config' ? item.owner : `${item.store} · ${item.owner} · ${item.field}`
}
</script>

<template>
  <div
    v-if="items.length"
    class="mb-6 border border-red-400/50 bg-red-50/60 dark:bg-red-900/15 rounded-lg overflow-hidden"
    role="alert"
    data-testid="unreadable-credentials"
  >
    <div class="flex items-start gap-2 px-4 py-3">
      <ExclamationTriangleIcon
        class="w-4 h-4 mt-0.5 shrink-0 text-red-600 dark:text-red-400"
        aria-hidden="true"
      />
      <span class="min-w-0 flex-1">
        <span class="text-sm font-medium text-red-800 dark:text-red-300">
          {{ items.length === 1 ? '1 stored credential does' : `${items.length} stored credentials do` }} not decrypt
        </span>
        <span class="block text-xs text-red-700 dark:text-red-400">
          {{ items.length === 1 ? 'It was' : 'They were' }} saved with a different secret from the one in this installation's
          <code>certs/.env</code>, so JClaw treats {{ items.length === 1 ? 'it' : 'each one' }} as missing. Put back the
          <code>certs/.env</code> {{ items.length === 1 ? 'it was' : 'they were' }} saved with and restart to recover
          {{ items.length === 1 ? 'it' : 'them all' }}, or enter {{ items.length === 1 ? 'it' : 'each one' }} again.
        </span>
      </span>
    </div>
    <div class="border-t border-red-400/30 divide-y divide-red-400/20">
      <div
        v-for="item in items"
        :key="`${item.store}:${item.owner}:${item.field}`"
        class="px-4 py-2 flex max-sm:flex-wrap items-center gap-3 text-xs"
      >
        <span class="font-mono text-fg-primary truncate min-w-0">{{ label(item) }}</span>
        <NuxtLink
          :to="link(item)"
          class="ml-auto shrink-0 text-red-700 dark:text-red-400 hover:underline"
        >
          Enter it again
        </NuxtLink>
      </div>
    </div>
  </div>
</template>
