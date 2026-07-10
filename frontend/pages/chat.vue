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
import { computeConversationCost, formatConversationCost, formatConversationCostTooltip, type MessageUsage } from '~/utils/usage-cost'
import { formatSize } from '~/utils/format'
import { initCollapsedState } from '~/utils/thinking'
import { hydrateToolCalls } from '~/utils/tool-calls'
import { resolveThinkingLock } from '~/utils/thinking-lock'
// Filter out tool messages and empty assistant messages (tool call records) from display.
// The predicate lives in ~/utils/display-message-filter for unit-testability; see
// JCLAW-75 for the specific reasoning-stream regression the reasoning-aware
// suppression rule closes.
import { shouldDisplayMessage } from '~/utils/display-message-filter'
import { backfillServerIds } from '~/utils/message-reconcile'

import type { Agent, Conversation, Message, MessageAttachment, ConfigResponse, ToolCall } from '~/types/api'
import { effectiveThinkingLevels, type ProviderModel } from '~/composables/useProviders'
import { useModelAutocomplete } from '~/composables/useModelAutocomplete'
import { useChatAttachments, type UploadedAttachment } from '~/composables/useChatAttachments'
import { useChatScroll } from '~/composables/useChatScroll'
import { useChatConversation, type ChatConversationLoadHooks } from '~/composables/useChatConversation'
import { useChatSubagents } from '~/composables/useChatSubagents'
import { useMediaGenPolling } from '~/composables/useMediaGenPolling'
import { useStreamMarkdownRender } from '~/composables/useStreamMarkdownRender'
import ChatMessage from '~/components/chat/ChatMessage.vue'

// Local helpers for fields that streaming/optimistic bubbles carry in addition
// to the persisted Message shape. Kept co-located rather than in types/api.ts
// because these are client-only stream-state fields, not part of the wire
// contract.
interface StreamingMessage extends Message {
  _thinkingInProgress?: boolean
}

// Queue status returned by GET /api/conversations/:id/queue — the chat page
// uses this to decide whether to keep the "agent busy" banner showing after a
// stream completes.
interface ConversationQueueStatus {
  busy: boolean
  [key: string]: unknown
}

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

// A11y: generated ids for label/control association
const agentSelectId = useId()

// Extract configured providers and their models from config
const configDataRef = computed(() => configData.value ?? null)
const { providers } = useProviders(configDataRef)

// The currently selected agent object
const selectedAgent = computed(() => agents.value?.find(a => a.id === selectedAgentId.value))

// Current model info for the selected agent. Looks up across ALL configured
// providers — not just the agent's own provider — because the chat dropdown
// lets the user pick any model from any enabled provider without going back
// to the Agents page. Encoded provider + id avoids ambiguity when two
// providers happen to expose the same model id (e.g. "kimi-k2.5").
/**
 * The currently open conversation row, if any. Exposes the modelProvider /
 * modelId override fields (JCLAW-108) so the model dropdown can reflect
 * per-conversation state.
 *
 * Defensive: useFetch's `data` can briefly hold non-array values during
 * pending / error states (null, SSR hydration mismatch, or a route that
 * returned an error object). Check Array.isArray before .find rather than
 * relying on optional chaining alone.
 */
const currentConversation = computed(() => {
  const list = conversations.value
  if (!Array.isArray(list)) return null
  return list.find(c => c.id === selectedConvoId.value) ?? null
})

/**
 * Effective (provider, modelId) for the currently open conversation.
 * Honors the JCLAW-108 conversation override when both override columns are
 * set; falls back to the agent's default otherwise. This is the single
 * resolver both the dropdown key (selectedModelKey) and the capability
 * pills (selectedModelInfo) route through — preventing the JCLAW-112
 * drift where one side honored the override and the other didn't.
 */
const effectiveModel = computed<{ providerName: string | null, modelId: string | null }>(() => {
  const conv = currentConversation.value
  if (conv?.modelProviderOverride && conv?.modelIdOverride) {
    return { providerName: conv.modelProviderOverride, modelId: conv.modelIdOverride }
  }
  return {
    providerName: selectedAgent.value?.modelProvider ?? null,
    modelId: selectedAgent.value?.modelId ?? null,
  }
})

/**
 * ModelInfo for the effective (override-or-agent) model. The Think / Vision /
 * Audio pills and the thinking-level dropdown all derive from this, so they
 * reflect the capabilities of the model that will actually run the next
 * turn — not the agent's default when an override is active.
 */
const selectedModelInfo = computed<ProviderModel | null>(() => {
  const { providerName, modelId } = effectiveModel.value
  if (!providerName || !modelId) return null
  const provider = providers.value.find(p => p.name === providerName)
  return provider?.models.find(m => m.id === modelId) ?? null
})

/**
 * Compound key used as the `<option>` value for the model dropdown so the
 * change handler can read both provider and model from a single DOM value.
 * Routes through {@link effectiveModel} so the dropdown and the capability
 * pills stay in sync. "::" separator is safe against every provider name
 * and model id we currently ship.
 */
const selectedModelKey = computed(() => {
  const { providerName, modelId } = effectiveModel.value
  return providerName && modelId ? `${providerName}::${modelId}` : ''
})

// Whether the selected model supports thinking
const thinkingSupported = computed(() => selectedModelInfo.value?.supportsThinking === true)

// Provider/model combos where reasoning cannot be disabled even with the
// toggle off. Two converging causes — model architecture (alwaysThinks pure
// reasoners like o1/R1) and provider integration limits (JCLAW-127:
// ollama-cloud + Gemini 2.5 Pro / 3). Both surface as a locked pill with an
// explanatory tooltip so the operator isn't misled into thinking their
// preference was honored.
const thinkingLock = computed(() =>
  resolveThinkingLock(
    effectiveModel.value.providerName,
    effectiveModel.value.modelId,
    selectedModelInfo.value,
  ),
)

// Thinking levels advertised by the currently selected model. Empty for
// non-thinking models — the toolbar hides the selector in that case.
const thinkingLevels = computed<string[]>(() => effectiveThinkingLevels(selectedModelInfo.value))

// Model capability flags surfaced as pills next to the paperclip. Mirrors LM
// Studio's "Think / Vision" chip row so users can see at a glance which input
// types the currently-selected model accepts. Flags originate in provider
// metadata (OpenRouter architecture.input_modalities, Ollama capabilities)
// or the operator-toggled checkbox in Settings; see ModelDiscoveryService.
const visionSupported = computed(() => selectedModelInfo.value?.supportsVision === true)
// JCLAW-165: capability indicator only — there's no per-agent audio toggle
// to drive (transcription gives every model an audio path). The pill in
// the composer signals "this model handles audio natively"; voice notes
// to non-supportsAudio models go through the transcription pipeline
// transparently.
const audioSupported = computed(() => selectedModelInfo.value?.supportsAudio === true)
// Capability indicator only — uploaded videos work on any model. A
// supportsVideo model watches the clip natively; others route to the dedicated
// video model (Settings → Video Interpretation), then to frames/captions.
const videoSupported = computed(() => selectedModelInfo.value?.supportsVideo === true)

// --- Pill toggle state ---

// Think pill: active when the agent currently has a reasoning-effort level set.
// Null/blank thinkingMode means thinking is off even on a capable model.
const thinkingActive = computed(() => {
  const mode = selectedAgent.value?.thinkingMode
  return typeof mode === 'string' && mode.length > 0
})

// Remember the last non-off thinking level the operator picked for THIS session so
// toggling the pill off → on restores "medium" or whatever they'd most recently
// chosen, instead of always jumping back to the first advertised level. The ref
// is intentionally module-local and not persisted — the next page load starts
// fresh from whatever the agent's stored thinkingMode was.
const lastThinkingLevel = ref<string>('medium')

// Vision and audio are pure capability indicators — no LLM provider exposes
// an API-level off-switch for either modality, so a client-side toggle would
// just be "don't attach images/audio." The chat composer renders the pills
// when the model supports the capability; clicks are no-ops.

