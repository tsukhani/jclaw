<script setup lang="ts">
/**
 * JCLAW-1247: an agent's workspace as an expandable tree with sizes and a total.
 *
 * A window onto the on-disk workspace, not a viewer: nothing here renders file content,
 * and the Standing Orders editor beside it stays the only write path. A sub-agent shows
 * its root agent's shared workspace, because that is the directory it writes into.
 */
import { ArchiveBoxArrowDownIcon, ArrowDownTrayIcon, ChevronRightIcon } from '@heroicons/vue/24/outline'
import { DocumentIcon, FolderIcon, TrashIcon } from '@heroicons/vue/20/solid'
import type { ApiErrorDetails, WorkspaceEntry, WorkspaceListing } from '~/types/api'
import { formatSize } from '~/utils/format'

const props = defineProps<{ agentId: number | null }>()

// (1) Data layer: the listing and its total, re-read whenever the agent changes.
const listing = ref<WorkspaceListing | null>(null)
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

function startPolling() {
  if (timer) return
  timer = setInterval(() => void load({ silent: true }), REFRESH_MS)
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
  void load({ silent: true })
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

// (2) Tree rendering: the nested listing flattened to the rows currently expanded.
interface Row { entry: WorkspaceEntry, depth: number }

function isOpen(path: string) {
  return openDirs.value[path] === true
}

function toggle(path: string) {
  openDirs.value[path] = !isOpen(path)
}

const rows = computed<Row[]>(() => {
  const out: Row[] = []
  const walk = (entries: WorkspaceEntry[], depth: number) => {
    for (const entry of entries) {
      out.push({ entry, depth })
      if (entry.kind === 'dir' && entry.children && isOpen(entry.path)) walk(entry.children, depth + 1)
    }
  }
  walk(listing.value?.entries ?? [], 0)
  return out
})

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

async function confirmDelete(path: string) {
  const id = props.agentId
  if (!id) return
  const encoded = path.split('/').map(encodeURIComponent).join('/')
  const ack = await mutate(`/api/agents/${id}/workspace-tree/${encoded}`, { method: 'DELETE' })
  pendingDelete.value = null
  if (ack) await load()
}

watch(() => props.agentId, () => {
  pendingDelete.value = null
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
        <a
          v-if="listing"
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
    >
      The workspace is empty.
    </p>

    <div
      v-else
      class="pb-2"
    >
      <div
        v-for="row in rows"
        :key="row.entry.path"
        class="flex items-center gap-2 pr-3 py-1 hover:bg-muted/50 transition-colors"
        :style="{ paddingLeft: `${12 + row.depth * 16}px` }"
        :data-testid="`ws-row-${row.entry.path}`"
      >
        <button
          v-if="row.entry.kind === 'dir'"
          type="button"
          class="flex items-center gap-1.5 min-w-0 flex-1 text-left text-fg-primary bg-transparent border-0"
          :aria-expanded="isOpen(row.entry.path)"
          @click="toggle(row.entry.path)"
        >
          <ChevronRightIcon
            class="w-3 h-3 shrink-0 text-fg-muted transition-transform"
            :class="isOpen(row.entry.path) ? 'rotate-90' : ''"
            aria-hidden="true"
          />
          <FolderIcon
            class="w-4 h-4 shrink-0 text-fg-muted"
            aria-hidden="true"
          />
          <span class="truncate text-sm font-mono">{{ row.entry.name }}</span>
        </button>
        <span
          v-else
          class="flex items-center gap-1.5 min-w-0 flex-1 text-fg-primary"
        >
          <span
            class="w-3 shrink-0"
            aria-hidden="true"
          />
          <DocumentIcon
            class="w-4 h-4 shrink-0 text-fg-muted"
            aria-hidden="true"
          />
          <span class="truncate text-sm font-mono">{{ row.entry.name }}</span>
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
          <template v-if="!row.entry.protected">
            <template v-if="pendingDelete === row.entry.path">
              <button
                type="button"
                class="rounded border border-border bg-transparent px-1.5 py-0.5 text-[11px] text-danger"
                :aria-label="`Confirm: ${deleteLabel(row.entry)}`"
                :data-testid="`ws-delete-confirm-${row.entry.path}`"
                @click="confirmDelete(row.entry.path)"
              >Confirm</button>
              <button
                type="button"
                class="rounded border border-border bg-transparent px-1.5 py-0.5 text-[11px] text-fg-muted"
                :aria-label="`Cancel: ${deleteLabel(row.entry)}`"
                :data-testid="`ws-delete-cancel-${row.entry.path}`"
                @click="pendingDelete = null"
              >Cancel</button>
            </template>
            <button
              v-else
              type="button"
              class="border-0 bg-transparent p-0.5 text-fg-muted hover:text-danger"
              :aria-label="deleteLabel(row.entry)"
              :title="deleteLabel(row.entry)"
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
  </div>
</template>
