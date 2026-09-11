<script setup lang="ts">
// Database panel (JCLAW-1165): the health strip first, then backups, then repair.
// Backup and cleanup run in the JVM. Restore and repair need the database closed, so
// they hand off to `jclaw.sh` the way Restart does and this page watches the backend
// go down and come back; the outcome is read from /api/system/database on return.
import { formatDateTime } from '~/utils/schedule'
import { ArrowDownTrayIcon, ArrowPathIcon, TrashIcon } from '@heroicons/vue/24/outline'
import { formatSize } from '~/utils/format'

interface BackupInfo {
  id: string
  bytes: number
  createdAt: string
}

interface TableResult {
  name: string
  expected: number | null
  staged: number
  restored: number
}

interface RepairManifest {
  stamp: string
  ok: boolean
  summary: string
  files: Array<{ name: string, bytes: number }>
  tables: TableResult[]
  enumCastTables: string[]
  referenceAvailable: boolean
  referenceError: string | null
  unrecoveredRows: number
  scriptErrors: number
  failures: Array<{ statement: string, error: string }>
}

interface LastOperation {
  op: string | null
  phase: string | null
  message: string | null
  startedAt: string | null
  backup: string | null
}

interface DatabaseStatus {
  verdict: 'HEALTHY' | 'ATTENTION' | 'CRITICAL'
  reason: string
  dataFileBytes: number
  traceFileBytes: number
  preRestoreBytes: number
  intermediateBytes: number
  freeBytes: number
  h2Version: string
  probeMs: number | null
  pool: { active: number, idle: number, total: number, awaiting: number, max: number } | null
  corruption24h: number
  corruption7d: number
  firstCorruptionAt: string | null
  lastBackupAt: string | null
  lastBackupAgeSeconds: number | null
  backups: BackupInfo[]
  backupsDir: string
  retention: number
  schedule: string | null
  /** The zone the schedule runs in — app.timezone, else the server's; the list renders in it too. */
  timezone: string
  scheduledBackupAt: string | null
  scheduledBackupError: string | null
  repair: RepairManifest | null
  cleanupAvailable: boolean
  cleanupUnavailableReason: string | null
  lastOperation: LastOperation | null
  maintenanceAvailable: boolean
  maintenanceUnavailableReason: string | null
}

const { confirm } = useConfirm()
const { mutate, error: mutationError } = useApiMutation()

// useLazyFetch, not useFetch: a top-level await would suspend the whole settings
// panel behind the trace-file scan on a cold boot.
const { data: status, refresh } = useLazyFetch<DatabaseStatus>('/api/system/database')

const busy = ref<string | null>(null)
const failure = ref<string | null>(null)
const notice = ref<string | null>(null)
const uploadInput = ref<HTMLInputElement | null>(null)

type Phase = 'idle' | 'requesting' | 'stopping' | 'starting'
const phase = ref<Phase>('idle')
const elapsed = ref(0)
let poller: ReturnType<typeof setInterval> | null = null
onBeforeUnmount(stopPolling)

function stopPolling() {
  if (poller) {
    clearInterval(poller)
    poller = null
  }
}

const verdictStyle = computed(() => {
  switch (status.value?.verdict) {
    case 'HEALTHY': return { dot: 'bg-emerald-500', text: 'text-emerald-700 dark:text-emerald-400', label: 'Healthy' }
    case 'ATTENTION': return { dot: 'bg-amber-500', text: 'text-amber-700 dark:text-amber-400', label: 'Attention' }
    case 'CRITICAL': return { dot: 'bg-red-500', text: 'text-red-700 dark:text-red-400', label: 'Critical' }
    default: return { dot: 'bg-fg-muted/40', text: 'text-fg-muted', label: 'Checking…' }
  }
})

const needsRepair = computed(() => status.value?.verdict === 'ATTENTION' || status.value?.verdict === 'CRITICAL')

function age(seconds: number | null): string {
  if (seconds === null) return 'never'
  if (seconds < 90) return 'just now'
  if (seconds < 3600) return `${Math.round(seconds / 60)} min ago`
  if (seconds < 86400 * 2) return `${Math.round(seconds / 3600)} h ago`
  return `${Math.round(seconds / 86400)} days ago`
}

