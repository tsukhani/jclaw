<script setup lang="ts">
import { ChevronDownIcon, CommandLineIcon, FolderIcon, GlobeAltIcon, WrenchIcon, WrenchScrewdriverIcon } from '@heroicons/vue/24/outline'
import type { ToolCall, ToolCallResultChip } from '~/types/api'

// JCLAW-170: the tool-calls accordion for an assistant turn. Card-level collapse
// is parent-owned (set on reload / stream-completion), so `collapsed` is a value
// prop and the toggle round-trips through the parent; per-call `_expanded` also
// lives on the tc objects and is set by the stream/reload logic, so toggle-call
// emits up too. The parent passes a fresh `[...msg.toolCalls]` each render so this
// card re-renders in lockstep with the (shallowRef + triggerRef) message store —
// matching the behaviour of the old inline block.
defineProps<{ toolCalls: ToolCall[], collapsed: boolean }>()
const emit = defineEmits<{
  (e: 'toggle-collapse'): void
  (e: 'toggle-call', tc: ToolCall): void
}>()

/**
 * JCLAW-170: resolve the registry's semantic icon key to a Heroicon component.
 * Keys beyond this switch default to the generic wrench so an unknown tool
 * still renders visibly rather than as a blank cell.
 */
function toolCallIcon(key: string | null | undefined) {
  switch (key) {
    case 'search': return GlobeAltIcon
    case 'folder': return FolderIcon
    case 'terminal':
    case 'shell': return CommandLineIcon
    case 'wrench': return WrenchIcon
    default: return WrenchScrewdriverIcon
  }
}

/**
 * JCLAW-170: compact one-line preview of a tool call's arguments. For
 * web_search the query gets wrapped in quotes to match the "Searched <q>"
 * label the reference UX uses; other tools show their first argument name
 * and value, truncated. Falls back to the raw JSON slice on parse failure.
 */
function toolCallPreview(tc: ToolCall): string {
  if (!tc.arguments) return ''
  try {
    const parsed = JSON.parse(tc.arguments) as Record<string, unknown>
    if (tc.name === 'web_search' && typeof parsed.query === 'string') {
      return `Searched "${parsed.query}"`
    }
    const keys = Object.keys(parsed)
    if (keys.length === 0) return tc.name
    const first = keys[0]!
    const v = parsed[first]
    const preview = typeof v === 'string' ? v : JSON.stringify(v)
    return `${first}: ${String(preview).slice(0, 80)}`
  }
  catch {
    return tc.arguments.slice(0, 80)
  }
}

const MAX_VISIBLE_RESULT_CHIPS = 6
const MAX_RESULT_TEXT_PREVIEW = 600

/** JCLAW-170: structured chips that belong to a single tool call. Returned
 *  in their original order; the caller slices for "show first N + N more"
 *  display. Tools without a structured payload return []. */
function chipsForToolCall(tc: ToolCall): ToolCallResultChip[] {
  return tc.resultStructured?.results ?? []
}

function visibleChipsForCall(tc: ToolCall): ToolCallResultChip[] {
  return chipsForToolCall(tc).slice(0, MAX_VISIBLE_RESULT_CHIPS)
}

function extraChipCountForCall(tc: ToolCall): number {
  return Math.max(0, chipsForToolCall(tc).length - MAX_VISIBLE_RESULT_CHIPS)
}

/** Truncated text preview for tools that return plain text rather than a
 *  structured result list. Keeps the per-call expansion useful for shell,
 *  filesystem, web_fetch — anything whose output is the LLM-visible string.
 *  Long results are clipped with an ellipsis so the block doesn't dominate
 *  the transcript; clicking through to copy the full result happens via the
 *  larger UX, not the per-call peek. */
function truncatedToolResultText(tc: ToolCall): string {
  const text = (tc.resultText ?? '').trim()
  if (!text) return ''
  if (text.length <= MAX_RESULT_TEXT_PREVIEW) return text
  return text.slice(0, MAX_RESULT_TEXT_PREVIEW) + '…'
}

function toolCallHasExpandableBody(tc: ToolCall): boolean {
  return chipsForToolCall(tc).length > 0 || !!truncatedToolResultText(tc)
}

function chipTitle(chip: ToolCallResultChip): string {
  if (chip.title && chip.title.trim()) return chip.title.trim()
  if (chip.url) {
    try {
      return new URL(chip.url).hostname.replace(/^www\./, '')
    }
    catch {
      return chip.url
    }
  }
  return 'result'
}