function toggleThinkingPill() {
  if (!thinkingSupported.value) return
  // JCLAW-127: on a locked combo (ollama-cloud + Gemini 2.5 Pro / 3) the
  // upstream Google API ignores our off signal, so clicking is a no-op. The
  // tooltip communicates why.
  if (thinkingLock.value.locked) return
  if (thinkingActive.value) {
    updateAgentSetting({ thinkingMode: null })
  }
  else {
    // Prefer the session-remembered level if it's still a valid option on this
    // model, otherwise fall back to the first advertised level so we never
    // send an invalid enum value the backend would have to defensively reject.
    const levels = thinkingLevels.value
    const next = levels.includes(lastThinkingLevel.value) ? lastThinkingLevel.value : levels[0]
    if (next) updateAgentSetting({ thinkingMode: next })
  }
}

// Hover/focus menu above the Think pill: lets the user pick a specific
// reasoning level (low/medium/high — whatever the current model advertises)
// without going through the off → on dance. Click-to-toggle on the pill
// itself is preserved as the cheap one-click affordance; the menu is the
// power-user path. The 150ms close delay covers the cursor traversing the
// gap between pill and menu — without it the menu vanishes mid-traverse.
//
// The menu is teleported to <body> because the composer <form> has
// overflow-hidden (necessary for its rounded-[22px] border) which would
// otherwise clip the upward-growing menu — that's why bumping z-index
// alone didn't fix the "Low is missing" report. With Teleport the menu
// becomes a viewport-positioned floater anchored to the trigger button's
// bounding rect; scroll/resize listeners keep it pinned while open.
const thinkingMenuOpen = ref(false)
const thinkPillRef = ref<HTMLButtonElement | null>(null)
const thinkingMenuStyle = ref<Record<string, string>>({})
let thinkingMenuCloseTimer: ReturnType<typeof setTimeout> | null = null
let thinkingMenuListenersAttached = false

function computeThinkingMenuStyle() {
  const btn = thinkPillRef.value
  if (!btn) return
  const r = btn.getBoundingClientRect()
  thinkingMenuStyle.value = {
    left: `${r.left + r.width / 2}px`,
    top: `${r.top - 6}px`,
    transform: 'translate(-50%, -100%)',
  }
}

function attachMenuTrackingListeners() {
  if (thinkingMenuListenersAttached) return
  // capture: true so scroll events on nested overflow-auto containers
  // (the chat history scrollbox) reposition the floating menu too.
  window.addEventListener('scroll', computeThinkingMenuStyle, { passive: true, capture: true })
  window.addEventListener('resize', computeThinkingMenuStyle)
  thinkingMenuListenersAttached = true
}

function detachMenuTrackingListeners() {
  if (!thinkingMenuListenersAttached) return
  window.removeEventListener('scroll', computeThinkingMenuStyle, { capture: true } as EventListenerOptions)
  window.removeEventListener('resize', computeThinkingMenuStyle)
  thinkingMenuListenersAttached = false
}

function openThinkingMenu() {
  if (thinkingMenuCloseTimer) {
    clearTimeout(thinkingMenuCloseTimer)
    thinkingMenuCloseTimer = null
  }
  if (!thinkingSupported.value) return
  if (thinkingLock.value.locked) return
  if (!thinkingLevels.value.length) return
  // Only surface the level picker when Think is currently on. The pill's
  // click-to-toggle handles on/off; the menu is purely "now that thinking
  // is on, let me change the level." Showing it for a disabled pill would
  // confusingly let the user re-enable Think via a hover-then-click that
  // looks like nothing more than picking a level.
  if (!thinkingActive.value) return
  computeThinkingMenuStyle()
  thinkingMenuOpen.value = true
  attachMenuTrackingListeners()
}

function scheduleCloseThinkingMenu() {
  if (thinkingMenuCloseTimer) clearTimeout(thinkingMenuCloseTimer)
  thinkingMenuCloseTimer = setTimeout(() => {
    thinkingMenuOpen.value = false
    thinkingMenuCloseTimer = null
    detachMenuTrackingListeners()
  }, 150)
}

function setThinkingLevel(level: string) {
  if (!thinkingSupported.value) return
  if (thinkingLock.value.locked) return
  lastThinkingLevel.value = level
  updateAgentSetting({ thinkingMode: level })
  thinkingMenuOpen.value = false
  detachMenuTrackingListeners()
}

onBeforeUnmount(() => {
  if (thinkingMenuCloseTimer) {
    clearTimeout(thinkingMenuCloseTimer)
    thinkingMenuCloseTimer = null
  }
  detachMenuTrackingListeners()
})

// Sync model or thinking mode change back to the agent
async function updateAgentSetting(updates: Partial<Agent>) {
  if (!selectedAgentId.value) return
  try {
    await $fetch(`/api/agents/${selectedAgentId.value}`, { method: 'PUT', body: updates })
    refreshAgents()
  }
  catch { /* ignore */ }
}

/**
 * Model-dropdown change handler.
 *
 * JCLAW-108: when a conversation is open, writes a conversation-scoped
 * override (PUT /api/conversations/{id}/model-override) instead of mutating
 * the Agent row. This keeps mid-chat model switches bounded to the current
 * conversation — matching the `/model NAME` slash command's semantics.
 *
 * When no conversation is open (the user is about to start a fresh one),
 * falls back to the pre-JCLAW-108 behavior of mutating the agent's default
 * model. This preserves the settings-page flow where editing the agent from
 * here is the intent.
 */
async function onModelKeyChange(key: string) {
  const sepIdx = key.indexOf('::')
  if (sepIdx < 0) return
  const modelProvider = key.slice(0, sepIdx)
  const modelId = key.slice(sepIdx + 2)

  const convoId = selectedConvoId.value
  if (convoId != null) {
    // Write the conversation override. Match the refresh-on-success pattern
    // used by updateAgentSetting so the local conversations list realigns
    // with persisted state (including the fields listConversations now
    // returns — modelProviderOverride / modelIdOverride).
    try {
      await $fetch(`/api/conversations/${convoId}/model-override`, {
        method: 'PUT',
        body: { modelProvider, modelId },
      })
      refreshConversations()
    }
    catch (err) {
      // Server rejected (unknown provider/model) or network error. Refetch
      // to realign the dropdown with persisted state.
      refreshConversations()
      throw err
    }
    return
  }

  // No conversation open — fall back to mutating the agent default.
  const provider = providers.value.find(p => p.name === modelProvider)
  const model = provider?.models.find(m => m.id === modelId) ?? null
  const updates: Partial<Agent> = { modelProvider, modelId }
  // If the new model doesn't advertise the current thinking level, clear it in
  // the same PUT so the backend doesn't have to normalize the mismatch. The
  // backend also collapses unknown levels to null defensively, but sending the
  // cleared value keeps the optimistic UI and the persisted state aligned.
  const nextLevels = effectiveThinkingLevels(model)
  const current = selectedAgent.value?.thinkingMode
  if (current && !nextLevels.includes(current)) {
    updates.thinkingMode = null
  }
  updateAgentSetting(updates)
}

/**
 * JCLAW-108 helpers for model-switch indicators and the conversation-total
 * display in the chat UI.
 */

/**
 * Walk {@code displayMessages} backwards from {@code idx - 1} to find the
 * most recent PRIOR assistant message with usage — i.e. the model that ran
 * the preceding turn. Returns null when no prior assistant turn exists
 * (the conversation is on its first assistant message, or earlier rows
 * predate JCLAW-107 and lack usage data).
 */
function previousAssistantUsage(idx: number): MessageUsage | null {
  for (let i = idx - 1; i >= 0; i--) {
    const prev = displayMessages.value[i]
    if (prev && prev.role === 'assistant' && prev.usage) return prev.usage
  }
  return null
}

/**
 * True when message at {@code idx} is an assistant turn whose
 * {@code modelProvider/modelId} differs from the previous assistant turn.
 * Drives the "Switched to X" divider between mid-conversation model changes.
 */
