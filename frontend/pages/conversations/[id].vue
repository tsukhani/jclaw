<script setup lang="ts">
import type { Conversation, Message } from '~/types/api'
import { computeUsageCostBreakdown } from '~/utils/usage-cost'

const route = useRoute()
const router = useRouter()
const id = Number(route.params.id)

const conversation = ref<Conversation | null>(null)
const messages = ref<Message[]>([])

try {
  const [convos, msgs] = await Promise.all([
    $fetch<Conversation[]>(`/api/conversations?limit=1&offset=0&id=${id}`),
    $fetch<Message[]>(`/api/conversations/${id}/messages`),
  ])
  conversation.value = convos?.[0] ?? null
  messages.value = msgs ?? []
} catch {
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
      hypotheticalCost +=
        ((msg.usage.prompt || 0) / 1_000_000) * breakdown.effectivePromptPrice +
        breakdown.outputCost
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

  const body = messages.value.map((m: any) => {
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
    <div class="flex items-center justify-between mb-4">
      <button @click="router.push('/conversations')" class="text-xs text-fg-muted hover:text-fg-strong transition-colors">&larr; Back to conversations</button>
      <div v-if="conversation" class="flex items-center gap-1">
        <NuxtLink
          :to="`/chat?conversation=${id}`"
          class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
          title="Open in Chat"
        >
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M8 12h.01M12 12h.01M16 12h.01M21 12c0 4.418-4.03 8-9 8a9.863 9.863 0 01-4.255-.949L3 20l1.395-3.72C3.512 15.042 3 13.574 3 12c0-4.418 4.03-8 9-8s9 3.582 9 8z" /></svg>
        </NuxtLink>
        <button @click="exportConversation"
                class="p-1.5 text-fg-muted hover:text-fg-strong transition-colors"
                title="Export conversation as Markdown">
          <svg class="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24"><path stroke-linecap="round" stroke-linejoin="round" stroke-width="1.5" d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" /></svg>
        </button>
      </div>
    </div>

    <div v-if="!conversation" class="text-center text-fg-muted py-12">
      Conversation not found.
    </div>

    <template v-else>
      <div class="bg-surface-elevated border border-border p-4 mb-4">
        <h1 class="text-lg font-semibold text-fg-strong mb-2">{{ conversation.preview || 'Conversation' }}</h1>
        <div class="flex flex-wrap gap-x-6 gap-y-1 text-xs text-fg-muted">
          <span>Channel: <strong class="text-fg-primary">{{ conversation.channelType }}</strong></span>
          <span>Agent: <strong class="text-fg-primary">{{ conversation.agentName }}</strong></span>
          <span>Peer: <strong class="text-fg-primary font-mono">{{ conversation.peerId || '—' }}</strong></span>
          <span>Messages: <strong class="text-fg-primary">{{ conversation.messageCount }}</strong></span>
          <span>Created: <strong class="text-fg-primary">{{ new Date(conversation.createdAt).toLocaleString() }}</strong></span>
          <span>Updated: <strong class="text-fg-primary">{{ new Date(conversation.updatedAt).toLocaleString() }}</strong></span>
        </div>
        <div v-if="conversationStats.inputTokens > 0" class="flex flex-wrap gap-x-6 gap-y-1 text-xs text-fg-muted mt-2 pt-2 border-t border-border">
          <span>Input: <strong class="text-fg-primary">{{ conversationStats.inputTokens.toLocaleString() }}</strong> tokens</span>
          <span v-if="conversationStats.cachedTokens > 0"
                :title="`${conversationStats.cachedTokens.toLocaleString()} of ${conversationStats.inputTokens.toLocaleString()} input tokens served from prompt cache`">
            Cached: <strong class="text-amber-400">{{ conversationStats.cachedTokens.toLocaleString() }}</strong> tokens
          </span>
          <span>Output: <strong class="text-fg-primary">{{ conversationStats.outputTokens.toLocaleString() }}</strong> tokens</span>
          <span v-if="conversationStats.avgTokPerSec">Avg speed: <strong class="text-fg-primary">{{ conversationStats.avgTokPerSec.toFixed(1) }}</strong> tok/s</span>
          <span v-if="conversationStats.totalCost !== null">Cost: <strong class="text-emerald-400">{{ conversationStats.totalCost < 0.0001 ? '< $0.0001' : '$' + conversationStats.totalCost.toFixed(4) }}</strong></span>
          <span v-if="conversationStats.savings !== null"
                :title="`Estimated savings vs. paying full input rate for every token (${conversationStats.savingsPct.toFixed(0)}% off)`">
            Saved: <strong class="text-emerald-400">{{ conversationStats.savings < 0.0001 ? '< $0.0001' : '$' + conversationStats.savings.toFixed(4) }}</strong>
            <span class="text-fg-muted">({{ conversationStats.savingsPct.toFixed(0) }}%)</span>
          </span>
        </div>
      </div>

      <div class="space-y-3">
        <div
          v-for="msg in messages"
          :key="msg.id"
          :class="msg.role === 'user' ? 'ml-16' : msg.role === 'tool' ? 'ml-8' : ''"
        >
          <div class="flex items-center gap-2 mb-0.5">
            <span class="text-xs font-mono text-fg-muted">{{ msg.role }}</span>
            <span class="text-xs text-fg-muted">{{ new Date(msg.createdAt).toLocaleTimeString() }}</span>
          </div>
          <div class="bg-muted border border-border px-3 py-2 text-sm text-fg-primary whitespace-pre-wrap">{{ msg.content || '(tool call)' }}</div>
        </div>
      </div>
    </template>
  </div>
</template>
