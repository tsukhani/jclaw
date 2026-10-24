<script setup lang="ts">
import { ref } from 'vue'
import { useMcpAppBridge } from '~/composables/useMcpAppBridge'
import type { McpAppCall } from '~/types/api'

// One MCP Apps view under its tool call. sandbox="allow-scripts" without allow-same-origin gives the page an
// opaque origin, so it cannot reach JClaw's DOM, storage or session; never add allow-same-origin here.
const props = defineProps<{ call: McpAppCall }>()

const frame = ref<HTMLIFrameElement | null>(null)
const container = ref<HTMLElement | null>(null)
const { state, height, src, onLoad } = useMcpAppBridge(frame, container, () => props.call)
</script>

<template>
  <div
    ref="container"
    data-testid="mcp-app-frame"
    class="border-t border-neutral-200 dark:border-neutral-700 bg-surface-elevated text-fg-primary"
  >
    <!-- Kept at no height until the view initializes, so a page that fails shows only the line below. -->
    <iframe
      v-if="state !== 'unavailable'"
      ref="frame"
      :src="src"
      sandbox="allow-scripts"
      loading="lazy"
      :tabindex="state === 'ready' ? undefined : -1"
      :title="`View for ${call.tool}`"
      class="block w-full border-0"
      :style="{ height: `${height}px` }"
      @load="onLoad"
    />
    <p
      v-if="state !== 'ready'"
      role="status"
      class="px-3 py-2 text-sm italic text-fg-muted"
    >
      {{ state === 'loading' ? 'Loading view…' : 'The view could not be loaded.' }}
    </p>
  </div>
</template>
