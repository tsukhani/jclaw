<script setup lang="ts">
import type { Agent, Conversation, Message } from '~/types/api'
import type { Filter } from '~/components/FilterBar.vue'
import { h } from 'vue'
import type { ColumnDef } from '@tanstack/vue-table'

const { confirm } = useConfirm()

const conversations = ref<Conversation[]>([])
const total = ref(0)
const page = ref(1)
const pageSize = 20
const loading = ref(false)

// Active filters from FilterBar — maps filter keys to API params
const activeFilters = ref<Filter[]>([])

const { data: agentList } = await useFetch<Agent[]>('/api/agents', { default: () => [] })

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize)))
const rangeStart = computed(() => total.value === 0 ? 0 : (page.value - 1) * pageSize + 1)
const rangeEnd = computed(() => Math.min(page.value * pageSize, total.value))

function getFilterValue(key: string): string {
  return activeFilters.value.find(f => f.key === key)?.value ?? ''
}

async function load() {
  loading.value = true
  try {
    const offset = (page.value - 1) * pageSize
    const params = new URLSearchParams()
    params.set('limit', String(pageSize))
    params.set('offset', String(offset))
    const name = getFilterValue('name')
    const channel = getFilterValue('channel')
    const agent = getFilterValue('agent')
    const peer = getFilterValue('peer')
    if (name) params.set('name', name)
    if (channel) params.set('channel', channel)
    if (agent) {
      // Resolve agent name to ID
      const a = agentList.value?.find((ag: Agent) => ag.name.toLowerCase() === agent.toLowerCase())
      if (a) params.set('agentId', String(a.id))
    }
    if (peer) params.set('peer', peer)
    const res = await $fetch.raw<Conversation[]>(`/api/conversations?${params.toString()}`)
    conversations.value = res._data ?? []
    const headerTotal = res.headers.get('x-total-count')
    total.value = headerTotal ? Number.parseInt(headerTotal, 10) : conversations.value.length
  }
  finally {
    loading.value = false
  }
}

await load()

function onFiltersChanged(filters: Filter[]) {
  activeFilters.value = filters
  page.value = 1
  selectedIds.value = new Set()
  load()
}

