<script setup lang="ts">
import type { Task, TaskStats } from '~/types/api'
import { BellAlertIcon, CalendarDaysIcon, ChevronRightIcon, TableCellsIcon, TrashIcon } from '@heroicons/vue/24/outline'
import { linkify } from '~/utils/linkify'

// BellAlertIcon retained for the empty-state placeholder; the header no
// longer carries it to match the /channels page's plain h1 convention.

definePageMeta({ title: 'Reminders' })

// ── JCLAW-440: table / calendar view, persisted to the URL so refresh /
// back-forward keep the view (mirrors the Tasks page). ──
const route = useRoute()
const router = useRouter()
const view = computed<'table' | 'calendar'>({
  get() {
    return route.query.view === 'calendar' ? 'calendar' : 'table'
  },
  set(v) {
    router.replace({ query: { ...route.query, view: v === 'table' ? undefined : v } }).catch(() => {})
  },
})

// ── JCLAW-438: FilterBar-driven search/filter ──
// Mirrors the /tasks page: one FilterBar emits chips, onFiltersChanged
// rehydrates the watched refs, and the request URL recomputes from them so
// the table refetches reactively. payloadType=reminder stays pinned so this
// page only ever shows reminders (the /tasks list passes the inverse,
// excludePayloadType=reminder, so a row never appears on both pages).
const qFilter = ref('')
const statusFilter = ref('')
const typeFilter = ref('')

interface Filter { key: string, value: string }
function onFiltersChanged(filters: Filter[]) {
  // A bare token parses to key `name` in FilterBar; map it to q too so plain
  // typing searches the reminder text without needing the `q:` prefix.
  qFilter.value = filters.find(f => f.key === 'q' || f.key === 'name')?.value ?? ''
  statusFilter.value = filters.find(f => f.key === 'status')?.value ?? ''
  typeFilter.value = filters.find(f => f.key === 'type')?.value ?? ''
}

const url = computed(() => {
  const params = new URLSearchParams()
  params.set('payloadType', 'reminder')
  // q is the FTS keyword (reminder name + description Lucene scope); status /
  // type are equality predicates the backend intersects with the hit set.
  if (qFilter.value) params.set('q', qFilter.value)
  if (statusFilter.value) params.set('status', statusFilter.value)
  if (typeFilter.value) params.set('type', typeFilter.value)
  params.set('limit', '200')
  return `/api/tasks?${params}`
})
const { data: reminders, refresh } = await useFetch<Task[]>(url)

// Reminder-scoped KPI strip — kept unfiltered (always ?payloadType=reminder)
// so the Active/Pending/Failed totals reflect ALL reminders, not the current
// search. Matches the /tasks page, whose KPI strip is also search-independent.
const { data: reminderStats } = await useFetch<TaskStats>('/api/tasks/stats?payloadType=reminder')

const { mutate } = useApiMutation()
const { confirm } = useConfirm()

const hasActiveFilters = computed(() => !!(qFilter.value || statusFilter.value || typeFilter.value))

// ── JCLAW-438: Delete-all via the shared bulk-select pattern ──
// Same trash-icon → checkbox column → "Delete N" flow the Tasks and Subagent
// Runs pages use (useBulkSelect). "Select all" + delete is the delete-all path.
const {
  selectMode,
  selectedIds,
  deletingBulk,
  selectableRows,
  enter: enterSelectMode,
  exit: exitSelectMode,
  toggle: toggleSelection,
  toggleAll: toggleSelectAll,
  deleteSelected,
} = useBulkSelect<Task>({
  rows: reminders,
  deleteOne: id => $fetch<unknown>(`/api/tasks/${id}`, { method: 'DELETE' }),
  onComplete: () => refresh(),
  confirmCopy: count => ({
    title: 'Delete reminders',
    message: `Permanently delete ${count} reminder${count === 1 ? '' : 's'}? This cannot be undone.`,
  }),
})

const allSelected = computed(() =>
  selectableRows.value.length > 0 && selectedIds.value.size === selectableRows.value.length)

// ── Expandable rows (mirrors the Tasks page) ──
// The Reminder cell shows just the kebab name; the chevron expands a detail
// row with the full reminder text (the verbatim nudge) and collapses it again.
const expandedIds = reactive(new Set<number>())
function toggleExpand(id: number) {
  if (expandedIds.has(id)) expandedIds.delete(id)
  else expandedIds.add(id)
}
// colspan for the expanded detail row: all 8 visible columns, +1 for the
// leading checkbox column when bulk-select is active.
const reminderColspan = computed(() => (selectMode.value ? 9 : 8))

