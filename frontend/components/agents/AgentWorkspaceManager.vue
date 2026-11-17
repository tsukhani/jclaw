<script setup lang="ts">
/**
 * JCLAW-1247: an agent's workspace as an expandable tree with sizes and a total.
 *
 * A window onto the on-disk workspace, not a viewer: nothing here renders file content,
 * and the Standing Orders editor beside it stays the only write path. A sub-agent shows
 * its root agent's shared workspace, because that is the directory it writes into.
 */
import { ArchiveBoxArrowDownIcon, ArrowDownTrayIcon, CheckCircleIcon, ChevronRightIcon } from '@heroicons/vue/24/outline'
import { CheckIcon, DocumentIcon, DocumentTextIcon, FolderIcon, TrashIcon, XMarkIcon } from '@heroicons/vue/20/solid'
import type { ApiErrorDetails, WorkspaceEntry, WorkspaceListing } from '~/types/api'
import { formatSize } from '~/utils/format'
import { workspaceEntryStyle } from '~/utils/workspace-files'

const props = defineProps<{ agentId: number | null }>()

// (1) Data layer: the listing and its total, re-read whenever the agent changes.
// Shallow: a large workspace lists tens of thousands of entries, and a deep proxy over all of
// them turns every filter walk into a crawl. The tree is replaced whole on each load, never mutated.
const listing = shallowRef<WorkspaceListing | null>(null)
const loading = ref(false)
const error = ref<ApiErrorDetails | null>(null)
const latest = useLatestRequest()
// Declared here because the immediate watcher below resets it on an agent switch.
const openDirs = ref<Record<string, boolean>>({})

async function load(options?: { silent?: boolean }) {
  const id = props.agentId
  const token = latest.begin()
  if (!id) {
    listing.value = null
    return
  }
  // A background poll must not flash the header spinner or blank a visible error mid-flight.
  const silent = options?.silent === true
  if (!silent) {
    loading.value = true
    error.value = null
  }
  try {
    const data = await $fetch<WorkspaceListing>(`/api/agents/${id}/workspace-tree`)
    if (!latest.isCurrent(token)) return
    listing.value = data
    error.value = null
  }
  catch (e) {
    if (!latest.isCurrent(token)) return
    // A failed background poll keeps the last good tree on screen; the next tick retries, and a
    // failure that persists still surfaces on the next agent switch or reload.
    if (silent && listing.value) return
    error.value = apiErrorDetails(e)
  }
  finally {
    if (latest.isCurrent(token)) loading.value = false
  }
}

watch(() => props.agentId, () => {
  openDirs.value = {}
  void load()
}, { immediate: true })
defineExpose({ reload: load })

// Polled so a file a running agent writes appears without the operator clicking anything.
// The listing is an attributes-only walk, so this cadence is affordable.
const REFRESH_MS = 10_000
let timer: ReturnType<typeof setInterval> | null = null

// Skipped mid-delete: a listing read before the latest DELETE landed would put the entry back.
function poll() {
  if (!progress.value) void load({ silent: true })
}

function startPolling() {
  if (timer) return
  timer = setInterval(poll, REFRESH_MS)
}

function stopPolling() {
  if (!timer) return
  clearInterval(timer)
  timer = null
}

function onVisibility() {
  if (document.visibilityState !== 'visible') {
    stopPolling()
    return
  }
  // Catch up on what changed while the tab was away rather than waiting out a whole interval.
  poll()
  startPolling()
}

onMounted(() => {
  // The immediate watcher above already issued the first load.
  if (document.visibilityState === 'visible') startPolling()
  document.addEventListener('visibilitychange', onVisibility)
})

onBeforeUnmount(() => {
  stopPolling()
  document.removeEventListener('visibilitychange', onVisibility)
})

// (2) Tree rendering: the nested listing flattened to the rows currently expanded, or, while a
// filter is typed, to every entry whose name matches plus the folders that lead to it.
interface Row { entry: WorkspaceEntry, depth: number }

