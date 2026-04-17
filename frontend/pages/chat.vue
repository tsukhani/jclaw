<script setup lang="ts">
import { marked } from 'marked'
import DOMPurify from 'dompurify'
import { formatUsageCost, formatUsageCostTooltip, type MessageUsage } from '~/utils/usage-cost'
import { formatSize } from '~/utils/format'
import { thinkingHeaderLabel, initCollapsedState } from '~/utils/thinking'

// Configure marked for safe rendering
marked.setOptions({
  breaks: true,
  gfm: true
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
    ADD_ATTR: ['src', 'controls', 'autoplay', 'download', 'target']
  })
  const result = agentId != null ? rewriteWorkspaceLinks(sanitized, agentId) : sanitized

  // Only cache if under limit (prevents unbounded growth during long sessions)
  if (markdownCache.size < MARKDOWN_CACHE_MAX) {
    markdownCache.set(cacheKey, result)
  }
  return result
}

/**
 * Rewrite relative anchor hrefs (e.g. "summary.docx" or "uploads/123/report.pdf")
 * to the workspace file endpoint and mark them as downloads. Absolute URLs,
 * anchors, and existing /api/ links are left untouched.
 */
function rewriteWorkspaceLinks(html: string, agentId: number): string {
  if (typeof DOMParser === 'undefined') return html
  const parser = new DOMParser()
  const doc = parser.parseFromString(`<div id="__root">${html}</div>`, 'text/html')
  const root = doc.getElementById('__root')
  if (!root) return html
  root.querySelectorAll('a[href]').forEach(a => {
    const href = a.getAttribute('href') || ''
    if (!href) return
    if (href.startsWith('/') || href.startsWith('#')) return
    // External links open in a new tab
    if (/^https?:/i.test(href)) {
      a.setAttribute('target', '_blank')
      a.setAttribute('rel', 'noopener noreferrer')
      return
    }
    if (/^(mailto|tel|ftp|data|javascript):/i.test(href)) return
    // Decode first: marked.parse already URL-encodes the href (spaces → %20),
    // so a raw encodeURIComponent would double-encode (%20 → %2520), producing
    // a URL that 404s because the filename on disk has real spaces, not "%20".
    const encoded = href.split('/').filter(Boolean).map(s => encodeURIComponent(decodeURIComponent(s))).join('/')
    a.setAttribute('href', `/api/agents/${agentId}/files/${encoded}`)
    a.setAttribute('download', '')
    a.classList.add('workspace-file')
  })
  return root.innerHTML
}

function formatTimestamp(iso: string): string {
  if (!iso) return ''
  const date = new Date(iso)
  if (isNaN(date.getTime())) return ''
  return date.toLocaleString(undefined, {
    month: 'long', day: 'numeric', year: 'numeric',
    hour: 'numeric', minute: '2-digit', second: '2-digit',
    timeZoneName: 'short'
  })
}

import type { Agent, Conversation, Message, ConfigResponse } from '~/types/api'
import { effectiveThinkingLevels } from '~/composables/useProviders'

const { data: agents, refresh: refreshAgents } = await useFetch<Agent[]>('/api/agents')
const { data: configData } = await useFetch<ConfigResponse>('/api/config')

const selectedAgentId = ref<number | null>(null)

// Extract configured providers and their models from config
const { providers } = useProviders(configData)

// The currently selected agent object
const selectedAgent = computed(() => agents.value?.find((a: any) => a.id === selectedAgentId.value))

// Current model info for the selected agent. Looks up across ALL configured
// providers — not just the agent's own provider — because the chat dropdown
// lets the user pick any model from any enabled provider without going back
// to the Agents page. Encoded provider + id avoids ambiguity when two
// providers happen to expose the same model id (e.g. "kimi-k2.5").
const selectedModelInfo = computed(() => {
  const providerName = selectedAgent.value?.modelProvider
  const modelId = selectedAgent.value?.modelId
  if (!providerName || !modelId) return null
  const provider = providers.value.find(p => p.name === providerName)
  return provider?.models.find((m: any) => m.id === modelId) ?? null
})

// Compound key used as the <option> value so the change handler can read
// both provider and model from a single DOM value. Must use a separator that
// can't appear in either side; "::" is safe against every provider name and
// model id we currently ship.
const selectedModelKey = computed(() => {
  const p = selectedAgent.value?.modelProvider
  const m = selectedAgent.value?.modelId
  return p && m ? `${p}::${m}` : ''
})

// Whether the selected model supports thinking
const thinkingSupported = computed(() => selectedModelInfo.value?.supportsThinking === true)

