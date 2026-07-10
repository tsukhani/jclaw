<script setup lang="ts">
import { ArrowDownTrayIcon, PhotoIcon, TrashIcon } from '@heroicons/vue/24/outline'
import type { MessageAttachment } from '~/types/api'
import { formatSize } from '~/utils/format'
import { generatedImageLabel } from '~/utils/generated-attachment'

// JCLAW-227/228: a tool-generated image on the assistant turn — an inline preview
// (while the workspace bytes still exist) plus an info/actions chip carrying the
// size, the rendered pixel dimensions, the prompt, and download/delete. `deleted`
// arrives as its own prop (not read off `att`) so the deletion marker re-renders
// across the component boundary — the parent mutates `att.deleted` on a shallowRef
// and forces the update with `triggerRef`, which a same-reference object prop
// would otherwise swallow.
defineProps<{ att: MessageAttachment, deleted: boolean }>()
const emit = defineEmits<(e: 'delete') => void>()

// Rendered <img> natural size, captured on load — the truest source, since a
// provider may return different pixels than requested. One attachment per card.
const dims = ref<{ w: number, h: number } | null>(null)
function onImageLoad(ev: Event) {
  const el = ev.target as HTMLImageElement
  if (el.naturalWidth > 0 && el.naturalHeight > 0) dims.value = { w: el.naturalWidth, h: el.naturalHeight }
}
function dimsLabel(): string {
  return dims.value ? `${dims.value.w}×${dims.value.h}` : ''
}
</script>

<template>
  <div class="flex flex-col gap-1.5 items-start">
    <!-- Inline preview — only while the bytes still exist in the workspace. -->
    <a
      v-if="!deleted"
      :href="`/api/attachments/${att.uuid}`"
      target="_blank"
      rel="noopener"
      :title="generatedImageLabel(att)"
    >
      <img
        :src="`/api/attachments/${att.uuid}`"
        alt="AI-generated graphic"
        class="max-w-[320px] max-h-[320px] rounded-lg border border-border object-contain"
        @load="onImageLoad"
      >
    </a>
    <!-- Info + actions chip: header row (icon, size, dimensions, actions) above
         the full-width prompt. Pinned to the preview width for a stacked look. -->
    <div
      class="flex flex-col gap-2 w-[320px] max-w-full bg-muted border border-border rounded-lg px-3 py-2 text-xs"
      :class="deleted ? 'text-fg-muted' : 'text-fg-strong'"
    >
      <div class="flex items-center gap-2">
        <PhotoIcon
          class="w-4 h-4 shrink-0 text-fg-muted"
          aria-hidden="true"
        />
        <span
          v-if="!deleted"
          class="text-fg-muted"
        >{{ formatSize(att.sizeBytes) }}</span>
        <span
          v-if="!deleted && dimsLabel()"
          class="text-fg-muted"
        >{{ dimsLabel() }}</span>
        <!-- Once the bytes are gone: a deletion marker replaces the actions. -->
        <span
          v-if="deleted"
          class="ml-auto text-[11px] italic text-red-700/90 dark:text-red-400/90"
        >deleted from workspace</span>
        <!-- Actions while the file exists: download + delete, right-aligned. -->
        <template v-else>
          <a
            :href="`/api/attachments/${att.uuid}`"
            :download="att.originalFilename"
            class="ml-auto shrink-0 w-6 h-6 inline-flex items-center justify-center text-fg-muted hover:text-fg-strong transition-colors"
            title="Download image"
            aria-label="Download image"
          >
            <ArrowDownTrayIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </a>
          <button
            type="button"
            class="shrink-0 w-6 h-6 inline-flex items-center justify-center text-fg-muted hover:text-red-600 dark:hover:text-red-400 transition-colors"
            title="Delete image from workspace"
            @click="emit('delete')"
          >
            <TrashIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </template>
      </div>
      <!-- Prompt: full chip width, wraps naturally. -->
      <p class="break-words leading-relaxed">
        {{ generatedImageLabel(att) }}
      </p>
    </div>
  </div>
</template>