/** Favicon load failures fall back to the generic globe. We swap the <img>
 *  for a hidden element and let the adjacent GlobeAltIcon take over. */
function onFaviconError(ev: Event) {
  const img = ev.target as HTMLImageElement | null
  if (img) img.style.display = 'none'
}
</script>

<template>
  <div class="mb-3 border border-neutral-200 dark:border-neutral-700 rounded-xl overflow-hidden bg-surface-elevated">
    <button
      type="button"
      class="w-full flex items-center gap-2 px-3 py-2 text-left text-xs text-fg-muted hover:text-fg-strong focus:outline-none"
      :title="collapsed ? 'Expand tool calls' : 'Collapse tool calls'"
      @click="emit('toggle-collapse')"
    >
      <WrenchScrewdriverIcon
        class="w-3.5 h-3.5 shrink-0"
        aria-hidden="true"
      />
      <span class="font-medium">
        {{ toolCalls.length }} tool {{ toolCalls.length === 1 ? 'call' : 'calls' }}
      </span>
      <ChevronDownIcon
        class="w-3.5 h-3.5 transition-transform ml-auto"
        :class="collapsed ? '' : 'rotate-180'"
        aria-hidden="true"
      />
    </button>
    <div
      v-if="!collapsed"
      class="border-t border-neutral-200 dark:border-neutral-700"
    >
      <div
        v-for="tc in toolCalls"
        :key="tc.id"
        class="border-b border-neutral-100 dark:border-neutral-800 last:border-b-0"
      >
        <button
          type="button"
          class="w-full flex items-center gap-2 px-3 py-3 text-left text-sm text-fg-muted hover:text-fg-strong focus:outline-none disabled:cursor-default disabled:hover:text-fg-muted"
          :disabled="!toolCallHasExpandableBody(tc)"
          :title="toolCallHasExpandableBody(tc) ? (tc._expanded ? 'Collapse this call' : 'Expand this call') : ''"
          @click="emit('toggle-call', tc)"
        >
          <component
            :is="toolCallIcon(tc.icon)"
            class="w-3.5 h-3.5 shrink-0"
            aria-hidden="true"
          />
          <span class="truncate">
            <span class="text-fg-subtle">Used tool:</span>
            {{ toolCallPreview(tc) }}
          </span>
          <ChevronDownIcon
            v-if="toolCallHasExpandableBody(tc)"
            class="w-3.5 h-3.5 transition-transform ml-auto"
            :class="tc._expanded ? 'rotate-180' : '-rotate-90'"
            aria-hidden="true"
          />
        </button>
        <div
          v-if="tc._expanded && chipsForToolCall(tc).length"
          class="flex flex-wrap gap-1.5 px-3 pb-3"
        >
          <a
            v-for="(chip, cIdx) in visibleChipsForCall(tc)"
            :key="(chip.url ?? chip.title ?? '') + ':' + cIdx"
            :href="chip.url ?? '#'"
            target="_blank"
            rel="noopener noreferrer"
            class="inline-flex items-center gap-1.5 px-2 py-1 text-sm border border-neutral-200 dark:border-neutral-700 rounded-full text-fg-muted hover:text-fg-strong hover:bg-surface transition-colors max-w-[200px]"
            :title="chip.title ?? chip.url ?? ''"
          >
            <img
              v-if="chip.faviconUrl"
              :src="chip.faviconUrl"
              class="w-3.5 h-3.5 shrink-0 rounded-sm"
              alt=""
              referrerpolicy="no-referrer"
              @error="onFaviconError"
            >
            <GlobeAltIcon
              v-else
              class="w-3.5 h-3.5 shrink-0"
              aria-hidden="true"
            />
            <span class="truncate">{{ chipTitle(chip) }}</span>
          </a>
          <span
            v-if="extraChipCountForCall(tc) > 0"
            class="inline-flex items-center px-2 py-1 text-sm border border-dashed border-neutral-200 dark:border-neutral-700 rounded-full text-fg-subtle"
          >+{{ extraChipCountForCall(tc) }} more</span>
        </div>
        <pre
          v-else-if="tc._expanded && truncatedToolResultText(tc)"
          class="px-3 pb-3 text-sm text-fg-muted whitespace-pre-wrap break-words"
        >{{ truncatedToolResultText(tc) }}</pre>
      </div>
    </div>
  </div>
</template>
