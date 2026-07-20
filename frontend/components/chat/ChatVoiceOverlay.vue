<script setup lang="ts">
// Voice-mode overlay (JCLAW-791, Phase A). A modal over the chat page that
// drives the real-time cascade via useVoiceMode: mic capture + VAD-segmented
// utterances → local STT → agent → streaming TTS playback. Distinct from the
// composer's "Record voice" button (which attaches an audio clip). Shares the
// current conversation, so turns land in the chat history too.
import { MicrophoneIcon, SpeakerWaveIcon, XMarkIcon } from '@heroicons/vue/24/outline'

const props = defineProps<{ agentId: number }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const { state, transcript, reply, errorMsg, start, stop } = useVoiceMode()

function close() {
  stop()
  emit('close')
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Escape') close()
}

onMounted(() => {
  start(props.agentId)
  window.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onKeydown)
  stop()
})

const statusLabel = computed(() => {
  switch (state.value) {
    case 'connecting': return 'Connecting…'
    case 'listening': return 'Listening…'
    case 'capturing': return 'Listening…'
    case 'thinking': return 'Thinking…'
    case 'speaking': return 'Speaking…'
    case 'error': return 'Something went wrong'
    default: return 'Voice mode'
  }
})

const indicatorClass = computed(() => {
  switch (state.value) {
    case 'capturing': return 'bg-emerald-600 text-white'
    case 'listening': return 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400'
    case 'thinking': return 'bg-muted text-fg-muted animate-pulse'
    case 'speaking': return 'bg-sky-600 text-white'
    case 'error': return 'bg-red-500/15 text-red-600 dark:text-red-400'
    default: return 'bg-muted text-fg-muted'
  }
})

const pulse = computed(() => state.value === 'listening' || state.value === 'capturing')
</script>

<template>
  <!-- Backdrop: click-outside dismisses (a mouse convenience). Keyboard users
       dismiss via Escape (window listener) or the Close button, so this static
       interaction is intentional. -->
  <!-- eslint-disable-next-line vuejs-accessibility/click-events-have-key-events, vuejs-accessibility/no-static-element-interactions -->
  <div
    class="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm"
    @click.self="close"
  >
    <div
      class="w-full max-w-md mx-4 bg-surface-elevated border border-border shadow-xl"
      role="dialog"
      aria-modal="true"
      aria-label="Voice mode"
    >
      <div class="flex items-center justify-between px-4 py-2.5 border-b border-border">
        <h2 class="text-sm font-medium text-fg-strong">
          Voice mode
        </h2>
        <button
          type="button"
          aria-label="Close voice mode"
          class="p-1 text-fg-muted hover:text-fg-primary transition-colors"
          @click="close"
        >
          <XMarkIcon
            class="w-5 h-5"
            aria-hidden="true"
          />
        </button>
      </div>

      <div class="flex flex-col items-center gap-3 py-7">
        <div
          class="w-20 h-20 rounded-full flex items-center justify-center transition-colors"
          :class="[indicatorClass, { 'animate-pulse': pulse }]"
        >
          <SpeakerWaveIcon
            v-if="state === 'speaking'"
            class="w-9 h-9"
            aria-hidden="true"
          />
          <MicrophoneIcon
            v-else
            class="w-9 h-9"
            aria-hidden="true"
          />
        </div>
        <span
          class="text-sm text-fg-primary"
          aria-live="polite"
        >{{ statusLabel }}</span>
      </div>

      <div class="px-4 pb-4 space-y-1.5 min-h-[3rem]">
        <p
          v-if="transcript"
          class="text-xs text-fg-muted"
        >
          <span class="font-medium">You:</span> {{ transcript }}
        </p>
        <p
          v-if="reply"
          class="text-sm text-fg-primary"
        >
          <span class="font-medium text-fg-muted">Agent:</span> {{ reply }}
        </p>
        <p
          v-if="state === 'error'"
          class="text-xs text-red-600 dark:text-red-400"
        >
          {{ errorMsg }}
        </p>
      </div>

      <p class="px-4 pb-4 text-[11px] text-fg-muted text-center">
        Speak, then pause to send. Uses the local transcription model and your
        selected voice engine (Settings&nbsp;›&nbsp;Speech).
      </p>
    </div>
  </div>
</template>
