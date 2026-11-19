<script setup lang="ts">
import {
  ArrowPathIcon,
  ArrowUturnLeftIcon,
  ArrowsRightLeftIcon,
  CheckIcon,
  ChevronDownIcon,
  ClipboardIcon,
  ExclamationTriangleIcon,
  PencilIcon,
  PhotoIcon,
  SpeakerWaveIcon,
  SpeakerXMarkIcon,
  TrashIcon,
  UsersIcon,
} from '@heroicons/vue/24/outline'
import { averageSpeed, formatTokensPerSec, renderMarkdown, writingSpeed } from '~/utils/chat-markdown'
import { vStructureViewers } from '~/utils/structure/directive'
import { vVisualFigures } from '~/utils/visual/directive'
import { vYoutubeEmbeds } from '~/utils/youtube-embed'
import {
  formatNanosDuration, formatUsageCost, formatUsageCostTooltip, providerMetricRows, type MessageUsage,
} from '~/utils/usage-cost'
import { routeClassLabel, routeDescription, routeOf } from '~/utils/model-route'
import { thinkingHeaderLabel } from '~/utils/thinking'
import { QUOTE_KIND_LABELS, splitQuotedReply, type QuotedReplyParts } from '~/utils/quoted-reply'
import type { VideoJobStatus } from '~/utils/video-job'
import type { Message, MessageAttachment, ScrapeJobRef, ToolCall } from '~/types/api'
import ChatAttachmentChip from '~/components/chat/ChatAttachmentChip.vue'
import ChatAudioAttachment from '~/components/chat/ChatAudioAttachment.vue'
import ChatGeneratedImage from '~/components/chat/ChatGeneratedImage.vue'
import ChatGeneratedVideo from '~/components/chat/ChatGeneratedVideo.vue'
import ChatScrapeJobCard from '~/components/chat/ChatScrapeJobCard.vue'
import ChatScrapeJobNotice from '~/components/chat/ChatScrapeJobNotice.vue'
import ChatTaskRunResult from '~/components/chat/ChatTaskRunResult.vue'
import ChatSubagentRow from '~/components/chat/ChatSubagentRow.vue'
import ChatThinkingCard from '~/components/chat/ChatThinkingCard.vue'
import ChatToolCalls from '~/components/chat/ChatToolCalls.vue'

// JCLAW-690 (stage 3): the per-message renderer, extracted from chat.vue's
// v-for body verbatim. Renders the inline-subagent run-header, the model-switch
// indicator, the subagent_announce card, and the user/assistant message rows.
//
// CRITICAL reactivity contract: `messages` in the parent is a shallowRef mutated
// in-place and forced with triggerRef, so the Message objects here are PLAIN
// (non-reactive) objects. Nested-field mutations (msg.thinkingCollapsed,
// msg.toolCallsCollapsed, each tool call's id, _expanded and result, each
// attachment's uuid and deleted) would be swallowed across the component
// boundary because the `msg` prop reference is unchanged. The parent therefore
// also passes `renderToken` (messageRenderToken) — a string digest of those
// mutable fields — which changes whenever any of them does, forcing this child to
// re-render in lockstep with the inline original. It is deliberately unused in
// the template; do NOT use it as a `:key`.
//
// `videoJobStatus` is a genuine reactive ref in the parent, so reads into it are
// tracked and re-render this card on poll updates without needing the token.
interface SubagentRunSlice {
  runId: number
  position: 'first' | 'middle' | 'last'
  collapsed: boolean
}

const props = defineProps<{
  msg: Message
  msgIdx: number
  // Exists solely to force re-render on shallowRef nested-field mutations; not read in the template.
  renderToken: string
  agentId: number | null
  streaming: boolean
  copiedMessageId: string | number | null
  streamingMessageKey: string | null
  streamContent: string
  streamContentHtml: string
  streamReasoningHtml: string
  videoJobStatus: Record<number, VideoJobStatus>
  imageGenTurnKey: string | null
  imageGenPercent: number | null
  tokStatsHoverKey: string | number | null
  runSlice: SubagentRunSlice | null
  runLabel: string
  runStatus: string
  showModelSwitch: boolean
  /** Draw MCP Apps views under tool calls: the web chat's own turns only, not a subagent's. */
  mcpApps?: boolean
}>()

const emit = defineEmits<{
  (
    e: 'toggle-tool-calls' | 'toggle-thinking' | 'copy-reasoning' | 'copy-message' | 'edit-user-message' | 'delete-message' | 'regenerate-message' | 'reply-message',
    msg: Message,
  ): void
  (e: 'toggle-subagent-run', runId: number): void
  (e: 'delete-attachment', att: MessageAttachment): void
  (e: 'toggle-tool-call-expansion', tc: ToolCall): void
  (e: 'set-tok-stats-hover-key', key: string | number | null): void
}>()

