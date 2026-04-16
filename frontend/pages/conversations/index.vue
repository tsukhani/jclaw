<script setup lang="ts">
import type { Agent, Conversation, Message } from '~/types/api'

const { confirm } = useConfirm()

const conversations = ref<Conversation[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)

const filterName = ref('')
const filterChannel = ref('')
const filterAgentId = ref('')
const filterPeer = ref('')

const { data: channelList } = await useFetch<string[]>('/api/conversations/channels', { default: () => [] })
const { data: agentList } = await useFetch<Agent[]>('/api/agents', { default: () => [] })

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const rangeStart = computed(() => total.value === 0 ? 0 : (page.value - 1) * pageSize + 1)
const rangeEnd = computed(() => Math.min(page.value * pageSize, total.value))

async function load() {
  loading.value = true
  try {
    const offset = (page.value - 1) * pageSize
    const params = new URLSearchParams()
    params.set('limit', String(pageSize))
    params.set('offset', String(offset))
    if (filterName.value.trim()) params.set('name', filterName.value.trim())
    if (filterChannel.value) params.set('channel', filterChannel.value)
    if (filterAgentId.value) params.set('agentId', filterAgentId.value)
    if (filterPeer.value.trim()) params.set('peer', filterPeer.value.trim())
    const res = await $fetch.raw<Conversation[]>(`/api/conversations?${params.toString()}`)
    conversations.value = res._data ?? []
    const headerTotal = res.headers.get('x-total-count')
    total.value = headerTotal ? parseInt(headerTotal, 10) : conversations.value.length
  } finally {
    loading.value = false
  }
}

await load()

// Debounced refetch on filter changes. Text inputs wait 300ms; selects refetch
// immediately. Any filter change resets to page 1 and clears selection so we
// don't carry stale IDs across the new result set.
let filterDebounce: ReturnType<typeof setTimeout> | null = null
function onFilterChange(debounce: boolean) {
  if (filterDebounce) clearTimeout(filterDebounce)
  const run = () => {
    page.value = 1
    selectedIds.value = new Set()
    load()
  }
  if (debounce) {
    filterDebounce = setTimeout(run, 300)
  } else {
    run()
  }
}

onUnmounted(() => { if (filterDebounce) clearTimeout(filterDebounce) })

function goto(p: number) {
  if (p < 1 || p > totalPages.value || p === page.value) return
  page.value = p
  selectedIds.value = new Set()
  load()
}

const selectedConvo = ref<Conversation | null>(null)
const messages = ref<Message[]>([])

const selectedIds = ref<Set<number>>(new Set())
const deletingBulk = ref(false)

const allSelected = computed(() => {
  if (!conversations.value.length) return false
  return conversations.value.every(c => selectedIds.value.has(c.id))
})

const someSelected = computed(() => selectedIds.value.size > 0 && !allSelected.value)

function toggleSelection(id: number) {
  const next = new Set(selectedIds.value)
  if (next.has(id)) next.delete(id); else next.add(id)
  selectedIds.value = next
}

function toggleSelectAll() {
  if (allSelected.value) {
    selectedIds.value = new Set()
  } else {
    selectedIds.value = new Set(conversations.value.map(c => c.id))
  }
}