function shouldShowModelSwitchIndicator(idx: number): boolean {
  const msg = displayMessages.value[idx]
  if (!msg || msg.role !== 'assistant' || !msg.usage?.modelId) return false
  const prior = previousAssistantUsage(idx)
  if (!prior || !prior.modelId) return false
  return prior.modelId !== msg.usage.modelId || prior.modelProvider !== msg.usage.modelProvider
}

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

/**
 * Latest assistant turn's usage block, or null when the conversation has none
 * yet. Drives the top-right context meter's numbers — we show the prompt/
 * completion from the most recent turn because that represents the context
 * about to be resent on the next request.
 */
const latestAssistantUsage = computed<MessageUsage | null>(() => {
  const msgs = displayMessages.value ?? []
  for (let i = msgs.length - 1; i >= 0; i--) {
    const m = msgs[i]
    if (m && m.role === 'assistant' && m.usage) return m.usage
  }
  return null
})

/**
 * Cumulative tokens billed across the whole conversation: sum of every
 * assistant turn's prompt + completion. This double-counts the carried
 * context (each turn re-sends the prior history), which is the right
 * thing for "total since the beginning" — it matches what the operator
 * was actually billed for. Distinct from `latestAssistantUsage` which
 * reports the *current* context size and shrinks after a compaction.
 */
const conversationCumulativeTokens = computed<number>(() => {
  const msgs = displayMessages.value ?? []
  let total = 0
  for (const m of msgs) {
    if (m && m.role === 'assistant' && m.usage) {
      total += (m.usage.prompt ?? 0) + (m.usage.completion ?? 0)
    }
  }
  return total
})

/**
 * Running cost summary for the currently open conversation. Honors each turn's
 * own embedded pricing so mixed-model conversations (e.g. Kimi → Flash) total
 * correctly. Null when the conversation has no assistant turns.
 *
 * The watch that populates this lives near {@link displayMessages} since it
 * depends on that computed; declared up here so template bindings resolve.
 */
const conversationCostSummary = ref<{ label: string, tooltip: string, turnCount: number } | null>(null)

// Per-bubble collapse toggle handler. Header label + default-collapse rules
// live in ~/utils/thinking.ts (thinkingHeaderLabel, initCollapsedState) so
// they are unit-testable without mounting the page.
//
// triggerRef is required because messages is a shallowRef — mutating a
// property on a Message object inside the array does not trigger reactivity
// on its own. The same applies to the tool-call toggles below.
function toggleThinking(msg: Message) {
  msg.thinkingCollapsed = !msg.thinkingCollapsed
  triggerRef(messages)
}

// JCLAW-170: tool-calls block collapse toggle. Mirrors the thinking card's
// single-boolean collapse UX.
function toggleToolCalls(msg: Message) {
  (msg as Message & { toolCallsCollapsed?: boolean }).toolCallsCollapsed
    = !(msg as Message & { toolCallsCollapsed?: boolean }).toolCallsCollapsed
  triggerRef(messages)
}

// JCLAW-170: per-call expand toggle. Each tool call inside the outer block
// has its own collapsible body (chip grid or result text), independent of
// the surrounding "N tool calls" accordion.
function toggleToolCallExpansion(tc: ToolCall) {
  tc._expanded = !tc._expanded
  triggerRef(messages)
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
const input = ref('')
const streaming = ref(false)
const streamStatus = ref('')
// _key of the assistant message currently being streamed. Set when we push the
// placeholder in sendMessage, cleared when the stream ends. Used by the
// template to route the in-flight bubble to streamContentHtml /
// streamReasoningHtml (throttled markdown render) instead of running the full
// renderMarkdown pipeline on every token mutation.
const streamingMessageKey = ref<string | null>(null)
const chatInput = ref<HTMLTextAreaElement | null>(null)

/**
 * JCLAW-114: /model NAME autocomplete state. Driven by the input watcher
 * below — watches for the /model <query> prefix and surfaces a floating
 * popup above the textarea with matching provider/model pairs from the
 * (already-filtered) providers list. Keyboard nav via ArrowUp/Down/Enter/
 * Tab/Escape, mouse via click.
 */
const modelAutocomplete = useModelAutocomplete(providers)

watch(input, (text) => {
  modelAutocomplete.update(text)
})

function onInputKeydown(event: KeyboardEvent) {
  // Only steal keys while the popup is open — when it's closed, the textarea
  // behaves exactly as before (Enter sends, everything else is text input).
  if (!modelAutocomplete.open.value) return
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    modelAutocomplete.moveHighlight('down')
  }
  else if (event.key === 'ArrowUp') {
    event.preventDefault()
    modelAutocomplete.moveHighlight('up')
  }
  else if (event.key === 'Tab' || event.key === 'Enter') {
    const replacement = modelAutocomplete.accept(input.value)
    if (replacement !== null) {
      event.preventDefault()
      input.value = replacement
      nextTick(() => autoResize())
    }
  }
  else if (event.key === 'Escape') {
    event.preventDefault()
    modelAutocomplete.close()
  }
}

function onInputEnter(event: KeyboardEvent) {
  // When the autocomplete popup is open, Enter accepts the selection
  // (handled by onInputKeydown). Otherwise it sends the message.
  if (modelAutocomplete.open.value) {
    onInputKeydown(event)
    return
  }
  event.preventDefault()
  sendMessage()
}

function pickAutocomplete(choice: string) {
  modelAutocomplete.moveHighlight('down') // no-op if already highlighted
  const idx = modelAutocomplete.options.value.indexOf(choice)
  if (idx >= 0) modelAutocomplete.highlightedIndex.value = idx
  const replacement = modelAutocomplete.accept(input.value)
  if (replacement !== null) {
    input.value = replacement
    nextTick(() => {
      autoResize()
      chatInput.value?.focus()
    })
  }
}

