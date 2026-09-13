<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import ChatMessage from '~/components/chat/ChatMessage.vue'
import { useChatMessageActions } from '~/composables/useChatMessageActions'
import type { SubagentRunStatus } from '~/composables/useChatSubagentChips'
import { useSubagentTranscript } from '~/composables/useSubagentTranscript'
import { shouldDisplayMessage } from '~/utils/display-message-filter'
import type { Message } from '~/types/api'

// JCLAW-1205: a subagent run's child transcript, read-only, inside its expanded chip.
const props = defineProps<{
  childConversationId: number
  status: SubagentRunStatus
  agentId: number | null
}>()

const { messages, loaded, failed, retry } = useSubagentTranscript(props.childConversationId, () => props.status)

const displayMessages = computed(() => messages.value.filter(m => shouldDisplayMessage(m, false)))

// Only the copy and collapse handlers are wired below, so the composer deps stay inert.
const {
  copiedMessageId,
  tokStatsHoverKey,
  copyMessage,
  copyReasoning,
  toggleThinking,
  toggleToolCalls,
  toggleToolCallExpansion,
} = useChatMessageActions({
  messages,
  selectedConvoId: ref(props.childConversationId),
  streaming: ref(false),
  input: ref(''),
  chatInput: ref(null),
  sendMessage: () => {},
  autoResize: () => {},
})

// chat.vue's messageRenderKey, plus call results and attachments, which a poll can change on a row already shown.
function renderToken(msg: Message): string {
  const calls = (msg.toolCalls ?? []).map(t => `${t._expanded ? 1 : 0}${t.resultText == null ? 0 : 1}`).join('')
  const attachments = (msg.attachments ?? []).map(a => a.uuid).join(',')
  return `${!!msg.thinkingCollapsed}|${!!msg.toolCallsCollapsed}|${calls}|${attachments}`
}

const BOTTOM_SLACK_PX = 24
const scrollEl = ref<HTMLElement | null>(null)
let followBottom = true

function onScroll() {
  const el = scrollEl.value
  if (el) followBottom = el.scrollHeight - el.scrollTop - el.clientHeight <= BOTTOM_SLACK_PX
}

function scrollToBottom() {
  const el = scrollEl.value
  if (el) el.scrollTop = el.scrollHeight
}

watch(() => displayMessages.value.length, () => {
  if (followBottom) scrollToBottom()
}, { flush: 'post' })

onMounted(scrollToBottom)
</script>

<template>
  <div
    data-testid="subagent-transcript-panel"
    class="subagent-transcript flex flex-col rounded-xl border border-border bg-surface-elevated"
  >
    <!-- The only scroller in an expanded chip; no overscroll-contain, so a wheel at its end reaches the stack. -->
    <div
      ref="scrollEl"
      data-testid="subagent-transcript-scroll"
      class="max-h-[min(24rem,40vh)] overflow-y-auto overflow-x-hidden px-3 py-3"
      @scroll="onScroll"
    >
      <div
        v-if="!loaded"
        class="flex items-center gap-3"
      >
        <p
          role="status"
          class="text-sm italic text-fg-muted"
        >
          {{ failed ? 'Could not load this transcript.' : 'Loading transcript…' }}
        </p>
        <button
          v-if="failed"
          type="button"
          data-testid="subagent-transcript-retry"
          class="text-xs text-fg-muted underline underline-offset-2 hover:text-fg-strong"
          @click="retry"
        >
          Retry
        </button>
      </div>
      <p
        v-else-if="!displayMessages.length"
        data-testid="subagent-transcript-empty"
        class="text-sm italic text-fg-muted"
      >
        No messages yet.
      </p>
      <div
        v-else
        class="space-y-4"
      >
        <ChatMessage
          v-for="(msg, msgIdx) in displayMessages"
          :key="msg.id ?? msg._key"
          :msg="msg"
          :msg-idx="msgIdx"
          :render-token="renderToken(msg)"
          :agent-id="agentId"
          :streaming="false"
          :copied-message-id="copiedMessageId"
          :streaming-message-key="null"
          stream-content=""
          stream-content-html=""
          stream-reasoning-html=""
          :video-job-status="{}"
          :image-gen-turn-key="null"
          :image-gen-percent="null"
          :tok-stats-hover-key="tokStatsHoverKey"
          :run-slice="null"
          run-label=""
          run-status=""
          :show-model-switch="false"
          @toggle-tool-calls="toggleToolCalls"
          @toggle-tool-call-expansion="toggleToolCallExpansion"
          @toggle-thinking="toggleThinking"
          @copy-reasoning="copyReasoning"
          @copy-message="copyMessage"
          @set-tok-stats-hover-key="tokStatsHoverKey = $event"
        />
      </div>
    </div>
    <div class="flex justify-end border-t border-border-subtle px-3 py-1.5 text-xs">
      <NuxtLink
        :to="`/chat?conversation=${childConversationId}`"
        data-testid="subagent-transcript-full"
        class="text-fg-muted underline-offset-2 hover:text-fg-strong hover:underline"
      >
        Open full transcript
      </NuxtLink>
    </div>
  </div>
</template>

<style scoped>
/* ChatMessage has no read-only mode: these are its edit, regenerate and delete controls, left unwired here. */
.subagent-transcript :deep(button[title="Edit & resubmit"]),
.subagent-transcript :deep(button[title="Regenerate response"]),
.subagent-transcript :deep(button[title="Delete message"]),
.subagent-transcript :deep(button[title$="from workspace"]) {
  display: none;
}
</style>
