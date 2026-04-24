<script setup lang="ts">
import {
  ArrowDownTrayIcon,
  ArrowPathIcon,
  CheckIcon,
  ChevronDownIcon,
  ClipboardIcon,
  EyeIcon,
  LightBulbIcon,
  MicrophoneIcon,
  PaperAirplaneIcon,
  PaperClipIcon,
  PencilIcon,
  PencilSquareIcon,
  SpeakerWaveIcon,
  TrashIcon,
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
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { computeConversationCost, formatConversationCost, formatConversationCostTooltip, formatUsageCost, formatUsageCostTooltip, type MessageUsage } from '~/utils/usage-cost'
import { formatSize } from '~/utils/format'
import { thinkingHeaderLabel, initCollapsedState } from '~/utils/thinking'
import { resolveThinkingLock } from '~/utils/thinking-lock'
import { rewriteWorkspaceLinks } from '~/utils/markdown-links'
// Filter out tool messages and empty assistant messages (tool call records) from display.
// The predicate lives in ~/utils/display-message-filter for unit-testability; see
// JCLAW-75 for the specific reasoning-stream regression the reasoning-aware
// suppression rule closes.
import { shouldDisplayMessage } from '~/utils/display-message-filter'

import type { Agent, Conversation, Message, ConfigResponse } from '~/types/api'
import { effectiveThinkingLevels, type ProviderModel } from '~/composables/useProviders'
import { useModelAutocomplete } from '~/composables/useModelAutocomplete'

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

// Configure marked for safe rendering
marked.setOptions({
  breaks: true,
  gfm: true,
})

function formatTokensPerSec(usage: MessageUsage): string | null {
  if (!usage.durationMs || usage.durationMs <= 0 || !usage.completion) return null
  const tps = (usage.completion / usage.durationMs) * 1000
  return tps.toFixed(1) + ' tok/s'
}

// Configure DOMPurify to allow images, audio, video, and download links
DOMPurify.addHook('uponSanitizeAttribute', (node, data) => {
  // Allow src attributes on img/audio/video/source that point to our API
  if (data.attrName === 'src' && data.attrValue?.startsWith('/api/')) {
    data.forceKeepAttr = true
  }
  // Allow href for download links to our API
  if (data.attrName === 'href' && data.attrValue?.startsWith('/api/')) {
    data.forceKeepAttr = true
  }
})

// Marked (CommonMark) only allows whitespace in link destinations when they
// are wrapped in angle brackets. LLMs routinely emit bare filenames with
// spaces like `[file.docx](file.docx)`, which silently fall through as plain
// text. Wrap such destinations in <...> so they parse into real anchors.
function normalizeMarkdownLinks(text: string): string {
  return text.replace(/\[([^\]\n]+)\]\(([^)\n]+)\)/g, (match, label, dest) => {
    const trimmed = dest.trim()
    if (trimmed.startsWith('<') && trimmed.endsWith('>')) return match
    if (!/\s/.test(trimmed)) return match
    return `[${label}](<${trimmed}>)`
  })
}

// Memoized markdown rendering — avoids re-parsing unchanged messages on re-render.
// Cache is keyed by (text + agentId); entries for the streaming message are skipped
// since its content changes every token.
const markdownCache = new Map<string, string>()
const MARKDOWN_CACHE_MAX = 200

function renderMarkdown(text: string, agentId: number | null = null): string {
  if (!text) return ''
  const cacheKey = `${agentId}:${text}`
  const cached = markdownCache.get(cacheKey)
  if (cached) return cached

  const html = marked.parse(normalizeMarkdownLinks(text)) as string
  const sanitized = DOMPurify.sanitize(html, {
    ADD_TAGS: ['img', 'audio', 'video', 'source'],
    ADD_ATTR: ['src', 'controls', 'autoplay', 'download', 'target'],
  })
  const result = agentId != null ? rewriteWorkspaceLinks(sanitized, agentId) : sanitized

  // Only cache if under limit (prevents unbounded growth during long sessions)
  if (markdownCache.size < MARKDOWN_CACHE_MAX) {
    markdownCache.set(cacheKey, result)
  }
  return result
}

const { data: agents, refresh: refreshAgents } = await useFetch<Agent[]>('/api/agents')
const { data: configData } = await useFetch<ConfigResponse>('/api/config')

const selectedAgentId = ref<number | null>(null)

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