// Thinking levels advertised by the currently selected model. Empty for
// non-thinking models — the toolbar hides the selector in that case.
const thinkingLevels = computed<string[]>(() => effectiveThinkingLevels(selectedModelInfo.value as any))

// Sync model or thinking mode change back to the agent
async function updateAgentSetting(updates: Record<string, any>) {
  if (!selectedAgentId.value) return
  try {
    await $fetch(`/api/agents/${selectedAgentId.value}`, { method: 'PUT', body: updates })
    refreshAgents()
  } catch { /* ignore */ }
}

function onModelChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  const sepIdx = value.indexOf('::')
  if (sepIdx < 0) return
  const modelProvider = value.slice(0, sepIdx)
  const modelId = value.slice(sepIdx + 2)
  const provider = providers.value.find(p => p.name === modelProvider)
  const model = provider?.models.find((m: any) => m.id === modelId)
  // Send both fields so a cross-provider pick (e.g. ollama-cloud → openrouter)
  // lands atomically; sending only modelId would leave the agent pointing a
  // stale modelProvider at a model that doesn't exist there.
  const updates: Record<string, any> = { modelProvider, modelId }
  // If the new model doesn't advertise the current thinking level, clear it in
  // the same PUT so the backend doesn't have to normalize the mismatch. The
  // backend also collapses unknown levels to null defensively, but sending the
  // cleared value keeps the optimistic UI and the persisted state aligned.
  const nextLevels = effectiveThinkingLevels(model as any)
  const current = selectedAgent.value?.thinkingMode
  if (current && !nextLevels.includes(current)) {
    updates.thinkingMode = null
  }
  updateAgentSetting(updates)
}

function onThinkingModeChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  // Empty string is "off" — send null so the backend clears the column.
  // This setting only applies to future turns; existing rendered thinking
  // bubbles are per-message and unaffected (each bubble carries its own
  // collapse state).
  updateAgentSetting({ thinkingMode: value || null })
}

// Per-bubble collapse toggle handler. Header label + default-collapse rules
// live in ~/utils/thinking.ts (thinkingHeaderLabel, initCollapsedState) so
// they are unit-testable without mounting the page.
function toggleThinking(msg: any) {
  msg.thinkingCollapsed = !msg.thinkingCollapsed
}


const conversationsUrl = computed(() =>
  selectedAgentId.value
    ? `/api/conversations?channel=web&agentId=${selectedAgentId.value}&limit=50`
    : null
)
const { data: conversations, refresh: refreshConversations } = await useFetch<Conversation[]>(conversationsUrl)
const selectedConvoId = ref<number | null>(null)
const messages = ref<Message[]>([])
const input = ref('')
const streaming = ref(false)
const streamStatus = ref('')
const chatInput = ref<HTMLTextAreaElement | null>(null)

