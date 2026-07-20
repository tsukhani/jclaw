<script setup lang="ts">
import {
  ArrowDownTrayIcon,
  EyeIcon,
  FilmIcon,
  LightBulbIcon,
  MicrophoneIcon,
  PaperAirplaneIcon,
  PaperClipIcon,
  PencilSquareIcon,
  PhoneIcon,
  SpeakerWaveIcon,
  UsersIcon,
  XMarkIcon,
} from '@heroicons/vue/24/outline'
// Solid lightbulb for the active-reasoning header — the outline bulb on the
// composer's Think toggle means "thinking available", the solid+amber bulb
// on an expanded reasoning block means "thoughts are here, shining." Solid
// stop icon is the filled red square used to interrupt an in-flight stream.
// Solid microphone pairs with the outline icon to signal "recording live."
import {
  LightBulbIcon as LightBulbIconSolid,
  MicrophoneIcon as MicrophoneIconSolid,
  StopIcon as StopIconSolid,
} from '@heroicons/vue/24/solid'
import { formatSize } from '~/utils/format'
// Filter out tool messages and empty assistant messages (tool call records) from display.
// The predicate lives in ~/utils/display-message-filter for unit-testability; see
// JCLAW-75 for the specific reasoning-stream regression the reasoning-aware
// suppression rule closes.
import { shouldDisplayMessage } from '~/utils/display-message-filter'

import type { Agent, Conversation, Message, ConfigResponse } from '~/types/api'
import { useChatComposer } from '~/composables/useChatComposer'
import { useChatMessageActions } from '~/composables/useChatMessageActions'
import { useChatUsageMeter } from '~/composables/useChatUsageMeter'
import { useChatAttachments } from '~/composables/useChatAttachments'
import { useChatScroll } from '~/composables/useChatScroll'
import { useChatConversation, type ChatConversationLoadHooks } from '~/composables/useChatConversation'
import { useAgentModel } from '~/composables/useAgentModel'
import { useChatAnnouncePoller } from '~/composables/useChatAnnouncePoller'
import { useChatSubagents } from '~/composables/useChatSubagents'
import { useMediaGenPolling } from '~/composables/useMediaGenPolling'
import { useChatStream } from '~/composables/useChatStream'
import ChatMessage from '~/components/chat/ChatMessage.vue'
import ChatAgentSelector from '~/components/chat/ChatAgentSelector.vue'

const { data: agents, refresh: refreshAgents } = await useFetch<Agent[]>('/api/agents')
const { data: configData } = await useFetch<ConfigResponse>('/api/config')

// Seed selectedAgentId synchronously from the agents we just loaded so the
// conversations useFetch below has a valid agentId on its first render. If we
// leave this null, useFetch evaluates the URL function to a string containing
// "agentId=null" — harmless — but more importantly: an earlier version of this
// page passed null as the URL itself, which Nuxt stringified to fetch `/null`,
// got Nitro's SPA-fallback HTML back with a 200, and crashed downstream
// consumers expecting an array. The auto-select watcher below still runs so
// later agent reloads (e.g. refreshAgents) keep working.
const selectedAgentId = ref<number | null>(
  agents.value?.find(a => a.isMain)?.id ?? agents.value?.[0]?.id ?? null,
)

// JCLAW-791: real-time voice mode overlay — a live spoken conversation with the
// current agent. Distinct from the composer's "Record voice" button (which just
// attaches an audio clip).
const voiceModeActive = ref(false)

// Extract configured providers and their models from config
const configDataRef = computed(() => configData.value ?? null)
const { providers } = useProviders(configDataRef)

/**
 * JCLAW-690: per-message render digest passed to <ChatMessage :render-token>.
 * `messages` is a shallowRef whose Message objects are mutated in-place then
 * forced with triggerRef, so nested-field changes (thinkingCollapsed,
 * toolCallsCollapsed, each tool call's _expanded, each attachment's deleted)
 * carry no reference change on the `:msg` prop and would be swallowed across
 * the component boundary. This digest changes whenever any of those mutable
 * fields does, so the child re-renders in lockstep with the old inline v-for.
 * Not a `:key` — the row identity stays `msg.id ?? msg._key`.
 */
function messageRenderKey(msg: Message): string {
  return `${!!msg.thinkingCollapsed}|${!!msg.toolCallsCollapsed}|${(msg.toolCalls ?? []).map(t => t._expanded ? 1 : 0).join('')}|${(msg.attachments ?? []).map(a => a.deleted ? 1 : 0).join('')}`
}

/** Broken images inside a rendered markdown body collapse instead of showing
 *  the browser's broken-image icon + alt text. Markdown is injected via v-html
 *  (no Vue `@error` binding possible), so we delegate. The usual culprit: a
 *  model re-embeds a generated image with a markdown `![]()` whose URL it
 *  invented — the real image already renders as the attachment card above, so
 *  hiding the dead embed leaves a clean reply. Scoped to `.prose-chat` so the
 *  generated-image card (a separate template, not v-html) and favicons are
 *  untouched. Must run in the capture phase — `error` events don't bubble. */
function onMarkdownImageError(ev: Event) {
  const target = ev.target as HTMLElement | null
  if (target instanceof HTMLImageElement && target.closest('.prose-chat')) {
    target.style.display = 'none'
  }
}

// Function form (not a Ref) so Vue's reactivity tracks `selectedAgentId.value`
// on every URL evaluation — useFetch refetches automatically when the user
// picks a different agent. selectedAgentId is seeded synchronously above, so
// the URL is well-formed on first render. (NEVER pass a value-may-be-null
// Ref/function to useFetch: it stringifies null to "null" and fetches `/null`,
// Nitro's SPA fallback returns 200 + the index HTML, and downstream consumers
// crash trying to treat HTML as a Conversation[].)
const { data: conversations, refresh: refreshConversations } = await useFetch<Conversation[]>(
  () => `/api/conversations?channel=web&agentId=${selectedAgentId.value}&limit=50`,
)
// Conversation lifecycle (current-conversation messages, load/switch/resolve,
// end-of-stream id reconciliation, read-only subagent transcript) lives in
// useChatConversation, which OWNS the messages shallowRef every other chat
// composable reads (shallowRef so per-token property mutations don't cascade
// through computeds that walk the array; structural changes triggerRef it). Its
// loadConversation side effects (video poll, subagent-collapse, scroll, focus)
// are wired through convHooks *after* the leaf composables below are created —
// they read this composable's messages, so it must exist first. See the wiring
// block after useChatSubagents.
const convHooks: ChatConversationLoadHooks = {}
const {
  messages,
  selectedConvoId,
  subagentTranscript,
  effectiveDisplayAgentId,
  clearSubagentTranscript,
  initializing,
  reconcileMessageIds,
  resolveAndLoadConversation,
  loadConversation,
} = useChatConversation({ agents, selectedAgentId, hooks: convHooks })