// JCLAW-127: Provider/model combos where reasoning cannot be disabled even with
// the toggle off. Shown as a locked-blue pill with an explanatory tooltip so
// the operator isn't misled into thinking their preference was honored.
const thinkingLock = computed(() =>
  resolveThinkingLock(effectiveModel.value.providerName, effectiveModel.value.modelId),
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
const audioSupported = computed(() => selectedModelInfo.value?.supportsAudio === true)

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

// Vision/Audio pills: active-by-default on a capable model (agent field null).
// Only an explicit `false` on the agent record pins them off. This keeps the
// UX aligned with the rest of jclaw — operators opt OUT of a capability, not
// INTO one — and avoids a migration for existing agents on capable models.
const visionActive = computed(() => selectedAgent.value?.visionEnabled !== false)
const audioActive = computed(() => selectedAgent.value?.audioEnabled !== false)

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

function toggleVisionPill() {
  if (!visionSupported.value) return
  updateAgentSetting({ visionEnabled: !visionActive.value })
}

function toggleAudioPill() {
  if (!audioSupported.value) return
  updateAgentSetting({ audioEnabled: !audioActive.value })
}

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

/** Compact "provider/model-id" label for the switch indicator. */
function formatModelLabel(msg: Message): string {
  const u = msg.usage
  if (!u) return '?'
  return u.modelProvider ? `${u.modelProvider}/${u.modelId ?? '?'}` : (u.modelId ?? '?')
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
 * Running cost summary for the currently open conversation. Honors each turn's
 * own embedded pricing so mixed-model conversations (e.g. Kimi → Flash) total
 * correctly. Returns null when the conversation has no assistant turns.
 */
const conversationCostSummary = computed(() => {
  const usages = (displayMessages.value ?? [])
    .filter(m => m.role === 'assistant' && m.usage)
    .map(m => m.usage as MessageUsage)
  if (usages.length === 0) return null
  const breakdown = computeConversationCost(usages)
  const label = formatConversationCost(breakdown)
  if (label === null) return null
  return {
    label,
    tooltip: formatConversationCostTooltip(breakdown),
    turnCount: breakdown.turnCount,
  }
})

// Per-bubble collapse toggle handler. Header label + default-collapse rules
// live in ~/utils/thinking.ts (thinkingHeaderLabel, initCollapsedState) so
// they are unit-testable without mounting the page.
function toggleThinking(msg: Message) {
  msg.thinkingCollapsed = !msg.thinkingCollapsed
}

const conversationsUrl = computed(() =>
  selectedAgentId.value
    ? `/api/conversations?channel=web&agentId=${selectedAgentId.value}&limit=50`
    : null,
)
// `conversationsUrl` is nullable — when null we want useFetch to skip, which
// Nuxt supports at runtime. The public type signature doesn't model null, so
// we cast the Ref down to the narrower runtime contract.
const { data: conversations, refresh: refreshConversations } = await useFetch<Conversation[]>(
  conversationsUrl as unknown as Ref<string>,
)
const selectedConvoId = ref<number | null>(null)
const messages = ref<Message[]>([])
const input = ref('')
const streaming = ref(false)
const streamStatus = ref('')
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
async function regenerateMessage(msg: Message) {
  if (streaming.value) return
  const convoId = selectedConvoId.value
  const idx = messages.value.findIndex(m => m === msg)
  if (idx < 0) return
  let userIdx = -1
  for (let i = idx - 1; i >= 0; i--) {
    if (messages.value[i]!.role === 'user') {
      userIdx = i
      break
    }
  }
  if (userIdx < 0) return
  const userContent = messages.value[userIdx]!.content ?? ''
  if (convoId) {
    for (let i = userIdx; i < messages.value.length; i++) {
      const m = messages.value[i]!
      if (m.id) {
        try {
          await $fetch(`/api/conversations/${convoId}/messages/${m.id}`, { method: 'DELETE' })
        }
        catch { /* best-effort — local truncate still happens */ }
      }
    }
  }
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

/**
 * Backfill server ids onto the local `messages` array after a stream turn.
 *
 * The stream protocol doesn't emit persisted message ids (see
 * ApiChatController.writeSse — the complete event carries only content).
 * That left the optimistic user + assistant bubbles without ids until the
 * user left and re-entered the conversation, which disabled the Delete
 * button (gated on `msg.id`) until then.
 *
 * Rather than renegotiate the SSE contract, we refetch the transcript once
 * at end-of-stream and walk both lists backwards, pairing id-less local
 * rows with the freshest server row of the same role. Bounded by the tail
 * distance so unrelated mid-conversation edits can't re-key older rows.
 */
async function reconcileMessageIds() {
  const convoId = selectedConvoId.value
  if (!convoId) return
  try {
    const fresh = await $fetch<Message[]>(`/api/conversations/${convoId}/messages`)
    if (!fresh?.length) return
    const local = messages.value
    for (let li = local.length - 1, ri = fresh.length - 1; li >= 0 && ri >= 0; li--, ri--) {
      const L = local[li]
      const R = fresh[ri]
      if (!L || !R) continue
      if (L.role !== R.role) break
      if (!L.id && R.id) L.id = R.id
    }
  }
  catch (e) {
    console.error('Failed to reconcile message ids:', e)
  }
}

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
    if (idx >= 0) messages.value.splice(idx, 1)
  }
  catch (e) {
    console.error('Failed to delete message:', e)
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
const messagesEl = ref<HTMLElement | null>(null)
const abortController = ref<AbortController | null>(null)
let scrollRaf: number | null = null
function scrollToBottom() {
  if (scrollRaf) return
  scrollRaf = requestAnimationFrame(() => {
    if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    scrollRaf = null
  })
}

/**
 * Reasoning bubble is a fixed-height scroll region (see the h-80 data-
 * reasoning-body div in the template). As reasoning tokens stream in, pin
 * the last one to its own bottom so the latest thought is visible without
 * the user having to chase the scroll themselves. Only the in-flight
 * message's bubble is updated — historical messages keep whatever scroll
 * position the user set.
 */
watch(streamReasoning, async () => {
  if (!streaming.value) return
  await nextTick()
  const bodies = messagesEl.value?.querySelectorAll<HTMLElement>('[data-reasoning-body]')
  const last = bodies?.[bodies.length - 1]
  if (last) last.scrollTop = last.scrollHeight
})

onUnmounted(() => {
  abortController.value?.abort()
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
})

function stopStreaming() {
  if (!streaming.value) return
  abortController.value?.abort()
  streaming.value = false
  streamStatus.value = ''
}
const displayMessages = computed(() =>
  messages.value.filter(m => shouldDisplayMessage(m, streaming.value)),
)

// Deep-link: if ?conversation=ID is present, load that conversation and switch
// to its agent on mount.
const route = useRoute()
const deepLinkConvoId = route.query.conversation ? Number(route.query.conversation) : null
const initializing = ref(true) // suppresses agent-change clear during setup

// Auto-select agent on load
watch(agents, (val) => {
  if (!val?.length || selectedAgentId.value) return
  const def = val.find(a => a.isMain) || val[0]
  if (!def) return
  selectedAgentId.value = def.id
}, { immediate: true })

// When agent changes, clear current conversation (unless during initial setup)
watch(selectedAgentId, () => {
  if (initializing.value) return
  selectedConvoId.value = null
  messages.value = []
})

// Deep-link: once conversations are loaded, find and select the target conversation.
// The conversationsUrl computed auto-fetches when selectedAgentId changes, so we
// watch the conversations data to detect when the right agent's list arrives.
if (deepLinkConvoId) {
  const stopDeepLink = watch(conversations, async (convos) => {
    if (!convos || !agents.value?.length) return

    // Check if the target conversation is in the current agent's list
    const found = convos.find(c => c.id === deepLinkConvoId)
    if (found) {
      // It's in the current list — select it
      selectedConvoId.value = deepLinkConvoId
      messages.value = await $fetch<Message[]>(`/api/conversations/${deepLinkConvoId}/messages`) ?? []
      initCollapsedState(messages.value)
      scrollToBottom()
      initializing.value = false
      stopDeepLink()
      return
    }

    // Not in the current agent's list — find which agent owns it
    if (initializing.value) {
      try {
        const allConvos = await $fetch<Conversation[]>('/api/conversations?channel=web&limit=100')
        const convo = allConvos?.find(c => c.id === deepLinkConvoId)
        if (convo) {
          const agent = agents.value.find(a => a.name === convo.agentName)
          if (agent && agent.id !== selectedAgentId.value) {
            // Switch agent — this triggers conversationsUrl to change, which
            // triggers useFetch to refetch, which triggers this watcher again
            // with the correct agent's conversation list.
            selectedAgentId.value = agent.id
            return // wait for next watcher fire with new conversations
          }
        }
      }
      catch { /* fall through */ }
      // Couldn't find the conversation — give up and finish init
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

async function loadConversation(id: number) {
  selectedConvoId.value = id
  messages.value = await $fetch<Message[]>(`/api/conversations/${id}/messages`) ?? []
  initCollapsedState(messages.value)
  scrollToBottom()
}

// Sidebar Recents deep-link from within /chat: NuxtLink swaps the query
// without remounting the page, so the one-shot deep-link watcher above
// doesn't re-fire. This route-query watcher catches in-page navigations
// and loads the target conversation — switching agents when the target
// belongs to a different agent's list.
watch(() => route.query.conversation, async (raw) => {
  const id = raw ? Number(raw) : null
  if (!id || id === selectedConvoId.value) return
  const ownedByCurrent = conversations.value?.some(c => c.id === id) ?? false
  if (ownedByCurrent) {
    await loadConversation(id)
    return
  }
  // Target lives under a different agent — find it and switch, then load.
  try {
    const allConvos = await $fetch<Conversation[]>('/api/conversations?channel=web&limit=100')
    const convo = allConvos?.find(c => c.id === id)
    if (!convo) return
    const agent = agents.value?.find(a => a.name === convo.agentName)
    if (agent && agent.id !== selectedAgentId.value) {
      selectedAgentId.value = agent.id
    }
    await loadConversation(id)
  }
  catch { /* best-effort */ }
})

async function sendMessage() {
  if (streaming.value || !selectedAgentId.value) return
  const rawText = input.value.trim()
  if (!rawText && !attachedFiles.value.length) return

  attachError.value = null
  const pending = attachedFiles.value.slice()
  let uploaded: UploadedAttachment[] = []
  if (pending.length) {
    try {
      uploaded = await uploadAttachments(selectedAgentId.value)
    }
    catch (e: unknown) {
      const err = e as { data?: { error?: string }, message?: string } | undefined
      attachError.value = 'Upload failed: ' + (err?.data?.error || err?.message || 'unknown error')
      return
    }
  }

  // JCLAW-25: message.content is the user's raw text. Attachment metadata
  // rides in the `attachments` field; the backend persists chat_message_attachment
  // rows and synthesizes the LLM content parts (image_url for images, bracketed
  // file references for non-images) from those rows on every turn.
  const text = rawText

  input.value = ''
  // Revoke any blob preview URLs still held for the just-sent attachments.
  for (const f of pending) {
    const url = attachmentPreviews.value.get(f)
    if (url) URL.revokeObjectURL(url)
    attachmentPreviews.value.delete(f)
  }
  attachedFiles.value = []
  if (chatInput.value) chatInput.value.style.height = 'auto'
  messages.value.push({ _key: crypto.randomUUID(), role: 'user', content: text, createdAt: new Date().toISOString() })
  scrollToBottom()

  streaming.value = true
  streamContent.value = ''
  streamReasoning.value = ''
  streamStatus.value = ''

  // Capture the conversation id we're sending — if the server returns a
  // DIFFERENT id in the init frame, the backend minted a new conversation
  // (e.g. /new slash-command, JCLAW-26). We need to discard the stale
  // visible history from the prior conversation so the view matches what
  // the new conversation row actually contains on reload.
  const sentConversationId = selectedConvoId.value

  // Add placeholder for streaming response
  let assistantIdx = messages.value.length
  messages.value.push({ _key: crypto.randomUUID(), role: 'assistant', content: '', createdAt: new Date().toISOString() })

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

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue
        try {
          const event = JSON.parse(line.slice(6))
          if (event.type === 'init' && event.conversationId) {
            // JCLAW-26: if the server minted a new conversation (e.g.
            // /new), reset the visible history to match what the new
            // row actually contains — the assistant placeholder we're
            // about to stream into. The optimistic user bubble and the
            // prior conversation's loaded history get dropped.
            if (sentConversationId !== null && sentConversationId !== event.conversationId) {
              const placeholder = messages.value[assistantIdx]
              if (placeholder) {
                messages.value = [placeholder]
                assistantIdx = 0
              }
            }
            selectedConvoId.value = event.conversationId
            if (event.thinkingMode) {
              streamStatus.value = `thinking (${event.thinkingMode})...`
            }
          }
          else if (event.type === 'status') {
            // Check if this is a usage JSON payload
            if (event.content?.startsWith('{') && event.content.includes('"usage"')) {
              try {
                const parsed = JSON.parse(event.content)
                if (parsed.usage) {
                  messages.value[assistantIdx]!.usage = parsed.usage
                  // The metrics pill renders below the bubble the moment
                  // usage lands. Without this scroll the per-token scroller
                  // leaves the viewport pinned to the pre-metrics bottom
                  // and the user sees a half-cropped stats row.
                  scrollToBottom()
                }
              }
              catch { /* not JSON, treat as status text */ }
            }
            else {
              streamStatus.value = event.content
            }
          }
          else if (event.type === 'reasoning') {
            const m = messages.value[assistantIdx] as StreamingMessage
            // Bubble state is set once on the first reasoning chunk. Writing
            // these properties again on every subsequent chunk — even to the
            // same value — triggers Vue reactivity and makes the bubble
            // visibly reflow/flicker (observed as "expands and contracts over
            // and over"). The protective _thinkingInProgress=true flag is
            // enough to pin the "Thinking" label for the duration of the
            // reasoning phase; no need to re-pin it per chunk.
            if (!m._thinkingStartedAt) {
              m._thinkingStartedAt = Date.now()
              m._thinkingInProgress = true
              m._thinkingDurationMs = null
              m.thinkingCollapsed = false
            }
            streamReasoning.value += event.content
            m.reasoning = streamReasoning.value
            if (!streamContent.value) {
              streamStatus.value = 'thinking...'
            }
            scrollToBottom()
          }
          else if (event.type === 'token') {
            // Empty-content token events are emitted by OpenAI-compatible
            // providers (observed on Kimi K2.5 via OpenRouter) interleaved
            // with every reasoning chunk — the `content` field is always
            // present in the API schema and defaults to "" when the chunk
            // only carries reasoning. Treating those as a real reasoning→
            // content transition was the "Thought for 0.01 seconds during
            // streaming" bug: the second empty-content token after the
            // first reasoning event was tripping the flip. Skip them
            // entirely so the transition fires only on the first byte of
            // genuine content.
            if (!event.content) continue
            const m = messages.value[assistantIdx] as StreamingMessage
            // Reasoning→content transition: stamp duration, flip flag,
            // auto-collapse. Guarded by _thinkingInProgress so the stamp
            // fires exactly once at the transition — subsequent content
            // tokens are no-ops on this branch.
            if (m._thinkingInProgress) {
              m._thinkingDurationMs = Date.now() - (m._thinkingStartedAt ?? Date.now())
              m._thinkingInProgress = false
              m.thinkingCollapsed = true
            }
            streamStatus.value = ''
            if (event.timestamp) m.createdAt = event.timestamp
            streamContent.value += event.content
            m.content = streamContent.value
            scrollToBottom()
          }
          else if (event.type === 'complete') {
            const m = messages.value[assistantIdx] as StreamingMessage
            // Reasoning-only turn (no content streamed): finalize duration here.
            if (m._thinkingInProgress) {
              m._thinkingDurationMs = Date.now() - (m._thinkingStartedAt ?? Date.now())
              m._thinkingInProgress = false
              m.thinkingCollapsed = true
            }
            streamStatus.value = ''
            m.content = event.content || streamContent.value
            // Collapsing thinking + swapping in final content shifts layout
            // height. Re-pin to the bottom so the next thing the user sees
            // (the metrics pill landing on the subsequent status event) is
            // fully in view.
            scrollToBottom()
          }
          else if (event.type === 'error') {
            messages.value[assistantIdx]!.content = event.content
          }
          else if (event.type === 'queued') {
            messages.value[assistantIdx]!.content = 'Your message has been queued (position: ' + (event.position || '?') + '). Processing shortly...'
          }
        }
        catch {
          // Skip malformed events
        }
      }
    }
  }
  catch (e: unknown) {
    // AbortError is expected when the user clicks Stop — preserve any content
    // that was already streamed and append a small "(stopped)" marker instead
    // of replacing the bubble with a scary error.
    const err = e as { name?: string, message?: string } | undefined
    if (err?.name === 'AbortError') {
      const existing = messages.value[assistantIdx]!.content || streamContent.value || ''
      messages.value[assistantIdx]!.content = existing
        ? existing.replace(/\s*$/, '') + '\n\n_(stopped)_'
        : '_(stopped before any response)_'
    }
    else {
      messages.value[assistantIdx]!.content = 'Error: ' + (err?.message || 'Failed to get response')
    }
  }
  finally {
    streaming.value = false
    triggerRef(messages) // re-render with final content + markdown
    // triggerRef can add height (markdown codeblocks expand, metrics pill
    // appears if usage arrived after complete). Wait a tick so the DOM
    // reflects the final layout, then pin to the bottom.
    nextTick().then(scrollToBottom)
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

const fileInput = ref<HTMLInputElement | null>(null)
const attachedFiles = ref<File[]>([])
const attachError = ref<string | null>(null)
const MAX_ATTACHMENTS = 5

// JCLAW-131: per-kind upload caps sourced from /api/config, with defaults
// matching services/UploadLimits.java. The server re-applies these caps on
// upload (authoritative); the frontend copy is just UX — showing the user
// the refusal before bytes leave the browser.
const DEFAULT_MAX_IMAGE_BYTES = 20 * 1024 * 1024
const DEFAULT_MAX_AUDIO_BYTES = 100 * 1024 * 1024
const DEFAULT_MAX_FILE_BYTES = 100 * 1024 * 1024

function configInt(key: string, fallback: number): number {
  const raw = configData.value?.entries?.find(e => e.key === key)?.value
  if (!raw) return fallback
  const parsed = Number.parseInt(raw, 10)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : fallback
}
const maxImageBytes = computed(() => configInt('upload.maxImageBytes', DEFAULT_MAX_IMAGE_BYTES))
const maxAudioBytes = computed(() => configInt('upload.maxAudioBytes', DEFAULT_MAX_AUDIO_BYTES))
const maxFileBytes = computed(() => configInt('upload.maxFileBytes', DEFAULT_MAX_FILE_BYTES))

// Per-file thumbnail preview URL for image attachments (JCLAW-25). Keyed by
// File identity via a WeakMap-like ref map; createObjectURL results are
// revoked when the chip is removed so we don't leak blob handles across
// long chat sessions.
const attachmentPreviews = ref(new Map<File, string>())

/** Upload response shape returned per file from POST /api/chat/upload. */
interface UploadedAttachment {
  attachmentId: string
  originalFilename: string
  mimeType: string
  sizeBytes: number
  kind: 'IMAGE' | 'FILE'
}

function isImageFile(f: File): boolean {
  return typeof f.type === 'string' && f.type.startsWith('image/')
}

function isAudioFile(f: File): boolean {
  return typeof f.type === 'string' && f.type.startsWith('audio/')
}

/** Effective byte cap for this File's kind, sourced from Settings config. */
function capForFile(f: File): number {
  if (isImageFile(f)) return maxImageBytes.value
  if (isAudioFile(f)) return maxAudioBytes.value
  return maxFileBytes.value
}

function kindLabelForFile(f: File): string {
  if (isImageFile(f)) return 'image'
  if (isAudioFile(f)) return 'audio'
  return 'file'
}

function triggerFileUpload() {
  fileInput.value?.click()
}

function handleFileUpload(event: Event) {
  const target = event.target as HTMLInputElement
  const picked = target.files ? Array.from(target.files) : []
  addAttachments(picked)
  target.value = ''
}

// JCLAW-25 drop path. Mirrors the paperclip flow: read files off the
// DataTransfer, route through addAttachments so the vision gate, size
// cap, and thumbnail preview apply uniformly regardless of how the file
// entered the composer.
function handleDrop(event: DragEvent) {
  const files = event.dataTransfer?.files
  if (!files || files.length === 0) return
  addAttachments(Array.from(files))
}

// JCLAW-25 paste path. Inspects the clipboard items for file entries —
// typically a single image from a screenshot-copy (Cmd/Ctrl-Shift-4,
// Windows Snipping Tool, etc.). Text pastes fall through untouched.
function handlePaste(event: ClipboardEvent) {
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

function addAttachments(files: File[]) {
  attachError.value = null
  for (const f of files) {
    if (attachedFiles.value.length >= MAX_ATTACHMENTS) {
      attachError.value = `Maximum ${MAX_ATTACHMENTS} files per message`
      break
    }
    // JCLAW-131: per-kind size cap, sourced from Settings config. The
    // human-readable message names the kind so the operator knows which
    // limit to raise.
    const cap = capForFile(f)
    if (f.size > cap) {
      const label = kindLabelForFile(f)
      attachError.value = `${f.name} exceeds ${Math.round(cap / (1024 * 1024))} MB limit for ${label} uploads`
      continue
    }
    // JCLAW-25 capability gate: refuse image attachments when the selected
    // model doesn't advertise vision.
    if (isImageFile(f) && !visionSupported.value) {
      attachError.value = 'This model does not support images'
      continue
    }
    // JCLAW-131: mirror gate for audio. Backend returns 400 on send if the
    // client bypasses this; showing the refusal at attach time avoids a
    // pointless round-trip and gives the operator the exact string.
    if (isAudioFile(f) && !audioSupported.value) {
      attachError.value = 'This model does not support audio'
      continue
    }
    attachedFiles.value.push(f)
    if (isImageFile(f)) {
      attachmentPreviews.value.set(f, URL.createObjectURL(f))
    }
  }
}

function removeAttachment(idx: number) {
  const f = attachedFiles.value[idx]
  if (f) {
    const url = attachmentPreviews.value.get(f)
    if (url) {
      URL.revokeObjectURL(url)
      attachmentPreviews.value.delete(f)
    }
  }
  attachedFiles.value.splice(idx, 1)
}

// ────────────────────── Voice-note recording (browser mic) ─────────────────
// One click starts the recorder; a second click stops it and attaches the
// captured blob through the same addAttachments() pipeline as paperclip
// uploads. Gated on audioSupported at the button level — consistent with
// the Audio capability pill, and avoids a rejection on attach time.

const isRecording = ref(false)
// Kept as plain non-reactive bindings — Vue reactivity on a MediaRecorder
// proxy would be wasteful and the ondataavailable callback can't walk a
// reactive wrapper without surprises.
let mediaRecorder: MediaRecorder | null = null
let mediaStream: MediaStream | null = null
let recordedChunks: Blob[] = []

/** Pick the best supported MIME for this browser. Chromium ships
 *  audio/webm (Opus); Safari exposes audio/mp4 instead. Returns null if
 *  neither is supported so the caller can fall back to a default. */
function pickAudioMime(): string | null {
  if (typeof MediaRecorder === 'undefined') return null
  const candidates = ['audio/webm;codecs=opus', 'audio/webm', 'audio/mp4']
  for (const c of candidates) {
    if (MediaRecorder.isTypeSupported(c)) return c
  }
  return null
}

function extensionForMime(mime: string): string {
  if (mime.startsWith('audio/webm')) return 'webm'
  if (mime.startsWith('audio/mp4')) return 'm4a'
  return 'bin'
}

function releaseRecorder() {
  if (mediaStream) {
    for (const track of mediaStream.getTracks()) track.stop()
  }
  mediaStream = null
  mediaRecorder = null
  recordedChunks = []
}

async function startRecording() {
  if (isRecording.value) return
  // Cheap pre-check so we don't prompt for mic permission on a model that
  // can't consume the resulting attachment anyway — addAttachments would
  // reject it after the fact, which wastes both the permission grant and
  // the recording itself. Mirrors the vision-gate error copy below.
  if (!audioSupported.value) {
    attachError.value = 'This model does not support audio'
    return
  }
  if (typeof navigator === 'undefined' || !navigator.mediaDevices?.getUserMedia) {
    attachError.value = 'Voice recording not supported in this browser'
    return
  }
  let stream: MediaStream
  try {
    stream = await navigator.mediaDevices.getUserMedia({ audio: true })
  }
  catch {
    // NotAllowedError (denied) and NotFoundError (no device) both land here;
    // the user-visible string is deliberately generic since the remedy —
    // "check site permissions / plug in a mic" — covers both.
    attachError.value = 'Microphone access denied or unavailable'
    return
  }
  const mime = pickAudioMime()
  mediaStream = stream
  recordedChunks = []
  try {
    mediaRecorder = new MediaRecorder(stream, mime ? { mimeType: mime } : undefined)
  }
  catch {
    attachError.value = 'Voice recording not supported in this browser'
    releaseRecorder()
    return
  }
  mediaRecorder.ondataavailable = (e: BlobEvent) => {
    if (e.data && e.data.size > 0) recordedChunks.push(e.data)
  }
  mediaRecorder.onstop = () => {
    // mediaRecorder is nulled in releaseRecorder(); capture the type first.
    const effectiveType = mediaRecorder?.mimeType || mime || 'audio/webm'
    const blob = new Blob(recordedChunks, { type: effectiveType })
    const ext = extensionForMime(effectiveType)
    // YYYYMMDD-HHMMSS in local time — readable at a glance in the chip
    // and unique enough across a single session. We skip the timezone
    // because the filename doesn't roundtrip to the agent; it's just UX.
    const d = new Date()
    const pad = (n: number) => String(n).padStart(2, '0')
    const ts = `${d.getFullYear()}${pad(d.getMonth() + 1)}${pad(d.getDate())}`
      + `-${pad(d.getHours())}${pad(d.getMinutes())}${pad(d.getSeconds())}`
    const file = new File([blob], `voice-${ts}.${ext}`, { type: effectiveType })
    addAttachments([file])
    releaseRecorder()
  }
  mediaRecorder.start()
  isRecording.value = true
}

function stopRecording() {
  if (!isRecording.value) return
  isRecording.value = false
  if (mediaRecorder && mediaRecorder.state !== 'inactive') {
    // onstop fires asynchronously after this call; releaseRecorder is
    // invoked from inside the handler so the final blob is built before
    // we tear down.
    mediaRecorder.stop()
  }
  else {
    releaseRecorder()
  }
}

function toggleRecording() {
  if (isRecording.value) stopRecording()
  else void startRecording()
}

onBeforeUnmount(() => {
  if (isRecording.value) stopRecording()
  else releaseRecorder()
})

const formatAttachmentSize = formatSize

async function uploadAttachments(agentId: number): Promise<UploadedAttachment[]> {
  if (!attachedFiles.value.length) return []
  const form = new FormData()
  form.append('agentId', String(agentId))
  for (const f of attachedFiles.value) {
    form.append('files', f, f.name)
  }
  const res = await $fetch<{ files: UploadedAttachment[] }>(
    '/api/chat/upload',
    { method: 'POST', body: form },
  )
  return res.files
}

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
defineExpose({ addAttachments, attachedFiles, attachError })

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
  a.download = `${title.replace(/[^a-zA-Z0-9 ]/g, '').replace(/\s+/g, '-').toLowerCase()}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
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
            :cost-label="conversationCostSummary?.label ?? null"
            :cost-tooltip="conversationCostSummary?.tooltip ?? null"
            :turn-count="conversationCostSummary?.turnCount ?? null"
          />
        </span>
      </div>

      <!--
        Body wrapper: a flex-col with a pair of spacers (top + bottom)
        that instantly reflow between 0 and 1fr. When the chat is empty
        the spacers grow so the hero + composer sit at vertical center;
        when a conversation is active they collapse and the messages
        scroller takes over. We deliberately do NOT transition flex-grow
        — see Unsloth Studio, which swaps the composer between a
        centered welcome slot and an absolute-bottom dock with zero
        transition. The animated alternative competed with the hero fade
        and the caption's v-if layout jump and looked jankier than a
        snap reflow.
      -->
      <div class="flex-1 flex flex-col min-h-0">
        <!-- Top spacer: grows to push content toward vertical center on empty. -->
        <div
          class="shrink-0 basis-0"
          :style="{ flexGrow: isEmptyChat ? 1 : 0 }"
          aria-hidden="true"
        />

        <!--
          Empty-state hero with a brief fade-in. Matches Unsloth's
          Tailwind Animate `animate-in fade-in slide-in-from-bottom-1`
          default (~150ms) — long enough to read as intentional, short
          enough to not compete with the instant spacer reflow above.
        -->
        <Transition
          enter-active-class="transition-opacity duration-150 ease-out"
          enter-from-class="opacity-0"
          enter-to-class="opacity-100"
          leave-active-class="transition-opacity duration-100 ease-out"
          leave-from-class="opacity-100"
          leave-to-class="opacity-0"
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
            <template
              v-for="(msg, msgIdx) in displayMessages"
              :key="msg.id ?? msg._key"
            >
              <!-- JCLAW-108: divider when two adjacent assistant messages ran on
                 different models. Helps make mid-conversation /model switches
                 visible in the scrollback. -->
              <div
                v-if="shouldShowModelSwitchIndicator(msgIdx)"
                class="flex items-center gap-3 text-xs text-fg-muted select-none"
              >
                <span class="flex-1 border-t border-border-subtle" />
                <span class="whitespace-nowrap">Switched to {{ formatModelLabel(msg) }}</span>
                <span class="flex-1 border-t border-border-subtle" />
              </div>
              <div
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
                    <div
                      class="inline-block bg-muted rounded-2xl text-fg-strong px-4 py-2 text-base whitespace-pre-wrap break-words"
                    >
                      {{ msg.content }}
                    </div>
                    <div class="flex items-center justify-end gap-1 mt-1 h-5 opacity-0 group-hover:opacity-100 transition-opacity">
                      <button
                        type="button"
                        class="p-1 text-fg-muted hover:text-fg-primary transition-colors"
                        :title="copiedMessageId === (msg.id ?? msg._key) ? 'Copied' : 'Copy to clipboard'"
                        @click="copyMessage(msg)"
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
                        :disabled="streaming"
                        class="p-1 text-fg-muted hover:text-fg-primary disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
                        title="Edit & resubmit"
                        @click="editUserMessage(msg)"
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
                        @click="deleteMessage(msg)"
                      >
                        <TrashIcon
                          class="w-4 h-4"
                          aria-hidden="true"
                        />
                      </button>
                    </div>
                  </div>
                  <!-- Assistant messages: optional thinking card + plain markdown body -->
                  <div
                    v-else
                    class="group"
                  >
                    <!--
                  Thinking/reasoning block, Unsloth-style: bordered card with a
                  "Thought for Xs" header (lightbulb + chevron) and an in-place
                  Copy button. Collapsed by default on historical turns; in-flight
                  turns open on first reasoning delta and auto-collapse at the
                  reasoning→content transition.
                -->
                    <div
                      v-if="msg.reasoning"
                      class="mb-3 border border-neutral-200 dark:border-neutral-700 rounded-xl overflow-hidden bg-surface-elevated"
                    >
                      <div class="flex items-center gap-2 px-3 py-2">
                        <button
                          type="button"
                          class="flex-1 flex items-center gap-2 text-left text-xs text-fg-muted hover:text-fg-strong focus:outline-none"
                          :title="msg.thinkingCollapsed ? 'Expand reasoning' : 'Collapse reasoning'"
                          @click="toggleThinking(msg)"
                        >
                          <LightBulbIconSolid
                            class="w-3.5 h-3.5 shrink-0 text-amber-400 drop-shadow-[0_0_4px_rgba(251,191,36,0.55)]"
                            aria-hidden="true"
                          />
                          <span class="font-medium">{{ thinkingHeaderLabel(msg) }}</span>
                          <ChevronDownIcon
                            class="w-3.5 h-3.5 transition-transform ml-1"
                            :class="msg.thinkingCollapsed ? '' : 'rotate-180'"
                            aria-hidden="true"
                          />
                        </button>
                        <button
                          type="button"
                          class="shrink-0 inline-flex items-center gap-1 px-2 py-1 text-[11px] text-fg-muted hover:text-fg-strong transition-colors"
                          :title="copiedMessageId === `reason:${msg.id ?? msg._key}` ? 'Copied' : 'Copy reasoning'"
                          @click.stop="copyReasoning(msg)"
                        >
                          <ClipboardIcon
                            v-if="copiedMessageId !== `reason:${msg.id ?? msg._key}`"
                            class="w-3.5 h-3.5"
                            aria-hidden="true"
                          />
                          <CheckIcon
                            v-else
                            class="w-3.5 h-3.5 text-emerald-500"
                            aria-hidden="true"
                          />
                          <span>Copy</span>
                        </button>
                      </div>
                      <!-- eslint-disable vue/no-v-html -- renderMarkdown runs content through DOMPurify (see renderMarkdown above) before returning. -->
                      <div
                        v-if="!msg.thinkingCollapsed"
                        data-reasoning-body
                        class="prose-chat px-4 pb-3 pt-1 text-base text-fg-primary break-words h-80 overflow-y-auto border-t border-neutral-200 dark:border-neutral-700"
                        v-html="renderMarkdown(msg.reasoning, selectedAgentId)"
                      />
                      <!-- eslint-enable vue/no-v-html -->
                    </div>
                    <!-- Response content — plain rendered markdown, no bubble. -->
                    <!-- eslint-disable vue/no-v-html -- renderMarkdown runs content through DOMPurify (see renderMarkdown above) before returning. -->
                    <div
                      v-if="msg.content"
                      class="prose-chat text-fg-primary text-base overflow-x-auto break-words"
                      v-html="renderMarkdown(msg.content, selectedAgentId)"
                    />
                    <!-- eslint-enable vue/no-v-html -->
                    <div
                      v-else-if="!msg.reasoning"
                      class="text-fg-muted text-base italic"
                    >
                      (empty response)
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
                          : 'opacity-0 group-hover:opacity-100',
                      ]"
                    >
                      <button
                        type="button"
                        class="p-1 text-fg-muted hover:text-fg-primary transition-colors"
                        :title="copiedMessageId === (msg.id ?? msg._key) ? 'Copied' : 'Copy to clipboard'"
                        @click="copyMessage(msg)"
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
                        :disabled="streaming"
                        class="p-1 text-fg-muted hover:text-fg-primary disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
                        title="Regenerate response"
                        @click="regenerateMessage(msg)"
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
                        @click="deleteMessage(msg)"
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
                        @update:open="(v) => { if (!v) tokStatsHoverKey = null }"
                      >
                        <PopoverTrigger as-child>
                          <button
                            type="button"
                            class="ml-1 px-2 py-0.5 text-xs font-mono tabular-nums text-fg-muted hover:text-fg-primary rounded-md transition-colors cursor-help"
                            :aria-label="`Response speed: ${formatTokensPerSec(msg.usage)}`"
                            @mouseenter="tokStatsHoverKey = msg.id ?? msg._key ?? null"
                            @mouseleave="tokStatsHoverKey = null"
                            @focus="tokStatsHoverKey = msg.id ?? msg._key ?? null"
                            @blur="tokStatsHoverKey = null"
                          >
                            {{ formatTokensPerSec(msg.usage) }}
                          </button>
                        </PopoverTrigger>
                        <PopoverContent
                          side="top"
                          align="start"
                          :side-offset="8"
                          class="min-w-52 px-3 py-2 rounded-[10px] border-neutral-200 dark:border-neutral-700/50"
                          @mouseenter="tokStatsHoverKey = msg.id ?? msg._key ?? null"
                          @mouseleave="tokStatsHoverKey = null"
                          @focusin="tokStatsHoverKey = msg.id ?? msg._key ?? null"
                          @focusout="tokStatsHoverKey = null"
                        >
                          <dl class="grid gap-1.5 text-xs">
                            <div class="flex items-center justify-between gap-4">
                              <dt class="text-muted-foreground">
                                Prompt tokens
                              </dt>
                              <dd class="font-mono tabular-nums">
                                {{ msg.usage.prompt.toLocaleString() }}
                              </dd>
                            </div>
                            <div
                              v-if="msg.usage.reasoning"
                              class="flex items-center justify-between gap-4"
                            >
                              <dt class="text-muted-foreground">
                                Thinking tokens
                              </dt>
                              <dd class="font-mono tabular-nums">
                                {{ msg.usage.reasoning.toLocaleString() }}
                              </dd>
                            </div>
                            <div
                              v-if="msg.usage.cached"
                              class="flex items-center justify-between gap-4"
                            >
                              <dt class="text-muted-foreground">
                                Cached tokens
                              </dt>
                              <dd class="font-mono tabular-nums">
                                {{ msg.usage.cached.toLocaleString() }}
                              </dd>
                            </div>
                            <div class="flex items-center justify-between gap-4">
                              <dt class="text-muted-foreground">
                                Completion
                              </dt>
                              <dd class="font-mono tabular-nums">
                                {{ msg.usage.completion.toLocaleString() }}
                              </dd>
                            </div>
                            <div
                              aria-hidden="true"
                              class="my-0.5 border-t border-neutral-200 dark:border-neutral-700/50"
                            />
                            <div class="flex items-center justify-between gap-4">
                              <dt class="text-muted-foreground">
                                Speed
                              </dt>
                              <dd class="font-mono tabular-nums">
                                {{ formatTokensPerSec(msg.usage) }}
                              </dd>
                            </div>
                            <div
                              v-if="msg.usage.durationMs"
                              class="flex items-center justify-between gap-4"
                            >
                              <dt class="text-muted-foreground">
                                Total
                              </dt>
                              <dd class="font-mono tabular-nums">
                                {{ (msg.usage.durationMs / 1000).toFixed(2) }}s
                              </dd>
                            </div>
                            <div
                              v-if="formatUsageCost(msg.usage)"
                              class="flex items-center justify-between gap-4"
                              :title="formatUsageCostTooltip(msg.usage) ?? undefined"
                            >
                              <dt class="text-muted-foreground">
                                Cost
                              </dt>
                              <dd class="font-mono tabular-nums text-amber-500/80">
                                {{ formatUsageCost(msg.usage) }}
                              </dd>
                            </div>
                          </dl>
                        </PopoverContent>
                      </Popover>
                    </div>
                  </div>
                </div>
              </div>
            </template>
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

        <!-- Input -->
        <div class="px-4 py-3 relative mx-auto w-full max-w-3xl">
          <!-- JCLAW-114: /model NAME autocomplete popup, anchored above the
             form. Rendered outside the form so the form's overflow-hidden
             (needed for rounded borders) doesn't clip the popup. -->
          <div
            v-if="modelAutocomplete.open.value"
            class="absolute left-4 right-4 bottom-full mb-1 z-10
                 bg-surface-elevated border border-border rounded-md shadow-lg
                 max-h-60 overflow-y-auto"
            role="listbox"
            aria-label="Model completion options"
          >
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
                  class="text-red-600 dark:text-red-400/70 hover:text-red-800 dark:hover:text-red-200 transition-colors"
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
              placeholder="Send a message..."
              :disabled="streaming"
              rows="1"
              aria-label="Message input"
              class="w-full px-4 pt-4 pb-6 bg-transparent text-sm text-fg-strong
                   placeholder-fg-muted focus:outline-hidden resize-none overflow-hidden"
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
                    ? 'border-red-500 text-red-500 bg-red-500/10 animate-pulse'
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
                  type="button"
                  class="inline-flex items-center gap-1.5 px-3 h-8 rounded-full text-xs font-medium transition-colors"
                  :class="[
                    (thinkingActive || thinkingLock.locked)
                      ? 'bg-emerald-500/15 text-emerald-500 hover:bg-emerald-500/25'
                      : 'border border-border text-fg-muted hover:text-fg-strong hover:bg-muted',
                    thinkingLock.locked ? 'cursor-not-allowed opacity-90' : '',
                  ]"
                  :aria-disabled="thinkingLock.locked"
                  :title="thinkingLock.locked
                    ? thinkingLock.reason
                    : (thinkingActive ? 'Thinking on — click to turn off' : 'Thinking off — click to turn on')"
                  @click="toggleThinkingPill"
                >
                  <LightBulbIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                  Think
                </button>
                <button
                  v-if="visionSupported"
                  type="button"
                  class="inline-flex items-center gap-1.5 px-3 h-8 rounded-full text-xs font-medium transition-colors"
                  :class="visionActive
                    ? 'bg-sky-500/15 text-sky-400 hover:bg-sky-500/25'
                    : 'border border-border text-fg-muted hover:text-fg-strong hover:bg-muted'"
                  :title="visionActive ? 'Vision on — click to turn off' : 'Vision off — click to turn on'"
                  @click="toggleVisionPill"
                >
                  <EyeIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                  Vision
                </button>
                <button
                  v-if="audioSupported"
                  type="button"
                  class="inline-flex items-center gap-1.5 px-3 h-8 rounded-full text-xs font-medium transition-colors"
                  :class="audioActive
                    ? 'bg-amber-500/15 text-amber-400 hover:bg-amber-500/25'
                    : 'border border-border text-fg-muted hover:text-fg-strong hover:bg-muted'"
                  :title="audioActive ? 'Audio on — click to turn off' : 'Audio off — click to turn on'"
                  @click="toggleAudioPill"
                >
                  <SpeakerWaveIcon
                    class="w-3.5 h-3.5"
                    aria-hidden="true"
                  />
                  Audio
                </button>
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
                  class="p-1.5 text-red-500 hover:text-red-400 transition-colors"
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
                  class="p-1.5 text-emerald-500 hover:text-emerald-400 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                  title="Send"
                >
                  <PaperAirplaneIcon
                    class="w-5 h-5"
                    aria-hidden="true"
                  />
                </button>
              </div>
            </div>
          </form>
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
.prose-chat h1, .prose-chat h2, .prose-chat h3 { font-weight: 600; margin: 0.75em 0 0.25em; }
.prose-chat h1 { font-size: 1.1em; }
.prose-chat h2 { font-size: 1em; }
.prose-chat h3 { font-size: 0.95em; }
.prose-chat pre { padding: 0.75em 1em; margin: 0.5em 0; overflow-x: auto; }
.prose-chat pre code { background: none; padding: 0; }

.prose-chat code {
  padding: 0.15em 0.35em;
  font-size: 0.875em;
  font-family: ui-monospace, monospace;
}
.prose-chat a { text-decoration: underline; }

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
}
.prose-chat a.workspace-file::before { content: "⬇"; font-size: 0.85em; opacity: 0.75; }

.prose-chat blockquote {
  padding-left: 0.75em;
  margin: 0.5em 0;
}
.prose-chat hr { border: none; margin: 0.75em 0; }

.prose-chat img {
  max-width: 100%;
  height: auto;
  border-radius: 0.5em;
  margin: 0.5em 0;
  cursor: pointer;
}

.prose-chat audio, .prose-chat video {
  max-width: 100%;
  margin: 0.5em 0;
  border-radius: 0.5em;
}

.prose-chat table {
  /* Fixed layout + a common first-column width is what makes separate tables
     align across the page. When the agent emits one table per category (tools
     catalog) or a skills table next to a tools table, readers expect the
     identifier columns to line up; with `auto` layout each table auto-sizes
     independently, so short rows ("exec") collapse their column tight while
     wider rows ("filesystem") get wider — the columns visually drift between
     tables. Fixed layout lets us pin the first column to a consistent width
     across every table in this bubble. */
  table-layout: fixed;
  border-collapse: collapse;
  margin: 0.5em 0;
  width: 100%;
  font-size: 0.95em;
}

.prose-chat th, .prose-chat td {
  padding: 0.4em 0.75em;
  text-align: left;
  vertical-align: top;
}

/* First column (identifier — tool name, skill name, etc.) pinned to a common
   width across every prose-chat table. Tool/skill names are snake_case and
   ≤ ~20 chars by convention, so 14em comfortably fits the longest observed
   name ("restaurant-recommender") without wrapping while keeping consistent
   horizontal rhythm across categories. nowrap guards against mid-word wraps
   if a future identifier pushes past the budget. */
.prose-chat th:first-child, .prose-chat td:first-child {
  width: 14em;
  white-space: nowrap;
}

/* Light-mode palette (default) */
.prose-chat strong { color: #171717; font-weight: 600; }
.prose-chat em { color: #404040; }
.prose-chat h1, .prose-chat h2, .prose-chat h3 { color: #171717; }
.prose-chat code { background: rgb(0,0,0,6%); color: #171717; }
.prose-chat pre { background: rgb(0,0,0,4%); border: 1px solid rgb(0,0,0,8%); }
.prose-chat a { color: #525252; }

.prose-chat a.workspace-file {
  background: rgb(16, 185, 129, 8%);
  border: 1px solid rgb(16, 185, 129, 35%);
  color: #047857;
}

.prose-chat a.workspace-file:hover {
  background: rgb(16, 185, 129, 18%);
  border-color: rgb(16, 185, 129, 60%);
}
.prose-chat blockquote { border-left: 2px solid rgb(0,0,0,12%); color: #525252; }
.prose-chat hr { border-top: 1px solid rgb(0,0,0,10%); }
.prose-chat img { border: 1px solid rgb(0,0,0,8%); }
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
