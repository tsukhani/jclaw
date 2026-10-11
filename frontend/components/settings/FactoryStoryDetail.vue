<script setup lang="ts">
import type { ApiErrorDetails } from '~/types/api'
import type { BoardStory, LogView } from './factory-board'

const POLL_MS = 3_000

const props = defineProps<{ story: BoardStory }>()

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

// A hidden page pauses the status poll too, so the story would never leave `running` there.
function onVisibility() {
  if (document.visibilityState !== 'visible') {
    stopPolling()
  }
  else if (props.story.state === 'running') {
    load()
    startPolling()
  }
}

onMounted(() => document.addEventListener('visibilitychange', onVisibility))

onBeforeUnmount(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', onVisibility)
})
</script>

<template>
  <div
    class="space-y-3"
    data-testid="factory-story-view"
  >
    <p
      v-if="story.logs.length === 0"
      class="text-xs text-fg-muted"
    >
      No logs yet.
    </p>
    <template v-else>
      <div class="sticky top-0 bg-background flex flex-wrap gap-1 pb-2">
        <button
          v-for="f in story.logs"
          :key="f"
          type="button"
          :aria-pressed="tab === f"
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
        class="text-xs font-mono bg-muted p-2 whitespace-pre-wrap break-words"
        data-testid="factory-log-text"
      >{{ log.text }}</pre>
    </template>
  </div>
</template>
