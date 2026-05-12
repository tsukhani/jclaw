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
  PuzzlePieceIcon,
} from '@heroicons/vue/24/outline'
import type { FunctionalComponent } from 'vue'
import type { ToolAction, ToolCategory, ToolMeta } from '~/composables/useToolMeta'

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
  plug: PuzzlePieceIcon,
}
function iconFor(name: string): FunctionalComponent {
  return TOOL_ICONS[name] ?? DocumentTextIcon
}

// JCLAW-173: this page is a read-only catalog. Per-tool enable/disable
// lives on the agent detail page (pages/agents.vue) — the tool registry
// itself is global, but binding a tool to a specific agent is the only
// meaningful axis. The previous "global toggle" was a fan-out that wrote
// to every agent's AgentToolConfig row, which was a leaky abstraction.
const { TOOL_META, ORDERED_TOOLS, refresh: refreshTools } = useToolMeta()

// Force a refetch on every visit so the MCP tab reflects live server
// state. Without this the module-level cache means an operator who
// disabled an MCP server in another tab still sees its card here until
// a hard reload.
onMounted(() => {
  refreshTools()
})

const CATEGORIES = ['All', 'System', 'Web', 'Files', 'Utilities', 'MCP'] as const

// ─── Derived lists ─────────────────────────────────────────────────────────────

/**
 * One renderable card on the /tools page. Native tools yield exactly one
 * card per tool; MCP tools sharing a {@code group} (the server name) fold
 * into a single card whose Functions disclosure lists every tool that
 * server advertises. The LLM-facing tool catalog is unaffected — every
 * MCP tool remains its own callable entry; this is purely UI grouping.
 */
interface ToolCard {
  key: string
  displayName: string
  category: ToolCategory
  iconBg: string
  iconColor: string
  iconKey: string
  description: string
  functions: ToolAction[]
}

function cardFromSingleTool(name: string, meta: ToolMeta): ToolCard {
  return {
    key: name,
    displayName: name,
    category: meta.category,
    iconBg: meta.iconBg,
    iconColor: meta.iconColor,
    iconKey: meta.icon,
    description: meta.shortDescription,
    functions: meta.functions,
  }
}

function cardFromGroup(groupName: string, members: [ToolMeta, ...ToolMeta[]]): ToolCard {
  const [first, ...rest] = members
  const folded: ToolAction[] = [...first.functions]
  for (const m of rest) folded.push(...m.functions)
  return {
    key: `group:${groupName}`,
    displayName: groupName,
    category: first.category,
    iconBg: first.iconBg,
    iconColor: first.iconColor,
    iconKey: first.icon,
    description: members.length === 1
      ? first.shortDescription
      : `MCP server connection. ${members.length} tools available.`,
    functions: folded,
  }
}

const allCards = computed<ToolCard[]>(() => {
  // Walk ORDERED_TOOLS to preserve registration order. Group on the fly so
  // the first appearance of a group fixes its slot in the output sequence.
  const groups = new Map<string, [ToolMeta, ...ToolMeta[]]>()
  const groupOrder: string[] = []
  const cards: ToolCard[] = []
  for (const name of ORDERED_TOOLS.value) {
    const meta = TOOL_META.value[name]
    if (!meta) continue
    if (meta.group) {
      const existing = groups.get(meta.group)
      if (existing) {
        existing.push(meta)
      }
      else {
        groups.set(meta.group, [meta])
        groupOrder.push(meta.group)
      }
    }
    else {
      cards.push(cardFromSingleTool(name, meta))
    }
  }
  // Append one card per group, in first-appearance order. groupOrder only
  // contains keys we set above, so every lookup is non-null.
  for (const g of groupOrder) {
    const members = groups.get(g)
    if (members) cards.push(cardFromGroup(g, members))
  }
  return cards
})

const activeCategory = ref<typeof CATEGORIES[number]>('All')

const filteredCards = computed(() =>
  activeCategory.value === 'All'
    ? allCards.value
    : allCards.value.filter(c => c.category === activeCategory.value),
)

// ─── Expand/collapse ──────────────────────────────────────────────────────────

const expandedSet = ref(new Set<string>())

