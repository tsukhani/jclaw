<script setup lang="ts">
import { BellAlertIcon, TrashIcon, XMarkIcon } from '@heroicons/vue/24/outline'

/**
 * Global notification toast overlay (JCLAW reminders feature).
 *
 * Polls /api/notifications?status=unread every NOTIFICATION_POLL_MS and
 * stacks any returned rows as floating toasts in the bottom-right of the
 * viewport. Clicking a toast acknowledges it (POST /api/notifications/{id}/ack)
 * and animates it out; the trash icon hard-deletes it server-side.
 *
 * Mounted once at layout level (layouts/default.vue) so reminders surface
 * regardless of which page the user is on.
 */

interface NotificationView {
  id: number
  agentId: number | null
  agentName: string | null
  content: string
  sourceTaskRunId: number | null
  sourceTaskId: number | null
  createdAt: string
  acknowledgedAt: string | null
}

const NOTIFICATION_POLL_MS = 10_000

const toasts = ref<NotificationView[]>([])
const seenIds = new Set<number>()
let pollTimer: ReturnType<typeof setInterval> | undefined

async function fetchUnread() {
  // Skip polling when the tab isn't visible — browser throttles intervals
  // anyway, and an obscured tab can't show a toast meaningfully.
  if (typeof document !== 'undefined' && document.visibilityState === 'hidden') return
  try {
    const fresh = await $fetch<NotificationView[]>('/api/notifications?status=unread&limit=20')
    if (!fresh?.length) return
    const incoming = fresh.filter(n => !seenIds.has(n.id))
    if (!incoming.length) return
    for (const n of incoming) seenIds.add(n.id)
    // Newest first: prepend rather than push so a freshly-fired reminder
    // sits at the top of the stack where the eye lands.
    toasts.value = [...incoming, ...toasts.value]
  }
  catch (e) {
    // Auth fail / 5xx / offline: drop silently. The next tick will retry.
    console.warn('NotificationBar poll failed:', e)
  }
}

async function acknowledge(id: number) {
  try {
    await $fetch(`/api/notifications/${id}/ack`, { method: 'POST' })
  }
  catch (e) {
    console.warn('Acknowledge failed:', e)
  }
  toasts.value = toasts.value.filter(t => t.id !== id)
}

async function dismiss(id: number) {
  // User-intent for the trash icon is "delete this reminder entirely" —
  // including the underlying Task row that drives the /reminders page,
  // not just the Notification. Cascade through the source task first
  // (when known) so the row vanishes from /reminders in the same gesture.
  // A 404 from either endpoint is a no-op — both rows might already be
  // gone from a prior delete on a different surface.
  const toast = toasts.value.find(t => t.id === id)
  const sourceTaskId = toast?.sourceTaskId ?? null
  if (sourceTaskId != null) {
    try {
      await $fetch(`/api/tasks/${sourceTaskId}`, { method: 'DELETE' })
    }
    catch (e) {
      console.warn('Source task delete failed (likely already gone):', e)
    }
  }
  try {
    await $fetch(`/api/notifications/${id}`, { method: 'DELETE' })
  }
  catch (e) {
    console.warn('Notification delete failed:', e)
  }
  toasts.value = toasts.value.filter(t => t.id !== id)
}

function formatWhen(iso: string): string {
  // Compact relative display ("just now", "5m ago", "2h ago", "yesterday",
  // then ISO date). Toasts pop in at fire time, so most of them are
  // <minute-old — keep the format tight.
  const t = Date.parse(iso)
  if (!Number.isFinite(t)) return ''
  const deltaSec = Math.max(0, Math.round((Date.now() - t) / 1000))
  if (deltaSec < 30) return 'just now'
  if (deltaSec < 3600) return `${Math.round(deltaSec / 60)}m ago`
  if (deltaSec < 86400) return `${Math.round(deltaSec / 3600)}h ago`
  if (deltaSec < 2 * 86400) return 'yesterday'
  return new Date(t).toISOString().slice(0, 10)
}

onMounted(() => {
  void fetchUnread()
  pollTimer = setInterval(fetchUnread, NOTIFICATION_POLL_MS)
})

onUnmounted(() => {
  if (pollTimer != null) clearInterval(pollTimer)
})
</script>

<template>
  <Teleport to="body">
    <div
      class="fixed top-20 right-4 z-50 flex flex-col gap-2 pointer-events-none"
      aria-live="polite"
      aria-atomic="false"
    >
      <TransitionGroup
        name="toast"
        tag="div"
        class="flex flex-col gap-2"
      >
        <!-- Toast card uses ARIA status live-region semantics so updates are announced politely; <output> is form-associated and inline (the wrong semantics + layout for a standalone notification). -->
        <article
          v-for="t in toasts"
          :key="t.id"
          role="status"
          class="pointer-events-auto w-80 max-w-[calc(100vw-2rem)] rounded-lg border border-zinc-200 bg-white px-4 py-3 shadow-lg dark:border-zinc-700 dark:bg-zinc-900"
        >
          <div class="flex items-start gap-3">
            <BellAlertIcon
              class="mt-0.5 h-5 w-5 shrink-0 text-amber-500 dark:text-amber-400"
              aria-hidden="true"
            />
            <div class="flex-1 min-w-0">
              <div class="flex items-center justify-between gap-2">
                <span class="text-xs font-semibold uppercase tracking-wide text-amber-700 dark:text-amber-400">
                  Reminder
                </span>
                <span
                  v-if="t.createdAt"
                  class="text-[0.65rem] text-amber-700 dark:text-amber-400"
                >
                  {{ formatWhen(t.createdAt) }}
                </span>
              </div>
              <p class="mt-1 whitespace-pre-wrap text-sm text-zinc-900 dark:text-zinc-100">
                {{ t.content }}
              </p>
              <div class="mt-2 flex items-center justify-between gap-3">
                <button
                  class="text-xs font-medium text-amber-700 hover:text-amber-700 dark:text-amber-400 dark:hover:text-amber-300"
                  @click="acknowledge(t.id)"
                >
                  Mark as seen
                </button>
                <button
                  class="rounded p-1 text-amber-500 hover:bg-amber-50 hover:text-amber-600 dark:text-amber-400 dark:hover:bg-amber-900/30 dark:hover:text-amber-300"
                  title="Delete reminder"
                  aria-label="Delete reminder"
                  @click="dismiss(t.id)"
                >
                  <TrashIcon
                    class="h-4 w-4"
                    aria-hidden="true"
                  />
                </button>
              </div>
            </div>
            <button
              class="text-zinc-400 hover:text-zinc-600 dark:text-zinc-500 dark:hover:text-zinc-300"
              aria-label="Dismiss"
              @click="acknowledge(t.id)"
            >
              <XMarkIcon
                class="h-4 w-4"
                aria-hidden="true"
              />
            </button>
          </div>
        </article>
      </TransitionGroup>
    </div>
  </Teleport>
</template>

<style scoped>
.toast-enter-active,
.toast-leave-active {
  transition: opacity 200ms ease, transform 200ms ease;
}

.toast-enter-from {
  opacity: 0;
  transform: translateX(20px);
}

.toast-leave-to {
  opacity: 0;
  transform: translateX(20px);
}
</style>