/** Same shape as the Tasks and Reminders columns, "11 Sept 2026 · 7:25:04 pm", in the instance's zone. */
function when(iso: string): string {
  return formatDateTime(iso, status.value?.timezone ?? null)
}

/** Size line: the data file, plus what else data/ is carrying, as one figure with its parts. */
const sizeLine = computed(() => {
  const s = status.value
  if (!s) return ''
  const parts: string[] = []
  if (s.traceFileBytes > 0) parts.push(`trace ${formatSize(s.traceFileBytes)}`)
  if (s.preRestoreBytes > 0) parts.push(`pre-restore copy ${formatSize(s.preRestoreBytes)}`)
  if (s.intermediateBytes > 0) parts.push(`repair remnants ${formatSize(s.intermediateBytes)}`)
  return parts.length ? `${formatSize(s.dataFileBytes)} + ${parts.join(', ')}` : formatSize(s.dataFileBytes)
})

async function backupNow() {
  busy.value = 'backup'
  failure.value = null
  notice.value = null
  const res = await mutate<BackupInfo>('/api/system/database/backups', { method: 'POST' })
  busy.value = null
  if (!res) {
    failure.value = mutationError.value ?? 'The backup was refused.'
    return
  }
  notice.value = `Backed up to ${res.id} (${formatSize(res.bytes)}).`
  await refresh()
}