// Resolved once so the template narrows them under v-if; CodeQL reads template expressions as plain
// JavaScript, where a `!` assertion is a parse error that drops the expression from its analysis.
const quoted = computed(() => quotedParts(props.msg))
const route = computed(() => routeOf(props.msg))

/** A reply's quoted block: stored in its text, or held client-side on an optimistic row (JCLAW-1299). */
function quotedParts(msg: Message): QuotedReplyParts | null {
  if (msg._quote) return { label: QUOTE_KIND_LABELS[msg._quote.kind], quoted: msg._quote.text, reply: msg.content ?? '' }
  return splitQuotedReply(msg.content)
}

/** JCLAW-1273: the background scrape jobs this turn's web_scrape calls started. */
function scrapeJobRefs(msg: Message): ScrapeJobRef[] {
  return (msg.toolCalls ?? []).flatMap(tc => tc.resultStructured?.scrapeJob ? [tc.resultStructured.scrapeJob] : [])
}

/** Compact "provider/model-id" label for the switch indicator. */
function formatModelLabel(msg: Message): string {
  const u = msg.usage
  if (!u) return '?'
  return u.modelProvider ? `${u.modelProvider}/${u.modelId ?? '?'}` : (u.modelId ?? '?')
}

/** "2 model calls · Chosen by Auto · Agent work", under the usage card's model; empty when neither is known. */
function usageMetaLine(u: MessageUsage): string {
  const parts: string[] = []
  if (u.calls) parts.push(u.calls === 1 ? 'One model call' : `${u.calls.toLocaleString()} model calls`)
  if (u.route) parts.push(`Chosen by Auto · ${routeClassLabel(u.route)}${u.route.failover ? ' (failover)' : ''}`)
  return parts.join(' · ')
}

function usageReadNotes(u: MessageUsage): string[] {
  const notes: string[] = []
  if ((u.calls ?? 0) > 1) notes.push(`Each model call rereads the conversation, so Read adds up all ${u.calls}.`)
  if (u.cached) notes.push('Reused tokens were read before, so they usually cost less.')
  return notes
}

/** The final call's prompt and completion, the reading the header's context meter takes. */
function memoryAfter(u: MessageUsage): number {
  return (u.lastPrompt ?? 0) + (u.lastCompletion ?? 0)
}

function speedLabel(tokensPerSecond: number | null): string {
  return tokensPerSecond == null ? '' : tokensPerSecond.toFixed(1)
}

// Read-aloud (TTS) playback, shared across messages so only one plays at a time
// (JCLAW-789/793). The engine is the operator's Settings > Speech selection.
const { playingKey: readAloudPlayingKey, loadingKey: readAloudLoadingKey,
  errorKey: readAloudErrorKey, errorMessage: readAloudErrorMessage,
  toggle: toggleReadAloud } = useReadAloud()
</script>