async function deleteOne(r: Task) {
  const ok = await confirm({
    title: 'Delete reminder?',
    message: `"${reminderBody(r)}" — this is permanent.`,
    confirmText: 'Delete',
    variant: 'danger',
  })
  if (!ok) return
  await mutate(`/api/tasks/${r.id}`, { method: 'DELETE' })
  await refresh()
}

// FilterBar's Export button downloads the current (filtered) reminder set as a
// JSON bundle — mirrors the Tasks page's audit export, scoped to reminders.
function exportReminders() {
  const list = reminders.value ?? []
  if (!list.length) return
  const payload = { exportedAt: new Date().toISOString(), reminderCount: list.length, reminders: list }
  const blob = new Blob([JSON.stringify(payload, null, 2)], { type: 'application/json' })
  const href = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = href
  a.download = `jclaw-reminders-${new Date().toISOString().replaceAll(':', '-').slice(0, 19)}.json`
  document.body.appendChild(a)
  a.click()
  a.remove()
  URL.revokeObjectURL(href)
}

// Only one-shot reminders ever complete (recurring ones recur), so auto-delete
// only applies to them — the toggle is hidden for recurring reminders.
function isOneShot(r: Task): boolean {
  return r.type === 'SCHEDULED' || r.type === 'IMMEDIATE'
}

// Flip auto-delete-after-fire for a one-off reminder.
async function toggleAutoDelete(r: Task, on: boolean) {
  await mutate(`/api/tasks/${r.id}`, { method: 'PATCH', body: { autoDeleteOnComplete: on } })
  await refresh()
}

// The /api/tasks Task type carries description / delivery / scheduleDisplay
// through its open `[key: string]: unknown` index signature rather than
// declared optional properties, so reads need an explicit string narrowing.
function asString(v: unknown): string {
  return typeof v === 'string' ? v : ''
}

function reminderBody(r: Task): string {
  return asString(r.description) || r.name
}

function reminderDelivery(r: Task): string {
  return asString(r.delivery)
}

function reminderScheduleDisplay(r: Task): string {
  return asString(r.scheduleDisplay)
}

// ── JCLAW-438: live "time until fire" countdown ──
// 1s tick so the When column stays current while the page is open. SPA-only
// (Reminders renders client-side), so the interval is safe in onMounted.
const nowMs = ref(Date.now())
let tickHandle: ReturnType<typeof setInterval> | undefined
onMounted(() => {
  tickHandle = setInterval(() => {
    nowMs.value = Date.now()
  }, 1000)
})
onUnmounted(() => {
  if (tickHandle) clearInterval(tickHandle)
})

// When column — the exact wall-clock datetime the reminder (next) fires, in
// the app's effective timezone ("10 Jun 2026 · 1:15 pm"). COMPLETED reminders
// have already fired, so the Fired column is authoritative — defer.
function whenLabel(r: Task): string {
  if (r.status === 'COMPLETED') return '—'
  if (!r.nextRunAt) return '—'
  return formatDateTime(r.nextRunAt, r.effectiveTimezone ?? null)
}

// Schedule column — how it's scheduled. Recurring reminders show their cadence
// ("every Tuesday at 5 PM", "every 30 min"); one-shot reminders show the
// countdown until they fire ("in 3 hours"). Never a raw cron / ISO value.
function reminderSchedule(r: Task): string {
  if (r.type === 'CRON' || r.type === 'INTERVAL') return humanSchedule(r)
  if (r.status === 'COMPLETED') return '—'
  if (!r.nextRunAt) return reminderScheduleDisplay(r) || r.type
  return timeUntil(r.nextRunAt, nowMs.value)
}

// Fired column — the absolute datetime the reminder last fired, same format as
// the When column (JCLAW-438 item 2), in the app's effective timezone.
function firedAtLabel(r: Task): string {
  const raw = asString(r.lastFiredAt)
  if (!raw) return '—'
  return formatDateTime(raw, r.effectiveTimezone ?? null)
}

