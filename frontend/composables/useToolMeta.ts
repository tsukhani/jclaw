/**
 * Tool-metadata composable — thin consumer over GET /api/tools/meta.
 *
 * The backend is the authoritative source for name / category / icon /
 * shortDescription / actions / requiresConfig / system (JCLAW-72). This
 * composable only owns purely-presentational concerns that have no business
 * on the JVM: Tailwind class names keyed by category, and the SVG <path>
 * string dictionary keyed by the backend-supplied icon name.
 *
 * Adding a new tool in app/tools/ is now a backend-only change. This file
 * should not need updates unless the four-category taxonomy grows or the
 * set of supported SVG icons expands.
 */

import { ref, computed, type Ref, type ComputedRef } from 'vue'

// ───── Types ─────────────────────────────────────────────────────────────

export interface ToolAction {
  name: string
  description: string
}

/** Shape returned by GET /api/tools/meta. */
export interface ToolApiMeta {
  name: string
  category: ToolCategory
  icon: string
  shortDescription: string
  system: boolean
  requiresConfig?: string
  /** Optional grouping key. Tools sharing the same group fold into a
   *  single /tools-page card with their actions concatenated. Used by
   *  MCP-discovered tools (group = MCP server name) so all tools from
   *  one server render together. {@code undefined} for native tools. */
  group?: string
  actions: ToolAction[]
}

export type ToolCategory = 'System' | 'Web' | 'Files' | 'Utilities' | 'MCP'

/** Shape consumed by the tools page — backend meta plus derived styling. */
export interface ToolMeta extends ToolApiMeta {
  categoryColor: string
  iconBg: string
  iconColor: string
  functions: ToolAction[] // legacy alias for actions; preserved for pages/tools.vue compatibility
}

// ───── Presentational mappings (frontend-only concerns) ──────────────────

const CATEGORY_STYLES: Record<ToolCategory, { categoryColor: string, iconBg: string, iconColor: string }> = {
  System: {
    categoryColor: 'text-neutral-400 bg-neutral-800',
    iconBg: 'bg-neutral-800',
    iconColor: 'text-neutral-300',
  },
  Files: {
    categoryColor: 'text-amber-400 bg-amber-500/15',
    iconBg: 'bg-amber-500/15',
    iconColor: 'text-amber-400',
  },
  Web: {
    categoryColor: 'text-blue-400 bg-blue-500/15',
    iconBg: 'bg-blue-500/15',
    iconColor: 'text-blue-400',
  },
  Utilities: {
    categoryColor: 'text-emerald-400 bg-emerald-500/15',
    iconBg: 'bg-emerald-500/15',
    iconColor: 'text-emerald-400',
  },
  // JCLAW-33: MCP tools live in a violet palette to distinguish "external
  // connection" from native categories. Each MCP-server-discovered tool is
  // wrapped by McpToolAdapter (backend) which reports category="MCP".
  MCP: {
    categoryColor: 'text-violet-400 bg-violet-500/15',
    iconBg: 'bg-violet-500/15',
    iconColor: 'text-violet-400',
  },
}

const PILL_CLASSES: Record<ToolCategory, string> = {
  System: 'bg-neutral-800 border-neutral-700/60 text-neutral-400',
  Files: 'bg-amber-500/10 border-amber-500/25 text-amber-400',
  Web: 'bg-blue-500/10 border-blue-500/25 text-blue-400',
  Utilities: 'bg-emerald-500/10 border-emerald-500/25 text-emerald-400',
  MCP: 'bg-violet-500/10 border-violet-500/25 text-violet-400',
}

export const TOOL_CATEGORIES = ['System', 'Files', 'Web', 'Utilities', 'MCP'] as const

// ───── Module-level cache ────────────────────────────────────────────────
// One fetch per page load, shared across every component that calls
// useToolMeta(). Re-entrant: concurrent callers during the first fetch all
// await the same in-flight promise.

const metaList: Ref<ToolApiMeta[]> = ref([])
let fetchPromise: Promise<ToolApiMeta[]> | null = null

async function ensureLoaded(): Promise<ToolApiMeta[]> {
  if (metaList.value.length > 0) return metaList.value
  if (!fetchPromise) {
    fetchPromise = $fetch<ToolApiMeta[]>('/api/tools/meta')
      .then((data) => {
        metaList.value = data ?? []
        return metaList.value
      })
      .catch(() => {
        // Network error — leave metaList empty so pages render a graceful
        // empty grid. Reset the promise so a retry on next call is possible.
        fetchPromise = null
        return [] as ToolApiMeta[]
      })
  }
  return fetchPromise
}

/**
 * Drop the module-level cache and re-fetch /api/tools/meta. The default
 * cache is sticky for the page lifetime (tools rarely change for native
 * categories), but pages that need to reflect MCP server toggles — chiefly
 * /tools and the agent detail page — should call this on mount so an
 * operator who flipped a server in another tab sees the change without
 * a full reload.
 */
async function refresh(): Promise<ToolApiMeta[]> {
  metaList.value = []
  fetchPromise = null
  return ensureLoaded()
}

function augment(api: ToolApiMeta): ToolMeta {
  const styles = CATEGORY_STYLES[api.category] ?? CATEGORY_STYLES.Utilities
  return {
    ...api,
    categoryColor: styles.categoryColor,
    iconBg: styles.iconBg,
    iconColor: styles.iconColor,
    functions: api.actions, // legacy name preserved for pages/tools.vue
  }
}

// ───── Public composable ────────────────────────────────────────────────

export function useToolMeta() {
  // Kick off the fetch eagerly so consumers don't have to remember to await.
  ensureLoaded()

  const TOOL_META: ComputedRef<Record<string, ToolMeta>> = computed(() => {
    const out: Record<string, ToolMeta> = {}
    for (const t of metaList.value) {
      out[t.name] = augment(t)
    }
    return out
  })

  const ORDERED_TOOLS: ComputedRef<string[]> = computed(() =>
    metaList.value.map(t => t.name),
  )

  function getToolMeta(name: string): ToolMeta | null {
    return TOOL_META.value[name] ?? null
  }

  function getPillClass(name: string): string {
    const category = TOOL_META.value[name]?.category
    return category ? PILL_CLASSES[category] : PILL_CLASSES.System
  }

  return {
    TOOL_META,
    ORDERED_TOOLS,
    getToolMeta,
    getPillClass,
    /** Awaitable — resolves once the /api/tools/meta fetch lands. */
    ready: ensureLoaded,
    /** Drop the cache and re-fetch. Call on mount of pages that need to
     *  reflect live MCP server state (e.g. /tools, agent detail). */
    refresh,
  }
}