function autoResize() {
  const el = chatInput.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

// Per-message "just copied" flash so the user gets visual feedback without a toast.
const copiedMessageId = ref<string | number | null>(null)
async function copyMessage(msg: Message) {
  try {
    // Copy the raw source — for assistant turns that means the markdown, not
    // the rendered HTML. Pasting into a doc or another chat stays faithful to
    // what the model actually produced.
    await navigator.clipboard.writeText(msg.content ?? '')
    copiedMessageId.value = msg.id ?? msg._key ?? null
    setTimeout(() => {
      if (copiedMessageId.value === (msg.id ?? msg._key ?? null)) copiedMessageId.value = null
    }, 1200)
  }
  catch (e) {
    console.error('Failed to copy message:', e)
  }
}

// Copy the reasoning body only (not the final response). Namespaced key so the
// per-message flash doesn't clash with copyMessage on the same row.
async function copyReasoning(msg: Message) {
  if (!msg.reasoning) return
  try {
    await navigator.clipboard.writeText(msg.reasoning)
    const key = `reason:${msg.id ?? msg._key}`
    copiedMessageId.value = key
    setTimeout(() => {
      if (copiedMessageId.value === key) copiedMessageId.value = null
    }, 1200)
  }
  catch (e) {
    console.error('Failed to copy reasoning:', e)
  }
}
/**
 * Regenerate the assistant's response to the last user prompt. Semantically
 * equivalent to "rewind to the user message, re-send it". Deletes the user +
 * assistant pair (and anything after) both server-side and locally, then
 * calls sendMessage() with the user's original text so the backend runs a
 * fresh turn against the pre-existing conversation history.
 */
/**
 * Find the index of the most recent user message at-or-before {@code fromIdx}.
 * Returns -1 if none is found.
 */
function findPriorUserMessageIdx(fromIdx: number): number {
  for (let i = fromIdx - 1; i >= 0; i--) {
    if (messages.value[i]!.role === 'user') return i
  }
  return -1
}

/**
 * Best-effort server-side delete of messages [startIdx..end). Failures are
 * swallowed because the local truncate happens regardless.
 */
async function deleteServerMessagesFrom(convoId: number, startIdx: number) {
  for (let i = startIdx; i < messages.value.length; i++) {
    const m = messages.value[i]!
    if (!m.id) continue
    try {
      await $fetch(`/api/conversations/${convoId}/messages/${m.id}`, { method: 'DELETE' })
    }
    catch { /* best-effort — local truncate still happens */ }
  }
}

async function regenerateMessage(msg: Message) {
  if (streaming.value) return
  const convoId = selectedConvoId.value
  const idx = messages.value.indexOf(msg)
  if (idx < 0) return
  const userIdx = findPriorUserMessageIdx(idx)
  if (userIdx < 0) return
  const userContent = messages.value[userIdx]!.content ?? ''
  if (convoId) await deleteServerMessagesFrom(convoId, userIdx)
  messages.value = messages.value.slice(0, userIdx)
  input.value = userContent
  await nextTick()
  sendMessage()
}

/**
 * Per-message hover state for the tok/s statistics popover. Stores the
 * currently-open message's id/_key so only one popover is visible at a
 * time; v-for rows bind their individual open state off this ref.
 */
const tokStatsHoverKey = ref<string | number | null>(null)

async function deleteMessage(msg: Message) {
  // Skip mid-stream placeholders that have no server-side row yet — the
  // outer stop-streaming path already handles those.
  if (!msg.id) return
  const convoId = selectedConvoId.value
  if (!convoId) return
  try {
    await $fetch(`/api/conversations/${convoId}/messages/${msg.id}`, { method: 'DELETE' })
    // Splice optimistically rather than refetching the whole transcript —
    // keeps the remaining messages' thinkingCollapsed / _thinkingDurationMs
    // bubble state intact (they're client-only refs that a refetch would lose).
    const idx = messages.value.findIndex(m => m.id === msg.id)
    if (idx >= 0) {
      messages.value.splice(idx, 1)
      triggerRef(messages)
    }
  }
  catch (e) {
    console.error('Failed to delete message:', e)
  }
}

/**
 * JCLAW-209: delete a generated image's bytes from the workspace. Soft delete —
 * the server frees the on-disk file and flags the row, so the chip persists with
 * a "deleted from workspace" marker rather than vanishing. Flip the local flag
 * and triggerRef (messages is a shallowRef) instead of refetching the transcript.
 */
async function deleteAttachment(att: MessageAttachment) {
  try {
    await $fetch(`/api/attachments/${att.uuid}`, { method: 'DELETE' })
    att.deleted = true
    triggerRef(messages)
  }
  catch (e) {
    console.error('Failed to delete attachment:', e)
  }
}
async function editUserMessage(msg: Message) {
  if (streaming.value) return
  input.value = msg.content ?? ''
  await nextTick()
  const el = chatInput.value
  if (el) {
    autoResize()
    el.focus()
    el.setSelectionRange(el.value.length, el.value.length)
    el.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
  }
}
const agentBusy = ref(false)
const streamContent = ref('')
const streamReasoning = ref('')
const abortController = ref<AbortController | null>(null)
// Autoscroll coordination (viewport el + scrollToBottom + reasoning-body pin)
// lives in useChatScroll; it reads the stream state it reacts to as args.
const { messagesEl, scrollToBottom } = useChatScroll(streaming, streamReasoning)

// Throttled markdown rendering for the in-flight streaming bubble lives in
// useStreamMarkdownRender; the SSE handlers below call schedule*/flush and the
// template reads the two HTML refs. (A building block the eventual useChatStream
// will compose.)
const {
  streamContentHtml,
  streamReasoningHtml,
  scheduleStreamContentRender,
  scheduleStreamReasoningRender,
  flushStreamRender,
} = useStreamMarkdownRender(streamContent, streamReasoning, selectedAgentId, messages)

onMounted(() => {
  focusInput()
})

/**
 * JCLAW-270 async-spawn announce poller. After the parent's streaming turn
 * ends, the {@code subagent_announce} Message that closes an async spawn can
 * arrive seconds (or minutes) later — the SSE stream is already closed by
 * then, so the chat view has no reactive path to learn about it. Without a
 * refresh the announce sits in the DB and the user has to manually reload.
 *
 * Mirror {@code /subagents}'s "auto-refresh while RUNNING" pattern: while
 * the open conversation has at least one tool result reporting
 * {@code status:RUNNING} for a {@code run_id} that no {@code subagent_announce}
 * row has closed yet, poll {@code /api/conversations/{id}/messages} every
 * {@link ANNOUNCE_POLL_INTERVAL_MS}. Merge any newer rows by server id —
 * never clobber in-flight optimistic placeholders. Stops as soon as the
 * announce arrives or the page unmounts.
 *
 * Deliberately silent during streaming — the SSE path already keeps the
 * messages list reactive, so polling on top of it would race and risk
 * stomping the in-flight assistant bubble. The check runs every 5s
 * regardless, so within one cadence of stream end the loop kicks in.
 */
const ANNOUNCE_POLL_INTERVAL_MS = 5000

/**
 * Keep polling for this long after the most recent subagent_announce row
 * lands, even though {@link hasPendingAsyncAnnounce} flips false. Covers
 * the gap where {@link AgentRunner#runYieldResume} runs the parent's
 * resumed turn on the backend with no SSE stream to the chat tab — any
 * `message` tool calls or sync sub-agent activity inside the resumed
 * turn would otherwise sit in the DB until the user reloads. Three
 * minutes accommodates a typical Phase 4 import (Radarr scan + verify
 * + transmission cleanup, ~30-180s) plus headroom; long enough that
 * the user sees the full workflow inline, short enough that idle
 * conversations don't poll forever.
 */
const POST_ANNOUNCE_GRACE_MS = 180_000
let announcePollTimer: ReturnType<typeof setInterval> | undefined

/**
 * Extract the async-spawn {@code run_id} from a JSON-shaped tool result body.
 * Returns null when the body isn't a {@code status:RUNNING} async-spawn
 * payload — every "not an async-pending result" branch falls through
 * harmlessly. The cheap substring pre-check avoids JSON.parse on every other
 * tool result body (web_search responses, file reads, etc.) on every poll
 * tick.
 */
function pendingAsyncRunIdFromResultText(text: string | null | undefined): string | null {
  if (!text) return null
  if (!text.includes('"status"') || !text.includes('RUNNING')) return null
  try {
    const parsed = JSON.parse(text) as { run_id?: unknown, status?: unknown }
    if (parsed?.status !== 'RUNNING') return null
    const runId = parsed.run_id
    if (typeof runId === 'string') return runId
    if (typeof runId === 'number') return String(runId)
    return null
  }
  catch {
    return null
  }
}

/**
 * Returns the {@code run_id} string carried in a tool-role message's JSON
 * content when the result reported {@code status:RUNNING}. This is the
 * post-reload shape — the standalone tool-role row only lands in
 * {@code messages.value} after a transcript refetch.
 */
function pendingAsyncRunId(m: Message): string | null {
  if (m.role !== 'tool') return null
  return pendingAsyncRunIdFromResultText(m.content)
}

/**
 * True when the open conversation has at least one async-spawn tool result
 * whose announce hasn't landed. Drives the poll-tick decision: if false we
 * skip the network call entirely and let the interval idle until either a
 * new spawn fires or the page unmounts.
 *
 * Walks both shapes the spawn result can take in the local list:
 *   1. Standalone tool-role row (post-reload, after the {@code /messages}
 *      refetch that {@code loadConversation} runs).
 *   2. {@code resultText} on an entry of an assistant message's
 *      {@link Message.toolCalls} array — this is the only shape that exists
 *      between stream-end and the next reload, since the SSE
 *      {@code tool_call} frame folds the result inline rather than emitting
 *      a separate tool-role message. Without this branch the poller would
 *      never fire on the same-page spawn-and-wait path, and the user would
 *      have to navigate away to see the announce land.
 */
/** Collect the set of run-ids that have already produced an announce row. */
function collectAnnouncedRunIds(msgs: Message[]): Set<string> {
  const announcedRunIds = new Set<string>()
  for (const m of msgs) {
    if (m.messageKind !== 'subagent_announce') continue
    const meta = (m as Message & { metadata?: { runId?: number | string } }).metadata
    const rid = meta?.runId
    if (rid != null) announcedRunIds.add(String(rid))
  }
  return announcedRunIds
}

/**
 * JCLAW-326: count of subagent runs that have produced an announce row in
 * the current conversation. Drives the "View N subagents in this
 * conversation" banner; 0 hides the banner. Counts unique run-ids rather
 * than raw announce messages so a hypothetical duplicate (re-render race)
 * doesn't inflate the figure.
 */
const announcedSubagentCount = computed(() => collectAnnouncedRunIds(messages.value).size)

/**
 * Check whether {@code m}'s assistant toolCalls contain a pending async
 * spawn whose run-id has not yet been announced.
 */
function hasUnannouncedToolCallPending(m: Message, announcedRunIds: Set<string>): boolean {
  if (m.role !== 'assistant' || !m.toolCalls?.length) return false
  for (const tc of m.toolCalls) {
    const tcPending = pendingAsyncRunIdFromResultText(tc.resultText)
    if (tcPending && !announcedRunIds.has(tcPending)) return true
  }
  return false
}

function hasPendingAsyncAnnounce(): boolean {
  const msgs = messages.value
  if (!msgs.length) return false
  const announcedRunIds = collectAnnouncedRunIds(msgs)
  for (const m of msgs) {
    const pending = pendingAsyncRunId(m)
    if (pending && !announcedRunIds.has(pending)) return true
    if (hasUnannouncedToolCallPending(m, announcedRunIds)) return true
  }
  return false
}

/**
 * True when the most recent {@code subagent_announce} row arrived within
 * {@link POST_ANNOUNCE_GRACE_MS}. Drives a grace-window poll after an
 * announce lands so messages persisted by the subsequent yield-resumed
 * turn (which has no SSE channel to this tab) surface inline rather
 * than only on manual reload.
 */
function isWithinPostAnnounceGrace(): boolean {
  const msgs = messages.value
  if (!msgs.length) return false
  let latest = 0
  for (const m of msgs) {
    if (m.messageKind !== 'subagent_announce') continue
    if (!m.createdAt) continue
    const t = Date.parse(m.createdAt)
    if (!Number.isFinite(t)) continue
    if (t > latest) latest = t
  }
  if (latest === 0) return false
  return Date.now() - latest < POST_ANNOUNCE_GRACE_MS
}

/**
 * Grace window for polling after a {@code task_manager.createTask} call:
 * once a scheduled / recurring task is in the conversation's history, the
 * fire that lands on the server has no SSE channel back to this tab (the
 * task fires on a virtual-thread carrier, not in a chat turn). Poll for
 * this long after the latest createTask call so the auto-delivered fire
 * output (TaskExecutor → DeliveryDispatcher web-send) surfaces inline
 * rather than only on manual reload. Generous because recurring tasks
 * re-fire indefinitely; one createTask call typically intends to receive
 * many deliveries over time.
 */
const TASK_DELIVERY_POLL_GRACE_MS = 30 * 60_000

/**
 * Tool name that schedules background tasks; matches
 * {@code TaskTool.TOOL_NAME} on the backend. Detecting by name (not by
 * tool-result substring) is stable across the two shapes a tool call
 * takes in the local list — the SSE {@code tool_call} frame may not
 * populate {@code resultText} the same way the persisted-row reload
 * does, but the {@code name} field is invariant.
 */
const TASK_MANAGER_TOOL_NAME = 'task_manager'

/**
 * True when the open conversation has any {@code task_manager} tool call
 * within {@link TASK_DELIVERY_POLL_GRACE_MS}. While true,
 * {@link announcePollTick} polls so a task fire's auto-delivered message
 * (Bug 1 fix) surfaces inline. Scans both shapes the tool call takes in
 * the local list (mirrors {@link hasPendingAsyncAnnounce}): standalone
 * tool-role rows post-reload (where the assistant row above carried the
 * call's name), and live SSE-pushed entries on the streaming assistant's
 * {@link Message.toolCalls} array pre-reload.
 *
 * <p>Triggers on every {@code task_manager} action, not just
 * {@code createTask} — only createTask actually schedules a new fire,
 * but updateTask / pause / resume / runNow / cancelTask all leave the
 * conversation in a state where another fire may land soon, and the cost
 * of one extra GET per 5s while the grace window is open is trivial.
 */
function hasRecentTaskCreate(): boolean {
  const msgs = messages.value
  if (!msgs.length) return false
  const cutoff = Date.now() - TASK_DELIVERY_POLL_GRACE_MS
  for (const m of msgs) {
    const messageAge = m.createdAt ? Date.parse(m.createdAt) : Number.NaN
    // When the message has no createdAt (optimistic), treat as "now" so
    // we don't skip a just-issued create that races the timestamp write.
    if (Number.isFinite(messageAge) && messageAge < cutoff) continue
    if (m.toolCalls?.length) {
      for (const tc of m.toolCalls) {
        if (tc.name === TASK_MANAGER_TOOL_NAME) return true
      }
    }
  }
  return false
}

/**
 * Refetch the open conversation's messages and append any rows whose server
 * id isn't already present locally. Preserves client-only state on existing
 * rows (toolCallsCollapsed, _key on optimistic placeholders, etc.) by
 * never mutating or replacing them.
 *
 * Backfills server ids onto id-less optimistic rows BEFORE computing the
 * additions set: without that step the server-side copies of those rows
 * (which carry ids the local set doesn't yet have) sail past the
 * id-membership filter and get appended as duplicates, producing the
 * "user prompt rendered twice" symptom on async-spawn turns.
 */
async function pollForAnnounce() {
  const convoId = selectedConvoId.value
  if (!convoId) return
  let fresh: Message[]
  try {
    fresh = await $fetch<Message[]>(`/api/conversations/${convoId}/messages`) ?? []
  }
  catch (e) {
    console.error('Failed to poll for announce:', e)
    return
  }
  if (!fresh.length) return
  backfillServerIds(messages.value, fresh)
  const knownIds = new Set<number>()
  for (const m of messages.value) {
    if (typeof m.id === 'number') knownIds.add(m.id)
  }
  const additions = fresh.filter(m => typeof m.id === 'number' && !knownIds.has(m.id))
  if (!additions.length) return
  // Hydrate tool-role rows into the preceding assistant message's toolCalls
  // array on the additions before pushing (mirrors loadConversation). Pass
  // the full fresh list so hydrateToolCalls can match tool rows against
  // their owning assistant turn, then drop everything but additions.
  hydrateToolCalls(fresh as unknown as Array<Record<string, unknown>>)
  for (const m of additions) {
    if (!m.toolCalls?.length) continue
    (m as Message & { toolCallsCollapsed?: boolean }).toolCallsCollapsed = true
    for (let i = 0; i < m.toolCalls.length; i++) {
      m.toolCalls[i]!._expanded = i === m.toolCalls.length - 1
    }
  }
  messages.value = [...messages.value, ...additions]
  initCollapsedState(messages.value)
  initSubagentCollapsedState(messages.value)
  triggerRef(messages)
}

function announcePollTick() {
  // Don't fight the streaming path — SSE already keeps the messages list
  // reactive during the parent's own turn. Resume on the next tick after
  // streaming ends.
  if (streaming.value) return
  // Three reasons to poll:
  //   1. An async-spawn is in flight, awaiting its announce row.
  //   2. An announce row arrived within POST_ANNOUNCE_GRACE_MS — the
  //      backend's yield-resume may still be writing follow-up messages
  //      (Phase 4 "import starting", Phase 5 "ready to watch", etc.).
  //   3. A task_manager.createTask call landed within
  //      TASK_DELIVERY_POLL_GRACE_MS — its scheduled fire will auto-deliver
  //      back into this conversation on a virtual-thread carrier with no
  //      SSE channel to this tab (TaskExecutor → DeliveryDispatcher
  //      web-send). Without polling, the delivered Message row sits in the
  //      DB until the user reloads.
  if (!hasPendingAsyncAnnounce()
    && !isWithinPostAnnounceGrace()
    && !hasRecentTaskCreate()) return
  void pollForAnnounce()
}

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
  announcePollTimer = setInterval(announcePollTick, ANNOUNCE_POLL_INTERVAL_MS)
  // Capture phase: image load errors don't bubble, so a document-level listener
  // only sees them in capture. The handler is scoped to `.prose-chat`.
  document.addEventListener('error', onMarkdownImageError, true)
})

