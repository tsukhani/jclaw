<script setup lang="ts">
// Instance restart panel. Hands off to `jclaw.sh restart` via
// POST /api/system/restart, then watches the backend go down and come back
// (useRestartAndReconnect, which the Java runtime panel shares).
//
// The preflight is deliberately re-fetched on click rather than trusted from
// mount: the in-flight counts it reports are what the operator confirms
// against, and a panel left open for ten minutes would be confirming against
// a stale picture of what the restart is about to interrupt.
import type { RestartPreflight } from '~/composables/useRestartAndReconnect'
import { interruptionSummary } from '~/composables/useRestartAndReconnect'

const { confirm } = useConfirm()
const { mutate, error: mutationError } = useApiMutation()
const { failure, busy, statusLine, restart } = useRestartAndReconnect()

// useLazyFetch, not useFetch: a top-level `await` here suspends the whole
// settings panel behind this request on a cold boot.
const { data: preflight, refresh } = useLazyFetch<RestartPreflight>('/api/system/restart')

function durationHint(p: RestartPreflight): string {
  if (p.rebuildExpected) {
    // Names the driver rather than asserting a duration, because the slow case
    // has never been measured. Two timed restarts on a developer clone: 48s
    // with both steps skipped, 58s with a full SPA rebuild — so the SPA is
    // worth ~10s, not minutes. Both logged compileJava UP-TO-DATE, leaving a
    // cold Java recompile the one step that could plausibly take much longer
    // and the one step still unmeasured. Don't put a number here until it is.
    return 'This is a source checkout — the restart may recompile Java sources and '
      + 'rebuild the SPA, and skips both when nothing changed. Usually well under a '
      + 'minute; a cold Java recompile takes longer.'
  }
  return p.backendOnly
    ? 'The backend will be unavailable for roughly 30–60 seconds. The dev server stays up.'
    : 'JClaw will be unavailable for roughly 30–60 seconds.'
}

// jclaw.sh decides at start whether the cache must be rebuilt, so the preflight cannot know; say what it costs.
function aotHint(p: RestartPreflight): string {
  return p.aotCacheEnabled ? 'A restart that has to rebuild the AOT cache takes about 30 seconds longer. ' : ''
}

async function handleRestart() {
  failure.value = null

  // Fresh counts — see the note at the top of this file.
  await refresh()
  const p = preflight.value
  if (!p || !p.available) return

  const ok = await confirm({
    title: 'Restart JClaw',
    message: `${interruptionSummary(p)} ${durationHint(p)} ${aotHint(p)}`
      + 'This page reconnects automatically once the backend is back.',
    confirmText: 'Restart',
    variant: 'danger',
  })
  if (!ok) return

  await restart(p)
}

// jclaw.sh reads the switch before it launches the JVM, so it applies from the next start.
const aotSaving = ref(false)
const aotEnabled = computed(() => preflight.value?.aotCacheEnabled ?? true)
const aotStatusAt = computed(() => {
  const at = preflight.value?.aotCacheStatusAt
  return at ? new Date(at).toLocaleString() : null
})

async function toggleAotCache() {
  if (!preflight.value || aotSaving.value) return
  aotSaving.value = true
  failure.value = null
  const res = await mutate('/api/system/aot-cache', { method: 'POST', body: { enabled: !aotEnabled.value } })
  if (!res) {
    failure.value = mutationError.value ?? 'The AOT cache setting was not saved.'
  }
  await refresh()
  aotSaving.value = false
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Restart
    </h2>
    <p class="text-xs text-fg-muted">
      Stops this instance and starts it again via <code>jclaw.sh restart</code>. In-flight chat
      streams, running task runs and active subagent runs are interrupted. This page reconnects
      on its own once the backend answers again.
    </p>

    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Restart JClaw</span>
          <div class="text-xs text-fg-muted mt-0.5">
            <template v-if="preflight && !preflight.available">
              {{ preflight.unavailableReason }}
            </template>
            <template v-else-if="preflight">
              Mode: {{ preflight.mode }}<template v-if="preflight.backendOnly">
                — backend only; the Nuxt dev server keeps running
              </template>
              <template v-if="preflight.rebuildExpected">
                — source checkout, so this may recompile first
              </template>
            </template>
            <template v-else>
              Checking restart availability…
            </template>
          </div>
        </div>
        <button
          :disabled="busy || !preflight?.available"
          class="shrink-0 px-3 py-1.5 text-xs font-medium text-white
                 bg-red-600 hover:bg-red-700 disabled:bg-red-600/40
                 disabled:cursor-not-allowed rounded-full transition-colors"
          @click="handleRestart"
        >
          {{ busy ? 'Restarting…' : 'Restart' }}
        </button>
      </div>

      <div class="px-4 py-2.5 border-t border-border flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span
            id="aot-cache-label"
            class="text-sm font-medium text-fg-strong"
          >AOT cache</span>
          <div class="text-xs text-fg-muted mt-0.5">
            Starts JClaw from a cache of the Java classes it loads, about a second faster. After an
            upgrade, or when Java or the JVM settings change, the next start first runs JClaw once to
            build a new cache. Takes effect at the next start or restart.
          </div>
          <div
            v-if="preflight?.aotCacheStatus"
            class="text-xs text-fg-muted mt-0.5"
            data-testid="aot-cache-status"
          >
            Last start: {{ preflight.aotCacheStatus }}<template v-if="aotStatusAt">
              ({{ aotStatusAt }})
            </template>
          </div>
        </div>
        <button
          role="switch"
          :aria-checked="aotEnabled"
          aria-labelledby="aot-cache-label"
          :title="aotEnabled ? 'Start without the AOT cache' : 'Start from the AOT cache'"
          :disabled="!preflight || busy || aotSaving"
          :class="[
            'relative w-9 h-5 shrink-0 rounded-full transition-colors',
            aotEnabled
              ? 'bg-emerald-600 hover:bg-emerald-700 dark:hover:bg-emerald-600'
              : 'bg-muted hover:bg-muted ring-1 ring-inset ring-input dark:ring-0',
            !preflight || busy || aotSaving ? 'cursor-not-allowed opacity-60' : 'cursor-pointer',
          ]"
          data-testid="aot-cache-toggle"
          @click="toggleAotCache"
        >
          <span
            :class="aotEnabled ? 'translate-x-4' : 'translate-x-0.5'"
            class="block w-4 h-4 bg-white rounded-full transition-transform"
          />
        </button>
      </div>

      <div
        v-if="statusLine || failure"
        class="px-4 py-2.5 border-t border-border text-xs"
        role="status"
        aria-live="polite"
      >
        <span
          v-if="failure"
          class="text-red-600 dark:text-red-400"
        >{{ failure }}</span>
        <span
          v-else
          class="text-fg-muted"
        >{{ statusLine }}</span>
      </div>
    </div>
  </div>
</template>
