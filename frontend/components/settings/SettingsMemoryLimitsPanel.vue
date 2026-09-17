<script setup lang="ts">
/**
 * How many memories reach the prompt.
 *
 * These two counts are the ONLY bounds on their blocks. The token budgets that used
 * to sit alongside them were removed (JCLAW-955, JCLAW-979) because they dropped
 * whichever memory happened to be verbose rather than whichever ranked lowest, and
 * did it silently. So what is set here decides the whole memory footprint of a turn,
 * which is why it belongs in Settings rather than in the config table alone.
 */
const { configValue, saveField, saving, resync } = useSettingsConfig()

/** Mirrors the code defaults in agents.SystemPromptAssembler. */
const MemoryLimitKeys = {
  coreMaxCount: 'memory.coreload.maxCount',
  recallLimit: 'memory.recall.limit',
} as const

const savedCoreMaxCount = computed(() => configValue(MemoryLimitKeys.coreMaxCount, '20'))
const savedRecallLimit = computed(() => configValue(MemoryLimitKeys.recallLimit, '10'))

const coreMaxCount = ref(savedCoreMaxCount.value)
const recallLimit = ref(savedRecallLimit.value)

const isDirty = computed(() =>
  coreMaxCount.value !== savedCoreMaxCount.value || recallLimit.value !== savedRecallLimit.value)

/** Both are counts of whole memories, so below 1 silently disables the block. */
const isValid = computed(() =>
  Number.isInteger(Number(coreMaxCount.value)) && Number(coreMaxCount.value) >= 1
  && Number.isInteger(Number(recallLimit.value)) && Number(recallLimit.value) >= 1)

const { saveError, attempt } = useSaveAttempt()

async function saveLimits() {
  if (!isDirty.value || !isValid.value) return
  const saved = await attempt(async () => {
    await saveField(MemoryLimitKeys.coreMaxCount, String(Number(coreMaxCount.value)))
    await saveField(MemoryLimitKeys.recallLimit, String(Number(recallLimit.value)))
  })
  // The two writes can half-land: show what was saved, not what was there before.
  if (!saved) await resync()
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
          >Both limits must be a whole number of at least 1.</span>
        </div>
      </div>
      <ApiErrorAlert
        :error="saveError"
        class="px-4 py-2.5 border-t border-border"
      />
    </div>
  </div>
</template>
