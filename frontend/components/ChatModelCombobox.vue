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
        class="group inline-flex items-center gap-2 px-3 py-1.5 rounded-lg text-base text-fg-strong
               hover:bg-muted focus:outline-hidden focus:bg-muted transition-colors max-w-[420px]"
      >
        <span
          class="w-2 h-2 rounded-full shrink-0"
          :class="statusClass"
        />
        <span class="font-medium truncate">{{ current?.label ?? 'Select a model' }}</span>
        <span
          v-if="current"
          class="text-sm text-fg-muted truncate"
        >{{ current.sublabel }}</span>
        <ChevronDown class="w-4 h-4 text-fg-muted shrink-0" />
      </button>
    </PopoverTrigger>
    <!--
      Styling traced from Unsloth Studio's model picker via Chrome devtools:
      w-[440px] p-2 rounded-[10px] wrapper, space-y-2 between the pill-shaped
      search and a max-h-64 list scroller. Section headers use the tight 10px
      uppercase pattern; rows are flex with a rounded-[6px] hover/selected
      bg in #ececec (light) / #2e3035 (dark). Border override needed because
      jclaw's --border is invisible against --popover in dark mode (same
      gotcha as ChatContextMeter).
    -->
    <PopoverContent
      align="start"
      class="w-[440px] p-2 rounded-[10px] border-[#dfe7e3] dark:border-[#2e3035]"
    >
      <div class="space-y-2">
        <div class="relative">
          <Search class="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-muted-foreground pointer-events-none" />
          <!--
            Focus ring is emerald (not the default shadcn --ring which is
            near-black in light / light-grey in dark): matches Unsloth's
            branded green focus indicator on this same input. 3px ring at
            50% alpha mirrors their oklab(0.6929 -0.136 0.032 / 0.5) 0 0 0 3px.
          -->
          <input
            v-model="query"
            type="text"
            placeholder="Search models"
            aria-label="Search models"
            class="w-full h-9 pl-8 pr-3 rounded-3xl bg-input/30 border border-[#dfe7e3] dark:border-[#2e3035]
                   text-sm text-fg-primary placeholder:text-muted-foreground
                   focus-visible:border-emerald-500 focus-visible:ring-emerald-500/50 focus-visible:ring-[3px]
                   focus-visible:outline-none transition-colors"
          >
        </div>
        <div class="max-h-64 overflow-y-auto p-1">
          <div
            v-if="!Object.keys(filtered).length"
            class="px-3 py-6 text-center text-xs text-muted-foreground"
          >
            No matching models.
          </div>
          <template
            v-for="(groupRows, provider) in filtered"
            :key="provider"
          >
            <div class="flex items-center gap-1.5 px-2.5 py-1.5 text-[10px] font-semibold uppercase tracking-wider text-muted-foreground">
              {{ provider }}
            </div>
            <button
              v-for="row in groupRows"
              :key="`${row.provider}::${row.model}`"
              type="button"
              class="flex w-full items-center gap-2 rounded-[6px] px-2.5 py-1.5 text-left text-sm transition-colors
                     hover:bg-neutral-100 dark:hover:bg-[#2e3035]"
              :class="row.provider === current?.provider && row.model === current?.model
                ? 'bg-neutral-100 dark:bg-[#2e3035] text-fg-strong' : 'text-fg-primary'"
              @click="pick(row)"
            >
              <span class="block min-w-0 flex-1 truncate">{{ row.label }}</span>
              <span class="ml-auto flex items-center gap-1.5 shrink-0 text-muted-foreground truncate max-w-[160px]">
                {{ row.model }}
              </span>
            </button>
          </template>
        </div>
      </div>
    </PopoverContent>
  </Popover>
</template>