const filter = ref('')
// The query trails the input by a beat so a burst of keystrokes walks the tree once, not per key.
const query = ref('')
const FILTER_DEBOUNCE_MS = 150
let filterTimer: ReturnType<typeof setTimeout> | null = null
watch(filter, (value) => {
  if (filterTimer) clearTimeout(filterTimer)
  const next = value.trim().toLowerCase()
  if (!next) {
    query.value = ''
    return
  }
  filterTimer = setTimeout(() => {
    query.value = next
  }, FILTER_DEBOUNCE_MS)
})
onBeforeUnmount(() => {
  if (filterTimer) clearTimeout(filterTimer)
})
watch(() => props.agentId, () => {
  filter.value = ''
  query.value = ''
})

// The page renders rows as plain DOM, so a bound keeps a one-letter filter over a huge workspace
// from freezing the tab; the notice tells the operator to narrow the filter or collapse folders.
const MAX_ROWS = 1000

function isOpen(path: string) {
  return openDirs.value[path] === true
}

function toggle(path: string) {
  openDirs.value[path] = !isOpen(path)
}

// A filtered folder is shown open, since collapsing it would hide the very match that keeps it visible.
function isShownOpen(path: string) {
  return query.value ? true : isOpen(path)
}

// Appends the matching rows under `entries` to `out` and returns how many it added; a folder is
// kept when it matches or anything below it does. Loops rather than spreads: a spread of a
// large subtree's rows is quadratic and can overflow the call stack.
function appendMatching(entries: WorkspaceEntry[], depth: number, q: string, out: Row[]): number {
  let added = 0
  for (const entry of entries) {
    const mark = out.length
    out.push({ entry, depth })
    const below = entry.kind === 'dir' && entry.children ? appendMatching(entry.children, depth + 1, q, out) : 0
    if (below === 0 && !entry.name.toLowerCase().includes(q)) {
      out.length = mark
      continue
    }
    added += 1 + below
  }
  return added
}

const allRows = computed<Row[]>(() => {
  const entries = listing.value?.entries ?? []
  const out: Row[] = []
  if (query.value) {
    appendMatching(entries, 0, query.value, out)
    return out
  }
  const walk = (list: WorkspaceEntry[], depth: number) => {
    for (const entry of list) {
      out.push({ entry, depth })
      if (entry.kind === 'dir' && entry.children && isOpen(entry.path)) walk(entry.children, depth + 1)
    }
  }
  walk(entries, 0)
  return out
})

const rows = computed<Row[]>(() => allRows.value.length > MAX_ROWS ? allRows.value.slice(0, MAX_ROWS) : allRows.value)
const hiddenRows = computed(() => Math.max(0, allRows.value.length - MAX_ROWS))

// Colour and icon move together so the kind is readable without the colour.
const STYLE_CLASS = {
  dir: 'text-sky-700 dark:text-sky-400',
  text: 'text-emerald-700 dark:text-emerald-400',
  binary: 'text-amber-700 dark:text-amber-400',
} as const

function styleOf(entry: WorkspaceEntry) {
  return workspaceEntryStyle(entry.kind, entry.name)
}

// (3) Per-row actions: download and delete.

// A link rather than a fetch, so the browser owns the save dialog and honours the filename the
// server sends; each segment is encoded so a '#' or '?' in a name survives the round trip.
function downloadHref(entry: WorkspaceEntry) {
  const encoded = entry.path.split('/').map(encodeURIComponent).join('/')
  return `/api/agents/${props.agentId}/workspace-download/${encoded}`
}

// The archive's name comes from the server's Content-Disposition, which carries the agent name
// this component never sees.
const backupHref = computed(() => `/api/agents/${props.agentId}/workspace-backup`)

const { mutate, errorDetails: deleteError } = useApiMutation()
const pendingDelete = ref<string | null>(null)

// A folder delete takes the whole subtree, so the label says so rather than leaving it to the icon.
function deleteLabel(entry: WorkspaceEntry) {
  return entry.kind === 'dir'
    ? `Delete folder ${entry.name} and everything in it`
    : `Delete ${entry.name}`
}

function deleteUrl(id: number, path: string) {
  return `/api/agents/${id}/workspace-tree/${path.split('/').map(encodeURIComponent).join('/')}`
}