function toggleExpand(key: string) {
  const s = new Set(expandedSet.value)
  if (s.has(key)) s.delete(key)
  else s.add(key)
  expandedSet.value = s
}

const allExpanded = computed(() =>
  filteredCards.value.length > 0
  && filteredCards.value.every(c => expandedSet.value.has(c.key)),
)

function toggleAllExpanded() {
  if (allExpanded.value) {
    expandedSet.value = new Set()
  }
  else {
    expandedSet.value = new Set(filteredCards.value.map(c => c.key))
  }
}

// Per-category styles need to be looked up by name since cards may share
// a category. Re-derived from the same source useToolMeta exposes.
const CATEGORY_PILL: Record<ToolCategory, string> = {
  System: 'text-neutral-400 bg-neutral-800',
  Files: 'text-amber-400 bg-amber-500/15',
  Web: 'text-blue-400 bg-blue-500/15',
  Utilities: 'text-emerald-400 bg-emerald-500/15',
  MCP: 'text-violet-400 bg-violet-500/15',
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

    <!-- MCP-only empty state. Other categories ship native compile-time
         tools so they're never empty in practice; only MCP can be empty
         depending on operator configuration. Hint at the right page so
         the operator knows where to wire up a server. -->
    <div
      v-if="activeCategory === 'MCP' && filteredCards.length === 0"
      class="bg-surface-elevated border border-border p-8 text-center text-sm text-fg-muted"
    >
      No MCP tools available.
      <NuxtLink
        to="/mcp-servers"
        class="text-emerald-500 hover:text-emerald-400 underline underline-offset-2"
      >
        Connect a server
      </NuxtLink>
      to populate this tab.
    </div>

    <!-- Grid -->
    <div
      v-else
      class="grid grid-cols-1 md:grid-cols-2 xl:grid-cols-3 gap-4"
    >
      <div
        v-for="card in filteredCards"
        :key="card.key"
        class="bg-surface-elevated border border-border flex flex-col"
      >
        <!-- Card header -->
        <div class="p-4 flex items-start gap-3">
          <!-- Icon -->
          <div
            class="w-9 h-9 rounded flex items-center justify-center shrink-0"
            :class="card.iconBg"
          >
            <component
              :is="iconFor(card.iconKey)"
              class="w-5 h-5"
              :class="card.iconColor"
              aria-hidden="true"
            />
          </div>

          <!-- Name + category badge -->
          <div class="flex-1 min-w-0">
            <span class="text-sm font-mono font-semibold text-fg-strong truncate block">
              {{ card.displayName }}
            </span>
            <span
              class="mt-1.5 inline-block text-[10px] font-medium px-1.5 py-px rounded-sm leading-tight"
              :class="CATEGORY_PILL[card.category]"
            >
              {{ card.category }}
            </span>
          </div>
        </div>

        <!-- Description — fixed height so Functions header aligns across all cards in a row -->
        <p class="px-4 pb-4 text-xs text-fg-muted leading-relaxed min-h-[4rem] line-clamp-3">
          {{ card.description }}
        </p>

        <!-- Functions accordion header -->
        <button
          class="px-4 py-2.5 border-t border-border flex items-center justify-between w-full group transition-colors"
          :class="expandedSet.has(card.key) ? 'bg-muted' : 'hover:bg-muted'"
          @click="toggleExpand(card.key)"
        >
          <div class="flex items-center gap-2">
            <span class="text-[11px] font-medium text-fg-muted group-hover:text-fg-primary transition-colors">
              Functions
            </span>
            <span class="text-[10px] text-fg-primary bg-muted px-1.5 py-px rounded tabular-nums">
              {{ card.functions.length }}
            </span>
          </div>
          <ChevronDownIcon
            class="w-3.5 h-3.5 text-fg-muted group-hover:text-fg-muted transition-all duration-200 shrink-0"
            :class="expandedSet.has(card.key) ? 'rotate-180' : ''"
            aria-hidden="true"
          />
        </button>

        <!-- Functions panel -->
        <div
          v-if="expandedSet.has(card.key)"
          class="border-t border-border"
        >
          <div
            v-for="fn in card.functions"
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
