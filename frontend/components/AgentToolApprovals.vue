<script setup lang="ts">
/**
 * JCLAW-1062: standing tool-approval grants for one agent, with revoke.
 *
 * An "always allow" tap on an approval prompt writes a grant that suppresses the
 * prompt for that (agent, tool) pair permanently and survives restarts. This is the
 * only way to take one back short of deleting the agent.
 *
 * Management lives here rather than in Settings because a grant is per-agent; the
 * Settings panel carries a read-only roll-up that links back to these pages.
 */
import type { ApiErrorDetails } from '~/types/api'

const props = defineProps<{ agentId: number | null }>()

interface Grant { id: number, toolName: string }

const grants = ref<Grant[]>([])
const loading = ref(false)
const error = ref<ApiErrorDetails | null>(null)
const revoking = ref<string | null>(null)

const { mutate, errorDetails: revokeError } = useApiMutation()

async function load() {
  if (!props.agentId) {
    grants.value = []
    return
  }
  loading.value = true
  error.value = null
  try {
    grants.value = await $fetch<Grant[]>(`/api/agents/${props.agentId}/tool-approvals`)
  }
  catch (e) {
    error.value = apiErrorDetails(e, 'Could not load standing approvals.')
  }
  finally {
    loading.value = false
  }
}

async function revoke(toolName: string) {
  if (!props.agentId) return
  revoking.value = toolName
  const ok = await mutate(
    `/api/agents/${props.agentId}/tool-approvals/${encodeURIComponent(toolName)}`,
    { method: 'DELETE' })
  revoking.value = null
  if (ok !== null) await load()
}

watch(() => props.agentId, load, { immediate: true })
defineExpose({ reload: load })
</script>

<template>
  <div
    v-if="agentId"
    class="bg-surface-elevated border border-border"
  >
    <div class="px-4 py-2.5 border-b border-border flex items-center justify-between">
      <div class="flex items-center gap-2">
        <span class="text-sm font-medium text-fg-strong">Standing Tool Approvals</span>
        <span class="text-xs text-fg-muted">{{ grants.length }}</span>
      </div>
    </div>

    <p class="px-4 py-2 text-xs text-fg-muted">
      Tools this agent may run without being challenged, from an “always allow” tap.
      A grant has no channel dimension: it applies wherever the agent runs, including
      group chats where guests can reach it.
    </p>

    <ApiErrorAlert
      v-if="error"
      :error="error"
      class="px-4 pb-3"
    />

    <p
      v-else-if="loading"
      class="px-4 pb-3 text-xs text-fg-muted"
    >
      Loading…
    </p>

    <p
      v-else-if="!grants.length"
      class="px-4 pb-3 text-xs text-fg-muted"
    >
      None. Every dangerous action is prompted.
    </p>

    <div
      v-else
      class="divide-y divide-border"
    >
      <div
        v-for="grant in grants"
        :key="grant.id"
        class="px-4 py-2.5 flex items-center justify-between gap-3"
      >
        <span class="font-mono text-xs text-fg-strong">{{ grant.toolName }}</span>
        <button
          type="button"
          class="text-xs px-2 py-1 border border-border text-fg-strong hover:bg-muted disabled:opacity-50"
          :disabled="revoking === grant.toolName"
          :data-testid="`revoke-${grant.toolName}`"
          @click="revoke(grant.toolName)"
        >
          {{ revoking === grant.toolName ? 'Revoking…' : 'Revoke' }}
        </button>
      </div>
    </div>

    <ApiErrorAlert
      :error="revokeError"
      class="px-4 py-2.5 border-t border-border"
    />
  </div>
</template>