// Agent + model + thinking-config state (model resolution chain, capability
// pills, the teleported thinking-level menu, and the agent/conversation-override
// writes) lives in useAgentModel. Placed after useChatConversation because its
// effectiveModel resolver reads selectedConvoId + the conversations list;
// selectedAgentId stays a page ref (cross-coupled + template v-model).
const {
  selectedAgent,
  currentConversation,
  selectedModelInfo,
  selectedModelKey,
  thinkingSupported,
  thinkingLock,
  thinkingLevels,
  thinkingActive,
  visionSupported,
  audioSupported,
  videoSupported,
  thinkingMenuOpen,
  thinkPillRef,
  thinkingMenuStyle,
  toggleThinkingPill,
  openThinkingMenu,
  scheduleCloseThinkingMenu,
  setThinkingLevel,
  onModelKeyChange,
} = useAgentModel({
  agents,
  selectedAgentId,
  selectedConvoId,
  conversations,
  providers,
  refreshAgents,
  refreshConversations,
})
const input = ref('')
// `streaming` and `streamReasoning` (below) are page-level shared refs, not
// owned by useChatStream: useChatScroll pins the reasoning body off them,
// useMediaGenPolling tears down on streaming-end, and displayMessages /
// isEmptyChat / the template read `streaming` — all constructed before the
// stream composable. Keeping them here dissolves that construction cycle.
const streaming = ref(false)
const chatInput = ref<HTMLTextAreaElement | null>(null)

// streamReasoning is a page-level shared ref (see note above the `streaming`
// declaration): useChatScroll pins the reasoning body off it, and useChatStream
// mutates it as reasoning tokens arrive.
const streamReasoning = ref('')
// Autoscroll coordination (viewport el + scrollToBottom + reasoning-body pin)
// lives in useChatScroll; it reads the stream state it reacts to as args.
const { messagesEl, scrollToBottom } = useChatScroll(streaming, streamReasoning)

onMounted(() => {
  focusInput()
})

// Media-generation progress pollers (video job status + local image-gen
// step-percent) live in useMediaGenPolling. Both straddle the stream and
// conversation lifecycles: the page sets imageGenTurnKey and calls the start*
// controls from its stream/conversation handlers below.
const {
  videoJobStatus,
  imageGenPercent,
  imageGenTurnKey,
  startVideoPolling,
  stopVideoPolling,
  startImageProgressPolling,
} = useMediaGenPolling(messages, streaming)

onMounted(() => {
  // Capture phase: image load errors don't bubble, so a document-level listener
  // only sees them in capture. The handler is scoped to `.prose-chat`.
  document.addEventListener('error', onMarkdownImageError, true)
})

onUnmounted(() => {
  document.removeEventListener('error', onMarkdownImageError, true)
})

const displayMessages = computed(() =>
  messages.value.filter(m => shouldDisplayMessage(m, streaming.value)),
)

// Inline-subagent display state (collapsible blocks, active coding-run per
// conversation, run header labels) lives in useChatSubagents. Pure derivation
// over displayMessages + selectedConvoId; the async-announce poller below is a
// separate concern (conversation refresh) and stays here for now.
const {
  subagentRunSlices,
  activeCodingRunId,
  initSubagentCollapsedState,
  toggleSubagentRun,
  subagentBlockLabel,
  subagentBlockStatus,
} = useChatSubagents(displayMessages, selectedConvoId)

// Wire useChatConversation's loadConversation side effects now that the leaf
// composables exist (they read the conversation's messages, so it was created
// first — this closes that construction-order cycle). The hooks only fire at
// load time, so the deep-link resolution below (which can call loadConversation
// synchronously on its immediate watch) sees them already populated. focusInput
// is a hoisted function declaration, so referencing it here is safe.
convHooks.beforeLoad = () => stopVideoPolling() // don't leak a prior convo's poll loop
convHooks.afterLoad = (msgs) => {
  initSubagentCollapsedState(msgs)
  scrollToBottom()
  focusInput()
  startVideoPolling() // resume progress polling for any pending video placeholder
}

// Async-spawn announce / task-delivery poller (JCLAW-270/326) lives in
// useChatAnnouncePoller. Conversation-refresh, not display — it merges
// late-arriving rows into messages and re-runs the subagent-collapse init.
// Owns its own 5s poll timer; placed after useChatSubagents for that init dep.
const {
  announcedSubagentCount,
  hasPendingAsyncAnnounce,
  hasRecentTaskCreate,
  pollForAnnounce,
} = useChatAnnouncePoller({ messages, selectedConvoId, streaming, initSubagentCollapsedState })

// Token-usage + cost meter (latest-turn usage, cumulative tokens, running cost
// recomputed only when idle, and the JCLAW-108 model-switch divider predicate)
// lives in useChatUsageMeter, deriving off displayMessages + streaming.
const {
  shouldShowModelSwitchIndicator,
  latestAssistantUsage,
  conversationCumulativeTokens,
  conversationCostSummary,
} = useChatUsageMeter(displayMessages, streaming)

// Deep-link: if ?conversation=ID is present, load that conversation and switch
// to its agent on mount.
const route = useRoute()
const router = useRouter()
const deepLinkConvoId = route.query.conversation ? Number(route.query.conversation) : null

// Hand-off from the Apps "Create app" affordance: ?compose=<request> prefills
// the composer with the app-creator request so the operator can review + send.
// Strip the query afterward so an in-page nav / refresh doesn't re-prefill.
onMounted(async () => {
  const compose = route.query.compose
  if (typeof compose === 'string' && compose.trim()) {
    input.value = compose
    router.replace({ query: { ...route.query, compose: undefined } })
    // Programmatic v-model writes don't fire the textarea's @input handler, so
    // grow the composer to fit the prefilled request ourselves (after nextTick
    // flushes the new value into the DOM, so scrollHeight reflects it).
    await nextTick()
    autoResize()
    focusInput()
  }
})

// Deep-link: once conversations are loaded, find and select the target conversation.
// The useFetch URL function above re-fires when selectedAgentId changes, so we
// watch the conversations data to detect when the right agent's list arrives.
if (deepLinkConvoId) {
  const stopDeepLink = watch(conversations, async (convos) => {
    if (!convos || !agents.value?.length) return

    // Check if the target conversation is in the current agent's list
    const found = convos.find(c => c.id === deepLinkConvoId)
    if (found) {
      // It's in the current list — route through loadConversation so the
      // JCLAW-170 tool-calls hydration runs; skipping it here was the cause
      // of the "N × 1 tool call" split render on first page load.
      await loadConversation(deepLinkConvoId)
      initializing.value = false
      stopDeepLink()
      return
    }

    // Not in the current agent's list — resolve via direct fetch (works for
    // any channel including subagent transcripts).
    if (initializing.value) {
      await resolveAndLoadConversation(deepLinkConvoId)
      initializing.value = false
      stopDeepLink()
    }
  }, { immediate: true })

  // Safety: don't leave the watcher running forever
  onUnmounted(() => stopDeepLink())
}
else {
  // No deep-link — mark init done after first tick so agent watcher works normally
  watch(conversations, () => {
    if (initializing.value) initializing.value = false
  }, { once: true })
}

