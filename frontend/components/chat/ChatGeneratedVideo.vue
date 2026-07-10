<script setup lang="ts">
import { ArrowDownTrayIcon, ExclamationTriangleIcon, FilmIcon, TrashIcon } from '@heroicons/vue/24/outline'
import type { MessageAttachment } from '~/types/api'
import { formatSize } from '~/utils/format'
import { generatedImageLabel } from '~/utils/generated-attachment'
import { formatVideoDuration, videoDisplayState, videoGenMeta, videoProgressPercent, videoResultSizeBytes, videoSrc, type VideoJobStatus } from '~/utils/video-job'

// JCLAW-234: a tool-generated video on the assistant turn. Async — a generating
// card while the job runs, swapping to an inline player on success or an error
// card on failure. `jobStatus` is the poll status the parent owns (keyed by
// generationJobId); `deleted` is its own prop for the same cross-boundary
// re-render reason as ChatGeneratedImage.
const props = defineProps<{ att: MessageAttachment, jobStatus?: VideoJobStatus, deleted: boolean }>()
const emit = defineEmits<(e: 'delete') => void>()

// Thin helpers over the pure video-job utils. Kept as functions (re-run each
// render) so they reflect the latest jobStatus / deleted exactly as the inline
// original did.
function state() {
  return videoDisplayState(props.att, props.jobStatus)
}
function src() {
  return videoSrc(props.att, props.jobStatus)
}
function errorMessage(): string {
  return props.jobStatus?.errorMessage || 'Unknown error'
}
function percent(): number | null {
  return videoProgressPercent(props.jobStatus)
}
function sizeLabel(): string {
  const bytes = videoResultSizeBytes(props.att, props.jobStatus)
  return bytes > 0 ? formatSize(bytes) : ''
}
function aspect(): string | null {
  return videoGenMeta(props.att.generationMetadata).aspectRatio
}
function fps(): number | null {
  return videoGenMeta(props.att.generationMetadata).fps
}

// Rendered-clip duration (seconds) captured from the <video>'s loadedmetadata —
// the true length — with the requested value as a fallback. One clip per card.
const duration = ref<number | null>(null)
function onVideoLoadedMeta(ev: Event) {
  const el = ev.target as HTMLVideoElement
  if (Number.isFinite(el.duration) && el.duration > 0) duration.value = el.duration
}
function durationLabel(): string {
  return formatVideoDuration(duration.value, videoGenMeta(props.att.generationMetadata).durationSeconds)
}
</script>

<template>
  <div class="flex flex-col gap-1.5 items-start">
    <template v-if="state() === 'ready'">
      <!-- eslint-disable-next-line vuejs-accessibility/media-has-caption -- an AI-generated clip has no caption track -->
      <video
        v-if="!deleted"
        :src="src()"
        controls
        preload="metadata"
        class="max-w-[360px] max-h-[360px] rounded-lg border border-border bg-black"
        @loadedmetadata="onVideoLoadedMeta"
      />
      <!-- Info + actions chip, mirroring the generated-image chip. -->
      <div
        class="flex flex-col gap-2 w-[360px] max-w-full bg-muted border border-border rounded-lg px-3 py-2 text-xs"
        :class="deleted ? 'text-fg-muted' : 'text-fg-strong'"
      >
        <div class="flex items-center gap-2">
          <FilmIcon
            class="w-4 h-4 shrink-0 text-fg-muted"
            aria-hidden="true"
          />
          <template v-if="!deleted">
            <span
              v-if="sizeLabel()"
              class="text-fg-muted"
            >{{ sizeLabel() }}</span>
            <span
              v-if="aspect()"
              class="text-fg-muted"
            >{{ aspect() }}</span>
            <span
              v-if="fps() != null"
              class="text-fg-muted"
            >{{ fps() }} fps</span>
            <span
              v-if="durationLabel()"
              class="text-fg-muted"
            >{{ durationLabel() }}</span>
            <!-- Actions while the file exists: download + delete, right-aligned. -->
            <a
              :href="`/api/attachments/${att.uuid}`"
              :download="att.originalFilename"
              class="ml-auto shrink-0 w-6 h-6 inline-flex items-center justify-center text-fg-muted hover:text-fg-strong transition-colors"
              title="Download video"
              aria-label="Download video"
            >
              <ArrowDownTrayIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </a>
            <button
              type="button"
              class="shrink-0 w-6 h-6 inline-flex items-center justify-center text-fg-muted hover:text-red-600 dark:hover:text-red-400 transition-colors"
              title="Delete video from workspace"
              @click="emit('delete')"
            >
              <TrashIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </button>
          </template>
          <!-- Once the bytes are gone: a deletion marker replaces the actions. -->
          <span
            v-else
            class="ml-auto text-[11px] italic text-red-700/90 dark:text-red-400/90"
          >deleted from workspace</span>
        </div>
        <!-- Prompt: full chip width, wraps naturally. -->
        <p class="break-words leading-relaxed">
          {{ generatedImageLabel(att) }}
        </p>
      </div>
    </template>
    <div
      v-else-if="state() === 'failed'"
      class="flex items-start gap-2 w-[320px] max-w-full bg-red-50/60 dark:bg-red-950/20 border border-red-200 dark:border-red-900/50 rounded-lg px-3 py-2 text-xs text-red-700 dark:text-red-400"
    >
      <ExclamationTriangleIcon
        class="w-4 h-4 shrink-0 mt-0.5"
        aria-hidden="true"
      />
      <div class="flex flex-col gap-0.5 min-w-0">
        <span class="font-medium">Video generation failed</span>
        <span class="break-words text-red-700/90 dark:text-red-400/90">{{ errorMessage() }}</span>
      </div>
    </div>
    <div
      v-else
      class="flex items-center gap-2.5 w-[320px] max-w-full bg-muted border border-border rounded-lg px-3 py-2.5 text-xs text-fg-strong"
    >
      <svg
        class="w-4 h-4 shrink-0 animate-spin text-purple-500"
        viewBox="0 0 24 24"
        fill="none"
        aria-hidden="true"
      >
        <circle
          class="opacity-25"
          cx="12"
          cy="12"
          r="10"
          stroke="currentColor"
          stroke-width="4"
        />
        <path
          class="opacity-75"
          fill="currentColor"
          d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"
        />
      </svg>
      <div class="flex flex-col gap-0.5 min-w-0 flex-1">
        <span class="font-medium">
          Generating video…<template v-if="percent() != null"> {{ percent() }}%</template>
        </span>
        <span class="truncate text-fg-muted">{{ generatedImageLabel(att) }}</span>
        <!-- Determinate bar only when the local engine reports real per-step
             progress (JCLAW-232); cloud jobs (percent null) keep the bare spinner. -->
        <div
          v-if="percent() != null"
          class="mt-1 h-1 w-full rounded-full bg-border overflow-hidden"
          role="progressbar"
          :aria-valuenow="percent() ?? 0"
          aria-valuemin="0"
          aria-valuemax="100"
        >
          <div
            class="h-full bg-purple-500 transition-[width] duration-500"
            :style="{ width: percent() + '%' }"
          />
        </div>
      </div>
    </div>
  </div>
</template>