async function deleteBackup(b: BackupInfo) {
  const ok = await confirm({
    title: 'Delete backup',
    message: `Delete ${b.id} (${formatSize(b.bytes)}, ${when(b.createdAt)})? This cannot be undone.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  failure.value = null
  const res = await mutate(`/api/system/database/backups/${encodeURIComponent(b.id)}`, { method: 'DELETE' })
  if (!res) failure.value = mutationError.value ?? 'Could not delete the backup.'
  await refresh()
}

function downloadUrl(b: BackupInfo): string {
  return `/api/system/database/backups/${encodeURIComponent(b.id)}/download`
}

async function restoreBackup(b: BackupInfo) {
  const ok = await confirm({
    title: 'Restore this backup',
    message: `Replace the database with the backup from ${when(b.createdAt)} (${b.id}). Everything written since then is lost. `
      + 'The current file is kept as jclaw.mv.db.pre-restore until the next backup. JClaw restarts; this page reconnects on its own.',
    confirmText: 'Restore',
    variant: 'danger',
  })
  if (!ok) return
  // The id travels in the query string: a Play action binds its parameters from there, not from a JSON body.
  await handOff(`/api/system/database/restore?id=${encodeURIComponent(b.id)}`, {}, 'restore')
}

async function restoreUpload(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  const ok = await confirm({
    title: 'Restore from file',
    message: `Replace the database with ${file.name} (${formatSize(file.size)}). The file is checked before anything changes; `
      + 'everything written since that backup is lost. JClaw restarts; this page reconnects on its own.',
    confirmText: 'Restore',
    variant: 'danger',
  })
  input.value = ''
  if (!ok) return
  const body = new FormData()
  body.append('file', file)
  await handOff('/api/system/database/restore', body, 'restore')
}

async function repair() {
  const ok = await confirm({
    title: 'Repair the database',
    message: 'Rebuilds the database from whatever H2\'s recovery tool can still read. Rows on pages it cannot read are lost; '
      + 'the damaged file is kept aside until you clean it up. Back up first if the database still answers. '
      + 'JClaw restarts; this page reconnects on its own and then shows the per-table result.',
    confirmText: 'Repair',
    variant: 'danger',
  })
  if (!ok) return
  await handOff('/api/system/database/repair', {}, 'repair')
}

async function handOff(url: string, body: Record<string, string> | FormData, op: string) {
  failure.value = null
  notice.value = null
  phase.value = 'requesting'
  const res = await mutate<{ rebuildExpected?: boolean }>(url, { method: 'POST', body })
  if (!res) {
    phase.value = 'idle'
    failure.value = mutationError.value
      ? `${mutationError.value} The instance is still running.`
      : `The ${op} request was rejected. The instance is still running.`
    return
  }
  watchForReturn(Boolean(res.rebuildExpected))
}

/** True when /api/status answers at all; any throw is a "down". */
async function backendUp(): Promise<boolean> {
  try {
    await $fetch('/api/status', { retry: 0, timeout: 4000 })
    return true
  }
  catch {
    return false
  }
}

/**
 * Two-phase reconnect, as Restart does: the backend stays up for a couple of seconds so
 * the 202 reaches us, so wait for it to go down before waiting for it to come back.
 */
function watchForReturn(rebuildExpected: boolean) {
  phase.value = 'stopping'
  elapsed.value = 0
  // A repair reads the whole file twice, and a source checkout may recompile on the way back.
  const startupBudget = rebuildExpected ? 1500 : 900
  const shutdownBudget = 90
  let consecutiveUp = 0
  stopPolling()
  poller = setInterval(async () => {
    elapsed.value += 1
    const up = await backendUp()
    if (phase.value === 'stopping') {
      if (!up) {
        phase.value = 'starting'
        elapsed.value = 0
      }
      else if (elapsed.value >= shutdownBudget) {
        stopPolling()
        phase.value = 'idle'
        failure.value = `The backend was still responding ${shutdownBudget}s after the request was accepted. Check logs/database.log.`
      }
      return
    }
    if (up) {
      consecutiveUp += 1
      if (consecutiveUp >= 2) {
        stopPolling()
        reloadNuxtApp({ persistState: false })
      }
      return
    }
    consecutiveUp = 0
    if (elapsed.value >= startupBudget) {
      stopPolling()
      phase.value = 'idle'
      failure.value = `The backend did not come back within ${startupBudget}s. Check logs/database.log and logs/restart.log.`
    }
  }, 1000)
}

const statusLine = computed(() => {
  switch (phase.value) {
    case 'requesting': return 'Handing off to jclaw.sh…'
    case 'stopping': return `Waiting for the backend to stop… (${elapsed.value}s)`
    case 'starting': return `Waiting for the backend to come back… (${elapsed.value}s)`
    default: return ''
  }
})

async function cleanUp() {
  const s = status.value
  if (!s?.repair) return
  const ok = await confirm({
    title: 'Clean up repair files',
    message: `Delete the ${s.repair.files.length} files the repair on ${s.repair.stamp} created (${formatSize(s.intermediateBytes)}), `
      + 'including the damaged database file? Each is checked against the checksum the repair recorded first.',
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  busy.value = 'clean'
  failure.value = null
  const res = await mutate<{ reclaimedBytes: number, deleted: unknown[] }>('/api/system/database/repair/clean', { method: 'POST' })
  busy.value = null
  if (!res) {
    failure.value = mutationError.value ?? 'The cleanup was refused.'
    return
  }
  notice.value = `Removed ${res.deleted.length} files; ${formatSize(res.reclaimedBytes)} reclaimed.`
  await refresh()
}

// db.backup.retention and db.backup.schedule are ordinary config rows. An empty value
// deletes the row, which is what "no schedule" is — the key's absence, not a blank value.
async function writeConfig(key: string, value: string): Promise<boolean> {
  failure.value = null
  try {
    if (value === '') await $fetch(`/api/config/${key}`, { method: 'DELETE' })
    else await $fetch('/api/config', { method: 'POST', body: { key, value } })
    await refresh()
    return true
  }
  catch (e) {
    const data = (e as { data?: { error?: string, message?: string } })?.data
    failure.value = data?.error ?? data?.message ?? (e instanceof Error ? e.message : 'Save failed')
    return false
  }
}

const editingRetention = ref(false)
const retentionDraft = ref('')

function startRetentionEdit() {
  editingRetention.value = true
  failure.value = null
  retentionDraft.value = String(status.value?.retention ?? 7)
}

async function saveRetention() {
  // String(): v-model on a number input hands back a number, which has no trim().
  if (await writeConfig('db.backup.retention', String(retentionDraft.value).trim())) editingRetention.value = false
}

// The daily backup time. Re-seeded only when the stored value itself changes, so a status
// refresh landing mid-edit does not overwrite what the operator is still typing.
const scheduleDraft = ref('')
watch(() => status.value?.schedule ?? '', (stored) => {
  scheduleDraft.value = stored
}, { immediate: true })
const scheduleDirty = computed(() => scheduleDraft.value !== (status.value?.schedule ?? ''))

async function saveSchedule() {
  await writeConfig('db.backup.schedule', scheduleDraft.value.trim())
}

async function turnOffSchedule() {
  scheduleDraft.value = ''
  await writeConfig('db.backup.schedule', '')
}

/** How long a finished restore or repair stays on screen, measured from when it started. */
const LAST_OP_WINDOW_MS = 15 * 60 * 1000

/**
 * The outcome `jclaw.sh` left in logs/database-status.json. It exists for the reload the
 * restart forces, but the file stays until the next operation overwrites it — so show it
 * while the operation is in flight and briefly after it began, and treat it as history
 * after that rather than pinning "Repair complete" to the panel for ever. An outcome whose
 * start time is missing or unreadable counts as old for the same reason. It renders under
 * the action that produced it, not in the health strip, which reports the database itself.
 */
const lastOp = computed(() => {
  const op = status.value?.lastOperation
  if (!op?.phase) return null
  const ok = op.phase === 'done'
  const failed = op.phase === 'failed'
  const inFlight = !ok && !failed
  if (!inFlight) {
    const started = op.startedAt ? Date.parse(op.startedAt) : Number.NaN
    if (Number.isNaN(started) || Date.now() - started > LAST_OP_WINDOW_MS) return null
  }
  const verb = op.op === 'restore' ? 'Restore' : op.op === 'repair' ? 'Repair' : 'Maintenance'
  return {
    text: `${verb}: ${op.message ?? op.phase}`,
    ok,
    failed,
    inFlight,
    panel: op.op === 'restore' ? 'backups' : 'repair',
  }
})
</script>

<template>
  <div class="mb-6 space-y-4">
    <h2 class="text-sm font-medium text-fg-muted">
      Database
    </h2>
    <p class="text-xs text-fg-muted">
      The H2 data file behind everything on this instance. Back it up online at any time; restore or
      repair it through <code>jclaw.sh</code>, which stops the instance, does the work, and starts it again.
    </p>

    <!-- Health strip -->
    <div
      class="bg-surface-elevated border border-border"
      data-testid="db-health"
    >
      <div class="px-4 py-3 flex flex-wrap items-center gap-x-6 gap-y-2">
        <div class="flex items-center gap-2 min-w-32">
          <span
            class="w-2.5 h-2.5 rounded-full shrink-0"
            :class="verdictStyle.dot"
            aria-hidden="true"
          />
          <span
            class="text-sm font-medium"
            :class="verdictStyle.text"
            data-testid="db-verdict"
          >{{ verdictStyle.label }}</span>
        </div>
        <dl
          v-if="status"
          class="flex flex-wrap gap-x-6 gap-y-1 text-xs tabular-nums"
        >
          <div class="flex gap-1.5">
            <dt class="text-fg-muted">
              Size
            </dt>
            <dd class="text-fg-strong">
              {{ sizeLine }}
            </dd>
          </div>
          <div class="flex gap-1.5">
            <dt class="text-fg-muted">
              Free
            </dt>
            <dd class="text-fg-strong">
              {{ formatSize(status.freeBytes) }}
            </dd>
          </div>
          <div class="flex gap-1.5">
            <dt class="text-fg-muted">
              Last backup
            </dt>
            <dd
              class="text-fg-strong"
              :title="status.lastBackupAt ?? undefined"
            >
              {{ age(status.lastBackupAgeSeconds) }}
            </dd>
          </div>
          <div class="flex gap-1.5">
            <dt class="text-fg-muted">
              H2
            </dt>
            <dd class="text-fg-strong">
              {{ status.h2Version }}
            </dd>
          </div>
          <div
            v-if="status.probeMs !== null"
            class="flex gap-1.5"
          >
            <dt class="text-fg-muted">
              Probe
            </dt>
            <dd class="text-fg-strong">
              {{ status.probeMs }} ms<template v-if="status.pool">
                · pool {{ status.pool.active }}/{{ status.pool.max }} active, {{ status.pool.awaiting }} waiting
              </template>
            </dd>
          </div>
        </dl>
        <button
          class="ml-auto p-1 text-fg-muted hover:text-fg-strong transition-colors"
          title="Refresh"
          aria-label="Refresh database status"
          @click="refresh()"
        >
          <ArrowPathIcon
            class="w-4 h-4"
            aria-hidden="true"
          />
        </button>
      </div>
      <div
        v-if="status"
        class="px-4 py-2 border-t border-border text-xs text-fg-muted"
        data-testid="db-reason"
      >
        {{ status.reason }}
        <template v-if="status.firstCorruptionAt">
          First read failure in the window: {{ when(status.firstCorruptionAt) }}.
        </template>
      </div>
    </div>

    <!-- Backups -->
    <div class="bg-surface-elevated border border-border">
      <div class="px-4 py-2.5 flex items-center gap-3 border-b border-border">
        <span class="text-sm font-medium text-fg-strong">Backups</span>
        <span
          v-if="status"
          class="text-xs text-fg-muted truncate"
          :title="status.backupsDir"
        >in {{ status.backupsDir }}</span>
        <button
          class="ml-auto shrink-0 px-3 py-1.5 text-xs font-medium text-white bg-emerald-600 hover:bg-emerald-700
                 disabled:bg-emerald-600/40 disabled:cursor-not-allowed rounded-full transition-colors"
          :disabled="busy !== null || phase !== 'idle'"
          data-testid="db-backup-now"
          @click="backupNow"
        >
          {{ busy === 'backup' ? 'Backing up…' : 'Back up now' }}
        </button>
      </div>

      <div class="divide-y divide-border">
        <div
          v-for="b in status?.backups ?? []"
          :key="b.id"
          class="px-4 py-2 flex items-center gap-3 text-xs"
        >
          <span class="font-mono text-fg-primary truncate">{{ b.id }}</span>
          <span class="text-fg-muted tabular-nums shrink-0">{{ when(b.createdAt) }}</span>
          <span class="text-fg-muted tabular-nums shrink-0">{{ formatSize(b.bytes) }}</span>
          <span class="ml-auto flex items-center gap-1 shrink-0">
            <a
              :href="downloadUrl(b)"
              class="p-1 text-fg-muted hover:text-fg-strong transition-colors"
              :title="`Download ${b.id}`"
              :aria-label="`Download ${b.id}`"
            >
              <ArrowDownTrayIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </a>
            <button
              class="px-2 py-0.5 text-xs border border-border hover:bg-muted transition-colors disabled:opacity-50"
              :disabled="phase !== 'idle' || !status?.maintenanceAvailable"
              :title="status?.maintenanceUnavailableReason ?? `Restore ${b.id}`"
              :data-testid="`db-restore-${b.id}`"
              @click="restoreBackup(b)"
            >
              Restore
            </button>
            <button
              class="p-1 text-fg-muted hover:text-red-600 transition-colors"
              :title="`Delete ${b.id}`"
              :aria-label="`Delete ${b.id}`"
              @click="deleteBackup(b)"
            >
              <TrashIcon
                class="w-3.5 h-3.5"
                aria-hidden="true"
              />
            </button>
          </span>
        </div>
        <div
          v-if="status && status.backups.length === 0"
          class="px-4 py-3 text-xs text-fg-muted"
        >
          No backups yet.
        </div>
      </div>

      <!-- Schedule: db.backup.schedule, a time of day; the row's absence is "no schedule". -->
      <div
        class="px-4 py-2.5 border-t border-border"
        data-testid="db-schedule"
      >
        <div class="flex flex-wrap items-center gap-x-3 gap-y-2">
          <span class="text-xs font-medium text-fg-strong">Schedule</span>
          <span class="text-xs text-fg-muted">Daily at</span>
          <input
            id="db-schedule-time"
            v-model="scheduleDraft"
            type="time"
            aria-label="Daily backup time"
            class="px-2 py-0.5 bg-muted border border-input text-xs text-fg-strong font-mono focus:outline-hidden"
            @keydown.enter="saveSchedule"
          >
          <button
            class="px-2 py-0.5 text-xs border border-border hover:bg-muted transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!scheduleDirty"
            data-testid="db-schedule-save"
            @click="saveSchedule"
          >
            Save
          </button>
          <button
            v-if="status?.schedule"
            class="px-2 py-0.5 text-xs text-fg-muted hover:text-fg-strong transition-colors"
            data-testid="db-schedule-off"
            @click="turnOffSchedule"
          >
            Turn off
          </button>
        </div>
        <p
          class="mt-1.5 text-xs text-fg-muted"
          data-testid="db-schedule-state"
        >
          <template v-if="status?.schedule">
            Backing up every day at {{ status.schedule }} ({{ status.timezone }}, this instance's timezone; the times above are in it too).
          </template>
          <template v-else>
            No automatic backup — pick a time to back one up every day.
          </template>
          <template v-if="status?.scheduledBackupAt">
            Last scheduled backup {{ when(status.scheduledBackupAt) }}.
          </template>
        </p>
        <p
          v-if="status?.scheduledBackupError"
          class="mt-1 text-xs text-red-700 dark:text-red-400"
        >
          Last scheduled backup failed: {{ status.scheduledBackupError }}
        </p>
      </div>

      <div class="px-4 py-2.5 border-t border-border flex flex-wrap items-center gap-x-6 gap-y-2 text-xs">
        <div class="flex items-center gap-2">
          <span class="font-mono text-fg-muted">retention</span>
          <template v-if="editingRetention">
            <input
              v-model="retentionDraft"
              type="number"
              min="1"
              aria-label="Backups to keep"
              class="w-16 px-2 py-0.5 bg-muted border border-input text-fg-strong font-mono focus:outline-hidden"
              @keydown.enter="saveRetention"
              @keydown.escape="editingRetention = false"
            >
            <button
              class="text-emerald-700 dark:text-emerald-400"
              @click="saveRetention"
            >
              Save
            </button>
          </template>
          <button
            v-else
            class="text-fg-primary hover:underline"
            title="Backups to keep; the oldest is pruned after each new one"
            @click="startRetentionEdit"
          >
            keep {{ status?.retention ?? 7 }}
          </button>
        </div>
        <!-- The file input carries the picker and stays out of the tab order; the button is
             the control, so this reads as an action rather than a stray form field. -->
        <div class="ml-auto">
          <input
            id="db-restore-upload"
            ref="uploadInput"
            type="file"
            accept=".zip"
            class="sr-only"
            tabindex="-1"
            aria-hidden="true"
            @change="restoreUpload"
          >
          <button
            class="px-2 py-0.5 text-xs border border-border hover:bg-muted transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="phase !== 'idle' || !status?.maintenanceAvailable"
            :title="status?.maintenanceUnavailableReason ?? 'Restore from a backup zip on this machine'"
            data-testid="db-restore-upload"
            @click="uploadInput?.click()"
          >
            Restore from a file…
          </button>
        </div>
      </div>

      <div
        v-if="lastOp && lastOp.panel === 'backups'"
        class="px-4 py-2 border-t border-border text-xs"
        :class="lastOp.failed ? 'text-red-700 dark:text-red-400' : lastOp.ok ? 'text-emerald-700 dark:text-emerald-400' : 'text-fg-muted'"
        data-testid="db-last-op"
      >
        {{ lastOp.text }}
      </div>
    </div>

    <!-- Repair -->
    <div
      class="bg-surface-elevated border"
      :class="needsRepair ? 'border-amber-500/70' : 'border-border'"
      data-testid="db-repair-section"
    >
      <div class="px-4 py-2.5 flex items-center justify-between gap-4">
        <div class="min-w-0">
          <span class="text-sm font-medium text-fg-strong">Repair</span>
          <div class="text-xs text-fg-muted mt-0.5">
            <template v-if="status && !status.maintenanceAvailable">
              {{ status.maintenanceUnavailableReason }}
            </template>
            <template v-else-if="needsRepair">
              Rebuilds the data file from what H2 can still read, keeps the damaged file aside, and restarts. Back up first if you can.
            </template>
            <template v-else>
              Not needed while the verdict is Healthy. Available anyway; it rebuilds and compacts the data file.
            </template>
          </div>
        </div>
        <button
          class="shrink-0 px-3 py-1.5 text-xs font-medium rounded-full transition-colors disabled:cursor-not-allowed"
          :class="needsRepair
            ? 'text-white bg-amber-600 hover:bg-amber-700 disabled:bg-amber-600/40'
            : 'border border-border text-fg-primary hover:bg-muted disabled:opacity-50'"
          :disabled="phase !== 'idle' || !status?.maintenanceAvailable"
          data-testid="db-repair"
          @click="repair"
        >
          Repair
        </button>
      </div>

      <div
        v-if="lastOp && lastOp.panel === 'repair'"
        class="px-4 py-2 border-t border-border text-xs"
        :class="lastOp.failed ? 'text-red-700 dark:text-red-400' : lastOp.ok ? 'text-emerald-700 dark:text-emerald-400' : 'text-fg-muted'"
        data-testid="db-last-op"
      >
        {{ lastOp.text }}
      </div>

      <div
        v-if="status?.repair"
        class="px-4 py-2.5 border-t border-border text-xs space-y-2"
        data-testid="db-repair-result"
      >
        <div :class="status.repair.ok ? 'text-emerald-700 dark:text-emerald-400' : 'text-amber-700 dark:text-amber-400'">
          Last repair ({{ status.repair.stamp }}): {{ status.repair.summary }}
        </div>
        <div
          v-if="!status.repair.referenceAvailable"
          class="text-fg-muted"
        >
          {{ status.repair.referenceError }} — a table H2 had already rolled back reads as empty below.
        </div>
        <div class="overflow-x-auto">
          <table class="text-xs tabular-nums">
            <thead>
              <tr class="text-fg-muted text-left">
                <th class="pr-4 font-normal">
                  Table
                </th>
                <th class="pr-4 font-normal">
                  Restored
                </th>
                <th class="pr-4 font-normal">
                  Expected
                </th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="t in status.repair.tables"
                :key="t.name"
                :class="(t.expected !== null ? t.restored === t.expected : t.restored === t.staged) ? '' : 'text-amber-700 dark:text-amber-400'"
              >
                <td class="pr-4 font-mono">
                  {{ t.name }}<span
                    v-if="status.repair.enumCastTables.includes(t.name)"
                    class="text-fg-muted"
                  > (enum cast)</span>
                </td>
                <td class="pr-4">
                  {{ t.restored }}
                </td>
                <td class="pr-4">
                  {{ t.expected ?? t.staged }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <div
          v-for="f in status.repair.failures"
          :key="f.statement"
          class="text-red-700 dark:text-red-400 font-mono truncate"
          :title="f.statement"
        >
          {{ f.error }}
        </div>
        <div class="flex items-center gap-3">
          <span class="text-fg-muted">
            Kept aside: {{ status.repair.files.length }} files, {{ formatSize(status.intermediateBytes) }}
          </span>
          <button
            class="px-2 py-0.5 border border-border hover:bg-muted transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
            :disabled="!status.cleanupAvailable || busy !== null"
            :title="status.cleanupUnavailableReason ?? 'Delete the files the repair created'"
            data-testid="db-clean"
            @click="cleanUp"
          >
            {{ busy === 'clean' ? 'Cleaning…' : 'Clean up repair files' }}
          </button>
          <span
            v-if="!status.cleanupAvailable"
            class="text-fg-muted"
            data-testid="db-clean-reason"
          >{{ status.cleanupUnavailableReason }}</span>
        </div>
      </div>
    </div>

    <div
      v-if="statusLine || failure || notice"
      class="text-xs"
      role="status"
      aria-live="polite"
    >
      <span
        v-if="failure"
        class="text-red-700 dark:text-red-400"
      >{{ failure }}</span>
      <span
        v-else-if="notice"
        class="text-emerald-700 dark:text-emerald-400"
      >{{ notice }}</span>
      <span
        v-else
        class="text-fg-muted"
      >{{ statusLine }}</span>
    </div>
  </div>
</template>
