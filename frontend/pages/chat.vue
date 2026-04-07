<script setup lang="ts">
import { marked } from 'marked'
import DOMPurify from 'dompurify'

// Configure marked for safe rendering
marked.setOptions({
  breaks: true,
  gfm: true
})

function renderMarkdown(text: string): string {
  if (!text) return ''
  return DOMPurify.sanitize(marked.parse(text) as string)
}

const { data: agents } = await useFetch<any[]>('/api/agents')

const selectedAgentId = ref<number | null>(null)

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
const chatInput = ref<HTMLTextAreaElement | null>(null)

function autoResize() {
  const el = chatInput.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}
const agentBusy = ref(false)
const streamContent = ref('')
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

// Filter out tool messages and empty assistant messages (tool call records) from display
const displayMessages = computed(() =>
  messages.value.filter(m =>
    m.role !== 'tool' && !(m.role === 'assistant' && !m.content)
  )
)

// Set default agent
watch(agents, (val) => {
  if (val?.length && !selectedAgentId.value) {
    const def = val.find((a: any) => a.isDefault) || val[0]
    selectedAgentId.value = def.id
  }
}, { immediate: true })

// When agent changes, clear current conversation and refresh list
watch(selectedAgentId, () => {
  selectedConvoId.value = null
  messages.value = []
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
  if (!input.value.trim() || !selectedAgentId.value || streaming.value) return

  const text = input.value.trim()
  input.value = ''
  if (chatInput.value) chatInput.value.style.height = 'auto'
  messages.value.push({ _key: crypto.randomUUID(), role: 'user', content: text, createdAt: new Date().toISOString() })
  scrollToBottom()

  streaming.value = true
  streamContent.value = ''

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
          } else if (event.type === 'token') {
            streamContent.value += event.content
            messages.value[assistantIdx].content = streamContent.value
            scrollToBottom()
          } else if (event.type === 'complete') {
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
    messages.value[assistantIdx].content = 'Error: ' + (e.message || 'Failed to get response')
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

function triggerFileUpload() {
  fileInput.value?.click()
}

function handleFileUpload(event: Event) {
  const target = event.target as HTMLInputElement
  const file = target.files?.[0]
  if (!file) return
  // Read as text and paste into input
  const reader = new FileReader()
  reader.onload = () => {
    const text = reader.result as string
    input.value = `[Attached: ${file.name}]\n${text.substring(0, 10000)}`
  }
  reader.readAsText(file)
  target.value = '' // Reset so same file can be re-uploaded
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
      <!-- Agent selector -->
      <div class="px-4 py-2.5 border-b border-neutral-800 flex items-center gap-3">
        <label class="text-xs text-neutral-500">Agent:</label>
        <select
          v-model="selectedAgentId"
          class="bg-neutral-800 border border-neutral-700 text-sm text-white px-2 py-1
                 focus:outline-none focus:border-neutral-600"
        >
          <option v-for="agent in agents" :key="agent.id" :value="agent.id">
            {{ agent.name }} ({{ agent.modelId }})
          </option>
        </select>
        <span v-if="streaming" class="text-xs text-emerald-400 animate-pulse">streaming...</span>
        <span v-else-if="agentBusy" class="text-xs text-neutral-500 animate-pulse">processing queue...</span>
      </div>

      <!-- Messages -->
      <div ref="messagesEl" class="flex-1 overflow-y-auto p-4 space-y-4">
        <div
          v-for="msg in displayMessages"
          :key="msg.id ?? msg._key"
          :class="msg.role === 'user' ? 'ml-12' : 'mr-12'"
        >
          <div class="text-xs text-neutral-600 mb-1">{{ msg.role }}</div>
          <!-- User messages: plain text -->
          <div v-if="msg.role === 'user'"
               class="bg-neutral-800 text-neutral-200 px-3 py-2 text-sm whitespace-pre-wrap"
          >{{ msg.content }}</div>
          <!-- Assistant messages: rendered markdown -->
          <div v-else
               class="prose-chat text-neutral-300 border border-neutral-800 px-3 py-2 text-sm"
               v-html="renderMarkdown(msg.content || '')"
          />
        </div>
        <div v-if="streaming && !streamContent" class="mr-12">
          <div class="text-xs text-neutral-600 mb-1">assistant</div>
          <div class="border border-neutral-800 px-3 py-2 text-sm text-neutral-500">
            <span class="animate-pulse">Thinking...</span>
          </div>
        </div>
      </div>

      <!-- Input -->
      <div class="px-4 py-3">
        <form @submit.prevent="sendMessage"
              class="bg-neutral-900 border border-neutral-600/40 rounded-xl overflow-hidden">
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
          <input ref="fileInput" type="file" class="hidden" @change="handleFileUpload" />
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
                type="submit"
                :disabled="streaming || !input.trim()"
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
</style>