// Land the cursor in the composer textarea so the user can start typing
// immediately. Used after loadConversation (deep-link from /conversations,
// in-page Recents click, or any other entry that lands on a fresh
// conversation) and after newChat (reset state, ready for a new turn).
//
// nextTick guards two race conditions: (1) the textarea's :disabled binding
// flips with `streaming`, and a synchronously-disabled element silently
// rejects focus(); (2) on first deep-link load the template-ref is bound
// after the surrounding microtask, so a same-tick focus would no-op.
function focusInput() {
  nextTick(() => {
    chatInput.value?.focus()
  })
}

// After a model pick, land the cursor back in the composer — the same focus
// the page does on load — so the user can start typing without a second click.
// The combobox suppresses reka-ui's default focus-return-to-trigger on a pick
// (see ChatModelCombobox's close-auto-focus handler); without that, reka's
// setTimeout(0) refocus would steal focus back from the composer.
function onModelPicked(key: string) {
  onModelKeyChange(key)
  focusInput()
}

// Sidebar Recents deep-link from within /chat: NuxtLink swaps the query
// without remounting the page, so the one-shot deep-link watcher above
// doesn't re-fire. This route-query watcher catches in-page navigations
// and loads the target conversation — switching agents when the target
// belongs to a different agent's list, or entering read-only subagent-
// transcript mode when the target belongs to a subagent (which isn't in
// the dropdown).
watch(() => route.query.conversation, async (raw) => {
  const id = raw ? Number(raw) : null
  if (!id || id === selectedConvoId.value) return
  const ownedByCurrent = conversations.value?.some(c => c.id === id) ?? false
  if (ownedByCurrent) {
    clearSubagentTranscript()
    await loadConversation(id)
    return
  }
  await resolveAndLoadConversation(id)
})

function newChat() {
  // Abort any in-flight stream first so late SSE events don't land in the
  // freshly-cleared state as orphan deltas.
  if (streaming.value) stopStreaming()
  selectedConvoId.value = null
  messages.value = []
  clearSubagentTranscript()
  // Clear composer + streaming buffers so the new-chat boundary is a hard
  // reset: no half-typed prompt, no attachments carried over, no stale
  // reasoning/content placeholders. The context meter is derived from
  // messages, so emptying that is enough to zero the token counter.
  streamContent.value = ''
  streamReasoning.value = ''
  streamStatus.value = ''
  input.value = ''
  attachedFiles.value = []
  attachError.value = null
  focusInput()
}

/**
 * Landing-state predicate — true when there's no active conversation,
 * no messages in the in-memory buffer, and nothing streaming. Drives the
 * centered-hero composer layout and hides the context meter (which has
 * nothing to report yet).
 */
const isEmptyChat = computed(() =>
  selectedConvoId.value === null
  && messages.value.length === 0
  && !streaming.value,
)

// Composer attachment lifecycle (staged files, per-kind caps, previews,
// paperclip upload, voice recorder) lives in a dedicated composable so it's
// one cohesive unit. The page keeps only the read-only-transcript guarded
// event handlers below, which route into addAttachments.
const {
  fileInput,
  attachedFiles,
  attachError,
  attachmentPreviews,
  isRecording,
  addAttachments,
  removeAttachment,
  triggerFileUpload,
  toggleRecording,
  uploadAttachments,
} = useChatAttachments(configDataRef)

// SSE streaming state machine + send flow lives in useChatStream. It composes
// useStreamMarkdownRender for the throttled bubble HTML and takes the cross-
// composable coordination it needs (conversation messages/reconcile, scroll,
// media-gen pollers, attachment upload, data refresh) as deps. streaming and
// streamReasoning are shared page refs (see the note by their declarations), so
// scroll/media — constructed before this — can read them without a cycle.
const {
  streamContent,
  streamStatus,
  streamingMessageKey,
  streamContentHtml,
  streamReasoningHtml,
  sendMessage,
  stopStreaming,
} = useChatStream({
  messages,
  selectedConvoId,
  subagentTranscript,
  selectedAgentId,
  streaming,
  streamReasoning,
  input,
  chatInput,
  attachedFiles,
  attachError,
  attachmentPreviews,
  uploadAttachments,
  scrollToBottom,
  focusInput,
  imageGenTurnKey,
  startImageProgressPolling,
  startVideoPolling,
  reconcileMessageIds,
  refreshConversations,
  refreshAgents,
})

// Composer-local interaction glue (/model autocomplete, textarea keyboard +
// resize handlers, drop/paste/file-input routing, and the empty↔active FLIP
// animation) lives in useChatComposer. The composer <form> template stays in
// the page and binds chatInput / composerEl locally — no ref forwarding.
const {
  modelAutocomplete,
  composerEl,
  onInputKeydown,
  onInputEnter,
  pickAutocomplete,
  autoResize,
  handleFileUpload,
  handleDrop,
  handlePaste,
} = useChatComposer({
  input,
  providers,
  chatInput,
  subagentTranscript,
  isEmptyChat,
  addAttachments,
  sendMessage,
})

const formatAttachmentSize = formatSize

// Per-message user actions (copy/delete/edit/regenerate + the collapse toggles)
// live in useChatMessageActions. Placed after the composer + stream composables
// since regenerate/edit reach into the composer (autoResize) and stream
// (sendMessage). Its refs (copiedMessageId, tokStatsHoverKey) + handlers wire
// straight into the ChatMessage rows.
const {
  copiedMessageId,
  tokStatsHoverKey,
  copyMessage,
  copyReasoning,
  deleteMessage,
  deleteAttachment,
  editUserMessage,
  regenerateMessage,
  toggleThinking,
  toggleToolCalls,
  toggleToolCallExpansion,
} = useChatMessageActions({
  messages,
  selectedConvoId,
  streaming,
  input,
  chatInput,
  sendMessage,
  autoResize,
})

// JCLAW-25: expose the attachment-gate surface so vitest can exercise the
// visionSupported refusal without needing to synthesize a drag-drop or
// file-input event. Kept minimal — only the three symbols the test asserts
// against are surfaced. Hoisting defineExpose above the top-level awaits
// would require moving attachedFiles + addAttachments + the vision/audio
// computed refs ahead of the `await useFetch('/api/config')` call, which
// breaks the config-driven capability gates. Accepting the lint rule's
// runtime-timing concern as a known tradeoff: the test harness does its
// mountSuspended wait before calling the exposed method, so the async
// gap doesn't manifest in practice.
// eslint-disable-next-line vue/no-expose-after-await
defineExpose({ addAttachments, attachedFiles, attachError, loadConversation, messages,
  hasPendingAsyncAnnounce, hasRecentTaskCreate, pollForAnnounce, resolveAndLoadConversation,
  subagentTranscript })

