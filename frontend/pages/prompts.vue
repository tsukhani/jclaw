<script setup lang="ts">
// Prompts Library (JCLAW-813): a searchable, category-filtered grid of saved
// reusable prompts. Categories are a fixed taxonomy (server-provided); tags are
// the free-form axis. "Run" hands a prompt to the chat composer via ?compose=
// (the same mechanism the Apps page uses), so no chat.vue changes are needed.
import { ArrowDownTrayIcon, ArrowUpTrayIcon, MagnifyingGlassIcon, PlusIcon } from '@heroicons/vue/24/outline'
import PromptCard from '~/components/prompts/PromptCard.vue'
import PromptFormDialog from '~/components/prompts/PromptFormDialog.vue'
import type { Prompt, PromptCategory } from '~/types/api'

const { data: promptsData, pending, refresh } = useLazyFetch<Prompt[]>('/api/prompts', { default: () => [] })
const { data: categoriesData } = useLazyFetch<PromptCategory[]>('/api/prompts/categories', { default: () => [] })
const prompts = computed(() => promptsData.value ?? [])
const categories = computed(() => categoriesData.value ?? [])

// Client-side search + category filter over the (small, personal-scale) list.
const search = ref('')
const activeCategory = ref<string | null>(null) // null = All

const filtered = computed(() => {
  const q = search.value.trim().toLowerCase()
  return prompts.value.filter((p) => {
    if (activeCategory.value && p.category !== activeCategory.value) return false
    if (!q) return true
    return p.title.toLowerCase().includes(q)
      || p.content.toLowerCase().includes(q)
      || (p.tags ?? '').toLowerCase().includes(q)
  })
})

const { confirm } = useConfirm()

// ---- add / edit ----
const dialogOpen = ref(false)
const editing = ref<Prompt | null>(null)
function openCreate() {
  editing.value = null
  dialogOpen.value = true
}
function openEdit(p: Prompt) {
  editing.value = p
  dialogOpen.value = true
}

