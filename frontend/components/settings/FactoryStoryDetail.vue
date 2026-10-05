<script setup lang="ts">
import type { ApiErrorDetails } from '~/types/api'
import { FACTORY_BUTTON, type BoardStory, type LogView } from './factory-board'

const POLL_MS = 3_000

const props = defineProps<{ story: BoardStory }>()
const emit = defineEmits<{ close: [] }>()

const tab = ref<string | null>(null)
const log = ref<LogView | null>(null)
const error = ref<ApiErrorDetails | null>(null)
const latest = useLatestRequest()
let timer: ReturnType<typeof setInterval> | null = null

watch([() => props.story.key, () => props.story.logs.join('\n')], ([key], prev) => {
  if (!prev?.length || key !== prev[0] || !tab.value || !props.story.logs.includes(tab.value)) {
    tab.value = props.story.logs[0] ?? null
  }
}, { immediate: true })

async function load() {
  const file = tab.value
  if (!file) return
  const token = latest.begin()
  try {
    const view = await $fetch<LogView>(
      `/api/factory/stories/${encodeURIComponent(props.story.key)}/logs/${encodeURIComponent(file)}`)
    if (!latest.isCurrent(token)) return
    log.value = view
    error.value = null
  }
  catch (e) {
    if (!latest.isCurrent(token)) return
    log.value = null
    error.value = apiErrorDetails(e)
  }
}

function startPolling() {
  if (!timer && tab.value) timer = setInterval(load, POLL_MS)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

watch([() => props.story.key, tab], () => {
  stopPolling()
  latest.begin()
  log.value = null
  error.value = null
  if (!tab.value) return
  load()
  if (props.story.state === 'running') startPolling()
}, { immediate: true })

// Leaving `running` only stops the poll: the last fetched tail stays on screen.
watch(() => props.story.state === 'running', (running) => {
  if (running) startPolling()
  else stopPolling()
})

onBeforeUnmount(stopPolling)
</script>

<template>
  <section
    class="bg-surface-elevated border border-border px-4 py-3 space-y-3"
    data-testid="factory-story-view"
  >
    <div class="flex items-start justify-between gap-3">
      <h3 class="text-sm font-medium text-fg-strong">
        <span class="font-mono">{{ story.key }}</span> {{ story.summary }}
      </h3>
      <button
        type="button"
        :class="FACTORY_BUTTON"
        data-testid="factory-story-close"
        @click="emit('close')"
      >
        Close
      </button>
    </div>
    <p
      v-if="story.logs.length === 0"
      class="text-xs text-fg-muted"
    >
      No logs yet.
    </p>
    <template v-else>
      <div
        class="flex flex-wrap gap-1"
        role="tablist"
      >
        <button
          v-for="f in story.logs"
          :key="f"
          type="button"
          role="tab"
          :aria-selected="tab === f"
          class="px-2 py-1 text-xs font-mono border border-border"
          :class="tab === f ? 'bg-muted text-fg-strong' : 'text-fg-muted hover:bg-muted/40'"
          :data-testid="`factory-log-tab-${f}`"
          @click="tab = f"
        >
          {{ f }}
        </button>
      </div>
      <ApiErrorAlert :error="error" />
      <p
        v-if="log?.truncated"
        class="text-xs text-fg-muted"
      >
        Showing the end of the log only.
      </p>
      <pre
        v-if="log"
        class="max-h-96 overflow-auto text-xs font-mono bg-muted p-2 whitespace-pre-wrap break-words"
        data-testid="factory-log-text"
      >{{ log.text }}</pre>
    </template>
  </section>
</template>
