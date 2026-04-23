<script setup lang="ts">
/**
 * Unsloth-style searchable model combobox. Trigger shows the current model
 * with a status dot and provider sublabel; clicking opens a popover with a
 * typeahead search and provider-grouped results.
 *
 * Keeps parity with the former `<select>` by emitting a single
 * `update:modelKey` string of the form `<provider>::<modelId>` so the parent
 * can route through the existing onModelChange handler unchanged.
 */
import { ChevronDown, Search } from 'lucide-vue-next'
import type { Provider } from '~/composables/useProviders'
import {
  Popover, PopoverContent, PopoverTrigger,
} from '~/components/ui/popover'

const props = defineProps<{
  providers: Provider[]
  modelKey: string
  /** Status dot colour. Defaults to the running-well emerald. */
  statusTone?: 'ok' | 'busy' | 'offline'
}>()

const emit = defineEmits<{
  (e: 'update:modelKey', key: string): void
}>()

const open = ref(false)
const query = ref('')

interface Row { provider: string, model: string, label: string, sublabel: string }

const rows = computed<Row[]>(() => {
  const out: Row[] = []
  for (const p of props.providers) {
    for (const m of p.models) {
      out.push({
        provider: p.name,
        model: m.id,
        label: m.name || m.id,
        sublabel: p.name,
      })
    }
  }
  return out
})

const filtered = computed<Record<string, Row[]>>(() => {
  const q = query.value.trim().toLowerCase()
  const matches = q
    ? rows.value.filter(r =>
        r.label.toLowerCase().includes(q)
        || r.model.toLowerCase().includes(q)
        || r.provider.toLowerCase().includes(q))
    : rows.value
  const groups: Record<string, Row[]> = {}
  for (const r of matches) {
    groups[r.provider] ??= []
    groups[r.provider]!.push(r)
  }
  return groups
})

const current = computed(() => {
  const [provider, model] = props.modelKey.split('::')
  return rows.value.find(r => r.provider === provider && r.model === model) ?? null
})

const statusClass = computed(() => {
  switch (props.statusTone) {
    case 'busy': return 'bg-amber-400'
    case 'offline': return 'bg-neutral-500'
    default: return 'bg-emerald-500'
  }
})

function pick(row: Row) {
  emit('update:modelKey', `${row.provider}::${row.model}`)
  open.value = false
  query.value = ''
}
</script>

<template>
  <Popover v-model:open="open">
    <PopoverTrigger as-child>
      <button
        type="button"
        class="group inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm text-fg-strong
               hover:bg-muted focus:outline-hidden focus:bg-muted transition-colors max-w-[420px]"
      >
        <span
          class="w-2 h-2 rounded-full shrink-0"
          :class="statusClass"
        />
        <span class="font-medium truncate">{{ current?.label ?? 'Select a model' }}</span>
        <span
          v-if="current"
          class="text-xs text-fg-muted truncate"
        >{{ current.sublabel }}</span>
        <ChevronDown class="w-3.5 h-3.5 text-fg-muted shrink-0" />
      </button>
    </PopoverTrigger>
    <PopoverContent
      align="start"
      class="w-[420px] p-0 bg-surface-elevated border-border"
    >
      <div class="relative border-b border-border">
        <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-fg-muted" />
        <input
          v-model="query"
          type="text"
          placeholder="Search models"
          aria-label="Search models"
          class="w-full pl-9 pr-3 py-2.5 bg-transparent text-sm text-fg-strong placeholder:text-fg-muted
                 focus:outline-hidden"
        >
      </div>
      <div class="max-h-80 overflow-y-auto py-1">
        <div
          v-if="!Object.keys(filtered).length"
          class="px-3 py-6 text-center text-xs text-fg-muted"
        >
          No matching models.
        </div>
        <template
          v-for="(groupRows, provider) in filtered"
          :key="provider"
        >
          <div class="px-3 pt-2 pb-1 text-[10px] font-semibold tracking-wider text-fg-muted uppercase">
            {{ provider }}
          </div>
          <button
            v-for="row in groupRows"
            :key="`${row.provider}::${row.model}`"
            type="button"
            class="w-full flex items-center justify-between gap-3 px-3 py-2 text-left text-sm
                   hover:bg-muted transition-colors"
            :class="[
              row.provider === current?.provider && row.model === current?.model
                ? 'bg-muted text-fg-strong' : 'text-fg-primary',
            ]"
            @click="pick(row)"
          >
            <span class="truncate">{{ row.label }}</span>
            <span class="text-[10px] text-fg-muted shrink-0 font-mono truncate max-w-[160px]">
              {{ row.model }}
            </span>
          </button>
        </template>
      </div>
    </PopoverContent>
  </Popover>
</template>