// ---- delete ----
async function remove(p: Prompt) {
  const ok = await confirm({
    title: 'Delete prompt',
    message: `Delete "${p.title}"? This can't be undone.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  await $fetch(`/api/prompts/${p.id}`, { method: 'DELETE' })
  await refresh()
}

// ---- run ----
function run(p: Prompt) {
  navigateTo({ path: '/chat', query: { compose: p.content } })
}

// ---- export ----
async function exportPrompts() {
  const doc = await $fetch('/api/prompts/export')
  const blob = new Blob([JSON.stringify(doc, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = 'jclaw-prompts.json'
  a.click()
  URL.revokeObjectURL(url)
}

// ---- import (file → inline merge/replace picker → POST) ----
const fileInput = ref<HTMLInputElement | null>(null)
const pendingImport = ref<{ prompts: unknown[] } | null>(null)
const importing = ref(false)

function triggerImport() {
  fileInput.value?.click()
}

async function onImportFile(e: Event) {
  const input = e.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = '' // allow re-selecting the same file
  if (!file) return
  let doc: unknown
  try {
    doc = JSON.parse(await file.text())
  }
  catch {
    await confirm({ title: 'Import failed', message: 'That file is not valid JSON.', confirmText: 'OK' })
    return
  }
  // Accept either the export document ({version, prompts:[…]}) or a bare array.
  const list = Array.isArray(doc) ? doc : ((doc as { prompts?: unknown[] })?.prompts ?? [])
  if (!Array.isArray(list) || list.length === 0) {
    await confirm({ title: 'Nothing to import', message: 'No prompts were found in that file.', confirmText: 'OK' })
    return
  }
  pendingImport.value = { prompts: list }
}

async function doImport(mode: 'merge' | 'replace') {
  if (!pendingImport.value) return
  importing.value = true
  try {
    await $fetch('/api/prompts/import', {
      method: 'POST',
      body: { mode, prompts: pendingImport.value.prompts },
    })
    pendingImport.value = null
    await refresh()
  }
  finally {
    importing.value = false
  }
}
</script>

<template>
  <div class="flex flex-col min-h-full">
    <h1 class="text-lg font-semibold text-fg-strong mb-2">
      Prompts
    </h1>
    <p class="text-sm text-fg-muted mb-6">
      Save, organize, and reuse your frequently-used prompts. Filter by category or search
      titles, text, and tags. Hit <span class="font-medium text-fg-strong">Run</span> to open one
      in the chat composer, ready to edit before sending.
    </p>

    <!-- Toolbar: create + import/export + search -->
    <div class="flex flex-wrap items-center gap-2 mb-4">
      <button
        type="button"
        data-testid="new-prompt-button"
        class="inline-flex items-center gap-2 px-3 py-2 text-sm font-medium text-white bg-emerald-600 rounded-lg hover:bg-emerald-700 transition-colors"
        @click="openCreate"
      >
        <PlusIcon
          class="w-4 h-4"
          aria-hidden="true"
        />
        New prompt
      </button>
      <button
        type="button"
        data-testid="export-prompts-button"
        :disabled="!prompts.length"
        class="inline-flex items-center gap-1.5 px-3 py-2 text-sm text-fg-primary border border-border rounded-lg hover:border-emerald-500 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        @click="exportPrompts"
      >
        <ArrowDownTrayIcon
          class="w-4 h-4"
          aria-hidden="true"
        />
        Export
      </button>
      <button
        type="button"
        data-testid="import-prompts-button"
        class="inline-flex items-center gap-1.5 px-3 py-2 text-sm text-fg-primary border border-border rounded-lg hover:border-emerald-500 transition-colors"
        @click="triggerImport"
      >
        <ArrowUpTrayIcon
          class="w-4 h-4"
          aria-hidden="true"
        />
        Import
      </button>
      <input
        ref="fileInput"
        type="file"
        accept="application/json"
        aria-label="Import prompts from a JSON file"
        class="hidden"
        data-testid="import-file-input"
        @change="onImportFile"
      >
      <label
        for="prompt-search"
        class="ml-auto flex items-center gap-2 px-3 py-2 rounded-lg border border-border bg-surface-elevated w-full sm:w-64"
      >
        <MagnifyingGlassIcon
          class="w-4 h-4 text-fg-muted shrink-0"
          aria-hidden="true"
        />
        <span class="sr-only">Search prompts</span>
        <input
          id="prompt-search"
          v-model="search"
          type="search"
          placeholder="Search prompts"
          data-testid="prompt-search"
          class="flex-1 min-w-0 bg-transparent text-sm text-fg-strong placeholder:text-fg-muted focus:outline-none"
          @keydown.escape="search = ''"
        >
      </label>
    </div>

    <!-- Import mode picker (inline; appears after a file is chosen) -->
    <div
      v-if="pendingImport"
      class="mb-4 flex flex-wrap items-center gap-3 px-4 py-3 border border-border rounded-lg bg-surface-elevated"
      data-testid="import-mode-picker"
    >
      <span class="text-sm text-fg-strong">
        Import {{ pendingImport.prompts.length }} prompt(s):
      </span>
      <button
        type="button"
        :disabled="importing"
        data-testid="import-merge"
        class="px-3 py-1.5 text-sm font-medium text-white bg-emerald-600 rounded hover:bg-emerald-700 disabled:opacity-50 transition-colors"
        @click="doImport('merge')"
      >
        Merge
      </button>
      <button
        type="button"
        :disabled="importing"
        data-testid="import-replace"
        class="px-3 py-1.5 text-sm font-medium text-white bg-red-600 rounded hover:bg-red-700 disabled:opacity-50 transition-colors"
        @click="doImport('replace')"
      >
        Replace all
      </button>
      <button
        type="button"
        :disabled="importing"
        class="px-2 py-1.5 text-sm text-fg-muted hover:text-fg-strong transition-colors"
        @click="pendingImport = null"
      >
        Cancel
      </button>
      <span class="text-xs text-fg-muted">Merge appends; Replace wipes your library first.</span>
    </div>

    <!-- Category filter pills -->
    <div class="flex flex-wrap gap-1.5 mb-5">
      <button
        type="button"
        data-testid="category-filter-all"
        :class="[
          'px-2.5 py-1 text-xs rounded-full border transition-colors',
          activeCategory === null
            ? 'bg-emerald-600 text-white border-emerald-600'
            : 'text-fg-muted border-border hover:border-emerald-500',
        ]"
        @click="activeCategory = null"
      >
        All
      </button>
      <button
        v-for="c in categories"
        :key="c.value"
        type="button"
        :data-testid="`category-filter-${c.value}`"
        :class="[
          'inline-flex items-center gap-1 px-2.5 py-1 text-xs rounded-full border transition-colors',
          activeCategory === c.value
            ? 'bg-emerald-600 text-white border-emerald-600'
            : 'text-fg-muted border-border hover:border-emerald-500',
        ]"
        @click="activeCategory = c.value"
      >
        <component
          :is="promptCategoryIcon(c.value)"
          class="w-3.5 h-3.5"
          aria-hidden="true"
        />
        {{ c.label }}
      </button>
    </div>

    <!-- States -->
    <div
      v-if="pending"
      class="flex items-center gap-2 text-sm text-fg-muted"
    >
      <span class="inline-block w-3.5 h-3.5 border-2 border-current border-t-transparent rounded-full animate-spin" />
      Loading prompts…
    </div>
    <div
      v-else-if="!prompts.length"
      class="text-sm text-fg-muted border border-dashed border-border rounded-lg px-4 py-10 text-center"
    >
      <p class="mb-3">
        Your prompt library is empty.
      </p>
      <button
        type="button"
        class="inline-flex items-center gap-2 px-3 py-2 text-sm font-medium text-white bg-emerald-600 rounded-lg hover:bg-emerald-700 transition-colors"
        @click="openCreate"
      >
        <PlusIcon
          class="w-4 h-4"
          aria-hidden="true"
        />
        Add your first prompt
      </button>
    </div>
    <div
      v-else-if="!filtered.length"
      class="text-sm text-fg-muted border border-dashed border-border rounded-lg px-4 py-8 text-center"
    >
      No prompts match your search or filter.
    </div>

    <!-- Card grid -->
    <div
      v-else
      class="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 gap-4"
    >
      <PromptCard
        v-for="p in filtered"
        :key="p.id"
        :prompt="p"
        @edit="openEdit(p)"
        @delete="remove(p)"
        @run="run(p)"
      />
    </div>

    <PromptFormDialog
      v-model:open="dialogOpen"
      :editing="editing"
      :categories="categories"
      @saved="refresh"
    />
  </div>
</template>