function exportConversation() {
  if (!displayMessages.value.length) return
  const convo = conversations.value?.find(c => c.id === selectedConvoId.value)
  const title = convo?.preview || 'conversation'
  const lines: string[] = [`# ${title}\n`]
  for (const msg of displayMessages.value) {
    if (msg.role === 'user') {
      lines.push(`## User\n\n${msg.content}\n`)
    }
    else if (msg.role === 'assistant') {
      lines.push(`## Assistant\n\n${msg.content}\n`)
    }
  }
  const blob = new Blob([lines.join('\n')], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `${title.replaceAll(/[^a-zA-Z0-9 ]/g, '').replaceAll(/\s+/g, '-').toLowerCase()}.md`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <!--
    Height subtracts the 3.5rem (56px) top nav bar (see layouts/default.vue
    header.h-14) — NOT the parent main's p-6 padding, which is already
    cancelled by the -m-6 on this element. Using 3rem here leaves main
    with an 8px hidden scroll range: aggressive scroll in the messages
    pane chains up to main and pushes the chat header off the top by
    that amount, compressing its apparent top padding.
  -->
  <div
    class="flex -m-6"
    style="height: calc(100vh - 3.5rem);"
  >
    <!--
      Chat area takes the full main-content width now that the in-page
      Conversations sidebar has been removed. Multi-select delete + bulk
      management live on the dedicated /conversations page.
    -->
    <div class="flex-1 flex flex-col">
      <!--
        Chat header — Unsloth-style: compact agent selector on the left,
        searchable model combobox absolute-centered, context meter on the
        right. The per-turn Thinking level select moved out of the header;
        the Think pill in the composer footer owns on/off, and the level is
        controlled from the agent detail page.
      -->
      <!--
        Three-zone header: agent selector on the left, model combobox
        absolute-centered on the row midpoint, context meter pushed to the
        right edge. Absolute positioning on the model combobox keeps it
        optically centered regardless of the left/right content widths.
      -->
      <!--
        border-b uses neutral-300/700 instead of --border (which is
        neutral-200/800 — near-isoluminant with --surface-elevated in
        dark mode and invisible). One palette step up on both modes
        lifts the header above the chat canvas without jumping to a
        hard division line.
      -->
      <div class="relative px-3 py-2 border-b border-neutral-300 dark:border-neutral-700 flex items-center gap-2">
        <ChatAgentSelector
          v-model="selectedAgentId"
          :agents="agents"
        />
        <div class="absolute left-1/2 -translate-x-1/2">
          <ChatModelCombobox
            :providers="providers"
            :model-key="selectedModelKey"
            :status-tone="streaming ? 'busy' : (selectedAgent?.providerConfigured === false ? 'offline' : 'ok')"
            @update:model-key="onModelPicked"
          />
        </div>
        <span class="ml-auto flex items-center gap-2">
          <ChatContextMeter
            v-if="!isEmptyChat"
            :prompt-tokens="latestAssistantUsage?.prompt ?? 0"
            :completion-tokens="latestAssistantUsage?.completion ?? 0"
            :reasoning-tokens="latestAssistantUsage?.reasoning ?? 0"
            :cached-tokens="latestAssistantUsage?.cached ?? 0"
            :context-window="(selectedModelInfo?.contextWindow as number | undefined) ?? null"
            :cumulative-tokens="conversationCumulativeTokens"
            :compaction-count="currentConversation?.compactionCount ?? 0"
            :cost-label="conversationCostSummary?.label ?? null"
            :cost-tooltip="conversationCostSummary?.tooltip ?? null"
            :turn-count="conversationCostSummary?.turnCount ?? null"
          />
        </span>
      </div>

      <!--
        Body wrapper: a flex-col with a pair of spacers (top + bottom)
        that reflow between 0 and 1fr. When the chat is empty the
        spacers grow so the hero + composer sit at vertical center;
        when a conversation is active they collapse and the messages
        scroller takes over. The flex reflow itself is instant — the
        composer's apparent travel from centered to docked is animated
        via a FLIP watcher (see composerEl ref above) that snapshots the
        composer's rect before/after the reflow and interpolates the
        delta. Mirrors Unsloth Studio's motion.div layoutId mechanism.
      -->
      <div class="flex-1 flex flex-col min-h-0">
        <!-- Top spacer: grows to push content toward vertical center on empty. -->
        <div
          class="shrink-0 basis-0"
          :style="{ flexGrow: isEmptyChat ? 1 : 0 }"
          aria-hidden="true"
        />

        <!--
          Empty-state hero: fades + slides down from above on enter,
          unmounts instantly on leave. The 500ms duration and easing
          match the composer's FLIP (above) so when the user starts a
          new conversation the hero descends from the top while the
          composer ascends from the bottom and they settle into the
          centered layout together. Leave is instant because the
          composer's FLIP carries the empty→active visual continuity;
          a leave-active fade would keep the hero positioned in the
          DOM after the spacers collapsed, producing layout jump.
        -->
        <Transition
          enter-active-class="transition-all duration-500 ease-[cubic-bezier(0.32,0.72,0,1)]"
          enter-from-class="opacity-0 -translate-y-8"
          enter-to-class="opacity-100 translate-y-0"
        >
          <div
            v-if="isEmptyChat"
            class="mx-auto w-full max-w-3xl flex flex-col items-center gap-3 px-4 pb-4 text-center"
          >
            <img
              src="/clawdia-typing.webp"
              alt=""
              class="h-32 w-auto select-none"
            >
            <h1 class="text-2xl font-semibold text-fg-strong">
              Chat with your agent
            </h1>
            <p class="text-sm text-fg-muted">
              Pick an agent, pick a model, and start a new conversation.
            </p>
          </div>
        </Transition>

        <!--
          JCLAW-274: subagent-transcript banner. Surfaces above the message
          list when {@code subagentTranscript} is set — i.e. the user landed
          on this conversation via a "View transcript" link from /subagents
          or a {@code subagent_announce} card. The composer is disabled
          below; this banner makes the read-only state obvious so the user
          doesn't try to type and wonder why nothing sends.
        -->
        <div
          v-if="subagentTranscript"
          data-testid="subagent-transcript-banner"
          class="mx-auto w-full max-w-3xl px-4 pt-3"
        >
          <div
            class="flex items-center gap-2 px-3 py-2 text-xs bg-blue-50 dark:bg-blue-400/10
                   border border-blue-200 dark:border-blue-400/20 text-blue-700 dark:text-blue-300 rounded"
          >
            <span class="font-mono uppercase tracking-wide">Read-only</span>
            <span>Subagent transcript for <strong>{{ subagentTranscript.agentName }}</strong></span>
          </div>
        </div>

        <!--
          JCLAW-326: parent-conversation → /subagents deep-link banner.
          Only renders for a real parent conversation that has at least one
          subagent_announce in its message list; mutually exclusive with the
          subagentTranscript banner above (you can't be both viewing a
          child's transcript and the parent of a run at the same time).
          Counts unique run-ids so the banner figure matches the count of
          distinct rows the /subagents page will show under this filter.
        -->
        <div
          v-if="!subagentTranscript && selectedConvoId && announcedSubagentCount > 0"
          data-testid="conversation-subagents-banner"
          class="mx-auto w-full max-w-3xl px-4 pt-3"
        >
          <NuxtLink
            :to="`/subagents?parentConversationId=${selectedConvoId}`"
            class="flex items-center gap-2 px-3 py-2 text-xs bg-muted/40 border border-input
                   text-fg-muted hover:text-fg-strong hover:border-neutral-500 rounded transition-colors"
          >
            <UsersIcon
              class="w-3.5 h-3.5 shrink-0"
              aria-hidden="true"
            />
            <span>
              <strong>{{ announcedSubagentCount }}</strong>
              {{ announcedSubagentCount === 1 ? 'subagent' : 'subagents' }} spawned
              in this conversation
            </span>
            <span class="ml-auto">View list →</span>
          </NuxtLink>
        </div>

        <!--
          Messages — overscroll-contain stops trackpad/wheel momentum
          from chaining into the ancestor <main> if a height mismatch
          ever re-introduces a scroll range there (e.g. when the API
          status banner is visible). Without it, aggressive scroll in
          this list could push the chat header up out of view.
        -->
        <div
          v-if="!isEmptyChat"
          ref="messagesEl"
          class="flex-1 overflow-y-auto overflow-x-hidden overscroll-contain px-4 py-6"
        >
          <!--
          Unsloth-style centered content column: the scroll container
          stays full-width so the scrollbar tracks the window edge, but
          each turn sits inside a max-w-3xl rail that keeps long lines
          readable regardless of viewport width.

          `px-4` mirrors the composer wrapper's internal padding so the
          rail's usable content box aligns exactly with the composer
          form's visible borders — message text won't overhang the
          card's left edge, and the user bubble's right edge won't stick
          past the card's right edge.
        -->
          <div class="mx-auto w-full max-w-3xl px-4 space-y-5">
            <ChatMessage
              v-for="(msg, msgIdx) in displayMessages"
              :key="msg.id ?? msg._key"
              :msg="msg"
              :msg-idx="msgIdx"
              :render-token="messageRenderKey(msg)"
              :agent-id="effectiveDisplayAgentId"
              :streaming="streaming"
              :copied-message-id="copiedMessageId"
              :streaming-message-key="streamingMessageKey"
              :stream-content="streamContent"
              :stream-content-html="streamContentHtml"
              :stream-reasoning-html="streamReasoningHtml"
              :video-job-status="videoJobStatus"
              :image-gen-turn-key="imageGenTurnKey"
              :image-gen-percent="imageGenPercent"
              :tok-stats-hover-key="tokStatsHoverKey"
              :run-slice="subagentRunSlices[msgIdx] ?? null"
              :run-label="subagentRunSlices[msgIdx] ? subagentBlockLabel(subagentRunSlices[msgIdx]!.runId, displayMessages) : ''"
              :run-status="subagentRunSlices[msgIdx] ? subagentBlockStatus(subagentRunSlices[msgIdx]!.runId, displayMessages) : ''"
              :show-model-switch="shouldShowModelSwitchIndicator(msgIdx)"
              @toggle-subagent-run="toggleSubagentRun"
              @delete-attachment="deleteAttachment"
              @toggle-tool-calls="toggleToolCalls"
              @toggle-tool-call-expansion="toggleToolCallExpansion"
              @toggle-thinking="toggleThinking"
              @copy-reasoning="copyReasoning"
              @copy-message="copyMessage"
              @edit-user-message="editUserMessage"
              @delete-message="deleteMessage"
              @regenerate-message="regenerateMessage"
              @set-tok-stats-hover-key="tokStatsHoverKey = $event"
            />
            <!--
              Pre-first-byte placeholder. Visible only during the gap between
              "user sent the request" and "the first stream event (reasoning
              OR content) arrived." Once either signal lands, displayMessages
              starts rendering the real bubble (reasoning card and/or
              markdown body) and this placeholder yields. Without the
              streamReasoning guard, JCLAW-75 regression: reasoning-mode
              turns would show this pill for the entire thinking phase.

              Rendered as plain "Generating..." text — no bubble, no
              "assistant" label — matching Unsloth Studio's landing.
            -->
            <div
              v-if="streaming && !streamContent && !streamReasoning"
              class="text-base text-fg-muted animate-pulse"
            >
              Generating...
            </div>
          </div>
        </div>

        <!--
          JCLAW-663: live external coding-harness monitor. Docked above the
          composer (not inside the message scroller) so its step stream and
          Kill control stay in view while the harness runs. Only mounts when a
          coding run is active for the open conversation.
        -->
        <CodingRunMonitor
          v-if="activeCodingRunId"
          :key="activeCodingRunId"
          :run-id="activeCodingRunId"
        />

        <!-- Input -->
        <div
          ref="composerEl"
          class="px-4 py-3 relative mx-auto w-full max-w-3xl"
        >
          <!-- JCLAW-114: slash model NAME autocomplete popup, anchored above the
             form. Rendered outside the form so the form's overflow-hidden
             needed for rounded borders does not clip the popup. The wrapper
             uses ARIA listbox semantics because no native HTML element covers
             the WAI ARIA combobox plus listbox pattern for typeahead pickers;
             aria-label is provided for screen readers. -->
          <div
            v-if="modelAutocomplete.open.value"
            class="absolute left-4 right-4 bottom-full mb-1 z-10
                 bg-surface-elevated border border-border rounded-md shadow-lg
                 max-h-60 overflow-y-auto"
            role="listbox"
            aria-label="Model completion options"
          >
            <!-- Each row uses ARIA option semantics inside the parent listbox; the native HTML option element only works inside select. aria-selected is dynamically bound via the Vue colon shorthand, which Sonar's static analyser does not resolve. -->
            <button
              v-for="(opt, idx) in modelAutocomplete.options.value"
              :key="opt"
              type="button"
              :class="idx === modelAutocomplete.highlightedIndex.value
                ? 'bg-muted text-fg-strong'
                : 'text-fg-default hover:bg-muted/50'"
              class="block w-full text-left px-3 py-1.5 text-xs font-mono transition-colors"
              :aria-selected="idx === modelAutocomplete.highlightedIndex.value"
              role="option"
              @mousedown.prevent="pickAutocomplete(opt)"
            >
              {{ opt }}
            </button>
          </div>
          <!--
          JCLAW-25: drop/paste handlers on the composer form are the
          standard chat-composer pattern (Slack, Discord, ChatGPT all do
          this). Keyboard users retain paste via Ctrl/Cmd+V, which is a
          first-class native handler on the textarea inside, so the a11y
          loss is minimal.
        -->
          <!--
          Composer card — traced from Unsloth Studio via devtools.
          rounded-[22px] (their rounded-3xl), 1px border in #2e3035
          (neutral-700 at 50% opacity) in dark mode. Border override is
          needed because the project's --border token is invisible
          against --surface-elevated in dark mode.

          Shadow differs by theme:
          - light: three-layer stacked drop shadow at 4–5% alpha,
            matching Unsloth's composer — tight edge line (0 1px 2px
            .04), mid cushion (0 6px 14px .05), diffuse lift (0 18px
            40px .05). Reads as a soft float, not a dark halo.
          - dark: single 0 4px 16px at 30% alpha (#0000004d), the
            same value used by the login/setup-password cards. Below
            ~25% alpha, black shadows vanish against #222427 — this
            is the "elevated card" token across the auth pages.
        -->
          <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
          <form
            data-tour="chat-composer"
            class="bg-surface-elevated border border-neutral-200 dark:border-neutral-700/50 rounded-[22px]
                 shadow-[0_1px_2px_rgba(0,0,0,0.04),0_6px_14px_rgba(0,0,0,0.05),0_18px_40px_rgba(0,0,0,0.05)]
                 dark:shadow-[0_4px_16px_#0000004d]
                 overflow-hidden"
            @submit.prevent="sendMessage"
            @drop.prevent="handleDrop"
            @dragover.prevent
            @paste="handlePaste"
          >
            <div
              v-if="attachedFiles.length || attachError"
              class="px-3 pt-2.5 pb-1 flex flex-wrap gap-1.5"
            >
              <span
                v-for="(f, idx) in attachedFiles"
                :key="`${f.name}-${idx}`"
                class="inline-flex items-center gap-1.5 px-2 py-1 bg-muted border border-input rounded text-[11px] text-fg-primary"
              >
                <img
                  v-if="attachmentPreviews.get(f)"
                  :src="attachmentPreviews.get(f)"
                  :alt="f.name"
                  class="w-6 h-6 object-cover rounded-sm shrink-0"
                >
                <PaperClipIcon
                  v-else
                  class="w-3 h-3 text-fg-muted shrink-0"
                  aria-hidden="true"
                />
                <span
                  class="truncate max-w-[140px]"
                  :title="f.name"
                >{{ f.name }}</span>
                <span class="text-fg-muted">{{ formatAttachmentSize(f.size) }}</span>
                <button
                  type="button"
                  class="ml-0.5 text-fg-muted hover:text-fg-strong transition-colors"
                  title="Remove"
                  @click="removeAttachment(idx)"
                >
                  <XMarkIcon
                    class="w-3 h-3"
                    aria-hidden="true"
                  />
                </button>
              </span>
              <span
                v-if="attachError"
                class="inline-flex items-center gap-1.5 px-2 py-1 bg-red-50 dark:bg-red-900/30 border border-red-200 dark:border-red-800/50 rounded text-[11px] text-red-700 dark:text-red-300"
              >
                <span>{{ attachError }}</span>
                <button
                  type="button"
                  class="text-red-700 dark:text-red-400/70 hover:text-red-800 dark:hover:text-red-200 transition-colors"
                  title="Dismiss"
                  @click="attachError = null"
                >
                  <XMarkIcon
                    class="w-3 h-3"
                    aria-hidden="true"
                  />
                </button>
              </span>
            </div>
            <textarea
              id="chat-message-input"
              ref="chatInput"
              v-model="input"
              :placeholder="subagentTranscript ? 'Subagent transcripts are read-only' : 'Send a message...'"
              :disabled="streaming || !!subagentTranscript"
              rows="1"
              aria-label="Message input"
              class="w-full px-4 pt-4 pb-6 bg-transparent text-sm text-fg-strong
                   placeholder-fg-muted focus:outline-hidden resize-none overflow-y-auto overflow-x-hidden
                   disabled:cursor-not-allowed"
              @keydown.enter.exact="onInputEnter"
              @keydown.down="onInputKeydown"
              @keydown.up="onInputKeydown"
              @keydown.tab="onInputKeydown"
              @keydown.esc="onInputKeydown"
              @input="autoResize"
            />
            <input
              id="chat-file-upload"
              ref="fileInput"
              type="file"
              multiple
              aria-label="Upload files"
              class="hidden"
              @change="handleFileUpload"
            >
            <div class="grid grid-cols-[1fr_auto_1fr] items-center gap-2 px-2 pb-2">
              <div class="flex items-center gap-1.5 flex-wrap">
                <button
                  type="button"
                  class="inline-flex items-center justify-center w-8 h-8 rounded-full border border-border text-fg-muted hover:text-fg-strong hover:bg-muted transition-colors"
                  title="Attach file"
                  @click="triggerFileUpload"
                >
                  <!--
                  Lucide-style paperclip — parity with Unsloth's attach
                  affordance. The rounded border + bg-muted hover give a
                  subtle, pressable surface.
                -->
                  <PaperClipIcon
                    class="w-4 h-4"
                    aria-hidden="true"
                  />
                </button>
                <!--
                  Voice recorder — same affordance shape as the paperclip
                  (round 32x32, neutral border) so the row reads as a row of
                  attach-type actions. Always visible, parallel to the
                  paperclip: the audio-capability gate fires at click time,
                  not as a v-if, so the button disappearing can't surprise a
                  user who just swapped models. While recording, the icon
                  swaps to solid red and pulses so the operator can see from
                  across the room that the mic is hot.
                -->
                <button
                  type="button"
                  class="inline-flex items-center justify-center w-8 h-8 rounded-full border transition-colors"
                  :class="isRecording
                    ? 'border-red-500 text-red-700 dark:text-red-400 bg-red-500/10 animate-pulse'
                    : 'border-border text-fg-muted hover:text-fg-strong hover:bg-muted'"
                  :title="isRecording ? 'Stop recording' : 'Record voice'"
                  :aria-pressed="isRecording"
                  @click="toggleRecording"
                >
                  <component
                    :is="isRecording ? MicrophoneIconSolid : MicrophoneIcon"
                    class="w-4 h-4"
                    aria-hidden="true"
                  />
                </button>
                <!--
                  Voice mode (JCLAW-791): a live spoken conversation with the
                  agent — separate from "Record voice" above, which attaches an
                  audio clip. Opens the voice overlay for the current agent.
                -->
                <button
                  type="button"
                  class="inline-flex items-center justify-center w-8 h-8 rounded-full border border-border text-fg-muted hover:text-fg-strong hover:bg-muted transition-colors"
                  title="Voice mode — talk with the agent"
                  aria-label="Start voice mode"
                  @click="voiceModeActive = true"
                >
                  <PhoneIcon
                    class="w-4 h-4"
                    aria-hidden="true"
                  />
                </button>
              </div>
              <!--
                Model-capability toggles. Unsloth-style rounded-full pills:
                when active the pill paints in the capability colour; inactive
                pills stay outlined neutral. Visible only when the model
                advertises the capability. Think flips thinkingMode null ↔
                remembered level; Vision/Audio flip their *Enabled override.
                Grouped in the row's center track; the 1fr side tracks keep
                this group anchored to the container midpoint regardless of
                how many capability pills render.
              -->
              <div class="flex items-center gap-1.5 flex-wrap justify-self-center">
                <button
                  v-if="thinkingSupported"
                  ref="thinkPillRef"
                  type="button"
                  class="inline-flex items-center gap-1.5 px-3 h-8 rounded-full text-xs font-medium transition-colors"
                  :class="[
                    thinkingLock.locked
                      ? 'bg-emerald-700/30 text-emerald-300 cursor-not-allowed'
                      : (thinkingActive
                        ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400 hover:bg-emerald-500/25'
                        : 'border border-border text-fg-muted hover:text-fg-strong hover:bg-muted'),
                  ]"
                  :aria-disabled="thinkingLock.locked"
                  :aria-haspopup="thinkingActive && thinkingLevels.length > 1 && !thinkingLock.locked ? 'menu' : undefined"
                  :aria-expanded="thinkingMenuOpen"
                  :title="thinkingLock.locked
                    ? thinkingLock.reason
                    : (thinkingActive ? 'Thinking on — click to turn off, or hover to pick a level' : 'Thinking off — click to turn on')"
                  @click="toggleThinkingPill"
                  @mouseenter="openThinkingMenu"
                  @mouseleave="scheduleCloseThinkingMenu"
                  @focus="openThinkingMenu"
                  @blur="scheduleCloseThinkingMenu"
                >
                  <component
                    :is="thinkingLock.locked ? LightBulbIconSolid : LightBulbIcon"
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                  Think
                </button>
                <!--
                  Vision pill: capability indicator only. Shown when the model
                  accepts images natively (image_url). JCLAW-215: images are
                  accepted on non-vision models too — they're captioned
                  server-side into a text description — so this is now a
                  "native vs captioned" hint, not a gate. Span, not button.
                -->
                <span
                  v-if="visionSupported"
                  class="inline-flex items-center gap-1.5 px-3 h-8 rounded-full text-xs font-medium bg-sky-500/15 text-sky-700 dark:text-sky-400 cursor-default"
                  title="This model accepts image inputs natively. Other models receive a generated text description."
                >
                  <EyeIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                  Vision
                </span>
                <!--
                  JCLAW-165: capability indicator only. Renders when the
                  active model advertises native audio passthrough; not a
                  toggle — voice notes to non-supportsAudio models still go
                  through the transcription pipeline transparently. Span,
                  not button — there's no behaviour to invite clicking on.
                -->
                <span
                  v-if="audioSupported"
                  class="inline-flex items-center gap-1.5 px-3 h-8 rounded-full text-xs font-medium bg-amber-500/15 text-amber-700 dark:text-amber-400 cursor-default"
                  title="This model handles audio natively. Voice notes pass through directly; non-audio models receive a transcript."
                >
                  <SpeakerWaveIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                  Audio
                </span>
                <!--
                  Video pill: capability indicator only. Shown when the model
                  accepts video natively (Qwen-VL). Uploaded videos work on any
                  model — non-video models route to the dedicated video model
                  (Settings → Video Interpretation), else frames/captions. Span,
                  not a gate.
                -->
                <span
                  v-if="videoSupported"
                  class="inline-flex items-center gap-1.5 px-3 h-8 rounded-full text-xs font-medium bg-purple-500/15 text-purple-400 cursor-default"
                  title="This model accepts video natively. Other models route to the dedicated video model, then to frames or a captioned summary."
                >
                  <FilmIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                  Video
                </span>
              </div>
              <div class="flex items-center gap-1 justify-self-end">
                <button
                  type="button"
                  class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
                  title="New conversation"
                  @click="newChat"
                >
                  <!--
                  Square-with-pencil "edit/compose" glyph (Heroicons
                  PencilSquareIcon) — same semantic as Unsloth's "new chat"
                  button. Reads as "start a new conversation" without
                  needing the tooltip.
                -->
                  <PencilSquareIcon
                    class="w-4 h-4"
                    aria-hidden="true"
                  />
                </button>
                <button
                  type="button"
                  :disabled="!displayMessages.length"
                  class="p-1.5 text-fg-muted hover:text-fg-strong disabled:text-neutral-300 dark:disabled:text-neutral-700 transition-colors"
                  title="Export as Markdown"
                  @click="exportConversation"
                >
                  <ArrowDownTrayIcon
                    class="w-4 h-4"
                    aria-hidden="true"
                  />
                </button>
                <button
                  v-if="streaming"
                  type="button"
                  class="p-1.5 text-red-700 dark:text-red-400 hover:text-red-400 transition-colors"
                  title="Stop generating"
                  @click="stopStreaming"
                >
                  <StopIconSolid
                    class="w-5 h-5"
                    aria-hidden="true"
                  />
                </button>
                <button
                  v-else
                  type="submit"
                  :disabled="!input.trim() && !attachedFiles.length"
                  class="p-1.5 transition-colors
                         enabled:text-emerald-400 enabled:hover:text-emerald-300
                         disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed"
                  title="Send"
                >
                  <PaperAirplaneIcon
                    class="w-5 h-5 -rotate-45"
                    aria-hidden="true"
                  />
                </button>
              </div>
            </div>
          </form>
          <!--
            Reasoning-level picker for the Think pill. Teleported to <body>
            because the composer <form> above has overflow-hidden (needed for
            its rounded-[22px] border) which would otherwise clip the upward-
            growing menu — z-index alone can't escape an overflow:hidden
            ancestor. With Teleport the menu becomes a viewport-anchored
            floater positioned via thinkingMenuStyle (computed from the Think
            button's bounding rect on open + scroll/resize).
          -->
          <Teleport to="body">
            <Transition
              enter-active-class="transition duration-100"
              enter-from-class="opacity-0 translate-y-1"
              enter-to-class="opacity-100 translate-y-0"
              leave-active-class="transition duration-75"
              leave-from-class="opacity-100 translate-y-0"
              leave-to-class="opacity-0 translate-y-1"
            >
              <div
                v-if="thinkingMenuOpen && thinkingActive && thinkingLevels.length > 1 && !thinkingLock.locked"
                role="menu"
                tabindex="-1"
                class="fixed flex flex-col bg-surface-elevated border border-border rounded-lg shadow-lg overflow-hidden z-50 min-w-24"
                :style="thinkingMenuStyle"
                @mouseenter="openThinkingMenu"
                @mouseleave="scheduleCloseThinkingMenu"
                @focusin="openThinkingMenu"
                @focusout="scheduleCloseThinkingMenu"
              >
                <button
                  v-for="level in thinkingLevels"
                  :key="level"
                  role="menuitem"
                  type="button"
                  class="px-3 py-1.5 text-xs font-medium text-left whitespace-nowrap transition-colors"
                  :class="selectedAgent?.thinkingMode === level
                    ? 'bg-emerald-500/15 text-emerald-700 dark:text-emerald-400 hover:bg-emerald-500/25'
                    : 'text-fg-muted hover:text-fg-strong hover:bg-muted'"
                  @click="setThinkingLevel(level)"
                >
                  {{ level.charAt(0).toUpperCase() + level.slice(1) }}
                </button>
              </div>
            </Transition>
          </Teleport>
          <p
            v-if="!isEmptyChat"
            class="mt-1.5 text-center text-[11px] text-fg-muted"
          >
            LLMs can make mistakes. Double-check all responses.
          </p>
        </div>
        <!-- Bottom spacer: mirror of the top spacer, so the composer
             lands at vertical center of the body during the empty
             state rather than just being pushed down from the top.
             No transition — the reflow is instant by design (see the
             note on the top spacer). -->
        <div
          class="shrink-0 basis-0"
          :style="{ flexGrow: isEmptyChat ? 1 : 0 }"
          aria-hidden="true"
        />
      </div> <!-- end body wrapper -->
    </div>
    <ChatVoiceOverlay
      v-if="voiceModeActive && selectedAgentId != null"
      :agent-id="selectedAgentId!"
      @close="voiceModeActive = false"
    />
  </div>
</template>

<style>
/* Markdown rendering styles for chat messages.
 * Structural rules first, then light-mode palette as the default, with
 * `html.dark .prose-chat …` overrides mirroring the original dark palette.
 * The outer `.prose-chat` wrapper already uses Tailwind `dark:` utilities for
 * the surface bg/border/text — these rules only target nested markdown output
 * that the Vue template can't reach directly. */
.prose-chat { overflow-wrap: anywhere; }
.prose-chat p { margin: 0.5em 0; }
.prose-chat p:first-child { margin-top: 0; }
.prose-chat p:last-child { margin-bottom: 0; }

.prose-chat ul, .prose-chat ol {
  margin: 0.5em 0;
  padding-left: 1.5em;
}
.prose-chat li { margin: 0.25em 0; }

/* Headings, code blocks, links, blockquote, hr, img: structural + light-mode
   palette are merged here. Dark-mode overrides further below restate only the
   colours that change. */
.prose-chat h1, .prose-chat h2, .prose-chat h3 {
  font-weight: 600;
  margin: 0.75em 0 0.25em;
  color: #171717;
}
.prose-chat h1 { font-size: 1.1em; }
.prose-chat h2 { font-size: 1em; }
.prose-chat h3 { font-size: 0.95em; }

.prose-chat pre {
  padding: 0.75em 1em;
  margin: 0.5em 0;
  overflow-x: auto;
  background: rgb(0,0,0,4%);
  border: 1px solid rgb(0,0,0,8%);
}
.prose-chat pre code { background: none; padding: 0; }

/* #171717 (near-black) on a 6%-black-over-light-surface background is well above
   WCAG AA. Sonar reports a false low-contrast reading because it cannot resolve
   the alpha background against the surrounding surface. */
.prose-chat code {
  padding: 0.15em 0.35em;
  font-size: 0.875em;
  font-family: ui-monospace, monospace;
  background: rgb(0,0,0,6%);
  color: #171717;
}

.prose-chat a {
  text-decoration: underline;
  color: #525252;
}

/* Effective background here is approximately #e8f6f0 (emerald 8% over a white
   surface); emerald-700 (#047857) on that background measures around 6.3 to 1
   (WCAG AA pass for normal text) per the WebAIM contrast checker. Sonar
   reports a false low-contrast reading because it cannot resolve the alpha
   against the surrounding surface. */
.prose-chat a.workspace-file {
  display: inline-flex;
  align-items: center;
  gap: 0.4em;
  padding: 0.25em 0.6em;
  margin: 0.15em 0.15em 0.15em 0;
  border-radius: 0.4em;
  text-decoration: none;
  font-size: 0.9em;
  transition: background 0.15s, border-color 0.15s;
  background: rgb(16, 185, 129, 8%);
  border: 1px solid rgb(16, 185, 129, 35%);
  color: #047857;
}
.prose-chat a.workspace-file::before { content: "⬇"; font-size: 0.85em; opacity: 0.75; }

.prose-chat blockquote {
  padding-left: 0.75em;
  margin: 0.5em 0;
  border-left: 2px solid rgb(0,0,0,12%);
  color: #525252;
}

.prose-chat hr {
  border: none;
  margin: 0.75em 0;
  border-top: 1px solid rgb(0,0,0,10%);
}

.prose-chat img {
  max-width: 100%;
  height: auto;
  border-radius: 0.5em;
  margin: 0.5em 0;
  cursor: pointer;
  border: 1px solid rgb(0,0,0,8%);
}

.prose-chat audio, .prose-chat video {
  max-width: 100%;
  margin: 0.5em 0;
  border-radius: 0.5em;
}

.prose-chat table {
  /* Auto layout — each table sizes its columns from its own content. The
     prior approach pinned the first column to 14em + nowrap so a tools
     table and a skills table side-by-side would have aligned identifier
     columns; that worked for short snake_case identifiers but rendered
     long-content first columns (e.g. news headlines from daily-briefing)
     as horizontal overflow that visually overlapped the second column.
     Cross-table alignment was a niche optimization; correct rendering for
     any first-column content is the better default. */
  table-layout: auto;
  border-collapse: collapse;
  margin: 0.5em 0;
  width: 100%;
  font-size: 0.95em;
}

.prose-chat th, .prose-chat td {
  padding: 0.4em 0.75em;
  text-align: left;
  vertical-align: top;

  /* Override the `.prose-chat { overflow-wrap: anywhere }` inherited from
     the wrapper. `anywhere` lets the table-layout algorithm shrink columns
     to a 1ch min-content size, which on auto-layout was breaking short
     identifiers ("exec", "filesystem") character-by-character. `break-word`
     keeps the long-URL behavior — content still wraps when it would
     overflow — but pins the min-content size to the longest word, so
     auto-layout sizes each column to fit its widest cell. */
  overflow-wrap: break-word;
}

/* Light-mode-only rules: strong and em palette, plus interaction states and
   table cells that don't have a structural counterpart above. Dark-mode
   overrides further below restate only the colours that change. */
.prose-chat strong { color: #171717; font-weight: 600; }
.prose-chat em { color: #404040; }

.prose-chat a.workspace-file:hover {
  background: rgb(16, 185, 129, 18%);
  border-color: rgb(16, 185, 129, 60%);
}
.prose-chat img:hover { border-color: rgb(0,0,0,20%); }
.prose-chat th { color: #171717; border-bottom: 1px solid rgb(0,0,0,15%); font-weight: 600; }
.prose-chat td { border-bottom: 1px solid rgb(0,0,0,6%); }

/* Dark-mode overrides */
html.dark .prose-chat strong { color: #e5e5e5; }
html.dark .prose-chat em { color: #d4d4d4; }

html.dark .prose-chat h1,
html.dark .prose-chat h2,
html.dark .prose-chat h3 { color: #e5e5e5; }
html.dark .prose-chat code { background: rgb(255,255,255,6%); color: inherit; }
html.dark .prose-chat pre { background: rgb(255,255,255,4%); border-color: rgb(255,255,255,8%); }
html.dark .prose-chat a { color: #a3a3a3; }

/* Effective background here is approximately #1d2a25 (emerald 10% over the
   dark surface); emerald-300 (#6ee7b7) on that background measures around
   10 to 1 (WCAG AAA pass) per the WebAIM contrast checker. Sonar reports a
   false low-contrast reading because it cannot resolve the alpha against the
   surrounding dark surface. */
html.dark .prose-chat a.workspace-file {
  background: rgb(16, 185, 129, 10%);
  border-color: rgb(16, 185, 129, 30%);
  color: #6ee7b7;
}

html.dark .prose-chat a.workspace-file:hover {
  background: rgb(16, 185, 129, 18%);
  border-color: rgb(16, 185, 129, 55%);
}
html.dark .prose-chat blockquote { border-left-color: rgb(255,255,255,10%); color: #a3a3a3; }
html.dark .prose-chat hr { border-top-color: rgb(255,255,255,8%); }
html.dark .prose-chat img { border-color: rgb(255,255,255,8%); }
html.dark .prose-chat img:hover { border-color: rgb(255,255,255,20%); }
html.dark .prose-chat th { color: #e5e5e5; border-bottom-color: rgb(255,255,255,15%); }
html.dark .prose-chat td { border-bottom-color: rgb(255,255,255,6%); }
</style>