onUnmounted(() => {
  abortController.value?.abort()
  if (announcePollTimer != null) clearInterval(announcePollTimer)
  document.removeEventListener('error', onMarkdownImageError, true)
})

function stopStreaming() {
  if (!streaming.value) return
  abortController.value?.abort()
  streaming.value = false
  streamStatus.value = ''
  focusInput()
}
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

// Recompute the cost meter only when streaming is idle — usage lands at
// end-of-turn, so any recompute mid-stream would walk every message and call
// computeConversationCost for an unchanged value. The shallowRef migration
// on messages already prevents per-token cascades; this watch keeps the
// recompute off the displayMessages tracking path entirely so the meter
// stays stable through the streaming → idle transition.
watch(() => [displayMessages.value, streaming.value] as const, ([msgs, isStreaming]) => {
  if (isStreaming) return
  const usages = (msgs ?? [])
    .filter(m => m.role === 'assistant' && m.usage)
    .map(m => m.usage as MessageUsage)
  if (usages.length === 0) {
    conversationCostSummary.value = null
    return
  }
  const breakdown = computeConversationCost(usages)
  const label = formatConversationCost(breakdown)
  if (label === null) {
    conversationCostSummary.value = null
    return
  }
  conversationCostSummary.value = {
    label,
    tooltip: formatConversationCostTooltip(breakdown),
    turnCount: breakdown.turnCount,
  }
}, { immediate: true })

