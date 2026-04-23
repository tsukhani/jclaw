<script setup lang="ts">
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
let titleRefreshTimeout: ReturnType<typeof setTimeout> | null = null
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
  if (titleRefreshTimeout) clearTimeout(titleRefreshTimeout)
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
  // Generate a title for the conversation we're leaving (if it still has a truncated preview)
  if (selectedConvoId.value && selectedConvoId.value !== id) {
    generateTitleForConversation(selectedConvoId.value)
  }
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
    setTimeout(() => {
      refreshConversations()
    }, 600)
  }
}

async function generateTitleForConversation(convoId: number) {
  try {
    await $fetch(`/api/conversations/${convoId}/generate-title`, { method: 'POST' })
    // Refresh after a delay to pick up the async-generated title
    if (titleRefreshTimeout) clearTimeout(titleRefreshTimeout)
    titleRefreshTimeout = setTimeout(() => {
      refreshConversations()
    }, 3000)
  }
  catch {
    // Best-effort — ignore failures
  }
}

function newChat() {
  // Generate a title for the conversation we're leaving
  if (selectedConvoId.value) {
    generateTitleForConversation(selectedConvoId.value)
  }
  selectedConvoId.value = null
  messages.value = []
}

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
  <div
    class="flex -m-6"
    style="height: calc(100vh - 3rem);"
  >
    <!--
      Chat area takes the full main-content width now that the in-page
      Conversations sidebar has been removed. Multi-select delete + bulk
      management live on the dedicated /conversations page.
    -->
    <div class="flex-1 flex flex-col">
      <!--
        Chat header — Unsloth-style: searchable model combobox on the left,
        compact agent selector + streaming status mid-row, context meter on
        the right. The per-turn Thinking level select moved out of the
        header; the Think pill in the composer footer owns on/off, and the
        level is controlled from the agent detail page.
      -->
      <!--
        Three-zone header: model combobox on the left, agent selector
        absolute-centered on the row midpoint, context meter pushed to the
        right edge. Absolute positioning on the agent label keeps it
        optically centered regardless of the left/right content widths.
      -->
      <div class="relative px-3 py-2 border-b border-border flex items-center gap-2">
        <ChatModelCombobox
          :providers="providers"
          :model-key="selectedModelKey"
          :status-tone="streaming ? 'busy' : (selectedAgent?.providerConfigured === false ? 'offline' : 'ok')"
          @update:model-key="onModelKeyChange"
        />
        <label
          v-if="(agents?.length ?? 0) > 1"
          :for="agentSelectId"
          class="absolute left-1/2 -translate-x-1/2 text-sm text-fg-muted flex items-center gap-1.5"
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
        <span class="ml-auto flex items-center gap-2">
          <ChatContextMeter
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

      <!-- Messages -->
      <div
        ref="messagesEl"
        class="flex-1 overflow-y-auto overflow-x-hidden px-4 py-6"
      >
        <!--
          Unsloth-style centered content column: the scroll container
          stays full-width so the scrollbar tracks the window edge, but
          each turn sits inside a max-w-3xl rail that keeps long lines
          readable regardless of viewport width.
        -->
        <div class="mx-auto w-full max-w-3xl space-y-5">
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
                      <svg
                        v-if="copiedMessageId !== (msg.id ?? msg._key)"
                        class="w-4 h-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      ><path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                      /></svg>
                      <svg
                        v-else
                        class="w-4 h-4 text-emerald-700 dark:text-emerald-400"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      ><path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2.5"
                        d="M5 13l4 4L19 7"
                      /></svg>
                    </button>
                    <button
                      type="button"
                      :disabled="streaming"
                      class="p-1 text-fg-muted hover:text-fg-primary disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
                      title="Edit & resubmit"
                      @click="editUserMessage(msg)"
                    >
                      <svg
                        class="w-4 h-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      ><path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z"
                      /></svg>
                    </button>
                    <button
                      type="button"
                      :disabled="streaming"
                      class="p-1 text-fg-muted hover:text-red-600 dark:hover:text-red-400 disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
                      title="Delete message"
                      @click="deleteMessage(msg)"
                    >
                      <svg
                        class="w-4 h-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      ><path
                        stroke-linecap="round"
                        stroke-linejoin="round"
                        stroke-width="2"
                        d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                      /></svg>
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
                        <svg
                          class="w-3.5 h-3.5 shrink-0"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="1.5"
                          d="M9 18h6M10 22h4M12 2a7 7 0 00-4 12.74V17a1 1 0 001 1h6a1 1 0 001-1v-2.26A7 7 0 0012 2z"
                        /></svg>
                        <span class="font-medium">{{ thinkingHeaderLabel(msg) }}</span>
                        <svg
                          class="w-3.5 h-3.5 transition-transform ml-1"
                          :class="msg.thinkingCollapsed ? '' : 'rotate-180'"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="1.5"
                          d="M19 9l-7 7-7-7"
                        /></svg>
                      </button>
                      <button
                        type="button"
                        class="shrink-0 inline-flex items-center gap-1 px-2 py-1 text-[11px] text-fg-muted hover:text-fg-strong transition-colors"
                        :title="copiedMessageId === `reason:${msg.id ?? msg._key}` ? 'Copied' : 'Copy reasoning'"
                        @click.stop="copyReasoning(msg)"
                      >
                        <svg
                          v-if="copiedMessageId !== `reason:${msg.id ?? msg._key}`"
                          class="w-3.5 h-3.5"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="1.5"
                          d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                        /></svg>
                        <svg
                          v-else
                          class="w-3.5 h-3.5 text-emerald-500"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2.5"
                          d="M5 13l4 4L19 7"
                        /></svg>
                        <span>Copy</span>
                      </button>
                    </div>
                    <div
                      v-if="!msg.thinkingCollapsed"
                      data-reasoning-body
                      class="px-4 pb-3 pt-1 text-base text-fg-primary whitespace-pre-wrap break-words h-80 overflow-y-auto border-t border-neutral-200 dark:border-neutral-700"
                    >
                      {{ msg.reasoning }}
                    </div>
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
                Usage metrics + hover actions share a single right-aligned
                row so the copy/delete icons live on the same visual baseline
                as the token pills. ml-auto on the action group pushes it to
                the far right of the flex row regardless of how many stat
                pills are visible; the min-width ensures the bubble widens
                far enough that the full worst-case set (prompt + cached +
                reasoning + completion + separator + tok/s + duration + cost
                + copy + delete) fits with breathing room. flex-wrap is
                dropped — wrapping the action icons to their own line was
                the previous layout we're explicitly moving away from.
              -->
                  <div
                    v-if="msg.usage || msg.id"
                    class="flex items-center gap-2 mt-1.5 px-1 min-w-[500px]"
                  >
                    <template v-if="msg.usage">
                      <span
                        class="inline-flex items-center gap-1 text-xs text-fg-muted"
                        :title="`${msg.usage.prompt.toLocaleString()} input tokens`"
                      >
                        <svg
                          class="w-3 h-3 text-fg-muted"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M7 11l5-5m0 0l5 5m-5-5v12"
                        /></svg>
                        {{ msg.usage.prompt.toLocaleString() }}
                      </span>
                      <span
                        v-if="msg.usage.cached"
                        class="inline-flex items-center gap-1 text-xs text-amber-700 dark:text-amber-400/70"
                        :title="`${msg.usage.cached.toLocaleString()} of ${msg.usage.prompt.toLocaleString()} input tokens served from prompt cache`"
                      >
                        <svg
                          class="w-3 h-3"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M13 10V3L4 14h7v7l9-11h-7z"
                        /></svg>
                        {{ msg.usage.cached.toLocaleString() }}
                      </span>
                      <span
                        v-if="msg.usage.reasoning"
                        class="inline-flex items-center gap-1 text-xs text-blue-700/80 dark:text-blue-400/70"
                        :title="`${msg.usage.reasoning.toLocaleString()} reasoning tokens`"
                      >
                        <svg
                          class="w-3 h-3"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z"
                        /></svg>
                        {{ msg.usage.reasoning.toLocaleString() }}
                      </span>
                      <span
                        class="inline-flex items-center gap-1 text-xs text-fg-muted"
                        :title="`${msg.usage.completion.toLocaleString()} output tokens`"
                      >
                        <svg
                          class="w-3 h-3 text-fg-muted"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M17 13l-5 5m0 0l-5-5m5 5V6"
                        /></svg>
                        {{ msg.usage.completion.toLocaleString() }}
                      </span>
                      <span class="text-border text-xs">|</span>
                      <span
                        v-if="formatTokensPerSec(msg.usage)"
                        class="inline-flex items-center gap-1 text-xs text-fg-muted"
                        title="Output tokens per second"
                      >
                        <svg
                          class="w-3 h-3"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M13 10V3L4 14h7v7l9-11h-7z"
                        /></svg>
                        {{ formatTokensPerSec(msg.usage) }}
                      </span>
                      <span
                        v-if="msg.usage.durationMs"
                        class="inline-flex items-center gap-1 text-xs text-fg-muted"
                        title="Total response time"
                      >
                        <svg
                          class="w-3 h-3"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M12 8v4l3 3m6-3a9 9 0 11-18 0 9 9 0 0118 0z"
                        /></svg>
                        {{ (msg.usage.durationMs / 1000).toFixed(1) }}s
                      </span>
                      <span
                        v-if="formatUsageCost(msg.usage)"
                        class="inline-flex items-center gap-1 text-xs text-amber-500/80 font-medium"
                        :title="formatUsageCostTooltip(msg.usage)"
                      >
                        <svg
                          class="w-3 h-3"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M12 8c-1.657 0-3 .895-3 2s1.343 2 3 2 3 .895 3 2-1.343 2-3 2m0-8c1.11 0 2.08.402 2.599 1M12 8V7m0 1v8m0 0v1m0-1c-1.11 0-2.08-.402-2.599-1M21 12a9 9 0 11-18 0 9 9 0 0118 0z"
                        /></svg>
                        {{ formatUsageCost(msg.usage) }}
                      </span>
                    </template>
                    <!--
                  Assistant hover actions: copy the raw markdown to clipboard,
                  or delete the message server-side. Hidden until hover to
                  keep the row calm; ml-auto anchors them to the right edge
                  regardless of how much stat content sits on the left. Only
                  rendered on persisted messages (msg.id) — mid-stream
                  placeholders have no server row to delete yet.
                -->
                    <div
                      v-if="msg.id"
                      class="ml-auto flex items-center gap-1 opacity-0 group-hover:opacity-100 transition-opacity"
                    >
                      <button
                        type="button"
                        class="p-1 text-fg-muted hover:text-fg-primary transition-colors"
                        :title="copiedMessageId === (msg.id ?? msg._key) ? 'Copied' : 'Copy to clipboard'"
                        @click="copyMessage(msg)"
                      >
                        <svg
                          v-if="copiedMessageId !== (msg.id ?? msg._key)"
                          class="w-4 h-4"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z"
                        /></svg>
                        <svg
                          v-else
                          class="w-4 h-4 text-emerald-700 dark:text-emerald-400"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2.5"
                          d="M5 13l4 4L19 7"
                        /></svg>
                      </button>
                      <button
                        type="button"
                        :disabled="streaming"
                        class="p-1 text-fg-muted hover:text-red-600 dark:hover:text-red-400 disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
                        title="Delete message"
                        @click="deleteMessage(msg)"
                      >
                        <svg
                          class="w-4 h-4"
                          fill="none"
                          stroke="currentColor"
                          viewBox="0 0 24 24"
                        ><path
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          stroke-width="2"
                          d="M19 7l-.867 12.142A2 2 0 0116.138 21H7.862a2 2 0 01-1.995-1.858L5 7m5 4v6m4-6v6m1-10V4a1 1 0 00-1-1h-4a1 1 0 00-1 1v3M4 7h16"
                        /></svg>
                      </button>
                    </div>
                  </div>
                </div>
              </div>
            </div>
          </template>
          <!--
          Pre-first-byte placeholder. Visible only during the gap between "user
          sent the request" and "the first stream event (reasoning OR content)
          arrived." Once either signal lands, displayMessages starts rendering
          the real bubble (with live reasoning and/or content) and this gray
          placeholder yields. Without the streamReasoning guard, JCLAW-75
          regression: reasoning-mode turns show this pill for the entire
          thinking phase.
        -->
          <div
            v-if="streaming && !streamContent && !streamReasoning"
            class="flex justify-start"
          >
            <div class="max-w-[85%]">
              <div class="flex items-baseline gap-2 mb-1">
                <span class="text-xs font-medium text-emerald-700 dark:text-emerald-400">assistant</span>
              </div>
              <div class="bg-muted border border-input rounded-2xl rounded-tl-sm px-4 py-2.5 text-base text-fg-muted">
                <span class="animate-pulse">{{ streamStatus || 'Thinking...' }}</span>
              </div>
            </div>
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
          Composer card — traced from Unsloth Studio via devtools:
          rounded-[22px] (their rounded-3xl), 1px border in #2e3035
          (neutral-700 at 50% opacity against the card bg), and a soft
          0 2px 12px rgba(0,0,0,0.2) drop shadow. The border override is
          needed because the project's --border token is invisible
          against --surface-elevated in dark mode.
        -->
        <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
        <form
          class="bg-surface-elevated border border-neutral-200 dark:border-neutral-700/50 rounded-[22px]
                 shadow-[0_2px_12px_rgba(0,0,0,0.2)] overflow-hidden"
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
              <svg
                v-else
                class="w-3 h-3 text-fg-muted shrink-0"
                fill="none"
                stroke="currentColor"
                viewBox="0 0 24 24"
              >
                <path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2"
                  d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13"
                />
              </svg>
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
                <svg
                  class="w-3 h-3"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2.5"
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
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
                <svg
                  class="w-3 h-3"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                >
                  <path
                    stroke-linecap="round"
                    stroke-linejoin="round"
                    stroke-width="2.5"
                    d="M6 18L18 6M6 6l12 12"
                  />
                </svg>
              </button>
            </span>
          </div>
          <textarea
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
            ref="fileInput"
            type="file"
            multiple
            aria-label="Upload files"
            class="hidden"
            @change="handleFileUpload"
          >
          <div class="flex items-center justify-between gap-2 px-2 pb-2">
            <div class="flex items-center gap-1.5 flex-wrap">
              <button
                type="button"
                class="inline-flex items-center justify-center w-8 h-8 rounded-full border border-border text-fg-muted hover:text-fg-strong hover:bg-muted transition-colors"
                title="Attach file"
                @click="triggerFileUpload"
              >
                <!--
                  Lucide-style paperclip — parity with Unsloth's attach
                  affordance. The rounded border + bg-muted hover keep the
                  circle treatment used for the send button on the right.
                -->
                <svg
                  class="w-4 h-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="1.8"
                  d="M21.44 11.05l-9.19 9.19a6 6 0 01-8.49-8.49l9.19-9.19a4 4 0 015.66 5.66l-9.2 9.19a2 2 0 01-2.83-2.83l8.49-8.48"
                /></svg>
              </button>
              <!--
                Model-capability toggles. Unsloth-style rounded-full pills:
                when active the pill paints in the capability colour; inactive
                pills stay outlined neutral. Visible only when the model
                advertises the capability. Think flips thinkingMode null ↔
                remembered level; Vision/Audio flip their *Enabled override.
              -->
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
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="1.5"
                  d="M9 18h6M10 22h4M12 2a7 7 0 00-4 12.74V17a1 1 0 001 1h6a1 1 0 001-1v-2.26A7 7 0 0012 2z"
                /></svg>
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
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="1.5"
                  d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                /><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="1.5"
                  d="M2.458 12C3.732 7.943 7.523 5 12 5c4.478 0 8.268 2.943 9.542 7-1.274 4.057-5.064 7-9.542 7-4.477 0-8.268-2.943-9.542-7z"
                /></svg>
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
                <svg
                  class="w-3.5 h-3.5"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="1.5"
                  d="M11 5L6 9H2v6h4l5 4V5zm8.536-.536a9 9 0 010 12.728M15.536 8.464a5 5 0 010 7.072"
                /></svg>
                Audio
              </button>
            </div>
            <div class="flex items-center gap-1">
              <button
                type="button"
                class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
                title="New conversation"
                @click="newChat"
              >
                <!--
                  Square-with-pencil "edit/compose" glyph (Lucide square-pen)
                  — same semantic as Unsloth's "new chat" button. Reads as
                  "start a new conversation" without needing the tooltip.
                -->
                <svg
                  class="w-4 h-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="1.5"
                  d="M12 3H5a2 2 0 00-2 2v14a2 2 0 002 2h14a2 2 0 002-2v-7M18.5 2.5a2.121 2.121 0 013 3L12 15l-4 1 1-4 9.5-9.5z"
                /></svg>
              </button>
              <button
                type="button"
                :disabled="!displayMessages.length"
                class="p-1.5 text-fg-muted hover:text-fg-strong disabled:text-neutral-300 dark:disabled:text-neutral-700 transition-colors"
                title="Export as Markdown"
                @click="exportConversation"
              >
                <svg
                  class="w-4 h-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="1.5"
                  d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4"
                /></svg>
              </button>
              <button
                v-if="streaming"
                type="button"
                class="inline-flex items-center justify-center w-8 h-8 rounded-full bg-red-500 text-white hover:bg-red-600 transition-colors"
                title="Stop generating"
                @click="stopStreaming"
              >
                <svg
                  class="w-3.5 h-3.5"
                  fill="currentColor"
                  viewBox="0 0 24 24"
                ><rect
                  x="6"
                  y="6"
                  width="12"
                  height="12"
                  rx="1.5"
                /></svg>
              </button>
              <button
                v-else
                type="submit"
                :disabled="!input.trim() && !attachedFiles.length"
                class="inline-flex items-center justify-center w-8 h-8 rounded-full bg-emerald-500 text-white hover:bg-emerald-400 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
                title="Send"
              >
                <svg
                  class="w-4 h-4"
                  fill="none"
                  stroke="currentColor"
                  viewBox="0 0 24 24"
                ><path
                  stroke-linecap="round"
                  stroke-linejoin="round"
                  stroke-width="2.5"
                  d="M12 19V5m0 0l-7 7m7-7l7 7"
                /></svg>
              </button>
            </div>
          </div>
        </form>
        <p class="mt-1.5 text-center text-[11px] text-fg-muted">
          LLMs can make mistakes. Double-check all responses.
        </p>
      </div>
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
