<script setup lang="ts">
import { SpeakerWaveIcon } from '@heroicons/vue/24/outline'
import type { MessageAttachment } from '~/types/api'
import { formatSize } from '~/utils/format'

// JCLAW-562: audio on the assistant turn (the extract action's voice-lineup
// file, or any tool-produced audio) gets an inline player — a bare download
// chip gave the operator nothing to listen to. The compact chip stays below it
// for filename/size/download.
defineProps<{ att: MessageAttachment }>()
</script>

<template>
  <div class="flex flex-col gap-1.5 items-start">
    <!-- eslint-disable-next-line vuejs-accessibility/media-has-caption -- chat audio has no caption track -->
    <audio
      :src="`/api/attachments/${att.uuid}`"
      controls
      preload="metadata"
      class="w-[320px] max-w-full"
    />
    <a
      :href="`/api/attachments/${att.uuid}`"
      target="_blank"
      rel="noopener"
      class="inline-flex items-center gap-2 max-w-[320px] bg-muted border border-border rounded-lg px-3 py-1.5 text-xs text-fg-strong hover:bg-muted/60 transition-colors"
      :title="`${att.originalFilename} · ${formatSize(att.sizeBytes)} · ${att.mimeType}`"
    >
      <SpeakerWaveIcon
        class="w-4 h-4 shrink-0 text-fg-muted"
        aria-hidden="true"
      />
      <span class="truncate">{{ att.originalFilename }}</span>
      <span class="text-fg-muted shrink-0">{{ formatSize(att.sizeBytes) }}</span>
      <span
        v-if="att.generated"
        class="shrink-0 text-[10px] uppercase tracking-wide text-purple-500 border border-purple-400/40 rounded px-1"
        :title="att.generationMetadata ? `AI-generated · ${att.generationMetadata}` : 'AI-generated audio'"
      >gen</span>
    </a>
  </div>
</template>