function autoResize() {
  const el = chatInput.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

// Per-message "just copied" flash so the user gets visual feedback without a toast.
const copiedMessageId = ref<string | number | null>(null)
async function copyUserMessage(msg: any) {
  try {
    await navigator.clipboard.writeText(msg.content ?? '')
    copiedMessageId.value = msg.id ?? msg._key ?? null
    setTimeout(() => {
      if (copiedMessageId.value === (msg.id ?? msg._key ?? null)) copiedMessageId.value = null
    }, 1200)
  } catch (e) {
    console.error('Failed to copy message:', e)
  }
}
async function editUserMessage(msg: any) {
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
const sidebarWidth = ref(224) // 14rem = 224px (matches w-56)
const isResizing = ref(false)
let cleanupResize: (() => void) | null = null

function startResize(e: MouseEvent) {
  isResizing.value = true
  const startX = e.clientX
  const startWidth = sidebarWidth.value

  function onMove(e: MouseEvent) {
    const newWidth = startWidth + (e.clientX - startX)
    sidebarWidth.value = Math.max(120, Math.min(newWidth, 600))
  }
  function onUp() {
    isResizing.value = false
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
    cleanupResize = null
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
  cleanupResize = () => { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp) }
}

const selectMode = ref(false)
const selectedIds = ref<Set<number>>(new Set())

function toggleSelect(id: number) {
  const s = new Set(selectedIds.value)
  if (s.has(id)) s.delete(id); else s.add(id)
  selectedIds.value = s
}

function selectAll() {
  if (!conversations.value) return
  if (selectedIds.value.size === conversations.value.length) {
    selectedIds.value = new Set()
  } else {
    selectedIds.value = new Set(conversations.value.map((c: any) => c.id))
  }
}

async function deleteSelected() {
  if (selectedIds.value.size === 0) return
  const ids = [...selectedIds.value]
  try {
    await $fetch('/api/conversations', { method: 'DELETE', body: { ids } })
    if (selectedConvoId.value && selectedIds.value.has(selectedConvoId.value)) {
      selectedConvoId.value = null
      messages.value = []
    }
    selectedIds.value = new Set()
    selectMode.value = false
    refreshConversations()
  } catch { /* ignore */ }
}

function exitSelectMode() {
  selectMode.value = false
  selectedIds.value = new Set()
}

let scrollRaf: number | null = null
let titleRefreshTimeout: ReturnType<typeof setTimeout> | null = null
function scrollToBottom() {
  if (scrollRaf) return
  scrollRaf = requestAnimationFrame(() => {
    if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    scrollRaf = null
  })
}

onUnmounted(() => {
  abortController.value?.abort()
  cleanupResize?.()
  if (scrollRaf) cancelAnimationFrame(scrollRaf)
  if (titleRefreshTimeout) clearTimeout(titleRefreshTimeout)
})

function stopStreaming() {
  if (!streaming.value) return
  abortController.value?.abort()
  streaming.value = false
  streamStatus.value = ''

}

// Filter out tool messages and empty assistant messages (tool call records) from display.
// The predicate lives in ~/utils/display-message-filter for unit-testability; see
// JCLAW-75 for the specific reasoning-stream regression the reasoning-aware
// suppression rule closes.
import { shouldDisplayMessage } from '~/utils/display-message-filter'
const displayMessages = computed(() =>
  messages.value.filter(m => shouldDisplayMessage(m as any, streaming.value))
)

// Deep-link: if ?conversation=ID is present, load that conversation and switch
// to its agent on mount.
const route = useRoute()
const deepLinkConvoId = route.query.conversation ? Number(route.query.conversation) : null
const initializing = ref(true) // suppresses agent-change clear during setup

// Auto-select agent on load
watch(agents, (val) => {
  if (!val?.length || selectedAgentId.value) return
  const def = val.find((a: any) => a.isMain) || val[0]
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
    const found = convos.find((c: any) => c.id === deepLinkConvoId)
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
        const convo = allConvos?.find((c: any) => c.id === deepLinkConvoId)
        if (convo) {
          const agent = agents.value.find((a: any) => a.name === convo.agentName)
          if (agent && agent.id !== selectedAgentId.value) {
            // Switch agent — this triggers conversationsUrl to change, which
            // triggers useFetch to refetch, which triggers this watcher again
            // with the correct agent's conversation list.
            selectedAgentId.value = agent.id
            return  // wait for next watcher fire with new conversations
          }
        }
      } catch { /* fall through */ }
      // Couldn't find the conversation — give up and finish init
      initializing.value = false
      stopDeepLink()
    }
  }, { immediate: true })

  // Safety: don't leave the watcher running forever
  onUnmounted(() => stopDeepLink())
} else {
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

async function sendMessage() {
  if (streaming.value || !selectedAgentId.value) return
  const rawText = input.value.trim()
  if (!rawText && !attachedFiles.value.length) return

  attachError.value = null
  const pending = attachedFiles.value.slice()
  let uploadedPaths: string[] = []
  if (pending.length) {
    try {
      uploadedPaths = await uploadAttachments(selectedAgentId.value)
    } catch (e: any) {
      attachError.value = 'Upload failed: ' + (e?.data?.error || e?.message || 'unknown error')
      return
    }
  }

  let text = rawText
  if (uploadedPaths.length) {
    const block = '[Attached files in workspace:\n'
      + uploadedPaths.map(p => `- ${p}`).join('\n')
      + ']'
    text = rawText ? `${block}\n\n${rawText}` : block
  }

  input.value = ''
  attachedFiles.value = []
  if (chatInput.value) chatInput.value.style.height = 'auto'
  messages.value.push({ _key: crypto.randomUUID(), role: 'user', content: text, createdAt: new Date().toISOString() })
  scrollToBottom()

  streaming.value = true
  streamContent.value = ''
  streamReasoning.value = ''
  streamStatus.value = ''

  // Add placeholder for streaming response
  const assistantIdx = messages.value.length
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
        message: text
      })
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
            selectedConvoId.value = event.conversationId
            if (event.thinkingMode) {
              streamStatus.value = `thinking (${event.thinkingMode})...`
            }
          } else if (event.type === 'status') {
            // Check if this is a usage JSON payload
            if (event.content?.startsWith('{') && event.content.includes('"usage"')) {
              try {
                const parsed = JSON.parse(event.content)
                if (parsed.usage) messages.value[assistantIdx].usage = parsed.usage
              } catch { /* not JSON, treat as status text */ }
            } else {
              streamStatus.value = event.content
            }
          } else if (event.type === 'reasoning') {
            const m = messages.value[assistantIdx] as any
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
          } else if (event.type === 'token') {
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
            const m = messages.value[assistantIdx] as any
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
          } else if (event.type === 'complete') {
            const m = messages.value[assistantIdx] as any
            // Reasoning-only turn (no content streamed): finalize duration here.
            if (m._thinkingInProgress) {
              m._thinkingDurationMs = Date.now() - (m._thinkingStartedAt ?? Date.now())
              m._thinkingInProgress = false
              m.thinkingCollapsed = true
            }
            streamStatus.value = ''
            m.content = event.content || streamContent.value
          } else if (event.type === 'error') {
            messages.value[assistantIdx].content = event.content
          } else if (event.type === 'queued') {
            messages.value[assistantIdx].content = 'Your message has been queued (position: ' + (event.position || '?') + '). Processing shortly...'
          }
        } catch {
          // Skip malformed events
        }
      }
    }
  } catch (e: any) {
    // AbortError is expected when the user clicks Stop — preserve any content
    // that was already streamed and append a small "(stopped)" marker instead
    // of replacing the bubble with a scary error.
    if (e?.name === 'AbortError') {
      const existing = messages.value[assistantIdx].content || streamContent.value || ''
      messages.value[assistantIdx].content = existing
        ? existing.replace(/\s*$/, '') + '\n\n_(stopped)_'
        : '_(stopped before any response)_'
    } else {
      messages.value[assistantIdx].content = 'Error: ' + (e.message || 'Failed to get response')
    }
  } finally {
    streaming.value = false
    triggerRef(messages) // re-render with final content + markdown
    // Check if agent is still processing queued messages
    if (selectedConvoId.value) {
      try {
        const status = await $fetch<any>(`/api/conversations/${selectedConvoId.value}/queue`)
        agentBusy.value = status.busy
      } catch { agentBusy.value = false }
    }
    refreshConversations()
  }
}

