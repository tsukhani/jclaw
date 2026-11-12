<script setup lang="ts">
// One prompt card (JCLAW-813): title, category badge, truncated preview, tag
// chips, and the Copy / Edit / Delete / Run actions. Copy is handled locally
// (transient icon swap); the rest are emitted for the page to handle.
import { CheckIcon, ClipboardIcon, PencilIcon, PlayIcon, TrashIcon } from '@heroicons/vue/24/outline'
import type { Prompt } from '~/types/api'

const props = defineProps<{ prompt: Prompt }>()
const emit = defineEmits<{ (e: 'edit' | 'delete' | 'run'): void }>()

const tagList = computed(() =>
  (props.prompt.tags ?? '').split(',').map(t => t.trim()).filter(Boolean))

const copied = ref(false)
async function copy() {
  try {
    await navigator.clipboard.writeText(props.prompt.content)
    copied.value = true
    setTimeout(() => {
      copied.value = false
    }, 1500)
  }
  catch {
    // Clipboard unavailable (insecure context / denied) — no-op.
  }
}
</script>

<template>
  <div
    class="flex flex-col border border-border rounded-lg bg-surface-elevated p-4 gap-2"
    :data-testid="`prompt-card-${prompt.id}`"
  >
    <div class="flex items-start justify-between gap-2">
      <h3 class="text-sm font-medium text-fg-strong truncate">
        {{ prompt.title }}
      </h3>
      <span class="shrink-0 inline-flex items-center gap-1 text-[11px] px-1.5 py-0.5 rounded-full bg-muted text-fg-muted whitespace-nowrap">
        <component
          :is="promptCategoryIcon(prompt.category)"
          class="w-3 h-3"
          aria-hidden="true"
        />
        {{ prompt.categoryLabel }}
      </span>
    </div>
    <p class="text-xs text-fg-muted line-clamp-3 whitespace-pre-wrap">
      {{ prompt.content }}
    </p>
    <div
      v-if="tagList.length"
      class="flex flex-wrap gap-1"
    >
      <span
        v-for="t in tagList"
        :key="t"
        class="text-[10px] px-1.5 py-0.5 rounded bg-muted text-fg-muted"
      >#{{ t }}</span>
    </div>
    <div class="flex items-center gap-1 mt-auto pt-1">
      <button
        type="button"
        title="Copy prompt text"
        :aria-label="`Copy ${prompt.title}`"
        :data-testid="`prompt-copy-${prompt.id}`"
        class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
        @click="copy"
      >
        <component
          :is="copied ? CheckIcon : ClipboardIcon"
          class="w-4 h-4"
          aria-hidden="true"
        />
      </button>
      <button
        type="button"
        title="Edit"
        :aria-label="`Edit ${prompt.title}`"
        :data-testid="`prompt-edit-${prompt.id}`"
        class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
        @click="emit('edit')"
      >
        <PencilIcon
          class="w-4 h-4"
          aria-hidden="true"
        />
      </button>
      <button
        type="button"
        title="Delete"
        :aria-label="`Delete ${prompt.title}`"
        :data-testid="`prompt-delete-${prompt.id}`"
        class="p-1.5 text-fg-muted hover:text-red-600 dark:hover:text-red-400 transition-colors"
        @click="emit('delete')"
      >
        <TrashIcon
          class="w-4 h-4"
          aria-hidden="true"
        />
      </button>
      <button
        type="button"
        title="Run in chat"
        :aria-label="`Run ${prompt.title} in chat`"
        :data-testid="`prompt-run-${prompt.id}`"
        class="ml-auto inline-flex items-center gap-1 px-2 py-1 text-xs font-medium text-emerald-700 dark:text-emerald-400 border border-emerald-600/40 rounded hover:bg-emerald-50 dark:hover:bg-emerald-900/20 transition-colors"
        @click="emit('run')"
      >
        <PlayIcon
          class="w-3.5 h-3.5"
          aria-hidden="true"
        />
        Run
      </button>
    </div>
  </div>
</template>
