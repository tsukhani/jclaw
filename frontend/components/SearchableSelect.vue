<script setup lang="ts">
/**
 * A select whose options narrow as the operator types, for lists too long to scroll: every
 * provider's models together run to hundreds. Command filters on the text each option renders,
 * so typing finds exactly what the operator reads.
 */
import { ChevronDown } from '@lucide/vue'
import {
  Command, CommandEmpty, CommandGroup, CommandInput, CommandItem, CommandList,
} from '~/components/ui/command'
import { Popover, PopoverContent, PopoverTrigger } from '~/components/ui/popover'

export interface SearchableOption {
  value: string
  label: string
  /** Muted text after the label, which the filter matches too: a model's id beside its display name. */
  detail?: string
  /** Options sharing a group are listed under it, in the order the group first appears. */
  group?: string
  disabled?: boolean
}

// Class and test ids belong on the trigger, the one element this renders in place.
defineOptions({ inheritAttrs: false })

const props = withDefaults(defineProps<{
  options: SearchableOption[]
  /** The selected value; one no option holds shows the placeholder. */
  modelValue?: string
  placeholder: string
  /** The field's accessible name. */
  label: string
  disabled?: boolean
  searchLabel?: string
  emptyText?: string
  /** Classes for the popover, which is teleported and inherits nothing from the trigger's place. */
  contentClass?: string
}>(), {
  modelValue: '',
  disabled: false,
  searchLabel: 'Type to filter',
  emptyText: 'No matches.',
  contentClass: '',
})

const emit = defineEmits<(e: 'update:modelValue', value: string) => void>()

const open = ref(false)

const selected = computed(() => props.options.find(o => o.value === props.modelValue) ?? null)

const groups = computed(() => {
  const byGroup = new Map<string, SearchableOption[]>()
  for (const o of props.options) {
    const key = o.group ?? ''
    if (!byGroup.has(key)) byGroup.set(key, [])
    byGroup.get(key)!.push(o)
  }
  return [...byGroup]
})

function pick(option: SearchableOption) {
  // reka emits select for a disabled item too, and pointer-events-none stops only the mouse,
  // not a screen reader's activation.
  if (option.disabled) return
  open.value = false
  emit('update:modelValue', option.value)
}
</script>

<template>
  <Popover v-model:open="open">
    <PopoverTrigger as-child>
      <button
        v-bind="$attrs"
        type="button"
        :aria-label="selected ? `${label}: ${selected.label}` : label"
        :disabled="disabled"
        class="flex items-center justify-between gap-2 text-left disabled:opacity-50"
      >
        <span class="min-w-0 truncate">{{ selected?.label ?? placeholder }}</span>
        <ChevronDown
          class="w-4 h-4 shrink-0 opacity-60"
          aria-hidden="true"
        />
      </button>
    </PopoverTrigger>
    <PopoverContent
      align="start"
      :class="['p-0 w-(--reka-popover-trigger-width) min-w-72 max-w-[calc(100vw-2rem)]', contentClass]"
    >
      <Command :model-value="modelValue">
        <CommandInput
          :placeholder="searchLabel"
          :aria-label="searchLabel"
        />
        <CommandList>
          <CommandEmpty>{{ emptyText }}</CommandEmpty>
          <CommandGroup
            v-for="[group, items] in groups"
            :key="group"
            :heading="group || undefined"
          >
            <CommandItem
              v-for="o in items"
              :key="o.value"
              :value="o.value"
              :disabled="o.disabled"
              class="[overflow-wrap:anywhere] data-[highlighted]:outline-solid data-[highlighted]:outline-2 data-[highlighted]:-outline-offset-2 data-[highlighted]:outline-ring"
              @select="pick(o)"
            >
              <!-- The highlight's background alone measured 1.03:1 against the popover in dark mode: the outline is what a keyboard user sees. -->
              <span class="min-w-0 flex-1">{{ o.label }}</span>
              <span
                v-if="o.detail"
                class="min-w-0 text-xs text-muted-foreground"
              >{{ o.detail }}</span>
            </CommandItem>
          </CommandGroup>
        </CommandList>
      </Command>
    </PopoverContent>
  </Popover>
</template>