// The run in flight or the last one, entry by entry, so the operator sees each one go and what is left.
type RunState = 'queued' | 'deleting' | 'deleted' | 'failed'
interface DeleteRun { seq: number, active: boolean, items: Array<{ entry: WorkspaceEntry, state: RunState }> }
const run = shallowRef<DeleteRun | null>(null)
let runSeq = 0
const runLog = ref<HTMLElement | null>(null)

const progress = computed(() => {
  const current = run.value
  if (!current?.active) return null
  return {
    done: current.items.filter(item => item.state === 'deleted').length,
    total: current.items.length,
    current: current.items.find(item => item.state === 'deleting')?.entry.path ?? '',
  }
})

const notice = computed(() => {
  const finished = run.value
  if (!finished || finished.active) return null
  const deleted = finished.items.filter(item => item.state === 'deleted').map(item => item.entry)
  const failed = finished.items.find(item => item.state === 'failed')
  // With nothing deleted, the error alert alone says what happened.
  if (failed) return deleted.length ? `Deleted ${deleted.length} of ${finished.items.length}; stopped at ${displayPath(failed.entry)}.` : null
  return deleted.length === 1 ? `Deleted ${displayPath(deleted[0]!)}.` : `Deleted ${countPhrase(deleted)}.`
})

const RUN_STATE_LABEL: Record<RunState, string> = { queued: 'waiting', deleting: 'deleting…', deleted: 'deleted', failed: 'failed' }

function runStateLabel(state: RunState, active: boolean) {
  return state === 'queued' && !active ? 'not deleted' : RUN_STATE_LABEL[state]
}

// Keeps the entry in flight in view inside the log's own scroll box, without scrolling the page.
function scrollLogToCurrent() {
  const box = runLog.value
  const current = box?.querySelector<HTMLElement>('[data-state="deleting"]')
  if (box && current) box.scrollTop = current.offsetTop - box.clientHeight / 2
}

// Drops `path` and its size from every folder above it and from the total, so the list shows the
// delete at once; the re-read after the run then replaces it with the server's view.
function withoutEntry(tree: WorkspaceListing, path: string): WorkspaceListing {
  let removed = 0
  const prune = (list: WorkspaceEntry[]): WorkspaceEntry[] => {
    const out: WorkspaceEntry[] = []
    for (const entry of list) {
      if (entry.path === path) {
        removed = entry.size
        continue
      }
      if (entry.children && path.startsWith(`${entry.path}/`)) {
        const children = prune(entry.children)
        out.push({ ...entry, children, size: entry.size - removed })
      }
      else {
        out.push(entry)
      }
    }
    return out
  }
  const entries = prune(tree.entries)
  return { ...tree, entries, total: tree.total - removed }
}

function plural(count: number, noun: string) {
  return `${count} ${noun}${count === 1 ? '' : 's'}`
}

function countPhrase(entries: WorkspaceEntry[]) {
  const folders = entries.filter(entry => entry.kind === 'dir').length
  const files = entries.length - folders
  return [files ? plural(files, 'file') : '', folders ? plural(folders, 'folder') : ''].filter(Boolean).join(' and ')
}

function displayPath(entry: WorkspaceEntry) {
  return entry.kind === 'dir' ? `${entry.path}/` : entry.path
}

// One at a time, stopping at the first refusal, so the alert names the entry that failed and
// everything after it stays ticked for a retry.
async function runDeletes(id: number, entries: WorkspaceEntry[]) {
  const seq = ++runSeq
  run.value = { seq, active: true, items: entries.map(entry => ({ entry, state: 'queued' as RunState })) }
  // An agent switch drops the run, and its remaining requests then mark nothing.
  const mark = (index: number, state: RunState) => {
    const current = run.value
    if (current?.seq !== seq) return
    run.value = { ...current, items: current.items.map((item, i) => i === index ? { ...item, state } : item) }
  }
  const deleted: WorkspaceEntry[] = []
  for (const [index, entry] of entries.entries()) {
    mark(index, 'deleting')
    void nextTick(scrollLogToCurrent)
    if (await mutate(deleteUrl(id, entry.path), { method: 'DELETE' }) === null) {
      mark(index, 'failed')
      break
    }
    mark(index, 'deleted')
    deleted.push(entry)
    if (props.agentId !== id) continue
    if (listing.value) listing.value = withoutEntry(listing.value, entry.path)
    if (selected.value.has(entry.path)) untick(entry.path)
  }
  if (run.value?.seq === seq) run.value = { ...run.value, active: false }
  if (props.agentId !== id) return
  // Before the re-read, which takes seconds on a large workspace: the run is over now.
  if (deleted.length === entries.length) stopSelecting()
  if (deleted.length) await load()
}

