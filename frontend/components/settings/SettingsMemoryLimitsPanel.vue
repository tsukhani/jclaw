<script setup lang="ts">
/**
 * How many memories reach the prompt.
 *
 * These counts are the ONLY bounds on their blocks. The token budgets that used
 * to sit alongside them were removed (JCLAW-955, JCLAW-979) because they dropped
 * whichever memory happened to be verbose rather than whichever ranked lowest, and
 * did it silently. So what is set here decides the whole memory footprint of a turn,
 * which is why it belongs in Settings rather than in the config table alone.
 */
const { configValue, saveField, saving } = useSettingsConfig()

/** Mirrors the code defaults in agents.SystemPromptAssembler, memory.MemoryClusters and memory.MemoryAutoCapture. */
const MemoryLimitKeys = {
  coreMaxCount: 'memory.coreload.maxCount',
  recallLimit: 'memory.recall.limit',
  clusterMax: 'memory.recall.clusterMax',
  shortlistContainment: 'memory.consolidation.shortlist.minContainment',
} as const

const savedCoreMaxCount = computed(() => configValue(MemoryLimitKeys.coreMaxCount, '20'))
const savedRecallLimit = computed(() => configValue(MemoryLimitKeys.recallLimit, '10'))
const savedClusterMax = computed(() => configValue(MemoryLimitKeys.clusterMax, '10'))
const savedShortlistContainment = computed(() => configValue(MemoryLimitKeys.shortlistContainment, '0.3'))

const coreMaxCount = ref(savedCoreMaxCount.value)
const recallLimit = ref(savedRecallLimit.value)
const clusterMax = ref(savedClusterMax.value)
const shortlistContainment = ref(savedShortlistContainment.value)

const fields = [
  { key: MemoryLimitKeys.coreMaxCount, value: coreMaxCount, saved: savedCoreMaxCount },
  { key: MemoryLimitKeys.recallLimit, value: recallLimit, saved: savedRecallLimit },
  { key: MemoryLimitKeys.clusterMax, value: clusterMax, saved: savedClusterMax },
  { key: MemoryLimitKeys.shortlistContainment, value: shortlistContainment, saved: savedShortlistContainment },
]

const isDirty = computed(() => fields.some(f => String(f.value.value) !== f.saved.value))

const isCount = (value: string) => Number.isInteger(Number(value)) && Number(value) >= 1

/** The counts are whole memories, so below 1 silently disables a block; the overlap is a share of words. */
const isValid = computed(() =>
  isCount(String(coreMaxCount.value)) && isCount(String(recallLimit.value)) && isCount(String(clusterMax.value))
  && Number(shortlistContainment.value) > 0 && Number(shortlistContainment.value) <= 1)

const { saveError, attempt } = useSaveAttempt()

async function saveLimits() {
  if (!isDirty.value || !isValid.value) return
  // Only changed fields, so an unset key keeps following its code default.
  // saveField refreshes after each write, so one that lands before another fails still shows.
  await attempt(async () => {
    for (const f of fields) {
      if (String(f.value.value) !== f.saved.value) await saveField(f.key, String(Number(f.value.value)))
    }
  })
}
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Limits
    </h2>
    <p class="text-xs text-fg-muted">
      How many memories reach the prompt each turn. Core memories load on every turn regardless of
      what was said; recalled memories are matched against the current message. Both are counts of
      whole memories — a memory that is selected is always injected in full, never truncated.
    </p>

    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-3 space-y-3">
        <div>
          <label
            for="memory-core-max-count"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Core memories per turn</span>
            <input
              id="memory-core-max-count"
              v-model="coreMaxCount"
              type="number"
              min="1"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-core-max-count"
            >
          </label>
          <p class="mt-1 text-xs text-fg-muted">
            The highest-importance durable facts, always in context. Ordered by importance, so
            lowering this drops the least important first.
          </p>
        </div>

        <div>
          <label
            for="memory-recall-limit"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Recalled memories per turn</span>
            <input
              id="memory-recall-limit"
              v-model="recallLimit"
              type="number"
              min="1"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-recall-limit"
            >
          </label>
          <p class="mt-1 text-xs text-fg-muted">
            Matched against each message and ranked by relevance, importance and age. Lowering this
            drops the lowest-ranked first.
          </p>
        </div>

        <div>
          <label
            for="memory-cluster-max"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Linked memories recalled together</span>
            <input
              id="memory-cluster-max"
              v-model="clusterMax"
              type="number"
              min="1"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-cluster-max"
            >
          </label>
          <p class="mt-1 text-xs text-fg-muted">
            When a recalled memory is linked to others, such as a routine and the steps added to it later,
            they are recalled together, up to this many. They come on top of the recalled memories above.
          </p>
        </div>

        <div>
          <label
            for="memory-shortlist-containment"
            class="block"
          >
            <span class="block text-xs font-medium text-fg-strong mb-1">Consolidation overlap</span>
            <input
              id="memory-shortlist-containment"
              v-model="shortlistContainment"
              type="number"
              min="0"
              max="1"
              step="0.05"
              class="w-full px-2 py-1.5 text-sm bg-surface border border-input text-fg-strong"
              data-testid="memory-shortlist-containment"
            >
          </label>
          <p class="mt-1 text-xs text-fg-muted">
            When a new memory is saved, an existing one holding at least this share of its words is checked
            to see whether the new one replaces or extends it. Words found in most of an agent's memories,
            such as your name, are not counted.
          </p>
        </div>

        <div class="flex items-center gap-2">
          <button
            type="button"
            class="px-3 py-1.5 text-xs border border-border hover:bg-muted/40 transition-colors disabled:opacity-50"
            :disabled="!isDirty || !isValid || saving"
            data-testid="memory-limits-save"
            @click="saveLimits"
          >
            Save
          </button>
          <span
            v-if="!isValid"
            class="text-xs text-red-700 dark:text-red-400"
            data-testid="memory-limits-invalid"
          >Each count must be a whole number of at least 1, and the overlap above 0 and at most 1.</span>
        </div>
      </div>
      <ApiErrorAlert
        :error="saveError"
        class="px-4 py-2.5 border-t border-border"
      />
    </div>
  </div>
</template>