async function generateTitleForConversation(convoId: number) {
  try {
    await $fetch(`/api/conversations/${convoId}/generate-title`, { method: 'POST' })
    // Refresh after a delay to pick up the async-generated title
    if (titleRefreshTimeout) clearTimeout(titleRefreshTimeout)
    titleRefreshTimeout = setTimeout(() => refreshConversations(), 3000)
  } catch {
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
const MAX_ATTACHMENT_BYTES = 10 * 1024 * 1024

function triggerFileUpload() {
  fileInput.value?.click()
}

function handleFileUpload(event: Event) {
  const target = event.target as HTMLInputElement
  const picked = target.files ? Array.from(target.files) : []
  addAttachments(picked)
  target.value = ''
}

function addAttachments(files: File[]) {
  attachError.value = null
  for (const f of files) {
    if (attachedFiles.value.length >= MAX_ATTACHMENTS) {
      attachError.value = `Maximum ${MAX_ATTACHMENTS} files per message`
      break
    }
    if (f.size > MAX_ATTACHMENT_BYTES) {
      attachError.value = `${f.name} exceeds ${MAX_ATTACHMENT_BYTES / (1024 * 1024)} MB`
      continue
    }
    attachedFiles.value.push(f)
  }
}

function removeAttachment(idx: number) {
  attachedFiles.value.splice(idx, 1)
}

const formatAttachmentSize = formatSize

async function uploadAttachments(agentId: number): Promise<string[]> {
  if (!attachedFiles.value.length) return []
  const form = new FormData()
  form.append('agentId', String(agentId))
  for (const f of attachedFiles.value) {
    form.append('files', f, f.name)
  }
  const res = await $fetch<{ batchId: string; files: Array<{ path: string; name: string; size: number }> }>(
    '/api/chat/upload',
    { method: 'POST', body: form }
  )
  return res.files.map(f => f.path)
}

function exportConversation() {
  if (!displayMessages.value.length) return
  const convo = conversations.value?.find((c: any) => c.id === selectedConvoId.value)
  const title = convo?.preview || 'conversation'
  const lines: string[] = [`# ${title}\n`]
  for (const msg of displayMessages.value) {
    if (msg.role === 'user') {
      lines.push(`## User\n\n${msg.content}\n`)
    } else if (msg.role === 'assistant') {
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
  <div class="flex -m-6" style="height: calc(100vh - 3rem);" :class="{ 'select-none': isResizing }">
    <!-- Conversation sidebar -->
    <div :style="{ width: sidebarWidth + 'px' }" class="shrink-0 border-r border-border flex flex-col overflow-hidden">
      <div class="p-3 border-b border-border flex items-center justify-between">
        <template v-if="selectMode">
          <button @click="selectAll" class="text-xs text-fg-muted hover:text-fg-strong transition-colors">
            {{ selectedIds.size === (conversations?.length || 0) ? 'None' : 'All' }}
          </button>
          <div class="flex items-center gap-2">
            <button
              @click="deleteSelected"
              :disabled="selectedIds.size === 0"
              class="text-xs text-red-400 hover:text-red-300 disabled:text-fg-muted transition-colors"
            >Delete ({{ selectedIds.size }})</button>
            <button @click="exitSelectMode" class="text-xs text-fg-muted hover:text-fg-strong transition-colors">Done</button>
          </div>
        </template>
        <template v-else>
          <span class="text-xs font-medium text-fg-muted">Conversations</span>
          <button
            v-if="conversations?.length"
            @click="selectMode = true"
            class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
            title="Edit conversations"
          >
            <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.232 5.232l3.536 3.536m-2.036-5.036a2.5 2.5 0 113.536 3.536L6.5 21.036H3v-3.572L16.732 3.732z" /></svg>
          </button>
        </template>
      </div>
      <div class="flex-1 overflow-y-auto">
        <div
          v-for="convo in conversations"
          :key="convo.id"
          @click="selectMode ? toggleSelect(convo.id) : loadConversation(convo.id)"
          :class="[
            selectMode && selectedIds.has(convo.id) ? 'bg-muted text-fg-strong' :
            selectedConvoId === convo.id ? 'bg-muted text-fg-strong' : 'text-fg-muted'
          ]"
          class="w-full text-left px-3 py-2 text-xs hover:bg-muted transition-colors truncate flex items-center gap-2 cursor-pointer"
        >
          <span v-if="selectMode" class="shrink-0 w-3.5 h-3.5 border border-input flex items-center justify-center text-[10px]"
                :class="selectedIds.has(convo.id) ? 'bg-white text-fg-strong border-white' : ''">
            <span v-if="selectedIds.has(convo.id)">&#10003;</span>
          </span>
          <span class="truncate">{{ convo.preview || convo.agentName }} &middot; {{ new Date(convo.updatedAt).toLocaleDateString() }}</span>
        </div>
      </div>
    </div>

    <!-- Resize handle -->
    <div
      @mousedown="startResize"
      class="w-1 shrink-0 cursor-col-resize bg-transparent hover:bg-muted active:bg-neutral-500 transition-colors"
    />

    <!-- Chat area -->
    <div class="flex-1 flex flex-col">
      <!-- Agent / Model / Thinking selector -->
      <div class="px-4 py-2.5 border-b border-border flex items-center gap-3 flex-wrap">
        <label class="text-xs text-fg-muted">Agent:</label>
        <select
          v-model="selectedAgentId"
          class="bg-muted border border-input text-sm text-fg-strong px-2 py-1
                 focus:outline-hidden focus:border-ring"
        >
          <option v-for="agent in agents" :key="agent.id" :value="agent.id">
            {{ agent.name }}
          </option>
        </select>

        <label class="text-xs text-fg-muted">Model:</label>
        <select
          :value="selectedModelKey"
          @change="onModelChange"
          class="bg-muted border border-input text-sm text-fg-strong px-2 py-1
                 focus:outline-hidden focus:border-ring"
        >
          <optgroup v-for="p in providers" :key="p.name" :label="p.name">
            <option
              v-for="m in p.models"
              :key="`${p.name}::${m.id}`"
              :value="`${p.name}::${m.id}`"
            >
              {{ m.name || m.id }}
            </option>
          </optgroup>
        </select>

        <!--
          Thinking-level selector. Options come from the selected model's
          `thinkingLevels` metadata, so switching from an Ollama model (3 levels)
          to an OpenAI o-series on OpenRouter (up to 5 levels) re-populates in
          place. Hidden entirely for non-thinking models — the agent detail
          page renders a disabled placeholder, but the chat toolbar prioritizes
          compactness since non-reasoning is the common case.
        -->
        <template v-if="thinkingSupported && thinkingLevels.length">
          <label class="text-xs text-fg-muted">Thinking:</label>
          <select
            :value="selectedAgent?.thinkingMode || ''"
            @change="onThinkingModeChange"
            class="bg-muted border border-input text-sm text-fg-strong px-2 py-1
                   focus:outline-hidden focus:border-ring"
          >
            <option value="">Off</option>
            <option v-for="level in thinkingLevels" :key="level" :value="level">
              {{ level.charAt(0).toUpperCase() + level.slice(1) }}
            </option>
          </select>
        </template>

        <span v-if="streaming" class="text-xs text-emerald-700 dark:text-emerald-400 animate-pulse">{{ streamStatus || 'streaming...' }}</span>
        <span v-else-if="agentBusy" class="text-xs text-fg-muted animate-pulse">processing queue...</span>
      </div>

      <!-- Messages -->
      <div ref="messagesEl" class="flex-1 overflow-y-auto overflow-x-hidden p-4 space-y-5">
        <div
          v-for="msg in displayMessages"
          :key="msg.id ?? msg._key"
          :class="msg.role === 'user' ? 'flex justify-end' : 'flex justify-start'"
        >
          <div :class="msg.role === 'user' ? 'max-w-[80%]' : 'max-w-[85%]'" class="min-w-0">
            <div class="flex items-baseline gap-2 mb-1" :class="msg.role === 'user' ? 'justify-end' : ''">
              <span class="text-xs font-medium" :class="msg.role === 'user' ? 'text-blue-700 dark:text-blue-400' : 'text-emerald-700 dark:text-emerald-400'">
                {{ msg.role === 'user' ? 'you' : 'assistant' }}
              </span>
              <span v-if="msg.createdAt" class="text-xs text-fg-muted">{{ formatTimestamp(msg.createdAt) }}</span>
            </div>
            <!-- User messages: plain text + hover actions (copy, edit) -->
            <div v-if="msg.role === 'user'" class="group">
              <div
                class="bg-blue-100 dark:bg-blue-900/30 border border-blue-200 dark:border-blue-800/40 rounded-2xl rounded-tr-sm text-fg-primary px-4 py-2.5 text-sm whitespace-pre-wrap break-words"
              >{{ msg.content }}</div>
              <div class="flex items-center justify-end gap-1 mt-1 h-5 opacity-0 group-hover:opacity-100 transition-opacity">
                <button
                  type="button"
                  @click="copyUserMessage(msg)"
                  class="p-1 text-fg-muted hover:text-fg-primary transition-colors"
                  :title="copiedMessageId === (msg.id ?? msg._key) ? 'Copied' : 'Copy to clipboard'"
                >
                  <svg v-if="copiedMessageId !== (msg.id ?? msg._key)" class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" /></svg>
                  <svg v-else class="w-3.5 h-3.5 text-emerald-700 dark:text-emerald-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7" /></svg>
                </button>
                <button
                  type="button"
                  @click="editUserMessage(msg)"
                  :disabled="streaming"
                  class="p-1 text-fg-muted hover:text-fg-primary disabled:text-neutral-300 dark:disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
                  title="Edit & resubmit"
                >
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" /></svg>
                </button>
              </div>
            </div>
            <!-- Assistant messages: rendered markdown with optional thinking -->
            <div v-else>
              <!--
                Thinking/reasoning block. Header is always rendered when reasoning
                exists; clicking it toggles just this bubble's collapse state.
                Default: in-flight turns start expanded and auto-collapse at the
                reasoning→content transition; historical turns load collapsed.
                Body is suppressed when msg.thinkingCollapsed is true.
              -->
              <div v-if="msg.reasoning"
                   class="bg-emerald-50 dark:bg-emerald-950/30 border border-emerald-200 dark:border-emerald-800/20 rounded-xl rounded-tl-sm text-emerald-800/80 dark:text-emerald-300/70 px-3 py-2 text-xs font-mono mb-1.5 whitespace-pre-wrap break-words"
                   :class="msg.thinkingCollapsed ? '' : 'max-h-48 overflow-y-auto'">
                <button type="button"
                        @click="toggleThinking(msg)"
                        class="flex items-center gap-1.5 w-full text-left text-emerald-700 dark:text-emerald-400/60 text-[10px] font-sans font-medium hover:text-emerald-600 dark:hover:text-emerald-400/90 focus:outline-none"
                        :class="msg.thinkingCollapsed ? '' : 'mb-1'"
                        :title="msg.thinkingCollapsed ? 'Expand reasoning' : 'Collapse reasoning'">
                  <svg class="w-3 h-3 transition-transform"
                       :class="msg.thinkingCollapsed ? '' : 'rotate-90'"
                       fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9 5l7 7-7 7" />
                  </svg>
                  <span>{{ thinkingHeaderLabel(msg) }}</span>
                </button>
                <div v-if="!msg.thinkingCollapsed">{{ msg.reasoning }}</div>
              </div>
              <!-- Response content -->
              <div v-if="msg.content"
                   class="prose-chat bg-muted border border-input rounded-2xl rounded-tl-sm text-fg-primary px-4 py-2.5 text-sm overflow-x-auto break-words"
                   v-html="renderMarkdown(msg.content, selectedAgentId)"
              />
              <div v-else-if="!msg.reasoning"
                   class="bg-muted border border-input rounded-2xl rounded-tl-sm text-fg-muted px-4 py-2.5 text-sm italic">
                (empty response)
              </div>
              <!-- Usage metrics footer -->
              <div v-if="msg.usage" class="flex items-center gap-2 flex-wrap mt-1.5 px-1">
                <span class="inline-flex items-center gap-1 text-xs text-fg-muted"
                      :title="`${msg.usage.prompt.toLocaleString()} input tokens`">
                  <svg class="w-3 h-3 text-fg-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 11l5-5m0 0l5 5m-5-5v12" /></svg>
                  {{ msg.usage.prompt.toLocaleString() }}
                </span>
                <span v-if="msg.usage.cached"
                      class="inline-flex items-center gap-1 text-xs text-amber-700 dark:text-amber-400/70"
                      :title="`${msg.usage.cached.toLocaleString()} of ${msg.usage.prompt.toLocaleString()} input tokens served from prompt cache`">
                  <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M13 10V3L4 14h7v7l9-11h-7z" /></svg>
                  {{ msg.usage.cached.toLocaleString() }}
                </span>
                <span v-if="msg.usage.reasoning"
                      class="inline-flex items-center gap-1 text-xs text-emerald-700/80 dark:text-emerald-400/70"
                      :title="`${msg.usage.reasoning.toLocaleString()} reasoning tokens`">
                  <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" /></svg>
                  {{ msg.usage.reasoning.toLocaleString() }}
                </span>
                <span class="inline-flex items-center gap-1 text-xs text-fg-muted"
                      :title="`${msg.usage.completion.toLocaleString()} output tokens`">
                  <svg class="w-3 h-3 text-fg-muted" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 13l-5 5m0 0l-5-5m5 5V6" /></svg>
                  {{ msg.usage.completion.toLocaleString() }}
                </span>
                <span class="text-border text-xs">|</span>
                <span v-if="formatTokensPerSec(msg.usage)" class="text-xs text-fg-muted" title="Output tokens per second">
                  {{ formatTokensPerSec(msg.usage) }}
                </span>
                <span v-if="msg.usage.durationMs" class="text-xs text-fg-muted" title="Total response time">
                  {{ (msg.usage.durationMs / 1000).toFixed(1) }}s
                </span>
                <span v-if="formatUsageCost(msg.usage)"
                      class="text-xs text-amber-500/80 font-medium"
                      :title="formatUsageCostTooltip(msg.usage)">
                  {{ formatUsageCost(msg.usage) }}
                </span>
              </div>
            </div>
          </div>
        </div>
        <!--
          Pre-first-byte placeholder. Visible only during the gap between "user
          sent the request" and "the first stream event (reasoning OR content)
          arrived." Once either signal lands, displayMessages starts rendering
          the real bubble (with live reasoning and/or content) and this gray
          placeholder yields. Without the streamReasoning guard, JCLAW-75
          regression: reasoning-mode turns show this pill for the entire
          thinking phase.
        -->
        <div v-if="streaming && !streamContent && !streamReasoning" class="flex justify-start">
          <div class="max-w-[85%]">
            <div class="flex items-baseline gap-2 mb-1">
              <span class="text-xs font-medium text-emerald-700 dark:text-emerald-400">assistant</span>
            </div>
            <div class="bg-muted border border-input rounded-2xl rounded-tl-sm px-4 py-2.5 text-sm text-fg-muted">
              <span class="animate-pulse">{{ streamStatus || 'Thinking...' }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Input -->
      <div class="px-4 py-3">
        <form @submit.prevent="sendMessage"
              class="bg-surface-elevated border border-ring rounded-xl overflow-hidden">
          <div v-if="attachedFiles.length || attachError" class="px-3 pt-2.5 pb-1 flex flex-wrap gap-1.5">
            <span
              v-for="(f, idx) in attachedFiles"
              :key="`${f.name}-${idx}`"
              class="inline-flex items-center gap-1.5 px-2 py-1 bg-muted border border-input rounded text-[11px] text-fg-primary"
            >
              <svg class="w-3 h-3 text-fg-muted shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
              </svg>
              <span class="truncate max-w-[140px]" :title="f.name">{{ f.name }}</span>
              <span class="text-fg-muted">{{ formatAttachmentSize(f.size) }}</span>
              <button
                type="button"
                @click="removeAttachment(idx)"
                class="ml-0.5 text-fg-muted hover:text-fg-strong transition-colors"
                title="Remove"
              >
                <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
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
                @click="attachError = null"
                class="text-red-600 dark:text-red-400/70 hover:text-red-800 dark:hover:text-red-200 transition-colors"
                title="Dismiss"
              >
                <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </span>
          </div>
          <textarea
            v-model="input"
            placeholder="Type a message..."
            :disabled="streaming"
            rows="1"
            @keydown.enter.exact.prevent="sendMessage()"
            @input="autoResize"
            ref="chatInput"
            class="w-full px-4 pt-3 pb-2 bg-transparent text-sm text-fg-strong
                   placeholder-fg-muted focus:outline-hidden resize-none overflow-hidden"
          />
          <input ref="fileInput" type="file" multiple class="hidden" @change="handleFileUpload" />
          <div class="flex items-center justify-between px-3 pb-2.5">
            <div class="flex items-center gap-1">
              <button type="button" @click="triggerFileUpload" class="p-1.5 text-fg-muted hover:text-fg-primary transition-colors" title="Attach file">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" /></svg>
              </button>
            </div>
            <div class="flex items-center gap-1">
              <button type="button" @click="newChat" class="p-1.5 text-fg-muted hover:text-fg-primary transition-colors" title="New conversation">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 4v16m8-8H4" /></svg>
              </button>
              <button type="button" @click="exportConversation" :disabled="!displayMessages.length" class="p-1.5 text-fg-muted hover:text-fg-primary disabled:text-neutral-300 dark:disabled:text-neutral-700 transition-colors" title="Export as Markdown">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" /></svg>
              </button>
              <button
                v-if="streaming"
                type="button"
                @click="stopStreaming"
                class="p-1.5 text-red-600 dark:text-red-500 hover:text-red-700 dark:hover:text-red-400 transition-colors"
                title="Stop generating"
              >
                <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="1.5" /></svg>
              </button>
              <button
                v-else
                type="submit"
                :disabled="!input.trim() && !attachedFiles.length"
                class="p-1.5 text-fg-muted hover:text-emerald-700 dark:hover:text-emerald-400 disabled:text-neutral-300 dark:disabled:text-neutral-700 transition-colors"
                title="Send"
              >
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M6 12L3 21l18-9L3 3l3 9zm0 0h8" /></svg>
              </button>
            </div>
          </div>
        </form>
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
.prose-chat { overflow-wrap: break-word; word-break: break-word; }
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
.prose-chat code { background: rgba(0,0,0,0.06); color: #171717; }
.prose-chat pre { background: rgba(0,0,0,0.04); border: 1px solid rgba(0,0,0,0.08); }
.prose-chat a { color: #525252; }
.prose-chat a.workspace-file {
  background: rgba(16, 185, 129, 0.08);
  border: 1px solid rgba(16, 185, 129, 0.35);
  color: #047857;
}
.prose-chat a.workspace-file:hover {
  background: rgba(16, 185, 129, 0.18);
  border-color: rgba(16, 185, 129, 0.6);
}
.prose-chat blockquote { border-left: 2px solid rgba(0,0,0,0.12); color: #525252; }
.prose-chat hr { border-top: 1px solid rgba(0,0,0,0.1); }
.prose-chat img { border: 1px solid rgba(0,0,0,0.08); }
.prose-chat img:hover { border-color: rgba(0,0,0,0.2); }
.prose-chat th { color: #171717; border-bottom: 1px solid rgba(0,0,0,0.15); font-weight: 600; }
.prose-chat td { border-bottom: 1px solid rgba(0,0,0,0.06); }

/* Dark-mode overrides */
html.dark .prose-chat strong { color: #e5e5e5; }
html.dark .prose-chat em { color: #d4d4d4; }
html.dark .prose-chat h1,
html.dark .prose-chat h2,
html.dark .prose-chat h3 { color: #e5e5e5; }
html.dark .prose-chat code { background: rgba(255,255,255,0.06); color: inherit; }
html.dark .prose-chat pre { background: rgba(255,255,255,0.04); border-color: rgba(255,255,255,0.08); }
html.dark .prose-chat a { color: #a3a3a3; }
html.dark .prose-chat a.workspace-file {
  background: rgba(16, 185, 129, 0.1);
  border-color: rgba(16, 185, 129, 0.3);
  color: #6ee7b7;
}
html.dark .prose-chat a.workspace-file:hover {
  background: rgba(16, 185, 129, 0.18);
  border-color: rgba(16, 185, 129, 0.55);
}
html.dark .prose-chat blockquote { border-left-color: rgba(255,255,255,0.1); color: #a3a3a3; }
html.dark .prose-chat hr { border-top-color: rgba(255,255,255,0.08); }
html.dark .prose-chat img { border-color: rgba(255,255,255,0.08); }
html.dark .prose-chat img:hover { border-color: rgba(255,255,255,0.2); }
html.dark .prose-chat th { color: #e5e5e5; border-bottom-color: rgba(255,255,255,0.15); }
html.dark .prose-chat td { border-bottom-color: rgba(255,255,255,0.06); }
</style>