async function deleteSelected() {
  if (!selectedIds.value.size) return
  const count = selectedIds.value.size
  const ok = await confirm({
    title: 'Delete conversations',
    message: `Delete ${count} conversation${count === 1 ? '' : 's'}? This cannot be undone.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  deletingBulk.value = true
  try {
    await $fetch('/api/conversations', {
      method: 'DELETE',
      body: { ids: Array.from(selectedIds.value) },
    })
    selectedIds.value = new Set()
    await load()
  } catch (e) {
    console.error('Failed to delete conversations:', e)
  } finally {
    deletingBulk.value = false
  }
}

const peekOpen = ref(false)

async function selectConversation(convo: Conversation) {
  selectedConvo.value = convo
  peekOpen.value = true
  messages.value = await $fetch<Message[]>(`/api/conversations/${convo.id}/messages`) ?? []
}

function closePeek() {
  peekOpen.value = false
  // Keep selectedConvo so re-opening preserves last selection
}

function peekNext() {
  if (!conversations.value.length || !selectedConvo.value) return
  const idx = conversations.value.findIndex(c => c.id === selectedConvo.value!.id)
  const next = conversations.value[Math.min(idx + 1, conversations.value.length - 1)]
  if (next && next.id !== selectedConvo.value.id) selectConversation(next)
}

function peekPrev() {
  if (!conversations.value.length || !selectedConvo.value) return
  const idx = conversations.value.findIndex(c => c.id === selectedConvo.value!.id)
  const prev = conversations.value[Math.max(idx - 1, 0)]
  if (prev && prev.id !== selectedConvo.value.id) selectConversation(prev)
}

function exportConversation() {
  if (!selectedConvo.value) return
  const c = selectedConvo.value
  const header = [
    `# ${c.preview || 'Conversation'}`,
    '',
    `- **Channel:** ${c.channelType}`,
    `- **Agent:** ${c.agentName}`,
    `- **Peer:** ${c.peerId || '—'}`,
    `- **Created:** ${new Date(c.createdAt).toLocaleString()}`,
    `- **Updated:** ${new Date(c.updatedAt).toLocaleString()}`,
    '',
    '---',
    '',
  ].join('\n')

  const body = messages.value.map((m: any) => {
    const ts = new Date(m.createdAt).toLocaleString()
    return `## ${m.role} — ${ts}\n\n${m.content || '(tool call)'}\n`
  }).join('\n')

  const blob = new Blob([header + body], { type: 'text/markdown' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `conversation-${c.id}.md`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">Conversations</h1>
      <button @click="deleteSelected"
              :disabled="!selectedIds.size || deletingBulk"
              class="px-3 py-1.5 bg-red-700 text-white text-xs font-medium hover:bg-red-600 disabled:opacity-40 disabled:cursor-not-allowed transition-colors">
        {{ deletingBulk ? 'Deleting...' : `Delete${selectedIds.size ? ' ' + selectedIds.size : ''}` }}
      </button>
    </div>

    <!-- Filter row -->
    <div class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-2 mb-3">
      <input v-model="filterName" @input="onFilterChange(true)"
             type="text" placeholder="Search name..."
             class="px-3 py-1.5 bg-surface-elevated border border-border text-sm text-fg-strong placeholder-fg-muted focus:outline-hidden focus:border-ring" />
      <select v-model="filterChannel" @change="onFilterChange(false)"
              class="px-3 py-1.5 bg-surface-elevated border border-border text-sm text-fg-strong focus:outline-hidden focus:border-ring">
        <option value="">All channels</option>
        <option v-for="ch in channelList" :key="ch" :value="ch">{{ ch }}</option>
      </select>
      <select v-model="filterAgentId" @change="onFilterChange(false)"
              class="px-3 py-1.5 bg-surface-elevated border border-border text-sm text-fg-strong focus:outline-hidden focus:border-ring">
        <option value="">All agents</option>
        <option v-for="a in agentList" :key="a.id" :value="a.id">{{ a.name }}</option>
      </select>
      <input v-model="filterPeer" @input="onFilterChange(true)"
             type="text" placeholder="Filter peer..."
             class="px-3 py-1.5 bg-surface-elevated border border-border text-sm text-fg-strong placeholder-fg-muted focus:outline-hidden focus:border-ring" />
    </div>

    <!-- List view -->
    <div class="bg-surface-elevated border border-border">
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th class="px-4 py-2.5 w-10">
              <input type="checkbox"
                     :checked="allSelected"
                     :indeterminate.prop="someSelected"
                     @change="toggleSelectAll"
                     :disabled="!conversations.length"
                     class="accent-red-500 align-middle"
                     title="Select all on this page" />
            </th>
            <th class="px-4 py-2.5 font-medium">Name</th>
            <th class="px-4 py-2.5 font-medium">Channel</th>
            <th class="px-4 py-2.5 font-medium">Agent</th>
            <th class="px-4 py-2.5 font-medium">Peer</th>
            <th class="px-4 py-2.5 font-medium">Messages</th>
            <th class="px-4 py-2.5 font-medium">Last Activity</th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <tr
            v-for="convo in conversations"
            :key="convo.id"
            @click="selectConversation(convo)"
            class="cursor-pointer transition-colors"
            :class="selectedConvo?.id === convo.id && peekOpen ? 'bg-muted' : 'hover:bg-muted'"
          >
            <td class="px-4 py-2.5 w-10" @click.stop>
              <input type="checkbox"
                     :checked="selectedIds.has(convo.id)"
                     @change="toggleSelection(convo.id)"
                     class="accent-red-500 align-middle" />
            </td>
            <td class="px-4 py-2.5 text-fg-primary max-w-xs truncate" :title="convo.preview || ''">
              <span v-if="convo.preview">{{ convo.preview }}</span>
              <span v-else class="text-fg-muted">—</span>
            </td>
            <td class="px-4 py-2.5 text-fg-primary">
              <span class="font-mono text-xs bg-muted px-1.5 py-0.5">{{ convo.channelType }}</span>
            </td>
            <td class="px-4 py-2.5 text-fg-primary">{{ convo.agentName }}</td>
            <td class="px-4 py-2.5 text-fg-muted font-mono text-xs">{{ convo.peerId || '—' }}</td>
            <td class="px-4 py-2.5 text-fg-muted">{{ convo.messageCount }}</td>
            <td class="px-4 py-2.5 text-fg-muted text-xs">{{ new Date(convo.updatedAt).toLocaleString() }}</td>
          </tr>
        </tbody>
      </table>
      <div v-if="!conversations?.length" class="px-4 py-8 text-center text-sm text-fg-muted">
        No conversations yet
      </div>
      <div
        v-if="total > 0"
        class="flex items-center justify-between px-4 py-2.5 border-t border-border text-xs text-fg-muted"
      >
        <span>Showing {{ rangeStart }}–{{ rangeEnd }} of {{ total }}</span>
        <div class="flex items-center gap-1">
          <button
            @click="goto(page - 1)"
            :disabled="page <= 1 || loading"
            class="px-2 py-1 border border-border rounded hover:text-fg-strong hover:border-ring disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            Prev
          </button>
          <span class="px-2">Page {{ page }} of {{ totalPages }}</span>
          <button
            @click="goto(page + 1)"
            :disabled="page >= totalPages || loading"
            class="px-2 py-1 border border-border rounded hover:text-fg-strong hover:border-ring disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          >
            Next
          </button>
        </div>
      </div>
    </div>

    <!-- PeekPanel for conversation detail -->
    <PeekPanel
      :open="peekOpen"
      @update:open="closePeek"
      @next="peekNext"
      @prev="peekPrev"
      :title="selectedConvo?.preview || 'Conversation'"
      :description="`${selectedConvo?.agentName || ''} · ${selectedConvo?.channelType || ''}`"
      :pop-out-route="selectedConvo ? `/conversations/${selectedConvo.id}` : undefined"
    >
      <template v-if="selectedConvo">
        <div class="flex items-center justify-between mb-4">
          <div class="flex flex-wrap gap-x-6 gap-y-1 text-xs text-fg-muted">
            <span>Channel: <strong class="text-fg-primary">{{ selectedConvo.channelType }}</strong></span>
            <span>Agent: <strong class="text-fg-primary">{{ selectedConvo.agentName }}</strong></span>
            <span>Peer: <strong class="text-fg-primary font-mono">{{ selectedConvo.peerId || '—' }}</strong></span>
            <span>Messages: <strong class="text-fg-primary">{{ selectedConvo.messageCount }}</strong></span>
          </div>
          <div class="flex items-center gap-1 shrink-0">
            <NuxtLink
              :to="`/chat?conversation=${selectedConvo.id}`"
              class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
              title="Open in Chat"
            >
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" /></svg>
            </NuxtLink>
            <button @click="exportConversation"
                    class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
                    title="Export conversation as Markdown">
              <svg class="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" /></svg>
            </button>
          </div>
        </div>
        <div class="space-y-3">
          <div
            v-for="msg in messages"
            :key="msg.id"
            :class="msg.role === 'user' ? 'ml-12' : msg.role === 'tool' ? 'ml-6' : ''"
          >
            <div class="flex items-center gap-2 mb-0.5">
              <span class="text-xs font-mono text-fg-muted">{{ msg.role }}</span>
              <span class="text-xs text-fg-muted">{{ new Date(msg.createdAt).toLocaleTimeString() }}</span>
            </div>
            <div class="bg-muted border border-border px-3 py-2 text-sm text-fg-primary whitespace-pre-wrap">{{ msg.content || '(tool call)' }}</div>
          </div>
        </div>
      </template>
    </PeekPanel>
  </div>
</template>
