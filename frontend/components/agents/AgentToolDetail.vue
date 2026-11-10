<script setup lang="ts">
import type { ToolDetail } from '~/composables/useToolMeta'
import type { ApiErrorDetails } from '~/types/api'

/** One opened row of the agent editor's tool lists: what GET /api/tools/{name} returned for it. */
defineProps<{
  detail: ToolDetail | null
  error: ApiErrorDetails | null
  /** What the list calls the tool's actions: a native tool's functions, an MCP server's actions. */
  heading: string
  /** A native tool's description says what it does; an MCP handle's is the model's calling convention, so it stays out. */
  showDescription?: boolean
}>()
</script>

<template>
  <div class="px-4 pb-3 pl-14">
    <ApiErrorAlert
      v-if="error"
      :error="error"
    />
    <p
      v-else-if="!detail"
      role="status"
      class="text-xs text-fg-muted"
    >
      Loading…
    </p>
    <div
      v-else
      class="border border-border"
      data-testid="agent-tool-detail"
    >
      <p
        v-if="showDescription && detail.description"
        class="px-4 py-2.5 border-b border-border text-xs text-fg-muted leading-relaxed whitespace-pre-line"
      >
        {{ detail.description }}
      </p>
      <!-- Hairline dividers and zero-padded indices keep a 119-action server (google-workspace) scannable. -->
      <div class="flex items-center justify-between px-4 py-2 bg-muted/40 border-b border-border">
        <span class="text-[11px] uppercase tracking-wider font-medium text-fg-muted">
          {{ heading }}
        </span>
        <span class="text-[11px] font-mono tabular-nums text-fg-muted">
          {{ detail.actions.length }}
        </span>
      </div>
      <ol class="divide-y divide-border/60">
        <li
          v-for="(action, idx) in detail.actions"
          :key="action.name"
          class="grid grid-cols-[2.5rem_1fr] items-baseline gap-x-3 px-4 py-2.5 hover:bg-muted/40 transition-colors"
        >
          <span class="text-xs font-mono tabular-nums text-fg-muted select-none">
            {{ String(idx + 1).padStart(3, '0') }}
          </span>
          <div class="flex flex-col gap-0.5 min-w-0">
            <span class="font-mono text-xs text-fg-strong">{{ action.name }}</span>
            <span
              v-if="action.description"
              class="text-xs text-fg-muted leading-relaxed"
            >{{ action.description }}</span>
          </div>
        </li>
      </ol>
    </div>
  </div>
</template>
