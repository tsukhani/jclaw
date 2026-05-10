<script setup lang="ts">
import {
  CheckCircleIcon,
  ChevronDownIcon,
  ClipboardDocumentCheckIcon,
  ClockIcon,
  CommandLineIcon,
  ComputerDesktopIcon,
  DocumentTextIcon,
  FolderIcon,
  GlobeAltIcon,
  MagnifyingGlassIcon,
} from '@heroicons/vue/24/outline'
import type { FunctionalComponent } from 'vue'
import type { ToolMeta } from '~/composables/useToolMeta'

// Maps the backend-supplied icon name (a stable string contract — see
// composables/useToolMeta.ts) to the concrete Heroicons component. Unknown
// keys fall back to DocumentTextIcon so a new backend icon never breaks
// the grid while we catch up on the frontend side.
const TOOL_ICONS: Record<string, FunctionalComponent> = {
  terminal: CommandLineIcon,
  folder: FolderIcon,
  document: DocumentTextIcon,
  globe: GlobeAltIcon,
  search: MagnifyingGlassIcon,
  browser: ComputerDesktopIcon,
  clock: ClockIcon,
  check: CheckCircleIcon,
  tasks: ClipboardDocumentCheckIcon,
}
function iconFor(name: string): FunctionalComponent {
  return TOOL_ICONS[name] ?? DocumentTextIcon
}

// JCLAW-173: this page is a read-only catalog. Per-tool enable/disable
// lives on the agent detail page (pages/agents.vue) — the tool registry
// itself is global, but binding a tool to a specific agent is the only
// meaningful axis. The previous "global toggle" was a fan-out that wrote
// to every agent's AgentToolConfig row, which was a leaky abstraction.
const { TOOL_META, ORDERED_TOOLS } = useToolMeta()

const CATEGORIES = ['All', 'System', 'Web', 'Files', 'Utilities', 'MCP'] as const

// ─── Derived lists ─────────────────────────────────────────────────────────────

interface ToolCard {
  name: string
  meta: ToolMeta
}

const allTools = computed<ToolCard[]>(() =>
  ORDERED_TOOLS.value
    .map(name => ({ name, meta: TOOL_META.value[name] }))
    .filter((t): t is ToolCard => !!t.meta && !t.meta.system),
)

const activeCategory = ref<typeof CATEGORIES[number]>('All')

const filteredTools = computed(() =>
  activeCategory.value === 'All'
    ? allTools.value
    : allTools.value.filter(t => t.meta.category === activeCategory.value),
)

// ─── Expand/collapse ──────────────────────────────────────────────────────────

const expandedSet = ref(new Set<string>())

function toggleExpand(name: string) {
  const s = new Set(expandedSet.value)
  if (s.has(name)) s.delete(name)
  else s.add(name)
  expandedSet.value = s
}

const allExpanded = computed(() =>
  filteredTools.value.length > 0
  && filteredTools.value.every(t => expandedSet.value.has(t.name)),
)

function toggleAllExpanded() {
  if (allExpanded.value) {
    expandedSet.value = new Set()
  }
  else {
    expandedSet.value = new Set(filteredTools.value.map(t => t.name))
  }
}
</script>

<template>
  <div>
    <!-- Header -->
    <div class="mb-6">
      <h1 class="text-lg font-semibold text-fg-strong">
        Tools
      </h1>
      <p class="mt-1 text-sm text-fg-muted">
        Built-in capabilities available to every agent. Open an agent's
        detail page to bind or unbind a tool for that specific agent.
      </p>
    </div>

    <!-- Category filter + global expand/collapse -->
    <div class="flex items-center justify-between gap-3 mb-6">
      <div class="flex gap-1.5 flex-wrap">
        <button
          v-for="cat in CATEGORIES"
          :key="cat"
          class="px-3 py-1 text-xs border transition-colors"
          :class="activeCategory === cat
            ? 'bg-emerald-500/10 border-emerald-600 dark:border-emerald-500/40 text-emerald-700 dark:text-emerald-400'
            : 'bg-surface-elevated border-border text-fg-muted hover:text-fg-primary hover:border-input'"
          @click="activeCategory = cat"
        >
          {{ cat }}
        </button>
      </div>

      <!-- Expand/collapse all -->
      <button
        class="flex items-center gap-1.5 px-3 py-1 text-xs border border-border bg-surface-elevated text-fg-muted hover:text-fg-primary hover:border-input transition-colors shrink-0"
        @click="toggleAllExpanded"
      >
        <ChevronDownIcon
          class="w-3 h-3 transition-transform duration-200"
          :class="allExpanded ? 'rotate-180' : ''"
          aria-hidden="true"
        />
        {{ allExpanded ? 'Collapse all' : 'Expand all' }}
      </button>
    </div>

    <!-- Grid -->
    <div class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4">
      <div
        v-for="tool in filteredTools"
        :key="tool.name"
        class="bg-surface-elevated border border-border flex flex-col"
      >
        <!-- Card header -->
        <div class="p-4 flex items-start gap-3">
          <!-- Icon -->
          <div
            class="w-9 h-9 rounded flex items-center justify-center shrink-0"
            :class="tool.meta.iconBg"
          >
            <component
              :is="iconFor(tool.meta.icon)"
              class="w-5 h-5"
              :class="tool.meta.iconColor"
              aria-hidden="true"
            />
          </div>

          <!-- Name + category badge -->
          <div class="flex-1 min-w-0">
            <span class="text-sm font-mono font-semibold text-fg-strong truncate block">
              {{ tool.name }}
            </span>
            <span
              class="mt-1.5 inline-block text-[10px] font-medium px-1.5 py-px rounded-sm leading-tight"
              :class="tool.meta.categoryColor"
            >
              {{ tool.meta.category }}
            </span>
          </div>
        </div>

        <!-- Description — fixed height so Functions header aligns across all cards in a row -->
        <p class="px-4 pb-4 text-xs text-fg-muted leading-relaxed min-h-[4rem] line-clamp-3">
          {{ tool.meta.shortDescription }}
        </p>

        <!-- Functions accordion header -->
        <button
          class="px-4 py-2.5 border-t border-border flex items-center justify-between w-full group transition-colors"
          :class="expandedSet.has(tool.name) ? 'bg-muted' : 'hover:bg-muted'"
          @click="toggleExpand(tool.name)"
        >
          <div class="flex items-center gap-2">
            <span class="text-[11px] font-medium text-fg-muted group-hover:text-fg-primary transition-colors">
              Functions
            </span>
            <span class="text-[10px] text-fg-primary bg-muted px-1.5 py-px rounded tabular-nums">
              {{ tool.meta.functions.length }}
            </span>
          </div>
          <ChevronDownIcon
            class="w-3.5 h-3.5 text-fg-muted group-hover:text-fg-muted transition-all duration-200 shrink-0"
            :class="expandedSet.has(tool.name) ? 'rotate-180' : ''"
            aria-hidden="true"
          />
        </button>

        <!-- Functions panel -->
        <div
          v-if="expandedSet.has(tool.name)"
          class="border-t border-border"
        >
          <div
            v-for="fn in tool.meta.functions"
            :key="fn.name"
            class="px-4 py-2 flex items-start gap-3 border-b border-border last:border-b-0"
          >
            <code class="text-[10px] font-mono text-emerald-700 dark:text-emerald-400/80 shrink-0 mt-px w-32 truncate">{{ fn.name }}</code>
            <span class="text-[10px] text-fg-muted leading-relaxed">{{ fn.description }}</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
