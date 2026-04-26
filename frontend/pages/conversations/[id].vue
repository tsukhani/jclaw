<script setup lang="ts">
import {
  ArrowDownTrayIcon,
  ChatBubbleOvalLeftEllipsisIcon,
} from '@heroicons/vue/24/outline'
import type { Conversation, Message } from '~/types/api'
import { computeUsageCostBreakdown } from '~/utils/usage-cost'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)

const conversation = ref<Conversation | null>(null)
const messages = ref<Message[]>([])

try {
  // JCLAW-171: fetch the specific conversation by id. The previous
  // pattern asked the list endpoint for {@code ?id=N}, but that
  // parameter is silently ignored — the list returned the most-recent
  // conversation instead, so the metadata header pointed at the wrong
  // row even though the message body underneath was correct.
  const [convo, msgs] = await Promise.all([
    $fetch<Conversation>(`/api/conversations/${id}`),
    $fetch<Message[]>(`/api/conversations/${id}/messages`),
  ])
  conversation.value = convo ?? null
  messages.value = msgs ?? []
}
catch {
  // handled by empty state below
}

const conversationStats = computed(() => {
  let inputTokens = 0
  let outputTokens = 0
  let cachedTokens = 0
  let totalCost = 0
  let hypotheticalCost = 0
  let totalDurationMs = 0
  let totalOutputTokens = 0
  let hasCost = false

  for (const msg of messages.value) {
    if (!msg.usage) continue
    inputTokens += msg.usage.prompt || 0
    outputTokens += msg.usage.completion || 0
    cachedTokens += msg.usage.cached || 0
    if (msg.usage.durationMs > 0 && msg.usage.completion > 0) {
      totalDurationMs += msg.usage.durationMs
      totalOutputTokens += msg.usage.completion
    }
    const breakdown = computeUsageCostBreakdown(msg.usage)
    if (breakdown) {
      totalCost += breakdown.total
      // Re-price every input token at the uncached rate to get the cost
      // this turn *would* have been without caching.
      hypotheticalCost
        += ((msg.usage.prompt || 0) / 1_000_000) * breakdown.effectivePromptPrice
          + breakdown.outputCost
      hasCost = true
    }
  }

  const avgTokPerSec = totalDurationMs > 0
    ? (totalOutputTokens / totalDurationMs) * 1000
    : null

  const savings = hasCost ? hypotheticalCost - totalCost : 0
  const savingsPct = hasCost && hypotheticalCost > 0
    ? (savings / hypotheticalCost) * 100
    : 0

  return {
    inputTokens,
    outputTokens,
    cachedTokens,
    avgTokPerSec,
    totalCost: hasCost ? totalCost : null,
    savings: hasCost && savings > 0 ? savings : null,
    savingsPct,
  }
})

/**
 * Latest assistant turn at or before {@code idx}, used to detect a
 * mid-conversation model change. Mirrors {@code chat.vue}'s logic so the
 * "Switched to X" divider lands at the same boundaries on the detail page.
 */
function previousAssistantUsage(idx: number) {
  for (let i = idx - 1; i >= 0; i--) {
    const m = messages.value[i]
    if (m && m.role === 'assistant' && m.usage) return m.usage
  }
  return null
}

function shouldShowModelSwitchIndicator(idx: number): boolean {
  const msg = messages.value[idx]
  if (!msg || msg.role !== 'assistant' || !msg.usage?.modelId) return false
  const prior = previousAssistantUsage(idx)
  if (!prior || !prior.modelId) return false
  return prior.modelId !== msg.usage.modelId || prior.modelProvider !== msg.usage.modelProvider
}

function formatModelLabel(msg: Message): string {
  const u = msg.usage
  if (!u) return '?'
  return u.modelProvider ? `${u.modelProvider}/${u.modelId ?? '?'}` : (u.modelId ?? '?')
}