<template>
  <!--
    JCLAW-267: inline-subagent block header. Renders before the
    FIRST message of each collapsible nested-turn block; clicking
    it toggles the run's collapsed state. The header label is
    derived from the boundary-start marker's content (set by
    SubagentSpawnTool); the status pill comes from the boundary-
    end marker.
  -->
  <div
    v-if="runSlice?.position === 'first'"
    class="flex items-center gap-2 select-none"
  >
    <button
      type="button"
      class="flex items-center gap-2 px-3 py-1.5 text-xs text-fg-muted hover:text-fg-strong border border-neutral-200 dark:border-neutral-700 rounded-full bg-surface-elevated transition-colors"
      :title="runSlice?.collapsed ? 'Expand subagent run' : 'Collapse subagent run'"
      @click="emit('toggle-subagent-run', runSlice!.runId)"
    >
      <UsersIcon
        class="w-3.5 h-3.5 shrink-0"
        aria-hidden="true"
      />
      <span class="font-medium truncate max-w-xs">
        Subagent: {{ runLabel }}
      </span>
      <span
        class="px-1.5 py-0.5 text-[10px] font-mono uppercase tracking-wide rounded"
        :class="{
          'bg-muted text-fg-muted': runStatus === 'Running',
          'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300': runStatus === 'Completed',
          'bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300': runStatus === 'Failed' || runStatus === 'Timed out',
        }"
      >
        {{ runStatus }}
      </span>
      <ChevronDownIcon
        class="w-3.5 h-3.5 transition-transform"
        :class="runSlice?.collapsed ? '-rotate-90' : ''"
        aria-hidden="true"
      />
    </button>
    <span class="flex-1 border-t border-dashed border-neutral-200 dark:border-neutral-700" />
  </div>
  <!-- JCLAW-108: divider when two adjacent assistant messages ran on
     different models. Helps make mid-conversation /model switches
     visible in the scrollback. -->
  <div
    v-if="showModelSwitch"
    class="flex items-center gap-3 text-xs text-fg-muted select-none"
  >
    <span class="flex-1 border-t border-border-subtle" />
    <span class="whitespace-nowrap">Switched to {{ formatModelLabel(msg) }}</span>
    <span class="flex-1 border-t border-border-subtle" />
  </div>
  <!--
    JCLAW-270: async-spawn completion card. Renders as a single
    self-contained tile (NOT a collapsible block — the card IS
    the entire announce surface; the full reply lives at the
    /conversations/{childConversationId} link). Branches off
    ahead of the user/assistant message rendering so the
    SYSTEM-role row doesn't fall into either bubble path.
  -->
  <ChatSubagentRow
    v-if="msg.messageKind === 'subagent_announce'"
    v-show="!runSlice || !runSlice?.collapsed"
    :msg="msg"
    :agent-id="agentId"
  />
  <ChatScrapeJobNotice
    v-else-if="msg.messageKind === 'scrape_job_complete'"
    v-show="!runSlice || !runSlice?.collapsed"
    :msg="msg"
  />
  <ChatTaskRunResult
    v-else-if="msg.messageKind === 'task_run_result'"
    v-show="!runSlice || !runSlice?.collapsed"
    :msg="msg"
  />
  <!-- JCLAW-1388: the stored stop marker reads like the live note and offers no reply actions. -->
  <div
    v-else-if="msg.messageKind === 'stop_marker'"
    v-show="!runSlice || !runSlice?.collapsed"
    class="flex justify-start"
    data-testid="stop-marker"
  >
    <div class="text-fg-muted text-base italic">
      (stopped before any response)
    </div>
  </div>
  <div
    v-else
    v-show="!runSlice || !runSlice?.collapsed"
    :class="msg.role === 'user' ? 'flex justify-end' : 'flex justify-start'"
  >
    <div
      :class="msg.role === 'user' ? 'max-w-[80%]' : 'max-w-[85%] w-full'"
      class="min-w-0"
    >
      <!-- User messages: subtle rounded pill + hover actions (copy, edit, delete) -->
      <div
        v-if="msg.role === 'user'"
        class="group"
      >
        <!-- JCLAW-279: persisted attachment chips, rendered on conversation reload.
             Placed above the message bubble so the hover-action icons that sit below
             the bubble unambiguously apply to the message text rather than the
             attachment row. -->
        <div
          v-if="msg.attachments?.length"
          class="flex flex-wrap gap-2 mb-2 justify-end"
        >
          <ChatAttachmentChip
            v-for="att in msg.attachments"
            :key="att.uuid"
            :att="att"
          />
        </div>
        <!-- JCLAW-327: USER-role rows with messageKind=subagent_send
             are agent-authored (the `message` tool persists as USER
             so the LLM sees it via loadRecentMessages, but the
             content is markdown the agent wrote — render it
             through the same markdown pipeline the assistant
             bubble uses). True user-typed content stays on the
             plain-text path so a literal `<script>` in user input
             can't inject HTML. -->
        <!-- eslint-disable vue/no-v-html -- renderMarkdown runs content through DOMPurify (see renderMarkdown above) before returning. -->
        <div
          v-if="msg.messageKind === 'subagent_send'"
          v-structure-viewers="agentId"
          v-visual-figures
          v-youtube-embeds
          class="prose-chat inline-block bg-muted rounded-2xl text-fg-strong px-4 py-2 text-base break-words"
          v-html="renderMarkdown(msg.content ?? '', agentId)"
        />
        <!-- eslint-enable vue/no-v-html -->
        <div
          v-else-if="quoted"
          class="inline-block bg-muted rounded-2xl text-fg-strong px-4 py-2 text-base whitespace-pre-wrap break-words"
        >
          <blockquote
            data-testid="message-quote"
            class="mb-1.5 border-l-2 border-input pl-2 text-sm text-fg-muted line-clamp-4"
            :title="quoted.quoted"
          >
            <span class="block text-xs font-medium">{{ quoted.label }}</span>{{ quoted.quoted }}
          </blockquote>{{ quoted.reply }}
        </div>
        <div
          v-else
          class="inline-block bg-muted rounded-2xl text-fg-strong px-4 py-2 text-base whitespace-pre-wrap break-words"
        >
          {{ msg.content }}
        </div>
        <div class="flex items-center justify-end gap-1 mt-1 h-5 opacity-0 group-hover:opacity-100 focus-within:opacity-100 transition-opacity">
          <button
            type="button"
            class="p-1 text-fg-muted hover:text-fg-primary transition-colors"
            :title="copiedMessageId === (msg.id ?? msg._key) ? 'Copied' : 'Copy to clipboard'"
            @click="emit('copy-message', msg)"
          >
            <ClipboardIcon
              v-if="copiedMessageId !== (msg.id ?? msg._key)"
              class="w-4 h-4"
              aria-hidden="true"
            />
            <CheckIcon
              v-else
              class="w-4 h-4 text-emerald-700 dark:text-emerald-400"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            class="p-1 text-fg-muted hover:text-fg-primary transition-colors"
            title="Reply"
            data-testid="reply-message"
            @click="emit('reply-message', msg)"
          >
            <ArrowUturnLeftIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            :disabled="streaming"
            class="p-1 text-fg-muted hover:text-fg-primary disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
            title="Edit & resubmit"
            @click="emit('edit-user-message', msg)"
          >
            <PencilIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            :disabled="streaming"
            class="p-1 text-fg-muted hover:text-red-600 dark:hover:text-red-400 disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
            title="Delete message"
            @click="emit('delete-message', msg)"
          >
            <TrashIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
      <!-- Assistant messages: optional tool-calls block + thinking card + plain markdown body -->
      <div
        v-else
        class="group"
      >
        <!-- JCLAW-227/228: tool-generated images (generate_image) inline on the
             assistant turn. A generated image renders an inline preview — the same
             "image inline + download link below" shape a browser-tool screenshot
             gets — while every other attachment keeps the compact chip. -->
        <div
          v-if="msg.attachments?.length"
          class="flex flex-col gap-3 mb-2 items-start"
        >
          <template
            v-for="att in msg.attachments"
            :key="att.uuid"
          >
            <ChatGeneratedImage
              v-if="att.generated && att.kind === 'IMAGE'"
              :att="att"
              :deleted="!!att.deleted"
              @delete="emit('delete-attachment', att)"
            />
            <ChatGeneratedVideo
              v-else-if="att.generated && att.kind === 'VIDEO'"
              :att="att"
              :job-status="att.generationJobId != null ? videoJobStatus[att.generationJobId] : undefined"
              :deleted="!!att.deleted"
              @delete="emit('delete-attachment', att)"
            />
            <ChatAudioAttachment
              v-else-if="att.kind === 'AUDIO' && !att.deleted"
              :att="att"
            />
            <ChatAttachmentChip
              v-else
              :att="att"
            />
          </template>
        </div>
        <!--
          JCLAW-170: tool-calls accordion. Renders above the
          thinking card whenever the assistant message carries one
          or more tool invocations (live-streamed via the
          {@code tool_call} SSE frame, or hydrated from persisted
          {@code tool_results_*} columns on reload). Mirrors the
          thinking card's bordered-card + header-button pattern;
          auto-collapses on reload and on stream-completion,
          expands when a new call lands so in-flight tool activity
          is visible without a click.
        -->
        <ChatToolCalls
          v-if="msg.toolCalls?.length"
          :tool-calls="[...msg.toolCalls]"
          :collapsed="!!(msg as Message & { toolCallsCollapsed?: boolean }).toolCallsCollapsed"
          :mcp-apps="mcpApps && !runSlice"
          @toggle-collapse="emit('toggle-tool-calls', msg)"
          @toggle-call="emit('toggle-tool-call-expansion', $event)"
        />
        <ChatScrapeJobCard
          v-for="jobRef in scrapeJobRefs(msg)"
          :key="jobRef.id"
          :job-ref="jobRef"
        />
        <!--
      Thinking/reasoning block, Unsloth-style: bordered card with a
      "Thought for Xs" header (lightbulb + chevron) and an in-place
      Copy button. Collapsed by default on historical turns; in-flight
      turns open on first reasoning delta and auto-collapse at the
      reasoning→content transition.
    -->
        <ChatThinkingCard
          v-if="msg.reasoning"
          :collapsed="!!msg.thinkingCollapsed"
          :header-label="thinkingHeaderLabel(msg)"
          :copied="copiedMessageId === `reason:${msg.id ?? msg._key}`"
          :reasoning="msg.reasoning"
          :agent-id="agentId"
          :is-streaming="msg._key === streamingMessageKey"
          :stream-html="streamReasoningHtml"
          @toggle="emit('toggle-thinking', msg)"
          @copy="emit('copy-reasoning', msg)"
        />
        <!-- Response content — plain rendered markdown, no bubble. -->
        <!-- eslint-disable vue/no-v-html -- renderMarkdown runs content through DOMPurify (see renderMarkdown above) before returning. -->
        <!--
          Streaming bubble: bind to streamContentHtml so the
          template re-renders at the throttled cadence (~12.5 fps)
          driven by scheduleStreamContentRender. Historical
          messages still go through renderMarkdown's cached path.
          streamingMessageKey is the _key of the in-flight
          assistant message (set when the placeholder is pushed
          in sendMessage, cleared on stream end).
        -->
        <div
          v-if="msg._key === streamingMessageKey ? !!streamContent : !!msg.content"
          v-structure-viewers="agentId"
          v-visual-figures
          v-youtube-embeds="msg._key !== streamingMessageKey"
          class="prose-chat text-fg-primary text-base overflow-x-auto break-words"
          v-html="msg._key === streamingMessageKey ? streamContentHtml : renderMarkdown(msg.content ?? '', agentId)"
        />
        <!-- eslint-enable vue/no-v-html -->
        <div
          v-else-if="!msg.reasoning && !msg.toolCalls?.length && !streaming"
          class="text-fg-muted text-base italic"
        >
          (empty response)
        </div>
        <!-- JCLAW-683: local image-gen progress, scoped to the turn
             that invoked generate_image (keyed by _key, mirroring the
             generated-video card's generationJobId keying). Polled via
             /api/imagegen/progress; polling starts only when THIS turn
             fires a generate_image tool call, so a concurrent
             generation's load phase can't leak onto an unrelated turn.
             Hidden for cloud providers (no per-step info) and when idle. -->
        <div
          v-if="msg._key === imageGenTurnKey && imageGenPercent != null"
          class="mt-2 flex items-center gap-2.5 bg-surface-elevated border border-border rounded-xl px-3 py-2 text-xs text-fg-strong"
        >
          <PhotoIcon
            class="w-4 h-4 shrink-0 text-purple-500"
            aria-hidden="true"
          />
          <div class="flex flex-col gap-1 min-w-0 flex-1">
            <span class="font-medium">Generating image… {{ imageGenPercent }}%</span>
            <div
              class="h-1 w-full rounded-full bg-border overflow-hidden"
              role="progressbar"
              :aria-valuenow="imageGenPercent"
              aria-valuemin="0"
              aria-valuemax="100"
            >
              <div
                class="h-full bg-purple-500 transition-[width] duration-500"
                :style="{ width: imageGenPercent + '%' }"
              />
            </div>
          </div>
        </div>
        <!-- JCLAW-291: model-output truncation marker. Sits below
             the rendered reply (and inside the inline subagent
             block when subagentRunId is set) so the operator sees
             "this isn't the full answer" without parsing finish
             reasons. Same amber chip style as the announce-card
             marker at the SYSTEM-role render path above. -->
        <!-- JCLAW-1222: the model the router chose. Visible without hovering, unlike the
             footer below, because on an Auto conversation the model changes from turn to turn.
             Shown from the stream's route frame, so it names the model while it is answering. -->
        <div
          v-if="route"
          class="flex flex-wrap items-center gap-1.5 mt-1.5 text-xs text-fg-muted"
          :title="routeDescription(route)"
          data-testid="route-badge"
        >
          <ArrowsRightLeftIcon
            class="w-3.5 h-3.5 shrink-0"
            aria-hidden="true"
          />
          <span class="sr-only">{{ routeDescription(route) }}</span>
          <span aria-hidden="true">Auto · {{ routeClassLabel(route) }} →</span>
          <span
            class="font-mono text-fg-strong"
            aria-hidden="true"
          >{{ route.model }}</span>
          <span
            v-if="route.thinkingMode"
            aria-hidden="true"
            data-testid="route-effort"
          >· {{ route.thinkingMode }} effort</span>
          <span
            v-if="route.failover"
            class="px-1.5 py-0.5 text-[10px] uppercase tracking-wide rounded text-amber-700 dark:text-amber-400 border border-amber-200 dark:border-amber-900/50 bg-amber-50/50 dark:bg-amber-950/20"
            aria-hidden="true"
          >failover</span>
          <span
            v-if="route.downshifted"
            class="px-1.5 py-0.5 text-[10px] uppercase tracking-wide rounded text-amber-700 dark:text-amber-400 border border-amber-200 dark:border-amber-900/50 bg-amber-50/50 dark:bg-amber-950/20"
            aria-hidden="true"
          >budget</span>
        </div>
        <div
          v-if="msg.truncated"
          class="flex items-center gap-1.5 mt-1.5 px-2 py-1 text-xs text-amber-700 dark:text-amber-400 border border-amber-200 dark:border-amber-900/50 rounded bg-amber-50/50 dark:bg-amber-950/20"
          data-testid="truncated-marker"
        >
          <ExclamationTriangleIcon
            class="w-3.5 h-3.5 shrink-0"
            aria-hidden="true"
          />
          <span>Reply was truncated by the model</span>
        </div>
        <!--
          Assistant footer — Unsloth-style compact row:
          [copy] [regenerate] [delete] [tok/s pill with hover
          popover for full stats]. Icons render as soon as
          streaming ends (no msg.id gate) so there's no
          perceptible delay during the persist race. Delete
          button is disabled until msg.id lands since the
          server needs an id to act on.
        -->
        <div
          v-if="!streaming && (msg.id || msg._key) && msg.content"
          :class="[
            'flex items-center gap-1 mt-1.5 -ml-1 transition-opacity',
            tokStatsHoverKey === (msg.id ?? msg._key)
              ? 'opacity-100'
              : 'opacity-0 group-hover:opacity-100 focus-within:opacity-100',
          ]"
        >
          <button
            type="button"
            class="p-1 text-fg-muted hover:text-fg-primary transition-colors"
            :title="copiedMessageId === (msg.id ?? msg._key) ? 'Copied' : 'Copy to clipboard'"
            @click="emit('copy-message', msg)"
          >
            <ClipboardIcon
              v-if="copiedMessageId !== (msg.id ?? msg._key)"
              class="w-4 h-4"
              aria-hidden="true"
            />
            <CheckIcon
              v-else
              class="w-4 h-4 text-emerald-700 dark:text-emerald-400"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            class="p-1 text-fg-muted hover:text-fg-primary transition-colors"
            title="Reply"
            data-testid="reply-message"
            @click="emit('reply-message', msg)"
          >
            <ArrowUturnLeftIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            :disabled="readAloudLoadingKey === String(msg.id ?? msg._key)"
            class="p-1 text-fg-muted hover:text-fg-primary disabled:cursor-not-allowed transition-colors"
            :title="readAloudErrorKey === String(msg.id ?? msg._key)
              ? readAloudErrorMessage ?? 'Read aloud failed'
              : (readAloudPlayingKey === String(msg.id ?? msg._key) ? 'Stop reading' : 'Read aloud')"
            :aria-label="readAloudPlayingKey === String(msg.id ?? msg._key) ? 'Stop reading aloud' : 'Read message aloud'"
            @click="toggleReadAloud(String(msg.id ?? msg._key), msg.content)"
          >
            <ArrowPathIcon
              v-if="readAloudLoadingKey === String(msg.id ?? msg._key)"
              class="w-4 h-4 animate-spin"
              aria-hidden="true"
            />
            <SpeakerXMarkIcon
              v-else-if="readAloudPlayingKey === String(msg.id ?? msg._key)"
              class="w-4 h-4 text-emerald-700 dark:text-emerald-400"
              aria-hidden="true"
            />
            <SpeakerWaveIcon
              v-else
              class="w-4 h-4"
              :class="readAloudErrorKey === String(msg.id ?? msg._key)
                ? 'text-red-600 dark:text-red-400' : ''"
              aria-hidden="true"
            />
          </button>
          <!-- JCLAW-880: the server's reason, shown rather than logged. Without this
               a refused read-aloud was indistinguishable from a dead button. -->
          <span
            v-if="readAloudErrorKey === String(msg.id ?? msg._key) && readAloudErrorMessage"
            role="status"
            aria-live="polite"
            class="text-[11px] text-red-600 dark:text-red-400"
          >{{ readAloudErrorMessage }}</span>
          <button
            type="button"
            :disabled="streaming"
            class="p-1 text-fg-muted hover:text-fg-primary disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
            title="Regenerate response"
            @click="emit('regenerate-message', msg)"
          >
            <ArrowPathIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
          <button
            type="button"
            :disabled="streaming || !msg.id"
            class="p-1 text-fg-muted hover:text-red-600 dark:hover:text-red-400 disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
            title="Delete message"
            @click="emit('delete-message', msg)"
          >
            <TrashIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
          <!--
            tok/s trigger + hover popover for the full usage
            breakdown. Only rendered once msg.usage has landed
            (post-stream "complete" event) so we don't flash
            a dash during the commit race.
          -->
          <Popover
            v-if="msg.usage && formatTokensPerSec(msg.usage)"
            :open="tokStatsHoverKey === (msg.id ?? msg._key)"
            @update:open="(v) => { if (!v) emit('set-tok-stats-hover-key', null) }"
          >
            <PopoverTrigger as-child>
              <!-- JCLAW-175: tok/s is informational, so it keeps no clickable look: a button only so a
                   keyboard reaches it, with no button styling, so nothing a mouse user sees changes. The
                   breakdown opens on hover through the controlled :open binding, and on keyboard focus too
                   (WCAG 2.1.1), with a ring drawn only for keyboard focus. -->
              <button
                type="button"
                class="ml-1 px-2 py-0.5 text-xs font-mono tabular-nums text-fg-muted hover:text-fg-primary rounded-md transition-colors cursor-help select-none focus:outline-none focus-visible:ring-2 focus-visible:ring-ring"
                @mouseenter="emit('set-tok-stats-hover-key', msg.id ?? msg._key ?? null)"
                @mouseleave="emit('set-tok-stats-hover-key', null)"
                @focus="emit('set-tok-stats-hover-key', msg.id ?? msg._key ?? null)"
                @blur="emit('set-tok-stats-hover-key', null)"
              >
                {{ formatTokensPerSec(msg.usage) }}
              </button>
            </PopoverTrigger>
            <!-- On close reka focuses the trigger, whose focus handler reopens the card: closed by the pointer
                 leaving, it stuck open with the keyboard ring on the pill, having taken focus from the message box. -->
            <PopoverContent
              side="top"
              align="start"
              :side-offset="8"
              class="w-72 max-w-[calc(100vw-2rem)] p-4 rounded-[10px] border-neutral-200 dark:border-neutral-700/50"
              @mouseenter="emit('set-tok-stats-hover-key', msg.id ?? msg._key ?? null)"
              @mouseleave="emit('set-tok-stats-hover-key', null)"
              @focusin="emit('set-tok-stats-hover-key', msg.id ?? msg._key ?? null)"
              @focusout="emit('set-tok-stats-hover-key', null)"
              @close-auto-focus="(event: Event) => event.preventDefault()"
            >
              <!--
                Rows sit 2px apart and groups 16px apart, so each group reads as one. A rule marks a change of
                subject only (under the header, above the provider's own numbers), and each group's help text is
                one footnote under its rows, so no sentence sits between numbers.
              -->
              <div
                data-testid="usage-card"
                class="grid gap-4 text-xs"
              >
                <div class="grid gap-0.5">
                  <div class="flex items-baseline justify-between gap-3">
                    <p class="text-[13px] leading-5 font-semibold text-fg-strong">
                      This reply
                    </p>
                    <p
                      v-if="msg.usage.durationMs"
                      class="text-[13px] leading-5 font-medium tabular-nums"
                    >
                      <span class="sr-only">Took </span>{{ formatNanosDuration(msg.usage.durationMs * 1e6) }}
                    </p>
                  </div>
                  <p
                    v-if="msg.usage.modelId"
                    class="font-mono text-[11.5px] leading-[18px] text-muted-foreground break-all"
                  >
                    {{ formatModelLabel(msg) }}
                  </p>
                  <p
                    v-if="usageMetaLine(msg.usage)"
                    class="leading-[18px] text-muted-foreground"
                    :title="msg.usage.route?.reason"
                  >
                    {{ usageMetaLine(msg.usage) }}
                  </p>
                </div>
                <div
                  aria-hidden="true"
                  class="border-t border-neutral-200 dark:border-neutral-700/50"
                />
                <div>
                  <p class="mb-1 text-[11px] leading-4 font-medium uppercase tracking-wider text-fg-muted">
                    Tokens
                  </p>
                  <dl class="grid gap-0.5">
                    <div class="flex items-baseline justify-between gap-4 leading-5">
                      <dt class="text-muted-foreground">
                        Read
                      </dt>
                      <dd class="tabular-nums">
                        {{ msg.usage.prompt.toLocaleString() }}
                      </dd>
                    </div>
                    <div
                      v-if="msg.usage.cached"
                      class="flex items-baseline justify-between gap-4 leading-5"
                    >
                      <dt class="pl-3 text-fg-muted">
                        Reused from earlier
                      </dt>
                      <dd class="tabular-nums">
                        {{ msg.usage.cached.toLocaleString() }}
                      </dd>
                    </div>
                    <div class="flex items-baseline justify-between gap-4 leading-5">
                      <dt class="text-muted-foreground">
                        Wrote
                      </dt>
                      <dd class="tabular-nums">
                        {{ msg.usage.completion.toLocaleString() }}
                      </dd>
                    </div>
                    <div
                      v-if="msg.usage.reasoning"
                      class="flex items-baseline justify-between gap-4 leading-5"
                    >
                      <dt class="text-muted-foreground">
                        Thinking
                      </dt>
                      <dd class="tabular-nums">
                        {{ msg.usage.reasoning.toLocaleString() }}
                      </dd>
                    </div>
                  </dl>
                  <p
                    v-if="usageReadNotes(msg.usage).length"
                    class="mt-1.5 text-[11.5px] leading-4 text-fg-muted"
                  >
                    {{ usageReadNotes(msg.usage).join(' ') }}
                  </p>
                </div>
                <div v-if="msg.usage.lastPrompt != null">
                  <p class="mb-1 text-[11px] leading-4 font-medium uppercase tracking-wider text-fg-muted">
                    Memory
                  </p>
                  <dl class="grid gap-0.5">
                    <div class="flex items-baseline justify-between gap-4 leading-5">
                      <dt class="text-muted-foreground">
                        After this reply
                      </dt>
                      <dd class="tabular-nums">
                        {{ memoryAfter(msg.usage).toLocaleString() }} <span class="text-fg-muted">tokens</span>
                      </dd>
                    </div>
                  </dl>
                  <p class="mt-1.5 text-[11.5px] leading-4 text-fg-muted">
                    The meter at the top shows this for the latest reply.
                  </p>
                </div>
                <div>
                  <p class="mb-1 text-[11px] leading-4 font-medium uppercase tracking-wider text-fg-muted">
                    {{ formatUsageCost(msg.usage) ? 'Speed and cost' : 'Speed' }}
                  </p>
                  <dl class="grid gap-0.5">
                    <div
                      v-if="writingSpeed(msg.usage) != null"
                      class="flex items-baseline justify-between gap-4 leading-5"
                    >
                      <dt class="text-muted-foreground">
                        Writing speed
                      </dt>
                      <dd class="tabular-nums">
                        {{ speedLabel(writingSpeed(msg.usage)) }} <span class="text-fg-muted">tokens/s</span>
                      </dd>
                    </div>
                    <div
                      v-else-if="averageSpeed(msg.usage) != null"
                      class="flex items-baseline justify-between gap-4 leading-5"
                    >
                      <dt class="text-muted-foreground">
                        Average speed
                      </dt>
                      <dd class="tabular-nums">
                        {{ speedLabel(averageSpeed(msg.usage)) }} <span class="text-fg-muted">tokens/s</span>
                      </dd>
                    </div>
                    <div
                      v-if="formatUsageCost(msg.usage)"
                      class="flex items-baseline justify-between gap-4 leading-5"
                      :title="formatUsageCostTooltip(msg.usage) ?? undefined"
                    >
                      <dt class="text-muted-foreground">
                        Cost
                      </dt>
                      <dd class="tabular-nums text-amber-700 dark:text-amber-400">
                        {{ formatUsageCost(msg.usage) }}
                      </dd>
                    </div>
                  </dl>
                  <p
                    v-if="writingSpeed(msg.usage) == null && averageSpeed(msg.usage) != null"
                    class="mt-1.5 text-[11.5px] leading-4 text-fg-muted"
                  >
                    Output over the whole reply's time, the wait for the first word included.
                  </p>
                </div>
                <!--
                  JCLAW-1147: whatever this provider reports beyond the OpenAI usage
                  schema. Rendered last and only when present, so a provider that says
                  nothing extra adds no section.
                -->
                <template v-if="providerMetricRows(msg.usage).length">
                  <div
                    aria-hidden="true"
                    class="border-t border-neutral-200 dark:border-neutral-700/50"
                  />
                  <div>
                    <p class="mb-1 text-[11px] leading-4 font-medium uppercase tracking-wider text-fg-muted">
                      Reported by {{ msg.usage.modelProvider ?? 'the provider' }}
                    </p>
                    <dl class="grid gap-0.5">
                      <div
                        v-for="row in providerMetricRows(msg.usage)"
                        :key="row.key"
                        class="flex items-baseline justify-between gap-4 leading-5"
                        :title="row.key"
                      >
                        <dt class="text-muted-foreground">
                          {{ row.label }}
                        </dt>
                        <dd class="tabular-nums">
                          {{ row.value }}
                        </dd>
                      </div>
                    </dl>
                  </div>
                </template>
              </div>
            </PopoverContent>
          </Popover>
        </div>
      </div>
    </div>
  </div>
</template>