// Deep-link: if ?conversation=ID is present, load that conversation and switch
// to its agent on mount.
const route = useRoute()
const deepLinkConvoId = route.query.conversation ? Number(route.query.conversation) : null

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

/**
 * Mutable cursor that the SSE handlers share. {@code assistantIdx} is updated
 * by the {@code init} handler when the server mints a new conversation and
 * the local history is reset to just the streaming placeholder.
 */
interface StreamContext {
  assistantIdx: number
  readonly sentConversationId: number | null
}

/**
 * Upload any attached files and return the server-side handles. Sets
 * {@code attachError} and returns null on failure so the caller can bail.
 */
async function uploadOrReportAttachError(): Promise<UploadedAttachment[] | null> {
  if (!attachedFiles.value.length || !selectedAgentId.value) return []
  try {
    return await uploadAttachments(selectedAgentId.value)
  }
  catch (e: unknown) {
    const err = e as { data?: { error?: string }, message?: string } | undefined
    attachError.value = 'Upload failed: ' + (err?.data?.error || err?.message || 'unknown error')
    return null
  }
}

/** Revoke blob URLs held for the about-to-be-sent attachments. */
function revokeAttachmentPreviews(pending: File[]) {
  for (const f of pending) {
    const url = attachmentPreviews.value.get(f)
    if (url) URL.revokeObjectURL(url)
    attachmentPreviews.value.delete(f)
  }
}

/**
 * Translate uploaded server handles into the shape the chat bubble's
 * optimistic-attachment chips expect (attachmentId → uuid). Returns
 * undefined when there were no uploads so the field is omitted entirely.
 */
function buildOptimisticAttachments(uploaded: UploadedAttachment[]) {
  if (!uploaded.length) return undefined
  return uploaded.map(u => ({
    uuid: u.attachmentId,
    originalFilename: u.originalFilename,
    mimeType: u.mimeType,
    sizeBytes: u.sizeBytes,
    kind: u.kind,
  }))
}

/** Reset all streaming buffers and flip the {@code streaming} flag on. */
function beginStreamingState() {
  streaming.value = true
  streamContent.value = ''
  streamReasoning.value = ''
  streamStatus.value = ''
  streamContentHtml.value = ''
  streamReasoningHtml.value = ''
}

/**
 * JCLAW-26: if the backend minted a NEW conversation (e.g. /new slash-
 * command), discard the stale visible history and re-anchor to just the
 * streaming placeholder so the view matches what the new conversation
 * actually contains.
 */
function handleInitConversationSwap(ctx: StreamContext, newConversationId: number) {
  if (ctx.sentConversationId === null) return
  if (ctx.sentConversationId === newConversationId) return
  const placeholder = messages.value[ctx.assistantIdx]
  if (!placeholder) return
  messages.value = [placeholder]
  ctx.assistantIdx = 0
  triggerRef(messages)
}

function handleStreamInitEvent(ctx: StreamContext, event: { conversationId?: number, thinkingMode?: string }) {
  if (!event.conversationId) return
  handleInitConversationSwap(ctx, event.conversationId)
  selectedConvoId.value = event.conversationId
  if (event.thinkingMode) {
    streamStatus.value = `thinking (${event.thinkingMode})...`
  }
}

/** Parse a status frame that may carry a usage JSON payload. Returns true if it was consumed as usage. */
function tryApplyUsageFromStatusContent(ctx: StreamContext, content: string): boolean {
  if (!content.startsWith('{') || !content.includes('"usage"')) return false
  try {
    const parsed = JSON.parse(content)
    if (!parsed.usage) return false
    messages.value[ctx.assistantIdx]!.usage = parsed.usage
    triggerRef(messages)
    // The metrics pill renders below the bubble the moment usage lands.
    // Without this scroll the per-token scroller leaves the viewport
    // pinned to the pre-metrics bottom and the user sees a half-cropped
    // stats row.
    scrollToBottom()
    return true
  }
  catch {
    return false
  }
}

function handleStreamStatusEvent(ctx: StreamContext, event: { content?: string }) {
  const content = event.content
  if (content && tryApplyUsageFromStatusContent(ctx, content)) return
  streamStatus.value = content ?? ''
}

/**
 * Stamp the once-per-turn "thinking started" fields on the first reasoning
 * chunk. Subsequent chunks are no-ops here to avoid Vue reactivity churn.
 */
function markThinkingStartedIfFirst(m: StreamingMessage): boolean {
  if (m._thinkingStartedAt) return false
  m._thinkingStartedAt = Date.now()
  m._thinkingInProgress = true
  m._thinkingDurationMs = null
  m.thinkingCollapsed = false
  return true
}

function handleStreamReasoningEvent(ctx: StreamContext, event: { content: string }) {
  const m = messages.value[ctx.assistantIdx] as StreamingMessage
  const stateChanged = markThinkingStartedIfFirst(m)
  streamReasoning.value += event.content
  // Property mutation on a shallowRef-held object does not trigger
  // reactivity. The streaming bubble reads streamReasoningHtml.value
  // (throttled markdown render below) instead of msg.reasoning, so
  // the user still sees the live update. msg.reasoning is kept in
  // sync so the post-stream renderMarkdown(msg.reasoning) path
  // displays the final text after the finally{} triggerRef.
  m.reasoning = streamReasoning.value
  scheduleStreamReasoningRender()
  if (stateChanged) triggerRef(messages)
  if (!streamContent.value) {
    streamStatus.value = 'thinking...'
  }
  scrollToBottom()
}

/**
 * Stamp the reasoning→content transition exactly once. Returns true if
 * the transition fired so the caller can decide whether to triggerRef.
 */
function finalizeThinkingOnTransition(m: StreamingMessage): boolean {
  if (m._thinkingInProgress !== true) return false
  m._thinkingDurationMs = Date.now() - (m._thinkingStartedAt ?? Date.now())
  m._thinkingInProgress = false
  m.thinkingCollapsed = true
  return true
}