function deliveryLabel(r: Task): string {
  const d = reminderDelivery(r)
  // Null/blank delivery means "auto-route to the calling chat" for reminders —
  // distinct from tasks' "none" (JCLAW-420). Keep that behavior.
  if (!d) return 'web (auto)'
  // JCLAW-420: `tool:<name>` self-delivery — show the tool name, not the raw
  // `tool:` prefix, mirroring the Tasks page's deliveryLabel.
  if (d.startsWith('tool:')) return d.slice('tool:'.length)
  if (d.startsWith('web:')) return 'web'
  if (d.startsWith('telegram:')) return 'telegram'
  return d
}

// Status → text color, matching the /tasks table's statusColors so reminders
// read with the same colored-mono convention as the rest of the app (rather
// than the old rounded-pill badges).
const statusColors: Record<string, string> = {
  PENDING: 'text-yellow-700 dark:text-yellow-400',
  ACTIVE: 'text-emerald-700 dark:text-emerald-400',
  RUNNING: 'text-blue-700 dark:text-blue-400',
  LOST: 'text-orange-700 dark:text-orange-400',
  COMPLETED: 'text-green-700 dark:text-green-400',
  FAILED: 'text-red-700 dark:text-red-400',
  CANCELLED: 'text-fg-muted',
}
</script>