function exportAllConversations() {
  const csv = [
    ['ID', 'Name', 'Channel', 'Agent', 'Peer', 'Messages', 'Created', 'Updated'].join(','),
    ...conversations.value.map(c =>
      [c.id, `"${(c.preview || '').replace(/"/g, '""')}"`, c.channelType, c.agentName, c.peerId || '', c.messageCount, c.createdAt, c.updatedAt].join(','),
    ),
  ].join('\n')
  const blob = new Blob([csv], { type: 'text/csv' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'conversations.csv'
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(url)
}

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
  if (next.has(id)) next.delete(id)
  else next.add(id)
  selectedIds.value = next
}

function toggleSelectAll() {
  if (allSelected.value) {
    selectedIds.value = new Set()
  }
  else {
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
  }
  catch (e) {
    console.error('Failed to delete conversations:', e)
  }
  finally {
    deletingBulk.value = false
  }
}

const deletingAll = ref(false)

/**
 * Build the filter object the server understands. Mirrors the param-set logic
 * in {@link load} but emits a JSON object suitable for the
 * {@code DELETE /api/conversations} body. Returns the resolved agentId
 * (number or undefined) so the description can echo a human-readable name
 * separately.
 */
function activeFilterPayload(): { channel?: string, agentId?: number, name?: string, peer?: string } {
  const out: { channel?: string, agentId?: number, name?: string, peer?: string } = {}
  const name = getFilterValue('name')
  const channel = getFilterValue('channel')
  const agent = getFilterValue('agent')
  const peer = getFilterValue('peer')
  if (name) out.name = name
  if (channel) out.channel = channel
  if (peer) out.peer = peer
  if (agent) {
    const a = agentList.value?.find((ag: Agent) => ag.name.toLowerCase() === agent.toLowerCase())
    if (a) out.agentId = a.id
  }
  return out
}

/** Human-readable echo of the active filters for the confirm message. */
function activeFilterDescription(): string {
  const parts: string[] = []
  for (const f of activeFilters.value) {
    if (f.value) parts.push(`${f.key}:${f.value}`)
  }
  return parts.join(' ')
}

async function deleteAll() {
  if (deletingAll.value || total.value <= 0) return
  const filterDesc = activeFilterDescription()
  const scope = filterDesc ? ` matching ${filterDesc}` : ''
  const ok = await confirm({
    title: 'Delete all conversations',
    message: `Delete all ${total.value} conversation${total.value === 1 ? '' : 's'}${scope}? This cannot be undone.`,
    confirmText: `Delete ${total.value}`,
    variant: 'danger',
    requireText: 'delete',
  })
  if (!ok) return
  deletingAll.value = true
  try {
    await $fetch('/api/conversations', {
      method: 'DELETE',
      body: { filter: activeFilterPayload() },
    })
    selectedIds.value = new Set()
    page.value = 1
    await load()
  }
  catch (e) {
    console.error('Failed to delete all conversations:', e)
  }
  finally {
    deletingAll.value = false
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

// ── DataTable column definitions ────────────────────────────────────────────
const columns: ColumnDef<Conversation, unknown>[] = [
  {
    id: 'select',
    header: () => h('input', {
      type: 'checkbox',
      checked: allSelected.value,
      indeterminate: someSelected.value,
      disabled: !conversations.value.length,
      class: 'accent-red-500 align-middle',
      title: 'Select all on this page',
      onChange: () => toggleSelectAll(),
    }),
    cell: ({ row }) => h('input', {
      type: 'checkbox',
      checked: selectedIds.value.has(row.original.id),
      class: 'accent-red-500 align-middle',
      onClick: (e: Event) => e.stopPropagation(),
      onChange: () => toggleSelection(row.original.id),
    }),
    enableSorting: false,
    size: 40,
  },
  {
    accessorKey: 'preview',
    header: 'Name',
    cell: ({ getValue }) => {
      const v = getValue() as string | null
      return v
        ? h('span', { class: 'text-fg-primary truncate max-w-xs block', title: v }, v)
        : h('span', { class: 'text-fg-muted' }, '—')
    },
  },
  {
    accessorKey: 'channelType',
    header: 'Channel',
    cell: ({ getValue }) => h('span', { class: 'font-mono text-xs bg-muted px-1.5 py-0.5 text-fg-primary' }, getValue() as string),
  },
  {
    accessorKey: 'agentName',
    header: 'Agent',
    cell: ({ getValue }) => h('span', { class: 'text-fg-primary' }, getValue() as string),
  },
  {
    accessorKey: 'peerId',
    header: 'Peer',
    cell: ({ getValue }) => h('span', { class: 'text-fg-muted font-mono text-xs' }, (getValue() as string) || '—'),
  },
  {
    accessorKey: 'messageCount',
    header: 'Messages',
    cell: ({ getValue }) => h('span', { class: 'text-fg-muted' }, String(getValue())),
  },
  {
    accessorKey: 'updatedAt',
    header: 'Last Activity',
    cell: ({ getValue }) => h('span', { class: 'text-fg-muted text-xs' }, new Date(getValue() as string).toLocaleString()),
  },
  {
    id: 'actions',
    header: '',
    enableSorting: false,
    size: 96,
    cell: ({ row }) => h('div', { class: 'flex items-center justify-end gap-1' }, [
      h('button', {
        type: 'button',
        class: 'p-1.5 text-fg-muted hover:text-fg-strong transition-colors',
        title: 'View details',
        onClick: (e: Event) => {
          e.stopPropagation()
          navigateTo(`/conversations/${row.original.id}`)
        },
      }, [
        h('svg', {
          class: 'w-4 h-4', fill: 'none', stroke: 'currentColor', viewBox: '0 0 24 24',
        }, [
          // Heroicons v2 "eye" outline — outer almond + inner pupil. Two
          // paths because the row-click default (open in /chat) covers the
          // chat affordance, so this slot is repurposed for "view this
          // conversation's detail page" — the read-only deep view that
          // doesn't load the agent runner.
          h('path', {
            'stroke-linecap': 'round',
            'stroke-linejoin': 'round',
            'stroke-width': '1.5',
            'd': 'M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z',
          }),
          h('path', {
            'stroke-linecap': 'round',
            'stroke-linejoin': 'round',
            'stroke-width': '1.5',
            'd': 'M15 12a3 3 0 11-6 0 3 3 0 016 0z',
          }),
        ]),
      ]),
      h('button', {
        type: 'button',
        class: 'p-1.5 text-fg-muted hover:text-fg-strong transition-colors',
        title: 'Quick preview',
        onClick: (e: Event) => {
          e.stopPropagation()
          selectConversation(row.original)
        },
      }, [
        h('svg', {
          class: 'w-4 h-4', fill: 'none', stroke: 'currentColor', viewBox: '0 0 24 24',
        }, [
          h('path', {
            'stroke-linecap': 'round',
            'stroke-linejoin': 'round',
            'stroke-width': '1.5',
            'd': 'M4 4h16v16H4z M14 4v16',
          }),
        ]),
      ]),
    ]),
  },
]
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        Conversations
      </h1>
      <div class="flex items-center gap-2">
        <button
          :disabled="!selectedIds.size || deletingBulk"
          class="px-3 py-1.5 bg-red-700 text-white text-xs font-medium hover:bg-red-600 disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          @click="deleteSelected"
        >
          {{ deletingBulk ? 'Deleting...' : `Delete${selectedIds.size ? ' ' + selectedIds.size : ''}` }}
        </button>
        <!--
          "Delete all" is a separate destructive surface from "Delete N"
          (selection-driven). It only appears when the matching count is
          greater than zero — there is nothing to delete in an empty list,
          and showing a disabled button there would be visual noise. When
          a filter is active, the button wipes only the matching subset;
          the confirm dialog echoes the filter so the user knows the scope
          before typing 'delete' to commit.
        -->
        <button
          v-if="total > 0"
          :disabled="deletingAll"
          class="px-3 py-1.5 border border-red-700 text-red-600 dark:text-red-400 text-xs font-medium hover:bg-red-700 hover:text-white disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
          @click="deleteAll"
        >
          {{ deletingAll ? 'Deleting...' : `Delete all${activeFilters.length ? ' matching' : ''}` }}
        </button>
      </div>
    </div>

    <!-- Filter bar -->
    <div class="mb-3">
      <FilterBar
        storage-key="conversations"
        placeholder="Filter... (e.g., agent:main channel:web peer:admin)"
        :filter-keys="['name', 'channel', 'agent', 'peer']"
        @update:filters="onFiltersChanged"
        @export="exportAllConversations"
      />
    </div>

    <!-- List view -->
    <div class="bg-surface-elevated border border-border">
      <DataTable
        :columns="columns"
        :data="conversations"
        :loading="loading"
        empty-message="No conversations yet"
        @row-click="(c: Conversation) => navigateTo(`/chat?conversation=${c.id}`)"
      />
      <div
        v-if="total > 0"
        class="flex items-center justify-between px-4 py-2.5 border-t border-border text-xs text-fg-muted"
      >
        <span>Showing {{ rangeStart }}–{{ rangeEnd }} of {{ total }}</span>
        <div class="flex items-center gap-1">
          <button
            :disabled="page <= 1 || loading"
            class="px-2 py-1 border border-border rounded hover:text-fg-strong hover:border-ring disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            @click="goto(page - 1)"
          >
            Prev
          </button>
          <span class="px-2">Page {{ page }} of {{ totalPages }}</span>
          <button
            :disabled="page >= totalPages || loading"
            class="px-2 py-1 border border-border rounded hover:text-fg-strong hover:border-ring disabled:opacity-40 disabled:cursor-not-allowed transition-colors"
            @click="goto(page + 1)"
          >
            Next
          </button>
        </div>
      </div>
    </div>

    <!-- PeekPanel for conversation detail -->
    <PeekPanel
      :open="peekOpen"
      :title="selectedConvo?.preview || 'Conversation'"
      :description="`${selectedConvo?.agentName || ''} · ${selectedConvo?.channelType || ''}`"
      @update:open="closePeek"
    >
      <template v-if="selectedConvo">
        <div class="flex flex-wrap gap-x-6 gap-y-1 text-xs text-fg-muted mb-4">
          <span>Channel: <strong class="text-fg-primary">{{ selectedConvo.channelType }}</strong></span>
          <span>Agent: <strong class="text-fg-primary">{{ selectedConvo.agentName }}</strong></span>
          <span>Peer: <strong class="text-fg-primary font-mono">{{ selectedConvo.peerId || '—' }}</strong></span>
          <span>Messages: <strong class="text-fg-primary">{{ selectedConvo.messageCount }}</strong></span>
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
            <div class="bg-muted border border-border px-3 py-2 text-sm text-fg-primary whitespace-pre-wrap">
              {{ msg.content || '(tool call)' }}
            </div>
          </div>
        </div>
      </template>
    </PeekPanel>
  </div>
</template>
