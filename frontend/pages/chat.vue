<script setup lang="ts">
import { marked } from 'marked'

// Configure marked for safe rendering
marked.setOptions({
  breaks: true,
  gfm: true
})

function renderMarkdown(text: string): string {
  if (!text) return ''
  return marked.parse(text) as string
}

const { data: agents } = await useFetch<any[]>('/api/agents')
const { data: conversations } = await useFetch<any[]>('/api/conversations?channel=web&limit=50')

const selectedAgentId = ref<number | null>(null)
const selectedConvoId = ref<number | null>(null)
const messages = ref<any[]>([])
const input = ref('')
const streaming = ref(false)
const streamContent = ref('')

// Set default agent
watch(agents, (val) => {
  if (val?.length && !selectedAgentId.value) {
    const def = val.find((a: any) => a.isDefault) || val[0]
    selectedAgentId.value = def.id
  }
}, { immediate: true })

async function loadConversation(id: number) {
  selectedConvoId.value = id
  const { data } = await useFetch<any[]>(`/api/conversations/${id}/messages`)
  messages.value = data.value ?? []
}

async function sendMessage() {
  if (!input.value.trim() || !selectedAgentId.value || streaming.value) return

  const text = input.value.trim()
  input.value = ''
  messages.value.push({ role: 'user', content: text, createdAt: new Date().toISOString() })

  streaming.value = true
  streamContent.value = ''

  try {
    const res = await $fetch<any>('/api/chat/send', {
      method: 'POST',
      body: {
        agentId: selectedAgentId.value,
        conversationId: selectedConvoId.value,
        message: text
      }
    })

    if (res.conversationId && !selectedConvoId.value) {
      selectedConvoId.value = res.conversationId
    }

    messages.value.push({
      role: 'assistant',
      content: res.response,
      createdAt: new Date().toISOString()
    })
  } catch (e: any) {
    messages.value.push({
      role: 'assistant',
      content: 'Error: ' + (e.message || 'Failed to get response'),
      createdAt: new Date().toISOString()
    })
  } finally {
    streaming.value = false
  }
}

function newChat() {
  selectedConvoId.value = null
  messages.value = []
}
</script>

<template>
  <div class="flex h-[calc(100vh-7rem)] gap-4">
    <!-- Conversation sidebar -->
    <div class="w-56 shrink-0 bg-neutral-900 border border-neutral-800 flex flex-col overflow-hidden">
      <div class="p-3 border-b border-neutral-800 flex items-center justify-between">
        <span class="text-xs font-medium text-neutral-400">Conversations</span>
        <button @click="newChat" class="text-xs text-neutral-500 hover:text-white transition-colors">+ New</button>
      </div>
      <div class="flex-1 overflow-y-auto">
        <button
          v-for="convo in conversations"
          :key="convo.id"
          @click="loadConversation(convo.id)"
          :class="selectedConvoId === convo.id ? 'bg-neutral-800 text-white' : 'text-neutral-400'"
          class="w-full text-left px-3 py-2 text-xs hover:bg-neutral-800 transition-colors truncate"
        >
          {{ convo.agentName }} &middot; {{ new Date(convo.updatedAt).toLocaleDateString() }}
        </button>
      </div>
    </div>

    <!-- Chat area -->
    <div class="flex-1 flex flex-col bg-neutral-900 border border-neutral-800">
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
      </div>

      <!-- Messages -->
      <div class="flex-1 overflow-y-auto p-4 space-y-4">
        <div
          v-for="(msg, i) in messages"
          :key="i"
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
        <div v-if="streaming" class="mr-12">
          <div class="text-xs text-neutral-600 mb-1">assistant</div>
          <div class="border border-neutral-800 px-3 py-2 text-sm text-neutral-300">
            <span class="animate-pulse">Thinking...</span>
          </div>
        </div>
      </div>

      <!-- Input -->
      <div class="p-3 border-t border-neutral-800">
        <form @submit.prevent="sendMessage" class="flex gap-2">
          <input
            v-model="input"
            type="text"
            placeholder="Type a message..."
            :disabled="streaming"
            class="flex-1 px-3 py-2 bg-neutral-800 border border-neutral-700 text-sm text-white
                   placeholder-neutral-600 focus:outline-none focus:border-neutral-500 transition-colors"
          />
          <button
            type="submit"
            :disabled="streaming || !input.trim()"
            class="px-4 py-2 bg-white text-neutral-950 text-sm font-medium
                   hover:bg-neutral-200 disabled:opacity-40 transition-colors"
          >Send</button>
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