function dismissRun() {
  if (!run.value?.active) run.value = null
}

async function confirmDelete(entry: WorkspaceEntry) {
  const id = props.agentId
  if (!id) return
  await runDeletes(id, [entry])
  pendingDelete.value = null
}

// (4) Bulk delete: tick entries, then delete them in one confirmed run.
const { confirm } = useConfirm()
const selectAllId = useId()
const selecting = ref(false)
// Keyed by path, and never holding an entry under a held folder: the folder's DELETE takes it,
// and a second DELETE for it would 404 and stop the run. So the size is the count of requests.
const selected = shallowRef(new Map<string, WorkspaceEntry>())

function hasHeldAncestor(path: string, held: Map<string, WorkspaceEntry>) {
  for (let i = path.indexOf('/'); i !== -1; i = path.indexOf('/', i + 1)) {
    if (held.has(path.slice(0, i))) return true
  }
  return false
}

function isCovered(path: string) {
  return hasHeldAncestor(path, selected.value)
}

function isTicked(path: string) {
  return selected.value.has(path) || isCovered(path)
}

function startSelecting() {
  pendingDelete.value = null
  run.value = null
  selected.value = new Map()
  selecting.value = true
}

function stopSelecting() {
  selecting.value = false
  selected.value = new Map()
}

function withEntries(entries: WorkspaceEntry[]) {
  const next = new Map(selected.value)
  for (const entry of entries) next.set(entry.path, entry)
  for (const path of next.keys()) {
    if (hasHeldAncestor(path, next)) next.delete(path)
  }
  return next
}

// The operator's own change to the ticks retires a finished run's log, bringing the tally back.
function setSelection(next: Map<string, WorkspaceEntry>) {
  selected.value = next
  dismissRun()
}

function untick(path: string) {
  const next = new Map(selected.value)
  next.delete(path)
  setSelection(next)
}

function toggleEntry(entry: WorkspaceEntry) {
  if (selected.value.has(entry.path)) untick(entry.path)
  else setSelection(withEntries([entry]))
}

// Under a filter, only rows that match by their own name: a folder shown just to lead to a match
// would take every file inside it that does not match.
const selectableRows = computed(() => rows.value.filter(row =>
  !row.entry.protected && (!query.value || row.entry.name.toLowerCase().includes(query.value))))
const allTicked = computed(() =>
  selectableRows.value.length > 0 && selectableRows.value.every(row => isTicked(row.entry.path)))
const someTicked = computed(() => !allTicked.value && selectableRows.value.some(row => isTicked(row.entry.path)))

function toggleAll() {
  setSelection(allTicked.value ? new Map() : withEntries(selectableRows.value.map(row => row.entry)))
}

// Every tick, listed: one can sit in a collapsed folder or outside the filter, and still goes.
const shownPaths = computed(() => new Set(rows.value.map(row => row.entry.path)))
const tally = computed(() => [...selected.value.values()].map(entry => ({ entry, shown: shownPaths.value.has(entry.path) })))
const hiddenTicks = computed(() => tally.value.filter(item => !item.shown).length)

// What leaves the tree, through a delete or another writer, leaves the selection. A run prunes
// its own deletes as it goes, which spares a walk of the whole tree per entry.
watch(listing, (next) => {
  if (progress.value || !selected.value.size) return
  const present = new Set<string>()
  const walk = (list: WorkspaceEntry[]) => {
    for (const entry of list) {
      present.add(entry.path)
      if (entry.children) walk(entry.children)
    }
  }
  walk(next?.entries ?? [])
  const kept = new Map([...selected.value].filter(([path]) => present.has(path)))
  if (kept.size !== selected.value.size) selected.value = kept
})

const SUMMARY_NAMES = 10