<template>
  <div>
    <div class="flex items-center justify-between mb-2">
      <div class="flex items-center gap-3">
        <h1 class="text-lg font-semibold text-fg-strong">
          Reminders
        </h1>
        <!-- View switcher (icon-only): table / calendar. State persists in the
             URL (?view=calendar) so refresh + shareable links survive. -->
        <div
          v-if="!selectMode"
          class="inline-flex border border-input divide-x divide-input"
          role="tablist"
          aria-label="Reminder view"
        >
          <button
            v-for="opt in ([
              { id: 'table', label: 'Table', icon: TableCellsIcon },
              { id: 'calendar', label: 'Calendar', icon: CalendarDaysIcon },
            ] as const)"
            :key="opt.id"
            type="button"
            role="tab"
            :aria-selected="view === opt.id"
            :title="`${opt.label} view`"
            :aria-label="`${opt.label} view`"
            class="p-2 inline-flex items-center transition-colors"
            :class="view === opt.id
              ? 'bg-muted text-fg-strong'
              : 'text-fg-muted hover:text-fg-strong'"
            @click="view = opt.id"
          >
            <component
              :is="opt.icon"
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </div>
      </div>
      <div class="flex items-center gap-2">
        <template v-if="!selectMode">
          <button
            :disabled="!reminders?.length"
            type="button"
            class="p-2 border border-input text-fg-muted hover:text-red-400 hover:border-red-700/50 disabled:opacity-40 disabled:hover:text-fg-muted disabled:hover:border-input transition-colors"
            title="Delete reminders"
            aria-label="Delete reminders"
            @click="enterSelectMode"
          >
            <TrashIcon
              class="w-4 h-4"
              aria-hidden="true"
            />
          </button>
        </template>
        <template v-else>
          <button
            type="button"
            class="px-3 py-1.5 border border-input text-fg-muted text-xs hover:text-fg-strong hover:border-neutral-500 transition-colors"
            @click="exitSelectMode"
          >
            Cancel
          </button>
          <button
            type="button"
            :disabled="!selectedIds.size || deletingBulk"
            class="px-3 py-1.5 bg-red-700 text-white text-xs font-medium hover:bg-red-600 disabled:opacity-40 transition-colors"
            @click="deleteSelected"
          >
            Delete {{ selectedIds.size || '' }}
          </button>
        </template>
      </div>
    </div>
    <p class="mb-6 text-sm text-zinc-600 dark:text-zinc-400">
      Personal nudges that fire on a schedule and surface as a toast in the corner of the app
      (or via Telegram if you've configured it). They never go through the LLM —
      the description is what you'll see.
    </p>

    <!-- Reminder KPI strip — reminders never run through the LLM, so per-run
         metrics (Runs today / Success rate) are meaningless here; we only track
         the lifecycle states that matter: Pending (one-shot waiting), Active
         (recurring ongoing), and Failed. Scoped to payloadType=reminder. -->
    <div
      v-if="reminderStats"
      class="grid grid-cols-3 gap-2 mb-4"
    >
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Active
        </div>
        <div
          class="text-lg font-semibold"
          :class="reminderStats.activeCount > 0 ? 'text-emerald-400' : 'text-fg-strong'"
        >
          {{ reminderStats.activeCount }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Pending
        </div>
        <div
          class="text-lg font-semibold"
          :class="reminderStats.pendingCount > 0 ? 'text-yellow-400' : 'text-fg-strong'"
        >
          {{ reminderStats.pendingCount }}
        </div>
      </div>
      <div class="bg-surface-elevated border border-border px-3 py-2">
        <div class="text-[10px] uppercase tracking-wider text-fg-muted">
          Failed
        </div>
        <div
          class="text-lg font-semibold"
          :class="reminderStats.failedCount > 0 ? 'text-red-400' : 'text-fg-strong'"
        >
          {{ reminderStats.failedCount }}
        </div>
      </div>
    </div>

    <!-- Search / filter bar — one chip-based bar (JCLAW-304 pattern). `q:`
         keyword runs FTS over the reminder name + description; `status:` and
         `type:` are equality filters. A bare word also searches (mapped to q
         in onFiltersChanged). -->
    <div class="mb-4">
      <FilterBar
        storage-key="reminders"
        placeholder="Filter... (e.g., dentist status:PENDING type:SCHEDULED)"
        :filter-keys="['q', 'status', 'type']"
        @update:filters="onFiltersChanged"
        @export="exportReminders"
      />
    </div>

    <!-- Empty state — onboarding copy when there are genuinely no reminders;
         a plain "no matches" when a filter is narrowing an otherwise non-empty
         set so the chat hint doesn't mislead. -->
    <section
      v-if="view === 'table' && (!reminders || reminders.length === 0)"
      class="border border-dashed border-border bg-surface-elevated px-6 py-12 text-center"
    >
      <template v-if="hasActiveFilters">
        <BellAlertIcon class="mx-auto h-10 w-10 text-fg-muted" />
        <h2 class="mt-3 text-sm font-medium text-fg-strong">
          No reminders match your filters
        </h2>
        <p class="mt-1 text-sm text-fg-muted">
          Clear the filter bar to see all reminders.
        </p>
      </template>
      <template v-else>
        <BellAlertIcon class="mx-auto h-10 w-10 text-fg-muted" />
        <h2 class="mt-3 text-sm font-medium text-fg-strong">
          No reminders yet
        </h2>
        <p class="mt-1 text-sm text-fg-muted">
          Open
          <NuxtLink
            to="/chat"
            class="font-medium text-emerald-500 underline-offset-2 hover:underline"
          >
            a chat
          </NuxtLink>
          and say something like <em>"remind me to pay salaries tomorrow at 9am"</em>
          or <em>"remind me in 5 minutes to take the laundry out"</em>.
        </p>
      </template>
    </section>

    <!-- Reminders table — same design-token styling as the /tasks table
         (bg-surface-elevated / border-border / text-fg-* / colored-mono status)
         so the rows read consistently with the rest of the app. -->
    <section
      v-else-if="view === 'table'"
      class="bg-surface-elevated border border-border"
    >
      <table class="w-full text-sm">
        <thead>
          <tr class="border-b border-border text-left text-xs text-fg-muted">
            <th
              v-if="selectMode"
              class="px-4 py-2.5 w-10"
            >
              <input
                type="checkbox"
                class="accent-red-500 cursor-pointer align-middle"
                :checked="allSelected"
                aria-label="Select all reminders"
                @change="toggleSelectAll"
              >
            </th>
            <th class="px-4 py-2.5 font-medium">
              Reminder
            </th>
            <th class="px-4 py-2.5 font-medium">
              Schedule
            </th>
            <th class="px-4 py-2.5 font-medium">
              Status
            </th>
            <th class="px-4 py-2.5 font-medium">
              Channel
            </th>
            <th class="px-4 py-2.5 font-medium">
              When
            </th>
            <th class="px-4 py-2.5 font-medium">
              Fired
            </th>
            <th class="px-4 py-2.5 font-medium">
              Auto-delete
            </th>
            <th class="px-4 py-2.5 font-medium text-right">
              Actions
            </th>
          </tr>
        </thead>
        <tbody class="divide-y divide-border">
          <template
            v-for="r in reminders"
            :key="r.id"
          >
            <tr class="hover:bg-muted/30 transition-colors">
              <td
                v-if="selectMode"
                class="px-4 py-2.5"
              >
                <input
                  type="checkbox"
                  class="accent-red-500 cursor-pointer align-middle"
                  :checked="selectedIds.has(r.id)"
                  :aria-label="`Select ${r.name}`"
                  @change="toggleSelection(r.id)"
                >
              </td>
              <td class="px-4 py-2.5 text-fg-primary">
                <button
                  type="button"
                  class="inline-flex items-center gap-1.5 text-left bg-transparent border-0 cursor-pointer text-fg-primary hover:text-fg-strong transition-colors"
                  :aria-expanded="expandedIds.has(r.id)"
                  :aria-label="`Toggle details for ${r.name}`"
                  @click.stop="toggleExpand(r.id)"
                >
                  <ChevronRightIcon
                    :class="expandedIds.has(r.id) ? 'rotate-90' : ''"
                    class="h-3.5 w-3.5 text-fg-muted shrink-0 transition-transform"
                    aria-hidden="true"
                  />
                  {{ r.name }}
                </button>
              </td>
              <td class="px-4 py-2.5 text-xs text-fg-muted">
                {{ reminderSchedule(r) }}
              </td>
              <td class="px-4 py-2.5 whitespace-nowrap">
                <span
                  :class="statusColors[r.status]"
                  class="text-xs font-mono"
                >{{ r.status }}</span>
              </td>
              <td class="px-4 py-2.5 whitespace-nowrap font-mono text-xs text-fg-muted">
                {{ deliveryLabel(r) }}
              </td>
              <td class="px-4 py-2.5 whitespace-nowrap text-fg-muted text-xs">
                {{ whenLabel(r) }}
              </td>
              <td class="px-4 py-2.5 whitespace-nowrap text-fg-muted text-xs">
                {{ firedAtLabel(r) }}
              </td>
              <td class="px-4 py-2.5 whitespace-nowrap">
                <input
                  v-if="isOneShot(r)"
                  type="checkbox"
                  class="accent-emerald-500 cursor-pointer align-middle"
                  :checked="r.autoDeleteOnComplete"
                  :aria-label="`Auto-delete ${r.name} after it fires`"
                  title="Auto-delete this reminder after a successful fire"
                  @change="toggleAutoDelete(r, ($event.target as HTMLInputElement).checked)"
                >
                <span
                  v-else
                  class="text-fg-muted text-xs"
                >—</span>
              </td>
              <td class="px-4 py-2.5 whitespace-nowrap text-right">
                <button
                  type="button"
                  class="p-1 text-fg-muted hover:text-red-400 transition-colors"
                  title="Delete reminder"
                  :aria-label="`Delete ${r.name}`"
                  @click="deleteOne(r)"
                >
                  <TrashIcon
                    class="w-4 h-4"
                    aria-hidden="true"
                  />
                </button>
              </td>
            </tr>
            <tr
              v-if="expandedIds.has(r.id)"
              class="bg-muted/20"
            >
              <td
                :colspan="reminderColspan"
                class="px-4 py-3"
              >
                <!-- Linkify-only render (NOT markdown): the reminder text is
                     shown verbatim, but bare http(s) URLs become clickable
                     anchors. -->
                <!-- eslint-disable vue/no-v-html -- linkify() HTML-escapes then DOMPurify-sanitizes -->
                <div
                  class="reminder-body max-w-3xl whitespace-pre-wrap break-words text-sm text-fg-primary"
                  v-html="linkify(reminderBody(r))"
                />
                <!-- eslint-enable vue/no-v-html -->
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </section>

    <!-- Calendar view (JCLAW-440): shared component, fire-projection only
         (reminders skip the LLM, so no run-trace blocks). -->
    <ScheduleCalendar
      v-else-if="view === 'calendar'"
      :items="reminders ?? []"
      :show-runs="false"
    />
  </div>
</template>

<style scoped>
/* Autolinked URLs in the expanded reminder body. v-html content isn't touched
   by scoped attributes, so `:deep()` is required to reach the injected <a>.
   Emerald + underline matches the page's other links (the empty-state hint). */
.reminder-body :deep(a) {
  color: #10b981; /* emerald-500 */
  text-decoration: underline;
  text-underline-offset: 2px;
}

.reminder-body :deep(a:hover) {
  color: #34d399; /* emerald-400 */
}
</style>
