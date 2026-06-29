<script setup lang="ts">
import { TrashIcon } from '@heroicons/vue/24/outline'
import type { Agent } from '~/types/api'

/**
 * JCLAW-40: agent memory admin. Lists a selected agent's stored memories with
 * importance and category; the operator can adjust importance inline (which
 * influences recall ranking and core-memory auto-load) and delete entries.
 * Read path is GET /api/agents/{id}/memories; mutations go through
 * PUT/DELETE /api/agents/{id}/memories/{memoryId}.
 */

interface MemoryDto {
  id: string
  text: string
  category: string | null
  importance: number
  createdAt: string | null
}

// Per-category badge colors, keyed by the six canonical MemoryCategory labels.
// Unknown/legacy categories fall back to a neutral gray.
const CATEGORY_CLASS: Record<string, string> = {
  core: 'bg-amber-100 text-amber-800 dark:bg-amber-900/40 dark:text-amber-300',
  fact: 'bg-sky-100 text-sky-800 dark:bg-sky-900/40 dark:text-sky-300',
  preference: 'bg-violet-100 text-violet-800 dark:bg-violet-900/40 dark:text-violet-300',
  decision: 'bg-emerald-100 text-emerald-800 dark:bg-emerald-900/40 dark:text-emerald-300',
  entity: 'bg-teal-100 text-teal-800 dark:bg-teal-900/40 dark:text-teal-300',
  lesson: 'bg-rose-100 text-rose-800 dark:bg-rose-900/40 dark:text-rose-300',
}

function categoryClass(cat: string | null): string {
  return CATEGORY_CLASS[cat ?? ''] ?? 'bg-gray-100 text-gray-700 dark:bg-gray-800 dark:text-gray-300'
}

// Top-level await so the agent list is populated before first render (and so
// mountSuspended resolves with data in tests). Default the selection to the
// first agent.
const { data: agents } = await useFetch<Agent[]>('/api/agents')
const selectedAgentId = ref<number | null>(agents.value?.[0]?.id ?? null)

const memories = ref<MemoryDto[]>([])
const loadingMemories = ref(false)
const loadError = ref<string | null>(null)

const { mutate } = useApiMutation()
const { confirm } = useConfirm()

watch(selectedAgentId, async (id) => {
  if (id === null) return
  loadingMemories.value = true
  loadError.value = null
  try {
    memories.value = await $fetch<MemoryDto[]>(`/api/agents/${id}/memories`)
  }
  catch (e: unknown) {
    loadError.value = e instanceof Error ? e.message : 'Failed to load memories'
    memories.value = []
  }
  finally {
    loadingMemories.value = false
  }
}, { immediate: true })

async function updateImportance(mem: MemoryDto, raw: string) {
  const id = selectedAgentId.value
  if (id === null) return
  const parsed = Number.parseFloat(raw)
  if (Number.isNaN(parsed)) return
  const value = Math.min(1, Math.max(0, parsed))
  const res = await mutate(`/api/agents/${id}/memories/${mem.id}`, {
    method: 'PUT',
    body: { importance: value },
  })
  if (res !== null) mem.importance = value
}

async function remove(mem: MemoryDto) {
  const id = selectedAgentId.value
  if (id === null) return
  const ok = await confirm({
    title: 'Delete memory',
    message: `Delete this memory?\n\n"${mem.text}"`,
    variant: 'danger',
    confirmText: 'Delete',
  })
  if (!ok) return
  const res = await mutate(`/api/agents/${id}/memories/${mem.id}`, { method: 'DELETE' })
  if (res !== null) memories.value = memories.value.filter(m => m.id !== mem.id)
}
</script>

<template>
  <div>
    <div class="mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        Memories
      </h1>
      <p class="mt-1 text-sm text-fg-muted">
        Durable facts each agent has captured. Adjust importance to influence recall ranking and
        core-memory auto-load, or delete entries.
      </p>
    </div>

    <label
      for="agent-select"
      class="mb-4 flex items-center gap-2 text-sm text-fg-muted"
    >
      <span>Agent</span>
      <select
        id="agent-select"
        v-model="selectedAgentId"
        class="border border-input bg-surface-elevated px-3 py-1.5 text-sm text-fg-strong"
      >
        <option
          v-for="a in agents ?? []"
          :key="a.id"
          :value="a.id"
        >
          {{ a.name }}
        </option>
      </select>
    </label>

    <p
      v-if="loadError"
      class="mb-4 text-sm text-red-400"
    >
      {{ loadError }}
    </p>

    <div
      v-if="loadingMemories"
      class="text-sm text-fg-muted"
    >
      Loading…
    </div>

    <p
      v-else-if="memories.length === 0"
      data-testid="memory-empty"
      class="border border-border bg-surface-elevated px-4 py-8 text-center text-sm text-fg-muted"
    >
      No memories captured for this agent yet.
    </p>

    <div
      v-else
      class="border border-border bg-surface-elevated"
    >
      <table
        data-testid="memory-table"
        class="w-full text-sm"
      >
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th class="px-4 py-2.5 font-medium">
              Memory
            </th>
            <th class="px-4 py-2.5 font-medium">
              Category
            </th>
            <th class="w-32 px-4 py-2.5 font-medium">
              Importance
            </th>
            <th class="px-4 py-2.5 font-medium">
              Created
            </th>
            <th class="w-12 px-4 py-2.5 text-right font-medium" />
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <tr
            v-for="mem in memories"
            :key="mem.id"
            data-testid="memory-row"
            class="align-top"
          >
            <td class="px-4 py-2.5 text-fg-primary">
              {{ mem.text }}
            </td>
            <td class="px-4 py-2.5">
              <span
                v-if="mem.category"
                class="inline-block px-2 py-0.5 text-xs font-medium"
                :class="categoryClass(mem.category)"
              >{{ mem.category }}</span>
            </td>
            <td class="px-4 py-2.5">
              <input
                type="number"
                min="0"
                max="1"
                step="0.05"
                :value="mem.importance"
                data-testid="importance-input"
                aria-label="Importance"
                class="w-20 border border-input bg-surface-elevated px-2 py-1 text-fg-strong"
                @change="updateImportance(mem, ($event.target as HTMLInputElement).value)"
              >
            </td>
            <td class="whitespace-nowrap px-4 py-2.5 text-fg-muted">
              {{ mem.createdAt ? formatDateTime(mem.createdAt) : '—' }}
            </td>
            <td class="px-4 py-2.5 text-right">
              <button
                type="button"
                title="Delete memory"
                data-testid="delete-memory"
                class="text-fg-muted transition-colors hover:text-red-400"
                @click="remove(mem)"
              >
                <TrashIcon class="h-5 w-5" />
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>
