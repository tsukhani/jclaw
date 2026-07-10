<script setup lang="ts">
import { ExclamationTriangleIcon, UsersIcon } from '@heroicons/vue/24/outline'
import { renderMarkdown } from '~/utils/chat-markdown'
import type { Message } from '~/types/api'

// JCLAW-291: the subagent_announce completion card — the SYSTEM-role row that
// closes an async subagent spawn. Shows the child's label, a status badge, the
// child's reply rendered through the shared markdown path, a "View full →" link
// to the child conversation, and a truncation marker when the reply hit
// max_tokens. The v-if (messageKind) and collapse v-show stay in the parent,
// which owns the per-run slice state; the announce payload is immutable once
// written, so the plain `msg` object prop is enough.
defineProps<{ msg: Message, agentId: number | null }>()

/**
 * JCLAW-270: helpers to read the structured async-spawn announce payload.
 * Each pulls one field out of the metadata blob; unsafe casts are isolated
 * here so the template stays simple, with sensible fallbacks so a malformed
 * payload never crashes the render.
 */
type AnnouncePayload = { runId?: number, label?: string, status?: string, reply?: string, childConversationId?: number, truncated?: boolean }
function readAnnounce(m: Message): AnnouncePayload {
  const meta = (m as Message & { metadata?: AnnouncePayload }).metadata
  return (meta ?? {}) as AnnouncePayload
}
function subagentAnnounceLabel(m: Message): string {
  return readAnnounce(m).label?.trim() ?? ''
}
function subagentAnnounceStatus(m: Message): string {
  return readAnnounce(m).status ?? 'COMPLETED'
}
function subagentAnnounceReply(m: Message): string {
  return readAnnounce(m).reply ?? ''
}
function subagentAnnounceChildId(m: Message): number | null {
  const id = readAnnounce(m).childConversationId
  return typeof id === 'number' ? id : null
}
function subagentAnnounceTruncated(m: Message): boolean {
  return !!(readAnnounce(m).truncated || m.truncated)
}
</script>

<template>
  <div
    class="flex justify-start"
    data-testid="subagent-announce-card"
  >
    <div class="max-w-[85%] w-full min-w-0">
      <div class="border border-neutral-200 dark:border-neutral-700 rounded-xl bg-surface-elevated overflow-hidden">
        <div class="flex items-center gap-2 px-3 py-2 border-b border-neutral-200 dark:border-neutral-700 bg-muted/40">
          <UsersIcon
            class="w-3.5 h-3.5 shrink-0 text-fg-muted"
            aria-hidden="true"
          />
          <span class="text-xs font-medium text-fg-strong truncate">
            Subagent: {{ subagentAnnounceLabel(msg) || 'run' }}
          </span>
          <span
            class="px-1.5 py-0.5 text-[10px] font-mono uppercase tracking-wide rounded shrink-0"
            :class="{
              'bg-emerald-100 dark:bg-emerald-900/40 text-emerald-700 dark:text-emerald-300': subagentAnnounceStatus(msg) === 'COMPLETED',
              'bg-red-100 dark:bg-red-900/40 text-red-700 dark:text-red-300': subagentAnnounceStatus(msg) === 'FAILED' || subagentAnnounceStatus(msg) === 'TIMEOUT',
              'bg-muted text-fg-muted': !['COMPLETED', 'FAILED', 'TIMEOUT'].includes(subagentAnnounceStatus(msg)),
            }"
          >
            {{ subagentAnnounceStatus(msg) }}
          </span>
          <NuxtLink
            v-if="subagentAnnounceChildId(msg)"
            :to="`/chat?conversation=${subagentAnnounceChildId(msg)}`"
            class="ml-auto text-xs text-fg-muted hover:text-fg-primary underline-offset-2 hover:underline shrink-0"
            data-testid="subagent-announce-view-full"
          >
            View full →
          </NuxtLink>
        </div>
        <!-- eslint-disable vue/no-v-html -- renderMarkdown runs content through DOMPurify before returning. -->
        <div
          class="prose-chat px-3 py-2 text-sm text-fg-strong overflow-x-auto break-words"
          v-html="renderMarkdown(subagentAnnounceReply(msg), agentId)"
        />
        <!-- eslint-enable vue/no-v-html -->
        <div
          v-if="subagentAnnounceTruncated(msg)"
          class="flex items-center gap-1.5 px-3 py-1.5 text-[11px] text-amber-700 dark:text-amber-400 border-t border-neutral-200 dark:border-neutral-700 bg-amber-50/50 dark:bg-amber-950/20"
          data-testid="truncated-marker"
        >
          <ExclamationTriangleIcon
            class="w-3.5 h-3.5 shrink-0"
            aria-hidden="true"
          />
          <span>Reply was truncated by the model</span>
        </div>
      </div>
    </div>
  </div>
</template>
