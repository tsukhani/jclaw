<script setup lang="ts">
// Telemetry settings panel (JCLAW-34). The otel.* keys are ordinary config rows —
// saved through /api/config like every other setting — and the runtime re-applies
// them on each write, so nothing here needs a restart. /api/telemetry is the
// runtime's own view of the result: whether it is exporting, where to, and the
// last export failure.
import {
  CheckIcon,
  PencilIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'

const { configData, saving, refresh } = useSettingsConfig()

const KEYS = {
  enabled: 'otel.enabled',
  endpoint: 'otel.exporter.endpoint',
  protocol: 'otel.exporter.protocol',
  headers: 'otel.exporter.secretHeaders',
  service: 'otel.service.name',
  ratio: 'otel.traces.sampler.ratio',
} as const

type Field = 'endpoint' | 'protocol' | 'headers' | 'service' | 'ratio'

interface TelemetryStatus {
  initialized: boolean
  enabled: boolean
  exporting: boolean
  endpoint: string
  protocol: string
  serviceName: string
  samplerRatio: number
  agentAttached: boolean
  lastExportError: string | null
}

interface TestResult {
  delivered: boolean
  traceId: string
  error: string | null
}

const otel = computed(() => {
  const map = new Map<string, string>()
  for (const e of configData.value?.entries ?? []) {
    if (e.key.startsWith('otel.')) map.set(e.key, e.value)
  }
  return {
    enabled: map.get(KEYS.enabled) === 'true',
    endpoint: map.get(KEYS.endpoint) ?? 'http://localhost:4318',
    protocol: map.get(KEYS.protocol) ?? 'http/protobuf',
    headers: map.get(KEYS.headers) ?? '',
    service: map.get(KEYS.service) ?? 'jclaw',
    ratio: map.get(KEYS.ratio) ?? '1.0',
  }
})

const { data: status, refresh: refreshStatus } = useLazyFetch<TelemetryStatus>('/api/telemetry')

const editing = ref<Field | null>(null)
const draft = ref('')
const saveError = ref<string | null>(null)
const testing = ref(false)
const testResult = ref<TestResult | null>(null)

function startEdit(field: Field) {
  editing.value = field
  saveError.value = null
  // The stored headers come back masked, so the editor starts empty: what is typed replaces them.
  draft.value = field === 'headers' ? '' : otel.value[field]
}

function messageOf(e: unknown): string {
  // The API answers a refused write with 403 {error}; a transport-level failure carries {message}.
  const data = (e as { data?: { error?: string, message?: string } })?.data
  return data?.error ?? data?.message ?? (e instanceof Error ? e.message : 'Save failed')
}

async function save(field: Field) {
  const key = KEYS[field]
  const value = draft.value.trim()
  saving.value = true
  saveError.value = null
  try {
    if (field === 'headers' && value === '') {
      await $fetch(`/api/config/${key}`, { method: 'DELETE' })
    }
    else {
      await $fetch('/api/config', { method: 'POST', body: { key, value } })
    }
    editing.value = null
    testResult.value = null
    await Promise.all([refresh(), refreshStatus()])
  }
  catch (e) {
    saveError.value = messageOf(e)
  }
  finally {
    saving.value = false
  }
}

async function toggleEnabled(event: Event) {
  const on = (event.target as HTMLInputElement).checked
  saving.value = true
  saveError.value = null
  try {
    await $fetch('/api/config', { method: 'POST', body: { key: KEYS.enabled, value: on ? 'true' : 'false' } })
    testResult.value = null
    await Promise.all([refresh(), refreshStatus()])
  }
  catch (e) {
    saveError.value = messageOf(e)
  }
  finally {
    saving.value = false
  }
}

async function sendTestSpan() {
  testing.value = true
  testResult.value = null
  try {
    testResult.value = await $fetch<TestResult>('/api/telemetry/test', { method: 'POST' })
    await refreshStatus()
  }
  catch (e) {
    testResult.value = { delivered: false, traceId: '', error: messageOf(e) }
  }
  finally {
    testing.value = false
  }
}

const rows: { field: Field, label: string, hint: string }[] = [
  { field: 'endpoint', label: 'exporter.endpoint', hint: 'Collector base URL; /v1/traces and /v1/metrics are appended for http/protobuf' },
  { field: 'protocol', label: 'exporter.protocol', hint: 'http/protobuf or grpc' },
  { field: 'headers', label: 'exporter.secretHeaders', hint: 'name=value,name=value sent with every export — vendor auth goes here; leave empty to clear' },
  { field: 'service', label: 'service.name', hint: 'How this instance is named in the collector' },
  { field: 'ratio', label: 'traces.sampler.ratio', hint: 'Share of requests recorded, 0 to 1' },
]
</script>

<template>
  <!-- Telemetry -->
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Telemetry
    </h2>
    <p class="text-xs text-fg-muted">
      Export traces and metrics to an OpenTelemetry collector over OTLP. Off by
      default; nothing leaves this instance until you turn it on. Changes apply
      immediately, including the collector address.
    </p>

    <div class="bg-surface-elevated border border-border">
      <div class="divide-y divide-border">
        <!-- Enabled -->
        <div class="px-4 py-2.5 flex items-center gap-3">
          <span class="text-xs font-mono text-fg-muted w-48 shrink-0">enabled</span>
          <label
            for="otel-enabled"
            class="flex items-center gap-2 text-sm text-fg-primary"
          >
            <input
              id="otel-enabled"
              type="checkbox"
              :checked="otel.enabled"
              :disabled="saving || status?.agentAttached"
              aria-label="Export telemetry"
              @change="toggleEnabled"
            >
            <span>{{ otel.enabled ? 'Exporting' : 'Off' }}</span>
          </label>
          <span
            v-if="status"
            class="ml-auto text-xs text-fg-muted truncate"
          >
            <template v-if="status.agentAttached">
              Java agent attached — its OTEL_* settings apply; these keys are read at start
            </template>
            <template v-else-if="status.exporting">
              → {{ status.endpoint }} ({{ status.protocol }})
            </template>
            <template v-else>
              not exporting
            </template>
          </span>
        </div>

        <!-- Editable keys -->
        <div
          v-for="row in rows"
          :key="row.field"
          class="px-4 py-2.5 flex items-center gap-3"
        >
          <span
            class="text-xs font-mono text-fg-muted w-48 shrink-0"
            :title="row.hint"
          >{{ row.label }}</span>
          <template v-if="editing === row.field">
            <select
              v-if="row.field === 'protocol'"
              v-model="draft"
              :aria-label="row.label"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
            >
              <option value="http/protobuf">
                http/protobuf
              </option>
              <option value="grpc">
                grpc
              </option>
            </select>
            <input
              v-else
              v-model="draft"
              :type="row.field === 'headers' ? 'password' : 'text'"
              :placeholder="row.field === 'headers' ? 'Authorization=Bearer …' : ''"
              :aria-label="row.label"
              class="flex-1 px-2 py-1 bg-muted border border-input text-sm text-fg-strong font-mono focus:outline-hidden"
              @keydown.enter="save(row.field)"
              @keydown.escape="editing = null"
            >
            <div class="flex gap-1">
              <button
                class="p-1 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 transition-colors"
                title="Save"
                :disabled="saving"
                @click="save(row.field)"
              >
                <CheckIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
              <button
                class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
                title="Cancel"
                @click="editing = null"
              >
                <XMarkIcon
                  class="w-3.5 h-3.5"
                  aria-hidden="true"
                />
              </button>
            </div>
          </template>
          <template v-else>
            <span class="flex-1 text-sm text-fg-primary font-mono truncate">{{ otel[row.field] || '(not set)' }}</span>
            <button
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              :title="`Edit ${row.label}`"
              @click="startEdit(row.field)"
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

    <!-- Delivery check -->
    <div class="flex items-center gap-3">
      <button
        class="px-3 py-1.5 text-sm border border-border bg-surface-elevated text-fg-primary hover:bg-muted transition-colors disabled:opacity-50"
        :disabled="testing || saving"
        @click="sendTestSpan"
      >
        {{ testing ? 'Sending…' : 'Send test span' }}
      </button>
      <span
        v-if="testResult"
        class="text-xs"
        :class="testResult.delivered ? 'text-emerald-700 dark:text-emerald-400' : 'text-red-700 dark:text-red-400'"
        role="status"
      >
        <template v-if="testResult.delivered">
          Delivered — trace {{ testResult.traceId }}
        </template>
        <template v-else>
          Not delivered: {{ testResult.error }}
        </template>
      </span>
      <span
        v-else-if="status?.lastExportError"
        class="text-xs text-red-700 dark:text-red-400"
      >
        Last export failed: {{ status.lastExportError }}
      </span>
    </div>
  </div>
</template>
