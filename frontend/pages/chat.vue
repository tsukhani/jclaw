<script setup lang="ts">
import { marked } from 'marked'
import DOMPurify from 'dompurify'

// Configure marked for safe rendering
marked.setOptions({
  breaks: true,
  gfm: true
})

function formatUsageCost(usage: MessageUsage): string | null {
  if (!usage.promptPrice && !usage.completionPrice) return null
  const inputCost = (usage.prompt / 1_000_000) * (usage.promptPrice || 0)
  const outputCost = (usage.completion / 1_000_000) * (usage.completionPrice || 0)
  const total = inputCost + outputCost
  if (total < 0.0001) return '< $0.0001'
  return '$' + total.toFixed(4)
}

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

function renderMarkdown(text: string, agentId: number | null = null): string {
  if (!text) return ''
  const html = marked.parse(text) as string
  const sanitized = DOMPurify.sanitize(html, {
    ADD_TAGS: ['img', 'audio', 'video', 'source'],
    ADD_ATTR: ['src', 'controls', 'autoplay', 'download', 'target']
  })
  return agentId != null ? rewriteWorkspaceLinks(sanitized, agentId) : sanitized
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
    if (/^(https?|mailto|tel|ftp|data|javascript):/i.test(href)) return
    const encoded = href.split('/').filter(Boolean).map(encodeURIComponent).join('/')
    a.setAttribute('href', `/api/agents/${agentId}/files/${encoded}`)
    a.setAttribute('download', '')
    a.setAttribute('target', '_blank')
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

const { data: agents, refresh: refreshAgents } = await useFetch<any[]>('/api/agents')
const { data: configData } = await useFetch<{ entries: any[] }>('/api/config')

const selectedAgentId = ref<number | null>(null)

// Extract configured providers and their models from config
const providers = computed(() => {
  const entries = configData.value?.entries ?? []
  const providerMap = new Map<string, { name: string, models: any[] }>()

  for (const e of entries) {
    if (!e.key.startsWith('provider.')) continue
    const name = e.key.split('.')[1]
    if (!providerMap.has(name)) providerMap.set(name, { name, models: [] })
  }

  for (const e of entries) {
    if (e.key.endsWith('.apiKey') && e.key.startsWith('provider.')) {
      const name = e.key.split('.')[1]
      if (!e.value || e.value === '(empty)') providerMap.delete(name)
    }
  }

  for (const e of entries) {
    if (e.key.endsWith('.models') && e.key.startsWith('provider.')) {
      const name = e.key.split('.')[1]
      const provider = providerMap.get(name)
      if (provider) {
        try { provider.models = JSON.parse(e.value) } catch { provider.models = [] }
      }
    }
  }

  return Array.from(providerMap.values())
})

// The currently selected agent object
const selectedAgent = computed(() => agents.value?.find((a: any) => a.id === selectedAgentId.value))

// Available models for the selected agent's provider
const availableModels = computed(() => {
  const providerName = selectedAgent.value?.modelProvider
  if (!providerName) return []
  return providers.value.find(p => p.name === providerName)?.models ?? []
})

// Current model info for the selected agent
const selectedModelInfo = computed(() => {
  const modelId = selectedAgent.value?.modelId
  return availableModels.value.find((m: any) => m.id === modelId) ?? null
})

// Whether the selected model supports thinking
const thinkingSupported = computed(() => selectedModelInfo.value?.supportsThinking === true)

// Sync model or thinking mode change back to the agent
async function updateAgentSetting(updates: Record<string, any>) {
  if (!selectedAgentId.value) return
  try {
    await $fetch(`/api/agents/${selectedAgentId.value}`, { method: 'PUT', body: updates })
    refreshAgents()
  } catch { /* ignore */ }
}

function onModelChange(event: Event) {
  const modelId = (event.target as HTMLSelectElement).value
  const model = availableModels.value.find((m: any) => m.id === modelId)
  const updates: Record<string, any> = { modelId }
  // Clear thinking mode if new model doesn't support it
  if (!model?.supportsThinking) {
    updates.thinkingMode = null
  }
  updateAgentSetting(updates)
}

function onThinkingModeChange(event: Event) {
  const val = (event.target as HTMLSelectElement).value
  updateAgentSetting({ thinkingMode: val || null })
  // Auto-toggle thinking display to match
  showThinking.value = !!val
}

const conversationsUrl = computed(() =>
  selectedAgentId.value
    ? `/api/conversations?channel=web&agentId=${selectedAgentId.value}&limit=50`
    : null
)
const { data: conversations, refresh: refreshConversations } = await useFetch<any[]>(conversationsUrl)
const selectedConvoId = ref<number | null>(null)
const messages = ref<any[]>([])
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
// Default thinking display to match whether the agent has thinking enabled
const showThinking = ref(false)
interface MessageUsage {
  prompt: number
  completion: number
  total: number
  reasoning: number
  durationMs: number
  promptPrice?: number      // cost per million tokens (input)
  completionPrice?: number  // cost per million tokens (output)
}
const messagesEl = ref<HTMLElement | null>(null)
const abortController = ref<AbortController | null>(null)
const sidebarWidth = ref(224) // 14rem = 224px (matches w-56)
const isResizing = ref(false)

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
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
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
function scrollToBottom() {
  if (scrollRaf) return
  scrollRaf = requestAnimationFrame(() => {
    if (messagesEl.value) messagesEl.value.scrollTop = messagesEl.value.scrollHeight
    scrollRaf = null
  })
}

onUnmounted(() => {
  abortController.value?.abort()
})

function stopStreaming() {
  if (!streaming.value) return
  abortController.value?.abort()
  streaming.value = false
  streamStatus.value = ''
}

// Filter out tool messages and empty assistant messages (tool call records) from display
// Keep assistant messages that have content, reasoning, or usage metrics
const displayMessages = computed(() =>
  messages.value.filter(m =>
    m.role !== 'tool' && (m.content || m.reasoning || m.usage)
  )
)

// Set default agent
watch(agents, (val) => {
  if (val?.length && !selectedAgentId.value) {
    const def = val.find((a: any) => a.isDefault) || val[0]
    selectedAgentId.value = def.id
  }
}, { immediate: true })

// When agent changes, clear current conversation and sync thinking toggle
watch(selectedAgentId, () => {
  selectedConvoId.value = null
  messages.value = []
  const agent = agents.value?.find((a: any) => a.id === selectedAgentId.value)
  showThinking.value = !!agent?.thinkingMode
})

async function loadConversation(id: number) {
  // Generate a title for the conversation we're leaving (if it still has a truncated preview)
  if (selectedConvoId.value && selectedConvoId.value !== id) {
    generateTitleForConversation(selectedConvoId.value)
  }
  selectedConvoId.value = id
  messages.value = await $fetch<any[]>(`/api/conversations/${id}/messages`) ?? []
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
            streamReasoning.value += event.content
            messages.value[assistantIdx].reasoning = streamReasoning.value
            if (!streamContent.value) {
              streamStatus.value = 'thinking...'
            }
            scrollToBottom()
          } else if (event.type === 'token') {
            streamStatus.value = ''
            if (event.timestamp) messages.value[assistantIdx].createdAt = event.timestamp
            streamContent.value += event.content
            messages.value[assistantIdx].content = streamContent.value
            scrollToBottom()
          } else if (event.type === 'complete') {
            streamStatus.value = ''
            messages.value[assistantIdx].content = event.content || streamContent.value
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
    setTimeout(() => refreshConversations(), 3000)
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

function formatAttachmentSize(bytes: number) {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

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
  a.click()
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div class="flex -m-6" style="height: calc(100vh - 3rem);" :class="{ 'select-none': isResizing }">
    <!-- Conversation sidebar -->
    <div :style="{ width: sidebarWidth + 'px' }" class="shrink-0 border-r border-neutral-800 flex flex-col overflow-hidden">
      <div class="p-3 border-b border-neutral-800 flex items-center justify-between">
        <template v-if="selectMode">
          <button @click="selectAll" class="text-xs text-neutral-500 hover:text-white transition-colors">
            {{ selectedIds.size === (conversations?.length || 0) ? 'None' : 'All' }}
          </button>
          <div class="flex items-center gap-2">
            <button
              @click="deleteSelected"
              :disabled="selectedIds.size === 0"
              class="text-xs text-red-400 hover:text-red-300 disabled:text-neutral-600 transition-colors"
            >Delete ({{ selectedIds.size }})</button>
            <button @click="exitSelectMode" class="text-xs text-neutral-500 hover:text-white transition-colors">Done</button>
          </div>
        </template>
        <template v-else>
          <span class="text-xs font-medium text-neutral-400">Conversations</span>
          <button
            v-if="conversations?.length"
            @click="selectMode = true"
            class="p-1 text-neutral-500 hover:text-white transition-colors"
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
            selectMode && selectedIds.has(convo.id) ? 'bg-neutral-700 text-white' :
            selectedConvoId === convo.id ? 'bg-neutral-800 text-white' : 'text-neutral-400'
          ]"
          class="w-full text-left px-3 py-2 text-xs hover:bg-neutral-800 transition-colors truncate flex items-center gap-2 cursor-pointer"
        >
          <span v-if="selectMode" class="shrink-0 w-3.5 h-3.5 border border-neutral-600 flex items-center justify-center text-[10px]"
                :class="selectedIds.has(convo.id) ? 'bg-white text-neutral-900 border-white' : ''">
            <span v-if="selectedIds.has(convo.id)">&#10003;</span>
          </span>
          <span class="truncate">{{ convo.preview || convo.agentName }} &middot; {{ new Date(convo.updatedAt).toLocaleDateString() }}</span>
        </div>
      </div>
    </div>

    <!-- Resize handle -->
    <div
      @mousedown="startResize"
      class="w-1 shrink-0 cursor-col-resize bg-transparent hover:bg-neutral-600 active:bg-neutral-500 transition-colors"
    />

    <!-- Chat area -->
    <div class="flex-1 flex flex-col">
      <!-- Agent / Model / Thinking selector -->
      <div class="px-4 py-2.5 border-b border-neutral-800 flex items-center gap-3 flex-wrap">
        <label class="text-xs text-neutral-500">Agent:</label>
        <select
          v-model="selectedAgentId"
          class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1
                 focus:outline-none focus:border-neutral-600"
        >
          <option v-for="agent in agents" :key="agent.id" :value="agent.id">
            {{ agent.name }}
          </option>
        </select>

        <label class="text-xs text-neutral-500">Model:</label>
        <select
          :value="selectedAgent?.modelId"
          @change="onModelChange"
          class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1
                 focus:outline-none focus:border-neutral-600"
        >
          <option v-for="m in availableModels" :key="m.id" :value="m.id">
            {{ m.name || m.id }}
          </option>
        </select>

        <label class="text-xs text-neutral-500" :class="{ 'opacity-40': !thinkingSupported }">Thinking:</label>
        <select
          :value="selectedAgent?.thinkingMode || ''"
          @change="onThinkingModeChange"
          :disabled="!thinkingSupported"
          class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1
                 focus:outline-none focus:border-neutral-600
                 disabled:opacity-40 disabled:cursor-not-allowed"
        >
          <option value="">Off</option>
          <option value="low">Low</option>
          <option value="medium">Medium</option>
          <option value="high">High</option>
        </select>

        <button @click="showThinking = !showThinking"
                :class="showThinking ? 'text-blue-400' : 'text-neutral-600'"
                class="p-1 hover:text-blue-300 transition-colors"
                title="Toggle thinking display">
          <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" /></svg>
        </button>

        <span v-if="streaming" class="text-xs text-emerald-400 animate-pulse">{{ streamStatus || 'streaming...' }}</span>
        <span v-else-if="agentBusy" class="text-xs text-neutral-500 animate-pulse">processing queue...</span>
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
              <span class="text-xs font-medium" :class="msg.role === 'user' ? 'text-blue-400' : 'text-emerald-400'">
                {{ msg.role === 'user' ? 'you' : 'assistant' }}
              </span>
              <span v-if="msg.createdAt" class="text-xs text-neutral-400">{{ formatTimestamp(msg.createdAt) }}</span>
            </div>
            <!-- User messages: plain text + hover actions (copy, edit) -->
            <div v-if="msg.role === 'user'" class="group">
              <div
                class="bg-blue-900/30 border border-blue-800/40 rounded-2xl rounded-tr-sm text-neutral-200 px-4 py-2.5 text-sm whitespace-pre-wrap break-words"
              >{{ msg.content }}</div>
              <div class="flex items-center justify-end gap-1 mt-1 h-5 opacity-0 group-hover:opacity-100 transition-opacity">
                <button
                  type="button"
                  @click="copyUserMessage(msg)"
                  class="p-1 text-neutral-500 hover:text-neutral-200 transition-colors"
                  :title="copiedMessageId === (msg.id ?? msg._key) ? 'Copied' : 'Copy to clipboard'"
                >
                  <svg v-if="copiedMessageId !== (msg.id ?? msg._key)" class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M8 16H6a2 2 0 01-2-2V6a2 2 0 012-2h8a2 2 0 012 2v2m-6 12h8a2 2 0 002-2v-8a2 2 0 00-2-2h-8a2 2 0 00-2 2v8a2 2 0 002 2z" /></svg>
                  <svg v-else class="w-3.5 h-3.5 text-emerald-400" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M5 13l4 4L19 7" /></svg>
                </button>
                <button
                  type="button"
                  @click="editUserMessage(msg)"
                  :disabled="streaming"
                  class="p-1 text-neutral-500 hover:text-neutral-200 disabled:text-neutral-700 disabled:cursor-not-allowed transition-colors"
                  title="Edit & resubmit"
                >
                  <svg class="w-3.5 h-3.5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M11 5H6a2 2 0 00-2 2v11a2 2 0 002 2h11a2 2 0 002-2v-5m-1.414-9.414a2 2 0 112.828 2.828L11.828 15H9v-2.828l8.586-8.586z" /></svg>
                </button>
              </div>
            </div>
            <!-- Assistant messages: rendered markdown with optional thinking -->
            <div v-else>
              <!-- Thinking/reasoning block -->
              <div v-if="showThinking && msg.reasoning"
                   class="bg-blue-950/30 border border-blue-800/20 rounded-xl rounded-tl-sm text-blue-300/70 px-3 py-2 text-xs font-mono mb-1.5 max-h-48 overflow-y-auto whitespace-pre-wrap break-words">
                <div class="flex items-center gap-1.5 mb-1 text-blue-400/60 text-[10px] font-sans font-medium">
                  <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" /></svg>
                  Thinking
                </div>
                {{ msg.reasoning }}
              </div>
              <!-- Response content -->
              <div v-if="msg.content"
                   class="prose-chat bg-neutral-800/50 border border-neutral-700/50 rounded-2xl rounded-tl-sm text-neutral-300 px-4 py-2.5 text-sm overflow-x-auto break-words"
                   v-html="renderMarkdown(msg.content, selectedAgentId)"
              />
              <div v-else-if="!msg.reasoning"
                   class="bg-neutral-800/50 border border-neutral-700/50 rounded-2xl rounded-tl-sm text-neutral-500 px-4 py-2.5 text-sm italic">
                (empty response)
              </div>
              <!-- Usage metrics footer -->
              <div v-if="msg.usage" class="flex items-center gap-2 flex-wrap mt-1.5 px-1">
                <span class="inline-flex items-center gap-1 text-xs text-neutral-400"
                      :title="`${msg.usage.prompt.toLocaleString()} input tokens`">
                  <svg class="w-3 h-3 text-neutral-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M7 11l5-5m0 0l5 5m-5-5v12" /></svg>
                  {{ msg.usage.prompt.toLocaleString() }}
                </span>
                <span v-if="msg.usage.reasoning"
                      class="inline-flex items-center gap-1 text-xs text-blue-400/70"
                      :title="`${msg.usage.reasoning.toLocaleString()} reasoning tokens`">
                  <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M9.663 17h4.673M12 3v1m6.364 1.636l-.707.707M21 12h-1M4 12H3m3.343-5.657l-.707-.707m2.828 9.9a5 5 0 117.072 0l-.548.547A3.374 3.374 0 0014 18.469V19a2 2 0 11-4 0v-.531c0-.895-.356-1.754-.988-2.386l-.548-.547z" /></svg>
                  {{ msg.usage.reasoning.toLocaleString() }}
                </span>
                <span class="inline-flex items-center gap-1 text-xs text-neutral-400"
                      :title="`${msg.usage.completion.toLocaleString()} output tokens`">
                  <svg class="w-3 h-3 text-neutral-500" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M17 13l-5 5m0 0l-5-5m5 5V6" /></svg>
                  {{ msg.usage.completion.toLocaleString() }}
                </span>
                <span class="text-neutral-700 text-xs">|</span>
                <span v-if="formatTokensPerSec(msg.usage)" class="text-xs text-neutral-500" title="Output tokens per second">
                  {{ formatTokensPerSec(msg.usage) }}
                </span>
                <span v-if="msg.usage.durationMs" class="text-xs text-neutral-500" title="Total response time">
                  {{ (msg.usage.durationMs / 1000).toFixed(1) }}s
                </span>
                <span v-if="formatUsageCost(msg.usage)"
                      class="text-xs text-amber-500/80 font-medium"
                      :title="`Input: $${((msg.usage.prompt / 1000000) * (msg.usage.promptPrice || 0)).toFixed(6)} + Output: $${((msg.usage.completion / 1000000) * (msg.usage.completionPrice || 0)).toFixed(6)}`">
                  {{ formatUsageCost(msg.usage) }}
                </span>
              </div>
            </div>
          </div>
        </div>
        <div v-if="streaming && !streamContent" class="flex justify-start">
          <div class="max-w-[85%]">
            <div class="flex items-baseline gap-2 mb-1">
              <span class="text-xs font-medium text-emerald-400">assistant</span>
            </div>
            <div class="bg-neutral-800/50 border border-neutral-700/50 rounded-2xl rounded-tl-sm px-4 py-2.5 text-sm text-neutral-500">
              <span class="animate-pulse">{{ streamStatus || 'Thinking...' }}</span>
            </div>
          </div>
        </div>
      </div>

      <!-- Input -->
      <div class="px-4 py-3">
        <form @submit.prevent="sendMessage"
              class="bg-neutral-900 border border-neutral-600/40 rounded-xl overflow-hidden">
          <div v-if="attachedFiles.length || attachError" class="px-3 pt-2.5 pb-1 flex flex-wrap gap-1.5">
            <span
              v-for="(f, idx) in attachedFiles"
              :key="`${f.name}-${idx}`"
              class="inline-flex items-center gap-1.5 px-2 py-1 bg-neutral-800 border border-neutral-700 rounded text-[11px] text-neutral-300"
            >
              <svg class="w-3 h-3 text-neutral-500 shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
              </svg>
              <span class="truncate max-w-[140px]" :title="f.name">{{ f.name }}</span>
              <span class="text-neutral-500">{{ formatAttachmentSize(f.size) }}</span>
              <button
                type="button"
                @click="removeAttachment(idx)"
                class="ml-0.5 text-neutral-500 hover:text-white transition-colors"
                title="Remove"
              >
                <svg class="w-3 h-3" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2.5" d="M6 18L18 6M6 6l12 12" />
                </svg>
              </button>
            </span>
            <span
              v-if="attachError"
              class="inline-flex items-center gap-1.5 px-2 py-1 bg-red-900/30 border border-red-800/50 rounded text-[11px] text-red-300"
            >
              <span>{{ attachError }}</span>
              <button
                type="button"
                @click="attachError = null"
                class="text-red-400/70 hover:text-red-200 transition-colors"
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
            class="w-full px-4 pt-3 pb-2 bg-transparent text-sm text-white
                   placeholder-neutral-600 focus:outline-none resize-none overflow-hidden"
          />
          <input ref="fileInput" type="file" multiple class="hidden" @change="handleFileUpload" />
          <div class="flex items-center justify-between px-3 pb-2.5">
            <div class="flex items-center gap-1">
              <button type="button" @click="triggerFileUpload" class="p-1.5 text-neutral-500 hover:text-neutral-300 transition-colors" title="Attach file">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" /></svg>
              </button>
            </div>
            <div class="flex items-center gap-1">
              <button type="button" @click="newChat" class="p-1.5 text-neutral-500 hover:text-neutral-300 transition-colors" title="New conversation">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M12 4v16m8-8H4" /></svg>
              </button>
              <button type="button" @click="exportConversation" :disabled="!displayMessages.length" class="p-1.5 text-neutral-500 hover:text-neutral-300 disabled:text-neutral-700 transition-colors" title="Export as Markdown">
                <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" /></svg>
              </button>
              <button
                v-if="streaming"
                type="button"
                @click="stopStreaming"
                class="p-1.5 text-neutral-400 hover:text-red-400 transition-colors"
                title="Stop generating"
              >
                <svg class="w-4 h-4" fill="currentColor" viewBox="0 0 24 24"><rect x="6" y="6" width="12" height="12" rx="1.5" /></svg>
              </button>
              <button
                v-else
                type="submit"
                :disabled="!input.trim() && !attachedFiles.length"
                class="p-1.5 text-neutral-500 hover:text-emerald-400 disabled:text-neutral-700 transition-colors"
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
/* Markdown rendering styles for chat messages */
.prose-chat { overflow-wrap: break-word; word-break: break-word; }
.prose-chat p { margin: 0.5em 0; }
.prose-chat p:first-child { margin-top: 0; }
.prose-chat p:last-child { margin-bottom: 0; }
.prose-chat strong { color: #e5e5e5; font-weight: 600; }
.prose-chat em { color: #d4d4d4; }
.prose-chat code {
  background: rgba(255,255,255,0.06);
  padding: 0.15em 0.35em;
  font-size: 0.875em;
  font-family: ui-monospace, monospace;
}
.prose-chat pre {
  background: rgba(255,255,255,0.04);
  border: 1px solid rgba(255,255,255,0.08);
  padding: 0.75em 1em;
  margin: 0.5em 0;
  overflow-x: auto;
}
.prose-chat pre code {
  background: none;
  padding: 0;
}
.prose-chat ul, .prose-chat ol {
  margin: 0.5em 0;
  padding-left: 1.5em;
}
.prose-chat li { margin: 0.25em 0; }
.prose-chat h1, .prose-chat h2, .prose-chat h3 {
  color: #e5e5e5;
  font-weight: 600;
  margin: 0.75em 0 0.25em;
}
.prose-chat h1 { font-size: 1.1em; }
.prose-chat h2 { font-size: 1em; }
.prose-chat h3 { font-size: 0.95em; }
.prose-chat a { color: #a3a3a3; text-decoration: underline; }
.prose-chat a.workspace-file {
  display: inline-flex;
  align-items: center;
  gap: 0.4em;
  padding: 0.25em 0.6em;
  margin: 0.15em 0.15em 0.15em 0;
  background: rgba(16, 185, 129, 0.1);
  border: 1px solid rgba(16, 185, 129, 0.3);
  border-radius: 0.4em;
  color: #6ee7b7;
  text-decoration: none;
  font-size: 0.9em;
  transition: background 0.15s, border-color 0.15s;
}
.prose-chat a.workspace-file:hover {
  background: rgba(16, 185, 129, 0.18);
  border-color: rgba(16, 185, 129, 0.55);
}
.prose-chat a.workspace-file::before {
  content: "⬇";
  font-size: 0.85em;
  opacity: 0.75;
}
.prose-chat blockquote {
  border-left: 2px solid rgba(255,255,255,0.1);
  padding-left: 0.75em;
  margin: 0.5em 0;
  color: #a3a3a3;
}
.prose-chat hr {
  border: none;
  border-top: 1px solid rgba(255,255,255,0.08);
  margin: 0.75em 0;
}
.prose-chat img {
  max-width: 100%;
  height: auto;
  border-radius: 0.5em;
  border: 1px solid rgba(255,255,255,0.08);
  margin: 0.5em 0;
  cursor: pointer;
}
.prose-chat img:hover {
  border-color: rgba(255,255,255,0.2);
}
.prose-chat audio, .prose-chat video {
  max-width: 100%;
  margin: 0.5em 0;
  border-radius: 0.5em;
}
</style>