function handleStreamTokenEvent(ctx: StreamContext, event: { content?: string, timestamp?: string }) {
  // Empty-content token events are emitted by OpenAI-compatible providers
  // (observed on Kimi K2.5 via OpenRouter) interleaved with every reasoning
  // chunk — the `content` field is always present in the API schema and
  // defaults to "" when the chunk only carries reasoning. Treating those as
  // a real reasoning→content transition was the "Thought for 0.01 seconds
  // during streaming" bug: the second empty-content token after the first
  // reasoning event was tripping the flip. Skip them entirely so the
  // transition fires only on the first byte of genuine content.
  if (!event.content) return
  const m = messages.value[ctx.assistantIdx] as StreamingMessage
  const transitioned = finalizeThinkingOnTransition(m)
  streamStatus.value = ''
  if (event.timestamp) m.createdAt = event.timestamp
  streamContent.value += event.content
  m.content = streamContent.value
  scheduleStreamContentRender()
  // Trigger a single re-render at the reasoning→content transition so the
  // thinking card collapses and the content bubble appears. Subsequent
  // content tokens flow through streamContentHtml without touching the
  // messages-array reactivity, which is the whole point of the shallowRef
  // migration.
  if (transitioned) triggerRef(messages)
  scrollToBottom()
}

interface ToolCallEvent {
  id: string
  name: string
  icon?: string
  arguments?: string
  resultText?: string | null
  resultStructured?: ToolCall['resultStructured']
  // JCLAW-228/562: the tool produced inline attachments (generate_image's image, diarize_audio's
  // per-speaker voice clips); the backend ships them so they render live on the streaming bubble
  // (not only after a reload).
  generatedAttachments?: MessageAttachment[]
}

function handleStreamToolCallEvent(ctx: StreamContext, event: ToolCallEvent) {
  // JCLAW-170: a tool invocation completed on the backend. Append the
  // structured payload to the streaming assistant message's toolCalls
  // array so the collapsible block surfaces it live. The accordion starts
  // expanded so users see in-progress activity the instant the first tool
  // finishes; once content begins to stream they can collapse it
  // themselves, and it auto-collapses on reload for historical turns.
  // Per-call: collapse any prior calls and auto-expand the just-arrived
  // one so its results are visible without an extra click.
  const m = messages.value[ctx.assistantIdx] as StreamingMessage
  if (!m.toolCalls) {
    m.toolCalls = []
    m.toolCallsCollapsed = false
  }
  for (const prev of m.toolCalls) prev._expanded = false
  m.toolCalls.push({
    id: event.id,
    name: event.name,
    icon: event.icon || 'wrench',
    arguments: event.arguments ?? '',
    resultText: event.resultText ?? null,
    resultStructured: event.resultStructured ?? null,
    _expanded: true,
  })
  // JCLAW-683: scope the local image-gen progress bar to THIS turn. A
  // generate_image call is the only thing that runs the local sidecar's
  // step loop, so start polling now — keyed to the invoking assistant
  // message's _key — rather than on every streaming turn. This is what stops
  // a concurrent generation's load phase from leaking onto an unrelated turn.
  if (event.name === 'generate_image') {
    imageGenTurnKey.value = m._key ?? null
    startImageProgressPolling()
  }
  // JCLAW-228/562: the call produced inline attachments (image / voice clips) — attach them to the
  // streaming bubble so they render immediately (the server already persisted them on the
  // assistant turn).
  if (event.generatedAttachments?.length) {
    if (!m.attachments) m.attachments = []
    for (const att of event.generatedAttachments) {
      if (!m.attachments.some(a => a.uuid === att.uuid)) {
        m.attachments.push(att)
      }
    }
    startVideoPolling() // a generate_video placeholder (JCLAW-234) needs the progress poll to begin
  }
  triggerRef(messages)
  scrollToBottom()
}

function handleStreamCompleteEvent(ctx: StreamContext, event: { content?: string }) {
  const m = messages.value[ctx.assistantIdx] as StreamingMessage
  // Reasoning-only turn (no content streamed): finalize duration here.
  finalizeThinkingOnTransition(m)
  streamStatus.value = ''
  m.content = event.content || streamContent.value
  // Collapsing thinking + swapping in final content shifts layout height.
  // Re-pin to the bottom so the next thing the user sees (the metrics pill
  // landing on the subsequent status event) is fully in view.
  scrollToBottom()
}

function handleStreamErrorEvent(ctx: StreamContext, event: { content: string }) {
  messages.value[ctx.assistantIdx]!.content = event.content
  triggerRef(messages)
}

function handleStreamQueuedEvent(ctx: StreamContext, event: { position?: number | string }) {
  messages.value[ctx.assistantIdx]!.content = 'Your message has been queued (position: ' + (event.position || '?') + '). Processing shortly...'
  triggerRef(messages)
}

/** Dispatch one SSE event to its dedicated handler. */
function dispatchStreamEvent(ctx: StreamContext, event: { type?: string } & Record<string, unknown>) {
  switch (event.type) {
    case 'init':
      handleStreamInitEvent(ctx, event as { conversationId?: number, thinkingMode?: string })
      break
    case 'status':
      handleStreamStatusEvent(ctx, event as { content?: string })
      break
    case 'reasoning':
      handleStreamReasoningEvent(ctx, event as { content: string })
      break
    case 'token':
      handleStreamTokenEvent(ctx, event as { content?: string, timestamp?: string })
      break
    case 'tool_call':
      handleStreamToolCallEvent(ctx, event as unknown as ToolCallEvent)
      break
    case 'complete':
      handleStreamCompleteEvent(ctx, event as { content?: string })
      break
    case 'error':
      handleStreamErrorEvent(ctx, event as { content: string })
      break
    case 'queued':
      handleStreamQueuedEvent(ctx, event as { position?: number | string })
      break
  }
}

/** Parse one `data: …` SSE line and dispatch; swallow malformed JSON. */
function processStreamLine(ctx: StreamContext, line: string) {
  if (!line.startsWith('data: ')) return
  try {
    const event = JSON.parse(line.slice(6))
    dispatchStreamEvent(ctx, event)
  }
  catch {
    // Skip malformed events
  }
}

/** Drain the SSE response stream, dispatching each event line as it arrives. */
async function consumeStreamResponse(ctx: StreamContext, res: Response) {
  if (!res.body) throw new Error('No response body')
  const reader = res.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  while (true) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop() || ''
    for (const line of lines) processStreamLine(ctx, line)
  }
}

/** Render the user-visible message into the assistant placeholder when the stream throws. */
function applyStreamErrorToPlaceholder(assistantIdx: number, e: unknown) {
  const err = e as { name?: string, message?: string } | undefined
  if (err?.name === 'AbortError') {
    // AbortError is expected when the user clicks Stop — preserve any content
    // that was already streamed and append a small "(stopped)" marker instead
    // of replacing the bubble with a scary error.
    const existing = messages.value[assistantIdx]!.content || streamContent.value || ''
    messages.value[assistantIdx]!.content = existing
      ? existing.replace(/\s*$/, '') + '\n\n_(stopped)_'
      : '_(stopped before any response)_'
  }
  else {
    messages.value[assistantIdx]!.content = 'Error: ' + (err?.message || 'Failed to get response')
  }
}

