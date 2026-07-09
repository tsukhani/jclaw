<script setup lang="ts">
import { CheckIcon, PencilIcon, XMarkIcon } from '@heroicons/vue/24/outline'

// LLM dispatcher caps — outbound concurrency tuning. Defaults seeded by
// DefaultConfigJob using clamp(8 * cores, 64, 256) per host (total = 2×);
// live-applied via ConfigService side-effect, no restart required. Bumped
// transiently during loadtest when --concurrency exceeds the live cap.
const { configValue, saveField } = useSettingsConfig()

const dispatcherMaxRequestsPerHost = computed(() =>
  configValue('dispatcher.llm.maxRequestsPerHost', '64'),
)
const dispatcherMaxRequests = computed(() =>
  configValue('dispatcher.llm.maxRequests', '128'),
)

const editingPerfField = ref<string | null>(null)
const perfFieldEdit = ref('')

async function savePerfField(configKey: string, value: string) {
  await saveField(configKey, value)
  editingPerfField.value = null
}
</script>

<template>
  <!-- Performance: LLM dispatcher caps -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Performance
    </h2>
    <p class="text-xs text-fg-muted">
      OkHttp dispatcher concurrency caps for outbound LLM calls.
      <span class="font-mono">maxRequestsPerHost</span> bounds in-flight calls to a
      single provider; <span class="font-mono">maxRequests</span> bounds the total
      across all providers. Auto-tuned at first start to
      <span class="font-mono">clamp(8 × cores, 64, 256)</span> per host with total set
      to <span class="font-mono">2×</span> that. Changes apply live; no restart needed.
      Transiently bumped during loadtest when <span class="font-mono">--concurrency</span>
      would otherwise saturate.
    </p>
    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0">
            maxRequestsPerHost
          </span>
          <template v-if="editingPerfField === 'maxRequestsPerHost'">
            <input
              v-model="perfFieldEdit"
              type="number"
              min="1"
              max="1024"
              aria-label="Max requests per host"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="savePerfField('dispatcher.llm.maxRequestsPerHost', perfFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingPerfField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ dispatcherMaxRequestsPerHost }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingPerfField = 'maxRequestsPerHost'; perfFieldEdit = dispatcherMaxRequestsPerHost"
            >
              <PencilIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
        </div>
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0">
            maxRequests
          </span>
          <template v-if="editingPerfField === 'maxRequests'">
            <input
              v-model="perfFieldEdit"
              type="number"
              min="1"
              max="2048"
              aria-label="Max requests total"
              class="w-24 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
            <button
              class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
              title="Save"
              @click="savePerfField('dispatcher.llm.maxRequests', perfFieldEdit)"
            >
              <CheckIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Cancel"
              @click="editingPerfField = null"
            >
              <XMarkIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono">{{ dispatcherMaxRequests }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              title="Edit"
              @click="editingPerfField = 'maxRequests'; perfFieldEdit = dispatcherMaxRequests"
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
  </div>
</template>