function exportConversation() {
  const c = conversation.value
  if (!c) return
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

  const body = messages.value.map((m) => {
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
    <button
      class="text-xs text-fg-muted hover:text-fg-strong transition-colors mb-4"
      @click="router.push('/conversations')"
    >
      &larr; Back to conversations
    </button>

    <div
      v-if="!conversation"
      class="text-center text-fg-muted py-12"
    >
      Conversation not found.
    </div>

    <template v-else>
      <!--
        JCLAW-176: restructured header. Three visually distinct zones stacked
        in a single bordered card so each conceptual layer reads on its own:

          1. Title zone  — preview text + secondary "{agent} · {channel}"
             context line, with the action icons pinned on the right. Sets
             itself apart from the metadata via a real bottom border.
          2. Identity zone — six-cell definition-list grid for the conversation's
             fixed metadata (channel, agent, peer, messages, created, updated).
          3. Usage zone — same grid shape for the aggregated token/cost stats,
             on a tinted surface so the "this is about cost, not identity"
             grouping lands visually without another heading.

        Each cell uses small-caps label over mono value so the 12 items read
        as discrete key/value pairs rather than an inline run-on sentence.
      -->
      <div class="bg-surface-elevated border border-border rounded-md mb-4">
        <!-- Title zone -->
        <div class="flex items-start justify-between gap-4 p-4 border-b border-border">
          <div class="min-w-0">
            <h1 class="text-lg font-semibold text-fg-strong truncate">
              {{ conversation.preview || 'Conversation' }}
            </h1>
            <p class="mt-0.5 text-xs text-fg-muted">
              <span class="font-mono text-fg-primary">{{ conversation.agentName }}</span>
              <span class="mx-1.5">·</span>
              <span>{{ conversation.channelType }}</span>
            </p>
          </div>
          <div class="flex items-center gap-1 shrink-0">
            <NuxtLink
              :to="`/chat?conversation=${id}`"
              class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
              title="Open in Chat"
            >
              <ChatBubbleOvalLeftEllipsisIcon
                class="w-5 h-5"
                aria-hidden="true"
              />
            </NuxtLink>
            <button
              class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
              title="Export conversation as Markdown"
              @click="exportConversation"
            >
              <ArrowDownTrayIcon
                class="w-5 h-5"
                aria-hidden="true"
              />
            </button>
          </div>
        </div>

        <!-- Identity zone -->
        <dl class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4 p-4">
          <div>
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Channel
            </dt>
            <dd class="mt-1 text-sm font-mono text-fg-primary truncate">
              {{ conversation.channelType }}
            </dd>
          </div>
          <div>
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Agent
            </dt>
            <dd class="mt-1 text-sm font-mono text-fg-primary truncate">
              {{ conversation.agentName }}
            </dd>
          </div>
          <div>
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Peer
            </dt>
            <dd class="mt-1 text-sm font-mono text-fg-primary truncate">
              {{ conversation.peerId || '—' }}
            </dd>
          </div>
          <div>
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Messages
            </dt>
            <dd class="mt-1 text-sm font-mono text-fg-primary tabular-nums">
              {{ conversation.messageCount }}
            </dd>
          </div>
          <div>
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Created
            </dt>
            <dd class="mt-1 text-sm font-mono text-fg-primary tabular-nums">
              {{ new Date(conversation.createdAt).toLocaleString() }}
            </dd>
          </div>
          <div>
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Updated
            </dt>
            <dd class="mt-1 text-sm font-mono text-fg-primary tabular-nums">
              {{ new Date(conversation.updatedAt).toLocaleString() }}
            </dd>
          </div>
        </dl>

        <!-- Usage zone -->
        <dl
          v-if="conversationStats.inputTokens > 0"
          class="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-6 gap-4 p-4 border-t border-border bg-muted/40"
        >
          <div>
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Input tokens
            </dt>
            <dd class="mt-1 text-sm font-mono text-fg-primary tabular-nums">
              {{ conversationStats.inputTokens.toLocaleString() }}
            </dd>
          </div>
          <div
            v-if="conversationStats.cachedTokens > 0"
            :title="`${conversationStats.cachedTokens.toLocaleString()} of ${conversationStats.inputTokens.toLocaleString()} input tokens served from prompt cache`"
          >
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Cached
            </dt>
            <dd class="mt-1 text-sm font-mono text-amber-400 tabular-nums">
              {{ conversationStats.cachedTokens.toLocaleString() }}
            </dd>
          </div>
          <div>
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Output tokens
            </dt>
            <dd class="mt-1 text-sm font-mono text-fg-primary tabular-nums">
              {{ conversationStats.outputTokens.toLocaleString() }}
            </dd>
          </div>
          <div v-if="conversationStats.avgTokPerSec">
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Avg speed
            </dt>
            <dd class="mt-1 text-sm font-mono text-fg-primary tabular-nums">
              {{ conversationStats.avgTokPerSec.toFixed(1) }} <span class="text-fg-muted">tok/s</span>
            </dd>
          </div>
          <div v-if="conversationStats.totalCost !== null">
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Cost
            </dt>
            <dd class="mt-1 text-sm font-mono text-emerald-400 tabular-nums">
              {{ conversationStats.totalCost < 0.0001 ? '< $0.0001' : '$' + conversationStats.totalCost.toFixed(4) }}
            </dd>
          </div>
          <div
            v-if="conversationStats.savings !== null"
            :title="`Estimated savings vs. paying full input rate for every token (${conversationStats.savingsPct.toFixed(0)}% off)`"
          >
            <dt class="text-[10px] font-medium uppercase tracking-wider text-fg-muted">
              Saved
            </dt>
            <dd class="mt-1 text-sm font-mono text-emerald-400 tabular-nums">
              {{ conversationStats.savings < 0.0001 ? '< $0.0001' : '$' + conversationStats.savings.toFixed(4) }}
              <span class="text-fg-muted">({{ conversationStats.savingsPct.toFixed(0) }}%)</span>
            </dd>
          </div>
        </dl>
      </div>

      <div class="space-y-3">
        <template
          v-for="(msg, msgIdx) in messages"
          :key="msg.id"
        >
          <!-- JCLAW-108 parity: divider when two adjacent assistant messages
               ran on different models, mirrored from the live chat page so
               mid-conversation /model switches are visible in the historical
               transcript too. -->
          <div
            v-if="shouldShowModelSwitchIndicator(msgIdx)"
            class="flex items-center gap-3 text-xs text-fg-muted select-none"
          >
            <span class="flex-1 border-t border-border-subtle" />
            <span class="whitespace-nowrap">Switched to {{ formatModelLabel(msg) }}</span>
            <span class="flex-1 border-t border-border-subtle" />
          </div>
          <div :class="msg.role === 'user' ? 'ml-16' : msg.role === 'tool' ? 'ml-8' : ''">
            <div class="flex items-center gap-2 mb-0.5">
              <span class="text-xs font-mono text-fg-muted">{{ msg.role }}</span>
              <span class="text-xs text-fg-muted">{{ new Date(msg.createdAt).toLocaleTimeString() }}</span>
              <span
                v-if="msg.role === 'assistant' && msg.usage?.modelId"
                class="text-xs font-mono text-fg-muted"
                :title="`Generated by ${formatModelLabel(msg)}`"
              >
                · {{ formatModelLabel(msg) }}
              </span>
            </div>
            <div class="bg-muted border border-border px-3 py-2 text-sm text-fg-primary whitespace-pre-wrap">
              {{ msg.content || '(tool call)' }}
            </div>
          </div>
        </template>
      </div>
    </template>
  </div>
</template>