async function sendMessage() {
  if (streaming.value || !selectedAgentId.value) return
  // Subagent transcripts are read-only — the user reached this view via a
  // "View transcript" link, and there's no well-defined target agent to
  // post to (the dropdown shows a top-level agent, not the subagent).
  if (subagentTranscript.value) return
  const rawText = input.value.trim()
  if (!rawText && !attachedFiles.value.length) return

  attachError.value = null
  const pending = attachedFiles.value.slice()
  const uploaded = await uploadOrReportAttachError()
  if (uploaded === null) return

  // JCLAW-25: message.content is the user's raw text. Attachment metadata
  // rides in the `attachments` field; the backend persists chat_message_attachment
  // rows and synthesizes the LLM content parts (image_url for images, bracketed
  // file references for non-images) from those rows on every turn.
  const text = rawText

  input.value = ''
  revokeAttachmentPreviews(pending)
  attachedFiles.value = []
  if (chatInput.value) chatInput.value.style.height = 'auto'
  // Map the just-uploaded attachments onto the optimistic user bubble so
  // the file chips render immediately. Without this, the chips stayed
  // hidden until the next /messages refetch surfaced the persisted
  // {@link MessageAttachment} rows — observable as "I uploaded a file
  // but it doesn't show until I leave and come back."
  messages.value.push({ _key: crypto.randomUUID(), role: 'user', content: text, createdAt: new Date().toISOString(), attachments: buildOptimisticAttachments(uploaded) })
  triggerRef(messages)
  scrollToBottom()

  beginStreamingState()

  // Capture the conversation id we're sending — if the server returns a
  // DIFFERENT id in the init frame, the backend minted a new conversation
  // (e.g. /new slash-command, JCLAW-26). We need to discard the stale
  // visible history from the prior conversation so the view matches what
  // the new conversation row actually contains on reload.
  const ctx: StreamContext = {
    assistantIdx: messages.value.length,
    sentConversationId: selectedConvoId.value,
  }

  // Add placeholder for streaming response
  const assistantKey = crypto.randomUUID()
  messages.value.push({ _key: assistantKey, role: 'assistant', content: '', createdAt: new Date().toISOString() })
  streamingMessageKey.value = assistantKey
  triggerRef(messages)

  abortController.value?.abort() // cancel any orphaned previous stream
  abortController.value = new AbortController()
  try {
    const res = await fetch('/api/chat/stream', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      signal: abortController.value.signal,
      body: JSON.stringify({
        agentId: selectedAgentId.value,
        conversationId: selectedConvoId.value,
        message: text,
        attachments: uploaded,
      }),
    })
    if (!res.ok) throw new Error(`HTTP ${res.status}`)
    await consumeStreamResponse(ctx, res)
  }
  catch (e: unknown) {
    applyStreamErrorToPlaceholder(ctx.assistantIdx, e)
  }
  finally {
    streaming.value = false
    streamingMessageKey.value = null
    flushStreamRender()
    triggerRef(messages) // re-render with final content + markdown
    // triggerRef can add height (markdown codeblocks expand, metrics pill
    // appears if usage arrived after complete). Wait a tick so the DOM
    // reflects the final layout, then pin to the bottom.
    nextTick().then(scrollToBottom)
    focusInput()
    // Check if agent is still processing queued messages
    if (selectedConvoId.value) {
      try {
        const status = await $fetch<ConversationQueueStatus>(`/api/conversations/${selectedConvoId.value}/queue`)
        agentBusy.value = status.busy
      }
      catch { agentBusy.value = false }
    }
    // Fire the refresh twice. Immediate: catches the common case where the
    // backend persist already landed by the time onComplete fires. Delayed:
    // catches the race where the SSE `complete` frame beat the persist Tx
    // commit (see AgentRunner.streamLlmLoop — emitUsageAndComplete is
    // intentionally called before the Tx.run persist block for latency).
    refreshConversations()
    // Re-sync agents too: the agent can rewrite its own model mid-turn via
    // the jclaw_api tool (PUT /api/agents/{id} → Agent.modelProvider/modelId).
    // The header selector reads selectedAgent through effectiveModel, so
    // without this it stays pinned to the pre-switch model until a manual
    // reload. Single fire is enough — the tool's PUT commits in its own Tx
    // mid-turn, well before the SSE `complete` frame, so the new value is
    // already persisted by the time we get here (unlike the conversation
    // persist-race the double-fire above guards against).
    refreshAgents()
    // Delayed reconcile rides the same persist-race window so the in-flight
    // user + assistant bubbles pick up their server ids (delete button gates
    // on msg.id — without this the Delete action was a no-op until the user
    // navigated away and back).
    setTimeout(() => {
      refreshConversations()
      void reconcileMessageIds()
    }, 600)
  }
}

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

// FLIP the composer between its empty-state (centered) and active-state
// (bottom-anchored) positions when isEmptyChat flips. Watcher's default
// flush:'pre' fires after the reactive change but before the DOM updates,
// so we capture the OLD rect there; nextTick yields the NEW rect; the
// difference becomes the starting translateY, animated back to 0. This
// is the same technique Unsloth uses via Framer Motion's layoutId.
const composerEl = ref<HTMLElement | null>(null)
watch(isEmptyChat, async () => {
  const el = composerEl.value
  if (!el) return
  const before = el.getBoundingClientRect()
  await nextTick()
  if (!composerEl.value) return
  if (globalThis.matchMedia('(prefers-reduced-motion: reduce)').matches) return
  const after = composerEl.value.getBoundingClientRect()
  const dy = before.top - after.top
  if (Math.abs(dy) < 4) return
  composerEl.value.animate(
    [{ transform: `translateY(${dy}px)` }, { transform: 'translateY(0)' }],
    { duration: 500, easing: 'cubic-bezier(0.32, 0.72, 0, 1)' },
  )
})

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

function handleFileUpload(event: Event) {
  const target = event.target as HTMLInputElement
  const picked = target.files ? Array.from(target.files) : []
  if (subagentTranscript.value) return // read-only transcript: drop attachments silently
  addAttachments(picked)
  target.value = ''
}

// JCLAW-25 drop path. Mirrors the paperclip flow: read files off the
// DataTransfer, route through addAttachments so the vision gate, size
// cap, and thumbnail preview apply uniformly regardless of how the file
// entered the composer.
function handleDrop(event: DragEvent) {
  if (subagentTranscript.value) return
  const files = event.dataTransfer?.files
  if (!files || files.length === 0) return
  addAttachments(Array.from(files))
}

// JCLAW-25 paste path. Inspects the clipboard items for file entries —
// typically a single image from a screenshot-copy (Cmd/Ctrl-Shift-4,
// Windows Snipping Tool, etc.). Text pastes fall through untouched.
function handlePaste(event: ClipboardEvent) {
  if (subagentTranscript.value) return
  const items = event.clipboardData?.items
  if (!items || items.length === 0) return
  const files: File[] = []
  for (const item of items) {
    if (item.kind === 'file') {
      const f = item.getAsFile()
      if (f) files.push(f)
    }
  }
  if (files.length === 0) return
  event.preventDefault()
  addAttachments(files)
}

const formatAttachmentSize = formatSize

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
        <label
          v-if="(agents?.length ?? 0) > 1"
          :for="agentSelectId"
          class="text-sm text-fg-muted flex items-center gap-1.5"
        >
          <span>Agent:</span>
          <select
            :id="agentSelectId"
            v-model="selectedAgentId"
            class="bg-transparent border-0 text-base text-fg-strong px-1 py-1
                   focus:outline-hidden cursor-pointer hover:bg-muted rounded"
          >
            <option
              v-for="agent in agents"
              :key="agent.id"
              :value="agent.id"
            >
              {{ agent.name }}
            </option>
          </select>
        </label>
        <!--
          Single-agent case: no dropdown — the user has nothing to pick
          between. Render as static text to preserve the same horizontal
          slot (keeps the absolute-centered model combobox optically
          centered) while making it obvious no choice is expected. Uses
          a div, not a label: there's no input to associate with.
        -->
        <div
          v-else-if="(agents?.length ?? 0) === 1"
          class="text-sm text-fg-muted flex items-center gap-1.5"
        >
          <span>Agent:</span>
          <span class="text-base text-fg-strong px-1 py-1">{{ selectedAgent?.name }}</span>
        </div>
        <div class="absolute left-1/2 -translate-x-1/2">
          <ChatModelCombobox
            :providers="providers"
            :model-key="selectedModelKey"
            :status-tone="streaming ? 'busy' : (selectedAgent?.providerConfigured === false ? 'offline' : 'ok')"
            @update:model-key="onModelKeyChange"
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