function deleteSummary(entries: WorkspaceEntry[]) {
  const folders = entries.filter(entry => entry.kind === 'dir').length
  const files = entries.length - folders
  const parts: string[] = []
  if (files) parts.push(plural(files, 'file'))
  if (folders) parts.push(`${plural(folders, 'folder')} with everything in ${folders === 1 ? 'it' : 'them'}`)
  const names = entries.slice(0, SUMMARY_NAMES).map(displayPath)
  if (entries.length > SUMMARY_NAMES) names.push(`…and ${entries.length - SUMMARY_NAMES} more`)
  return `Permanently delete ${parts.join(' and ')}? This cannot be undone.\n\n${names.join('\n')}`
}

async function deleteSelected() {
  const id = props.agentId
  const entries = [...selected.value.values()]
  if (!id || !entries.length) return
  const ok = await confirm({
    title: 'Delete from the workspace',
    message: deleteSummary(entries),
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  await runDeletes(id, entries)
}

watch(() => props.agentId, () => {
  pendingDelete.value = null
  run.value = null
  stopSelecting()
})
</script>

<template>
  <div
    v-if="agentId"
    class="bg-surface-elevated border border-border"
    data-testid="workspace-manager"
  >
    <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="text-sm font-medium text-fg-strong">Workspace</span>
        <span
          v-if="loading"
          class="text-xs text-fg-muted"
        >Loading…</span>
      </div>
      <div class="flex items-center gap-3">
        <template v-if="listing && selecting">
          <button
            type="button"
            class="px-2 py-0.5 border border-input bg-transparent text-xs text-fg-muted hover:text-fg-strong disabled:opacity-40 transition-colors"
            :disabled="!!progress"
            data-testid="workspace-select-cancel"
            @click="stopSelecting"
          >
            Cancel
          </button>
          <button
            type="button"
            class="px-2 py-0.5 border-0 bg-red-700 text-white text-xs font-medium hover:bg-red-600 disabled:opacity-40 transition-colors"
            :disabled="!selected.size || !!progress"
            data-testid="workspace-delete-selected"
            @click="deleteSelected"
          >
            {{ progress ? 'Deleting…' : `Delete ${selected.size || ''}`.trim() }}
          </button>
        </template>
        <template v-else-if="listing">
          <button
            type="button"
            class="inline-flex items-center gap-1 border-0 bg-transparent p-0 text-xs text-fg-muted hover:text-fg-strong disabled:opacity-40 transition-colors"
            title="Tick several files and folders, then delete them together"
            :disabled="!!progress"
            data-testid="workspace-select"
            @click="startSelecting"
          >
            <CheckCircleIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
            Select
          </button>
          <a
            :href="backupHref"
            download
            class="inline-flex items-center gap-1 text-xs text-fg-muted hover:text-fg-strong transition-colors"
            title="Download the whole workspace as a zip, Standing Orders included"
            data-testid="workspace-backup"
          >
            <ArchiveBoxArrowDownIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
            Back up
          </a>
        </template>
        <span
          v-if="listing"
          class="text-xs font-mono tabular-nums text-fg-muted"
          data-testid="workspace-total"
        >Total {{ formatSize(listing.total) }}</span>
      </div>
    </div>

    <p class="px-4 py-2 text-xs text-fg-muted">
      Everything on disk under this agent's workspace. The Standing Orders files are listed
      but protected; edit them in the editor above.
    </p>

    <div
      v-if="listing"
      class="px-4 pb-2"
    >
      <div class="relative">
        <input
          v-model="filter"
          type="search"
          aria-label="Filter files and folders"
          class="w-full rounded border border-border bg-transparent px-2.5 py-1.5 pr-8 text-sm text-fg-primary placeholder:text-fg-muted [&::-webkit-search-cancel-button]:appearance-none"
          placeholder="Filter files and folders…"
          autocomplete="off"
          data-testid="workspace-filter"
        >
        <button
          v-if="filter"
          type="button"
          class="absolute right-1.5 top-1/2 -translate-y-1/2 border-0 bg-transparent p-1 text-fg-muted hover:text-fg-strong"
          aria-label="Clear filter"
          data-testid="workspace-filter-clear"
          @click="filter = ''"
        >
          <XMarkIcon
            class="w-4 h-4"
            aria-hidden="true"
          />
        </button>
      </div>
      <label
        v-if="selecting"
        :for="selectAllId"
        class="mt-2 flex w-fit items-center gap-2 text-xs text-fg-muted cursor-pointer"
      >
        <input
          :id="selectAllId"
          type="checkbox"
          class="w-3.5 h-3.5 accent-red-500 cursor-pointer"
          :checked="allTicked"
          :indeterminate="someTicked"
          :disabled="!selectableRows.length || !!progress"
          data-testid="workspace-select-all"
          @change="toggleAll"
        >
        {{ query ? 'Select all matching' : 'Select all' }}
      </label>
    </div>

    <div
      class="px-4 text-xs text-fg-muted"
      :class="run ? 'pb-2' : ''"
    >
      <div class="flex items-start gap-2">
        <div
          role="status"
          class="min-w-0 flex-1"
          data-testid="workspace-delete-status"
        >
          <p
            v-if="progress"
            class="flex items-center gap-2 min-w-0"
          >
            <span
              class="inline-block w-3 h-3 shrink-0 border-2 border-current border-t-transparent rounded-full animate-spin"
              aria-hidden="true"
            />
            <span class="truncate">
              {{ progress.total > 1 ? `Deleting ${progress.done + 1} of ${progress.total}:` : 'Deleting' }}
              <span class="font-mono">{{ progress.current }}</span>
            </span>
          </p>
          <p v-else-if="notice">
            {{ notice }}
          </p>
        </div>
        <button
          v-if="run && !run.active"
          type="button"
          class="shrink-0 border-0 bg-transparent p-0 text-xs text-fg-muted underline hover:text-fg-strong"
          data-testid="workspace-delete-dismiss"
          @click="dismissRun"
        >
          Dismiss
        </button>
      </div>
      <div
        v-if="progress && progress.total > 1"
        class="mt-1.5 h-1 bg-muted overflow-hidden"
        aria-hidden="true"
      >
        <div
          class="h-full bg-red-600 transition-[width]"
          :style="{ width: `${(progress.done / progress.total) * 100}%` }"
        />
      </div>
    </div>

    <!-- The run's log: each entry with its state, kept after the run so the operator can check it. -->
    <ul
      v-if="run && run.items.length > 1"
      ref="runLog"
      class="relative mx-4 mb-2 max-h-40 overflow-y-auto border border-border py-1"
      data-testid="workspace-delete-log"
    >
      <li
        v-for="item in run.items"
        :key="item.entry.path"
        class="flex items-center gap-2 px-2 py-0.5 text-xs"
        :data-state="item.state"
        :data-testid="`ws-log-${item.entry.path}`"
      >
        <span
          v-if="item.state === 'deleting'"
          class="inline-block w-3 h-3 shrink-0 border-2 border-current border-t-transparent rounded-full animate-spin text-fg-muted"
          aria-hidden="true"
        />
        <CheckIcon
          v-else-if="item.state === 'deleted'"
          class="w-3 h-3 shrink-0 text-emerald-700 dark:text-emerald-400"
          aria-hidden="true"
        />
        <XMarkIcon
          v-else-if="item.state === 'failed'"
          class="w-3 h-3 shrink-0 text-danger"
          aria-hidden="true"
        />
        <span
          v-else
          class="w-3 shrink-0"
          aria-hidden="true"
        />
        <span
          class="truncate font-mono"
          :class="item.state === 'deleted' ? 'text-fg-muted line-through' : 'text-fg-primary'"
        >{{ displayPath(item.entry) }}</span>
        <span
          class="ml-auto shrink-0"
          :class="item.state === 'failed' ? 'text-danger' : 'text-fg-muted'"
          data-role="state"
        >{{ runStateLabel(item.state, run.active) }}</span>
      </li>
    </ul>
    <div
      v-else-if="selecting && selected.size"
      class="mx-4 mb-2 border border-border"
      data-testid="workspace-selection"
    >
      <!-- Every tick, listed, so one hidden by the filter or a collapsed folder is never a surprise. -->
      <p
        class="px-2 pt-1.5 pb-1 text-xs text-fg-muted"
        data-testid="workspace-selection-summary"
      >
        {{ selected.size }} selected{{ hiddenTicks ? `, ${hiddenTicks} hidden from the list below` : '' }}
      </p>
      <ul class="max-h-40 overflow-y-auto pb-1">
        <li
          v-for="item in tally"
          :key="item.entry.path"
          class="flex items-center gap-2 px-2 py-0.5 text-xs"
          :data-testid="`ws-tally-${item.entry.path}`"
        >
          <span class="truncate font-mono text-fg-primary">{{ displayPath(item.entry) }}</span>
          <span
            v-if="item.entry.kind === 'dir'"
            class="shrink-0 text-fg-muted"
          >with everything in it</span>
          <span
            v-if="!item.shown"
            class="shrink-0 rounded px-1 bg-muted text-[10px] uppercase tracking-wide text-fg-muted"
            title="Filtered out, or inside a collapsed folder"
          >hidden</span>
          <button
            type="button"
            class="ml-auto shrink-0 border-0 bg-transparent p-0.5 text-fg-muted hover:text-fg-strong disabled:opacity-40"
            :aria-label="`Untick ${displayPath(item.entry)}`"
            :disabled="!!progress"
            :data-testid="`ws-untick-${item.entry.path}`"
            @click="untick(item.entry.path)"
          >
            <XMarkIcon
              class="w-3.5 h-3.5"
              aria-hidden="true"
            />
          </button>
        </li>
      </ul>
    </div>

    <ApiErrorAlert
      v-if="deleteError"
      :error="deleteError"
      class="px-4 pb-3"
    />

    <ApiErrorAlert
      v-if="error"
      :error="error"
      class="px-4 pb-3"
    />

    <p
      v-else-if="listing && rows.length === 0"
      class="px-4 pb-3 text-sm text-fg-muted italic"
      data-testid="workspace-empty"
    >
      {{ query ? 'Nothing matches the filter.' : 'The workspace is empty.' }}
    </p>

    <div
      v-else
      class="pb-2"
    >
      <!-- A bounded viewport: the page keeps its shape however large the workspace grows. -->
      <div
        class="max-h-[32rem] overflow-y-auto border-t border-border"
        data-testid="workspace-scroll"
      >
        <div
          v-for="row in rows"
          :key="row.entry.path"
          class="flex max-sm:flex-wrap items-center gap-2 pr-3 py-1 hover:bg-muted/50 transition-colors"
          :class="progress?.current === row.entry.path ? 'opacity-50' : ''"
          :style="{ paddingLeft: `${12 + row.depth * 16}px` }"
          :data-testid="`ws-row-${row.entry.path}`"
        >
          <template v-if="selecting">
            <input
              v-if="!row.entry.protected"
              type="checkbox"
              class="w-3.5 h-3.5 shrink-0 accent-red-500 cursor-pointer disabled:cursor-default"
              :checked="isTicked(row.entry.path)"
              :disabled="!!progress || isCovered(row.entry.path)"
              :title="isCovered(row.entry.path) ? 'Deleted with its folder' : undefined"
              :aria-label="`Select ${row.entry.name}`"
              :data-testid="`ws-select-${row.entry.path}`"
              @change="toggleEntry(row.entry)"
            >
            <span
              v-else
              class="w-3.5 shrink-0"
              aria-hidden="true"
            />
          </template>
          <button
            v-if="row.entry.kind === 'dir'"
            type="button"
            class="flex items-center gap-1.5 min-w-0 max-sm:min-w-36 flex-1 text-left text-fg-primary bg-transparent border-0"
            :aria-expanded="isShownOpen(row.entry.path)"
            @click="toggle(row.entry.path)"
          >
            <ChevronRightIcon
              class="w-3 h-3 shrink-0 text-fg-muted transition-transform"
              :class="isShownOpen(row.entry.path) ? 'rotate-90' : ''"
              aria-hidden="true"
            />
            <FolderIcon
              class="w-4 h-4 shrink-0"
              :class="STYLE_CLASS.dir"
              aria-hidden="true"
            />
            <span
              class="truncate text-sm font-mono"
              :class="STYLE_CLASS.dir"
              data-kind="dir"
            >{{ row.entry.name }}</span>
          </button>
          <span
            v-else
            class="flex items-center gap-1.5 min-w-0 max-sm:min-w-36 flex-1 text-fg-primary"
          >
            <span
              class="w-3 shrink-0"
              aria-hidden="true"
            />
            <component
              :is="styleOf(row.entry) === 'text' ? DocumentTextIcon : DocumentIcon"
              class="w-4 h-4 shrink-0"
              :class="STYLE_CLASS[styleOf(row.entry)]"
              aria-hidden="true"
            />
            <span
              class="truncate text-sm font-mono"
              :class="STYLE_CLASS[styleOf(row.entry)]"
              :data-kind="styleOf(row.entry)"
            >{{ row.entry.name }}</span>
          </span>

          <span
            v-if="row.entry.protected"
            class="shrink-0 rounded px-1.5 py-0.5 text-[10px] uppercase tracking-wide bg-muted text-fg-muted"
            title="A Standing Orders file: listed, never deleted"
            :data-testid="`ws-protected-${row.entry.path}`"
          >protected</span>

          <span class="shrink-0 text-xs font-mono tabular-nums text-fg-muted">{{ formatSize(row.entry.size) }}</span>

          <!-- (3) Per-row actions area: download, then delete. -->
          <span
            class="flex items-center gap-1 shrink-0"
            :data-testid="`ws-actions-${row.entry.path}`"
          >
            <a
              :href="downloadHref(row.entry)"
              download
              class="w-6 h-6 inline-flex items-center justify-center text-fg-muted hover:text-fg-strong transition-colors"
              :title="row.entry.kind === 'dir' ? 'Download this folder as a zip' : 'Download this file'"
              :aria-label="`Download ${row.entry.name}`"
              :data-testid="`ws-download-${row.entry.path}`"
            >
              <ArrowDownTrayIcon
                class="w-4 h-4"
                aria-hidden="true"
              />
            </a>
            <span
              v-if="progress?.current === row.entry.path"
              class="inline-flex items-center gap-1 text-xs text-fg-muted"
              :data-testid="`ws-deleting-${row.entry.path}`"
            >
              <span
                class="inline-block w-3 h-3 border-2 border-current border-t-transparent rounded-full animate-spin"
                aria-hidden="true"
              />
              Deleting…
            </span>
            <template v-else-if="!row.entry.protected && !selecting">
              <template v-if="pendingDelete === row.entry.path">
                <button
                  type="button"
                  class="rounded border border-border bg-transparent px-1.5 py-0.5 text-xs text-danger"
                  :aria-label="`Confirm: ${deleteLabel(row.entry)}`"
                  :data-testid="`ws-delete-confirm-${row.entry.path}`"
                  @click="confirmDelete(row.entry)"
                >Confirm</button>
                <button
                  type="button"
                  class="rounded border border-border bg-transparent px-1.5 py-0.5 text-xs text-fg-muted"
                  :aria-label="`Cancel: ${deleteLabel(row.entry)}`"
                  :data-testid="`ws-delete-cancel-${row.entry.path}`"
                  @click="pendingDelete = null"
                >Cancel</button>
              </template>
              <button
                v-else
                type="button"
                class="border-0 bg-transparent p-0.5 text-fg-muted hover:text-danger disabled:opacity-40"
                :aria-label="deleteLabel(row.entry)"
                :title="deleteLabel(row.entry)"
                :disabled="!!progress"
                :data-testid="`ws-delete-${row.entry.path}`"
                @click="pendingDelete = row.entry.path"
              >
                <TrashIcon
                  class="w-4 h-4"
                  aria-hidden="true"
                />
              </button>
            </template>
          </span>
        </div>
      </div>
      <p
        v-if="hiddenRows > 0"
        class="px-4 pt-2 text-xs text-fg-muted"
        data-testid="workspace-row-cap"
      >
        Showing the first {{ MAX_ROWS.toLocaleString() }} of {{ allRows.length.toLocaleString() }} rows.
        {{ query ? 'Narrow the filter to see the rest.' : 'Collapse folders or use the filter to see the rest.' }}
      </p>
    </div>
  </div>
</template>
