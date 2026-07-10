<script setup lang="ts">
import { PlusIcon, TrashIcon } from '@heroicons/vue/24/outline'

// Per-logger runtime level overrides. Each (logger, level) pair applies live
// via log4j2 and is persisted so it survives a restart and overrides the
// log4j2.xml / application.conf baseline. Managed only here — the backend
// reserves the logging.level.* prefix from the generic Config API, so these
// never appear as raw config rows (and their level value is never masked).
const { saving } = useSettingsConfig()

interface LoggerLevelEntry { logger: string, level: string }
interface LoggerLevelsResponse {
  entries: LoggerLevelEntry[]
  validLevels: string[]
  knownLoggers: string[]
}
const { data: loggingData, refresh: refreshLogging }
  = await useFetch<LoggerLevelsResponse>('/api/logging/levels')

const loggerLevels = computed(() => loggingData.value?.entries ?? [])
const logLevelOptions = computed(() => loggingData.value?.validLevels ?? [])
// Loggers the backend currently knows about (instantiated + file-configured).
// Drives the add-field autocomplete and the typo hint below. A snapshot — a
// logger only shows up once its class has logged, so an unknown name is a
// warning, not an error (you may be pre-setting a dormant logger).
const knownLoggers = computed(() => loggingData.value?.knownLoggers ?? [])
const newLoggerName = ref('')
const newLoggerLevel = ref('DEBUG')
const loggingError = ref<string | null>(null)

const newLoggerUnknown = computed(() => {
  const n = newLoggerName.value.trim()
  return n.length > 0 && n.toLowerCase() !== 'root' && !knownLoggers.value.includes(n)
})

// The backend returns the rejection text as the response body on a 400
// (e.g. an invalid level); surface it rather than a generic "fetch failed".
function logLevelErrorMessage(e: unknown): string {
  if (e && typeof e === 'object' && 'data' in e) {
    const data = (e as { data?: unknown }).data
    if (typeof data === 'string' && data.trim()) return data
  }
  return e instanceof Error ? e.message : 'Request failed'
}

async function addLoggerLevel() {
  const logger = newLoggerName.value.trim()
  if (!logger) return
  saving.value = true
  loggingError.value = null
  try {
    await $fetch('/api/logging/levels', {
      method: 'POST',
      body: { logger, level: newLoggerLevel.value },
    })
    newLoggerName.value = ''
    refreshLogging()
  }
  catch (e) {
    loggingError.value = logLevelErrorMessage(e)
  }
  finally {
    saving.value = false
  }
}

async function updateLoggerLevel(logger: string, level: string) {
  saving.value = true
  loggingError.value = null
  try {
    await $fetch('/api/logging/levels', { method: 'POST', body: { logger, level } })
    refreshLogging()
  }
  catch (e) {
    loggingError.value = logLevelErrorMessage(e)
  }
  finally {
    saving.value = false
  }
}

async function deleteLoggerLevel(logger: string) {
  saving.value = true
  loggingError.value = null
  try {
    await $fetch(`/api/logging/levels/${encodeURIComponent(logger)}`, { method: 'DELETE' })
    refreshLogging()
  }
  catch (e) {
    loggingError.value = logLevelErrorMessage(e)
  }
  finally {
    saving.value = false
  }
}
</script>

<template>
  <!-- Logging Levels: per-logger runtime level overrides. -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Logging Levels
    </h2>
    <p class="text-xs text-fg-muted">
      Override the log level for a specific logger — a single class
      (<span class="font-mono">controllers.ApiChatController</span>) or a whole
      tree (<span class="font-mono">play</span>). Changes apply immediately, are
      saved, and survive a restart — overriding the levels in
      <span class="font-mono">log4j2.xml</span> and
      <span class="font-mono">application.conf</span>. Use
      <span class="font-mono">root</span> for the global level. Deleting an entry
      reverts that logger to its inherited level.
    </p>

    <div class="bg-surface-elevated border border-border">
      <!-- Add row -->
      <div class="px-4 py-2.5 flex items-center gap-3 border-b border-border">
        <input
          v-model="newLoggerName"
          type="text"
          list="logging-logger-suggestions"
          placeholder="logger (e.g. play or controllers.ApiChatController)"
          aria-label="Logger name"
          class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
          @keyup.enter="addLoggerLevel"
        >
        <datalist id="logging-logger-suggestions">
          <option
            v-for="name in knownLoggers"
            :key="name"
            :value="name"
          />
        </datalist>
        <select
          v-model="newLoggerLevel"
          aria-label="New logger level"
          class="px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
        >
          <option
            v-for="lvl in logLevelOptions"
            :key="lvl"
            :value="lvl"
          >
            {{ lvl }}
          </option>
        </select>
        <button
          class="shrink-0 p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
          title="Add override"
          :disabled="saving || !newLoggerName.trim()"
          @click="addLoggerLevel"
        >
          <PlusIcon
            class="w-4 h-4"
            aria-hidden="true"
          />
        </button>
      </div>

      <!-- Existing overrides -->
      <div
        v-if="loggerLevels.length"
        class="divide-y divide-border"
      >
        <div
          v-for="entry in loggerLevels"
          :key="entry.logger"
          class="px-4 py-2.5 flex items-center gap-3"
        >
          <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ entry.logger }}</span>
          <select
            :value="entry.level"
            :aria-label="`Level for ${entry.logger}`"
            class="px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            @change="updateLoggerLevel(entry.logger, ($event.target as HTMLSelectElement).value)"
          >
            <option
              v-for="lvl in logLevelOptions"
              :key="lvl"
              :value="lvl"
            >
              {{ lvl }}
            </option>
          </select>
          <button
            class="p-1 text-fg-muted hover:text-red-600 dark:hover:text-red-400 transition-colors disabled:opacity-40"
            :title="`Delete override for ${entry.logger}`"
            :disabled="saving"
            @click="deleteLoggerLevel(entry.logger)"
          >
            <TrashIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
      <div
        v-else
        class="px-4 py-2.5 text-xs text-fg-muted"
      >
        No overrides — every logger uses its configured level.
      </div>
    </div>

    <p
      v-if="newLoggerUnknown"
      class="text-xs text-amber-700 dark:text-amber-400"
    >
      No logger named <span class="font-mono">{{ newLoggerName.trim() }}</span> has
      logged yet — double-check the spelling, or add it anyway to pre-set a logger
      that hasn't run.
    </p>
    <p
      v-if="loggingError"
      class="text-xs text-red-700 dark:text-red-400"
    >
      {{ loggingError }}
    </p>
  </div>
</template>
