<script setup lang="ts">
import { DocumentIcon, PhotoIcon, SpeakerWaveIcon } from '@heroicons/vue/24/outline'
import type { MessageAttachment } from '~/types/api'
import { formatSize } from '~/utils/format'

// JCLAW-279: the compact attachment chip — a download/preview link with a
// kind icon, filename, size, and a "gen" corner-mark for tool-produced files.
// Used both for user-turn persisted/optimistic attachments and as the
// assistant-turn fallback for anything without a richer inline renderer.
defineProps<{ att: MessageAttachment }>()
</script>

<template>
  <a
    :href="`/api/attachments/${att.uuid}`"
    target="_blank"
    rel="noopener"
    class="inline-flex items-center gap-2 max-w-[260px] bg-muted border border-border rounded-lg px-3 py-1.5 text-xs text-fg-strong hover:bg-muted/60 transition-colors"
    :title="`${att.originalFilename} · ${formatSize(att.sizeBytes)} · ${att.mimeType}`"
  >
    <PhotoIcon
      v-if="att.kind === 'IMAGE'"
      class="w-4 h-4 shrink-0 text-fg-muted"
      aria-hidden="true"
    />
    <SpeakerWaveIcon
      v-else-if="att.kind === 'AUDIO'"
      class="w-4 h-4 shrink-0 text-fg-muted"
      aria-hidden="true"
    />
    <DocumentIcon
      v-else
      class="w-4 h-4 shrink-0 text-fg-muted"
      aria-hidden="true"
    />
    <span class="truncate">{{ att.originalFilename }}</span>
    <span class="text-fg-muted shrink-0">{{ formatSize(att.sizeBytes) }}</span>
    <!-- JCLAW-227: corner-mark files produced by a generate_* tool. -->
    <span
      v-if="att.generated"
      class="shrink-0 text-[10px] uppercase tracking-wide text-purple-500 border border-purple-400/40 rounded px-1"
      :title="att.generationMetadata ? `AI-generated · ${att.generationMetadata}` : 'AI-generated image'"
    >gen</span>
  </a>
</template>
